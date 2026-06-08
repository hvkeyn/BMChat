#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/deploy-env.sh"
eval ${RSYNC} "${SCRIPT_DIR}/www/desktop-update.json" "${VPS_USER}@${VPS_HOST}:/tmp/desktop-update.json"
${SSH} "install -m 0644 /tmp/desktop-update.json /var/www/bmchat/desktop-update.json && chown www-data:www-data /var/www/bmchat/desktop-update.json"
source "$SCRIPT_DIR/lib/mirror-env.sh"
eval ${RSYNC} "${SCRIPT_DIR}/www/desktop-update.json" "${HOST}:/tmp/desktop-update.json"
${SSH} ${HOST} "sudo install -m 0644 /tmp/desktop-update.json /var/www/bmchat/desktop-update.json && sudo chown www-data:www-data /var/www/bmchat/desktop-update.json"
echo "[deploy] desktop-update.json done."