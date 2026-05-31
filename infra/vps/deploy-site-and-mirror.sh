#!/usr/bin/env bash
# BMChat distribution rollout — pushes the static site, the latest
# APK and update.json to BOTH the primary host (5.187.4.132) and
# the WhiteBlade mirror (158.160.104.107:8080).
#
# Idempotent. Re-running just refreshes whichever artifacts changed.
# All deltas are computed locally; the servers don't need to talk
# to each other.
set -euo pipefail

# ---- primary VPS (Fornex, EU) ----
PRIM_HOST="${PRIM_HOST:-5.187.4.132}"
PRIM_USER="${PRIM_USER:-root}"
# Password comes from BMCHAT_PRIM_SSH_PASS env when running in CI
# (don't bake it into the script under VCS); fall back to the
# baked-in development credential locally.
PRIM_PASS="${BMCHAT_PRIM_SSH_PASS:-${PRIM_PASS:-wog11vrtfwjuoy4uwm}}"
PRIM_ROOT="${PRIM_ROOT:-/var/www/bmchat}"

# ---- WhiteBlade mirror (Yandex Cloud, RU) ----
MIRROR_HOST="${MIRROR_HOST:-158.160.104.107}"
MIRROR_USER="${MIRROR_USER:-dante}"
MIRROR_KEY="${MIRROR_KEY:-${HOME}/.ssh/yc_whiteblade}"
MIRROR_ROOT="${MIRROR_ROOT:-/var/www/bmchat}"

LOCAL_ROOT="/mnt/e/PPROJECTS/BMChat/infra/vps"

# How many of the most recent APK builds to mirror (the WhiteBlade
# disk is only 9 GB, no point pushing every legacy build).
MIRROR_KEEP_LAST=3

PRIM_SSH() { sshpass -p "${PRIM_PASS}" ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 "${PRIM_USER}@${PRIM_HOST}" "$@"; }
PRIM_RSYNC_OPTS=(-azh -e "ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15")

MIRROR_SSH() { ssh -i "${MIRROR_KEY}" -o StrictHostKeyChecking=no -o ServerAliveInterval=15 "${MIRROR_USER}@${MIRROR_HOST}" "$@"; }
MIRROR_RSYNC_OPTS=(-azh -e "ssh -i ${MIRROR_KEY} -o StrictHostKeyChecking=no -o ServerAliveInterval=15" --rsync-path="sudo rsync")

# ----------------------------------------------------------------
echo "==[ 1/5 ] primary: site + manifests + static =================="
PRIM_SSH "mkdir -p /tmp/bmchat-payload/{www,static,desktop}"
sshpass -p "${PRIM_PASS}" rsync "${PRIM_RSYNC_OPTS[@]}" \
  "${LOCAL_ROOT}/www/index.html" \
  "${LOCAL_ROOT}/www/i.html" \
  "${LOCAL_ROOT}/www/update.json" \
  "${LOCAL_ROOT}/www/desktop-update.json" \
  "${PRIM_USER}@${PRIM_HOST}:/tmp/bmchat-payload/www/"
sshpass -p "${PRIM_PASS}" rsync "${PRIM_RSYNC_OPTS[@]}" \
  "${LOCAL_ROOT}/www/static/" \
  "${PRIM_USER}@${PRIM_HOST}:/tmp/bmchat-payload/static/"

PRIM_SSH "set -e
  install -m 0644 /tmp/bmchat-payload/www/index.html         ${PRIM_ROOT}/index.html
  install -m 0644 /tmp/bmchat-payload/www/i.html             ${PRIM_ROOT}/i.html
  install -m 0644 /tmp/bmchat-payload/www/update.json        ${PRIM_ROOT}/update.json
  install -m 0644 /tmp/bmchat-payload/www/desktop-update.json ${PRIM_ROOT}/desktop-update.json
  mkdir -p ${PRIM_ROOT}/static
  rsync -a --delete /tmp/bmchat-payload/static/ ${PRIM_ROOT}/static/
  chown -R www-data:www-data ${PRIM_ROOT}/index.html ${PRIM_ROOT}/i.html ${PRIM_ROOT}/update.json ${PRIM_ROOT}/desktop-update.json ${PRIM_ROOT}/static
"

