#!/usr/bin/env bash
# BMChat — deploy update.json + latest APK(s) to Yandex mirror only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"

LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"
KEEP_APKS="${KEEP_APKS:-3}"

if [[ ! -f "$KEY" ]]; then
  echo "[mirror] SSH key not found: $KEY" >&2
  exit 1
fi

# Use a copy with strict perms when the key lives on NTFS (mode 0777).
SSH_KEY="$KEY"
if [[ -d "$HOME/.ssh" ]]; then
  SAFE_KEY="$HOME/.ssh/bmchat_mirror_deploy"
  cp -f "$KEY" "$SAFE_KEY"
  chmod 600 "$SAFE_KEY"
  SSH_KEY="$SAFE_KEY"
fi

SSH=(ssh -i "$SSH_KEY" -o StrictHostKeyChecking=no -o ServerAliveInterval=15
  "${MIRROR_USER}@${MIRROR_HOST}")
RSYNC=(rsync -azvh --progress
  -e "ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no -o ServerAliveInterval=15"
  --rsync-path="sudo rsync")

APK="${APK_NAME:-BMChat-foss-debug-2.50.10.apk}"
if [[ ! -f "${LOCAL_ROOT}/apk/${APK}" ]]; then
  echo "[mirror] missing ${LOCAL_ROOT}/apk/${APK}" >&2
  exit 1
fi

echo "==[ 0 ] mirror disk usage ====================================="
"${SSH[@]}" "df -h / /var/www 2>/dev/null || df -h; du -sh ${MIRROR_ROOT}/* 2>/dev/null | sort -h | tail -10"

echo "==[ 1 ] free space: drop old APKs (keep ${KEEP_APKS}) ========="
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
sudo mkdir -p ${MIRROR_ROOT}/apk
cd ${MIRROR_ROOT}/apk
ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +$((KEEP_APKS + 1)) | while read -r f; do
  echo "  rm old \$f"
  sudo rm -f "\$f"
done
EOF

echo "==[ 2 ] push update.json ======================================"
"${SSH[@]}" "sudo mkdir -p ${MIRROR_ROOT}"
"${RSYNC[@]}" "${LOCAL_ROOT}/www/update.json" \
  "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/update.json"

echo "==[ 3 ] push APK ${APK} ========================================"
"${RSYNC[@]}" "${LOCAL_ROOT}/apk/${APK}" \
  "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/apk/"

echo "==[ 4 ] symlinks + permissions ================================"
"${SSH[@]}" bash -se <<EOF
set -euo pipefail
sudo ln -sfn ${APK} ${MIRROR_ROOT}/apk/BMChat-foss-debug-latest.apk
sudo chown -R www-data:www-data ${MIRROR_ROOT}/update.json ${MIRROR_ROOT}/apk
ls -la ${MIRROR_ROOT}/update.json ${MIRROR_ROOT}/apk/ | tail -8
EOF

echo "==[ 5 ] verify (local curl) ==================================="
curl -fsS "http://${MIRROR_HOST}:8080/update.json" | head -8
echo "---"
curl -fsSI "http://${MIRROR_HOST}:8080/apk/${APK}" | head -4

echo "[mirror] done: http://${MIRROR_HOST}:8080/"
