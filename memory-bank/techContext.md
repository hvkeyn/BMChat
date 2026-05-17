# Tech Context

Workspace OS for this session: Windows.

Upstream technology stacks:
- Desktop: TypeScript, React-style frontend packages, Electron primary target, pnpm workspace, Node 22+, optional Tauri/browser targets.
- Android: Java, Gradle Android plugin, Android SDK/NDK, Rust native core build via scripts, F-Droid/Google Play flavors.
- iOS: Swift, Xcode workspace, CocoaPods, Rust toolchain, submodules for core libraries.
- Core: Rust Chatmail/Delta Chat core used by platform clients.

Important build realities:
- Desktop can be developed on Windows, Linux, and macOS, but packaging behavior differs per OS.
- Android can be prepared on Windows, but upstream build docs focus on Linux/Nix/Docker and Android Studio setups.
- iOS builds require macOS with Xcode and signing credentials.
- Store distribution requires separate signing identities, package IDs, bundle IDs, notification services, and store metadata.
- Desktop packaging mutates pnpm/node_modules state via upstream `pack:patch-node-modules`; restore dependencies before development checks.
- On the current Windows setup, pnpm's local store has repeatedly restored wrong TypeScript contents for `typescript@5.9.3`/`5.8.3`. If desktop checks show TypeScript 4.9 behavior, repair the local package from the official npm tarball before rerunning.

Licensing context:
- `deltachat-desktop` is GPL-3.0-or-later.
- `deltachat-android` is GPLv3+.
- `deltachat-ios` is MPL-2.0 for the app directories per upstream license.
- `chatmail/core` is MPL-2.0.
