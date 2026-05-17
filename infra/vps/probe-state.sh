#!/usr/bin/env bash
set +e
echo "== pid 2997657 =="
ps -p 2997657 -o pid,ppid,user,cmd= 2>&1 || echo "gone"
echo
echo "== telegram-bot-api binaries =="
which telegram-bot-api
ls -la /opt/telegram-bot-api/ 2>&1 | head -20
ls -la /var/lib/telegram-bot-api/ 2>&1 | head -10
echo
echo "== ports listening (interesting) =="
ss -lntp | grep -E ':(8080|8081|8082|8083) '
echo
echo "== systemd units =="
systemctl list-units --type=service --no-pager | grep -Ei 'telegram|bot-api'
ls /etc/systemd/system/ | grep -iE 'telegram|bot-api'
echo
echo "== docker ps =="
docker ps -a
echo
echo "== docker images =="
docker images
echo
echo "== free disk =="
df -h /opt /var
