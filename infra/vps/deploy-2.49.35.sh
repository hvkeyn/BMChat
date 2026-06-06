#!/usr/bin/env bash
# BMChat 2.49.35 deploy: client-only release.
#
# Unlike 2.49.34 we are not changing anything on the VPS side — the
# local telegram-bot-api server, bmchat-tgproxy local-mode and disk
# quota stay exactly as they were. We just push the new APK and the
# refreshed update.json so existing installs auto-update on their
# next foreground tick.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/deploy-env.sh
source "$SCRIPT_DIR/lib/deploy-env.sh"


APK_NAME="BMChat-foss-debug-2.49.35.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"


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
