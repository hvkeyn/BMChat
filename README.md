# BMChat / BroMoreChat

**Email-first messenger в стиле Telegram, работающий через любой IMAP/SMTP-сервер.**

BMChat — самостоятельный мессенджер на базе движка Delta Chat (Rust core + Autocrypt). Цель — взять надёжное end-to-end шифрование Delta Chat, добавить Telegram-уровневый UX (альбомы, мини-плеер, Shared Media, реакции, прочтения, кружочки) и собственную инфраструктуру обновлений, не зависящую от сторонних сервисов.

Весь чат-трафик идёт через почтовый сервер пользователя — никаких сторонних мессенджер-серверов и идентификаторов BMChat не использует. End-to-end шифрование Autocrypt применяется ко всем сообщениям между BMChat-клиентами; обычная почта от не-BMChat отправителей принимается как простой email-чат.

## Клиенты

| Путь | Платформа | Статус |
| --- | --- | --- |
| `clients/android` | Android 5.0+ | **2.50.10**, релизный канал debug, `infra/vps/www/update.json` |
| `clients/desktop` | Windows / Linux / macOS | **2.50.10**, авто-обновлятор через `infra/vps/www/desktop-update.json` |
| `clients/ios` | iOS | сборки требуют macOS + Xcode |

## Реализованные функции

### Email и доставка
- IMAP IDLE по всем подключенным аккаунтам, фоновая доставка через `KeepAliveService`.
- Опция **«Сканировать все папки»** (`Config::ScanAllFolders`) — забирает сообщения из любых папок (не только Inbox/Mvbox).
- Спам обрабатывается: `Config::FetchSpam` поднимает отдельный `simple_imap_loop` для папок `Spam/Junk` с `should_move_out_of_spam`.
- Сообщения от не-BMChat отправителей (классические email без `Chat-Version`) показываются как обычные чаты.

### Медиа и UX в стиле Telegram
- **Альбомы**: до 10 фото/видео компонуются в одну композитную JPEG-плитку (`AlbumComposer` + `AlbumThumbnailLayout`), Telegram-style сетка 1/2/3/4/2×n.
- **Live CameraX picker** (`BMChatGalleryPickerActivity`) — первая плитка в галерее это живой видоискатель камеры, тап раскрывает её на полный экран.
- **Реакции** — расширенный набор смайлов, плавающий picker над сообщением, кнопка «...» с полным picker'ом.
- **Read receipts** (`get_message_read_receipts`) — в групповых чатах тап по галочке открывает диалог со списком кто и когда прочитал.
- **Telegram-style mini-player** (Phase 2, 2.49.79) — `BMChatMiniPlayerView` снизу в списке чатов, в открытом чате и в Shared Media. Работает в фоне через `MediaSessionService`, управление с lock-screen и Bluetooth-наушников. Бейдж скорости `1×/1.5×/2×` рядом с длительностью аудио сообщения.
- **Telegram-style download UX** (Phase 1, 2.49.78) — круговой индикатор прогресса поверх миниатюр для медиа, не загруженных на диск, иконка перезапуска при ошибке. Сохранение в `Pictures/BMChat`, `Movies/BMChat`, `Music/BMChat`, `Download/BMChat`.
- **Shared Media browser** (Phase 3, 2.49.80) — Telegram-style вкладки `Apps · Photos · Videos · Audio · Files · Links` в профиле чата. Multi-select экспорт. Links-сканер собирает все URL из переписки.

### Управление хранилищем
- Структурированный API в Rust core (`storage_usage.rs`) — total/db/blobdir, evictable, per-category, per-chat.
- Android UI «Память и данные» с Telegram-style donut chart, авто-очисткой (`WorkManager`) и опасным разделом удаления почты с сервера через `delete_server_after`.

### Авто-обновления
- Android: `BMChatUpdater` (foreground/manual/background через `WorkManager`) тянет манифест обновлений, проверяет SHA-256 APK и ставит через `PackageInstaller`.
- Desktop: `bmchat-updater.ts` в Electron main делает то же для десктоп-сборок.
- Релизы публикуются BMChat-командой; конечные эндпоинты конфигурируются на этапе сборки и в публичный код не попадают.

### Email-боты и десктоп (2.50.5–2.50.10)
- **Email-боты** — не отдельные контакты в адресной книге; команды (`/start`, `/help`, …) отправляются в **«Сохранённые сообщения»** как `@имя_бота /команда` (например `@newsbot /start`).
- Настройка: **Настройки → Дополнительно → Email-боты** — команды, webhook (`X-BMChat-Bot-Token`), e-mail разработчика (транспорт `BMCHAT-BOT-UPDATE` / `BMCHAT-BOT-REPLY`).
- **2.50.6:** в боковом поиске по `@newsbot` показываются настроенные боты; кнопка **«Написать боту»** открывает чат с черновиком `@бот /start`.
- **2.50.6:** окно **«Соединение»** — статистика сообщений/чатов как на Android; при недоступности реле BMChat — проверка **IMAP/SMTP** (TCP) ваших почтовых серверов.
- **2.50.9–2.50.10:** синхронизация ботов Android ↔ Desktop (`ui.bmchat.email_bots`, каталог `ui.bmchat.bot_directory`); шифрование секретов на диске и через self-chat (`BMCHAT-BOT-SYNC v1`); исправлено пустое окно «Соединение» на десктопе.
- Пример PHP-бота: `infra/php-bot-example/` (токен из настроек BMChat обязателен в `config.php`).

