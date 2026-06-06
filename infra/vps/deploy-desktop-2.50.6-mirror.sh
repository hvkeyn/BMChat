#!/usr/bin/env bash
# BMChat Desktop 2.50.6+ — mirror VPS (credentials via lib/mirror-env.sh).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"

VPS_PORT="22"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

# Override artifact names when deploying 2.50.7 (e.g. VERSION=2.50.7 bash this script).
VERSION="${BMCHAT_DESKTOP_VERSION:-2.50.6}"
SETUP="BMChat-${VERSION}-Setup.x64.exe"
PORTABLE="BMChat-${VERSION}-Portable.x64.exe"
APPIMAGE="BMChat-${VERSION}-x86_64.AppImage"
DEB="bmchat-desktop_${VERSION}_amd64.deb"

if [[ ! -f "$KEY" ]]; then
  echo "[mirror] SSH key not found: $KEY" >&2
  echo "Set BMCHAT_MIRROR_SSH_KEY in infra/vps/.deploy.env" >&2
  exit 1
fi


for f in "$SETUP" "$PORTABLE" "$APPIMAGE" "$DEB"; do
  if [[ ! -f "${LOCAL_ROOT}/desktop/${f}" ]]; then
    echo "[mirror] missing ${LOCAL_ROOT}/desktop/${f}" >&2
    exit 1
  fi
done

${SSH} ${HOST} "mkdir -p /tmp/bmchat-desktop-mirror"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${SETUP}"    "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${PORTABLE}" "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${APPIMAGE}" "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/desktop/${DEB}"      "${HOST}:/tmp/bmchat-desktop-mirror/"
eval ${RSYNC} "${LOCAL_ROOT}/www/desktop-update.json" "${HOST}:/tmp/bmchat-desktop-mirror/desktop-update.json"

${SSH} ${HOST} bash -se <<EOF
set -euo pipefail
WEBROOT=/var/www/bmchat
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
