#!/usr/bin/env python3
"""
BMChat Telegram media proxy.

Receives signed URLs of the form

    /tgmedia/<urlsafe-base64( nonce(12) || aes-gcm-ciphertext-with-tag )>

The Android client encrypts a JSON payload containing the bot token,
the Telegram file_id, the original MIME type and an expiration
timestamp under a shared secret. This service decrypts, validates the
expiration, performs the standard Telegram Bot API getFile + download
calls and streams the resulting bytes back to the caller.

Why proxy in the first place?

* Telegram's cloud Bot API caps getFile + file download at ~20 MB.
  Forwarded videos and most non-trivial documents therefore cannot be
  pulled in by the BMChat Android client directly. Previously the
  client published a "[медиа video не удалось скачать]" placeholder
  and lost the actual content (May 11 user report).

* We never want plaintext bot tokens in URLs that travel through
  recipients' chat history, so the URL itself is encrypted, not just
  signed. Forwarding a stale URL after expiry yields 410 Gone.

The service is deliberately tiny and stateless — no DB, no caches.
It's started by systemd (bmchat-tgproxy.service) and reverse-proxied
by nginx on /tgmedia/.

This file is read by /etc/systemd/system/bmchat-tgproxy.service via
ExecStart=/usr/bin/python3 /opt/bmchat/bmchat_tgproxy.py.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover
    print(
        "[bmchat-tgproxy] FATAL: 'cryptography' package missing. "
        "Run `apt install python3-cryptography` or `pip3 install cryptography`.",
        file=sys.stderr,
    )
    sys.exit(2)

import urllib.request
import urllib.error


# Shared secret — MUST match TelegramProxy#SECRET on the Android client.
# Rotation policy: change both sides in lock-step and ship a new APK.
SECRET = (
    "9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f"
    "9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b"
)
KEY = hashlib.sha256(SECRET.encode("utf-8")).digest()
AES = AESGCM(KEY)

LISTEN_HOST = os.environ.get("BMCHAT_TGPROXY_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("BMCHAT_TGPROXY_PORT", "8090"))

# Telegram Bot API base. Switching this to a self-hosted local
# server (https://github.com/tdlib/telegram-bot-api running with
# --local) lifts the 20 MB getFile cap — and changes the semantics
# of getFile responses to return absolute file system paths
# instead of CDN-relative URLs (cf. _stream_file below for the
# branch that handles either shape).
API_BASE = os.environ.get(
    "BMCHAT_TGPROXY_API_BASE", "https://api.telegram.org"
)

# True when we're talking to a tdlib `--local` server. We detect
# this both by URL prefix and by the absolute-path file_path that
# getFile returns; this env flag is just the preferred default.
API_LOCAL_MODE = os.environ.get("BMCHAT_TGPROXY_API_LOCAL", "0") == "1"

# Whether to ask the bot API server to forget the cached file after
# we finish streaming it. Saves disk on the VPS (local mode caches
# every downloaded file under DATA_DIR) — disable temporarily if
# you want to inspect raw payloads.
API_DELETE_AFTER_STREAM = os.environ.get(
    "BMCHAT_TGPROXY_DELETE_AFTER_STREAM", "1"
) == "1"

# How long the proxy is willing to hold an upstream connection open.
# Long enough for cold-cache 4K videos, short enough that a stuck
# upstream doesn't pile up gunicorn workers.
UPSTREAM_TIMEOUT_S = 120

USER_AGENT = "BMChat-TG-Proxy/1.0 (+https://5.187.4.132/)"


def _b64url_decode(blob: str) -> bytes:
    pad = "=" * (-len(blob) % 4)
    return base64.urlsafe_b64decode(blob + pad)


def _decrypt_payload(token: str) -> Optional[dict]:
    raw = _b64url_decode(token)
    if len(raw) < 13:  # 12-byte nonce + ≥1 byte of ciphertext
        return None
    nonce, ct = raw[:12], raw[12:]
    plaintext = AES.decrypt(nonce, ct, None)
    return json.loads(plaintext.decode("utf-8"))


def _redact_token(token: str) -> str:
    """Mask the bot token in URLs we expose via /diag. Keep enough
    prefix that the user can tell *which* bot was probed (the part
    before the colon is the numeric bot id and is not secret), but
    redact the actual secret material so a leaked /diag response
    doesn't hand someone a working bot session."""
    if not token:
        return ""
    if ":" in token:
        bot_id, _, _ = token.partition(":")
        return f"{bot_id}:<redacted>"
    return "<redacted>"


