package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.util.Log;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONObject;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.Util;

import java.util.Iterator;
import java.util.Locale;

/**
 * A 1:1 chat started from an e-mail address is unencrypted until SecureJoin
 * finishes. If that address already has an encrypted chat (the person uses
 * BMChat), keep using that chat. Otherwise send the invite once and hold the
 * user's text until the chat becomes encrypted, instead of mailing it in the
 * clear.
 */
public final class BMChatPeerChat {

  private static final String TAG = "BMChatPeerChat";
  private static final String PENDING_KEY = "ui.bmchat.pending_plaintext";

  private BMChatPeerChat() {}

  public static int openSingleChat(Context context, DcContext dc, int contactId) {
    DcContact contact = dc.getContact(contactId);
    String addr = contact != null ? contact.getAddr() : null;
    int encrypted = encryptedChatForAddress(dc, addr);
    if (encrypted != 0) return encrypted;
    int chatId = dc.createChatByContactId(contactId);
    ensureInvite(context, dc, chatId);
    return chatId;
  }

  /** Encrypted 1:1 chat for this address, or 0. Unarchives it when found. */
  public static int encryptedChatForAddress(DcContext dc, String addr) {
    if (dc == null || addr == null) return 0;
    String want = addr.trim();
    if (want.isEmpty()) return 0;
    java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
    try {
      int[] named = dc.getContacts(0, want);
      if (named != null) for (int id : named) ids.add(id);
      int[] addressed = dc.getContacts(DcContext.DC_GCL_ADDRESS, want);
      if (addressed != null) for (int id : addressed) ids.add(id);
      int lookedUp = dc.lookupContactIdByAddr(want);
      if (lookedUp > 0) ids.add(lookedUp);
    } catch (Throwable t) {
      Log.w(TAG, "getContacts failed", t);
      return 0;
    }
    for (int id : ids) {
      if (id <= DcChat.DC_CHAT_ID_LAST_SPECIAL) continue;
      DcContact contact;
      try {
        contact = dc.getContact(id);
      } catch (Throwable t) {
        continue;
      }
      if (contact == null || contact.getAddr() == null) continue;
      if (!want.equalsIgnoreCase(contact.getAddr().trim())) continue;
      int chatId = dc.getChatIdByContactId(id);
      if (chatId == 0) continue;
      DcChat chat = dc.getChat(chatId);
      if (chat == null || chat.getType() != DcChat.DC_CHAT_TYPE_SINGLE) continue;
      if (!chat.isEncrypted()) continue;
      if (chat.getVisibility() == DcChat.DC_CHAT_VISIBILITY_ARCHIVED) {
        dc.setChatVisibility(chatId, DcChat.DC_CHAT_VISIBILITY_NORMAL);
      }
      return chatId;
    }
    return 0;
  }

  /**
   * When {@code chatId} is an unencrypted 1:1 and an encrypted chat exists
   * for the same address, return the encrypted id. Otherwise return
   * {@code chatId}.
   */
  public static int preferEncrypted(DcContext dc, int chatId) {
    if (!isPlainSingle(dc, chatId)) return chatId;
    String addr = peerAddress(dc, chatId);
    int encrypted = encryptedChatForAddress(dc, addr);
    return encrypted != 0 ? encrypted : chatId;
  }

  public static boolean isPlainSingle(DcContext dc, int chatId) {
    if (dc == null || chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL) return false;
    DcChat chat = dc.getChat(chatId);
    if (chat == null) return false;
    if (chat.getType() != DcChat.DC_CHAT_TYPE_SINGLE) return false;
    if (chat.isEncrypted() || chat.isSelfTalk() || chat.isDeviceTalk()) return false;
    if (chat.isMailingList() || chat.isMultiUser()) return false;
    return peerAddress(dc, chatId) != null;
  }

