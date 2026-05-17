# Progress

## Current Status

BMChat is now an e-mail-first messenger. The user's VPS at `5.187.4.132` is used only for the invite landing page (`/i`), the Android update manifest+APK (`/update.json`, `/apk/`), the Desktop update manifest (`/desktop-update.json`) and the public website (`/`). All actual conversation traffic — including the new e-mail invitations and their automatic acceptance on the receiver — flows through the user's own IMAP/SMTP server, fully encrypted via Autocrypt-driven SecureJoin. Invite links use the canonical form `http://5.187.4.132/i#FRAGMENT` across Rust core, Android, desktop, and iOS, with legacy `i.bmchat.example` and `i.delta.chat` still parseable. The Rust core was recompiled in WSL Ubuntu 24.04 (Rust 1.91.1, Android NDK r25c) so the new host is baked into `libnative-utils.so`. The Android APK was rebuilt with `versionCode 749 / versionName 2.49.1` and uploaded as `BMChat-foss-debug-2.49.1.apk` (76 419 302 bytes, SHA-256 `d852b2fd…0bc68afd`). The deploy script (`infra/vps/deploy.sh`) splits the original `nod-tracker` nginx site into two port-80 server blocks — one for the BMChat IP, one for `mynod.duckdns.org` keeping its `301 → HTTPS` — so the existing nod-tracker frontend on `https://mynod.duckdns.org/` is untouched; idempotency is guaranteed by always re-patching from a pristine backup. The in-app Android updater (`BMChatUpdater`) polls `update.json` every 6 hours, verifies SHA-256, and installs via `PackageInstaller`. The new Electron updater (`bmchat-updater.ts`) polls `desktop-update.json` every 12 hours from the main process and shows a download dialog when a newer installer is published. Cleartext HTTP is whitelisted only for `5.187.4.132` via `network_security_config.xml`.

## Completed

- Confirmed the workspace started empty.
- Created Memory Bank documentation structure.
- Defined initial product, architecture, licensing, and technical context.
- Created base project documents in `docs/`.
- Created initial brand configuration in `brand/config/bmchat-brand.json`.
- Created `clients/desktop`, `clients/android`, and `clients/ios` directories.
- Cloned `deltachat/deltachat-desktop` into `clients/desktop`.
- Cloned `deltachat/deltachat-android` into `clients/android` with core submodule.
- Cloned `deltachat/deltachat-ios` into `clients/ios` with core submodule.
- Created `bmchat/main` working branch in all three clients.
- Added `upstream` remotes pointing at the original Delta Chat repositories.
- Completed first branding surface audit in `docs/brand-map.md`.
- Completed Russian localization audit in `docs/localization-audit.md`.
- Completed theme and brand-layer plan in `docs/theme-plan.md`.
- Finalized build matrix and platform smoke checklist in `docs/build-matrix.md`.
- Added `.cursorrules` project intelligence for future sessions.
- Converted the workspace into a root monorepo git repository.
- Added root `README.md` and `.gitignore`.
- Verified desktop `pnpm -w check`; it passed with two upstream eslint warnings.
- Verified desktop `pnpm -w build:electron` with `VERSION_INFO_GIT_REF=bmchat-initial`; it passed.
- Verified Android `./gradlew.bat assembleDebug`; it passed and produced foss/gplay debug APKs.
- Confirmed iOS build cannot run in this Windows environment because `xcodebuild` is unavailable.
- Created private GitHub repository `hvkeyn/BMChat`.
- Pushed root monorepo branch `main` to `https://github.com/hvkeyn/BMChat`.
- Added `DESIGN.md` and updated BMChat brand tokens.
- Rebranded desktop Electron metadata, app IDs, app data folders, default themes, help pages, Russian/English strings, and placeholder tray/background assets.
- Rebranded Android application IDs, APK names, app labels, Russian/English strings, colors, launcher vector, Google services package names, and ru/en fastlane metadata.
- Rebranded iOS display names, bundle IDs, app groups, permission prompts, Russian/English strings, and selected color assets without running Xcode.
- Verified that existing group chat creation already supports BMChat "common/shared chats" on desktop, Android, and iOS; documented in `docs/group-chat-verification.md`.
- Verified desktop `pnpm -w check` passed through a Node 22 pnpm wrapper before packaging, with only the existing upstream eslint warnings.
- Verified desktop `pnpm -w build:electron` passed with `VERSION_INFO_GIT_REF=bmchat-rebrand`.
- Produced Windows test builds:
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Setup.x64.exe`
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Portable.x64.exe`
- Verified Android `./gradlew.bat assembleDebug` passed and produced:
  - `clients/android/build/outputs/apk/foss/debug/BMChat-foss-debug-2.49.0.apk`
  - `clients/android/build/outputs/apk/gplay/debug/BMChat-gplay-debug-2.49.0.apk`
