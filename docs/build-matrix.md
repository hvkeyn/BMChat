# Build Matrix

This file records build expectations for BMChat platform clients.

## Desktop

Local path: `clients/desktop`

Requirements:
- Node.js 22, from `.nvmrc`.
- pnpm 9.6 or newer.
- Workspace packages from `pnpm-workspace.yaml`.
- Platform-specific signing tools for release builds.

Common commands:

```sh
pnpm install
pnpm -w check
pnpm -w build:electron
pnpm -w start:electron
```

Packaging targets from upstream docs:

```sh
cd packages/target-electron
pnpm build4production
pnpm pack:generate_config
pnpm pack:patch-node-modules
pnpm pack:win
pnpm pack:linux
pnpm pack:mac
```

Notes:
- macOS packaging/signing needs macOS and Apple certificates.
- Windows packaging can require Developer Mode and short paths.
- Linux packaging includes AppImage and deb targets.
- Current catalog pins Delta Chat core npm packages at `2.49.0`.
- Branding-sensitive package files include `package.json`, `packages/target-electron/package.json`, generated `electron-builder.json5`, and Tauri config.

## Android

Local path: `clients/android`

Requirements:
- Android Studio or command-line Android SDK.
- Android SDK 36 and NDK 27.0.12077973 according to current upstream Gradle config.
- Gradle 8.13 from `gradle/wrapper/gradle-wrapper.properties`.
- Android Gradle plugin 8.11.1 from `build.gradle`.
- Rust toolchain for native core.
- Optional Docker/Podman/Nix environment from upstream docs.

Common commands:

```sh
scripts/ndk-make.sh
./gradlew assembleDebug
./gradlew assembleRelease
```

Notes:
- Windows may need path/script adjustments for shell scripts.
- gplay builds require BMChat Firebase configuration.
- Release builds require BMChat keystore credentials.
- Current `compileSdk` and `targetSdkVersion` are 36.
- Current `minSdkVersion` is 21.
- Current upstream version is `versionName "2.49.0"` and `versionCode 747`.
- `foss` and `gplay` flavors produce different app identities; BMChat must set both deliberately.

## iOS

Local path: `clients/ios`

Requirements:
- macOS.
- Xcode.
- rustup and Rust toolchain `1.91.1` from `rust-toolchain`.
- CocoaPods.
- iOS deployment target 13.0 from `Podfile`.
- Apple Developer Team and signing setup for devices/TestFlight/App Store.

Common commands:

```sh
git submodule update --init --recursive
rustup toolchain install "$(cat rust-toolchain)"
pod install
open deltachat-ios.xcworkspace
```

Notes:
- iOS cannot be fully built in this Windows workspace.
- App groups, bundle IDs, and entitlements must be replaced before release signing.
- Main CocoaPods dependencies include SwiftLint, SwiftFormat, ReachabilitySwift, SDWebImage, SVGKit, MCEmojiPicker, and WebRTC-lib 140.0.0.
- Current upstream app marketing version in the Xcode project is 2.49.2.

## CI Recommendation

Use separate jobs per client:

- `desktop-check`: install pnpm dependencies, run `pnpm -w check`, optionally `pnpm -w test`.
- `desktop-package-windows`: Windows runner, Electron build and `pnpm pack:win`.
- `desktop-package-linux`: Linux runner, Electron build and `pnpm pack:linux`.
- `desktop-package-macos`: macOS runner, Electron build and `pnpm pack:mac` with signing secrets.
- `android-debug`: Linux runner or container, native core build plus `./gradlew assembleDebug`.
- `android-release`: protected runner with BMChat keystore and Firebase files.
- `ios-build`: macOS runner with Xcode, CocoaPods, Rust, Apple signing, and submodules.

## Smoke Checklist

Run for every platform when builds are available:

- App starts with BMChat name/icon.
- Russian language is selectable or active according to system/app settings.
- Account/profile creation works.
- Existing account import/backup restore works.
- Message send/receive works.
- Notifications work.
- Invite/QR flow works.
- Theme/appearance selection works.
- License/about screens preserve required attribution.

## Release Blockers

- Final app identifiers and bundle IDs.
- Final icon and artwork set.
- Android keystore.
- Apple Developer Team and provisioning profiles.
- Firebase/FCM configuration for Android gplay.
- APNs/push setup for iOS.
- Source availability and license notices for GPL desktop/Android releases.
