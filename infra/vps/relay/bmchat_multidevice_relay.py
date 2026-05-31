#!/usr/bin/env python3
"""
BMChat multi-device relay.

This service is intentionally dumb storage:
  * desktop creates a high-entropy session id and embeds it in a QR;
  * mobile exports a backup, encrypts it locally, uploads ciphertext here;
  * mobile displays a 6-digit confirmation code;
  * desktop downloads the ciphertext only after the user enters that code.

The relay never receives plaintext account data, mail credentials, or the
encryption key.  The session id is the bearer token, so it must be random
enough to be unguessable and every object expires quickly.

Endpoints (all under nginx /mdrelay/):
  POST /session
    body: {"sid":"...","expires_in":1800}
    creates an empty session.

  PUT /session/<sid>/blob?code_hash=<sha256>
    body: encrypted backup bytes.
    mobile uploads the encrypted payload and a hash of the confirmation code.

  GET /session/<sid>/status
    returns JSON with uploaded/size/code_hash/expires_at.

  GET /session/<sid>/blob
    returns encrypted backup bytes.

  DELETE /session/<sid>
    deletes the session after successful import or cancellation.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


LISTEN_HOST = os.environ.get("BMCHAT_MDRELAY_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("BMCHAT_MDRELAY_PORT", "8091"))
STORE_DIR = Path(os.environ.get("BMCHAT_MDRELAY_STORE", "/var/lib/bmchat-mdrelay"))
MAX_BLOB_BYTES = int(os.environ.get("BMCHAT_MDRELAY_MAX_BYTES", str(2 * 1024 * 1024 * 1024)))
DEFAULT_TTL_S = int(os.environ.get("BMCHAT_MDRELAY_TTL_S", "1800"))
MAX_TTL_S = int(os.environ.get("BMCHAT_MDRELAY_MAX_TTL_S", "7200"))

SID_RE = re.compile(r"^[A-Za-z0-9_-]{32,96}$")
HASH_RE = re.compile(r"^[a-fA-F0-9]{64}$")


def _now() -> int:
    return int(time.time())


def _json_response(handler: BaseHTTPRequestHandler, status: int, payload: dict) -> None:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Cache-Control", "no-store")
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _error(handler: BaseHTTPRequestHandler, status: int, message: str) -> None:
    _json_response(handler, status, {"ok": False, "error": message})


def _sid_path(sid: str) -> Path:
    return STORE_DIR / sid


def _meta_path(sid: str) -> Path:
    return _sid_path(sid) / "meta.json"


def _blob_path(sid: str) -> Path:
    return _sid_path(sid) / "payload.bin"


def _valid_sid(sid: str) -> bool:
    return bool(SID_RE.fullmatch(sid))


def _read_meta(sid: str) -> dict | None:
    try:
        meta = json.loads(_meta_path(sid).read_text("utf-8"))
    except FileNotFoundError:
        return None
    except Exception:
        return None
    if int(meta.get("expires_at", 0)) <= _now():
        shutil.rmtree(_sid_path(sid), ignore_errors=True)
        return None
    return meta


def _write_meta(sid: str, meta: dict) -> None:
    path = _meta_path(sid)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(meta, separators=(",", ":"), ensure_ascii=False), "utf-8")
    os.replace(tmp, path)


def _cleanup_expired() -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    now = _now()
    for child in STORE_DIR.iterdir():
        if not child.is_dir():
            continue
        try:
            meta = json.loads((child / "meta.json").read_text("utf-8"))
            if int(meta.get("expires_at", 0)) <= now:
                shutil.rmtree(child, ignore_errors=True)
        except Exception:
            # Broken partial sessions are useless; remove them.
            shutil.rmtree(child, ignore_errors=True)


class RelayHandler(BaseHTTPRequestHandler):
    server_version = "BMChatMultiDeviceRelay/1.0"

    def log_message(self, fmt: str, *args) -> None:  # noqa: N802 - stdlib signature
        print("%s - - [%s] %s" % (self.address_string(), self.log_date_time_string(), fmt % args))

    def do_OPTIONS(self) -> None:  # noqa: N802 - stdlib signature
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def do_POST(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urlparse(self.path)
        if parsed.path != "/session":
            return _error(self, 404, "not_found")
        _cleanup_expired()
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length > 16 * 1024:
            return _error(self, 413, "body_too_large")
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except Exception:
            return _error(self, 400, "bad_json")
        sid = str(body.get("sid", ""))
        if not _valid_sid(sid):
            return _error(self, 400, "bad_sid")
        ttl = int(body.get("expires_in", DEFAULT_TTL_S) or DEFAULT_TTL_S)
        ttl = max(60, min(ttl, MAX_TTL_S))
        spath = _sid_path(sid)
        spath.mkdir(parents=True, exist_ok=True)
        meta = {
            "sid": sid,
            "created_at": _now(),
            "expires_at": _now() + ttl,
            "uploaded": False,
            "size": 0,
            "code_hash": None,
        }
        _write_meta(sid, meta)
        _json_response(self, 201, {"ok": True, **meta})

    def do_PUT(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "session" or parts[2] != "blob":
            return _error(self, 404, "not_found")
        sid = parts[1]
        if not _valid_sid(sid):
            return _error(self, 400, "bad_sid")
        meta = _read_meta(sid)
        if not meta:
            return _error(self, 404, "session_not_found")
        qs = parse_qs(parsed.query)
        code_hash = (qs.get("code_hash") or [""])[0]
        if not HASH_RE.fullmatch(code_hash):
            return _error(self, 400, "bad_code_hash")
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0:
            return _error(self, 400, "empty_blob")
        if length > MAX_BLOB_BYTES:
            return _error(self, 413, "blob_too_large")
        dest = _blob_path(sid)
        with tempfile.NamedTemporaryFile("wb", dir=str(_sid_path(sid)), delete=False) as tmp:
            remaining = length
            while remaining > 0:
                chunk = self.rfile.read(min(1024 * 1024, remaining))
                if not chunk:
                    os.unlink(tmp.name)
                    return _error(self, 400, "short_upload")
                tmp.write(chunk)
                remaining -= len(chunk)
            tmp_name = tmp.name
        os.replace(tmp_name, dest)
        meta.update({"uploaded": True, "size": length, "code_hash": code_hash.lower()})
        _write_meta(sid, meta)
        _json_response(self, 201, {"ok": True, "uploaded": True, "size": length})

    def do_GET(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "session":
            return _error(self, 404, "not_found")
        sid, what = parts[1], parts[2]
        if not _valid_sid(sid):
            return _error(self, 400, "bad_sid")
        meta = _read_meta(sid)
        if not meta:
            return _error(self, 404, "session_not_found")
        if what == "status":
            return _json_response(self, 200, {"ok": True, **meta})
        if what != "blob":
            return _error(self, 404, "not_found")
        blob = _blob_path(sid)
        if not blob.exists():
            return _error(self, 404, "blob_not_uploaded")
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(blob.stat().st_size))
        self.end_headers()
        with blob.open("rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def do_DELETE(self) -> None:  # noqa: N802 - stdlib signature
        parsed = urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 2 or parts[0] != "session":
            return _error(self, 404, "not_found")
        sid = parts[1]
        if not _valid_sid(sid):
            return _error(self, 400, "bad_sid")
        shutil.rmtree(_sid_path(sid), ignore_errors=True)
        _json_response(self, 200, {"ok": True})


def main() -> None:
    STORE_DIR.mkdir(parents=True, exist_ok=True)
    _cleanup_expired()
    httpd = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), RelayHandler)
    print(f"[bmchat-mdrelay] listening on {LISTEN_HOST}:{LISTEN_PORT}, store={STORE_DIR}")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
