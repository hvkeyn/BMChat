# BMChat Shared Chat Verification

BMChat first-pass "common chats" map to the existing Delta Chat group-chat feature. No protocol or core change is needed for this stage.

## Desktop

- UI entry: `clients/desktop/packages/frontend/src/components/dialogs/CreateChat/index.tsx`.
- The new-chat dialog exposes `menu_new_group`, now labeled "New Shared Chat" / "Новый общий чат".
- `CreateGroup` collects a shared chat name, optional image, and members.
- Creation calls `BackendRemote.rpc.createGroupChat(accountId, groupName, false)`.
- Members are added with `BackendRemote.rpc.addContactToChat`.

## Android

- UI entry: `clients/android/src/main/java/org/thoughtcrime/securesms/GroupCreateActivity.java`.
- `updateViewState()` uses `R.string.menu_new_group`, now labeled as a shared chat in English/Russian.
- `createGroup()` calls `rpc.createGroupChat(accId, groupName, false)` for regular groups.
- Selected contacts are added with `dcContext.addContactToChat(groupChatId, contactId)`.

## iOS

- UI entry: `clients/ios/deltachat-ios/Controller/NewGroupController.swift`.
- `viewDidLoad()` uses `String.localized("menu_new_group")`, now labeled as a shared chat in English/Russian.
- `doneButtonPressed()` calls `dcContext.createGroupChat(verified: allMembersVerified(), name: groupName)`.
- Selected members are added with `dcContext.addContactToChat(chatId: groupChatId, contactId: contactId)`.

## Result

The existing clients already support creating group/shared chats with members. BMChat's first pass surfaces that flow with product-specific Russian and English wording. Public communities, searchable groups, channels as a Telegram-like product layer, bots, and web are future roadmap items and are not implemented in this pass.
