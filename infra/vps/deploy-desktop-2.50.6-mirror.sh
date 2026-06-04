#!/usr/bin/env bash
# BMChat Desktop 2.50.6 — mirror VPS uploader (Yandex Cloud, dante user). Win + Linux + manifest.
set -euo pipefail
VPS_HOST="158.160.104.107"; VPS_USER="dante"; VPS_PORT="22"
VPS_PASS="${BMCHAT_MIRROR_SSH_PASS:-}"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SETUP="BMChat-2.50.6-Setup.x64.exe"
PORTABLE="BMChat-2.50.6-Portable.x64.exe"
APPIMAGE="BMChat-2.50.6-x86_64.AppImage"
DEB="bmchat-desktop_2.50.6_amd64.deb"

SSH="sshpass -p ${VPS_PASS} ssh -p ${VPS_PORT} -o StrictHostKeyChecking=no ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -p ${VPS_PORT} -o StrictHostKeyChecking=no'"

${SSH} "mkdir -p /tmp"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${VPS_USER}@${VPS_HOST}:/tmp/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${VPS_USER}@${VPS_HOST}:/tmp/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${APPIMAGE}" "${VPS_USER}@${VPS_HOST}:/tmp/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${DEB}"      "${VPS_USER}@${VPS_HOST}:/tmp/"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${VPS_USER}@${VPS_HOST}:/tmp/desktop-update.json"

${SSH} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/html
sudo mkdir -p "$WEBROOT/desktop"
sudo install -m 0644 /tmp/BMChat-2.50.6-Setup.x64.exe    "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.50.6-Portable.x64.exe "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.50.6-x86_64.AppImage  "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/bmchat-desktop_2.50.6_amd64.deb "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/desktop-update.json            "$WEBROOT/desktop-update.json"

sudo ln -sfn BMChat-2.50.6-Setup.x64.exe    "$WEBROOT/desktop/BMChat-Setup-x64.exe"
sudo ln -sfn BMChat-2.50.6-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"
sudo ln -sfn BMChat-2.50.6-x86_64.AppImage  "$WEBROOT/desktop/BMChat-x86_64.AppImage"
sudo ln -sfn bmchat-desktop_2.50.6_amd64.deb "$WEBROOT/desktop/bmchat-amd64.deb"

echo "[uploader-mirror-desktop] done."
EOF
