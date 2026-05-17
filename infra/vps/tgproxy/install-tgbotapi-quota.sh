#!/usr/bin/env bash
set -euo pipefail

# Move telegram-bot-api's data directory onto a dedicated 8 GB loop
# volume. Two reasons:
#
#   1. Hard quota — even an avalanche of concurrent video downloads
#      can't fill the whole root FS (where apache2, mariadb, mail,
#      nod-tracker, hestia and BMChat itself live). When the bot
#      hits the cap, it gets ENOSPC on its own partition; the rest
#      of the VPS keeps humming.
#
#   2. Cleanup is easier — we know exactly what to mtime-prune
#      because everything under /var/lib/telegram-bot-api is owned
#      by this one service.
#
# Preserves td.binlog (per-bot MTProto session state); losing it
# would force a re-authorization on the local Bot API server.

IMAGE="/opt/bmchat/tgbotapi-store.img"
MOUNTPOINT="/var/lib/telegram-bot-api"
SIZE_GB=8
SERVICE="bmchat-tg-bot-api"

echo "[quota] === preflight ==="
df -h /
free -h | head -2

if mount | grep -q "on ${MOUNTPOINT} "; then
    echo "[quota] ${MOUNTPOINT} is already on its own mount — nothing to do"
    exit 0
fi

echo "[quota] stopping ${SERVICE}"
systemctl stop "${SERVICE}"

# Tiny — under 100 MB right now — so a plain cp -a snapshot is fast
# and obviously correct. We keep the snapshot until the very end so
# any failure in the middle is recoverable by `mv` back.
SNAPSHOT="${MOUNTPOINT}.snapshot.$(date +%Y%m%d%H%M%S)"
echo "[quota] snapshotting ${MOUNTPOINT} -> ${SNAPSHOT}"
mv "${MOUNTPOINT}" "${SNAPSHOT}"
mkdir -p "${MOUNTPOINT}"
chmod 0700 "${MOUNTPOINT}"

if [[ ! -f "${IMAGE}" ]]; then
    echo "[quota] allocating ${SIZE_GB} GB loop image at ${IMAGE}"
    fallocate -l "${SIZE_GB}G" "${IMAGE}"
    chmod 0600 "${IMAGE}"
    echo "[quota] formatting ext4"
    mkfs.ext4 -F -L bmchat-tgbotapi "${IMAGE}"
else
    echo "[quota] reusing existing image ${IMAGE}"
fi

echo "[quota] mounting"
mount -o loop,noexec,nosuid "${IMAGE}" "${MOUNTPOINT}"

echo "[quota] restoring data"
cp -a "${SNAPSHOT}/." "${MOUNTPOINT}/"
chmod 0700 "${MOUNTPOINT}"

echo "[quota] adding fstab entry"
if ! grep -qF "${IMAGE}" /etc/fstab; then
    echo "${IMAGE} ${MOUNTPOINT} ext4 loop,noexec,nosuid,nofail,defaults 0 2" >> /etc/fstab
fi

echo "[quota] starting ${SERVICE}"
systemctl start "${SERVICE}"

sleep 2
echo "[quota] === post-state ==="
systemctl --no-pager status "${SERVICE}" | head -8
echo
df -h "${MOUNTPOINT}"
echo
echo "[quota] snapshot left at ${SNAPSHOT} — verify the bot works, then:"
echo "        rm -rf ${SNAPSHOT}"
