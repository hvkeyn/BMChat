package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotConfig;
import org.thoughtcrime.securesms.emailbots.EmailBotContactHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotStore;

import java.io.File;
import java.util.Locale;

/** Shared display helpers for {@code *bot.*@bots.bmchat.local} pseudo-contacts. */
public final class BotPseudoContactHelper {

  private static final String SUFFIX = "@bots.bmchat.local";

  private BotPseudoContactHelper() {}

  public static boolean isPseudoEmail(@Nullable String addr) {
    return addr != null
        && addr.toLowerCase(Locale.ROOT).trim().endsWith(SUFFIX);
  }

  /** {@code emailbot.newsbot@...} or {@code tgbot.mybot@...} -> {@code newsbot} / {@code mybot}. */
  @NonNull
  public static String slugFromPseudoEmail(@Nullable String addr) {
    if (addr == null || addr.isEmpty()) return "";
    String emailSlug = EmailBotContactHelper.nameFromBotEmail(addr);
    if (!emailSlug.isEmpty()) return emailSlug;
    String lower = addr.toLowerCase(Locale.ROOT).trim();
    if (!lower.endsWith(SUFFIX)) return "";
    int at = lower.indexOf('@');
    if (at <= 0) return "";
    String local = lower.substring(0, at);
    if (!local.startsWith("tgbot.")) return "";
    return local.substring("tgbot.".length());
  }

  /** Subtitle for contact pickers - never show the raw pseudo-email. */
  @Nullable
  public static String pickerSubtitle(@NonNull Context context,
                                      @Nullable DcContact contact) {
    if (contact == null) return null;
    String slug = slugFromPseudoEmail(contact.getAddr());
    if (slug.isEmpty()) return null;
    String desc = resolveDescription(context, contact);
    if (!TextUtils.isEmpty(desc)) {
      return context.getString(R.string.bmchat_bot_profile_username, slug)
          + " - " + desc;
    }
    return context.getString(R.string.bmchat_bot_profile_username, slug);
  }

  @Nullable
  private static String resolveDescription(@NonNull Context context,
                                           @NonNull DcContact contact) {
    int accountId = DcHelper.getContext(context).getAccountId();
    int contactId = contact.getId();
    EmailBotStore emailStore = new EmailBotStore(context);
    EmailBotConfig emailBot = emailStore.findByContactId(accountId, contactId);
    if (emailBot != null && !TextUtils.isEmpty(emailBot.description)) {
      return emailBot.description.trim();
    }
    BotStore tgStore = new BotStore(context);
    BotConfig tgBot = tgStore.findByContactId(accountId, contactId);
    if (tgBot != null) {
      if (!TextUtils.isEmpty(tgBot.description)) return tgBot.description.trim();
      if (!TextUtils.isEmpty(tgBot.shortDescription)) return tgBot.shortDescription.trim();
    }
    return null;
  }

  /** Local avatar file for a bot pseudo-contact, if configured. */
  @Nullable
  public static String resolveAvatarPath(@NonNull Context context,
                                         @Nullable DcContact contact) {
    if (contact == null) return null;
    int accountId = DcHelper.getContext(context).getAccountId();
    int contactId = contact.getId();
    EmailBotStore emailStore = new EmailBotStore(context);
    EmailBotConfig emailBot = emailStore.findByContactId(accountId, contactId);
    if (emailBot != null && isReadableFile(emailBot.avatarPath)) {
      return emailBot.avatarPath;
    }
    BotStore tgStore = new BotStore(context);
    BotConfig tgBot = tgStore.findByContactId(accountId, contactId);
    if (tgBot != null && isReadableFile(tgBot.avatarPath)) {
      return tgBot.avatarPath;
    }
    return null;
  }

  @NonNull
  public static int[] attachedBotContactIds(@NonNull Context context,
                                            int accountId,
                                            int chatId) {
    if (chatId <= 0) return new int[0];
    java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
    DcContext dc = DcHelper.getContext(context);
    EmailBotStore emailStore = new EmailBotStore(context);
    for (EmailBotConfig bot : emailStore.getForAccount(accountId)) {
      if (!bot.enabled || !bot.attachedChatIds.contains(chatId)) continue;
      int cid = bot.botContactId;
      if (cid <= 0) {
        cid = EmailBotContactHelper.ensureSearchableContact(dc, bot);
        if (cid > 0 && cid != bot.botContactId) {
          emailStore.patchContactIds(bot.id, cid, bot.botChatId);
        }
      }
      if (cid > 0) ids.add(cid);
    }
    BotStore tgStore = new BotStore(context);
    for (BotConfig bot : tgStore.getAll()) {
      if (bot.dcAccountId != accountId || !bot.attachedChatIds.contains(chatId)) continue;
      if (bot.botContactId > 0) ids.add(bot.botContactId);
    }
    int[] out = new int[ids.size()];
    int i = 0;
    for (Integer id : ids) out[i++] = id;
    return out;
  }

  private static boolean isReadableFile(@Nullable String path) {
    if (path == null || path.isEmpty()) return false;
    File f = new File(path);
    return f.isFile() && f.canRead();
  }
}