def _raw_http_json(url: str, method: str = "GET") -> dict:
    """Issue a single HTTP request to Telegram, capture status + body
    + headers regardless of HTTP outcome, and parse the JSON if it
    parses. Diagnostic helper — surfaces the raw exchange to /diag
    callers so they can verify exactly where a request was rejected
    without re-running curl by hand."""
    req = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT}, method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT_S) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            status = resp.status
            headers = dict(resp.headers.items())
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", errors="replace")
        except Exception:
            body = "(no body)"
        status = e.code
        headers = dict(e.headers.items()) if e.headers else {}
    except urllib.error.URLError as e:
        return {"transport_error": str(e.reason)}
    parsed: dict
    try:
        parsed = json.loads(body)
    except Exception:
        parsed = {"raw_body": body[:600]}
    return {
        "http_status": status,
        "response_headers": {
            k: v for k, v in headers.items()
            if k.lower() in ("content-type", "content-length", "accept-ranges",
                              "server", "date")
        },
        "telegram_response": parsed,
    }


def _raw_head(url: str) -> dict:
    """HEAD probe — used to see whether the file CDN returns metadata
    (Content-Length, Accept-Ranges) without actually pulling the
    bytes through the proxy."""
    req = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT}, method="HEAD",
    )
    try:
        with urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT_S) as resp:
            return {
                "http_status": resp.status,
                "response_headers": {
                    k: v for k, v in resp.headers.items()
                    if k.lower() in ("content-type", "content-length",
                                      "accept-ranges", "server", "date")
                },
            }
    except urllib.error.HTTPError as e:
        return {"http_status": e.code, "error_body": (e.read() or b"").decode("utf-8", "replace")[:400]}
    except urllib.error.URLError as e:
        return {"transport_error": str(e.reason)}


def _raw_get_file(bot_token: str, file_id: str) -> dict:
    """Call Telegram's getFile and return a structured result regardless
    of whether the HTTP request succeeded. Used both by the streaming
    path (which only needs file_path) and the /tgmedia/<…>?diag=1
    endpoint (which surfaces the raw response to the end user so they
    can confirm where exactly the request was rejected)."""
    url = f"{API_BASE}/bot{bot_token}/getFile?file_id={urllib.parse.quote(file_id)}"
    res = _raw_http_json(url)
    res["api_base"] = API_BASE
    res["request_url"] = url.replace(bot_token, _redact_token(bot_token))
    return res


def _fetch_file_path(bot_token: str, file_id: str) -> Optional[str]:
    """Resolve a Telegram file_id to its CDN-relative path via getFile."""
    res = _raw_get_file(bot_token, file_id)
    tg = res.get("telegram_response") or {}
    if not isinstance(tg, dict) or not tg.get("ok"):
        sys.stderr.write(
            f"[bmchat-tgproxy] getFile failed: {json.dumps(res)[:400]}\n"
        )
        return None
    result = tg.get("result") or {}
    return result.get("file_path")


