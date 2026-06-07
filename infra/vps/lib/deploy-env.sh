#!/usr/bin/env bash
set -euo pipefail
_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_VPS_ROOT="$(cd "$_LIB_DIR/.." && pwd)"
if [[ -f "$_VPS_ROOT/.deploy.env" ]]; then
  source "$_VPS_ROOT/.deploy.env"
fi
BMCHAT_VPS_PASS="${BMCHAT_VPS_PASS:-${BMCHAT_PRIM_SSH_PASS:-}}"
: "${BMCHAT_VPS_HOST:=${BMCHAT_PRIM_HOST:-}}"
: "${BMCHAT_VPS_USER:=${BMCHAT_PRIM_USER:-}}"
: "${BMCHAT_VPS_HOST:?Set BMCHAT_VPS_HOST in env or infra/vps/.deploy.env}"
: "${BMCHAT_VPS_USER:?Set BMCHAT_VPS_USER in env or infra/vps/.deploy.env}"
: "${BMCHAT_VPS_PASS:?Set BMCHAT_VPS_PASS or BMCHAT_PRIM_SSH_PASS}"
VPS_HOST="$BMCHAT_VPS_HOST"
VPS_USER="$BMCHAT_VPS_USER"
VPS_PASS="$BMCHAT_VPS_PASS"
PRIM_HOST="$VPS_HOST"
PRIM_USER="$VPS_USER"
PRIM_PASS="$VPS_PASS"
export SSHPASS="${VPS_PASS}"
SSH="sshpass -e ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15 ${VPS_USER}@${VPS_HOST}"
RSYNC="sshpass -e rsync -azvh -e 'ssh -o StrictHostKeyChecking=no -o ServerAliveInterval=15'"

