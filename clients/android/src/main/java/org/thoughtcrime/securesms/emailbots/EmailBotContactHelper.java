package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.connect.DcHelper;

import java.util.Locale;

/**
 * Ensures each email bot has a pseudo-contact and 1:1 home chat
 * ({@code emailbot.name@bots.bmchat.local}), matching desktop behaviour.
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

  @WorkerThread
  public static void ensureBotContact(@NonNull Context context,
                                      @NonNull EmailBotStore store,
                                      @NonNull EmailBotConfig bot) {
    DcContext dc = DcHelper.getAccounts(context).getAccount(bot.ownerAccountId);
    if (dc == null || !dc.isOk()) return;

    String email = makeBotEmail(bot.name);
    String displayName = bot.displayName != null && !bot.displayName.isEmpty()
        ? bot.displayName : "@" + bot.name;

    int contactId = bot.botContactId;
    if (contactId <= 0) {
      contactId = dc.lookupContactIdByAddr(email);
    }
    if (contactId <= 0) {
      contactId = dc.createContact(displayName, email);
    }
    if (contactId <= 0) {
      Log.w(TAG, "createContact failed for " + bot.name);
      return;
    }

    int chatId = bot.botChatId;
    if (chatId <= 0) {
      chatId = dc.getChatIdByContactId(contactId);
    }
    if (chatId <= 0) {
      chatId = dc.createChatByContactId(contactId);
    }

    if (contactId != bot.botContactId || chatId != bot.botChatId) {
      store.patchContactIds(bot.id, contactId, chatId);
    }
  }
}
