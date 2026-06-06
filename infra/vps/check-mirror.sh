#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/mirror-env.sh
source "$SCRIPT_DIR/lib/mirror-env.sh"

ssh -i "$KEY" -o StrictHostKeyChecking=no "$HOST" bash -se <<'EOF'
head -6 /var/www/html/update.json
echo "---nginx---"
sudo grep -r "root\|listen\|8080\|apk" /etc/nginx/sites-enabled/ 2>/dev/null | head -40
EOF
