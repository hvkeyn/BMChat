# Licensing Notes

This document is an engineering planning note, not legal advice.

## Upstream License Snapshot

| Component | Upstream | License observed |
| --- | --- | --- |
| Desktop | `deltachat/deltachat-desktop` | GPL-3.0-or-later |
| Android | `deltachat/deltachat-android` | GPLv3+ |
| iOS | `deltachat/deltachat-ios` | MPL-2.0 for app directories |
| Core | `chatmail/core` | MPL-2.0 |

## Practical Consequences

- BMChat desktop and Android builds based on the upstream clients should be treated as GPL-compatible distributions.
- Source availability, license notices, and modification rights must be preserved for recipients.
- BMChat branding, icons, screenshots, store text, signing credentials, and private service credentials can be BMChat-owned assets, but must be separated clearly from upstream-covered source where needed.
- Delta Chat trademark and logo usage should be removed from BMChat user-facing builds except where required for attribution, license, or documentation.

## Risk Areas

- Closed-source commercial distribution of desktop or Android clients based directly on GPL code is a major risk.
- Store distribution may require extra review for GPL obligations, source availability, and third-party notices.
- iOS App Store distribution of GPL components is historically sensitive; this project currently sees iOS app code under MPL-2.0, but dependencies must still be audited.
- Push notification services, hosted services, and custom servers may introduce separate terms and compliance requirements.

## Recommended Default

Proceed as an open, fork-compatible product until legal review confirms any alternative.

Do not remove upstream copyright notices.
Do not remove license files.
Do not imply that Delta Chat endorses BMChat.
