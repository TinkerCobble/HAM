"""Fail-closed privacy preflight for the exact HAM release files and website pages.

This catches selected exposure patterns; a human must still review images, data flow,
and any new destinations. Findings are deliberately printed without matched values.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import re
import sys
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "release/privacy-artifacts.json"
BASE = "https://tinkercobble.com"
PAGE_PATHS = (
    "ham/", "ham/privacy/", "ham/terms/", "ham/dev-update.json", "styles.css",
    "downloads/ham/ham-0.1.12-dev-code13.apk.sha256",
    "downloads/ham/ham-0.1.12-dev-code13-source.zip.sha256",
)
MAX_DOWNLOAD = 80 * 1024 * 1024
MAX_UNCOMPRESSED = 200 * 1024 * 1024
PATTERNS = {
    "local user path": re.compile(rb"[A-Za-z]:[\\/]Users[\\/]|/Users/[A-Za-z0-9_.-]+/", re.I),
    "private network address": re.compile(rb"(?<![0-9])(?:10\.[0-9]{1,3}\.[0-9]{1,3}|172\.(?:1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}|192\.168\.[0-9]{1,3})\.[0-9]{1,3}(?![0-9])"),
    "private key": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "access key": re.compile(rb"AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,}"),
}


def fetch(url: str) -> bytes:
    if not url.startswith(BASE + "/"):
        raise ValueError("Artifact URL is outside the approved HAM website")
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "HAM-privacy-preflight"}), timeout=30) as response:
        if response.status != 200:
            raise ValueError("Artifact URL did not return HTTP 200")
        if not response.geturl().startswith(BASE + "/"):
            raise ValueError("Artifact URL redirected outside the approved HAM website")
        data = response.read(MAX_DOWNLOAD + 1)
    if not data or len(data) > MAX_DOWNLOAD:
        raise ValueError("Artifact is missing or exceeds the review size limit")
    return data


def check_bytes(label: str, data: bytes, errors: list[str], *, scan_network: bool = True) -> None:
    for kind, pattern in PATTERNS.items():
        if kind == "private network address" and not scan_network:
            continue
        if pattern.search(data):
            errors.append(f"{label}: possible {kind} (value withheld)")


def check_zip(label: str, data: bytes, errors: list[str], *, apk: bool) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if sum(item.file_size for item in entries) > MAX_UNCOMPRESSED:
            errors.append(f"{label}: uncompressed contents exceed the review size limit")
            return
        if not entries or archive.testzip() is not None:
            errors.append(f"{label}: empty or damaged archive")
            return
        if len({item.filename for item in entries}) != len(entries):
            errors.append(f"{label}: duplicate archive filenames")
        if apk and not {"classes.dex", "AndroidManifest.xml"}.issubset({item.filename for item in entries}):
            errors.append("APK: Android contents missing")
        for item in entries:
            name = item.filename
            if item.is_dir():
                continue
            if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts:
                errors.append(f"{label}: unsafe archive path")
                continue
            if re.search(r"(?i)(?:^|/)(?:backup|secrets?)(?:/|\.)|\.(?:db|sqlite|jks|keystore|pem|log)$", name):
                errors.append(f"{label}: unexpected private-file type at {name}")
            if item.file_size > MAX_DOWNLOAD:
                errors.append(f"{label}: oversized member at {name}")
                continue
            content = archive.read(item)
            check_bytes(f"{label}:{name}", content, errors, scan_network=apk)
            if name.lower().endswith(".png") and any(tag in content for tag in (b"eXIf", b"tEXt", b"iTXt")):
                errors.append(f"{label}:{name}: PNG metadata needs private review")
            if name.lower().endswith((".jpg", ".jpeg", ".webp")) and b"Exif\x00\x00" in content:
                errors.append(f"{label}:{name}: image EXIF needs private review")


def audit(apk: bytes, source: bytes, pages: dict[str, bytes], lock: dict) -> list[str]:
    errors: list[str] = []
    for label, data, expected in (("APK", apk, lock["apk_sha256"]), ("source ZIP", source, lock["source_sha256"])):
        if hashlib.sha256(data).hexdigest() != expected.lower():
            errors.append(f"{label}: SHA-256 differs from pinned release")
        try:
            check_zip(label, data, errors, apk=label == "APK")
        except zipfile.BadZipFile:
            errors.append(f"{label}: invalid ZIP")
    for path in PAGE_PATHS:
        data = pages.get(path)
        if not data:
            errors.append(f"Website {path}: missing (check skipped)")
            continue
        check_bytes(f"Website {path}", data, errors)
    product = pages.get("ham/", b"")
    if lock["apk_url"].removeprefix(BASE).encode() not in product:
        errors.append("Website HAM page: download link does not match pinned APK")
    if lock["source_url"].removeprefix(BASE).encode() not in product:
        errors.append("Website HAM page: source link does not match pinned archive")
    feed = pages.get("ham/dev-update.json", b"")
    try:
        update = json.loads(feed)
        if update.get("apkUrl") != lock["apk_url"] or update.get("sha256", "").lower() != lock["apk_sha256"].lower():
            errors.append("DEV update feed: URL or checksum differs from pinned APK")
        if update.get("versionCode") != lock["version_code"] or update.get("packageName") != lock["package"]:
            errors.append("DEV update feed: package or version differs from pinned release")
    except (ValueError, TypeError):
        errors.append("DEV update feed: invalid JSON")
    for path in ("ham/privacy/", "ham/terms/"):
        if b"HAM" not in pages.get(path, b"") or b"mailto:" not in pages.get(path, b""):
            errors.append(f"Website {path}: legal/contact content missing")
    for path, expected in ((PAGE_PATHS[-2], lock["apk_sha256"]), (PAGE_PATHS[-1], lock["source_sha256"])):
        if expected.lower().encode() not in pages.get(path, b"").lower():
            errors.append(f"Website {path}: published checksum differs from pinned artifact")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--public", action="store_true", help="verify the exact live pinned release")
    mode.add_argument("--staged", action="store_true", help="verify candidate files before publication")
    parser.add_argument("--apk", type=pathlib.Path)
    parser.add_argument("--source-zip", type=pathlib.Path)
    parser.add_argument("--website-dir", type=pathlib.Path)
    args = parser.parse_args()
    try:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        if args.public:
            apk = fetch(lock["apk_url"])
            source = fetch(lock["source_url"])
            pages = {path: fetch(f"{BASE}/{path}") for path in PAGE_PATHS}
        else:
            if not all((args.apk, args.source_zip, args.website_dir)):
                parser.error("--staged requires --apk, --source-zip, and --website-dir")
            apk = args.apk.read_bytes()
            source = args.source_zip.read_bytes()
            pages = {path: (args.website_dir / path / "index.html" if path.endswith("/")
                            else args.website_dir / path).read_bytes() for path in PAGE_PATHS}
        problems = audit(apk, source, pages, lock)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print(f"FAIL: privacy preflight could not complete ({type(exc).__name__})")
        return 1
    for problem in problems:
        print("FAIL:", problem)
    if problems:
        return 1
    print("Exact APK, source ZIP, HAM pages, and DEV feed passed the artifact preflight; manual review remains required.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
