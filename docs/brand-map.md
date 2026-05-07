# BMChat Brand Map

This file tracks every platform surface that must become BMChat-specific.

## Brand Defaults

| Field | Default |
| --- | --- |
| Short product name | BMChat |
| Long product name | BroMoreChat |
| Desktop package name | bmchat-desktop |
| Android application ID placeholder | `chat.bromore.bmchat` |
| iOS bundle ID placeholder | `chat.bromore.bmchat` |
| Public website / landing | `http://5.187.4.132/` |
| Default BMChat relay | _none — users authenticate to their own IMAP/SMTP server_ |
| Invite link prefix | `http://5.187.4.132/i#…` |
| Auto-update manifest | `http://5.187.4.132/update.json` |
| APK distribution base | `http://5.187.4.132/apk/` |

First-pass identifiers and design tokens are now implementation defaults. Final production domains, signing identities, Firebase/APNs projects, and store metadata still need release approval before public distribution.

## Infrastructure Defaults

`brand/config/bmchat-brand.json` is the source of truth for BMChat-controlled service endpoints. Empty endpoint values mean the corresponding Delta Chat upstream integration must be disabled rather than falling back to an upstream URL. Manual IMAP/SMTP login remains supported for arbitrary mail servers; BMChat must not silently route profile creation, statistics, push registration, provider help, or relay directory traffic through Delta Chat infrastructure.

Current endpoint policy:

- `defaultChatmailHost`: placeholder BMChat host for instant-account flows until a production relay exists.
- `relayDirectoryUrl`, `providerOverviewBaseUrl`, `notificationRegisterUrl`, `turnServerHost`, `statsRecipient`, `updateUrl`: empty until BMChat-owned services exist.
- `inviteHost`: BMChat-owned invite host placeholder replacing `i.delta.chat` in generated/accepted app links.
- `mailPolicy.showClassicEmailsDefault`: `off`, so normal mail remains in the mailbox and does not become chat UI noise.

## Visual Direction

BMChat uses the `Burgundy Tech + friendly messenger` direction documented in `DESIGN.md` and sourced from `brand/assets/bmcha-logo-source.jpeg`:

- Primary burgundy `#7B1226` for the B-mark identity, active navigation, and primary actions.
- Crimson accent `#C62A48` for unread/active states and connection highlights.
- Light canvas `#FFF7F8` and dark canvas `#160208`.
- Native system typography and restrained rounded surfaces.

## Desktop Surfaces

Audited after cloning:

- `clients/desktop/package.json`: root package name `deltachat-desktop` and workspace script filters `@deltachat-desktop/*`.
- `clients/desktop/packages/target-electron/package.json`: package scope, description, repository, keywords, author, `productName`, homepage, and `@deltachat/*` dependency names.
- `clients/desktop/packages/target-electron/src/application-config.ts`: user config directory name `DeltaChat` and portable data folder `DeltaChatData`.
- `clients/desktop/packages/target-electron/src/application-constants.ts`: application icon file name `images/deltachat.*` and temp directory `chat.delta.desktop-temp`.
- `clients/desktop/packages/target-electron/src/tray.ts`: tray icon file names `deltachat` and `deltachat-unread`.
- `clients/desktop/packages/target-electron/build/gen-electron-builder-config.js`: Electron app IDs, package names, Linux desktop text, deb package name, Windows icon path, and preview build names.
- `clients/desktop/packages/target-tauri/src-tauri/tauri.conf.json5`: Tauri `productName`, bundle identifier, Linux metadata paths, icon paths, and Apple team placeholder.
- `clients/desktop/packages/frontend/themes`: built-in theme files `light.scss`, `dark.scss`, `darkpurple.scss`, `dark_amoled.scss`, plus development themes.
- `clients/desktop/images` and `clients/desktop/images/tray`: application, tray, unread, background, onboarding, and generic product artwork.
- `clients/desktop/_locales/*.xml`: user-facing product strings and Russian translations.
- `clients/desktop/static/help/*/help.html`: local help pages with visible Delta Chat references.
- `clients/desktop/README.md`, `clients/desktop/RELEASE.md`, `clients/desktop/docs/*`: developer and release references.

