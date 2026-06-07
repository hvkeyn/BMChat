#!/usr/bin/env bash
# Windows 2.50.28 + Linux 2.50.25 + Android 2.50.25 (with retries)
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/deploy-env.sh"
export SSHPASS="${VPS_PASS}"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
RSYNC_BASE=(sshpass -e rsync -azvh --timeout=120 -e "ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ConnectTimeout=20")
SSH_BASE=(sshpass -e ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 -o ConnectTimeout=20 "${VPS_USER}@${VPS_HOST}")

retry() {
  local n=0 max=4
  until "$@"; do
    n=$((n + 1))
    if [ "$n" -ge "$max" ]; then return 1; fi
    echo "[retry $n/$max] $*"
    sleep 5
  done
}

FILES=(
  "desktop/BMChat-2.50.28-Setup.x64.exe"
  "desktop/BMChat-2.50.28-Portable.x64.exe"
  "desktop/BMChat-2.50.25-x86_64.AppImage"
  "desktop/bmchat-desktop_2.50.25_amd64.deb"
  "www/desktop-update.json"
  "apk/BMChat-foss-debug-2.50.25.apk"
  "www/update.json"
)

retry "${SSH_BASE[@]}" "mkdir -p /tmp/bmchat-release/desktop /tmp/bmchat-release/apk /tmp/bmchat-release/www"
for rel in "${FILES[@]}"; do
  echo "[upload] $rel"
  retry "${RSYNC_BASE[@]}" "${LOCAL_ROOT}/${rel}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-release/$(dirname "$rel")/"
done

retry "${SSH_BASE[@]}" bash -se <<'EOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
mkdir -p "$WEBROOT/desktop" "$WEBROOT/apk"
install -m 0644 /tmp/bmchat-release/desktop/BMChat-2.50.28-Setup.x64.exe "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-release/desktop/BMChat-2.50.28-Portable.x64.exe "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-release/desktop/BMChat-2.50.25-x86_64.AppImage "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-release/desktop/bmchat-desktop_2.50.25_amd64.deb "$WEBROOT/desktop/"
install -m 0644 /tmp/bmchat-release/www/desktop-update.json "$WEBROOT/desktop-update.json"
install -m 0644 /tmp/bmchat-release/apk/BMChat-foss-debug-2.50.25.apk "$WEBROOT/apk/"
install -m 0644 /tmp/bmchat-release/www/update.json "$WEBROOT/update.json"
cd "$WEBROOT/desktop"
ln -sfn BMChat-2.50.28-Setup.x64.exe BMChat-Setup-x64.exe
ln -sfn BMChat-2.50.28-Portable.x64.exe BMChat-portable-x64.exe
ln -sfn BMChat-2.50.25-x86_64.AppImage BMChat-x86_64.AppImage
ln -sfn bmchat-desktop_2.50.25_amd64.deb bmchat-amd64.deb
cd "$WEBROOT/apk"
ln -sfn BMChat-foss-debug-2.50.25.apk BMChat-foss-debug-latest.apk
ls -1t BMChat-*-Setup.x64.exe 2>/dev/null | tail -n +2 | xargs -r rm -f
ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | tail -n +2 | xargs -r rm -f
ls -1t BMChat-*-x86_64.AppImage 2>/dev/null | tail -n +2 | xargs -r rm -f
ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | tail -n +2 | xargs -r rm -f
ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +3 | xargs -r rm -f
chown -R www-data:www-data "$WEBROOT/desktop" "$WEBROOT/apk" "$WEBROOT/desktop-update.json" "$WEBROOT/update.json"
rm -rf /tmp/bmchat-release
echo "[uploader-primary] release win=2.50.28 linux/apk=2.50.25 done."
EOF
