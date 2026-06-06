#!/usr/bin/env bash
set -euo pipefail
_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_VPS_ROOT="$(cd "$_LIB_DIR/.." && pwd)"
if [[ -f "$_VPS_ROOT/.deploy.env" ]]; then
  source "$_VPS_ROOT/.deploy.env"
fi
: "${BMCHAT_MIRROR_HOST:?Set BMCHAT_MIRROR_HOST in env or infra/vps/.deploy.env}"
: "${BMCHAT_MIRROR_USER:?Set BMCHAT_MIRROR_USER in env or infra/vps/.deploy.env}"
: "${BMCHAT_MIRROR_SSH_KEY:?Set BMCHAT_MIRROR_SSH_KEY to SSH private key path}"
MIRROR_HOST="$BMCHAT_MIRROR_HOST"
MIRROR_USER="$BMCHAT_MIRROR_USER"
MIRROR_KEY="$BMCHAT_MIRROR_SSH_KEY"
MIRROR_ROOT="${BMCHAT_MIRROR_ROOT:-/var/www/bmchat}"
KEY="$BMCHAT_MIRROR_SSH_KEY"
HOST="${MIRROR_USER}@${MIRROR_HOST}"
SSH="ssh -i ${KEY} -o StrictHostKeyChecking=no -o ServerAliveInterval=15"
RSYNC="rsync -azvh -e '${SSH}'"