  /**
   * @return true when the caller must not send this payload as a plain e-mail.
   *     The invite is sent instead and {@code text} is kept until encryption.
   */
  public static boolean holdUntilEncrypted(
      Context context, DcContext dc, int chatId, String text) {
    if (!isPlainSingle(dc, chatId)) return false;
    if (preferEncrypted(dc, chatId) != chatId) return false;
    ensureInvite(context, dc, chatId);
    if (text != null && !text.trim().isEmpty()) {
      queue(dc, chatId, text);
    }
    return true;
  }

  public static void ensureInvite(Context context, DcContext dc, int chatId) {
    if (context == null || dc == null || !isPlainSingle(dc, chatId)) return;
    String key = "ui.bmchat.invited." + chatId;
    if ("1".equals(dc.getConfig(key))) return;
    String invite = Util.rewriteInviteLink(dc.getSecurejoinQr(0));
    if (invite == null || invite.isEmpty()) return;
    try {
      DcMsg msg = new DcMsg(dc, DcMsg.DC_MSG_TEXT);
      msg.setText(context.getString(R.string.bmchat_invite_email_body, invite));
      int sent = dc.sendMsg(chatId, msg);
      if (sent != 0) dc.setConfig(key, "1");
    } catch (Throwable t) {
      Log.w(TAG, "ensureInvite failed", t);
    }
  }

  /** Send texts that were waiting, once their chat (or its encrypted twin) is protected. */
  public static void flushPending(DcContext dc) {
    if (dc == null) return;
    String raw = dc.getConfig(PENDING_KEY);
    if (raw == null || raw.isEmpty()) return;
    JSONObject pending;
    try {
      pending = new JSONObject(raw);
    } catch (Throwable t) {
      Log.w(TAG, "pending json", t);
      return;
    }
    boolean changed = false;
    Iterator<String> keys = pending.keys();
    java.util.List<String> done = new java.util.ArrayList<>();
    while (keys.hasNext()) {
      String key = keys.next();
      int chatId;
      try {
        chatId = Integer.parseInt(key);
      } catch (NumberFormatException e) {
        done.add(key);
        changed = true;
        continue;
      }
      int target = preferEncrypted(dc, chatId);
      DcChat chat = dc.getChat(target);
      if (chat == null || !chat.isEncrypted()) continue;
      String text = pending.optString(key, "");
      if (!text.isEmpty()) {
        try {
          DcMsg msg = new DcMsg(dc, DcMsg.DC_MSG_TEXT);
          msg.setText(text);
          dc.sendMsg(target, msg);
          dc.setDraft(chatId, null);
          if (target != chatId) dc.setDraft(target, null);
        } catch (Throwable t) {
          Log.w(TAG, "flush send failed", t);
          continue;
        }
      }
      done.add(key);
      changed = true;
    }
    if (!changed) return;
    for (String key : done) pending.remove(key);
    dc.setConfig(PENDING_KEY, pending.length() == 0 ? "" : pending.toString());
  }

  private static void queue(DcContext dc, int chatId, String text) {
    JSONObject pending;
    try {
      String raw = dc.getConfig(PENDING_KEY);
      pending = raw == null || raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    } catch (Throwable t) {
      pending = new JSONObject();
    }
    try {
      pending.put(Integer.toString(chatId), text);
      dc.setConfig(PENDING_KEY, pending.toString());
    } catch (Throwable t) {
      Log.w(TAG, "queue failed", t);
    }
  }

  private static String peerAddress(DcContext dc, int chatId) {
    int[] contacts = dc.getChatContacts(chatId);
    if (contacts == null || contacts.length != 1) return null;
    if (contacts[0] <= DcChat.DC_CHAT_ID_LAST_SPECIAL) return null;
    try {
      DcContact contact = dc.getContact(contacts[0]);
      if (contact == null || contact.getAddr() == null) return null;
      String addr = contact.getAddr().trim();
      return addr.isEmpty() ? null : addr.toLowerCase(Locale.ROOT);
    } catch (Throwable t) {
      return null;
    }
  }
}
