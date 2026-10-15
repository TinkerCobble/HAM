"""Fail closed on known HAM privacy regressions; manual runtime review remains required."""

import argparse
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
EXPECTED_PERMISSIONS = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
EXPECTED_LIBRARIES = {
    "androidx-core-ktx", "androidx-activity-compose", "androidx-lifecycle-runtime-ktx",
    "androidx-lifecycle-viewmodel-compose", "androidx-compose-bom", "androidx-compose-ui",
    "androidx-compose-ui-tooling-preview", "androidx-compose-material3",
    "androidx-compose-foundation", "androidx-compose-ui-tooling", "junit", "okhttp",
}
ALLOWED_HOSTS = {"tinkercobble.com", "your-home-assistant.example"}


def check() -> list[str]:
    errors = []
    manifest = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
    permissions = set(re.findall(r'<uses-permission\s+android:name="([^"]+)"', manifest))
    if permissions != EXPECTED_PERMISSIONS:
        errors.append(f"Android permission drift: {sorted(permissions)}")
    if 'android:allowBackup="false"' not in manifest:
        errors.append("Android backup setting changed; review local-data policy")
    if 'android:exported="false"' not in manifest or 'HomeAssistantLoginActivity' not in manifest:
        errors.append("Authorization activity exposure changed")

    for flavor in ("dev", "stableDirect"):
        direct_manifest = (ROOT / "app/src" / flavor / "AndroidManifest.xml").read_text(encoding="utf-8")
        if 'android.permission.REQUEST_INSTALL_PACKAGES' not in direct_manifest or 'androidx.core.content.FileProvider' not in direct_manifest:
            errors.append(f"Direct installer declaration missing from {flavor}")
    for flavor in ("stablePlay", "stableFdroid"):
        flavor_dir = ROOT / "app/src" / flavor
        if any('REQUEST_INSTALL_PACKAGES' in path.read_text(encoding="utf-8") for path in flavor_dir.rglob("*.xml")):
            errors.append(f"Direct installer permission leaked into {flavor}")
    for name in ("stablePlay", "stableFdroid"):
        if (ROOT / "app/src" / name / "java/com/tinkercobble/ham/updates/DirectUpdatePolicy.kt").exists():
            errors.append(f"Direct updater leaked into {name}")

    catalog = (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8")
    libraries = set(re.findall(r'^([a-z][a-z0-9-]+)\s*=\s*\{\s*module\s*=', catalog, re.M))
    if libraries != EXPECTED_LIBRARIES:
        errors.append(f"Dependency inventory changed: {sorted(libraries ^ EXPECTED_LIBRARIES)}")

    sources = {p: p.read_text(encoding="utf-8") for flavor in ("main", "dev", "stableDirect", "stablePlay", "stableFdroid")
               for p in (ROOT / "app/src" / flavor / "java").rglob("*.kt")}
    for path, source in sources.items():
        for match in re.finditer(r'https?://([A-Za-z0-9.-]+)', source):
            if match.group(1).lower() not in ALLOWED_HOSTS:
                errors.append(f"New hardcoded network host in {path.relative_to(ROOT)}: {match.group(1)}")
        if re.search(r'\b(Log\.(?:d|i|w|e|v)|Timber\.|FirebaseAnalytics|Crashlytics|AdvertisingIdClient)\b', source):
            errors.append(f"Potential logging/tracking added in {path.relative_to(ROOT)}")
    auth = (APP / "java/com/tinkercobble/ham/auth/HomeAssistantAuth.kt").read_text(encoding="utf-8")
    callback = (APP / "java/com/tinkercobble/ham/auth/AuthIdentity.kt").read_text(encoding="utf-8")
    client = (APP / "java/com/tinkercobble/ham/data/HomeAssistantClient.kt").read_text(encoding="utf-8")
    session = (APP / "java/com/tinkercobble/ham/auth/SecureSession.kt").read_text(encoding="utf-8")
    login = (APP / "java/com/tinkercobble/ham/HomeAssistantLoginActivity.kt").read_text(encoding="utf-8")
    for label, okay in {
        "application-specific OAuth identity": 'https://tinkercobble.com/ham/' in callback,
        "OAuth state validation": 'expectedState' in callback and 'params.count { it.first == "state" } == 1' in callback,
        "no REST bearer redirect": 'instanceFollowRedirects = false' in client,
        "no WebSocket bearer redirect": 'followRedirects(false).followSslRedirects(false)' in client,
        "encrypted session": 'AES/GCM/NoPadding' in session,
        "revocation failure result": 'requestRevocation(session: Session): Boolean' in auth,
        "authorization WebView cleanup": 'removeAllCookies' in login and 'deleteAllData' in login,
        "privacy data-flow inventory": (ROOT / "docs/PRIVACY_DATA_FLOW.md").exists(),
        "privacy draft": (ROOT / "docs/legal/PRIVACY_POLICY.md").exists(),
        "terms draft": (ROOT / "docs/legal/TERMS_OF_USE.md").exists(),
    }.items():
        if not okay:
            errors.append(f"Missing or changed {label}; review before release")
    return errors


def check_public() -> list[str]:
    errors = []
    for path in ("privacy/", "terms/"):
        url = f"https://tinkercobble.com/ham/{path}"
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "HAM-privacy-check"}), timeout=15) as response:
                html = response.read().decode("utf-8")
                if response.status != 200 or "HAM" not in html or "mailto:" not in html:
                    errors.append(f"Public legal page incomplete: {url}")
        except (urllib.error.URLError, TimeoutError, UnicodeError) as exc:
            errors.append(f"Public legal page unavailable: {url} ({type(exc).__name__})")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--public", action="store_true", help="also require live public legal pages")
    args = parser.parse_args()
    problems = check() + (check_public() if args.public else [])
    for problem in problems:
        print("FAIL:", problem)
    if problems:
        sys.exit(1)
    print("Source privacy checks passed. Runtime network and policy review remain manual gates.")