First-pass implementation keeps upstream workspace package scopes where changing them would be merge-heavy, but changes visible product metadata, app IDs, data folders, theme colors, icons, local strings, and packaging names.

## Android Surfaces

Audited after cloning:

- `clients/android/build.gradle`: namespace remains `org.thoughtcrime.securesms`; `applicationId` is `com.b44t.messenger`; gplay flavor overrides it with `chat.delta`; archive base name is `deltachat`.
- `clients/android/src/main/AndroidManifest.xml`: app label references `@string/app_name`; deeplink host now uses `5.187.4.132` with path `/i` (legacy `i.bmchat.example` and `i.delta.chat` are also accepted); shortcut/provider/authority values require a deeper pass before changing.
- `clients/android/src/main/res/values/strings.xml`: `app_name` is `Delta Chat` and English product strings are the source baseline.
- `clients/android/src/main/res/values-ru/strings.xml`: Russian `app_name` is still `Delta Chat`.
- `clients/android/src/main/res/mipmap-*`, `clients/android/src/debug/res/mipmap-*`, `clients/android/src/main/res/drawable/ic_launcher_foreground_monochrome.xml`: launcher icon resources.
- `clients/android/ic_launcher-web.png`: web/store icon.
- `clients/android/fastlane/metadata/android/*`: store title, descriptions, screenshots, localized metadata, including Russian metadata under `ru`.
- `clients/android/google-services.json`: must be replaced for a BMChat Google/Firebase project before gplay builds.
- `clients/android/jni/deltachat-core-rust`: submodule contains many Delta Chat references; treat as upstream core and avoid brand edits unless a user-facing stock string is proven to come from core.

First-pass implementation changes install identity, visible labels, strings, colors, launcher resources, and store metadata while keeping Java package names stable.

## iOS Surfaces

Audited after cloning:

- `clients/ios/deltachat-ios.xcodeproj/project.pbxproj`: product comments include `Delta Chat.appex`; bundle IDs include `chat.delta`, `chat.delta.DcShare`, `chat.delta.DcNotificationService`, `chat.delta.DcWidget`, and `chat.delta.Clip`; `PRODUCT_NAME` contains `Delta Chat` for share extension; `DEVELOPMENT_TEAM` is upstream-owned.
- `clients/ios/deltachat-ios/Info.plist`: `CFBundleDisplayName` is `Delta Chat`; URL names/schemes include `chat.delta`; permission prompts mention Delta Chat.
- `clients/ios/DcShare/Info.plist`, `clients/ios/DcNotificationService/Info.plist`, `clients/ios/DcWidget/Info.plist`, `clients/ios/DcAppClip/Info.plist`: extension metadata and visible names.
- `clients/ios/**/*.entitlements`: app groups and associated services must be changed with bundle IDs.
- `clients/ios/deltachat-ios/Assets.xcassets/AppIcon.appiconset`: app icon set.
- `clients/ios/deltachat-ios/Assets.xcassets/dc_logo.imageset`, `ic_chat.imageset`, `background_light.imageset`, `background_dark.imageset`, and `Colors/*`: visible brand and color assets.
- `clients/ios/deltachat-ios/*.lproj/InfoPlist.strings`: localized app names and permission prompts, including Russian.
- `clients/ios/deltachat-ios/*.lproj/Localizable.strings` and `.stringsdict`: localized UI strings, including Russian.
- `clients/ios/deltachat-ios/libraries/deltachat-core-rust`: submodule contains many Delta Chat references; treat as upstream core and avoid brand edits unless a user-facing stock string is proven to come from core.

First-pass implementation changes visible app names, permission prompts, bundle IDs, extension labels, and app groups. Full iOS build verification remains macOS/Xcode-only.

## First Implementation Order

1. Change app identities and user-visible names only in client layers.
2. Replace icons and theme/color resources using `brand/config/bmchat-brand.json` as source.
3. Replace store metadata and local help content.
4. Audit core-provided stock strings separately before touching core submodules.
5. Leave package/class/module namespaces unchanged unless they affect install identity, signing, or visible metadata.

## Attribution Rule

Replace user-facing branding. Preserve license notices, changelog attribution where legally relevant, and source comments unless they cause visible product identity issues.
