# Progress

## Current Status

Initial BMChat fork setup plan is complete.

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

## In Progress

Creating and pushing the private GitHub repository.

## Not Started

- First-pass code branding in desktop, Android, and iOS clients.
- Asset generation for BMChat logos/icons/backgrounds.
- Strict localization checker scripts.
- iOS build verification on macOS/Xcode.

## Known Issues And Risks

- iOS cannot be fully built on Windows; macOS/Xcode is required.
- Desktop and Android GPL licensing affects distribution model.
- Upstream repository sizes are significant, especially Android and iOS.
- Push notification setup will require new Firebase/APNs configuration for BMChat.
