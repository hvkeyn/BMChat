#!/usr/bin/env bash
# BMChat VPS deploy script — runs ON the VPS.
# Picks up bundled payload from /tmp/bmchat-payload/ and installs to /var/www/bmchat,
# patches nginx port-80 server block to serve invite/update/apk paths.
set -euo pipefail

PAYLOAD=/tmp/bmchat-payload
WEBROOT=/var/www/bmchat
NGINX_SITE=/etc/nginx/sites-enabled/nod-tracker

stamp() { date +%Y%m%d%H%M%S; }

echo "[bmchat-deploy] preparing webroot at $WEBROOT"
mkdir -p "$WEBROOT/apk"
cp -f "$PAYLOAD/www/index.html"   "$WEBROOT/index.html"
cp -f "$PAYLOAD/www/i.html"       "$WEBROOT/i.html"
cp -f "$PAYLOAD/www/update.json"  "$WEBROOT/update.json"
if [ -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.0.apk" ]; then
    cp -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.0.apk" "$WEBROOT/apk/"
    ln -sfn "BMChat-foss-debug-2.49.0.apk" "$WEBROOT/apk/BMChat-foss-debug-latest.apk"
fi
chown -R www-data:www-data "$WEBROOT"
find "$WEBROOT" -type d -exec chmod 0755 {} +
find "$WEBROOT" -type f -exec chmod 0644 {} +

echo "[bmchat-deploy] patching $NGINX_SITE"
mkdir -p /etc/nginx/backups
# Move any previous accidental in-tree backups out of sites-enabled so nginx stops
# parsing them as live configs.
for stale in /etc/nginx/sites-enabled/*.bak.*; do
    [ -e "$stale" ] || continue
    mv -f "$stale" "/etc/nginx/backups/$(basename "$stale")"
    echo "[bmchat-deploy] relocated stale backup -> /etc/nginx/backups/$(basename "$stale")"
done
ORIGINAL=/etc/nginx/backups/$(basename "$NGINX_SITE").original
if [ -f "$NGINX_SITE" ]; then
    BACKUP="/etc/nginx/backups/$(basename "$NGINX_SITE").bak.$(stamp)"
    cp "$NGINX_SITE" "$BACKUP"
    echo "[bmchat-deploy] backup -> $BACKUP"
fi
# Capture the very first un-patched copy as the canonical original, then always
# re-patch from it. This avoids accumulating stale `location /` blocks across
# repeated deploys.
if [ ! -f "$ORIGINAL" ] && [ -f "$NGINX_SITE" ] \
   && grep -q "return 301 https://\$host\$request_uri;" "$NGINX_SITE" \
   && ! grep -q "bmchat injected locations" "$NGINX_SITE"; then
    cp "$NGINX_SITE" "$ORIGINAL"
    echo "[bmchat-deploy] captured pristine original -> $ORIGINAL"
fi
if [ -f "$ORIGINAL" ]; then
    cp "$ORIGINAL" "$NGINX_SITE"
    echo "[bmchat-deploy] restored from $ORIGINAL before re-injecting"
fi

python3 - "$NGINX_SITE" "$PAYLOAD/nginx/bmchat-locations.conf" <<'PYEOF'
import re, sys, pathlib
site_path = pathlib.Path(sys.argv[1])
locations_path = pathlib.Path(sys.argv[2])
text = site_path.read_text()
locations = locations_path.read_text()

block_marker = "# >>> bmchat injected locations >>>"
end_marker   = "# <<< bmchat injected locations <<<"

# Drop a previous injection (locations + the catch-all location / we add below).
text = re.sub(re.escape(block_marker) + r".*?" + re.escape(end_marker) + r"\n?", "", text, flags=re.DOTALL)

# Build the injection. We turn the server-scope `return 301 ...;` (which would
# short-circuit all requests including our locations) into a `location /` so
# nginx can prefer a more specific location match.
indent = "    "
indented = "\n".join(indent + l for l in locations.splitlines() if l)
fallback = (
    indent + "# Fallback: anything not matched above keeps the original 80->443 redirect.\n"
    + indent + "location / {\n"
    + indent + indent + "return 301 https://$host$request_uri;\n"
    + indent + "}\n"
)
inject = (
    indent + block_marker + "\n"
    + indented + "\n"
    + fallback
    + indent + end_marker + "\n"
)

# Find the http server block (listen 80) for nod-tracker and replace its
# server-scope `return 301 ...;` with our injection.
pattern = re.compile(
    r"(server\s*\{[^{}]*?listen\s+80;[^{}]*?server_name[^;]*5\.187\.4\.132[^;]*;[^{}]*?)(\n\s*return\s+301\s+https://\$host\$request_uri;\s*\n)",
    re.DOTALL,
)
m = pattern.search(text)
if m:
    text = text[:m.start(2)] + "\n" + inject + text[m.end(2):]
    print("[bmchat-deploy] replaced server-scope `return 301` with location /, injected locations")
else:
    # If we already replaced it on a previous run, just inject locations near the end of the server block.
    pattern_block = re.compile(
        r"(server\s*\{[^{}]*?listen\s+80;[^{}]*?server_name[^;]*5\.187\.4\.132[^;]*;.*?)(\n\})",
        re.DOTALL,
    )
    mb = pattern_block.search(text)
    if not mb:
        sys.exit("[bmchat-deploy] could not locate port-80 server block with server_name 5.187.4.132")
    text = text[:mb.start(2)] + "\n" + inject + text[mb.end(2)-1:]
    print("[bmchat-deploy] re-injected locations into existing port-80 server")

site_path.write_text(text)
PYEOF

echo "[bmchat-deploy] nginx -t"
nginx -t

echo "[bmchat-deploy] systemctl reload nginx"
systemctl reload nginx

echo "[bmchat-deploy] verifying endpoints"
sleep 1
H="Host: 5.187.4.132"
curl -sS -H "$H" -o /dev/null -w "  GET /             -> %{http_code}\n" http://127.0.0.1/
curl -sS -H "$H" -o /dev/null -w "  GET /i            -> %{http_code}\n" http://127.0.0.1/i
curl -sS -H "$H" -o /dev/null -w "  GET /update.json  -> %{http_code}\n" http://127.0.0.1/update.json
curl -sS -H "$H" -I -o /dev/null -w "  HEAD /apk/...   -> %{http_code}\n" "http://127.0.0.1/apk/BMChat-foss-debug-latest.apk"
curl -sS -H "$H" http://127.0.0.1/update.json | head -20

echo "[bmchat-deploy] done."
