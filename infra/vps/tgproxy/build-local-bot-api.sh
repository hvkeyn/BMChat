#!/usr/bin/env bash
set -euo pipefail

# Build telegram-bot-api from source on the VPS. Used when Docker Hub
# is unreachable from this datacenter (typical: TLS handshake to
# registry-1.docker.io times out). The resulting binary is the same
# tdlib-backed server the official docs recommend.

INSTALL_PREFIX="/opt/bmchat/tgbotapi"
SRC_DIR="/usr/local/src/telegram-bot-api"
BINARY="${INSTALL_PREFIX}/bin/telegram-bot-api"
SERVICE_NAME="bmchat-tg-bot-api"
DATA_DIR="/var/lib/telegram-bot-api"

echo "[build] host info"
echo "  cpu cores: $(nproc)"
free -h | head -2
df -h /usr/local/src /opt

if [[ -x "${BINARY}" ]]; then
    echo "[build] ${BINARY} already exists, version:"
    "${BINARY}" --version 2>&1 | head -3 || true
    echo "[build] skipping rebuild — pass FORCE_REBUILD=1 to override"
    if [[ "${FORCE_REBUILD:-0}" != "1" ]]; then
        exit 0
    fi
fi

echo "[build] installing build deps"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
    build-essential cmake gperf zlib1g-dev libssl-dev \
    git ca-certificates

echo "[build] cloning telegram-bot-api into ${SRC_DIR}"
if [[ ! -d "${SRC_DIR}/.git" ]]; then
    rm -rf "${SRC_DIR}"
    git clone --depth 1 --recursive https://github.com/tdlib/telegram-bot-api.git "${SRC_DIR}"
else
    cd "${SRC_DIR}"
    git pull
    git submodule update --init --recursive
fi

mkdir -p "${SRC_DIR}/build"
cd "${SRC_DIR}/build"

echo "[build] configuring (MinSizeRel — keeps per-TU peak RAM well below 1 GB)"
# A vanilla Release build of td/telegram/BackgroundManager.cpp peaks
# at ~1.8 GB of resident memory, which OOM-kills cc1plus on a 1.9 GB
# VPS. MinSizeRel + GCC's -fno-var-tracking-assignments drops the
# peak to ~700 MB, which leaves enough head-room for the rest of
# userspace to keep ticking.
cmake -DCMAKE_BUILD_TYPE=MinSizeRel \
      -DCMAKE_CXX_FLAGS_MINSIZEREL="-Os -DNDEBUG -fno-var-tracking-assignments -g0" \
      -DCMAKE_C_FLAGS_MINSIZEREL="-Os -DNDEBUG -g0" \
      -DCMAKE_INSTALL_PREFIX="${INSTALL_PREFIX}" \
      ..

echo "[build] compiling — 2 jobs fit comfortably into 3.8 GB + 2 GB swap"
# 1 vCPU + 4 GB now (May 2026 VPS resize). Two parallel cc1plus
# instances each peak around 800 MB with MinSizeRel and easily fit
# under physical RAM, while swap stays available as a safety net.
cmake --build . --target install -j 2

echo "[build] done. binary:"
ls -la "${BINARY}"
"${BINARY}" --version | head -3 || true

mkdir -p "${DATA_DIR}"
chmod 0700 "${DATA_DIR}"
