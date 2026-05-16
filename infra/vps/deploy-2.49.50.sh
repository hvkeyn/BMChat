#!/usr/bin/env bash
# BMChat 2.49.50 — Email Bot API migrated to e-mail transport (BotFather-style).
set -euo pipefail

VPS_HOST="5.187.4.132"
VPS_USER="root"
VPS_PASS="${BMCHAT_PRIM_SSH_PASS:-wog11vrtfwjuoy4uwm}"
APK_NAME="BMChat-foss-debug-2.49.50.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json"        "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/www/email-bot-api.html" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/email-bot-api.html"
eval ${RSYNC} "${LOCAL_ROOT}/www/index.html"         "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/index.html"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}"        "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"
${SSH} "install -m 0644 /tmp/bmchat-payload/apk/${APK_NAME}              /var/www/bmchat/apk/${APK_NAME} && \
        install -m 0644 /tmp/bmchat-payload/www/update.json              /var/www/bmchat/update.json && \
        install -m 0644 /tmp/bmchat-payload/www/email-bot-api.html       /var/www/bmchat/email-bot-api.html && \
        install -m 0644 /tmp/bmchat-payload/www/index.html               /var/www/bmchat/index.html && \
        ln -sfn ${APK_NAME} /var/www/bmchat/apk/BMChat-foss-debug-latest.apk"
${SSH} "curl -sS http://127.0.0.1/update.json | head -8"
${SSH} "curl -sS -I http://127.0.0.1/apk/${APK_NAME}              | head -3"
${SSH} "curl -sS -I http://127.0.0.1/email-bot-api.html           | head -3"
echo "[uploader-primary] done."
