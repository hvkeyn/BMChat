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
mkdir -p "$WEBROOT/apk" "$WEBROOT/desktop"
cp -f "$PAYLOAD/www/index.html"           "$WEBROOT/index.html"
cp -f "$PAYLOAD/www/i.html"               "$WEBROOT/i.html"
cp -f "$PAYLOAD/www/update.json"          "$WEBROOT/update.json"
cp -f "$PAYLOAD/www/desktop-update.json"  "$WEBROOT/desktop-update.json"
if [ -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.0.apk" ]; then
    cp -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.0.apk" "$WEBROOT/apk/"
fi
if [ -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.1.apk" ]; then
    cp -f "$PAYLOAD/apk/BMChat-foss-debug-2.49.1.apk" "$WEBROOT/apk/"
    ln -sfn "BMChat-foss-debug-2.49.1.apk" "$WEBROOT/apk/BMChat-foss-debug-latest.apk"
elif [ -f "$WEBROOT/apk/BMChat-foss-debug-2.49.0.apk" ]; then
    ln -sfn "BMChat-foss-debug-2.49.0.apk" "$WEBROOT/apk/BMChat-foss-debug-latest.apk"
fi
# Copy any prebuilt desktop installers (electron-builder outputs) into per-arch
# subfolders if the deploying side staged them under $PAYLOAD/desktop/.
if [ -d "$PAYLOAD/desktop" ]; then
    cp -rf "$PAYLOAD/desktop/." "$WEBROOT/desktop/"
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

# Split the original `server { listen 80; server_name X Y; return 301 ...; }`
# into TWO server blocks:
#  * one for the BMChat IP, with our static locations and a final 301 fallback;
#  * one for the original duckdns.org domain, that keeps the unconditional
#    HTTP -> HTTPS redirect untouched.
# This keeps the existing nod-tracker user experience intact and avoids any
# `if (host = …)` magic inside a single server block.

pattern = re.compile(
    r"server\s*\{\s*listen\s+80;\s*server_name\s+([^;]+);\s*return\s+301\s+https://\$host\$request_uri;\s*\}",
    re.DOTALL,
)

def build_blocks(server_names):
    names = [n for n in re.split(r"\s+", server_names.strip()) if n]
    bmchat_names = [n for n in names if n == "5.187.4.132"]
    other_names = [n for n in names if n != "5.187.4.132"]
    if not bmchat_names:
        bmchat_names = ["5.187.4.132"]
    indent = "    "
    indented = "\n".join(indent + l for l in locations.splitlines() if l)
    bmchat_block = (
        "# BMChat invite / update / APK distribution on plain HTTP (port 80).\n"
        "server {\n"
        + indent + "listen 80;\n"
        + indent + "server_name " + " ".join(bmchat_names) + ";\n\n"
        + indented + "\n\n"
        + indent + "# Fallback: anything not explicitly listed redirects to HTTPS\n"
        + indent + "# so accidental browsing of root via IP still ends up somewhere safe.\n"
        + indent + "location / {\n"
        + indent + indent + "return 301 https://$host$request_uri;\n"
        + indent + "}\n"
        "}\n"
    )
    other_block = ""
    if other_names:
        other_block = (
            "# Original nod-tracker / duckdns HTTP -> HTTPS redirect (untouched).\n"
            "server {\n"
            + indent + "listen 80;\n"
            + indent + "server_name " + " ".join(other_names) + ";\n"
            + indent + "return 301 https://$host$request_uri;\n"
            "}\n"
        )
    return bmchat_block + ("\n" + other_block if other_block else "")

m = pattern.search(text)
if m:
    text = text[:m.start()] + build_blocks(m.group(1)) + text[m.end():]
    print("[bmchat-deploy] split port-80 server into BMChat (IP) + nod-tracker (duckdns)")
else:
    sys.exit("[bmchat-deploy] could not find pristine port-80 server block to split")

# ALSO patch the existing 443 server: if a request reaches it with
# `Host: 5.187.4.132` (i.e. someone clicked an `https://5.187.4.132/...`
# link), redirect to the plain-HTTP BMChat block instead of letting the
# nod-tracker frontend swallow it. The redirect preserves the URL fragment
# (browsers re-attach the original fragment to a 3xx target without one).

ssl_pattern = re.compile(
    r"(server\s*\{\s*listen\s+443\s+ssl[^{]*?server_name\s+[^;]*5\.187\.4\.132[^;]*?;)",
    re.DOTALL,
)
ssl_match = ssl_pattern.search(text)
if ssl_match:
    head = ssl_match.group(1)
    # Idempotency: don't add the redirect twice.
    insert_at = ssl_match.end(1)
    if "BMChat: redirect HTTPS-by-IP" not in text[:insert_at + 1024]:
        redirect_snippet = (
            "\n\n    # BMChat: redirect HTTPS-by-IP traffic to the plain-HTTP\n"
            "    # BMChat block. Without this, the nod-tracker frontend\n"
            "    # (which still includes 5.187.4.132 in server_name for\n"
            "    # backward compatibility) silently swallows invite links\n"
            "    # of the form https://5.187.4.132/i#... that older or\n"
            "    # cross-platform clients may emit.\n"
            "    if ($host = \"5.187.4.132\") {\n"
            "        return 302 http://$host$request_uri;\n"
            "    }\n"
        )
        text = text[:insert_at] + redirect_snippet + text[insert_at:]
        print("[bmchat-deploy] inserted HTTPS-by-IP -> HTTP redirect into 443 server block")
    else:
        print("[bmchat-deploy] HTTPS-by-IP redirect already present — skipping")
else:
    print("[bmchat-deploy] no 443 ssl block referencing 5.187.4.132 found — skipping HTTPS patch")

site_path.write_text(text)
PYEOF

echo "[bmchat-deploy] nginx -t"
nginx -t

echo "[bmchat-deploy] systemctl reload nginx"
systemctl reload nginx

echo "[bmchat-deploy] verifying endpoints"
sleep 1
H="Host: 5.187.4.132"
curl -sS -H "$H" -o /dev/null -w "  GET /                     -> %{http_code}\n" http://127.0.0.1/
curl -sS -H "$H" -o /dev/null -w "  GET /i                    -> %{http_code}\n" http://127.0.0.1/i
curl -sS -H "$H" -o /dev/null -w "  GET /update.json          -> %{http_code}\n" http://127.0.0.1/update.json
curl -sS -H "$H" -o /dev/null -w "  GET /desktop-update.json  -> %{http_code}\n" http://127.0.0.1/desktop-update.json
curl -sS -H "$H" -I -o /dev/null -w "  HEAD /apk/...             -> %{http_code}\n" "http://127.0.0.1/apk/BMChat-foss-debug-latest.apk"
curl -sS -H "$H" http://127.0.0.1/update.json | head -20

echo "[bmchat-deploy] done."
