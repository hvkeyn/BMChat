#!/usr/bin/env bash
set -euo pipefail
KEY="$HOME/.ssh/bmchat_yc_whiteblade"
cp -f /mnt/e/PPROJECTS/whiteBlade/artifacts/deploy/ssh/yc_whiteblade "$KEY"
chmod 600 "$KEY"
LOCAL=/mnt/e/PPROJECTS/BMChat/infra/vps
APK=BMChat-foss-debug-2.50.12.apk
HOST=dante@158.160.104.107
WEBROOT=/var/www/bmchat

ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" "sudo mkdir -p ${WEBROOT}/apk ${WEBROOT}/desktop"
scp -i "$KEY" -o StrictHostKeyChecking=no "$LOCAL/www/update.json" "$LOCAL/www/desktop-update.json" "$HOST:/tmp/"
scp -i "$KEY" -o StrictHostKeyChecking=no "$LOCAL/apk/$APK" "$HOST:/tmp/$APK"
ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" bash -se <<EOF
set -euo pipefail
WEBROOT=/var/www/bmchat
APK=BMChat-foss-debug-2.50.12.apk
sudo install -m 0644 /tmp/update.json "\$WEBROOT/update.json"
sudo install -m 0644 /tmp/desktop-update.json "\$WEBROOT/desktop-update.json"
sudo install -m 0644 /tmp/\$APK "\$WEBROOT/apk/\$APK"
sudo ln -sfn \$APK "\$WEBROOT/apk/BMChat-foss-debug-latest.apk"
# Drop stale APKs to save space on 9G disk
cd "\$WEBROOT/apk"
ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +4 | xargs -r sudo rm -f
sudo chown -R www-data:www-data "\$WEBROOT/update.json" "\$WEBROOT/apk"
ls -la "\$WEBROOT/update.json" "\$WEBROOT/apk/" | tail -6
rm -f /tmp/update.json /tmp/\$APK
EOF
echo "--- verify HTTP ---"
curl -fsS "http://158.160.104.107:8080/update.json" | head -8
curl -fsSI "http://158.160.104.107:8080/apk/$APK" | head -5
DESK_LOCAL="${LOCAL}/desktop"
if [ -f "${DESK_LOCAL}/BMChat-2.50.12-Setup.x64.exe" ]; then
  scp -i "$KEY" -o StrictHostKeyChecking=no \
    "${DESK_LOCAL}/BMChat-2.50.12-Setup.x64.exe" \
    "${DESK_LOCAL}/BMChat-2.50.12-Portable.x64.exe" \
    "$HOST:/tmp/"
  ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" bash -se <<'MEOF'
set -euo pipefail
WEBROOT=/var/www/bmchat
sudo install -m 0644 /tmp/BMChat-2.50.12-Setup.x64.exe    "$WEBROOT/desktop/"
sudo install -m 0644 /tmp/BMChat-2.50.12-Portable.x64.exe "$WEBROOT/desktop/"
cd "$WEBROOT/desktop"
sudo ln -sfn BMChat-2.50.12-Setup.x64.exe    BMChat-Setup-x64.exe
sudo ln -sfn BMChat-2.50.12-Portable.x64.exe BMChat-portable-x64.exe
ls -1t BMChat-*-Setup.x64.exe    2>/dev/null | tail -n +3 | xargs -r sudo rm -f
ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | tail -n +3 | xargs -r sudo rm -f
sudo chown -R www-data:www-data "$WEBROOT/desktop"
MEOF
  echo "[mirror] desktop 2.50.12 Win uploaded"
fi

echo "[mirror] OK — http://158.160.104.107:8080/"
