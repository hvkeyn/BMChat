# Active Context

Current focus: BMChat server-independence, strict mail filtering, the BMCha.jpeg visual direction, and the donation/Delta-Chat metadata cleanup have been implemented across desktop, Android, iOS client layers, and the mirrored Rust core copies.

Recent decisions:
- Start with all three clients: desktop, Android, and iOS.
- Treat licensing as unresolved for commercial/closed distribution; default to GPL-compatible open fork for desktop and Android.
- Keep upstream mergeability as a primary engineering constraint.
- Create project documentation before deep code modifications.
- Keep core submodules upstream-clean unless a proven user-facing requirement needs a controlled change.
- Use `BMChat` as the normal UI name and `BroMoreChat` for long-form product/marketing contexts.
- Treat "common chats" as existing Delta Chat group chats in this phase; no public Telegram-like communities are implemented yet.
- BMChat design direction is now `Burgundy Tech + friendly messenger` based on `brand/assets/bmcha-logo-source.jpeg`.
- Android manual testing exposed a startup-crash issue in the first rebuilt APKs: Gradle packaging ran without `libnative-utils.so`. The test APKs were repaired by restoring native libraries for all supported ABIs from the official Delta Chat `2.49.0` APK and rebuilding.
- Empty endpoint values in `brand/config/bmchat-brand.json` mean the corresponding Delta/upstream integration must be disabled, not used as a fallback.
- BMChat keeps manual IMAP/SMTP login for arbitrary mail servers, but incoming classic emails without `Chat-Version` or a known BMChat/Delta thread are tombstoned and do not create chats/notifications.
- The Show Classic Emails UI is hidden in desktop, Android, and iOS; existing profiles are migrated to `show_emails=0`.
- The onboarding logo, the welcome backdrop, the tray/launcher icons, and the About dialog were re-rendered to match the BMChat burgundy "B" identity. The default chat onboarding string was rewritten to "Свободный чат через вашу почту" / "Free chat through your email".
- Donation surfaces were removed from the Settings screens, menus, device update messages, metainfo, and stock translations across desktop, Android, and iOS. The core no longer sends a recurring donation device message; `donation_request_maybe` is now a no-op that just disables future checks.
- All references to delta.chat, transifex, and Delta Chat issue trackers were stripped from active UI; the Help menus only point to the BMChat repository for issues.

Immediate next steps:
- Retest the new Android APK artifacts on a real device, including strict filtering: ordinary mailbox mail must not appear; BMChat messages with `Chat-Version` must appear.
- Manually verify desktop Windows installer/portable artifacts.
- Add strict localization checker scripts before release.
- Prepare production domains, signing identities, Firebase/APNs projects, and store metadata.
- Verify iOS on macOS/Xcode.

Open questions:
- Final production website/domain.
- Final logo and store identity.
- Distribution model and legal review outcome.
