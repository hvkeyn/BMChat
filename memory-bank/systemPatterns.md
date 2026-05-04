# System Patterns

BMChat is organized as a multi-client fork rather than a single rewritten application.

Expected layout:
- `clients/desktop`: Delta Chat Desktop fork, TypeScript/Electron with experimental Tauri/browser targets.
- `clients/android`: Delta Chat Android fork, Java/Android Gradle project with Rust native core build.
- `clients/ios`: Delta Chat iOS fork, Swift/Xcode/CocoaPods project with Rust core submodule.
- `brand`: BMChat source-of-truth for product names, colors, icons, app identifiers, and generated platform assets.
- `docs`: project decisions, build matrix, branding map, licensing notes, and audit reports.
- `memory-bank`: persistent project context for future sessions.

Technical strategy:
- Prefer shallow, maintainable deltas from upstream clients.
- Avoid broad package/class renames unless needed for store submission or runtime identity.
- Keep internal namespaces stable where changing them would make upstream merges risky.
- Move repeated branding decisions into documented config and generated assets.
- Preserve upstream license notices and copyright attribution.

Update strategy:
- Track upstream remotes for desktop, Android, and iOS separately.
- Keep BMChat changes on dedicated branches such as `bmchat/main`.
- Document every platform-specific branding or localization edit in `docs/brand-map.md`.
