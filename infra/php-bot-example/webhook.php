<?php
/**
 * BMChat email-bot webhook — single-file PHP test endpoint.
 *
 * ─────────────────────────────────────────────────────────────────────
 *  Что это и зачем
 * ─────────────────────────────────────────────────────────────────────
 *
 * Это минимальный пример webhook-обработчика для email-бота BMChat,
 * который удобно загрузить на любой PHP-хостинг (timeweb, beget,
 * jino, обычный shared hosting) и получить за 5 минут полностью
 * рабочего бота, отвечающего на команды.
 *
 * BMChat отправляет каждое входящее сообщение пользователя боту в
 * виде POST-запроса с JSON-телом, формат полностью совместим
 * с Telegram Bot API:
 *
 *   POST  https://your-host.example/bmchat-bot/webhook.php
 *   Content-Type: application/json
 *   X-BMChat-Bot-Token: <token>
 *
 *   {
 *     "update_id": 12345,
 *     "message": {
 *       "message_id": 12345,
 *       "chat": { "id": 42, "type": "private" },
 *       "from": { "email": "user@example.com" },
 *       "text": "/help",
 *       "date": 1737020000
 *     },
 *     "bmchat": {
 *       "bot": "weatherbot",
 *       "token_suffix": "AbCd1234EfGh",
 *       "command": "/help",
 *       "argument": null
 *     }
 *   }
 *
 * Ответ должен быть JSON и иметь HTTP 200:
 *
 *   {
 *     "text": "Привет! Я погодный бот.",
 *     "reply_markup": {
 *       "inline_keyboard": [
 *         [ { "text": "Москва",  "callback_data": "/city Moscow"  } ],
 *         [ { "text": "Открыть карту", "url": "https://maps.example/" } ]
 *       ]
 *     }
 *   }
 *
 * BMChat покажет текст пользователю и развернёт кнопки. Нажатие
 * inline-кнопки отправляет ту же `callback_data` обратно как
 * следующее сообщение → опять прилетит сюда.
 *
 * ─────────────────────────────────────────────────────────────────────
 *  Установка
 * ─────────────────────────────────────────────────────────────────────
 *
 *   1.  Залить эту папку на хостинг, например в каталог
 *       /home/user/public_html/bmchat-bot/
 *   2.  Убедиться что веб-сервер видит /bmchat-bot/webhook.php и
 *       у каталогов /messages и /outbox есть права 0775 на запись
 *       от имени php-fpm / apache.
 *   3.  В BMChat → Настройки → Email-боты → Создать → задать имя
 *       (например "weatherbot"), описание, аватар. В поле
 *       "Webhook URL" вписать https://your-host.example/bmchat-bot/webhook.php
 *   4.  Опционально: задать секретный токен в TOKEN ниже и в
 *       настройках бота — если токены не совпадают, обработчик
 *       вернёт 401 и BMChat откатится на статичные ответы.
 *
 * ─────────────────────────────────────────────────────────────────────
 *  Что делает файл
 * ─────────────────────────────────────────────────────────────────────
 *
 *   • Логирует каждый входящий update в /messages/<utc-timestamp>_<chat>_<msg>.json
 *     (готовый «inbox» для офлайн-обработки скриптами в cron'е).
 *   • Логирует сводку всех событий в /messages/_log.txt построчно.
 *   • Сразу отвечает простой роутер-логикой по командам
 *     (/start, /help, /echo, /menu, /me).
 *   • Выкладывает в /outbox/<utc-timestamp>_<chat>.json копию того,
 *     что отправлено пользователю — удобно для аудита.
 *
 * Файл рассчитан на обычный PHP 7.4+ без composer, fpm, fcgi или
 * даже PDO — буквально copy/paste и работает.
 */

declare(strict_types=1);

// ─── 1. Конфиг ──────────────────────────────────────────────────────

const BMCHAT_BOT_NAME      = 'weatherbot';   // имя бота, для логов
const BMCHAT_SHARED_TOKEN  = '';             // optional: секрет, см. установка
const BMCHAT_INBOX_DIR     = __DIR__ . '/messages';
const BMCHAT_OUTBOX_DIR    = __DIR__ . '/outbox';
const BMCHAT_LOG_FILE      = __DIR__ . '/messages/_log.txt';

@mkdir(BMCHAT_INBOX_DIR, 0775, true);
@mkdir(BMCHAT_OUTBOX_DIR, 0775, true);

// ─── 2. Безопасность и приём запроса ────────────────────────────────

header('Content-Type: application/json; charset=utf-8');

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'POST only']);
    exit;
}

if (BMCHAT_SHARED_TOKEN !== '') {
    $supplied = $_SERVER['HTTP_X_BMCHAT_BOT_TOKEN'] ?? '';
    if (!hash_equals(BMCHAT_SHARED_TOKEN, $supplied)) {
        http_response_code(401);
        echo json_encode(['error' => 'invalid token']);
        bmchat_log('REJECT 401 token mismatch from ' . ($_SERVER['REMOTE_ADDR'] ?? 'unknown'));
        exit;
    }
}

