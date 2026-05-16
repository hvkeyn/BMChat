<?php
/**
 * Простая admin-страница для тестового бота: показывает последние
 * входящие и исходящие сообщения, чтобы видно было что webhook
 * реально вызывается. Не предназначено для продакшена — никакой
 * аутентификации, просто визуализация.
 */

declare(strict_types=1);

const BMCHAT_INBOX_DIR  = __DIR__ . '/messages';
const BMCHAT_OUTBOX_DIR = __DIR__ . '/outbox';
const BMCHAT_LOG_FILE   = __DIR__ . '/messages/_log.txt';

function tail_lines(string $path, int $count = 50): array {
    if (!is_readable($path)) return [];
    $lines = @file($path, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
    if (!$lines) return [];
    return array_slice($lines, -$count);
}

function recent_files(string $dir, int $count = 10): array {
    if (!is_dir($dir)) return [];
    $items = [];
    foreach (scandir($dir) as $name) {
        if ($name === '.' || $name === '..' || $name[0] === '_' || $name === '.gitkeep') continue;
        if (substr($name, -5) !== '.json') continue;
        $items[] = ['name' => $name, 'mtime' => filemtime($dir . '/' . $name) ?: 0];
    }
    usort($items, fn($a, $b) => $b['mtime'] <=> $a['mtime']);
    return array_slice($items, 0, $count);
}

$logTail = tail_lines(BMCHAT_LOG_FILE, 80);
$inbox   = recent_files(BMCHAT_INBOX_DIR, 10);
$outbox  = recent_files(BMCHAT_OUTBOX_DIR, 10);

?><!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<title>BMChat email-bot — admin</title>
<style>
  body  { font-family: -apple-system, system-ui, sans-serif; margin: 24px; color: #222; }
  h1, h2 { font-weight: 600; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
  .card { border: 1px solid #ddd; border-radius: 8px; padding: 16px; background: #fafafa; }
  .log  { font-family: SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px;
          background: #111; color: #ddd; padding: 12px; border-radius: 8px;
          white-space: pre-wrap; max-height: 320px; overflow: auto; }
  ul { margin: 0; padding-left: 18px; }
  a  { color: #006cba; text-decoration: none; }
  a:hover { text-decoration: underline; }
  .empty { color: #888; font-style: italic; }
</style>
</head>
<body>
  <h1>BMChat email-bot — тестовый webhook</h1>
  <p>Endpoint: <code>POST <?= htmlspecialchars(($_SERVER['REQUEST_SCHEME'] ?? 'http') . '://' . ($_SERVER['HTTP_HOST'] ?? 'localhost') . dirname($_SERVER['REQUEST_URI'] ?? '') . '/webhook.php') ?></code></p>

  <div class="grid">
    <div class="card">
      <h2>Последние входящие (inbox)</h2>
      <?php if (!$inbox): ?>
        <p class="empty">Пока ничего не приходило. Отправьте боту сообщение из BMChat.</p>
      <?php else: ?>
        <ul>
          <?php foreach ($inbox as $f): ?>
            <li>
              <a href="messages/<?= htmlspecialchars($f['name']) ?>" target="_blank"><?= htmlspecialchars($f['name']) ?></a>
              <small>· <?= gmdate('Y-m-d H:i:s', $f['mtime']) ?>Z</small>
            </li>
          <?php endforeach ?>
        </ul>
      <?php endif ?>
    </div>

    <div class="card">
      <h2>Последние исходящие (outbox)</h2>
      <?php if (!$outbox): ?>
        <p class="empty">Бот ещё ничего не отвечал.</p>
      <?php else: ?>
        <ul>
          <?php foreach ($outbox as $f): ?>
            <li>
              <a href="outbox/<?= htmlspecialchars($f['name']) ?>" target="_blank"><?= htmlspecialchars($f['name']) ?></a>
              <small>· <?= gmdate('Y-m-d H:i:s', $f['mtime']) ?>Z</small>
            </li>
          <?php endforeach ?>
        </ul>
      <?php endif ?>
    </div>
  </div>

  <h2 style="margin-top:24px">Журнал (<?= count($logTail) ?> строк)</h2>
  <?php if (!$logTail): ?>
    <p class="empty">Журнал пуст.</p>
  <?php else: ?>
    <div class="log"><?= htmlspecialchars(implode("\n", $logTail)) ?></div>
  <?php endif ?>
</body>
</html>
