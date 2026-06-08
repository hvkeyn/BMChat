#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/mirror-env.sh"
LOCAL_ROOT="$SCRIPT_DIR"
SETUP="BMChat-2.50.35-Setup.x64.exe"
PORTABLE="BMChat-2.50.35-Portable.x64.exe"
${SSH} ${HOST} "mkdir -p /tmp/bmchat-desktop-mirror"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}" "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${HOST}:/tmp/bmchat-desktop-mirror/desktop-update.json"
${SSH} ${HOST} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
sudo mkdir -p "$WEBROOT/desktop"
sudo install -m 0644 /tmp/bmchat-desktop-mirror/BMChat-2.50.35-Setup.x64.exe "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/bmchat-desktop-mirror/BMChat-2.50.35-Portable.x64.exe "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/bmchat-desktop-mirror/desktop-update.json "$WEBROOT/desktop-update.json"
sudo ln -sfn BMChat-2.50.35-Setup.x64.exe "$WEBROOT/desktop/BMChat-Setup-x64.exe"
sudo ln -sfn BMChat-2.50.35-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"
sudo chown -R www-data:www-data "$WEBROOT/desktop" "$WEBROOT/desktop-update.json"
echo "[uploader-mirror-desktop] 2.50.35 done."
EOF