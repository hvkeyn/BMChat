# Russian Localization Audit

Audit date: 2026-05-04.

## Summary

Russian localization exists in all three clients, but it is still Delta Chat-branded and not complete enough for a BMChat release without a focused pass.

## File Coverage

| Platform | Russian files found | Baseline files | Approximate key count |
| --- | --- | --- | --- |
| Desktop | `clients/desktop/_locales/ru.xml` | `clients/desktop/_locales/en.xml` | `ru.xml`: 845 strings, `en.xml`: 868 strings |
| Android | `clients/android/src/main/res/values-ru/strings.xml` | `clients/android/src/main/res/values/strings.xml` | `values-ru`: 868 strings, `values`: 872 strings |
| iOS | `clients/ios/deltachat-ios/ru.lproj/Localizable.strings`, `InfoPlist.strings`, `Localizable.stringsdict` | `en.lproj` equivalents | `ru Localizable`: 838 entries, `en Localizable`: 867 entries |
| Android store | `clients/android/fastlane/metadata/android/ru/*` | `fastlane/metadata/android/en-US/*` | Russian title, short description, full description present |
| Desktop help | `clients/desktop/static/help/ru/help.html` | `static/help/en/help.html` | Russian help page present |

Counts are a quick regex-based audit, not a substitute for a strict key-diff script.

## Remaining Delta Chat Brand References

High-priority visible Russian replacements:

- Desktop `clients/desktop/_locales/ru.xml`: 36 Delta Chat / Delta domain references.
- Android `clients/android/src/main/res/values-ru/strings.xml`: 38 Delta Chat / Delta domain references.
- iOS `clients/ios/deltachat-ios/ru.lproj/Localizable.strings`: 29 Delta Chat / Delta domain references.
- iOS `clients/ios/deltachat-ios/ru.lproj/InfoPlist.strings`: 7 Delta Chat permission-prompt references.
- Android store `clients/android/fastlane/metadata/android/ru`: 5 visible Delta Chat references.
- Desktop help `clients/desktop/static/help/ru/help.html`: at least 135 visible Delta Chat / Delta domain references.

## Representative Strings To Replace

Desktop and Android XML:

- `app_name`: `Delta Chat`.
- `invite_friends_text`: invites user to contact via Delta Chat.
- `donate_device_msg`: donation copy points to `https://delta.chat/donate`.
- `multidevice_*`: instructions say to install/open/update Delta Chat.
- `send_stats_to_devs` and related stats strings mention Delta Chat.
- `welcome_desktop`, `delta_chat_homepage`, `global_menu_help_about_desktop`, `global_menu_file_open_desktop`.
- Permission strings such as `InfoPlist_NSCameraUsageDescription` mention Delta Chat.
- Android-only `location_rationale` and data migration strings mention Delta Chat.

iOS:

- `Info.plist` and `ru.lproj/InfoPlist.strings` permission prompts mention Delta Chat.
- `ru.lproj/Localizable.strings` mirrors most Android/Desktop brand strings.
- Bundle URL names and schemes in `Info.plist` still use `chat.delta`.

Store/help:

- `fastlane/metadata/android/ru/title.txt` is `Delta Chat`.
- `fastlane/metadata/android/ru/full_description.txt` describes Delta Chat.
- `static/help/ru/help.html` is a translated Delta Chat FAQ; it should either be fully rewritten for BMChat or clearly moved to an upstream/technical attribution section.

## Localization Rules For BMChat

- Replace product-name mentions with `BMChat` in normal UI.
- Use `BroMoreChat` only in marketing/store/about contexts where the longer brand is desired.
- Replace user action instructions that say "install/open/update Delta Chat" with BMChat equivalents.
- Replace `delta.chat`, `get.delta.chat`, and `support.delta.chat` links only where BMChat will provide equivalent pages; otherwise keep them in attribution/upstream help sections.
- Preserve technical identifiers such as `DeltaChat` mail folder names only after confirming whether changing them affects protocol or compatibility.
- Preserve license and source attribution references to Delta Chat.

## Required Follow-Up

Create a strict localization checker before release:

- Parse Android/Desktop XML string keys and compare Russian against English.
- Parse iOS `.strings` and `.stringsdict` keys and compare Russian against English.
- Search for visible English strings in Russian files.
- Search for `Delta Chat`, `Deltachat`, `DeltaChat`, `delta.chat`, `chat.delta`, and store/help links.
- Produce a report that separates user-visible branding, legal attribution, and protocol/compatibility strings.