echo "==[ 2/5 ] primary: recent APKs + latest alias ==================="
LATEST_APKS=$(ls -1t "${LOCAL_ROOT}/apk/"BMChat-foss-debug-*.apk 2>/dev/null | head -n ${MIRROR_KEEP_LAST})
if [ -z "${LATEST_APKS}" ]; then
  echo "[primary] WARNING: no APKs in ${LOCAL_ROOT}/apk/, primary won't have any builds"
else
  echo "[primary] pushing $(echo "${LATEST_APKS}" | wc -l) APK(s):"
  echo "${LATEST_APKS}"
  PRIM_SSH "mkdir -p ${PRIM_ROOT}/apk"
  sshpass -p "${PRIM_PASS}" rsync "${PRIM_RSYNC_OPTS[@]}" ${LATEST_APKS} \
    "${PRIM_USER}@${PRIM_HOST}:${PRIM_ROOT}/apk/"
  NEWEST=$(basename "$(echo "${LATEST_APKS}" | head -n 1)")
  PRIM_SSH "set -e
    cd ${PRIM_ROOT}/apk
    ln -sfn ${NEWEST} BMChat-foss-debug-latest.apk
    chown -R www-data:www-data ${PRIM_ROOT}/apk
    ls -la ${PRIM_ROOT}/apk/ | tail -8
  "
fi

