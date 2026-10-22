#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/deploy-env.sh"
export SSHPASS="${VPS_PASS}"
LOCAL="$SCRIPT_DIR"
source "$SCRIPT_DIR/lib/mirror-env.sh"
KEY="${BMCHAT_MIRROR_SSH_KEY}"
FILES=("apk/BMChat-foss-release-2.50.54.apk" "www/update.json")
prim_ssh() { sshpass -e ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 "${VPS_USER}@${VPS_HOST}" "$@"; }
prim_rsync() { sshpass -e rsync -azvh --partial -e "ssh -o StrictHostKeyChecking=no" "$@"; }
prim_ssh "mkdir -p /tmp/bmchat-2554/{apk,www}"
for f in "${FILES[@]}"; do prim_rsync "${LOCAL}/${f}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-2554/$(dirname "$f")/"; done
prim_ssh bash -se <<'EOF'
set -euo pipefail
W=/var/www/bmchat
install -m0644 /tmp/bmchat-2554/apk/BMChat-foss-release-2.50.54.apk "$W/apk/"
install -m0644 /tmp/bmchat-2554/www/update.json "$W/update.json"
cd "$W/apk" && ln -sfn BMChat-foss-release-2.50.54.apk BMChat-foss-release-latest.apk && ln -sfn BMChat-foss-release-2.50.54.apk BMChat-foss-debug-latest.apk
chown -R www-data:www-data "$W/apk" "$W/update.json"
rm -rf /tmp/bmchat-2554
curl -sS http://127.0.0.1/update.json | head -4
EOF
echo "[deploy] Android 2.50.54 done (primary)."
