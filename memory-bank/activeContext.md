# Active Context

Current focus: the BMChat-flavoured Rust core has been cross-compiled in WSL (Ubuntu 24.04 + Rust 1.91.1 + Android NDK r25c) for all four Android ABIs and the resulting `libnative-utils.so` is now baked into the APK. Strict mail filter, BMChat invite host, deactivated donations, and other core-level patches are now active in the native binary. The earlier UI guard layer in Java/TypeScript/Swift remains in place as a safety net.

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
- The Rust core was rebuilt for Android in WSL Ubuntu 24.04 with Rust 1.91.1 (matching `clients/android/scripts/rust-toolchain`) and Android NDK r25c (`/opt/android/android-ndk-r25c`) via `bash scripts/ndk-make.sh`. The resulting `libnative-utils.so` for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` is committed in `clients/android/libs/<ABI>/` so APK builds on Windows do not need the native toolchain. The patched core (strict mail filter in `receive_imf.rs`, no-op donation request, BMChat invite host in `qr.rs`/`securejoin.rs`, etc.) is now active inside the APK. We keep the platform-side guard rails as a defence in depth:
  - Android `ConversationListAdapter` filters mailing-list and unencrypted-contact-request chats out of the chat list; `NotificationCenter` skips notifications for those chats.
  - Desktop `QrCode` dialog and shared `util.ts` rewrite legacy invite links to `i.bmchat.example` and strip the embedded "δ" centerpiece + `get.delta.chat` footer from QR SVGs.
  - iOS `Utils.rewriteInviteLink` / `Utils.stripDeltaBranding` clean QR SVGs and shared invite URLs; `ChatListViewModel` hides mailing-list and unencrypted-contact-request chats; `NotificationManager` suppresses the matching notifications.
  - Android `Util.INVITE_DOMAIN` is now `i.bmchat.example` with a `LEGACY_INVITE_DOMAIN` fallback used to match incoming links; `QrShowFragment.fixSVG` strips Delta branding and rewrites copied/shared invite URLs.
  - The yellow "Больше информации" button in the Android login form now opens an in-app dialog with the full provider hint (or generic login help) instead of trying to open `providers.delta.chat`.
  - The `secure_join_wait` device message was rewritten to tell users they can keep writing emails directly if the other side is not on BMChat.

Immediate next steps:
- Retest the rebuilt Android APK artifacts on a real device, including strict filtering: ordinary mailbox mail must not appear; BMChat messages with `Chat-Version` must appear; QR codes must show the BMChat invite host and no Delta artwork.
- Manually verify desktop Windows installer/portable artifacts.
- Add strict localization checker scripts before release.
- Prepare production domains, signing identities, Firebase/APNs projects, and store metadata.
- Verify iOS on macOS/Xcode.

Open questions:
- Final production website/domain.
- Final logo and store identity.
- Distribution model and legal review outcome.
