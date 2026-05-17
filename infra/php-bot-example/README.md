# BMChat email-bot — тестовый webhook на PHP

Минимальный шаблон webhook-обработчика для email-бота BMChat,
рассчитанный на любой shared PHP-хостинг. Никаких зависимостей,
composer-а или баз данных — только два .php файла и пара папок.

## Что внутри

- `webhook.php` — endpoint, который BMChat вызывает по HTTP POST на каждое
  входящее сообщение бота. Возвращает JSON-ответ совместимый с Telegram
  Bot API (`text` + опциональный `reply_markup.inline_keyboard`).
- `index.php` — небольшая admin-страница: показывает последние
  входящие, исходящие и tail журнала. Без авторизации — только
  для отладки.
- `messages/` — каждое входящее сообщение сохраняется как
  `YYYYMMDD-HHMMSS_chatN_msgM.json`. Удобно потом разгребать
  скриптом из cron'а.
- `outbox/` — копия каждого ответа бота.
- `messages/_log.txt` — однострочный журнал событий.

## Установка на shared-хостинге за 5 минут

1. Скопировать всю эту папку на хостинг, например в
   `~/public_html/bmchat-bot/`.
2. Дать веб-серверу право писать в `messages/` и `outbox/`:
   ```bash
   chmod 0775 messages outbox
   ```
3. Открыть `https://your-host.example/bmchat-bot/` — должна
   появиться страница «BMChat email-bot — тестовый webhook»
   с пустыми списками.
4. В BMChat → Настройки → Email-боты → Создать → задать имя
   (`weatherbot`, `helperbot`, …), описание, аватар. В поле
   **Webhook URL** вписать полный URL до `webhook.php`:
   ```
   https://your-host.example/bmchat-bot/webhook.php
   ```
5. Опционально включить общий секрет: задать одно и то же значение
   в `BMCHAT_SHARED_TOKEN` (в `webhook.php`) и в поле «Токен» бота
   в BMChat. Запросы без правильного `X-BMChat-Bot-Token`
   получат `401 Unauthorized`.
6. В BMChat открыть чат с ботом (или добавить бота в групповой чат /
   канал) и написать `/start`. Через секунды на admin-странице
   появятся записи в inbox и в журнале.

## Контракт webhook-а

Запрос:

```
POST  https://your-host.example/bmchat-bot/webhook.php
Content-Type: application/json
X-BMChat-Bot-Token: <token>     # если задан общий секрет

{
  "update_id": 12345,
  "message": {
    "message_id": 12345,
    "chat":  { "id": 42, "type": "private" },
    "from":  { "email": "user@example.com" },
    "text":  "/help",
    "date":  1737020000
  },
  "bmchat": {
    "bot": "weatherbot",
    "token_suffix": "AbCd1234EfGh",
    "command": "/help",
    "argument": null
  }
}
```

Успешный ответ (HTTP 200, JSON):

```json
{
  "text": "Привет! Я погодный бот.",
  "reply_markup": {
    "inline_keyboard": [
      [
        { "text": "🌤 Москва", "callback_data": "/city Moscow" },
        { "text": "🌧 Питер",  "callback_data": "/city SPB" }
      ],
      [
        { "text": "🌐 Карта", "url": "https://www.openstreetmap.org/" }
      ]
    ]
  }
}
```

Если боту нечего сказать — вернуть пустой JSON-объект `{}` или
HTTP 204. BMChat в этом случае ничего не отправит пользователю.

## Что уже умеет дефолтный роутер в `webhook.php`

| Команда       | Поведение                                                          |
|---------------|--------------------------------------------------------------------|
| `/start`      | Приветствие + список команд                                        |
| `/help`       | Текстовая справка                                                  |
| `/echo TEXT`  | Эхо: возвращает то же `TEXT`                                       |
| `/me`         | Возвращает email отправителя                                       |
| `/menu`       | Демонстрация inline-кнопок (callback_data + url)                   |
| `/city CITY`  | Срабатывает на тап «🌤 Москва» / «🌧 Питер» из `/menu`             |
| любой текст   | Подсказка `/help`, повторяя что прислал пользователь                |

## Расширение

Чтобы прикрутить реальную логику (погода, ChatGPT, БД заказов, что
угодно) — отредактируйте функцию `bmchat_route()` в `webhook.php`.
Возвращайте структуру `['text' => ..., 'reply_markup' => ...]` —
больше ничего не требуется.

Если webhook-обработчик возвращает пустой / некорректный JSON,
BMChat дополнительно прогонит сообщение через статичную таблицу
ответов, заданную в настройках бота — это отказоустойчивый
fallback на случай падения PHP-хостинга.

## Cron-приём (offline-обработка)

Если хочется обрабатывать сообщения не в момент HTTP-вызова, а
батчем (например, раз в минуту) — webhook.php уже сохраняет каждый
update в `messages/`. Можно добавить в crontab:

```
*/1 * * * * /usr/bin/php /home/user/public_html/bmchat-bot/process-inbox.php
```

`process-inbox.php` (не в комплекте — пишите сами под свою задачу)
читает `messages/*.json`, обрабатывает их и кладёт результат
куда нужно. Webhook при этом продолжает синхронно отвечать
коротким ack-сообщением «Принято, обрабатываю…».

## Безопасность

- Включите HTTPS на хостинге — webhook URL храним email и ID чатов.
- Задайте `BMCHAT_SHARED_TOKEN` — иначе любой, кто узнает URL,
  сможет дёргать бота от имени BMChat.
- Не пишите в журнал чувствительные поля сообщений — `bmchat_log`
  по умолчанию сохраняет только команду и сжатый текст.
- Папки `messages/` и `outbox/` лучше закрыть от прямого http-доступа
  через `.htaccess` (уже добавлен в этой папке).
