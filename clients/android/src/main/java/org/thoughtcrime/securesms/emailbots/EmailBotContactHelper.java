package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.connect.DcHelper;

import java.util.Locale;

/**
 * Ensures each email bot has a <em>local</em> home chat that never leaves
 * the device over SMTP.
 *
 * <p>Historically the home chat was a 1:1 conversation with a
 * non-deliverable {@code emailbot.name@bots.bmchat.local} pseudo-contact.
 * That turned out to be harmful: every command the user typed (and every
 * bot reply) was queued to the owner's real SMTP server addressed to the
 * fake {@code .local} domain, which providers such as Yandex reject with
 * {@code 5.7.1 Message rejected under suspicion of SPAM}.
 *
 * <p>The home chat is therefore a self-only <strong>outgoing broadcast</strong>
 * (a "channel" with no external recipients). Posting into it never produces
 * a message addressed to {@code @bots.bmchat.local}; at most it is mirrored
 * to the owner's own mailbox via {@code bcc_self} (which is normal mail to
 * oneself, never flagged as spam). The actual bot logic still travels over
 * the developer-mailbox transport / HTTP webhook.
 */
public final class EmailBotContactHelper {

  private static final String TAG = "EmailBotContactHelper";

  private EmailBotContactHelper() {}

  @NonNull
  public static String makeBotEmail(@NonNull String name) {
    String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
    if (slug.isEmpty()) slug = "bot";
    return "emailbot." + slug + "@bots.bmchat.local";
  }

  /** {@code emailbot.newsbot@bots.bmchat.local} → {@code newsbot}. */
  @NonNull
  public static String nameFromBotEmail(@Nullable String email) {
    if (email == null || email.isEmpty()) return "";
    String lower = email.toLowerCase(Locale.ROOT).trim();
    if (!lower.endsWith("@bots.bmchat.local")) return "";
    int at = lower.indexOf('@');
    if (at <= 0) return "";
    String local = lower.substring(0, at);
    if (!local.startsWith("emailbot.")) return "";
    return local.substring("emailbot.".length());
  }

  /** Bot slug for a 1:1 home chat, or empty if this chat is not an email-bot contact. */
  @NonNull
  public static String slugFromBotHomeChat(@NonNull DcContext dc, int chatId) {
    if (chatId <= 0) return "";
    int[] contacts = dc.getChatContacts(chatId);
    if (contacts == null) return "";
    for (int contactId : contacts) {
      if (contactId == DcContact.DC_CONTACT_ID_SELF) continue;
      com.b44t.messenger.DcContact c = dc.getContact(contactId);
      if (c == null) continue;
      String slug = nameFromBotEmail(c.getAddr());
      if (!slug.isEmpty()) return slug;
    }
    return "";
  }

  /** Returns {@code true} when {@code chatId} is a local bot home chat,
   * i.e. a self-only outgoing broadcast that never sends over SMTP. */
  public static boolean isLocalBotChat(@NonNull DcContext dc, int chatId) {
    if (chatId <= 0) return false;
    try {
      DcChat chat = dc.getChat(chatId);
      return chat != null && chat.getType() == DcChat.DC_CHAT_TYPE_OUT_BROADCAST;
    } catch (Throwable t) {
      return false;
    }
  }

  /**
   * Finds an email bot whose local home broadcast matches {@code chatId},
   * including when {@code botChatId} from sync belongs to another device.
   */
  @Nullable
  public static EmailBotConfig findBotForHomeChat(@NonNull DcContext dc,
                                                  @NonNull EmailBotStore store,
                                                  int accountId,
                                                  int chatId) {
    if (chatId <= 0) return null;
    DcChat chat = dc.getChat(chatId);
    if (chat == null || !chat.isOutBroadcast()) return null;

    for (EmailBotConfig b : store.getForAccount(accountId)) {
      if (!b.enabled) continue;
      if (b.botChatId == chatId) return b;
    }

    String chatName = chat.getName();
    if (chatName == null) chatName = "";
    EmailBotConfig labelMatch = null;
    java.util.List<EmailBotConfig> enabled = new java.util.ArrayList<>();
    for (EmailBotConfig b : store.getForAccount(accountId)) {
      if (!b.enabled) continue;
      enabled.add(b);
      if (matchesBotLabel(b, chatName)) labelMatch = b;
    }
    if (labelMatch != null) return labelMatch;
    // Self-only OutBroadcast has no pseudo-contact members — if the account
    // owns exactly one bot, this channel must be its home chat.
    if (enabled.size() == 1) return enabled.get(0);
    return null;
  }

