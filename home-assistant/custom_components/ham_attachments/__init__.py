"""Authenticated, private image storage for HAM calendar references.

No static directory, unauthenticated link, or Home Assistant calendar is created here.
"""

from __future__ import annotations

import hashlib
import json
import os
import uuid
from pathlib import Path

from aiohttp import web
from homeassistant.components.http import KEY_HASS, KEY_HASS_USER, HomeAssistantView
from homeassistant.helpers import config_validation as cv

DOMAIN = "ham_attachments"
MAX_BYTES = 10 * 1024 * 1024
CONFIG_SCHEMA = cv.empty_config_schema(DOMAIN)


def _image_type(body: bytes) -> str | None:
    if body.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if body.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if body.startswith(b"RIFF") and body[8:12] == b"WEBP":
        return "image/webp"
    return None


def _attachment_path(hass, item_id: str) -> Path:
    return Path(hass.config.path(".storage", "ham_attachments", item_id))


class AttachmentCollection(HomeAssistantView):
    url = "/api/ham_attachments"
    name = "api:ham_attachments:collection"
    requires_auth = True

    async def post(self, request: web.Request) -> web.Response:
        # Read at most MAX_BYTES + 1 so a malicious Content-Length cannot exhaust memory.
        chunks = bytearray()
        async for chunk in request.content.iter_chunked(65536):
            chunks.extend(chunk)
            if len(chunks) > MAX_BYTES:
                raise web.HTTPRequestEntityTooLarge(max_size=MAX_BYTES, actual_size=len(chunks))
        body = bytes(chunks)
        if not body or len(body) > MAX_BYTES:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BYTES, actual_size=len(body))
        content_type = _image_type(body)
        if content_type is None:
            raise web.HTTPUnsupportedMediaType(text="PNG, JPEG, or WebP required")
        hass = request.app[KEY_HASS]
        item_id = str(uuid.uuid4())
        path = _attachment_path(hass, item_id)
        await hass.async_add_executor_job(path.parent.mkdir, 0o700, True, True)

        def write() -> None:
            temp = path.with_suffix(".tmp")
            with open(temp, "xb") as output:
                output.write(body)
            os.chmod(temp, 0o600)
            os.replace(temp, path)
            metadata = path.with_suffix(".json")
            metadata.write_text(json.dumps({
                "content_type": content_type,
                "sha256": hashlib.sha256(body).hexdigest(),
                "owner": request[KEY_HASS_USER].id,
            }), encoding="utf-8")
            os.chmod(metadata, 0o600)

        await hass.async_add_executor_job(write)
        return self.json({"id": item_id, "sha256": hashlib.sha256(body).hexdigest()}, status_code=201)


class AttachmentItem(HomeAssistantView):
    url = "/api/ham_attachments/{item_id}"
    name = "api:ham_attachments:item"
    requires_auth = True

    async def get(self, request: web.Request, item_id: str) -> web.Response:
        path = _attachment_path(request.app[KEY_HASS], self._valid(item_id))

        def read():
            return path.read_bytes(), json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))

        try:
            body, meta = await request.app[KEY_HASS].async_add_executor_job(read)
        except (FileNotFoundError, ValueError):
            raise web.HTTPNotFound() from None
        if meta.get("owner") != request[KEY_HASS_USER].id:
            raise web.HTTPForbidden()
        return web.Response(body=body, content_type=meta["content_type"], headers={
            "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff",
            "Content-Disposition": "inline; filename=ham-screenshot",
        })

    async def delete(self, request: web.Request, item_id: str) -> web.Response:
        path = _attachment_path(request.app[KEY_HASS], self._valid(item_id))

        def remove() -> None:
            meta = json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
            if meta["owner"] != request[KEY_HASS_USER].id:
                raise PermissionError()
            path.unlink()
            path.with_suffix(".json").unlink()

        try:
            await request.app[KEY_HASS].async_add_executor_job(remove)
        except FileNotFoundError:
            raise web.HTTPNotFound() from None
        except PermissionError:
            raise web.HTTPForbidden() from None
        return web.Response(status=204)

    @staticmethod
    def _valid(item_id: str) -> str:
        try:
            return str(uuid.UUID(item_id)) if str(uuid.UUID(item_id)) == item_id else _bad_id()
        except ValueError:
            raise web.HTTPBadRequest(text="Invalid attachment ID") from None


def _bad_id() -> str:
    raise web.HTTPBadRequest(text="Invalid attachment ID")


async def async_setup(hass, config):
    hass.http.register_view(AttachmentCollection)
    hass.http.register_view(AttachmentItem)
    return True
