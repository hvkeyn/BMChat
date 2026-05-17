#!/usr/bin/env bash
# BMChat 2.49.57 mirror uploader.
set -euo pipefail

KEY="${BMCHAT_MIRROR_SSH_KEY:-$HOME/.ssh/yc_whiteblade}"
HOST="dante@158.160.104.107"
APK_NAME="BMChat-foss-debug-2.49.57.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="ssh -i ${KEY} -o StrictHostKeyChecking=no -o ServerAliveInterval=15"
RSYNC="rsync -azvh -e '${SSH}'"

eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${HOST}:/tmp/${APK_NAME}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${HOST}:/tmp/update.json"

${SSH} ${HOST} "sudo install -m 0644 /tmp/${APK_NAME} /var/www/bmchat/apk/ && \
                sudo install -m 0644 /tmp/update.json /var/www/bmchat/update.json && \
                sudo ln -sfn ${APK_NAME} /var/www/bmchat/apk/BMChat-foss-debug-latest.apk && \
                curl -sS http://127.0.0.1:8080/update.json | head -8 && \
                curl -sS -I http://127.0.0.1:8080/apk/${APK_NAME} | head -3"

echo "[uploader-mirror] done."
