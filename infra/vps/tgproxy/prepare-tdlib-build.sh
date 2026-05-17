#!/usr/bin/env bash
set -euo pipefail

# Prepare a small low-RAM VPS for tdlib compilation:
#  1. Free up disk by clearing apt caches and previous build dirs.
#  2. Create a 2 GB swap file so the OOM killer doesn't murder cc1plus
#     halfway through tdcore.dir (memory peaks ~1.7 GB per source file
#     on this branch — 1.9 GB physical RAM is not enough alone).

echo "[prep] disk before"
df -h /

echo "[prep] cleaning apt caches and old logs"
apt-get clean
journalctl --vacuum-time=3d
rm -rf /var/lib/telegram-bot-api/_old 2>/dev/null || true
# Earlier build attempt left tdlib's CMake intermediates around — they
# take ~1 GB. CMake will recreate them, no harm in nuking.
rm -rf /usr/local/src/telegram-bot-api/build

echo "[prep] disk after cleanup"
df -h /

if ! swapon --show | grep -q "/swapfile"; then
    echo "[prep] creating 2 GB /swapfile"
    fallocate -l 2G /swapfile
    chmod 0600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    # Persist across reboots.
    if ! grep -q "^/swapfile " /etc/fstab; then
        echo "/swapfile none swap sw 0 0" >> /etc/fstab
    fi
else
    echo "[prep] /swapfile already active, skipping"
fi

echo "[prep] memory state"
free -h
echo "[prep] swap"
swapon --show