### Брендинг
- `app_name`, цвета, иконки, темы, локализации (RU + EN), help-страницы и Fastlane-метаданные — везде **BMChat**. Все упоминания Delta Chat в пользовательском UI вычищены (`scripts/rebrand_strings.ps1`, `scripts/rebrand_java_logtags.ps1`).
- Лицензии и attribution upstream-кода сохранены — это форк, а не переписывание.

## Архитектура

```
┌─── Android (Java) ─── ConversationActivity / AllMediaActivity / ...
│         │
│         └── Rust core via JNI (libnative-utils.so) ──────┐
│                                                          │
├─── Desktop (Electron + React) ── Tauri/electron-main ───┤
│         │                                                │
│         └── deltachat-rpc-server (Rust) via stdio ──────┤
│                                                          │
├─── iOS (Swift) ── SwiftUI/UIKit ─────────────────────────┤
│         │                                                │
│         └── deltachat-core-rust ────────────────────────┤
│                                                          ▼
│                              ┌──────────────────────────┐
└───── auto-update manifests ─→│  BMChat release channel  │
                               └──────────────────────────┘
                                              ▲
                              user's own IMAP/SMTP server
                              (все чат-сообщения, fully E2E)
```

## Сборка

Требования:
- Android: JDK 17 (Eclipse Temurin), Android SDK + NDK r25c, Rust 1.91.1 для пересборки `libnative-utils.so`.
- Desktop: Node.js 22, pnpm 9, Rust 1.91.1.
- iOS: macOS + Xcode + CocoaPods.

### Android
```sh
cd clients/android
# Rust core (один раз или после правок в jni/deltachat-core-rust)
scripts/ndk-make.sh
# Debug APK
./gradlew assembleFossDebug
# Результат: clients/android/build/outputs/apk/foss/debug/BMChat-foss-debug-<version>.apk
```

### Desktop
```sh
cd clients/desktop
pnpm install
pnpm -w build:electron
pnpm -w start:electron
# Релизные артефакты (Windows + Linux), из WSL с wine32 для NSIS:
cd packages/target-electron
pnpm build && pnpm pack:win && pnpm pack:linux
# Деплой на VPS (после копирования в infra/vps/desktop/ и обновления sha256 в манифесте):
# bash infra/vps/deploy-desktop-2.50.10.sh
# APK + сайт + зеркало:
# bash infra/vps/deploy-site-and-mirror.sh
# Зеркало Yandex (ключ из whiteBlade):
# BMCHAT_DESKTOP_VERSION=2.50.10 bash infra/vps/deploy-desktop-2.50.6-mirror.sh
# (ключ: chmod 600 — иначе SSH отклонит; на зеркале нужно место на диске)
# SSH: ssh -i E:/PPROJECTS/whiteBlade/artifacts/deploy/ssh/yc_whiteblade dante@158.160.104.107
```

### iOS
```sh
cd clients/ios
git submodule update --init --recursive
rustup toolchain install "$(cat rust-toolchain)"
pod install
open deltachat-ios.xcworkspace
```

## Документация

- `memory-bank/projectbrief.md` — постановка задачи.
- `memory-bank/activeContext.md` — текущее состояние, последние решения.
- `memory-bank/progress.md` — список реализованного / в работе.
- `docs/fork-strategy.md` — стратегия форка vs upstream merge.
- `docs/brand-map.md` — таблица заменённых брендовых полей.
- `docs/build-matrix.md` — какие сборки/архитектуры проверены.

## Лицензирование

Этот репозиторий — форк Delta Chat. Лицензии upstream-кода сохранены:
- Desktop и Android — GPL-based.
- iOS клиент и Chatmail/Delta Chat core — MPL-based.
- BMChat-специфичные изменения и инфраструктура (`infra/`, `brand/`, `scripts/`, `memory-bank/`, `docs/`) опубликованы в этом же репозитории под совместимыми условиями; см. `docs/licensing-notes.md`.

## Дорожная карта (Phases)

| Фаза | Версия | Содержание | Статус |
| --- | --- | --- | --- |
| Phase 1 | 2.49.78 | Telegram-style download UX (круговой прогресс, save-as) | завершено |
| Phase 2 | 2.49.79 | Mini-player + playback speed | завершено |
| Phase 3 | 2.49.80 | Shared Media browser (Photos/Videos/Audio/Files/Links) | завершено |
| Phase 4 | 2.49.81 | Chat UX: PiP-video, swipe-to-reply, jump-to-date | завершено |
| Phase 5 | 2.49.82 | Круглые видео-сообщения (video notes) | в работе |
| Phase 6 | 2.50.x | Desktop/Android: email-боты, соединение, шифрование, updater | завершено (2.50.10) |
| Phase 7 | — | iOS порт фишек Phase 1–5 | планируется |