  public static boolean matchesBotLabel(@NonNull EmailBotConfig bot,
                                        @NonNull String label) {
    String n = label.trim();
    if (n.isEmpty()) return false;
    String lower = n.toLowerCase(Locale.ROOT);
    String dn = bot.displayName != null ? bot.displayName.trim() : "";
    if (!dn.isEmpty() && dn.equalsIgnoreCase(n)) return true;
    if (("@".concat(bot.name)).equalsIgnoreCase(n)) return true;
    if (bot.name.equalsIgnoreCase(n)) return true;
    if (lower.contains("@" + bot.name.toLowerCase(Locale.ROOT))) return true;
    if (lower.contains(bot.name.toLowerCase(Locale.ROOT))) return true;
    return false;
  }

  /** Ensures a searchable pseudo-contact exists for {@code @botname}. */
  @WorkerThread
  public static int ensureSearchableContact(@NonNull DcContext dc,
                                            @NonNull EmailBotConfig bot) {
    String email = makeBotEmail(bot.name);
    int contactId = dc.lookupContactIdByAddr(email);
    if (contactId <= 0) {
      String displayName = bot.displayName != null && !bot.displayName.isEmpty()
          ? bot.displayName : "@" + bot.name;
      contactId = dc.createContact(displayName, email);
    }
    return contactId;
  }

  @WorkerThread
  public static void ensureBotContact(@NonNull Context context,
                                      @NonNull EmailBotStore store,
                                      @NonNull EmailBotConfig bot) {
    DcContext dc = DcHelper.getAccounts(context).getAccount(bot.ownerAccountId);
    if (dc == null || !dc.isOk()) return;

    String email = makeBotEmail(bot.name);
    String displayName = bot.displayName != null && !bot.displayName.isEmpty()
        ? bot.displayName : "@" + bot.name;

    // The pseudo-contact is kept only so the bot can still be added to real
    // groups/broadcasts as a labelled member; it is NEVER used as the home
    // chat recipient anymore (that is what produced the SMTP spam bounce).
    int contactId = bot.botContactId;
    if (contactId <= 0) {
      contactId = dc.lookupContactIdByAddr(email);
    }
    if (contactId <= 0) {
      contactId = dc.createContact(displayName, email);
    }

    // Resolve / migrate to a local self-only broadcast home chat.
    int chatId = bot.botChatId;
    boolean haveLocal = false;
    if (chatId > 0) {
      try {
        DcChat chat = dc.getChat(chatId);
        if (chat != null && chat.getType() == DcChat.DC_CHAT_TYPE_OUT_BROADCAST) {
          haveLocal = true;
        } else if (chat != null) {
          // Old-style deliverable 1:1 (or any non-broadcast) chat → migrate
          // to a local broadcast and drop the old chat so the user can no
          // longer type into a conversation that bounces over SMTP.
          int migrated = createLocalBotChat(dc, displayName, bot.avatarPath);
          if (migrated > 0) {
            try { dc.deleteChat(chatId); } catch (Throwable ignored) {}
            chatId = migrated;
            haveLocal = true;
          }
        }
      } catch (Throwable t) {
        Log.w(TAG, "home-chat inspection failed for " + bot.name, t);
      }
    }
    if (!haveLocal) {
      int existing = findExistingHomeChatId(dc, bot);
      if (existing > 0) {
        chatId = existing;
        haveLocal = true;
      }
    }
    if (!haveLocal) {
      int created = createLocalBotChat(dc, displayName, bot.avatarPath);
      if (created > 0) {
        chatId = created;
        haveLocal = true;
      }
    }
    if (!haveLocal || chatId <= 0) {
      Log.w(TAG, "could not ensure local home chat for " + bot.name);
      return;
    }

    if (contactId != bot.botContactId || chatId != bot.botChatId) {
      store.patchContactIds(bot.id, contactId, chatId);
    }
    ensureSearchableContact(dc, bot);
  }