def _delete_local_file(bot_token: str, file_id: str) -> None:
    """Ask the local Bot API server to drop its on-disk cache for the
    given file_id. Cheap fire-and-forget — never fails the stream.
    No-op for cloud Bot API (the cloud doesn't expose deleteFile)."""
    if not API_DELETE_AFTER_STREAM:
        return
    if not API_BASE.startswith("http://127.") and not API_BASE.startswith("http://localhost"):
        # Only local servers expose `deleteFile`. Calling it against
        # api.telegram.org would just 404, but we'd rather skip the
        # extra HTTP request entirely on the cloud path.
        return
    try:
        url = f"{API_BASE}/bot{bot_token}/deleteFile?file_id={urllib.parse.quote(file_id)}"
        urllib.request.urlopen(
            urllib.request.Request(url, method="POST"), timeout=10,
        ).read()
    except Exception:  # pragma: no cover
        pass


def _stream_local_file(handler: BaseHTTPRequestHandler,
                       fs_path: str,
                       mime: Optional[str], name: Optional[str]) -> None:
    """Stream a file off the local file system (telegram-bot-api
    --local mode writes downloads to disk and returns their absolute
    path from getFile). Supports HTTP Range so the in-chat player can
    seek and progressively load multi-GB videos.
    """
    try:
        stat = os.stat(fs_path)
    except OSError:
        handler.send_error(502, "local file vanished before streaming")
        return
    total = stat.st_size

    # Parse a possible Range header from the client. We support the
    # single-range, byte-form: "bytes=START-END" or "bytes=START-".
    start, end = 0, total - 1
    range_header = handler.headers.get("Range")
    is_partial = False
    if range_header and range_header.startswith("bytes="):
        spec = range_header[len("bytes="):].split(",", 1)[0].strip()
        try:
            s, _, e = spec.partition("-")
            if s != "":
                start = int(s)
            if e != "":
                end = int(e)
            if start < 0 or end >= total or start > end:
                handler.send_response(416)
                handler.send_header("Content-Range", f"bytes */{total}")
                handler.end_headers()
                return
            is_partial = True
        except ValueError:
            # Malformed Range — fall back to a full response, as RFC 7233
            # permits. Players never send malformed ranges in practice
            # but we'd rather not 400 than confuse them.
            start, end = 0, total - 1
            is_partial = False

    content_length = end - start + 1
    handler.send_response(206 if is_partial else 200)
    handler.send_header("Content-Type", mime or "application/octet-stream")
    handler.send_header("Content-Length", str(content_length))
    handler.send_header("Accept-Ranges", "bytes")
    if is_partial:
        handler.send_header("Content-Range", f"bytes {start}-{end}/{total}")
    if name:
        safe_name = name.replace('"', "_")
        handler.send_header(
            "Content-Disposition", f"inline; filename=\"{safe_name}\""
        )
    handler.end_headers()

    if handler.command == "HEAD":
        return

    try:
        with open(fs_path, "rb") as f:
            if start:
                f.seek(start)
            remaining = content_length
            chunk = 256 * 1024
            while remaining > 0:
                buf = f.read(min(chunk, remaining))
                if not buf:
                    break
                try:
                    handler.wfile.write(buf)
                except (BrokenPipeError, ConnectionResetError):
                    break
                remaining -= len(buf)
    except OSError as e:  # pragma: no cover
        sys.stderr.write(f"[bmchat-tgproxy] local read error: {e}\n")


