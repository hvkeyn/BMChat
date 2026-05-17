package org.thoughtcrime.securesms.emailbots.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotConfig;
import org.thoughtcrime.securesms.emailbots.EmailBotStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.b44t.messenger.DcContext;

/**
 * Transparent trampoline that handles taps on
 * {@code bmchat-bot://cb/<bot>/<callback_data>} URLs embedded into
 * inline-keyboard messages produced by
 * {@link org.thoughtcrime.securesms.emailbots.EmailBotDispatcher}.
 *
 * <p>The activity does not show any UI — it parses the URI, posts a
 * Telegram-style {@code callback_query} envelope to the bot's webhook,
 * sends the resulting reply into the originating chat and immediately
 * finishes. From the user's point of view it's exactly the same
 * experience as tapping a Telegram inline button: the message updates
 * "in place" (as a follow-up message in our case) with the bot's
 * answer.
 */
public class EmailBotCallbackActivity extends Activity {

  private static final String TAG = "EmailBotCallback";

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Intent intent = getIntent();
    Uri data = intent != null ? intent.getData() : null;
    if (data == null) { finish(); return; }
    handleCallback(data);
    finish();
  }

  private void handleCallback(@NonNull Uri uri) {
    if (!"bmchat-bot".equalsIgnoreCase(uri.getScheme())) return;
    if (!"cb".equalsIgnoreCase(uri.getAuthority())
        && !"cb".equalsIgnoreCase(uri.getHost())) {
      // Future-proofing: only "cb" (callback query) is supported today,
      // skip anything else silently.
      return;
    }
    List<String> seg = uri.getPathSegments();
    String botName = uri.getAuthority();
    String callbackData;
    if ("cb".equalsIgnoreCase(uri.getAuthority())) {
      if (seg.size() < 2) return;
      botName = seg.get(0);
      callbackData = seg.get(1);
    } else {
      // legacy "/cb/<bot>/<data>" shape — keep for forward-compat.
      if (seg.size() < 3) return;
      botName = seg.get(1);
      callbackData = seg.get(2);
    }
    if (TextUtils.isEmpty(botName) || TextUtils.isEmpty(callbackData)) return;

    final String fBotName = botName;
    final String fData = callbackData;
    new Thread(() -> runCallback(fBotName, fData), "emailbot-callback").start();
  }

  private void runCallback(@androidx.annotation.NonNull String botName,
                           @androidx.annotation.NonNull String callbackData) {
    try {
      EmailBotStore store = new EmailBotStore(getApplicationContext());
      DcContext dcContext = DcHelper.getContext(this);
      int accountId = dcContext.getAccountId();

      EmailBotConfig bot = store.findByName(accountId, botName);
      if (bot == null || !bot.enabled) {
        toastOnUi(getString(R.string.bmchat_email_bot_cb_no_bot, botName));
        return;
      }
      if (TextUtils.isEmpty(bot.webhookUrl)) {
        toastOnUi(getString(R.string.bmchat_email_bot_cb_no_webhook));
        return;
      }

      String reply = postCallback(bot, callbackData);
      if (TextUtils.isEmpty(reply)) {
        toastOnUi(getString(R.string.bmchat_email_bot_cb_no_reply));
        return;
      }

      // For now we send the bot's answer to the user's own self-talk
      // (Saved Messages-equivalent) — we don't have a stable per-button
      // chat id yet because Android intent filters strip query params.
      // Users still see the answer in their primary BMChat list.
      int selfChat = dcContext.getChatIdByContactId(com.b44t.messenger.DcContact.DC_CONTACT_ID_SELF);
      if (selfChat <= 0) {
        selfChat = dcContext.createChatByContactId(com.b44t.messenger.DcContact.DC_CONTACT_ID_SELF);
      }
      if (selfChat > 0) {
        dcContext.sendTextMsg(selfChat, "@" + bot.name + ": " + reply);
        store.upsert(bot.withReplySent(System.currentTimeMillis()));
      }
    } catch (Throwable t) {
      Log.w(TAG, "callback failed", t);
    }
  }

  @Nullable
  private String postCallback(@androidx.annotation.NonNull EmailBotConfig bot,
                              @androidx.annotation.NonNull String callbackData) {
    HttpURLConnection conn = null;
    try {
      Uri u = Uri.parse(bot.webhookUrl);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
      if (!"http".equals(scheme) && !"https".equals(scheme)) return null;

      JSONObject update = new JSONObject();
      update.put("update_id", System.currentTimeMillis());

      JSONObject cq = new JSONObject();
      cq.put("id", String.valueOf(System.currentTimeMillis()));
      cq.put("data", callbackData);
      cq.put("from", new JSONObject().put("email", ""));
      update.put("callback_query", cq);

      JSONObject bmchat = new JSONObject();
      bmchat.put("bot", bot.name);
      bmchat.put("token_suffix", bot.token);
      bmchat.put("kind", "callback_query");
      update.put("bmchat", bmchat);

      conn = (HttpURLConnection) new URL(bot.webhookUrl).openConnection();
      conn.setConnectTimeout(8_000);
      conn.setReadTimeout(15_000);
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("User-Agent", "BMChat-EmailBot/2");
      conn.setRequestProperty("X-BMChat-Bot-Token", bot.token);

      byte[] bytes = update.toString().getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) return null;
      StringBuilder sb = new StringBuilder();
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        char[] buf = new char[2048];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
      }
      JSONObject resp = new JSONObject(sb.toString());
      String text = resp.optString("text", null);
      if (text == null || text.isEmpty()) text = resp.optString("reply", null);
      return text;
    } catch (Throwable t) {
      Log.w(TAG, "callback HTTP failed", t);
      return null;
    } finally {
      if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
    }
  }

  private void toastOnUi(final String text) {
    runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
  }
}