  /**
   * Reuses an existing OutBroadcast home chat instead of creating another
   * empty "Channel" on every sync pull.
   */
  public static int findExistingHomeChatId(@NonNull DcContext dc,
                                           @NonNull EmailBotConfig bot) {
    if (bot.botChatId > 0) {
      try {
        DcChat chat = dc.getChat(bot.botChatId);
        if (chat != null && chat.isOutBroadcast()) return bot.botChatId;
      } catch (Throwable ignored) {}
    }
    int best = 0;
    try {
      com.b44t.messenger.DcChatlist list = dc.getChatlist(0, null, 0);
      for (int i = 0; i < list.getCnt(); i++) {
        int cid = list.getChatId(i);
        DcChat chat = dc.getChat(cid);
        if (chat == null || !chat.isOutBroadcast()) continue;
        String chatName = chat.getName();
        if (chatName == null) chatName = "";
        if (!matchesBotLabel(bot, chatName) && !isOrphanDefaultChannel(bot, chatName)) {
          continue;
        }
        if (best == 0 || cid < best) best = cid;
      }
    } catch (Throwable t) {
      Log.w(TAG, "findExistingHomeChatId scan failed for " + bot.name, t);
    }
    return best;
  }

  /** Desktop/Android default broadcast title before rename. */
  private static boolean isOrphanDefaultChannel(@NonNull EmailBotConfig bot,
                                                @NonNull String chatName) {
    String n = chatName.trim();
    if (n.isEmpty()) return true;
    if ("channel".equalsIgnoreCase(n)) return true;
    return false;
  }

  /** Deletes duplicate empty bot home broadcasts, keeps one per bot. */
  @WorkerThread
  public static void pruneDuplicateHomeChats(@NonNull DcContext dc,
                                             @NonNull EmailBotStore store,
                                             int accountId) {
    for (EmailBotConfig bot : store.getForAccount(accountId)) {
      if (!bot.enabled) continue;
      int canonical = bot.botChatId;
      if (canonical <= 0) {
        canonical = findExistingHomeChatId(dc, bot);
        if (canonical > 0) {
          store.patchContactIds(bot.id, bot.botContactId, canonical);
        }
      }
      if (canonical <= 0) continue;
      try {
        com.b44t.messenger.DcChatlist list = dc.getChatlist(0, null, 0);
        for (int i = 0; i < list.getCnt(); i++) {
          int cid = list.getChatId(i);
          if (cid == canonical) continue;
          DcChat chat = dc.getChat(cid);
          if (chat == null || !chat.isOutBroadcast()) continue;
          String chatName = chat.getName();
          if (chatName == null) chatName = "";
          if (!matchesBotLabel(bot, chatName) && !isOrphanDefaultChannel(bot, chatName)) {
            continue;
          }
          try {
            dc.deleteChat(cid);
            Log.i(TAG, "pruned duplicate home chat " + cid + " for @" + bot.name);
          } catch (Throwable t) {
            Log.w(TAG, "prune delete failed chat=" + cid, t);
          }
        }
      } catch (Throwable t) {
        Log.w(TAG, "prune scan failed for @" + bot.name, t);
      }
    }
  }

  /**
   * Creates a self-only outgoing broadcast to host a bot's conversation
   * locally. No contacts are added, so the core never has an external
   * recipient to send to.
   */
  @WorkerThread
  private static int createLocalBotChat(@NonNull DcContext dc,
                                        @NonNull String name,
                                        @Nullable String avatarPath) {
    try {
      int chatId = dc.createBroadcastList();
      if (chatId <= 0) return 0;
      try { dc.setChatName(chatId, name); } catch (Throwable ignored) {}
      if (avatarPath != null && !avatarPath.isEmpty()) {
        try { dc.setChatProfileImage(chatId, avatarPath); } catch (Throwable ignored) {}
      }
      return chatId;
    } catch (Throwable t) {
      Log.w(TAG, "createBroadcastList failed", t);
      return 0;
    }
  }
}
