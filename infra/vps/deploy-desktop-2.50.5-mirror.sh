#!/usr/bin/env bash
# BMChat Desktop 2.50.5 — mirror VPS uploader (Yandex Cloud, dante user). Win + Linux + manifest.
set -euo pipefail
KEY="${BMCHAT_MIRROR_SSH_KEY:-/root/.ssh/yc_whiteblade}"
HOST="dante@158.160.104.107"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SETUP="BMChat-2.50.5-Setup.x64.exe"
PORTABLE="BMChat-2.50.5-Portable.x64.exe"
APPIMAGE="BMChat-2.50.5-x86_64.AppImage"
DEB="bmchat-desktop_2.50.5_amd64.deb"

SSH="ssh -i ${KEY} -o StrictHostKeyChecking=no"
RSYNC="rsync -azvh -e '${SSH}'"

eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${HOST}:/tmp/${SETUP}"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${HOST}:/tmp/${PORTABLE}"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${APPIMAGE}" "${HOST}:/tmp/${APPIMAGE}"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${DEB}"      "${HOST}:/tmp/${DEB}"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${HOST}:/tmp/desktop-update.json"

${SSH} ${HOST} bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
sudo mkdir -p "$WEBROOT/desktop"
sudo install -m 0644 /tmp/BMChat-2.50.5-Setup.x64.exe    "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.50.5-Portable.x64.exe "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.50.5-x86_64.AppImage  "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/bmchat-desktop_2.50.5_amd64.deb "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/desktop-update.json            "$WEBROOT/desktop-update.json"

sudo ln -sfn BMChat-2.50.5-Setup.x64.exe    "$WEBROOT/desktop/BMChat-Setup-x64.exe"
sudo ln -sfn BMChat-2.50.5-Portable.x64.exe "$WEBROOT/desktop/BMChat-portable-x64.exe"
sudo ln -sfn BMChat-2.50.5-x86_64.AppImage  "$WEBROOT/desktop/BMChat-x86_64.AppImage"
sudo ln -sfn bmchat-desktop_2.50.5_amd64.deb "$WEBROOT/desktop/bmchat-amd64.deb"

cd "$WEBROOT/desktop"
ls -1t BMChat-*-Setup.x64.exe    2>/dev/null | tail -n +4 | xargs -r sudo rm -f
ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | tail -n +4 | xargs -r sudo rm -f
ls -1t BMChat-*-x86_64.AppImage  2>/dev/null | tail -n +4 | xargs -r sudo rm -f
ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | tail -n +4 | xargs -r sudo rm -f

echo "[uploader-mirror-desktop] done."
EOF
