#!/usr/bin/env bash
set -euo pipefail
TMP=/tmp/bmchat-verify-$$
mkdir -p "$TMP"
PRIMARY=http://5.187.4.132
MIRROR=http://158.160.104.107:8080

check_server() {
  local base="$1" name="$2"
  echo "===== $name ($base) ====="
  curl -fsS "$base/update.json" -o "$TMP/update.json"
  python3 - <<PY
import json
m=json.load(open("$TMP/update.json"))
print("update.json:", m.get("versionName"), "code", m.get("versionCode"), "size", m.get("size"), "sha256", m.get("sha256"))
PY
  apk_path=$(python3 -c "import json; print(json.load(open('$TMP/update.json'))['url'].replace('http://5.187.4.132','$base'))")
  curl -fsS "$apk_path" -o "$TMP/apk.apk"
  apk_size=$(stat -c%s "$TMP/apk.apk")
  apk_sha=$(sha256sum "$TMP/apk.apk" | awk '{print $1}')
  echo "APK downloaded: size=$apk_size sha256=$apk_sha"
  python3 - <<PY
import json
m=json.load(open("$TMP/update.json"))
ok = m.get("size")==$apk_size and m.get("sha256")== "$apk_sha"
print("APK manifest match:", ok)
PY

  curl -fsS "$base/desktop-update.json" -o "$TMP/desktop-update.json"
  python3 - <<PY
import json
m=json.load(open("$TMP/desktop-update.json"))
print("desktop-update.json version:", m.get("version"))
for k in ("win-x64-installer","win-x64-portable"):
  p=m["platforms"][k]
  print(k+":", p["versionedFile"], "size", p["size"], "sha256", p["sha256"])
PY

  for f in BMChat-2.50.15-Setup.x64.exe BMChat-2.50.15-Portable.x64.exe; do
    if curl -fsSI "$base/desktop/$f" | head -1 | grep -q 200; then
      curl -fsS "$base/desktop/$f" -o "$TMP/$f"
      sz=$(stat -c%s "$TMP/$f")
      sha=$(sha256sum "$TMP/$f" | awk '{print $1}')
      echo "$f: size=$sz sha256=$sha"
    else
      echo "$f: HTTP NOT FOUND"
    fi
  done

  for alias in BMChat-Setup-x64.exe BMChat-portable-x64.exe; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -I "$base/desktop/$alias")
    echo "alias $alias: HTTP $code"
  done
  echo
}

check_server "$PRIMARY" "PRIMARY"
check_server "$MIRROR" "MIRROR"
rm -rf "$TMP"
