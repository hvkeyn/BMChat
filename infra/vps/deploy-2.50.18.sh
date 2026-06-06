#!/usr/bin/env bash
set -euo pipefail
VPS_HOST="5.187.4.132"; VPS_USER="root"
VPS_PASS="${BMCHAT_PRIM_SSH_PASS:-wog11vrtfwjuoy4uwm}"
APK_NAME="BMChat-foss-debug-2.50.18.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no'"
${SSH} "mkdir -p /tmp/bmchat-payload/www /tmp/bmchat-payload/apk"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"
${SSH} "set -e
  install -m 0644 /tmp/bmchat-payload/apk/${APK_NAME} /var/www/bmchat/apk/${APK_NAME}
  install -m 0644 /tmp/bmchat-payload/www/update.json /var/www/bmchat/update.json
  cd /var/www/bmchat/apk
  ln -sfn ${APK_NAME} BMChat-foss-debug-latest.apk
  ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +4 | xargs -r rm -f
  chown -R www-data:www-data /var/www/bmchat/apk /var/www/bmchat/update.json
"
echo "[uploader-primary] 2.50.18 APK done."