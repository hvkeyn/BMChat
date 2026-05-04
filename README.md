# BMChat

BMChat / BroMoreChat is a planned branded fork of Delta Chat for desktop, Android, and iOS.

The goal is to keep Delta Chat's decentralized messaging functionality while adding BMChat branding, configurable appearance, complete Russian localization, and a clear shared-chat UX for existing group chats.

## Clients

| Path | Platform | Upstream |
| --- | --- | --- |
| `clients/desktop` | Windows, Linux, macOS | `deltachat/deltachat-desktop` |
| `clients/android` | Android | `deltachat/deltachat-android` |
| `clients/ios` | iOS | `deltachat/deltachat-ios` |

Shared planning and audit documents are in `docs/`. Brand source values are in `brand/config/bmchat-brand.json`.

## Current Status

This repository is the BMChat fork workspace. The upstream clients are present, the first audits are documented, and the first visible rebrand pass is implemented for desktop, Android, and iOS client layers.

Implemented in the first rebrand pass:

- `DESIGN.md` and `brand/config/bmchat-brand.json` define BMChat's first design tokens.
- Desktop Electron metadata, app IDs, themes, local strings, help pages, and placeholder artwork use BMChat.
- Android application IDs, app label, colors, launcher resources, Russian/English strings, and store metadata use BMChat.
- iOS display names, bundle IDs, app groups, permission prompts, Russian/English strings, and basic color assets use BMChat.
- Existing group chats are surfaced as shared/common chats in Russian and English.

Not yet complete:

- Final production domains, signing identities, Firebase/APNs setup, and store metadata approval.
- Final designer-provided BMChat icon and artwork set.
- iOS build verification, because it requires macOS and Xcode.

Verified in this workspace:

- Desktop `pnpm -w check` passed with upstream warnings only when run through a Node 22 pnpm wrapper.
- Desktop `pnpm -w build:electron` passed with `VERSION_INFO_GIT_REF=bmchat-rebrand`.
- Desktop Windows test builds were produced:
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Setup.x64.exe`
  - `clients/desktop/packages/target-electron/dist/BMChat-2.49.1-Portable.x64.exe`
- Android `./gradlew.bat assembleDebug` passed and produced debug APKs:
  - `clients/android/build/outputs/apk/foss/debug/BMChat-foss-debug-2.49.0.apk`
  - `clients/android/build/outputs/apk/gplay/debug/BMChat-gplay-debug-2.49.0.apk`
- iOS `xcodebuild` is not available on this Windows machine.

## Development

Read the project context first:

```sh
memory-bank/projectbrief.md
memory-bank/activeContext.md
docs/fork-strategy.md
docs/brand-map.md
docs/localization-audit.md
docs/theme-plan.md
docs/build-matrix.md
```

Desktop:

```sh
cd clients/desktop
pnpm install
pnpm -w check
pnpm -w build:electron
pnpm -w start:electron
```

Android:

```sh
cd clients/android
scripts/ndk-make.sh
./gradlew assembleDebug
```

iOS requires macOS with Xcode:

```sh
cd clients/ios
git submodule update --init --recursive
rustup toolchain install "$(cat rust-toolchain)"
pod install
open deltachat-ios.xcworkspace
```

## Licensing

This repository contains upstream Delta Chat clients and core code with their original licenses and notices.

- Desktop and Android clients are GPL-based.
- iOS client and Chatmail/Delta Chat core code are MPL-based according to the upstream license files.
- Keep license notices and copyright attribution intact.

See `docs/licensing-notes.md` for the current engineering notes.