def _stream_file(handler: BaseHTTPRequestHandler,
                 bot_token: str, file_path: str,
                 file_id: str,
                 mime: Optional[str], name: Optional[str]) -> None:
    """Stream the file bytes from Telegram's CDN (cloud mode) or from
    the local disk (--local mode) to the client.

    Telegram's `--local` Bot API server returns absolute filesystem
    paths from getFile; we detect that branch and serve the file
    straight off disk. Cloud Bot API returns a relative CDN-style
    path, which we fetch over HTTPS as before.

    In both branches we forward HTTP Range requests so video / audio
    players can seek without downloading the full file — the
    difference is local mode uses `pread`-style seeking, cloud mode
    asks Telegram's CDN for a partial response.
    """
    # Local-server payloads look like absolute filesystem paths. The
    # cloud path is always a relative directory-ish string like
    # "documents/file_5.mp4", so the leading slash is a reliable
    # signal — also matches whichever value API_LOCAL_MODE is set to.
    if file_path.startswith("/"):
        _stream_local_file(handler, file_path, mime, name)
        _delete_local_file(bot_token, file_id)
        return

    url = f"{API_BASE}/file/bot{bot_token}/{file_path}"
    upstream_headers = {"User-Agent": USER_AGENT}
    # Pass the Range header through verbatim. Telegram CDN supports
    # standard byte ranges; without this the browser can't fast-seek
    # inside a video bubble.
    client_range = handler.headers.get("Range")
    if client_range:
        upstream_headers["Range"] = client_range

    req = urllib.request.Request(url, headers=upstream_headers)
    try:
        upstream = urllib.request.urlopen(req, timeout=UPSTREAM_TIMEOUT_S)
    except urllib.error.HTTPError as e:
        # 416 Range Not Satisfiable is a legit answer for tail seeks,
        # forward it as-is so the player sees the right error.
        if e.code in (416, 404):
            try:
                handler.send_response(e.code)
                ct = e.headers.get("Content-Type", "text/plain")
                handler.send_header("Content-Type", ct)
                handler.end_headers()
                handler.wfile.write(e.read())
                return
            except Exception:
                pass
        handler.send_error(502, f"upstream HTTP {e.code}")
        return
    except urllib.error.URLError as e:
        handler.send_error(502, f"upstream error: {e.reason}")
        return

    try:
        upstream_status = getattr(upstream, "status", 200)
        content_type = upstream.headers.get(
            "Content-Type",
            mime or "application/octet-stream",
        )
        content_length = upstream.headers.get("Content-Length")
        content_range = upstream.headers.get("Content-Range")
        # Honour 206 Partial Content from Telegram (Range req) so
        # the client knows it received only a slice and can keep
        # asking for more.
        handler.send_response(206 if upstream_status == 206 else 200)
        handler.send_header("Content-Type", content_type)
        if content_length:
            handler.send_header("Content-Length", content_length)
        if content_range:
            handler.send_header("Content-Range", content_range)
        handler.send_header("Accept-Ranges", "bytes")
        if name:
            # Hint browsers to keep the original filename when the user
            # right-clicks "Save as…". We deliberately mark it as
            # inline so videos / images render directly when tapped
            # in chat.
            safe_name = name.replace('"', "_")
            handler.send_header(
                "Content-Disposition",
                f"inline; filename=\"{safe_name}\"",
            )
        handler.end_headers()

        if handler.command == "HEAD":
            return

        # 256 KB chunks balance latency for the first frame of video
        # against syscall overhead for big documents.
        chunk = 256 * 1024
        while True:
            buf = upstream.read(chunk)
            if not buf:
                break
            try:
                handler.wfile.write(buf)
            except (BrokenPipeError, ConnectionResetError):
                # Players routinely abort the connection once they have
                # enough frames buffered — not an error worth alarming.
                break
    finally:
        try:
            upstream.close()
        except Exception:
            pass


def _send_json_error(handler: BaseHTTPRequestHandler, code: int, msg: str) -> None:
    payload = json.dumps({"error": msg}).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(payload)))
    handler.end_headers()
    if handler.command != "HEAD":
        handler.wfile.write(payload)


def _send_html_error(handler: BaseHTTPRequestHandler, code: int, msg: str) -> None:
    """Friendly HTML error page for users who tapped the link from a
    browser (rather than a programmatic API consumer)."""
    body = (
        "<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>BMChat — медиа недоступно</title>"
        "<style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;"
        "background:#101418;color:#e9eef3;display:flex;align-items:center;"
        "justify-content:center;min-height:100vh;margin:0;padding:24px;"
        "text-align:center}main{max-width:520px}h1{font-size:18px;margin:8px 0 12px}"
        "p{opacity:.8;line-height:1.4;margin:6px 0;font-size:14px}"
        ".chip{display:inline-block;padding:4px 10px;border-radius:999px;"
        "background:#243140;font-size:12px;margin-top:14px}</style>"
        "<main><h1>Медиа из Telegram-бота недоступно</h1>"
        f"<p>{msg}</p>"
        "<p>Файлы более 20 МБ Telegram отдаёт только через локальный Bot API "
        "server — он будет включён в ближайшем обновлении BMChat.</p>"
        f"<div class=\"chip\">BMChat tgproxy · {code}</div></main></html>"
    ).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "text/html; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    if handler.command != "HEAD":
        handler.wfile.write(body)


