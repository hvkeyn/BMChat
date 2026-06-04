#!/usr/bin/env bash
# BMChat Desktop 2.50.6+ — mirror VPS (158.160.104.107, user dante, SSH key from whiteBlade).
set -euo pipefail
VPS_HOST="158.160.104.107"
VPS_USER="dante"
VPS_PORT="22"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
WB_KEY="${BMCHAT_MIRROR_SSH_KEY:-/mnt/e/PPROJECTS/whiteBlade/artifacts/deploy/ssh/yc_whiteblade}"

# Override artifact names when deploying 2.50.7 (e.g. VERSION=2.50.7 bash this script).
VERSION="${BMCHAT_DESKTOP_VERSION:-2.50.6}"
SETUP="BMChat-${VERSION}-Setup.x64.exe"
PORTABLE="BMChat-${VERSION}-Portable.x64.exe"
APPIMAGE="BMChat-${VERSION}-x86_64.AppImage"
DEB="bmchat-desktop_${VERSION}_amd64.deb"

if [[ ! -f "$WB_KEY" ]]; then
  echo "[mirror] SSH key not found: $WB_KEY" >&2
  echo "Set BMCHAT_MIRROR_SSH_KEY or copy yc_whiteblade from E:/PPROJECTS/whiteBlade/artifacts/deploy/ssh/" >&2
  exit 1
fi

SSH="ssh -i $WB_KEY -p ${VPS_PORT} -o StrictHostKeyChecking=no ${VPS_USER}@${VPS_HOST}"
RSYNC="rsync -azvh -e \"ssh -i $WB_KEY -p ${VPS_PORT} -o StrictHostKeyChecking=no\""

for f in "$SETUP" "$PORTABLE" "$APPIMAGE" "$DEB"; do
  if [[ ! -f "${LOCAL_ROOT}/desktop/${f}" ]]; then
    echo "[mirror] missing ${LOCAL_ROOT}/desktop/${f}" >&2
    exit 1
  fi
done

$SSH "mkdir -p /tmp/bmchat-desktop-mirror"
eval $RSYNC "${LOCAL_ROOT}/desktop/${SETUP}"    "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop-mirror/"
eval $RSYNC "${LOCAL_ROOT}/desktop/${PORTABLE}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop-mirror/"
eval $RSYNC "${LOCAL_ROOT}/desktop/${APPIMAGE}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop-mirror/"
eval $RSYNC "${LOCAL_ROOT}/desktop/${DEB}"      "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop-mirror/"
eval $RSYNC "${LOCAL_ROOT}/www/desktop-update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-desktop-mirror/desktop-update.json"

$SSH bash -se <<EOF
set -euo pipefail
WEBROOT=/var/www/html
sudo mkdir -p "\$WEBROOT/desktop"
sudo install -m 0644 "/tmp/bmchat-desktop-mirror/${SETUP}"    "\$WEBROOT/desktop/"
sudo install -m 0644 "/tmp/bmchat-desktop-mirror/${PORTABLE}" "\$WEBROOT/desktop/"
sudo install -m 0644 "/tmp/bmchat-desktop-mirror/${APPIMAGE}"  "\$WEBROOT/desktop/"
sudo install -m 0644 "/tmp/bmchat-desktop-mirror/${DEB}"      "\$WEBROOT/desktop/"
sudo install -m 0644 /tmp/bmchat-desktop-mirror/desktop-update.json "\$WEBROOT/desktop-update.json"
sudo ln -sfn "${SETUP}"    "\$WEBROOT/desktop/BMChat-Setup-x64.exe"
sudo ln -sfn "${PORTABLE}" "\$WEBROOT/desktop/BMChat-portable-x64.exe"
sudo ln -sfn "${APPIMAGE}" "\$WEBROOT/desktop/BMChat-x86_64.AppImage"
sudo ln -sfn "${DEB}" "\$WEBROOT/desktop/bmchat-amd64.deb"
echo "[uploader-mirror-desktop] ${VERSION} done."
EOF
