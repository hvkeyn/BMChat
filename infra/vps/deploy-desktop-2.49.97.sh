#!/usr/bin/env bash
# BMChat Desktop 2.49.97 — primary VPS uploader.
# Stages installer + portable + manifest + a stable-alias symlink so
# the existing `bmchat-updater.ts` flow downloads the new build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/deploy-env.sh
source "$SCRIPT_DIR/lib/deploy-env.sh"

LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SETUP="BMChat-2.49.97-Setup.x64.exe"
PORTABLE="BMChat-2.49.97-Portable.x64.exe"


${SSH} "mkdir -p /tmp/bmchat-desktop"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/desktop-update.json"

${SSH} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
mkdir -p "$WEBROOT/desktop"
install -m 0644 /tmp/bmchat-desktop/BMChat-2.49.97-Setup.x64.exe    "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/BMChat-2.49.97-Portable.x64.exe "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/desktop-update.json             "$WEBROOT/desktop-update.json"

# Stable aliases used by both the website and the in-app self-updater.
ln -sfn BMChat-2.49.97-Setup.x64.exe    "$WEBROOT/desktop/BMChat-Setup-x64.exe"
ln -sfn BMChat-2.49.97-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"

chown -R www-data:www-data "$WEBROOT/desktop" "$WEBROOT/desktop-update.json"
echo "[uploader-primary-desktop] done."
EOF
