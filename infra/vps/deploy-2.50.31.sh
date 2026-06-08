#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/deploy-env.sh"
export SSHPASS="${VPS_PASS}"
LOCAL="$SCRIPT_DIR"
source "$SCRIPT_DIR/lib/mirror-env.sh"
KEY="${BMCHAT_MIRROR_SSH_KEY}"
FILES=("apk/BMChat-foss-debug-2.50.31.apk" "www/update.json")
prim_ssh() { sshpass -e ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 "${VPS_USER}@${VPS_HOST}" "$@"; }
prim_rsync() { sshpass -e rsync -azvh --partial -e "ssh -o StrictHostKeyChecking=no" "$@"; }
mir_ssh() { ssh -i "${KEY}" -o StrictHostKeyChecking=no -o ServerAliveInterval=15 "${MIRROR_USER}@${MIRROR_HOST}" "$@"; }
mir_rsync() { rsync -azvh --partial -e "ssh -i ${KEY} -o StrictHostKeyChecking=no" "$@"; }
prim_ssh "mkdir -p /tmp/bmchat-2531/{apk,www}"
for f in "${FILES[@]}"; do prim_rsync "${LOCAL}/${f}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-2531/$(dirname "$f")/"; done
prim_ssh bash -se <<'EOF'
set -euo pipefail
W=/var/www/bmchat
install -m0644 /tmp/bmchat-2531/apk/BMChat-foss-debug-2.50.31.apk "$W/apk/"
install -m0644 /tmp/bmchat-2531/www/update.json "$W/update.json"
cd "$W/apk" && ln -sfn BMChat-foss-debug-2.50.31.apk BMChat-foss-debug-latest.apk
chown -R www-data:www-data "$W/apk" "$W/update.json"
rm -rf /tmp/bmchat-2531
curl -sS http://127.0.0.1/update.json | head -4
EOF
mir_ssh "sudo mkdir -p /var/www/bmchat/apk && sudo chown -R dante:dante /var/www/bmchat"
mir_ssh "mkdir -p /tmp/bmchat-2531/{apk,www}"
for f in "${FILES[@]}"; do mir_rsync "${LOCAL}/${f}" "${MIRROR_USER}@${MIRROR_HOST}:/tmp/bmchat-2531/$(dirname "$f")/"; done
mir_ssh bash -se <<'EOF'
set -euo pipefail
W=/var/www/bmchat
sudo install -m0644 /tmp/bmchat-2531/apk/BMChat-foss-debug-2.50.31.apk "$W/apk/"
sudo install -m0644 /tmp/bmchat-2531/www/update.json "$W/update.json"
cd "$W/apk" && sudo ln -sfn BMChat-foss-debug-2.50.31.apk BMChat-foss-debug-latest.apk
sudo chown -R www-data:www-data "$W"
rm -rf /tmp/bmchat-2531
curl -sS http://127.0.0.1:8080/update.json | head -4
EOF
echo "[deploy] 2.50.31 android done."