package org.thoughtcrime.securesms.emailbots;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email-only transport for BMChat bots. Plays the role HTTP webhooks
 * play in Telegram: every inbound user message destined for a bot is
 * serialised as a Telegram-shaped {@code Update} JSON and shipped to
 * the bot's developer over the owner's existing SMTP credentials. The
 * developer's reply travels back the same way — a plain e-mail tagged
 * with {@code BMCHAT-BOT-REPLY} on the subject line plus a JSON body.
 *
 * <p>This class is intentionally stateless and side-effect-free aside
 * from the {@code DcContext.sendMsg} call; all routing decisions are
 * made by {@link EmailBotDispatcher} so unit tests can exercise the
 * parser in isolation.
 *
 * <h2>Wire format</h2>
 * <p>Delta-chat synthesises the e-mail Subject from the first line of
 * the message body, so we encode all routing info on that line — the
 * receiver can authenticate the message by parsing either Subject
 * (preferred, available before fetching the body) or the body itself.
 *
 * <p>Outgoing (BMChat → developer):
 * <pre>
 *     BMCHAT-BOT-UPDATE v1 @weather_bot chat=42 message=1715812345 from=alice@example.com
 *     ---
 *     { …Update JSON… }
 * </pre>
 *
 * <p>Incoming reply (developer → BMChat):
 * <pre>
 *     BMCHAT-BOT-REPLY v1 @weather_bot chat=42 in_reply_to=1715812345
 *     ---
 *     { "text": "…", "reply_markup": { … } }
 * </pre>
 */
public final class EmailBotMailer {

  private static final String TAG = "EmailBotMailer";

  public static final String MARKER_UPDATE = "BMCHAT-BOT-UPDATE v1";
  public static final String MARKER_REPLY  = "BMCHAT-BOT-REPLY v1";

  /**
   * Parser for the magic header line on a reply. Captures the bot
   * slug (group 1), original chat id (group 2) and message id
   * (group 3). Package-private so tests can drive it directly.
   *
   * <p>Tolerates a leading {@code Re:} or any mail-client subject
   * decoration ({@code [Re]}, {@code FW:} etc.) because both the
   * subject and the body's first line are matched against it.
   */
  static final Pattern REPLY_PATTERN = Pattern.compile(
      "BMCHAT-BOT-REPLY v1 @?([a-zA-Z0-9_]+) chat=(\\d+) in_reply_to=(\\d+)");

  private EmailBotMailer() {}

  /**
   * Encodes an inbound user message as a Telegram-style Update and
   * sends it to the bot's developer as a regular email. The developer
   * sees it in their inbox (or in their own BMChat) and answers it
   * exactly like any other thread, just with the JSON contract.
   *
   * @return {@code true} when the mail was queued successfully.
   */
  public static boolean sendUpdate(@NonNull DcContext dcContext,
                                   @NonNull EmailBotConfig bot,
                                   int originChatId,
                                   int originMsgId,
                                   @NonNull String senderEmail,
                                   @NonNull String body,
                                   @NonNull String command,
                                   @NonNull String argument) {
    if (TextUtils.isEmpty(bot.developerEmail)) {
      Log.w(TAG, "no developerEmail for bot " + bot.name);
      return false;
    }

    int contactId = dcContext.createContact(null, bot.developerEmail);
    if (contactId == 0) {
      Log.w(TAG, "createContact failed for " + bot.developerEmail);
      return false;
    }
    int developerChatId = dcContext.createChatByContactId(contactId);
    if (developerChatId == 0) {
      Log.w(TAG, "createChatByContactId failed for " + bot.developerEmail);
      return false;
    }

    try {
      JSONObject update = new JSONObject();
      update.put("update_id", originMsgId);

      JSONObject message = new JSONObject();
      message.put("message_id", originMsgId);
      message.put("chat", new JSONObject()
          .put("id", originChatId)
          .put("type", "private"));
      message.put("from", new JSONObject().put("email", senderEmail));
      message.put("text", body);
      message.put("date", System.currentTimeMillis() / 1000L);
      update.put("message", message);

      JSONObject bmchat = new JSONObject();
      bmchat.put("bot", bot.name);
      bmchat.put("token_suffix", bot.token);
      bmchat.put("command", command);
      bmchat.put("argument", argument);
      // Hint to the developer's autoresponder: this is the address
      // they must send the reply to (i.e. our own self-address as the
      // owner of this bot).
      DcContact self = dcContext.getContact(DcContact.DC_CONTACT_ID_SELF);
      if (self != null) {
        bmchat.put("reply_to", self.getAddr());
      }
      update.put("bmchat", bmchat);

      // Delta-chat derives the Subject from the first line of the
      // message body, so we put the full routing header there and
      // separate the JSON payload with a `---` fence.
      StringBuilder bodyText = new StringBuilder(512);
      bodyText.append(MARKER_UPDATE)
              .append(" @").append(bot.name)
              .append(" chat=").append(originChatId)
              .append(" message=").append(originMsgId)
              .append(" from=").append(senderEmail).append('\n');
      bodyText.append("---\n");
      bodyText.append(update.toString(2));

      DcMsg out = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      out.setText(bodyText.toString());
      dcContext.sendMsg(developerChatId, out);
      return true;
    } catch (Throwable t) {
      Log.w(TAG, "sendUpdate failed", t);
      return false;
    }
  }

