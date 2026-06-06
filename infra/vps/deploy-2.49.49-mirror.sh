#!/usr/bin/env bash
# BMChat 2.49.49 mirror uploader.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"


HOST="dante@158.160.104.107"
APK_NAME="BMChat-foss-debug-2.49.49.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"


eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}"        "${HOST}:/tmp/${APK_NAME}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json"        "${HOST}:/tmp/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/www/email-bot-api.html" "${HOST}:/tmp/email-bot-api.html"
eval ${RSYNC} "${LOCAL_ROOT}/www/index.html"         "${HOST}:/tmp/index.html"

${SSH} ${HOST} "sudo install -m 0644 /tmp/${APK_NAME}              /var/www/bmchat/apk/ && \
                sudo install -m 0644 /tmp/update.json              /var/www/bmchat/update.json && \
                sudo install -m 0644 /tmp/email-bot-api.html       /var/www/bmchat/email-bot-api.html && \
                sudo install -m 0644 /tmp/index.html               /var/www/bmchat/index.html && \
                sudo ln -sfn ${APK_NAME} /var/www/bmchat/apk/BMChat-foss-debug-latest.apk && \
                curl -sS http://127.0.0.1:8080/update.json | head -8 && \
                curl -sS -I http://127.0.0.1:8080/apk/${APK_NAME} | head -3 && \
                curl -sS -I http://127.0.0.1:8080/email-bot-api.html | head -3"

echo "[uploader-mirror] done."
