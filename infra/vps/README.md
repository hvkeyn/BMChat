# BMChat distribution infrastructure

Где живёт BMChat в эфире: Android-APK, desktop-бинари, сайт раздачи,
два сервера (primary + mirror), скрипты деплоя и GitHub Actions для
автоматических релизов.

## Слои

```
┌──────────────────────┐       ┌──────────────────────┐
│ primary (Fornex/EU)  │       │ mirror (YC/RU)       │
│ 5.187.4.132          │       │ 158.160.104.107:8080 │
│ /var/www/bmchat      │       │ /var/www/bmchat      │
│  ├── index.html      │       │  ├── index.html      │
│  ├── i.html          │       │  ├── i.html          │
│  ├── update.json     │  ←──→ │  ├── update.json     │
│  ├── desktop-update  │       │  ├── desktop-update  │
│  ├── apk/  (полная)  │       │  ├── apk/  (топ-3)   │
│  └── desktop/        │       │  └── desktop/        │
└──────────────────────┘       └──────────────────────┘
        ↑                              ↑
        └──── deploy-site-and-mirror.sh (CI или local)
```

Клиенты идут по цепочке:
**primary → manifest-supplied mirrors → built-in DEFAULT_HOSTS → P2P через
входящие сообщения** (см. `org.thoughtcrime.securesms.update` в Android-клиенте).

## Локальный деплой

Секреты **не хранятся в репозитории**. Перед первым деплоем:

```bash
cp infra/vps/.deploy.env.example infra/vps/.deploy.env
# заполните BMCHAT_VPS_* и BMCHAT_MIRROR_* в .deploy.env (файл в .gitignore)
```

```bash
wsl bash infra/vps/deploy-site-and-mirror.sh
```

Скрипт:

1. Кладёт `index.html`, `i.html`, `update.json`, `desktop-update.json`,
   `static/` на оба хоста.
2. Кладёт desktop-бинари из `infra/vps/desktop/` на оба хоста, делает
   stable-symlinks (`BMChat-x86_64.AppImage` → `BMChat-2.49.1-x86_64.AppImage`).
3. На mirror кладёт только последние **3 APK** (диск 9 ГБ).
4. Печатает HTTP-status каждой раздачи в качестве self-test.

Идемпотентен — повторный запуск только обновляет то, что изменилось.

## Сборка desktop локально

Разовый локальный билд (Linux + Windows). Соберёт всё в
`infra/vps/desktop/` готовое к `deploy-site-and-mirror.sh`:

```bash
# Linux (в WSL Ubuntu)
sudo apt-get install -y nodejs npm fakeroot dpkg fuse libfuse2 libnotify-bin
sudo npm i -g pnpm@9
mkdir -p ~/bmchat && rsync -a --delete \
  --exclude=node_modules --exclude=dist --exclude=html-dist \
  /mnt/e/PPROJECTS/BMChat/clients/desktop/ ~/bmchat/desktop/
cd ~/bmchat/desktop
pnpm install --frozen-lockfile
cd packages/target-electron
VERSION_INFO_GIT_REF=bmchat-2.49.44 pnpm build
pnpm pack:generate_config
pnpm pack:patch-node-modules
pnpm electron-builder --linux AppImage deb --publish never
cp dist/BMChat-*-x86_64.AppImage         /mnt/e/PPROJECTS/BMChat/infra/vps/desktop/
cp dist/bmchat-desktop_*_amd64.deb       /mnt/e/PPROJECTS/BMChat/infra/vps/desktop/
```

```powershell
# Windows (в обычной PowerShell)
robocopy E:\PPROJECTS\BMChat\clients\desktop C:\BMChat-build\desktop /E `
         /XD node_modules dist html-dist bundle_out /NFL /NDL
cd C:\BMChat-build\desktop
pnpm install --frozen-lockfile
cd packages\target-electron
$env:VERSION_INFO_GIT_REF = "bmchat-2.49.44"
pnpm build
pnpm pack:generate_config
pnpm pack:patch-node-modules
pnpm electron-builder --win nsis portable --publish never
Copy-Item dist\BMChat-*-Setup.x64.exe    E:\PPROJECTS\BMChat\infra\vps\desktop\
Copy-Item dist\BMChat-*-Portable.x64.exe E:\PPROJECTS\BMChat\infra\vps\desktop\
```

После — пересобрать манифест и катить:

```bash
python3 infra/vps/build-desktop-manifest.py infra/vps/desktop \
  > infra/vps/www/desktop-update.json
bash infra/vps/deploy-site-and-mirror.sh
```

## CI: автоматические desktop-релизы

`clients/desktop/.github/workflows/bmchat-desktop-release.yml` собирает
Linux + Windows на двух раннерах матрицы, потом отдельный deploy-job
сливает артефакты, перегенерирует `desktop-update.json` и катит на
оба сервера.

Триггеры:

* `workflow_dispatch` — кнопка «Run workflow» в Actions.
* push тега `desktop-vX.Y.Z`.

### Секреты репозитория

В Settings → Secrets and variables → Actions добавить:

| Secret | Что внутри |
| --- | --- |
| `BMCHAT_PRIM_SSH_PASS`  | SSH-пароль primary VPS (`root@…`). Задаётся только в GitHub Secrets, не в коде. |
| `BMCHAT_MIRROR_SSH_KEY` | Полное содержимое приватного OpenSSH-ключа mirror VPS. Задаётся только в GitHub Secrets. |

После добавления — Run workflow → Use workflow from `main`.

## Mirror на WhiteBlade

Поднят через `infra/vps/.tmp-mirror-setup.sh` (одноразовый скрипт,
оставлен в репо для воспроизводимости). Что у него внутри:

1. `apt-get install nginx rsync`.
2. `/etc/nginx/sites-available/bmchat-mirror` — vhost на порту **8080**
   (порт 80 занят MTProxy, 443 — Xray, других свободных нет).
3. `/var/www/bmchat/{apk,desktop,static}` под `www-data`.
4. Тот же набор location-ов, что у primary, но без `/tgmedia/` и
   `/bot-api/` — этим занимается только основной хост.
5. Если активен UFW — открывает 8080/tcp.

Внешний снаружи curl-чек:

```
$ curl -sI http://158.160.104.107:8080/update.json
HTTP/1.1 200 OK
Server: nginx/1.24.0 (Ubuntu)
```

Yandex Cloud security-group по умолчанию открыта для этого порта;
если кто-то её закрутит — `сeкьюрити группа → разрешить TCP 8080
in 0.0.0.0/0`.

## update.json и desktop-update.json

Оба манифеста содержат массив `mirrors`, который BMChat-клиент читает
динамически. Для добавления третьего зеркала достаточно:

1. Поднять там тот же nginx (см. `.tmp-mirror-setup.sh`).
2. Раскатать `apk/`, `desktop/`, `update.json`, `desktop-update.json`.
3. Дописать его prefix (например `"http://198.51.100.10"`) в массив
   `mirrors` обоих манифестов.

Установленные клиенты подхватят новый mirror на следующей проверке
обновлений и начнут пробовать его в обходе обычного списка.
