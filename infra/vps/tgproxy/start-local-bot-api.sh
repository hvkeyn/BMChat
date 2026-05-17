#!/usr/bin/env bash
set -euo pipefail

# Idempotent bootstrap for the local Telegram Bot API server. Run on
# the VPS after we've got an api_id/api_hash from my.telegram.org.
# The container exposes its HTTP interface on 127.0.0.1:8083 (the
# more obvious 8081 is already taken by apache2, 8082 by a uvicorn
# service). Nginx then publishes it at https://5.187.4.132/bot-api/.

CONTAINER_NAME="bmchat-tg-bot-api"
IMAGE="aiogram/telegram-bot-api:latest"
HOST_PORT="8083"
DATA_DIR="/var/lib/telegram-bot-api"

if [[ -z "${TELEGRAM_API_ID:-}" || -z "${TELEGRAM_API_HASH:-}" ]]; then
    echo "FATAL: pass TELEGRAM_API_ID and TELEGRAM_API_HASH via env" >&2
    exit 2
fi

mkdir -p "${DATA_DIR}"
chmod 0700 "${DATA_DIR}"

echo "[local-bot-api] pulling ${IMAGE}"
docker pull "${IMAGE}"

echo "[local-bot-api] removing old container (if any)"
docker rm -f "${CONTAINER_NAME}" 2>/dev/null || true

# `--local` makes Bot API:
#   * lift the 20 MB cap on getFile (files up to 2000 MB);
#   * return absolute file system paths for downloaded media (we read
#     them directly from ${DATA_DIR} in bmchat-tgproxy).
# We bind to 127.0.0.1 only — nginx is responsible for TLS / TLS-by-IP
# fronting and for restricting access to the BMChat client.
echo "[local-bot-api] launching ${CONTAINER_NAME}"
docker run -d \
    --name "${CONTAINER_NAME}" \
    --restart=always \
    -p 127.0.0.1:${HOST_PORT}:8081 \
    -v "${DATA_DIR}:${DATA_DIR}" \
    -e TELEGRAM_API_ID="${TELEGRAM_API_ID}" \
    -e TELEGRAM_API_HASH="${TELEGRAM_API_HASH}" \
    "${IMAGE}" \
    --local \
    --http-port=8081 \
    --dir="${DATA_DIR}"

echo "[local-bot-api] waiting for HTTP readiness"
for i in $(seq 1 30); do
    if curl -sf "http://127.0.0.1:${HOST_PORT}/" -o /dev/null --max-time 2; then
        echo "[local-bot-api] up after ${i}s"
        break
    fi
    sleep 1
done

echo "[local-bot-api] container status"
docker ps --filter "name=${CONTAINER_NAME}" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

echo "[local-bot-api] last 20 log lines"
docker logs --tail 20 "${CONTAINER_NAME}" 2>&1 | sed 's/^/    /' || true
