# Progress

## Current Status

Initial BMChat fork setup plan is complete and pushed to GitHub. First visible BMChat rebrand pass is implemented locally. BMChat server/filtering pass is implemented locally with new test builds. After user feedback against the previous build, a UI-side guard layer was added across Android, desktop, and iOS that hides mailing-list / classic-email noise, suppresses related notifications, scrubs Delta artwork from QR codes, rewrites legacy `i.delta.chat` invite hyperlinks to `i.bmchat.example`, fixes the "Больше информации" button on the Android login form, and rewrites the `secure_join_wait` device message.

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

## In Progress

Manual artifact testing for the new server/filtering builds.

## Not Started

- Strict localization checker scripts.
- iOS build verification on macOS/Xcode.

## Known Issues And Risks

- The Rust core changes (strict mail filter, donation off, BMChat invite host, etc.) are NOT yet compiled into `libnative-utils.so` because cross-compiling needs a Linux+NDK environment. UI guard rails plug the gap on Android/desktop/iOS, but the proper fix is to rebuild the native core.
- iOS cannot be fully built on Windows; macOS/Xcode is required.
- Desktop and Android GPL licensing affects distribution model.
- Upstream repository sizes are significant, especially Android and iOS.
- Push notification setup will require new Firebase/APNs configuration for BMChat.
- Android reproducible release builds must run the upstream native core build (`scripts/ndk-make.sh`) in a Linux/Nix/Docker environment before Gradle packaging. Windows Gradle-only packaging is not enough.
- Electron Builder produces local Windows artifacts but exits with a GitHub token warning if `GH_TOKEN` is not set for auto-publish.
- Desktop packaging mutates pnpm/node_modules state via upstream `pack:patch-node-modules`; use a clean install before development checks.
- On this Windows machine, pnpm can corrupt/restore TypeScript package contents incorrectly (`typescript@5.9.3` or `5.8.3` containing 4.9.5). If `pnpm -w check` reports invalid TS options, fetch the official TypeScript tarball with npm and replace the affected local `node_modules/.pnpm/typescript@.../node_modules/typescript` copy before rerunning checks.
