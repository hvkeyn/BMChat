# Active Context

Current focus: first visible BMChat rebrand pass is implemented across desktop, Android, and iOS client layers.

Recent decisions:
- Start with all three clients: desktop, Android, and iOS.
- Treat licensing as unresolved for commercial/closed distribution; default to GPL-compatible open fork for desktop and Android.
- Keep upstream mergeability as a primary engineering constraint.
- Create project documentation before deep code modifications.
- Keep core submodules upstream-clean unless a proven user-facing requirement needs a controlled change.
- Use `BMChat` as the normal UI name and `BroMoreChat` for long-form product/marketing contexts.
- Treat "common chats" as existing Delta Chat group chats in this phase; no public Telegram-like communities are implemented yet.
- BMChat design direction is `Tech Utility + friendly messenger` with blue/green tokens in `DESIGN.md` and `brand/config/bmchat-brand.json`.
- Android manual testing exposed a startup-crash issue in the first rebuilt APKs: Gradle packaging ran without `libnative-utils.so`. The test APKs were repaired by restoring native libraries for all supported ABIs from the official Delta Chat `2.49.0` APK and rebuilding.

Immediate next steps:
- Retest the repaired Android APK artifacts on a real device.
- Replace placeholder icon/background assets with final designer-provided BMChat artwork.
- Add strict localization checker scripts before release.
- Prepare production domains, signing identities, Firebase/APNs projects, and store metadata.
- Verify iOS on macOS/Xcode.

Open questions:
- Final production website/domain.
- Final logo and store identity.
- Distribution model and legal review outcome.
