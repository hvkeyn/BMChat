package org.thoughtcrime.securesms.bots;

import android.content.Context;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.emailbots.EmailBotConfig;
import org.thoughtcrime.securesms.emailbots.EmailBotStore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Attach / detach email and Telegram bots to channels and groups. */
public final class AttachedBotHelper {

  private AttachedBotHelper() {}

  public static boolean isAttachedBotContact(@NonNull Context context,
                                             int accountId,
                                             int chatId,
                                             int contactId) {
    if (chatId <= 0 || contactId <= 0) return false;
    for (int id : BotPseudoContactHelper.attachedBotContactIds(context, accountId, chatId)) {
      if (id == contactId) return true;
    }
    return false;
  }

  public static void detachFromChat(@NonNull Context context,
                                    int accountId,
                                    int chatId,
                                    @NonNull Collection<Integer> contactIds) {
    if (chatId <= 0 || contactIds.isEmpty()) return;
    EmailBotStore emailStore = new EmailBotStore(context);
    BotStore tgStore = new BotStore(context);
    for (Integer contactId : contactIds) {
      if (contactId == null || contactId <= 0) continue;
      EmailBotConfig emailBot = emailStore.findByContactId(accountId, contactId);
      if (emailBot != null && emailBot.attachedChatIds.contains(chatId)) {
        emailStore.upsert(emailBot.withoutAttachedChat(chatId));
        continue;
      }
      BotConfig tgBot = tgStore.findByContactId(accountId, contactId);
      if (tgBot != null && tgBot.attachedChatIds.contains(chatId)) {
        tgStore.upsert(tgBot.withoutAttachedChat(chatId));
      }
    }
  }

  @NonNull
  public static Set<Integer> attachedContactIdSet(@NonNull Context context,
                                                  int accountId,
                                                  int chatId) {
    Set<Integer> out = new HashSet<>();
    for (int id : BotPseudoContactHelper.attachedBotContactIds(context, accountId, chatId)) {
      out.add(id);
    }
    return out;
  }
}