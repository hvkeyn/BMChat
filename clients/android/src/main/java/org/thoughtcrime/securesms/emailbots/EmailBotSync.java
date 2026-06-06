package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.bots.TelegramBotSync;

import java.util.Locale;

/**
 * Multidevice sync for email-bot configs via the encrypted self-chat.
 *
 * <p>Ui-config alone is local-only in the core; this mirrors the encrypted
 * payload to {@code DC_CONTACT_ID_SELF} so other devices receive it as a normal
 * E2E-protected self message (BccSelf / Autocrypt), like other account data.
 */
public final class EmailBotSync {

  private static final String TAG = "EmailBotSync";
  public static final String MARKER = "BMCHAT-BOT-SYNC v1";
  private static final long MIN_PUBLISH_INTERVAL_MS = 8_000L;
  private static long lastPublishMs = 0L;

  private EmailBotSync() {}

  public static void publishIfNeeded(@NonNull Context context, int accountId,
                                     @NonNull String plainJson) {
    long now = System.currentTimeMillis();
    if (now - lastPublishMs < MIN_PUBLISH_INTERVAL_MS) return;
    lastPublishMs = now;
    publishNow(context, accountId, plainJson);
  }

  /** Always sends sync (e.g. after user saves a bot). */
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

  /**
   * Scans recent self-chat messages for sync payloads (multidevice catch-up).
   */
  public static void pullFromSelfChat(@NonNull Context context, int accountId,
                                      @NonNull EmailBotStore emailStore,
                                      @NonNull org.thoughtcrime.securesms.bots.BotStore tgStore) {
    try {
      DcContext dc = DcHelper.getAccounts(context).getAccount(accountId);
      if (dc == null || !dc.isOk()) return;
      int selfChat = dc.getChatIdByContactId(DcContact.DC_CONTACT_ID_SELF);
      if (selfChat <= 0) return;
      int[] ids = dc.getChatMsgs(selfChat, 0, 0);
      if (ids == null || ids.length == 0) return;
      int start = Math.max(0, ids.length - 80);
      for (int i = ids.length - 1; i >= start; i--) {
        DcMsg msg = dc.getMsg(ids[i]);
        if (msg == null) continue;
        if (tryIngest(context, accountId, msg, emailStore)) continue;
        TelegramBotSync.tryIngest(context, accountId, msg, tgStore);
      }
    } catch (Throwable t) {
      Log.w(TAG, "pullFromSelfChat failed", t);
    }
  }

  /**
   * @return {@code true} if the message was consumed as a bot-config sync.
   */
  public static boolean tryIngest(@NonNull Context context, int accountId,
                                  @NonNull DcMsg msg, @NonNull EmailBotStore store) {
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
