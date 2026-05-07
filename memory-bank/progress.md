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

- Real-device smoke test: install 2.49.0, observe auto-update prompt for 2.49.1, then exercise "Поделиться → Отправить на e-mail" with two BMChat accounts on different e-mail providers and verify auto-acceptance on the receiver.

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