- Investigated Android startup failure reported during manual testing. The first APKs were missing `libnative-utils.so`; they were rebuilt after restoring native libraries for all supported ABIs from the official Delta Chat `2.49.0` APK, and the rebuilt APKs now contain `native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'`.
- Added centralized BMChat endpoint placeholders and policy in `brand/config/bmchat-brand.json`; empty BMChat endpoints disable Delta/upstream fallbacks.
- Replaced working-path Delta/chatmail infrastructure dependencies for onboarding, invite links, heartbeat registration, statistics, and fallback call ICE servers.
- Changed core mail filtering so ordinary emails without `Chat-Version` or known BMChat/Delta thread linkage are tombstoned instead of becoming chat list items or notifications.
- Changed `show_emails` default and migration to `0`, and hid the Show Classic Emails setting in desktop, Android, and iOS.
- Moved `BMCha.jpeg` source into `brand/assets/bmcha-logo-source.jpeg` and changed tokens/assets to the burgundy B-mark direction.
- Added and passed the core test `test_bmchat_ignores_classic_mail_without_chat_version`.
- Verified desktop `pnpm -w check` passes after repairing the local Windows pnpm TypeScript cache issue, with only the two existing upstream eslint warnings in `ChatContext.tsx`.
- Verified Android `./gradlew.bat assembleDebug` passes and produces:
  - `clients/android/build/outputs/apk/foss/debug/BMChat-foss-debug-2.49.0.apk`
  - `clients/android/build/outputs/apk/gplay/debug/BMChat-gplay-debug-2.49.0.apk`
- Produced Windows desktop artifacts:
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Setup.x64.exe`
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Portable.x64.exe`
- Replaced the lingering Delta-Chat onboarding logo, welcome backdrop, tray/launcher icons, About dialog texts, and onboarding tagline with BMChat assets and copy.
- Removed all donation buttons / device messages / settings entries on desktop, Android, and iOS, plus the `https://delta.chat/...` URLs from the active Help menus and the Tauri/AppStream metadata; `donation_request_maybe` in the mirrored Rust core is now a no-op.
- Re-built and re-verified the Windows desktop artifacts and both Android debug APKs after the cleanup pass:
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Setup.x64.exe`
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Portable.x64.exe`
  - `clients/android/build/outputs/apk/foss/debug/BMChat-foss-debug-2.49.0.apk`
  - `clients/android/build/outputs/apk/gplay/debug/BMChat-gplay-debug-2.49.0.apk`
- Added a UI guard layer that survives the still-upstream native core:
  - Android `ConversationListAdapter` filters out mailing-list and unencrypted-contact-request chats; `NotificationCenter` suppresses notifications for the same chats.
  - Android `Util.INVITE_DOMAIN` is now `i.bmchat.example` with a `LEGACY_INVITE_DOMAIN` fallback; `Util.rewriteInviteLink` and `QrShowFragment.fixSVG`/`stripDeltaBranding` clean QR SVGs and shared invite URLs.
  - Android login form replaces the broken `providers.delta.chat` link behind the yellow "Больше информации" button with an in-app dialog showing the full provider hint plus a BMChat help link.
  - Desktop `shared/util.ts` exposes `BMCHAT_INVITE_HOST`, `rewriteInviteLink`, `stripDeltaBranding`; `QrCode.tsx` uses them to copy/share a clean invite link and render a Delta-free QR.
  - iOS `Helper/Utils.swift` adds `legacyInviteDomain`, `rewriteInviteLink`, `stripDeltaBranding`; `QrViewController` strips Delta branding before rendering; `ChatListViewModel` skips mailing-list / unencrypted-contact-request chats; `NotificationManager` suppresses notifications for them.
  - Android, iOS, and desktop `secure_join_wait` strings now tell the user to share the QR/invite link manually if the other side isn't on BMChat yet.
