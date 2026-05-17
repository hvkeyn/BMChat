# Local Telegram Bot API server (для файлов >20 МБ)

Облачный `https://api.telegram.org/bot…` режет `getFile` на 20 МБ.
Чтобы видео/документы до ~2 ГБ (4 ГБ для premium-загрузок) ходили
через бота, рядом с `bmchat-tgproxy` нужно запустить локальный
Bot API server. После этого достаточно изменить одну переменную в
`bmchat-tgproxy.service` и перезапустить сервис — клиент менять не нужно.

## Что нужно от пользователя один раз

1. Зайти на https://my.telegram.org → войти под Telegram-аккаунтом.
2. Раздел **API development tools**.
3. Заполнить форму (поле App title: `BMChat`, поле Short name: `bmchat`,
   платформа — любая, URL — `http://5.187.4.132`).
4. Скопировать пару `App api_id` (целое) и `App api_hash` (32 hex).

## Что сделать на VPS (после получения api_id/api_hash)

```bash
# 1. Подготовить директорию.
mkdir -p /opt/bmchat/tg-bot-api-data
chown -R 1000:1000 /opt/bmchat/tg-bot-api-data

# 2. Запустить docker-контейнер (telegram-bot-api ≥ 7.0).
docker run -d --name bmchat-tg-bot-api \
    --restart=always \
    -p 127.0.0.1:8081:8081 \
    -v /opt/bmchat/tg-bot-api-data:/var/lib/telegram-bot-api \
    -e TELEGRAM_API_ID=<api_id> \
    -e TELEGRAM_API_HASH=<api_hash> \
    aiogram/telegram-bot-api:latest \
    --local --http-port=8081 --dir=/var/lib/telegram-bot-api

# 3. Переключить bmchat-tgproxy на локальный сервер.
sed -i 's#^# *Environment=BMCHAT_TGPROXY_API_BASE=.*#Environment=BMCHAT_TGPROXY_API_BASE=http://127.0.0.1:8081#' \
    /etc/systemd/system/bmchat-tgproxy.service
systemctl daemon-reload
systemctl restart bmchat-tgproxy

# 4. Проверить.
curl -fsS http://127.0.0.1/tgmedia/healthz
journalctl -u bmchat-tgproxy --no-pager -n 30
```

## Что меняется

* Все вновь публикуемые ссылки `http://5.187.4.132/tgmedia/...`
  (включая существующие в чатах, пока не истекли) начнут отдавать
  файлы до 2 ГБ через `getFile` локального сервера, без 20 МБ-кэпа.
* Telegram-боты при первом обращении к локальному серверу должны
  залогиниться через `logOut` (на cloud) и заново вызвать
  `getMe` через `http://127.0.0.1:8081`. Скрипт регистрации в
  BMChat это делает автоматически при следующем `getUpdates`.
