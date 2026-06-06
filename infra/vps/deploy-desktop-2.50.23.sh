#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/deploy-env.sh
source "$SCRIPT_DIR/lib/deploy-env.sh"

LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SETUP="BMChat-2.50.23-Setup.x64.exe"
PORTABLE="BMChat-2.50.23-Portable.x64.exe"
APPIMAGE="BMChat-2.50.23-x86_64.AppImage"
DEB="bmchat-desktop_2.50.23_amd64.deb"

${SSH} "mkdir -p /tmp/bmchat-desktop"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${APPIMAGE}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${DEB}"      "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop/desktop-update.json"

${SSH} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
mkdir -p "$WEBROOT/desktop"
install -m 0644 /tmp/bmchat-desktop/BMChat-2.50.23-Setup.x64.exe    "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/BMChat-2.50.23-Portable.x64.exe "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/BMChat-2.50.23-x86_64.AppImage  "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/bmchat-desktop_2.50.23_amd64.deb "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-desktop/desktop-update.json            "$WEBROOT/desktop-update.json"
ln -sfn BMChat-2.50.23-Setup.x64.exe    "$WEBROOT/desktop/BMChat-Setup-x64.exe"
ln -sfn BMChat-2.50.23-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"
ln -sfn BMChat-2.50.23-x86_64.AppImage  "$WEBROOT/desktop/BMChat-x86_64.AppImage"
ln -sfn bmchat-desktop_2.50.23_amd64.deb "$WEBROOT/desktop/bmchat-amd64.deb"
chown -R www-data:www-data "$WEBROOT/desktop" "$WEBROOT/desktop-update.json"
cd "$WEBROOT/desktop"
ls -1t BMChat-*-Setup.x64.exe    2>/dev/null | tail -n +4 | xargs -r rm -f
ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | tail -n +4 | xargs -r rm -f
ls -1t BMChat-*-x86_64.AppImage  2>/dev/null | tail -n +4 | xargs -r rm -f
ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | tail -n +4 | xargs -r rm -f
echo "[uploader-primary-desktop] 2.50.23 done."
EOF