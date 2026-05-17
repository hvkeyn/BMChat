#!/usr/bin/env bash
# BMChat 2.49.67 — picker UX polish + 🔥 quick reaction.
set -euo pipefail

VPS_HOST="5.187.4.132"
VPS_USER="root"
VPS_PASS="${BMCHAT_PRIM_SSH_PASS:-wog11vrtfwjuoy4uwm}"
APK_NAME="BMChat-foss-debug-2.49.67.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"
${SSH} "install -m 0644 /tmp/bmchat-payload/apk/${APK_NAME} /var/www/bmchat/apk/${APK_NAME} && \
        install -m 0644 /tmp/bmchat-payload/www/update.json /var/www/bmchat/update.json && \
        ln -sfn ${APK_NAME} /var/www/bmchat/apk/BMChat-foss-debug-latest.apk"
${SSH} "curl -sS http://127.0.0.1/update.json | head -8"
${SSH} "curl -sS -I http://127.0.0.1/apk/${APK_NAME} | head -3"
echo "[uploader-primary] done."
