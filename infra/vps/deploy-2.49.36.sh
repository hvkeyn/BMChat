#!/usr/bin/env bash
# BMChat 2.49.36 deploy: client-only release.
#
# Pushes the new APK and the refreshed update.json. No VPS-side
# changes (telegram-bot-api, bmchat-tgproxy, nginx, quota timers
# all stay untouched).
set -euo pipefail

VPS_HOST="5.187.4.132"
VPS_USER="root"
VPS_PASS="wog11vrtfwjuoy4uwm"
APK_NAME="BMChat-foss-debug-2.49.36.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

echo "[uploader] preparing remote /tmp/bmchat-payload"
${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk}"

echo "[uploader] uploading www/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/update.json"

echo "[uploader] uploading apk (${APK_NAME})"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"

echo "[uploader] publishing to nginx docroot"
${SSH} "install -m 0644 /tmp/bmchat-payload/apk/${APK_NAME} /var/www/bmchat/apk/${APK_NAME} && \
        install -m 0644 /tmp/bmchat-payload/www/update.json   /var/www/bmchat/update.json"

echo "[uploader] sanity-checking endpoints"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/update.json | head -8"
${SSH} "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/apk/${APK_NAME} | head -4"
echo "[uploader] done."
