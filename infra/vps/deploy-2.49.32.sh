#!/usr/bin/env bash
set -euo pipefail

VPS_HOST="5.187.4.132"
VPS_USER="root"
VPS_PASS="wog11vrtfwjuoy4uwm"
APK_NAME="BMChat-foss-debug-2.49.32.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

echo "[uploader] preparing remote /tmp/bmchat-payload"
${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk,nginx,desktop,tgproxy}"

echo "[uploader] uploading www/"
eval ${RSYNC} "${LOCAL_ROOT}/www/" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/"
echo "[uploader] uploading nginx/"
eval ${RSYNC} "${LOCAL_ROOT}/nginx/" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/nginx/"
echo "[uploader] uploading tgproxy/"
eval ${RSYNC} "${LOCAL_ROOT}/tgproxy/" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/tgproxy/"
echo "[uploader] uploading apk (${APK_NAME})"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"
echo "[uploader] uploading deploy.sh"
eval ${RSYNC} "${LOCAL_ROOT}/deploy.sh" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/deploy.sh"

echo "[uploader] running remote deploy.sh"
${SSH} "chmod +x /tmp/bmchat-payload/deploy.sh && bash /tmp/bmchat-payload/deploy.sh"

echo "[uploader] sanity-checking endpoints"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/update.json | head -6"
${SSH} "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/apk/${APK_NAME} | head -4"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/tgmedia/healthz"
echo "[uploader] done."