  /**
   * Inspects an incoming message and, if it looks like a bot
   * developer answering a previously dispatched update, returns the
   * routing payload — otherwise {@code null}. The marker is searched
   * in both the Subject and the first line of the body, so the
   * protocol survives mail clients that line-wrap or "Re:"-prefix
   * the subject.
   */
  @Nullable
  public static ReplyEnvelope tryParseReply(@NonNull DcMsg msg) {
    String subject = safeSubject(msg);
    ReplyEnvelope env = matchReply(subject);
    if (env != null) return env;
    String body = msg.getText();
    if (body == null) return null;
    int newline = body.indexOf('\n');
    String firstLine = newline > 0 ? body.substring(0, newline) : body;
    return matchReply(firstLine);
  }

  @Nullable
  private static ReplyEnvelope matchReply(@Nullable String line) {
    if (line == null || line.isEmpty()) return null;
    Matcher m = REPLY_PATTERN.matcher(line);
    if (!m.find()) return null;
    try {
      return new ReplyEnvelope(
          m.group(1),
          Integer.parseInt(m.group(2)),
          Integer.parseInt(m.group(3)));
    } catch (NumberFormatException nfe) {
      return null;
    }
  }

  /**
   * Extracts the JSON payload from a reply body. Tolerates whatever
   * leading lines the developer included before {@code ---} — that
   * separator is the canonical "JSON starts here" marker.
   */
  @Nullable
  public static JSONObject parseReplyBody(@NonNull String body) {
    int sep = body.indexOf("\n---\n");
    String json;
    if (sep >= 0) {
      json = body.substring(sep + 5).trim();
    } else {
      // Some mail clients normalise CRLF; try once more.
      sep = body.indexOf("\r\n---\r\n");
      if (sep >= 0) {
        json = body.substring(sep + 7).trim();
      } else if (body.trim().startsWith("{")) {
        json = body.trim();
      } else {
        return null;
      }
    }
    if (json.isEmpty() || json.charAt(0) != '{') return null;
    try {
      return new JSONObject(json);
    } catch (Throwable t) {
      Log.w(TAG, "parseReplyBody failed", t);
      return null;
    }
  }

  /**
   * Flattens a Telegram-style {@code inline_keyboard} into Markdown
   * links targeting BMChat's custom {@code bmchat-bot://cb/…} URI
   * scheme so taps loop back through
   * {@link org.thoughtcrime.securesms.emailbots.ui.EmailBotCallbackActivity}.
   */
  @Nullable
  public static String renderInlineKeyboard(@NonNull String botSlug,
                                            @Nullable JSONArray rows) {
    if (rows == null || rows.length() == 0) return null;
    StringBuilder out = new StringBuilder();
    for (int r = 0; r < rows.length(); r++) {
      JSONArray row = rows.optJSONArray(r);
      if (row == null) continue;
      if (r > 0) out.append('\n');
      for (int c = 0; c < row.length(); c++) {
        JSONObject btn = row.optJSONObject(c);
        if (btn == null) continue;
        String label = btn.optString("text", "").trim();
        if (label.isEmpty()) continue;
        String url = btn.optString("url", null);
        String callbackData = btn.optString("callback_data", null);
        String target;
        if (url != null && !url.isEmpty()) {
          target = url;
        } else if (callbackData != null && !callbackData.isEmpty()) {
          target = "bmchat-bot://cb/" + android.net.Uri.encode(botSlug)
              + "/" + android.net.Uri.encode(callbackData);
        } else {
          continue;
        }
        if (c > 0) out.append('\n');
        out.append("\uD83D\uDD18 [").append(label).append("](").append(target).append(")");
      }
    }
    return out.length() == 0 ? null : out.toString();
  }

  @Nullable
  private static String safeSubject(@NonNull DcMsg msg) {
    try {
      String s = msg.getSubject();
      if (!TextUtils.isEmpty(s)) return s;
    } catch (Throwable ignored) {}
    return null;
  }

  /** Marker container produced by {@link #tryParseReply}. */
  public static final class ReplyEnvelope {
    @NonNull public final String botSlug;
    public final int originChatId;
    public final int originMsgId;
    ReplyEnvelope(@NonNull String botSlug, int originChatId, int originMsgId) {
      this.botSlug = botSlug;
      this.originChatId = originChatId;
      this.originMsgId = originMsgId;
    }
  }
}
