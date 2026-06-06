package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.bots.TelegramBotSync;
import org.thoughtcrime.securesms.util.MessageMarkdown;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Inspects every {@link DcContext#DC_EVENT_INCOMING_MSG} delivered to
 * BMChat and, when the message body carries a command for one of the
 * owner account's registered email bots, sends the configured reply
 * through the same Delta-chat core. The reply is delivered over the
 * owner's existing IMAP/SMTP credentials — there is no separate
 * "bot identity"; the reply simply originates from the owner.
 *
 * <p>Two activation grammars are recognised, mirroring Telegram:
 * <ul>
 *   <li><strong>Slash command</strong>: when the message body starts
 *       with {@code /command [arg…]} <em>and</em> the owner has
 *       exactly one enabled bot for the account, the command name is
 *       looked up in {@link EmailBotConfig#commands}.</li>
 *   <li><strong>Mention</strong>: {@code @botname /command [arg…]}
 *       (or {@code @botname text}) selects a specific bot by name,
 *       useful when the user runs several bots simultaneously.</li>
 * </ul>
 *
 * <p>When the bot's {@code webhookUrl} is non-null the dispatcher
 * additionally POSTs a JSON payload describing the inbound message to
 * that URL and (if it responds with HTTP 200 + {@code {"reply":…}})
 * sends the returned text in place of (or in addition to) the static
 * template reply.
 *
 * <p>All work happens on a small background pool; the event-loop
 * thread is never blocked.
 */
public final class EmailBotDispatcher {

  private static final String TAG = "EmailBotDispatcher";
  private static final String BOT_OUT_MARKER = "\u2060";

  /** Don't block the JNI event-loop with network calls or SMTP sends. */
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "emailbot-dispatcher");
        t.setDaemon(true);
        return t;
      });

  private final Context appContext;
  private final EmailBotStore store;
  private final EmailBotDirectory directory;

  public EmailBotDispatcher(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
    this.store = new EmailBotStore(appContext);
    this.directory = new EmailBotDirectory(appContext);
  }

  /**
   * Public entry point — called from
   * {@code DcEventCenter} when {@code DC_EVENT_INCOMING_MSG} fires.
   * The msgId belongs to {@code accountId}; both come straight from
   * the event payload (data1/data2).
   */
  public void onIncomingMessage(int accountId, int chatId, int msgId) {
    if (accountId <= 0 || chatId <= 0 || msgId <= 0) return;
    io.execute(() -> {
      try {
        handle(accountId, chatId, msgId);
      } catch (Throwable t) {
        Log.w(TAG, "handle failed", t);
      }
    });
  }

  @WorkerThread
  private void handle(int accountId, int chatId, int msgId) {
    DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
    if (dcContext == null) return;

    DcMsg msg = dcContext.getMsg(msgId);
    if (msg == null) return;

    // Catalog ingest must run even when this account has no local bots yet.
    if (EmailBotSync.tryIngest(appContext, accountId, msg, store)) return;

    if (TelegramBotSync.tryIngest(
        appContext, accountId, msg, new org.thoughtcrime.securesms.bots.BotStore(appContext))) {
      return;
    }

    if (directory.tryIngest(accountId, msg)) return;

    DcChat chat = dcContext.getChat(chatId);
    if (chat == null) return;

    EmailBotConfig activeBot = resolveActiveBotForChat(dcContext, accountId, chatId);
    String botSlugInChat = EmailBotContactHelper.slugFromBotHomeChat(dcContext, chatId);
    if (store.getAll().isEmpty() && activeBot == null && botSlugInChat.isEmpty()) return;

    boolean isSelf = msg.getFromId() == DcContact.DC_CONTACT_ID_SELF;
    boolean inHomeChat = activeBot != null
        && (activeBot.botChatId == chatId
            || EmailBotContactHelper.slugFromBotHomeChat(dcContext, chatId)
                .equalsIgnoreCase(activeBot.name));

    DcContact senderContact = dcContext.getContact(msg.getFromId());
    String senderEmail = senderContact != null
        ? senderContact.getAddr().toLowerCase(Locale.ROOT)
        : "";
    if (isSelf) {
      try {
        DcContact self = dcContext.getContact(DcContact.DC_CONTACT_ID_SELF);
        if (self != null) senderEmail = self.getAddr().toLowerCase(Locale.ROOT);
      } catch (Throwable ignored) {}
    }

    // 1) If this looks like a reply from a registered bot's developer,
    //    short-circuit out of the user-message path and dispatch the
    //    reply back into the original chat.
    if (handleDeveloperReply(dcContext, accountId, msg, senderEmail)) return;

    String body = msg.getText();
    if (TextUtils.isEmpty(body)) return;
    if (isBotEchoMessage(body)) return;

    if (isSelf && inHomeChat) {
      String t = body.trim();
      if (!t.startsWith("/") && !t.startsWith("@")) return;
    }

    // 2) User-side path: parse "@botname /cmd args" or "/cmd" in home chat.
    Invocation inv;
    if (inHomeChat && activeBot != null) {
      inv = parseInvocationInBotChat(body, accountId, activeBot);
    } else {
      inv = parseInvocation(body, accountId);
    }
    if (inv == null) return;

    EmailBotConfig bot = inv.bot;
    if (bot == null || !bot.enabled) return;

    boolean ownerInHomeChat = isSelf && inHomeChat && activeBot != null
        && activeBot.id.equals(bot.id);

    // 3) /start gate. Until the user explicitly opts in the bot stays
    //    silent — mirrors Telegram's "user must press Start" rule.
    if ("start".equals(inv.command)) {
      if (!bot.isSubscribed(senderEmail) && !senderEmail.isEmpty()) {
        EmailBotConfig updated = bot.withSubscribed(senderEmail);
        store.upsert(updated);
        bot = updated;
      }
      String welcome = resolveReply(bot, "start", inv.argument, senderEmail);
      if (TextUtils.isEmpty(welcome)) {
        welcome = defaultWelcome(bot);
      }
      // HTTP webhook and developer-mailbox transport apply to /start too
      // (PHP/Telegram-style bots expect the webhook on first contact).
      boolean forwarded = false;
      if (!TextUtils.isEmpty(bot.developerEmail)) {
        forwarded = EmailBotMailer.sendUpdate(
            dcContext, bot, chatId, msgId, senderEmail, body,
            inv.command, inv.argument);
      }
      if (!TextUtils.isEmpty(bot.webhookUrl)) {
        String webhookReply = callWebhook(bot, inv, senderEmail, body, chatId, msgId);
        if (!TextUtils.isEmpty(webhookReply)) {
          welcome = webhookReply;
        }
      }
      if (!TextUtils.isEmpty(welcome)) {
        sendBotReply(dcContext, bot, chatId, welcome);
      } else if (!forwarded) {
        Log.w(TAG, "email bot " + bot.name + ": /start produced no reply");
      }
      return;
    }

    if (!ownerInHomeChat && !senderEmail.isEmpty() && !bot.isSubscribed(senderEmail)) {
      EmailBotConfig updated = bot.withSubscribed(senderEmail);
      store.upsert(updated);
      bot = updated;
    }

    // 4) Email-transport bot (the new flavour): forward the update to
    //    the developer over the owner's own SMTP credentials. The
    //    static reply table is still consulted as a fallback so a
    //    purely "stateless" bot keeps working even before its mailbox
    //    is up.
    boolean forwarded = false;
    if (!TextUtils.isEmpty(bot.developerEmail)) {
      forwarded = EmailBotMailer.sendUpdate(
          dcContext, bot, chatId, msgId, senderEmail, body,
          inv.command, inv.argument);
    }

    String reply = resolveReply(bot, inv.command, inv.argument, senderEmail);

    // 5) Legacy HTTP webhook augmentation (BMChat 2.49.47 contract).
    //    Only fires when the developer has explicitly configured a URL.
    if (!TextUtils.isEmpty(bot.webhookUrl)) {
      String webhookReply = callWebhook(bot, inv, senderEmail, body, chatId, msgId);
      if (!TextUtils.isEmpty(webhookReply)) {
        reply = webhookReply;
      }
    }

    if (TextUtils.isEmpty(reply)) {
      if (forwarded) {
        // Quietly succeed: the developer's reply will arrive over
        // email and be routed via handleDeveloperReply.
        return;
      }
      return;
    }
    sendBotReply(dcContext, bot, chatId, reply);
  }

  /** Default welcome banner when the bot has no /start template. */
  @NonNull
  private static String defaultWelcome(@NonNull EmailBotConfig bot) {
    StringBuilder sb = new StringBuilder();
    String label = bot.displayName != null && !bot.displayName.isEmpty()
        ? bot.displayName : bot.name;
    sb.append("Привет! Я @").append(bot.name).append(" — ").append(label).append('.');
    if (bot.description != null && !bot.description.isEmpty()) {
      sb.append("\n\n").append(bot.description);
    }
    if (!bot.commands.isEmpty()) {
      sb.append("\n\nКоманды:");
      for (String cmd : bot.commands.keySet()) {
        sb.append("\n/").append(cmd);
      }
    }
    return sb.toString();
  }

  /** Posts a bot reply and mirrors it into {@link EmailBotConfig#attachedChatIds}. */
  private void sendBotReply(@NonNull DcContext dcContext,
                            @NonNull EmailBotConfig bot,
                            int originChatId,
                            @NonNull String reply) {
    try {
      java.util.LinkedHashSet<Integer> targets = new java.util.LinkedHashSet<>();
      if (originChatId > 0) {
        targets.add(originChatId);
      } else if (bot.botChatId > 0) {
        targets.add(bot.botChatId);
      }
      for (int attachedId : bot.attachedChatIds) {
        if (attachedId > 0) targets.add(attachedId);
      }
      if (targets.isEmpty()) return;
      for (int targetChatId : targets) {
        boolean home = bot.botChatId > 0 && bot.botChatId == targetChatId;
        String visible = home ? reply : "@" + bot.name + ": " + reply;
        dcContext.sendTextMsg(targetChatId, BOT_OUT_MARKER + visible);
      }
      EmailBotConfig updated = bot.withReplySent(System.currentTimeMillis());
      store.upsert(updated);
    } catch (Throwable t) {
      Log.w(TAG, "sendBotReply failed for " + bot.name, t);
    }
  }

  /**
   * Looks for the {@code BMCHAT-BOT-REPLY v1} marker on an incoming
   * message; if found and the sender is the registered developer of
   * the named bot, forwards the JSON payload back into the original
   * chat.
   *
   * @return {@code true} when the message was consumed as a bot reply.
   */
  @WorkerThread
  private boolean handleDeveloperReply(@NonNull DcContext dcContext,
                                       int accountId,
                                       @NonNull DcMsg msg,
                                       @NonNull String senderEmail) {
    EmailBotMailer.ReplyEnvelope env = EmailBotMailer.tryParseReply(msg);
    if (env == null) return false;

    EmailBotConfig bot = store.findByName(accountId, env.botSlug);
    if (bot == null) return false;
    if (TextUtils.isEmpty(bot.developerEmail)) return false;
    if (!bot.developerEmail.equalsIgnoreCase(senderEmail)) {
      Log.w(TAG, "reply for " + env.botSlug
          + " came from " + senderEmail + ", not " + bot.developerEmail);
      return false;
    }
    String body = msg.getText();
    if (body == null) return false;
    JSONObject payload = EmailBotMailer.parseReplyBody(body);
    if (payload == null) return false;

    String text = payload.optString("text", null);
    if (text == null || text.isEmpty()) text = payload.optString("reply", null);
    if (text == null || text.isEmpty()) return true; // marker consumed, nothing to render

    JSONObject markup = payload.optJSONObject("reply_markup");
    if (markup != null) {
      String keyboard = EmailBotMailer.renderInlineKeyboard(
          bot.name, markup.optJSONArray("inline_keyboard"));
      if (keyboard != null && !keyboard.isEmpty()) {
        text = text + "\n\n" + keyboard;
      }
    }

    sendBotReply(dcContext, bot, env.originChatId, text);
    return true;
  }

  /**
   * Parsing helper extracted for unit testing once we add it. Returns
   * {@code null} when the message is not a bot invocation.
   */
  @Nullable
  Invocation parseInvocation(@NonNull String body, int accountId) {
    String trimmed = body.trim();
    if (trimmed.isEmpty()) return null;

    String botName = null;
    String rest = trimmed;

    if (trimmed.startsWith("@")) {
      // "@botname /command arg"  or  "@botname free text"
      int sp = indexOfWhitespace(trimmed);
      if (sp < 0) {
        botName = trimmed.substring(1);
        rest = "";
      } else {
        botName = trimmed.substring(1, sp);
        rest = trimmed.substring(sp + 1).trim();
      }
      if (botName.isEmpty()) return null;
    }

    String command;
    String argument;
    if (rest.startsWith("/")) {
      int sp = indexOfWhitespace(rest);
      if (sp < 0) {
        command = rest.substring(1).toLowerCase(Locale.ROOT);
        argument = "";
      } else {
        command = rest.substring(1, sp).toLowerCase(Locale.ROOT);
        argument = rest.substring(sp + 1).trim();
      }
    } else if (botName != null && !rest.isEmpty()) {
      command = "default";
      argument = rest;
    } else {
      return null;
    }

    EmailBotConfig bot;
    if (botName != null) {
      bot = store.findByName(accountId, botName);
      if (bot == null) bot = store.findByNameGlobal(botName);
    } else {
      // No mention: only fire when the user has exactly one bot,
      // otherwise we have no way of guessing which one they meant.
      java.util.List<EmailBotConfig> bots = store.getForAccount(accountId);
      bot = bots.size() == 1 ? bots.get(0) : null;
    }
    if (bot == null) return null;
    return new Invocation(bot, command, argument);
  }

  @Nullable
  private EmailBotConfig findBotForChat(int accountId, int chatId) {
    for (EmailBotConfig b : store.getForAccount(accountId)) {
      if (!b.enabled) continue;
      if (b.botChatId > 0 && b.botChatId == chatId) return b;
    }
    return null;
  }

  @Nullable
  private EmailBotConfig resolveActiveBotForChat(@NonNull DcContext dc,
                                                 int accountId,
                                                 int chatId) {
    EmailBotConfig bot = findBotForChat(accountId, chatId);
    if (bot != null) return bot;
    return EmailBotResolver.resolve(store, dc, accountId, null, chatId);
  }

  @Nullable
  private Invocation parseInvocationInBotChat(@NonNull String body,
                                              int accountId,
                                              @NonNull EmailBotConfig homeBot) {
    Invocation inv = parseInvocation(body, accountId);
    if (inv != null) return inv;
    String trimmed = body.trim();
    if (!trimmed.startsWith("/")) return null;
    int sp = indexOfWhitespace(trimmed);
    String command = sp < 0
        ? trimmed.substring(1).toLowerCase(Locale.ROOT)
        : trimmed.substring(1, sp).toLowerCase(Locale.ROOT);
    String argument = sp < 0 ? "" : trimmed.substring(sp + 1).trim();
    return new Invocation(homeBot, command, argument);
  }

  private static boolean isBotEchoMessage(@NonNull String body) {
    String t = body.trim();
    if (t.startsWith(BOT_OUT_MARKER)) return true;
    return t.startsWith("@") && t.matches("^@[A-Za-z0-9_]+:\\s.*");
  }

  private static int indexOfWhitespace(@NonNull String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return i;
    }
    return -1;
  }

  @Nullable
  private String resolveReply(@NonNull EmailBotConfig bot,
                              @NonNull String command,
                              @NonNull String argument,
                              @NonNull String senderEmail) {
    String template = bot.commands.get(command);
    if (template == null && !"default".equals(command)) {
      // Unknown command: surface the help text if defined, otherwise
      // a default "unknown" notice. Mirrors how Telegram bots usually
      // behave when an unrecognised command arrives.
      template = bot.commands.get("default");
      if (template == null) {
        template = bot.commands.get("help");
      }
      if (template == null) {
        return null; // pure webhook-only bot
      }
    }
    if (template == null) return null;
    return template
        .replace("{{arg}}", argument)
        .replace("{{from}}", senderEmail)
        .replace("{{bot}}", bot.name);
  }

  /**
   * Calls the bot's webhook with a Telegram-style {@code update}
   * envelope and decodes the response. The response grammar is a
   * minimal subset of Telegram's <em>Sending updates</em> contract:
   *
   * <pre>{@code
   * {
   *   "method": "sendMessage",            // optional, defaults to sendMessage
   *   "text":   "Hello {{from}}",         // outgoing message body
   *   "parse_mode": "Markdown",           // optional, info-only
   *   "reply_markup": {
   *     "inline_keyboard": [              // 2-D array of buttons
   *       [{"text": "Yes", "callback_data": "y"}],
   *       [{"text": "No",  "url": "https://example.com"}]
   *     ]
   *   }
   * }
   * }</pre>
   *
   * <p>The legacy {@code {"reply": "…"}} schema from BMChat 2.49.47 is
   * still accepted for backwards compatibility — if both {@code reply}
   * and {@code text} are present, {@code text} wins.
   *
   * <p>Inline keyboards are rendered into the outgoing message body as
   * Markdown links pointing at a {@code bmchat-bot://} pseudo-scheme;
   * tapping such a link in BMChat re-invokes the webhook with a
   * {@code callback_query} update.
   */
  @Nullable
  private String callWebhook(@NonNull EmailBotConfig bot,
                             @NonNull Invocation inv,
                             @NonNull String senderEmail,
                             @NonNull String originalBody,
                             int chatId,
                             int msgId) {
    HttpURLConnection conn = null;
    try {
      Uri u = Uri.parse(bot.webhookUrl);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        Log.w(TAG, "webhook scheme not http/https: " + bot.webhookUrl);
        return null;
      }

      // Telegram-style update envelope. Mirrors the shape of an
      // Update object so existing client libraries (python-telegram-bot,
      // grammY, etc.) can be retargeted with a thin shim.
      JSONObject update = new JSONObject();
      update.put("update_id", msgId);

      JSONObject message = new JSONObject();
      message.put("message_id", msgId);
      message.put("chat", new JSONObject()
          .put("id", chatId)
          .put("type", "private"));
      message.put("from", new JSONObject()
          .put("email", senderEmail));
      message.put("text", originalBody);
      message.put("date", System.currentTimeMillis() / 1000L);
      update.put("message", message);

      // BMChat-specific convenience fields outside the Telegram
      // envelope so trivial bots can read the parsed command without
      // implementing a parser. These keys are stable.
      JSONObject bmchat = new JSONObject();
      bmchat.put("bot", bot.name);
      bmchat.put("token_suffix", bot.token);
      bmchat.put("command", inv.command);
      bmchat.put("argument", inv.argument);
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
      try (OutputStream os = conn.getOutputStream()) {
        os.write(bytes);
      }
      int code = conn.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK) {
        Log.w(TAG, "webhook " + bot.name + " HTTP " + code);
        return null;
      }
      StringBuilder sb = new StringBuilder();
      try (BufferedReader br = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        char[] buf = new char[2048];
        int n;
        while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
      }
      return decodeWebhookResponse(bot, sb.toString());
    } catch (Throwable t) {
      Log.w(TAG, "webhook " + bot.name + " failed", t);
      return null;
    } finally {
      if (conn != null) {
        try { conn.disconnect(); } catch (Throwable ignored) {}
      }
    }
  }

  /**
   * Decodes a webhook response into a plain-text reply that can be
   * pushed through Delta-chat. Supports both the BMChat 2.49.47 legacy
   * {@code {"reply": "…"}} shape and the new Telegram-shaped {@code
   * {"text": "…", "reply_markup": {…}}}.
   */
  @Nullable
  String decodeWebhookResponse(@NonNull EmailBotConfig bot, @Nullable String raw) {
    if (raw == null || raw.isEmpty()) return null;
    try {
      JSONObject resp = new JSONObject(raw);

      String text = resp.optString("text", null);
      if (text == null || text.isEmpty()) {
        text = resp.optString("reply", null);
      }
      if (text == null || text.isEmpty()) return null;

      String parseMode = resp.optString("parse_mode", null);
      text = normalizeWebhookText(text, parseMode);

      JSONObject markup = resp.optJSONObject("reply_markup");
      if (markup != null) {
        String keyboard = renderInlineKeyboard(bot, markup.optJSONArray("inline_keyboard"));
        if (keyboard != null && !keyboard.isEmpty()) {
          text = text + "\n\n" + keyboard;
        }
      }
      return text;
    } catch (Throwable t) {
      Log.w(TAG, "decodeWebhookResponse parse failed", t);
      return null;
    }
  }

  @Nullable
  private String normalizeWebhookText(@Nullable String text, @Nullable String parseMode) {
    if (text == null || text.isEmpty()) return text;
    if (parseMode != null) {
      String mode = parseMode.trim().toLowerCase(Locale.ROOT);
      if ("html".equals(mode)) {
        return MessageMarkdown.normalizeTelegramMarkdown(
            text.replaceAll("<br\\s*/?>", "\n")
                .replaceAll("<(b|strong)>", "**").replaceAll("</(b|strong)>", "**")
                .replaceAll("<(i|em)>", "__").replaceAll("</(i|em)>", "__")
                .replaceAll("<[^>]+>", ""));
      }
    }
    return MessageMarkdown.normalizeTelegramMarkdown(text);
  }

  /**
   * Flattens a Telegram-style {@code inline_keyboard} into Markdown
   * links suitable for the BMChat chat renderer. Each button becomes a
   * standalone line; rows in the Telegram array map to vertical groups
   * separated by a blank line, which keeps the visual hierarchy close
   * to what users see in Telegram even though we don't have true
   * pill-shaped buttons.
   */
  @Nullable
  private String renderInlineKeyboard(@NonNull EmailBotConfig bot,
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
          target = "bmchat-bot://cb/" + Uri.encode(bot.name)
              + "/" + Uri.encode(callbackData);
        } else {
          continue;
        }
        if (c > 0) out.append('\n');
        out.append("🔘 [").append(label).append("](").append(target).append(")");
      }
    }
    return out.length() == 0 ? null : out.toString();
  }

  /** Result of {@link #parseInvocation(String, int)}; package-private for tests. */
  static final class Invocation {
    @NonNull final EmailBotConfig bot;
    @NonNull final String command;
    @NonNull final String argument;
    Invocation(@NonNull EmailBotConfig bot,
               @NonNull String command,
               @NonNull String argument) {
      this.bot = bot;
      this.command = command;
      this.argument = argument;
    }
  }
}