echo "==[ 3/5 ] primary: desktop binaries + stable aliases ==========="
if [ -d "${LOCAL_ROOT}/desktop" ] && [ -n "$(ls -A "${LOCAL_ROOT}/desktop" 2>/dev/null)" ]; then
  sshpass -p "${PRIM_PASS}" rsync "${PRIM_RSYNC_OPTS[@]}" \
    "${LOCAL_ROOT}/desktop/" \
    "${PRIM_USER}@${PRIM_HOST}:/tmp/bmchat-payload/desktop/"

  # Move into place and create the "stable" filename aliases the
  # landing page links to. Symlinks let us bump the version without
  # having to update the HTML or the URL contract.
  PRIM_SSH "set -e
    mkdir -p ${PRIM_ROOT}/desktop
    rsync -a /tmp/bmchat-payload/desktop/ ${PRIM_ROOT}/desktop/
    cd ${PRIM_ROOT}/desktop
    appimg=\$(ls -1t BMChat-*-x86_64.AppImage 2>/dev/null | head -1)
    deb=\$(ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | head -1)
    setup=\$(ls -1t BMChat-*-Setup.x64.exe 2>/dev/null | head -1)
    portable=\$(ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | head -1)
    [ -n \"\$appimg\"  ] && ln -sfn \"\$appimg\"  BMChat-x86_64.AppImage
    [ -n \"\$deb\"     ] && ln -sfn \"\$deb\"     bmchat-amd64.deb
    [ -n \"\$setup\"   ] && ln -sfn \"\$setup\"   BMChat-Setup-x64.exe
    [ -n \"\$portable\" ] && ln -sfn \"\$portable\" BMChat-portable-x64.exe
    chown -R www-data:www-data ${PRIM_ROOT}/desktop
    ls -la ${PRIM_ROOT}/desktop | head -20
  "
else
  echo "[primary] no desktop binaries in ${LOCAL_ROOT}/desktop, skipping"
fi

echo "==[ 4/5 ] mirror: site + manifests + static + recent APKs + desktop ====="
# Make sure mirror root is writeable for our SSH user. /var/www
# belongs to root, so we use sudo rsync via --rsync-path.
MIRROR_SSH "sudo mkdir -p ${MIRROR_ROOT}/{apk,desktop,static} && sudo chown -R dante:dante ${MIRROR_ROOT}"
rsync "${MIRROR_RSYNC_OPTS[@]}" \
  "${LOCAL_ROOT}/www/index.html" \
  "${LOCAL_ROOT}/www/i.html" \
  "${LOCAL_ROOT}/www/update.json" \
  "${LOCAL_ROOT}/www/desktop-update.json" \
  "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/"
rsync "${MIRROR_RSYNC_OPTS[@]}" --delete \
  "${LOCAL_ROOT}/www/static/" \
  "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/static/"

# Pick the last MIRROR_KEEP_LAST APK builds from local repo.
LATEST_APKS=$(ls -1t "${LOCAL_ROOT}/apk/"BMChat-foss-debug-*.apk 2>/dev/null | head -n ${MIRROR_KEEP_LAST})
if [ -z "${LATEST_APKS}" ]; then
  echo "[mirror] WARNING: no APKs in ${LOCAL_ROOT}/apk/, mirror won't have any builds"
else
  echo "[mirror] pushing $(echo "${LATEST_APKS}" | wc -l) APK(s):"
  echo "${LATEST_APKS}"
  rsync "${MIRROR_RSYNC_OPTS[@]}" ${LATEST_APKS} \
    "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/apk/"
  # Maintain the "-latest.apk" symlink server-side.
  NEWEST=$(basename "$(echo "${LATEST_APKS}" | head -n 1)")
  MIRROR_SSH "sudo ln -sfn ${NEWEST} ${MIRROR_ROOT}/apk/BMChat-foss-debug-latest.apk && sudo chown -R www-data:www-data ${MIRROR_ROOT}"
fi

# Desktop binaries + stable aliases. The mirror only stores the
# current release (the disk is 9 GB), so we --delete everything
# that does not belong to it.
if [ -d "${LOCAL_ROOT}/desktop" ] && [ -n "$(ls -A "${LOCAL_ROOT}/desktop" 2>/dev/null)" ]; then
  rsync "${MIRROR_RSYNC_OPTS[@]}" --delete-excluded \
    --include="BMChat-*.AppImage" \
    --include="bmchat-desktop_*_amd64.deb" \
    --include="BMChat-*-Setup.x64.exe" \
    --include="BMChat-*-Portable.x64.exe" \
    --exclude="*" \
    "${LOCAL_ROOT}/desktop/" \
    "${MIRROR_USER}@${MIRROR_HOST}:${MIRROR_ROOT}/desktop/"
  MIRROR_SSH "set -e
    cd ${MIRROR_ROOT}/desktop
    appimg=\$(ls -1t BMChat-*-x86_64.AppImage 2>/dev/null | head -1)
    deb=\$(ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | head -1)
    setup=\$(ls -1t BMChat-*-Setup.x64.exe 2>/dev/null | head -1)
    portable=\$(ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | head -1)
    [ -n \"\$appimg\"   ] && sudo ln -sfn \"\$appimg\"   BMChat-x86_64.AppImage
    [ -n \"\$deb\"      ] && sudo ln -sfn \"\$deb\"      bmchat-amd64.deb
    [ -n \"\$setup\"    ] && sudo ln -sfn \"\$setup\"    BMChat-Setup-x64.exe
    [ -n \"\$portable\" ] && sudo ln -sfn \"\$portable\" BMChat-portable-x64.exe
    sudo chown -R www-data:www-data ${MIRROR_ROOT}/desktop
    ls -la ${MIRROR_ROOT}/desktop | head -20
  "
fi

echo "==[ 5/5 ] verify =============================================="
echo "--- primary"
PRIM_SSH "curl -sS -H 'Host: 5.187.4.132' http://127.0.0.1/update.json | head -10"
PRIM_SSH "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/ | head -2"
PRIM_SSH "curl -sS -I -H 'Host: 5.187.4.132' http://127.0.0.1/apk/BMChat-foss-debug-latest.apk | head -2"
PRIM_SSH "for f in BMChat-x86_64.AppImage bmchat-amd64.deb BMChat-Setup-x64.exe BMChat-portable-x64.exe; do
  printf '  %-32s ' \"\$f\"
  curl -sS -o /dev/null -w 'HTTP %{http_code}  size=%{size_download}\n' -I -H 'Host: 5.187.4.132' http://127.0.0.1/desktop/\$f
done"
echo "--- mirror"
MIRROR_SSH "curl -sS http://127.0.0.1:8080/update.json | head -10"
MIRROR_SSH "curl -sS -I http://127.0.0.1:8080/ | head -2"
MIRROR_SSH "curl -sS -I http://127.0.0.1:8080/apk/BMChat-foss-debug-latest.apk | head -2"
MIRROR_SSH "for f in BMChat-x86_64.AppImage bmchat-amd64.deb BMChat-Setup-x64.exe BMChat-portable-x64.exe; do
  printf '  %-32s ' \"\$f\"
  curl -sS -o /dev/null -w 'HTTP %{http_code}  size=%{size_download}\n' -I http://127.0.0.1:8080/desktop/\$f
done"

echo "[deploy] done."
echo "  Primary: http://${PRIM_HOST}/"
echo "  Mirror : http://${MIRROR_HOST}:8080/"
