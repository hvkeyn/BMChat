package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotCrypto;

/**
 * Multidevice sync for Telegram-bot configs via the encrypted self-chat.
 */
public final class TelegramBotSync {

  private static final String TAG = "TelegramBotSync";
  public static final String MARKER = "BMCHAT-TG-BOT-SYNC v1";
  private static final long MIN_PUBLISH_INTERVAL_MS = 8_000L;
  private static long lastPublishMs = 0L;

  private TelegramBotSync() {}

  public static void publishIfNeeded(@NonNull Context context, int accountId,
                                     @NonNull String plainJson) {
    long now = System.currentTimeMillis();
    if (now - lastPublishMs < MIN_PUBLISH_INTERVAL_MS) return;
    lastPublishMs = now;
    publishNow(context, accountId, plainJson);
  }

  public static void publishNow(@NonNull Context context, int accountId,
                                @NonNull String plainJson) {
    try {
      DcContext dc = DcHelper.getAccounts(context).getAccount(accountId);
      if (dc == null || !dc.isOk()) return;
      int selfChat = dc.getChatIdByContactId(DcContact.DC_CONTACT_ID_SELF);
      if (selfChat <= 0) {
        selfChat = dc.createChatByContactId(DcContact.DC_CONTACT_ID_SELF);
      }
      if (selfChat <= 0) return;
      String sealed = EmailBotCrypto.sealJson(context, accountId, plainJson);
      String body = MARKER + " account=" + accountId + "\n" + sealed;
      dc.sendTextMsg(selfChat, body);
    } catch (Throwable t) {
      Log.w(TAG, "publishNow failed", t);
    }
  }

  public static boolean tryIngest(@NonNull Context context, int accountId,
                                  @NonNull DcMsg msg, @NonNull BotStore store) {
    String body = msg.getText();
    if (body == null || body.isEmpty()) return false;
    String first = body.split("\\r?\\n", 2)[0].trim();
    if (!first.startsWith(MARKER)) return false;
    String payload = body.contains("\n") ? body.substring(body.indexOf('\n') + 1).trim() : "";
    if (payload.isEmpty()) return true;
    try {
      String json = EmailBotCrypto.openJson(context, accountId, payload);
      if (json == null || json.isEmpty()) return true;
      JSONObject root = new JSONObject(json);
      JSONArray arr = root.optJSONArray("bots");
      if (arr == null) return true;
      store.mergeFromSyncJson(accountId, arr);
      return true;
    } catch (Throwable t) {
      Log.w(TAG, "tryIngest failed", t);
      return true;
    }
  }
}
