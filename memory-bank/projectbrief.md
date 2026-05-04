# Project Brief

BMChat / BroMoreChat is planned as a branded fork of Delta Chat for desktop, Android, and iOS.

The product goal is to preserve Delta Chat's decentralized messenger functionality while replacing user-facing branding, visual identity, application identifiers, store metadata, and Russian-language user experience with BMChat-specific equivalents.

Initial scope:
- Desktop client for Windows, Linux, and macOS based on `deltachat/deltachat-desktop`.
- Android client based on `deltachat/deltachat-android`.
- iOS client based on `deltachat/deltachat-ios`.
- Shared upstream core dependency from Delta Chat / Chatmail core where used by the clients.
- Russian localization audit and completion across all clients.
- Theme and branding layer that can be changed without repeatedly hand-editing every platform.

Key constraint: desktop and Android upstream clients are GPL-based, so distribution of BMChat builds based on those clients must be planned as GPL-compatible unless a separate legal review chooses another architecture.