$raw = file_get_contents('php://input') ?: '';
$update = json_decode($raw, true);

if (!is_array($update)) {
    http_response_code(400);
    echo json_encode(['error' => 'malformed json']);
    bmchat_log('REJECT 400 malformed json: ' . substr($raw, 0, 200));
    exit;
}

// ─── 3. Архивирование входящего сообщения ──────────────────────────

$message  = $update['message']  ?? [];
$bmchat   = $update['bmchat']   ?? [];
$chatId   = (int)($message['chat']['id'] ?? 0);
$msgId    = (int)($message['message_id'] ?? $update['update_id'] ?? 0);
$from     = (string)($message['from']['email'] ?? 'unknown');
$text     = (string)($message['text'] ?? '');
$command  = (string)($bmchat['command'] ?? '');
$argument = (string)($bmchat['argument'] ?? '');

$inboxName = sprintf(
    '%s_chat%d_msg%d.json',
    gmdate('Ymd-His'),
    $chatId,
    $msgId
);
@file_put_contents(
    BMCHAT_INBOX_DIR . '/' . $inboxName,
    json_encode($update, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)
);

bmchat_log(sprintf(
    'IN  bot=%s chat=%d msg=%d from=%s cmd=%s text=%s',
    BMCHAT_BOT_NAME, $chatId, $msgId, $from,
    $command !== '' ? $command : '(none)',
    str_replace(["\r", "\n"], ' ', $text)
));

// ─── 4. Маршрутизация команд ────────────────────────────────────────

$reply = bmchat_route($command, $argument, $text, $from);

// ─── 5. Архивирование исходящего ответа ────────────────────────────

if ($reply !== null) {
    $outboxName = sprintf('%s_chat%d.json', gmdate('Ymd-His'), $chatId);
    @file_put_contents(
        BMCHAT_OUTBOX_DIR . '/' . $outboxName,
        json_encode($reply, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)
    );
    bmchat_log(sprintf(
        'OUT bot=%s chat=%d -> %s',
        BMCHAT_BOT_NAME, $chatId,
        str_replace(["\r", "\n"], ' ', (string)($reply['text'] ?? ''))
    ));
}

http_response_code(200);
echo json_encode($reply ?? new stdClass(), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
exit;

// ────────────────────────────────────────────────────────────────────
//  Helpers
// ────────────────────────────────────────────────────────────────────

/**
 * Простая команда-роутер. Возвращает массив с полями `text` и
 * (опционально) `reply_markup.inline_keyboard`, либо null если
 * боту нечего сказать (BMChat в этом случае не пришлёт пустого
 * сообщения пользователю).
 */
function bmchat_route(string $command, string $argument, string $text, string $from): ?array {
    $cmd = $command !== '' ? $command : strtolower(trim($text));

    switch ($cmd) {
        case '/start':
            return [
                'text' =>
                    "👋 Привет, {$from}!\n" .
                    "Я тестовый PHP-бот для BMChat. Команды:\n" .
                    "  /help — справка\n" .
                    "  /echo <текст> — повтор\n" .
                    "  /menu — пример inline-кнопок\n" .
                    "  /me — кто ты для меня",
            ];

        case '/help':
            return ['text' =>
                "Доступные команды:\n" .
                "/start — приветствие\n" .
                "/echo <текст> — повторю слово в слово\n" .
                "/menu — открыть меню кнопок\n" .
                "/me — твой email и chat_id"];

        case '/echo':
            $payload = $argument !== '' ? $argument : '(пусто)';
            return ['text' => "🪞 {$payload}"];

        case '/me':
            return ['text' => "Ты: {$from}"];

        case '/menu':
            return [
                'text' => 'Выбери действие:',
                'reply_markup' => [
                    'inline_keyboard' => [
                        [
                            ['text' => '🌤 Москва',   'callback_data' => '/city Moscow'],
                            ['text' => '🌧 Питер',    'callback_data' => '/city SPB'],
                        ],
                        [
                            ['text' => '🌐 Карта',   'url' => 'https://www.openstreetmap.org/'],
                        ],
                        [
                            ['text' => '↩️ Назад',   'callback_data' => '/start'],
                        ],
                    ],
                ],
            ];

        case '/city':
            $city = $argument !== '' ? $argument : 'неизвестно';
            return ['text' => "Запрошен прогноз для: {$city}\n(тут будет реальный API)"];

        default:
            // Любое другое сообщение — эхо с подсказкой.
            return [
                'text' =>
                    "Я не понял команду. Попробуй /help.\n\n" .
                    "Ты написал: {$text}",
            ];
    }
}

function bmchat_log(string $line): void {
    @file_put_contents(
        BMCHAT_LOG_FILE,
        '[' . gmdate('Y-m-d\TH:i:s\Z') . '] ' . $line . PHP_EOL,
        FILE_APPEND
    );
}
