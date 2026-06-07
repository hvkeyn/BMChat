#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=lib/deploy-env.sh
source "$SCRIPT_DIR/lib/deploy-env.sh"
echo "========== primary VPS — deep clean =========="
${SSH} "set -e
  df -h /
  cd /var/www/bmchat/desktop
  for f in BMChat-2.50.10-x86_64.AppImage bmchat-desktop_2.50.10_amd64.deb BMChat-2.50.20-Portable.x64.exe BMChat-2.50.20-Setup.x64.exe; do
    [ -f \"\$f\" ] && rm -fv \"\$f\" || true
  done
  ls -1t BMChat-*-Setup.x64.exe 2>/dev/null | tail -n +2 | xargs -r rm -fv || true
  ls -1t BMChat-*-Portable.x64.exe 2>/dev/null | tail -n +2 | xargs -r rm -fv || true
  ls -1t BMChat-*-x86_64.AppImage 2>/dev/null | tail -n +2 | xargs -r rm -fv || true
  ls -1t bmchat-desktop_*_amd64.deb 2>/dev/null | tail -n +2 | xargs -r rm -fv || true
  cd /var/www/bmchat/apk
  ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +3 | xargs -r rm -fv || true
  rm -rf /tmp/bmchat-* 2>/dev/null || true
  echo '--- result ---'
  df -h /
  du -sh /var/www/bmchat/* | sort -h
  ls -lah /var/www/bmchat/apk
  ls -lah /var/www/bmchat/desktop
"

# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"
echo "========== mirror VPS — deep clean =========="
${SSH} ${HOST} "set -e
  df -h /
  cd /var/www/bmchat/desktop
  sudo ls -1 BMChat-* bmchat-desktop_* 2>/dev/null | grep -v '2.50.23' | grep -v '^BMChat-Setup' | grep -v '^BMChat-portable' | grep -v '^BMChat-x86' | grep -v '^bmchat-amd64.deb\$' | while read -r f; do
    case \"\$f\" in BMChat-2.50.23*|bmchat-desktop_2.50.23*) ;; *) sudo rm -fv \"\$f\" ;; esac
  done
  cd /var/www/bmchat/apk
  sudo ls -1t BMChat-foss-debug-*.apk 2>/dev/null | tail -n +3 | xargs -r sudo rm -fv || true
  sudo rm -rf /tmp/bmchat-* 2>/dev/null || true
  echo '--- result ---'
  df -h /
  sudo du -sh /var/www/bmchat/* | sort -h
  sudo ls -lah /var/www/bmchat/apk
  sudo ls -lah /var/www/bmchat/desktop
"
echo "[deep-clean] done."