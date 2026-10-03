import Foundation

/// Prefer an existing encrypted 1:1 chat for an e-mail address. If none
/// exists, send a SecureJoin invite once and hold plaintext until the
/// chat becomes encrypted.
enum BMChatPeerChat {
    private static let pendingKey = "ui.bmchat.pending_plaintext"

    static func encryptedChatId(_ dc: DcContext, _ addr: String) -> Int {
        let want = addr.trimmingCharacters(in: .whitespacesAndNewlines)
        if want.isEmpty { return 0 }
        var ids = Set<Int>()
        for flags in [Int32(0), Int32(DC_GCL_ADDRESS)] {
            for id in dc.getContacts(flags: flags, queryString: want) {
                ids.insert(id)
            }
        }
        let lookedUp = dc.lookupContactIdByAddress(want)
        if lookedUp > 0 { ids.insert(lookedUp) }
        for id in ids {
            if id <= Int(DC_CHAT_ID_LAST_SPECIAL) { continue }
            let contact = dc.getContact(id: id)
            if contact.email.caseInsensitiveCompare(want) != .orderedSame { continue }
            let chatId = dc.getChatIdByContactId(id)
            if chatId == 0 { continue }
            let chat = dc.getChat(chatId: chatId)
            if chat.isMultiUser || !chat.isEncrypted { continue }
            if chat.isArchived {
                dc.setChatVisibility(chatId: chatId, visibility: DC_CHAT_VISIBILITY_NORMAL)
            }
            return chatId
        }
        return 0
    }

    static func preferEncrypted(_ dc: DcContext, _ chatId: Int) -> Int {
        guard isPlainSingle(dc, chatId), let addr = peerAddress(dc, chatId) else {
            return chatId
        }
        let encrypted = encryptedChatId(dc, addr)
        return encrypted != 0 ? encrypted : chatId
    }

    static func isPlainSingle(_ dc: DcContext, _ chatId: Int) -> Bool {
        if chatId <= Int(DC_CHAT_ID_LAST_SPECIAL) { return false }
        let chat = dc.getChat(chatId: chatId)
        if chat.isMultiUser || chat.isEncrypted || chat.isSelfTalk || chat.isDeviceTalk || chat.isMailinglist {
            return false
        }
        return peerAddress(dc, chatId) != nil
    }

    @discardableResult
    static func holdUntilEncrypted(_ dc: DcContext, _ chatId: Int, _ text: String) -> Bool {
        if !isPlainSingle(dc, chatId) { return false }
        if preferEncrypted(dc, chatId) != chatId { return false }
        ensureInvite(dc, chatId)
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { queue(dc, chatId, text) }
        return true
    }

    static func ensureInvite(_ dc: DcContext, _ chatId: Int) {
        if !isPlainSingle(dc, chatId) { return }
        let key = "ui.bmchat.invited.\(chatId)"
        if dc.getConfig(key) == "1" { return }
        guard let raw = dc.getSecurejoinQr(chatId: 0),
              let invite = Utils.rewriteInviteLink(raw), !invite.isEmpty else { return }
        let message = dc.newMessage(viewType: DC_MSG_TEXT)
        message.text = invite
        dc.sendMessage(chatId: chatId, message: message)
        dc.setConfig(key, "1")
    }

    static func waitingText() -> String {
        let key = "bmchat_wait_for_encryption"
        let value = String.localized(key)
        if value != key { return value }
        let lang = Locale.preferredLanguages.first ?? "en"
        if lang.hasPrefix("ru") {
            return "Чат ещё не защищён. Приглашение отправлено. Сообщение уйдёт, когда собеседник примет его в BMChat."
        }
        return "This chat is not encrypted yet. An invitation was sent. Your message will be delivered after the contact accepts it in BMChat."
    }

    static func flushPending(_ dc: DcContext) {
        guard let raw = dc.getConfig(pendingKey), !raw.isEmpty,
              let data = raw.data(using: .utf8),
              var pending = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] else {
            return
        }
        var changed = false
        for (key, text) in pending {
            guard let chatId = Int(key) else {
                pending.removeValue(forKey: key)
                changed = true
                continue
            }
            let target = preferEncrypted(dc, chatId)
            let chat = dc.getChat(chatId: target)
            if !chat.isEncrypted { continue }
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let message = dc.newMessage(viewType: DC_MSG_TEXT)
                message.text = text
                dc.sendMessage(chatId: target, message: message)
            }
            pending.removeValue(forKey: key)
            changed = true
        }
        if changed {
            if pending.isEmpty {
                dc.setConfig(pendingKey, "")
            } else if let data = try? JSONSerialization.data(withJSONObject: pending),
                      let raw = String(data: data, encoding: .utf8) {
                dc.setConfig(pendingKey, raw)
            }
        }
    }

    private static func queue(_ dc: DcContext, _ chatId: Int, _ text: String) {
        var pending: [String: String] = [:]
        if let raw = dc.getConfig(pendingKey), let data = raw.data(using: .utf8),
           let parsed = (try? JSONSerialization.jsonObject(with: data)) as? [String: String] {
            pending = parsed
        }
        pending[String(chatId)] = text
        if let data = try? JSONSerialization.data(withJSONObject: pending),
           let raw = String(data: data, encoding: .utf8) {
            dc.setConfig(pendingKey, raw)
        }
    }

    private static func peerAddress(_ dc: DcContext, _ chatId: Int) -> String? {
        let ids = dc.getChat(chatId: chatId).getContactIds(dc)
        guard ids.count == 1, ids[0] > Int(DC_CHAT_ID_LAST_SPECIAL) else { return nil }
        let addr = dc.getContact(id: ids[0]).email.trimmingCharacters(in: .whitespacesAndNewlines)
        return addr.isEmpty ? nil : addr
    }
}