def _wants_html(handler: BaseHTTPRequestHandler) -> bool:
    """Heuristic: render the friendly HTML page when the caller looks
    like a browser (Accept lists text/html), and JSON otherwise so
    automated clients still get a parseable error."""
    accept = handler.headers.get("Accept", "") or ""
    return "text/html" in accept.lower()


class Handler(BaseHTTPRequestHandler):
    server_version = "BMChatTgProxy/1.0"

    def log_message(self, fmt: str, *args) -> None:  # noqa: D401
        # journald already adds a timestamp + unit name; keep ours
        # short so `journalctl -u bmchat-tgproxy` stays readable.
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def do_HEAD(self) -> None:  # noqa: D401
        self.do_GET()

    def do_GET(self) -> None:  # noqa: D401
        # Strip the nginx-side prefix; keep the query separately so we
        # can support the ?diag=1 escape hatch (cf. _diag below).
        raw_path = self.path
        if raw_path.startswith("/tgmedia/"):
            raw_path = raw_path[len("/tgmedia/"):]
        elif raw_path.startswith("/tgmedia"):
            raw_path = raw_path[len("/tgmedia"):]
        if "?" in raw_path:
            path, qs = raw_path.split("?", 1)
        else:
            path, qs = raw_path, ""
        path = path.split("#", 1)[0]
        query = urllib.parse.parse_qs(qs, keep_blank_values=True)

        if path in ("", "/", "healthz", "/healthz", "health", "/health"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(b"bmchat-tgproxy ok\n")
            return

        token = path.rstrip("/")
        if not token:
            self._error(400, "missing payload")
            return

        try:
            payload = _decrypt_payload(token)
        except Exception:
            payload = None
        if payload is None:
            self._error(400, "Ссылка повреждена или подписана неизвестным ключом.")
            return

        try:
            exp = int(payload.get("exp", 0))
        except Exception:
            exp = 0
        if exp <= 0 or exp < int(time.time() * 1000):
            self._error(410, "Ссылка истекла — пусть бот пришлёт пост заново.")
            return

        bot_token = payload.get("t")
        file_id = payload.get("f")
        if not bot_token or not file_id:
            self._error(400, "В ссылке нет ID файла или бота.")
            return

        # One-shot migration: log the bot out of api.telegram.org so
        # it can be re-authorized on our local Bot API server. Has to
        # happen exactly once per bot, against the cloud endpoint, no
        # matter what API_BASE we're configured to use now. The link
        # itself authenticates the operation — the same signed URL
        # that grants access to media metadata also grants the right
        # to migrate the bot it belongs to.
        if "migrate" in query:
            cloud = "https://api.telegram.org"
            logout = _raw_http_json(
                f"{cloud}/bot{bot_token}/logOut", method="POST",
            )
            logout["request_url"] = (
                f"{cloud}/bot{bot_token}/logOut"
            ).replace(bot_token, _redact_token(bot_token))
            payload_out = {
                "step": "logOut on api.telegram.org",
                "explain": (
                    "After this returns ok=true, this bot is no longer "
                    "served by the cloud Bot API. Subsequent requests "
                    "must go to your local Bot API server (here, the "
                    "/bot-api/ nginx path → 127.0.0.1:8083), which is "
                    "the only place that can lift the 20 MB getFile "
                    "cap."
                ),
                "logout_response": logout,
            }
            body = json.dumps(payload_out, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
            return

        # Diagnostic mode — exposes the raw Telegram getMe + getFile
        # exchange (and a CDN HEAD probe when a file_path is returned)
        # so users (and us) can verify that a "file too big or
        # expired" error came verbatim from api.telegram.org rather
        # than being cooked up by our proxy. Activated with ?diag=1
        # on the same signed URL — anyone who can fetch the media
        # can also probe it (no extra secret needed, the link itself
        # authenticates).
        if "diag" in query:
            getme_url = f"{API_BASE}/bot{bot_token}/getMe"
            getme = _raw_http_json(getme_url)
            getme["request_url"] = getme_url.replace(
                bot_token, _redact_token(bot_token)
            )

            getfile = _raw_get_file(bot_token, file_id)

            cdn = None
            try:
                tg = getfile.get("telegram_response") or {}
                if isinstance(tg, dict) and tg.get("ok"):
                    result = tg.get("result") or {}
                    fp = result.get("file_path")
                    if fp and fp.startswith("/"):
                        # Local --local mode: the file is on our own
                        # disk. Report stat() instead of an HTTP HEAD.
                        try:
                            s = os.stat(fp)
                            cdn = {
                                "source": "local file system",
                                "file_path": fp,
                                "size_bytes": s.st_size,
                                "size_mb": round(s.st_size / 1024 / 1024, 2),
                                "exists": True,
                            }
                        except OSError as oe:
                            cdn = {"source": "local file system",
                                    "file_path": fp,
                                    "exists": False,
                                    "stat_error": str(oe)}
                    elif fp:
                        cdn_url = f"{API_BASE}/file/bot{bot_token}/{fp}"
                        cdn = _raw_head(cdn_url)
                        cdn["request_url"] = cdn_url.replace(
                            bot_token, _redact_token(bot_token)
                        )
            except Exception as e:
                cdn = {"diag_error": repr(e)}

            diag = {
                "api_base": API_BASE,
                "bot_id": _redact_token(bot_token),
                "file_id": file_id,
                "step_1_getMe": getme,
                "step_2_getFile": getfile,
                "step_3_cdn_head": cdn,
                "info": (
                    "Top-to-bottom: BMChat tgproxy talked to Telegram on "
                    "your behalf. step_1_getMe confirms the bot token is "
                    "alive; step_2_getFile is the cloud Bot API request "
                    "for file metadata (this is where the documented "
                    "20 MB getFile cap fires — see Telegram Bot API docs "
                    "on api.telegram.org/bots/api#getfile); step_3_cdn_head "
                    "is the second HTTP hop to the CDN that actually "
                    "serves bytes — it only runs when step_2 succeeded. "
                    "If step_2_getFile.telegram_response.description is "
                    "'file is too big', the file lives on Telegram CDN "
                    "but cloud Bot API refuses to expose its URL to bots. "
                    "Switching this proxy to a local tdlib/telegram-bot-api "
                    "server (set BMCHAT_TGPROXY_API_BASE) lifts that cap "
                    "to 2 GB because the local server uses MTProto, the "
                    "same protocol that the official Telegram apps use to "
                    "stream multi-GB videos."
                ),
            }
            body = json.dumps(diag, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
            return

        file_path = _fetch_file_path(bot_token, file_id)
        if not file_path:
            self._error(
                502,
                "Telegram отказался отдавать этот файл — обычно это значит "
                "что он больше 20 МБ (лимит облачного Bot API).",
            )
            return

        _stream_file(
            self,
            bot_token, file_path,
            file_id=file_id,
            mime=payload.get("m"),
            name=payload.get("n"),
        )

    def _error(self, code: int, msg: str) -> None:
        if _wants_html(self):
            _send_html_error(self, code, msg)
        else:
            _send_json_error(self, code, msg)


def main() -> int:
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    sys.stderr.write(
        f"[bmchat-tgproxy] listening on http://{LISTEN_HOST}:{LISTEN_PORT}\n"
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
