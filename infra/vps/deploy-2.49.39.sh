#!/usr/bin/env bash
# BMChat 2.49.39 — emergency crash-fix for 2.49.38. Client-only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/deploy-env.sh
source "$SCRIPT_DIR/lib/deploy-env.sh"


APK_NAME="BMChat-foss-debug-2.49.39.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"


${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/www/update.json"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${VPS_USER}@${VPS_HOST}:/tmp/bmchat-payload/apk/"
${SSH} "install -m 0644 /tmp/bmchat-payload/apk/${APK_NAME} /var/www/bmchat/apk/${APK_NAME} && \
        install -m 0644 /tmp/bmchat-payload/www/update.json   /var/www/bmchat/update.json"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/update.json | head -8"
${SSH} "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/apk/${APK_NAME} | head -4"
echo "[uploader] done."
