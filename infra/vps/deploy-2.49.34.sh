#!/usr/bin/env bash
set -euo pipefail

VPS_HOST="5.187.4.132"
VPS_USER="root"
VPS_PASS="wog11vrtfwjuoy4uwm"
APK_NAME="BMChat-foss-debug-2.49.34.apk"
LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

SSH="sshpass -p ${VPS_PASS} ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -p ${VPS_PASS} rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

echo "[uploader] preparing remote /tmp/bmchat-payload"
${SSH} "mkdir -p /tmp/bmchat-payload/{www,apk,nginx,desktop,tgproxy} /etc/bmchat"

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

# Telegram credentials (api_id / api_hash) for the local Bot API
# server. These are pulled from env vars on this side so they never
# get committed to the repo. The remote installs them to
# /etc/bmchat/tgbotapi.env, which the systemd unit reads.
TG_API_ID="${TG_API_ID:?env required}"
TG_API_HASH="${TG_API_HASH:?env required}"

echo "[uploader] writing /etc/bmchat/tgbotapi.env on remote"
${SSH} "umask 077 && cat > /etc/bmchat/tgbotapi.env <<EOF
TELEGRAM_API_ID=${TG_API_ID}
TELEGRAM_API_HASH=${TG_API_HASH}
EOF
chmod 0640 /etc/bmchat/tgbotapi.env"

echo "[uploader] installing systemd unit for telegram-bot-api"
eval ${RSYNC} "${LOCAL_ROOT}/tgproxy/bmchat-tg-bot-api.service" \
    "${VPS_USER}@${VPS_HOST}:/etc/systemd/system/bmchat-tg-bot-api.service"

echo "[uploader] running remote deploy.sh"
${SSH} "chmod +x /tmp/bmchat-payload/deploy.sh && bash /tmp/bmchat-payload/deploy.sh"

echo "[uploader] (re)starting bmchat-tg-bot-api"
${SSH} "systemctl daemon-reload && \
    if [ -x /opt/bmchat/tgbotapi/bin/telegram-bot-api ]; then \
        systemctl enable --now bmchat-tg-bot-api && \
        sleep 2 && \
        systemctl --no-pager status bmchat-tg-bot-api | head -8; \
    else \
        echo '[uploader] tdlib binary not built yet — service unit ready but not started'; \
    fi"

echo "[uploader] flipping bmchat-tgproxy to local Bot API server"
${SSH} "mkdir -p /etc/systemd/system/bmchat-tgproxy.service.d && \
    cat > /etc/systemd/system/bmchat-tgproxy.service.d/override.conf <<'EOF'
[Service]
Environment=BMCHAT_TGPROXY_API_BASE=http://127.0.0.1:8083
Environment=BMCHAT_TGPROXY_API_LOCAL=1
EOF
    systemctl daemon-reload && systemctl restart bmchat-tgproxy && \
    sleep 1 && systemctl --no-pager status bmchat-tgproxy | head -6"

echo "[uploader] sanity-checking endpoints"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/update.json | head -6"
${SSH} "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/apk/${APK_NAME} | head -4"
${SSH} "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/tgmedia/healthz"
${SSH} "curl -sS -o /dev/null -w 'GET /bot-api/ -> %{http_code}\n' http://127.0.0.1/bot-api/"
echo "[uploader] done."
