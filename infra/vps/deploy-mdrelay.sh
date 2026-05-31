#!/usr/bin/env bash
# Deploy BMChat multi-device relay to the primary VPS.
#
# This only installs the relay service and refreshes nginx locations.
# Desktop/Android integration is shipped by the app builds separately.
set -euo pipefail

VPS_HOST="${BMCHAT_PRIM_HOST:-5.187.4.132}"
VPS_USER="${BMCHAT_PRIM_USER:-root}"
VPS_PASS="${BMCHAT_PRIM_SSH_PASS:-wog11vrtfwjuoy4uwm}"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no'"

${SSH} "mkdir -p /tmp/bmchat-mdrelay"
eval ${RSYNC} "${LOCAL_ROOT}/relay/bmchat_multidevice_relay.py" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-mdrelay/"
eval ${RSYNC} "${LOCAL_ROOT}/relay/bmchat-mdrelay.service" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-mdrelay/"
eval ${RSYNC} "${LOCAL_ROOT}/nginx/bmchat-locations.conf" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-mdrelay/"

${SSH} bash -se <<'EOF'
set -euo pipefail

install -d -m 0755 /opt/bmchat
install -m 0755 /tmp/bmchat-mdrelay/bmchat_multidevice_relay.py /opt/bmchat/bmchat_multidevice_relay.py
install -m 0644 /tmp/bmchat-mdrelay/bmchat-mdrelay.service /etc/systemd/system/bmchat-mdrelay.service
install -d -o www-data -g www-data -m 0750 /var/lib/bmchat-mdrelay

systemctl daemon-reload
systemctl enable --now bmchat-mdrelay.service

# Refresh the shared BMChat nginx locations and re-inject them into the HTTP
# server block.  This mirrors deploy.sh but keeps this relay-only rollout small.
install -m 0644 /tmp/bmchat-mdrelay/bmchat-locations.conf /var/www/bmchat/bmchat-locations.conf
python3 - /etc/nginx/sites-enabled/nod-tracker /tmp/bmchat-mdrelay/bmchat-locations.conf <<'PYEOF'
import re
import sys
import pathlib

site_path = pathlib.Path(sys.argv[1])
locations_path = pathlib.Path(sys.argv[2])
text = site_path.read_text()
locations = locations_path.read_text()

original_path = pathlib.Path("/etc/nginx/backups/nod-tracker.original")
if original_path.exists():
    text = original_path.read_text()

pattern = re.compile(
    r"server\s*\{\s*listen\s+80;\s*server_name\s+([^;]+);\s*return\s+301\s+https://\$host\$request_uri;\s*\}",
    re.DOTALL,
)

def build_blocks(server_names: str) -> str:
    names = [n for n in re.split(r"\s+", server_names.strip()) if n]
    bmchat_names = [n for n in names if n == "5.187.4.132"] or ["5.187.4.132"]
    other_names = [n for n in names if n != "5.187.4.132"]
    indent = "    "
    indented = "\n".join(indent + line for line in locations.splitlines() if line)
    bmchat_block = (
        "# BMChat injected locations.\n"
        "server {\n"
        + indent + "listen 80;\n"
        + indent + "server_name " + " ".join(bmchat_names) + ";\n\n"
        + indented + "\n\n"
        + indent + "location / {\n"
        + indent + indent + "return 301 https://$host$request_uri;\n"
        + indent + "}\n"
        "}\n"
    )
    other_block = ""
    if other_names:
        other_block = (
            "server {\n"
            + indent + "listen 80;\n"
            + indent + "server_name " + " ".join(other_names) + ";\n"
            + indent + "return 301 https://$host$request_uri;\n"
            "}\n"
        )
    return bmchat_block + "\n" + other_block

new_text, count = pattern.subn(lambda match: build_blocks(match.group(1)), text, count=1)
if count != 1:
    raise SystemExit("could not find canonical port-80 redirect block in nginx site")
site_path.write_text(new_text)
PYEOF
nginx -t
systemctl reload nginx

sleep 1
systemctl is-active --quiet bmchat-mdrelay.service
python3 - <<'PYEOF'
import json
import urllib.request

body = json.dumps({"sid": "BMCHATrelaySmokeTestSession000000000001", "expires_in": 60}).encode()
request = urllib.request.Request(
    "http://127.0.0.1:8091/session",
    data=body,
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(request, timeout=10) as response:
    payload = json.loads(response.read().decode())
if not payload.get("ok"):
    raise SystemExit(f"relay smoke test failed: {payload}")
PYEOF
echo "[deploy-mdrelay] relay service is healthy."
EOF
