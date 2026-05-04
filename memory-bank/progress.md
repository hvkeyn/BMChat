# Progress

## Current Status

Initial BMChat fork setup plan is complete and pushed to GitHub. First visible BMChat rebrand pass is implemented locally.

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

## In Progress

Manual artifact testing.

## Not Started

- Asset generation for BMChat logos/icons/backgrounds.
- Strict localization checker scripts.
- iOS build verification on macOS/Xcode.

## Known Issues And Risks

- iOS cannot be fully built on Windows; macOS/Xcode is required.
- Desktop and Android GPL licensing affects distribution model.
- Upstream repository sizes are significant, especially Android and iOS.
- Push notification setup will require new Firebase/APNs configuration for BMChat.
- Android reproducible release builds must run the upstream native core build (`scripts/ndk-make.sh`) in a Linux/Nix/Docker environment before Gradle packaging. Windows Gradle-only packaging is not enough.
- Electron Builder produces local Windows artifacts but exits with a GitHub token warning if `GH_TOKEN` is not set for auto-publish.
- Desktop packaging mutates pnpm/node_modules state via upstream `pack:patch-node-modules`; use a clean install before development checks.
