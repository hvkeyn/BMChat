# BMChat

BMChat / BroMoreChat is a planned branded fork of Delta Chat for desktop, Android, and iOS.

The goal is to keep Delta Chat's decentralized messaging functionality while adding BMChat branding, configurable appearance, and complete Russian localization.

## Clients

| Path | Platform | Upstream |
| --- | --- | --- |
| `clients/desktop` | Windows, Linux, macOS | `deltachat/deltachat-desktop` |
| `clients/android` | Android | `deltachat/deltachat-android` |
| `clients/ios` | iOS | `deltachat/deltachat-ios` |

Shared planning and audit documents are in `docs/`. Brand source values are in `brand/config/bmchat-brand.json`.

## Current Status

This repository is the initial BMChat fork workspace. The upstream clients are present, and the first audits for branding, Russian localization, themes, licensing, and build requirements are documented.

Not yet complete:

- Final BMChat icons, colors, package IDs, and bundle IDs.
- First-pass code and asset rebranding.
- Release signing, Firebase/APNs setup, and store metadata.
- iOS build verification, because it requires macOS and Xcode.

Verified in this workspace:

- Desktop `pnpm -w check` passed with upstream warnings only.
- Desktop `pnpm -w build:electron` passed with `VERSION_INFO_GIT_REF=bmchat-initial`.
- Android `./gradlew.bat assembleDebug` passed and produced debug APKs for `foss` and `gplay`.
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
