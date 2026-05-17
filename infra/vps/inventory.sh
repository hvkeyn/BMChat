#!/usr/bin/env bash
# Read-only inventory: what's running, what's listening, who owns what.
# DOES NOT touch any state — used before resuming the tdlib build so
# we don't trample on nod-tracker / братский сайт / shadowbox.
set +e

echo "============= MEMORY ============="
free -h
echo
echo "============= DISK ==============="
df -h /
echo
echo "============= SWAP ==============="
swapon --show
echo
echo "============= SYSTEMD ACTIVE ============="
systemctl list-units --state=running --type=service --no-pager | head -40
echo
echo "============= LISTENING PORTS (sorted) ============="
ss -lntp | head -40
echo
echo "============= NGINX vhosts ============="
ls /etc/nginx/sites-enabled/ 2>&1
echo
echo "--- /etc/nginx/sites-enabled/nod-tracker (server_name lines) ---"
grep -nE 'server_name|listen ' /etc/nginx/sites-enabled/nod-tracker 2>&1 | head -20
echo
echo "============= apache2 vhosts ============="
ls /etc/apache2/sites-enabled/ 2>&1
echo
echo "============= bmchat services state ============="
systemctl --no-pager status bmchat-tgproxy 2>&1 | head -5
systemctl --no-pager status bmchat-tg-bot-api 2>&1 | head -5
echo
echo "============= tdlib build process ============="
pgrep -af 'cmake|build-local-bot|cc1plus' | head -10
echo
echo "============= last 5 build log lines ============="
tail -5 /var/log/bmchat-tgbotapi-build.log 2>&1
echo
echo "============= /opt/bmchat contents ============="
ls -la /opt/bmchat/ 2>&1
ls -la /opt/bmchat/tgbotapi/bin/ 2>&1 || echo "(no binary yet)"