- Cross-compiled the BMChat Rust core for Android via WSL (Ubuntu 24.04 + Rust 1.91.1 + Android NDK r25c at `/opt/android/android-ndk-r25c`):
  - Normalised every shell script and Makefile under `clients/android/` to LF line endings and added a repository-wide `.gitattributes` policy so `*.sh`, `*.mk`, Rust/C/Java/Kotlin/Swift sources, etc., always check out as LF (Windows-only files keep CRLF).
  - Ran `clients/android/scripts/ndk-make.sh` end-to-end, producing fresh `libdeltachat.a` and then `libnative-utils.so` per ABI (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`).
  - Allowed `clients/android/libs/<ABI>/libnative-utils.so` in `.gitignore` so the prebuilt native core ships with the repo and Windows users can assemble the APK without the Rust/NDK toolchain.
  - Rebuilt and re-archived `BMChat-foss-debug-2.49.0.apk` and `BMChat-gplay-debug-2.49.0.apk` with the BMChat-flavoured native core.
- Switched the BMChat invite link host from the placeholder `i.bmchat.example` to a real distribution VPS at `5.187.4.132`:
  - Stood up nginx on the VPS to serve `/` (landing), `/i` (invite landing that decodes the URL fragment client-side), `/update.json` (auto-update manifest), and `/apk/...` (signed APKs). Wrote `infra/vps/deploy.sh` + `infra/vps/nginx/bmchat-locations.conf` + `infra/vps/www/{index,i}.html` and made the deploy idempotent by capturing the pristine nginx site as `/etc/nginx/backups/nod-tracker.original` and re-patching from it.
  - Updated `qr.rs`, `securejoin.rs`, and `stock_str.rs` in both copies of the bundled Rust core; `Util.java` (`INVITE_HOST`/`LEGACY_INVITE_HOSTS`/`rewriteInviteLink`/`isInviteURL`), `QrShowFragment.fixSVG`, `WebViewActivity`, and `AndroidManifest.xml` deep-link intent filter on Android; `shared/util.ts` and `constants.ts` on desktop; `Utils.swift`, `WebxdcViewController`, and `AppDelegate` on iOS. Older invite URLs (`https://i.bmchat.example/#…`, `https://i.delta.chat/#…`) are still recognised on input.
  - Recompiled the Rust core in WSL and rebuilt both Android APKs as `versionCode 748` (still `versionName 2.49.0`); SHA-256 of the FOSS debug APK is `01f3fe65…f64f39daf6`.
- Added an in-app auto-updater for Android (`org.thoughtcrime.securesms.update.BMChatUpdater`) wired into `ConversationListActivity.onResume`. It polls `http://5.187.4.132/update.json` at most every 6 hours, downloads the APK to `cacheDir/updates/`, verifies SHA-256, and launches `Intent.ACTION_VIEW` against a FileProvider URI so the system installer can replace the running app. `network_security_config.xml` whitelists cleartext only for `5.187.4.132`.
- Hardened the VPS deploy so existing customer sites stay intact: `infra/vps/deploy.sh` now splits the original `nod-tracker` port-80 server into one block for `5.187.4.132` (BMChat) and another for `mynod.duckdns.org` (untouched `301 → HTTPS`). Verified on the live VPS that `https://mynod.duckdns.org/` still serves the original NOD frontend, while `http://5.187.4.132/{,/i,/update.json,/desktop-update.json,/apk/…}` returns 200 OK.
- Added a Desktop self-updater (`clients/desktop/packages/target-electron/src/bmchat-updater.ts`) wired from `index.ts`. It runs in the Electron main process, polls `http://5.187.4.132/desktop-update.json` at most every 12 hours, picks the variant matching `process.platform`/`process.arch`, and offers a "Скачать"/"Позже" dialog that links to the published installer. Bundled and verified via `pnpm build:backend` (the new symbol shows up in `target-electron/bundle_out/index.js`).
- Added e-mail-based invitations on Android:
  - `QrShowFragment` gained a "Отправить на e-mail" button next to the existing copy/share actions. It collects an address via dialog, calls `dcContext.createContact` + `dcContext.createChatByContactId`, and `dcContext.sendMsg` writes a regular message containing the canonical invite link — i.e. the entire invitation flows through the user's own SMTP.
  - On the receiving side, `BMChatInviteAutoAcceptor` is hooked into `DC_EVENT_INCOMING_MSG`. It scans every incoming message body for a BMChat invite URL (current host or any `LEGACY_INVITE_HOSTS`), runs `dcContext.checkQr`, and — if the QR is `DC_QR_ASK_VERIFYCONTACT/VERIFYGROUP/JOIN_BROADCAST` — calls `dcContext.joinSecurejoin` automatically so the encrypted SecureJoin handshake (also pure e-mail) starts without any tap. A small toast confirms the auto-accept. Each link is processed at most once per process to avoid duplicates.
- Telemetry decoupling: `StatsSending.showStatsThanksDialog` no longer points to the third-party Qualtrics survey. The opt-in toggle stays for future self-hosted use but does not leak any contact info to external services.
- Bumped the Android app to `versionCode 749 / versionName 2.49.1`. Rebuilt the FOSS debug APK on Windows with Adoptium JDK 17 + Android SDK; the resulting `BMChat-foss-debug-2.49.1.apk` (76 419 302 bytes, SHA-256 `d852b2fd60f72babe75dd44db4d9a90f5489f0cfa134b70351f77c710bc68afd`) was uploaded to `http://5.187.4.132/apk/`, and the `BMChat-foss-debug-latest.apk` symlink and `update.json` (`versionCode 749 / 2.49.1`) point at it. Older 2.49.0 APK is preserved alongside.

## In Progress

- Real-device smoke test: update an installed Android build to 2.49.25, send a Telegram post/album into BMChatBot, and confirm there is no `queue ENQUEUE FAIL`; Device messages should show `queue +1 ... всего: N`, the bot card should show `очередь: N`, and `Очередь сообщений` should show the post. Also verify the toast says `Получено: N · в очередь/журнал: N` (plus `в чат: N` when auto-publish is on).

## Latest Completed

- Implemented Android storage management and safe local cache cleanup:
  - core `storage_usage` now exposes structured total/db/blobdir usage, category breakdowns, per-chat breakdowns, and `evictable_bytes` based on whether a message can be downloaded again from IMAP;
  - new JSON-RPC methods `get_storage_usage` and `clear_local_storage` support Android now and provide a cross-client API for Desktop/iOS later;
  - local cleanup removes only safe cached blobs, converts affected messages back to downloadable placeholders, skips files without a known IMAP copy, and reports freed/skipped bytes;
  - Android Settings now has "Память и данные" with a Telegram-like donut summary, type/chat checklists, progress/result dialogs, automatic cache cleanup via WorkManager, and a separate dangerous server cleanup flow using `delete_server_after` with explicit confirmation;
  - added RU strings, a storage settings icon, Java RPC types, and `StorageCleanupWorker`; rebuilt Android native `libnative-utils.so` for all ABIs and verified `cargo fmt --check`, `cargo check -p deltachat-jsonrpc`, `./gradlew.bat :assembleFossDebug`, APK install, and app launch on device.
- Implemented PR branch `bmchat-24969-spam-camera-read-receipts` for Spam-fetch, live CameraX picker tile, and group read receipts:
  - core adds `fetch_spam`, stores `configured_spam_folder`, watches Spam/Junk when enabled, starts a Spam `simple_imap_loop`, and moves eligible chat messages out of Spam so they are fetched from Inbox/Mvbox and the provider learns "not spam";
  - Android Advanced Settings has the new "Проверять папку «Спам»" switch wired to core config `fetch_spam`;
  - gallery picker first tile is a live CameraX `PreviewView`; tapping it captures into MediaStore, inserts the shot at the top of the grid, and preselects it for sending;
  - outgoing group message footers/galки open a Telegram-style "Прочитали" dialog using `getMessageReadReceipts`;
  - rebuilt Android native `libnative-utils.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`; verified `cargo check -p deltachat`, Android `:assembleFossDebug`, installation/launch on `SM-G991B`, picker launch, live camera capture selection, and no new picker crash.
- Built and deployed Android `2.49.68` (`versionCode 816`) — BMU marker cleanup and notification-test correction:
  - disabled outbound peer-to-peer update markers in `UpdateBroadcast.maybeAppend()` because mail servers can strip zero-width wrappers and expose raw `BMU(base64...)` text to users;
  - stripped existing BMU markers from full-message HTML/title, notification summaries/MessagingStyle rows, and conversation-list previews, in addition to the existing chat-bubble strip path;
  - confirmed logcat contained an `adb force-stop` before the reported notification test; documented that Android blocks background services/receivers after force-stop until the user manually opens the app once;
  - installed the build without force-stop and verified `KeepAliveService` is `isForeground=true`;
  - deployed `BMChat-foss-debug-2.49.68.apk` (76 880 127 bytes, SHA-256 `890dabedb468c07a1f1c23b7bf9534ace3633bc5e7db40a7b264998b84f3e608`) and repointed `update.json` + `BMChat-foss-debug-latest.apk` on primary and mirror VPS.
- Built and deployed Android `2.49.25` (`versionCode 773`) — first-write fix for Telegram-bot queue:
  - diagnosed the user's `Device messages: queue ENQUEUE FAIL (пост) / error: null` as an `UnsupportedOperationException` thrown by adding to `Collections.emptyList()` on the first write into an empty `PendingPostStore` queue;
  - changed empty/parse-failure reads to mutable `ArrayList` and wrapped all mutating read paths before add/set/remove;
  - changed queue persistence from `apply()` to synchronous `commit()` so immediate count diagnostics see the saved entry;
  - changed dispatcher diagnostics to include exception class names instead of blank/null messages;
  - deployed `BMChat-foss-debug-2.49.25.apk` (76 574 341 bytes, SHA-256 `e3397f11e9fac111825652f17582af18599a06cd854a743990f6ef8b05dc4298`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/update.json` advertises `2.49.25` and `HEAD /apk/BMChat-foss-debug-2.49.25.apk` returns 200.
- Built and deployed Android `2.49.24` (`versionCode 772`) — stable Telegram-bot queue key:
  - fixed the remaining "received N, queue empty" scenario caused by storing `PendingPostStore` entries under local `BotConfig.id` UUID while the same Telegram bot/token could exist as multiple local rows after remove/re-add cycles;
  - `PendingPostStore` now uses a canonical `SHA-256(token)` preference key for BotConfig-based operations and automatically merges legacy UUID-keyed entries into that canonical queue on read;
  - dispatcher, scheduler, bot-list counts, and `PendingPostsActivity` now use BotConfig overloads, while remove/schedule/markPublished also touch legacy keys so migrated entries do not reappear;
  - poll stats now count every successful enqueue as `queued`, even when auto-publish is enabled, and the toast says `в очередь/журнал`;
  - deployed `BMChat-foss-debug-2.49.24.apk` (76 574 367 bytes, SHA-256 `561ebe1a081f2b1a0be7563fe8a5e3355810d4fb6f97b24bdd98a6019c5b6858`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/update.json` advertises `2.49.24` and `HEAD /apk/BMChat-foss-debug-2.49.24.apk` returns 200.
- Built and deployed Android `2.49.5` (`versionCode 753`) after the May 8 follow-up:
  - fixed `/i` "Открыть в BMChat" by moving the invite payload from the broken `#PAYLOAD#Intent` form into `?bmchat_invite=...` and restoring the canonical `http://5.187.4.132/i#...` link in Android before SecureJoin handling;
  - repaired `clients/android/src/main/assets/help/ru/help.html` from mojibake to real UTF-8 and kept explicit UTF-8 WebView loading in `LocalHelpActivity`;
  - added detailed local statistics to the Android "Соединение" screen;
  - shortened/traced long advanced-settings labels with explanatory summaries;
  - moved the Android foreground notification to a fresh visible BMChat channel and added a Windows desktop AppUserModelID for notification identity;
  - deployed `BMChat-foss-debug-2.49.5.apk` (76 441 671 bytes, SHA-256 `79a8e2044f95663679be2503972c50f298292ec2ceb62ebbb8af044a8378f808`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`.
- Built and deployed Android `2.49.6` (`versionCode 754`) for contact deduplication:
  - core contact lists now return one contact per normalized e-mail address;
  - migration 151 merges existing duplicate contacts and rewrites chat/message/reaction/location references to the chosen primary contact;
  - Android/Desktop/iOS creation flows lookup by e-mail before creating a contact, and Android/Desktop hide "create contact" when the e-mail already exists;
  - Android native core was rebuilt for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`;
  - deployed `BMChat-foss-debug-2.49.6.apk` (76 445 188 bytes, SHA-256 `96fe0ae547cf41dcc4b09fb9207ae21a3e9f8b309e28fe5721be7c213f54f7d1`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`.
- Built and deployed Android `2.49.7` (`versionCode 755`) — one-chat-per-e-mail enforcement:
  - new `BMChatChatDedupe` helper archives mirror 1:1 chats with the same peer e-mail, keeping the freshest one;
  - the helper is triggered on `DC_EVENT_INCOMING_MSG`, `DC_EVENT_CHAT_MODIFIED`, `DC_EVENT_CONTACTS_CHANGED`, `DC_EVENT_MSGS_CHANGED`, after `BMChatInviteAutoAcceptor` finishes the SecureJoin handshake, and after `QrCodeHandler.secureJoinByQr` / `showFingerprintOrQrSuccess`;
  - `ConversationListAdapter.rebuildVisibleIndices()` is now a UI-side safety net that hides extra 1:1 chats per peer e-mail even if archiving has not yet run;
  - no Rust core changes — `libnative-utils.so` from 2.49.6 is reused;
  - deployed `BMChat-foss-debug-2.49.7.apk` (76 447 615 bytes, SHA-256 `8a1e6291adc747ccf45372e244ddf9f6af0ac74b9fa90c1583e06bc40e4f9b4f`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/`, `/i`, `/update.json`, `/desktop-update.json` all return 200 and `update.json` advertises `2.49.7`.
- Built and deployed Android `2.49.8` (`versionCode 756`) — heads-up alerts and launcher-icon unread badge:
  - bumped `CH_MSG_VERSION 5 → 6`; `NotificationCenter` now deletes any previous `ch_msgV*_*` channel before creating the new v6 one with `IMPORTANCE_HIGH` and `setLockscreenVisibility(VISIBILITY_PUBLIC)`, falling back to `RingtoneManager.getDefaultUri(TYPE_NOTIFICATION)` when no ringtone is configured (otherwise Android silently demotes the heads-up to a quiet drawer entry);
  - per-message builder now sets `setVisibility(VISIBILITY_PUBLIC)` + `setBadgeIconType(BADGE_ICON_LARGE)` and falls back to the system default sound on pre-O;
  - added `me.leolin:ShortcutBadger:1.1.22@aar` plus a new `BMChatBadge` helper that updates the launcher-icon unread count via OEM-specific intent broadcasts (Xiaomi MIUI, Huawei EMUI, OPPO/Vivo, Sony, etc.). Bound to every relevant `DC_EVENT_*`, all notification add/remove paths, and an initial refresh in `ApplicationContext.onCreate()` so the badge is correct after reboot;
  - replaced the hard-coded "Delta Chat / New messages" summary text with `R.string.app_name` + a new `notify_new_messages` (RU "Новые сообщения" / EN "New messages");
  - no Rust core changes — `libnative-utils.so` from 2.49.6 is reused;
  - deployed `BMChat-foss-debug-2.49.8.apk` (76 456 014 bytes, SHA-256 `a1f960fee085a52671b2137bc1493ed525bba58438ac03eb92778e900cfab68b`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/`, `/i`, `/update.json`, `/desktop-update.json` all return 200 and `update.json` advertises `2.49.8`.
- Built and deployed Android `2.49.9` (`versionCode 757`) — notification content (sender name/avatar/preview) and quiet foreground service:
  - per-chat notifications switched from `InboxStyle` to `NotificationCompat.MessagingStyle`. Each row in the heads-up / drawer entry now carries the sender's `Person` (name + circular avatar bitmap rendered through Glide via `getAvatarForContact(DcContact)`) plus the actual text or media-summary content; the chat avatar still rides as `setLargeIcon` for the collapsed view;
  - new helper `BMChatNames#humanize(rawName, addr)` derives a friendly label from the local-part of an e-mail (`john.doe@yandex.ru` → `John Doe`) when the peer hasn't supplied a Display Name yet. Wired into `Recipient.getName()` (chat list, conversation header, picker dialogs) and into `NotificationCenter.notifyMessage`/`notifyReaction` and the per-chat title;
  - notification priority is forced to `>= NotificationCompat.PRIORITY_HIGH` for older OEM ROMs that silently demote heads-up otherwise; `getNotificationChannel()` always binds the platform default notification sound when the user hasn't picked one (prevents Samsung One UI from creating a silent channel on first install);
  - `CH_MSG_VERSION 6 → 7` so devices upgrading from 2.49.8 get the freshly-created channel with the guaranteed sound;
  - `BMChatInviteAutoAcceptor.processLink` now extracts `n=` and `a=` from the invite URL (fragment or query) and calls `dcContext.createContact(name, addr)` BEFORE `joinSecurejoin`, so the chat list shows the peer's nickname immediately;
  - foreground service notification ("BMChat работает в фоне") collapsed into a bare status-bar icon: `KeepAliveService` channel id bumped to `bmchat_fg_notification_ch_v3` with `IMPORTANCE_MIN`, `setShowBadge(false)`, `setSound(null,null)`, `setLockscreenVisibility(VISIBILITY_SECRET)`; legacy `*_v2`/`*` channels are deleted on first run; builder uses `PRIORITY_MIN`, `VISIBILITY_SECRET`, no body text, short `notify_background_connection_title = "BMChat"`;
  - no Rust core changes — `libnative-utils.so` from 2.49.6 is reused;
  - deployed `BMChat-foss-debug-2.49.9.apk` (76 461 802 bytes, SHA-256 `6d1fd55a664abc9d4049458033057ddcf7dc432528860138e8d568068dacbf31`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/`, `/i`, `/update.json`, `/desktop-update.json` all return 200 and `update.json` advertises `2.49.9`.
- Built and deployed Android `2.49.10` (`versionCode 758`) — auto-update fires on every foreground transition:
  - `BMChatUpdater.bindGlobalLifecycle(Application)` hooks an `ActivityLifecycleCallbacks` from `ApplicationContext.onCreate()`. It tracks the top activity for hosting the dialog and detects process-level background→foreground transitions to force a fresh manifest probe;
  - `forceCheckOnForeground()` bypasses the silent debounce on every foreground return, with a `60 s` mini-debounce so quick back-to-back transitions don't hammer the manifest;
  - `MIN_CHECK_INTERVAL_MS` 1 h → 15 min and `PERIODIC_RECHECK_MS` 30 min → 15 min, since BMChat ships hot-fix builds in quick succession and `update.json` is ~700 bytes;
  - the prompt is now hosted by the latest resumed activity (chat list, conversation, settings, …), buttons read «Скачать и установить» / «Позже» / «Пропустить эту версию», and a `promptShown` flag prevents double-open on rotation or simultaneous probes;
  - download dialog now shows a real horizontal progress bar and "X MB of Y MB" status, driven by a `ProgressListener` callback fed by `downloadApk()` (reports every 200 ms), with a re-entrancy guard against duplicate taps;
  - `ConversationListActivity.onResume` still calls `BMChatUpdater.scheduleForActivity()` (kept as a deprecated compatibility entry point) but the actual work is driven by the global lifecycle hook;
  - no Rust core changes — `libnative-utils.so` from 2.49.6 is reused;
  - deployed `BMChat-foss-debug-2.49.10.apk` (76 464 357 bytes, SHA-256 `c63a8ab5e4ace31509126eac59e1cb1825cdf0da70717ad3ff816616aabf804d`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/`, `/i`, `/update.json`, `/desktop-update.json` all return 200 and `update.json` advertises `2.49.10`.
- Built and deployed Android `2.49.11` (`versionCode 759`) — proactive profile broadcast to peers:
  - new `BMChatProfilePublisher` walks `getChatlist()` for the active account, picks every active 1:1 chat (no self/device/info/mailing list/contact request/group/non-canSend), deduplicates peer contact IDs, and sends a short `🔄 Profile updated.` text into each one. The actual profile carrier is the message's `From:` name and `Chat-User-Avatar` header that Delta Chat core already attaches — Delta Chat resets `contacts.selfavatar_sent=0` on every `Selfavatar` config write, so this fan-out delivers the new card to every recipient even if they never reply;
  - hard cap of 200 recipients per pass; group chats are intentionally skipped to avoid spamming many participants per profile change;
  - `CreateProfileActivity.updateProfile()` now compares the pre/post name and avatar-changed flag and, if either differs, surfaces a "Разослать новый профиль контактам?" AlertDialog after the local save with «Разослать» / «Не сейчас» buttons (the latter just finishes the screen, the former fires the publisher first);
  - new manual entry point `Settings → Дополнительные параметры → Разослать профиль контактам` wired in `AdvancedPreferenceFragment` to the same dialog → publisher path;
  - localised strings (RU/EN) for the publish dialog, menu entry, success/empty toasts, and the broadcast body marker;
  - no Rust core changes — `libnative-utils.so` from 2.49.6 is reused;
  - deployed `BMChat-foss-debug-2.49.11.apk` (76 471 695 bytes, SHA-256 `1a2b4c08395366498dcf9b711fc71745aa5632ab6887b60f32bbb3f03e268d15`) and repointed `update.json` + `BMChat-foss-debug-latest.apk`. Verified `/`, `/i`, `/update.json`, `/desktop-update.json` all return 200 and `update.json` advertises `2.49.11`.

## Not Started

- Desktop counterpart of the e-mail invite UI ("Send via e-mail" button in QR dialog) and matching desktop auto-acceptor hook.
- Real desktop installer build hosted under `http://5.187.4.132/desktop/<arch>/` (the manifest currently advertises the running version so the dialog stays silent until a newer installer is published).
- Strict localization checker scripts.
- iOS port of the email-invite UI + auto-acceptor.
- iOS build verification on macOS/Xcode.

## Known Issues And Risks

- The Rust core for Android is now rebuilt with BMChat patches; the iOS native core mirrored under `clients/ios/deltachat-ios/libraries/deltachat-core-rust` still needs the same cross-compile pass on macOS before the iOS `.framework` reflects the patches at runtime.
- iOS cannot be fully built on Windows; macOS/Xcode is required.
- Desktop and Android GPL licensing affects distribution model.
- Upstream repository sizes are significant, especially Android and iOS.
- Push notification setup will require new Firebase/APNs configuration for BMChat.
- Android reproducible release builds must run the upstream native core build (`scripts/ndk-make.sh`) in a Linux/Nix/Docker environment before Gradle packaging. Windows Gradle-only packaging is not enough.
- Electron Builder produces local Windows artifacts but exits with a GitHub token warning if `GH_TOKEN` is not set for auto-publish.
- Desktop packaging mutates pnpm/node_modules state via upstream `pack:patch-node-modules`; use a clean install before development checks.
- On this Windows machine, pnpm can corrupt/restore TypeScript package contents incorrectly (`typescript@5.9.3` or `5.8.3` containing 4.9.5). If `pnpm -w check` reports invalid TS options, fetch the official TypeScript tarball with npm and replace the affected local `node_modules/.pnpm/typescript@.../node_modules/typescript` copy before rerunning checks.
