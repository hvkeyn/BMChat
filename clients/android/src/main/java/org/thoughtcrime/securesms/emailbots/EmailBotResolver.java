package org.thoughtcrime.securesms.emailbots;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

/**
 * Resolves {@link EmailBotConfig} by bot name, 1:1 chat id, or pseudo-contact
 * {@code emailbot.<slug>@bots.bmchat.local} when {@code botChatId} was not yet linked.
 */
public final class EmailBotResolver {

  private EmailBotResolver() {}

  @Nullable
  public static EmailBotConfig resolve(@NonNull EmailBotStore store,
                                       @NonNull DcContext dc,
                                       int accountId,
                                       @Nullable String botNameHint,
                                       int chatIdHint) {
    String hint = normalizeName(botNameHint);
    if (!hint.isEmpty()) {
      EmailBotConfig byName = store.findByName(accountId, hint);
      if (byName != null) {
        return linkHomeChatIfNeeded(store, dc, byName, chatIdHint);
      }
    }

    if (chatIdHint > 0) {
      EmailBotConfig byChat = store.findByChatId(accountId, chatIdHint);
      if (byChat != null) return byChat;

      String slug = EmailBotContactHelper.slugFromBotHomeChat(dc, chatIdHint);
      if (!slug.isEmpty()) {
        EmailBotConfig bySlug = store.findByName(accountId, slug);
        if (bySlug != null) {
          return linkHomeChatIfNeeded(store, dc, bySlug, chatIdHint);
        }
        if (hint.isEmpty()) hint = slug;
      }
    }

    if (!hint.isEmpty()) {
      EmailBotConfig byName = store.findByName(accountId, hint);
      if (byName != null) return byName;
      for (EmailBotConfig b : store.getAll()) {
        if (b.enabled && b.name.equalsIgnoreCase(hint)) return b;
      }
    }
    return null;
  }

  @NonNull
  public static String normalizeName(@Nullable String raw) {
    if (raw == null || raw.isEmpty()) return "";
    String s = raw.trim();
    if (s.startsWith("@")) s = s.substring(1);
    try {
      s = Uri.decode(s);
    } catch (Throwable ignored) {}
    return s.trim();
  }

  @Nullable
  private static EmailBotConfig linkHomeChatIfNeeded(@NonNull EmailBotStore store,
                                                     @NonNull DcContext dc,
                                                     @NonNull EmailBotConfig bot,
                                                     int openChatId) {
    if (openChatId <= 0 || bot.botChatId == openChatId) return bot;
    String slug = EmailBotContactHelper.slugFromBotHomeChat(dc, openChatId);
    if (slug.isEmpty() || !slug.equalsIgnoreCase(bot.name)) return bot;
    if (bot.botChatId > 0 && bot.botChatId != openChatId) return bot;

    int contactId = bot.botContactId;
    if (contactId <= 0) {
      contactId = dc.lookupContactIdByAddr(EmailBotContactHelper.makeBotEmail(bot.name));
    }
    store.patchContactIds(bot.id, contactId, openChatId);
    EmailBotConfig linked = store.findByName(bot.ownerAccountId, bot.name);
    return linked != null ? linked : bot;
  }
}
