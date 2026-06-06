#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"

HOST="dante@158.160.104.107"
APK_NAME="BMChat-foss-debug-2.50.2.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
eval ${RSYNC} "${LOCAL_ROOT}/apk/${APK_NAME}" "${HOST}:/tmp/${APK_NAME}"
eval ${RSYNC} "${LOCAL_ROOT}/www/update.json" "${HOST}:/tmp/update.json"
${SSH} ${HOST} "sudo install -m 0644 /tmp/${APK_NAME} /var/www/bmchat/apk/ && sudo install -m 0644 /tmp/update.json /var/www/bmchat/update.json && sudo ln -sfn ${APK_NAME} /var/www/bmchat/apk/BMChat-foss-debug-latest.apk"
echo "[uploader-mirror] done."
