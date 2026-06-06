#!/usr/bin/env bash
# BMChat Desktop 2.49.97 — mirror VPS uploader (Yandex Cloud, dante user).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"

HOST="dante@158.160.104.107"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SETUP="BMChat-2.49.97-Setup.x64.exe"
PORTABLE="BMChat-2.49.97-Portable.x64.exe"


eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${HOST}:/tmp/${SETUP}"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${HOST}:/tmp/${PORTABLE}"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${HOST}:/tmp/desktop-update.json"

${SSH} ${HOST} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
sudo mkdir -p "$WEBROOT/desktop"
sudo install -m 0644 /tmp/BMChat-2.49.97-Setup.x64.exe    "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.49.97-Portable.x64.exe "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/desktop-update.json             "$WEBROOT/desktop-update.json"

sudo ln -sfn BMChat-2.49.97-Setup.x64.exe    "$WEBROOT/desktop/BMChat-Setup-x64.exe"
sudo ln -sfn BMChat-2.49.97-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"

echo "[uploader-mirror-desktop] done."
EOF
