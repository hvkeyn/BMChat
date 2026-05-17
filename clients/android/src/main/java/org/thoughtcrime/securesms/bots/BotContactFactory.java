package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

import java.io.File;

/**
 * Register a Telegram bot as a virtual user inside BMChat — exactly the
 * way a Telegram bot exists inside Telegram itself: as a contact you can
 * add to any chat or channel, where its messages then appear automatically.
 *
 * <p>This used to create a per-bot group chat ("auto-channel") where the
 * user was the only human member. That model conflicted with the Telegram
 * bot mental model: bots are not their own channels — they are participants
 * other channels can include. The current factory therefore does <em>not</em>
 * create a group, only:
 *
 * <ul>
 *   <li>a {@link com.b44t.messenger.DcContact} pseudo-user under a
 *       non-deliverable {@code @bots.bmchat.local} address; this contact
 *       becomes selectable in the standard "Add member" picker so the
 *       user can drop the bot into any chat or broadcast list,</li>
 *   <li>a 1:1 conversation with that pseudo-contact via
 *       {@code dc_create_chat_by_contact_id} — used as the default home
 *       feed for the bot's posts, similar to the @BotName chat in
 *       Telegram itself,</li>
 *   <li>a one-time info card (description + commands fetched via
 *       {@code getMyDescription} / {@code getMyShortDescription} /
 *       {@code getMyCommands}) posted into that 1:1 chat as a device
 *       message so the user immediately sees what the bot is.</li>
 * </ul>
 *
 * <p>The downloaded Telegram avatar is bound to that 1:1 chat via
 * {@code dc_set_chat_profile_image}, so the chat list shows the actual
 * bot picture next to the bot's name — even though there is no native
 * way to set a contact-level avatar without {@code Chat-User-Avatar}
 * arriving over IMAP.
 */
public final class BotContactFactory {

  private static final String TAG = "BotContactFactory";

  private BotContactFactory() {}

  /** Result of {@link #buildContact(Context, String, JSONObject)}. */
  public static final class Result {
    public final int dcAccountId;
    public final int botContactId;
    /**
     * The 1:1 chat created against the pseudo-contact. Used as the
     * default home feed for bot posts before the user adds the bot
     * to any custom group / channel.
     */
    public final int defaultChatId;
    @Nullable public final String avatarPath;
    public final long telegramBotId;
    @Nullable public final String telegramUsername;
    @Nullable public final String telegramName;
    @Nullable public final String description;
    @Nullable public final String shortDescription;

    Result(int dcAccountId, int botContactId, int defaultChatId,
           @Nullable String avatarPath,
           long telegramBotId, @Nullable String telegramUsername,
           @Nullable String telegramName,
           @Nullable String description, @Nullable String shortDescription) {
      this.dcAccountId = dcAccountId;
      this.botContactId = botContactId;
      this.defaultChatId = defaultChatId;
      this.avatarPath = avatarPath;
      this.telegramBotId = telegramBotId;
      this.telegramUsername = telegramUsername;
      this.telegramName = telegramName;
      this.description = description;
      this.shortDescription = shortDescription;
    }
  }

  @WorkerThread
  @Nullable
  public static Result buildContact(@NonNull Context context,
                                    @NonNull String token,
                                    @NonNull JSONObject meResult) {
    long botId = meResult.optLong("id", 0L);
    String username = meResult.optString("username", null);
    String firstName = meResult.optString("first_name", null);

    String displayName;
    if (firstName != null && !firstName.isEmpty()) displayName = firstName;
    else if (username != null && !username.isEmpty()) displayName = "@" + username;
    else displayName = "Telegram Bot";

    DcContext dc = DcHelper.getContext(context);
    int dcAccountId = dc.getAccountId();

    String botEmail = makeBotEmail(botId, username);

    int botContactId;
    try {
      botContactId = dc.createContact(displayName, botEmail);
    } catch (Throwable t) {
      Log.w(TAG, "createContact failed", t);
      return null;
    }
    if (botContactId <= 0) {
      Log.w(TAG, "createContact returned invalid id " + botContactId);
      return null;
    }

    int defaultChatId = 0;
    try {
      defaultChatId = dc.createChatByContactId(botContactId);
    } catch (Throwable t) {
      Log.w(TAG, "createChatByContactId failed (non-fatal)", t);
    }

    String avatarPath = null;
    TelegramApi api = new TelegramApi(token);
    try {
      File dir = new File(context.getFilesDir(), "bots/avatars");
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
      File avatarFile = new File(dir,
          "bot_" + botId + "_" + System.currentTimeMillis() + ".jpg");
      File downloaded = api.downloadProfilePhoto(botId, avatarFile);
      if (downloaded != null) {
        avatarPath = downloaded.getAbsolutePath();
        if (defaultChatId > 0) {
          try {
            dc.setChatProfileImage(defaultChatId, avatarPath);
          } catch (Throwable t) {
            Log.w(TAG, "setChatProfileImage failed (non-fatal)", t);
          }
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "downloadProfilePhoto failed (non-fatal)", t);
    }

    String description = api.getMyDescription();
    String shortDescription = api.getMyShortDescription();
    JSONArray commands = api.getMyCommands();

    if (defaultChatId > 0) {
      postInfoCard(dc, defaultChatId, displayName, username,
          description, shortDescription, commands);
    }

    return new Result(dcAccountId, botContactId, defaultChatId, avatarPath,
        botId, username, firstName, description, shortDescription);
  }

  /**
   * Synthesise a non-deliverable e-mail address for the bot
   * pseudo-contact. {@code @bots.bmchat.local} guarantees that any
   * accidental SMTP attempt fails fast at the resolver stage.
   */
  @NonNull
  static String makeBotEmail(long botId, @Nullable String username) {
    String slug;
    if (username != null && !username.isEmpty()) {
      slug = username.toLowerCase().replaceAll("[^a-z0-9._-]", "");
    } else {
      slug = "bot" + botId;
    }
    if (slug.isEmpty()) slug = "bot" + botId;
    return "tgbot." + slug + "@bots.bmchat.local";
  }

  /** Render and append a one-time "ℹ️ About / Commands" message into the
   *  bot home chat as a device message. */
  private static void postInfoCard(@NonNull DcContext dc, int chatId,
                                   @NonNull String displayName,
                                   @Nullable String username,
                                   @Nullable String description,
                                   @Nullable String shortDescription,
                                   @Nullable JSONArray commands) {
    StringBuilder sb = new StringBuilder();
    sb.append("ℹ️ ").append(displayName);
    if (username != null && !username.isEmpty()) {
      sb.append(" (@").append(username).append(")");
    }
    sb.append('\n');

    if (!TextUtils.isEmpty(shortDescription)) {
      sb.append('\n').append(shortDescription).append('\n');
    }
    if (!TextUtils.isEmpty(description) && !description.equals(shortDescription)) {
      sb.append('\n').append(description).append('\n');
    }

    if (commands != null && commands.length() > 0) {
      sb.append("\nКоманды:\n");
      for (int i = 0; i < commands.length(); i++) {
        JSONObject c = commands.optJSONObject(i);
        if (c == null) continue;
        String cmd = c.optString("command", "");
        String desc = c.optString("description", "");
        if (cmd.isEmpty()) continue;
        sb.append("  /").append(cmd);
        if (!desc.isEmpty()) sb.append(" — ").append(desc);
        sb.append('\n');
      }
    }

    sb.append("\nЭто домашний чат бота — сюда автоматически попадают все сообщения, которые приходят боту в Telegram.\n\nЧтобы бот публиковал их также в твою группу или канал, открой раздел «Настройки → Боты Telegram», нажми на бота и выбери «Добавить бота в чат…». Бот можно добавлять в любые группы и каналы (включая зашифрованные); приватные 1:1-чаты между двумя пользователями не поддерживаются — это совпадает с поведением Telegram.");

    try {
      DcMsg msg = new DcMsg(dc, DcMsg.DC_MSG_TEXT);
      msg.setText(sb.toString().trim());
      dc.addDeviceMsg("bot-info-" + chatId, msg);
    } catch (Throwable t) {
      Log.w(TAG, "postInfoCard via addDeviceMsg failed; falling back to sendMsg", t);
      try {
        DcMsg msg2 = new DcMsg(dc, DcMsg.DC_MSG_TEXT);
        msg2.setText(sb.toString().trim());
        dc.sendMsg(chatId, msg2);
      } catch (Throwable t2) {
        Log.w(TAG, "postInfoCard fallback sendMsg also failed", t2);
      }
    }
  }

  /** Best-effort cleanup: drops the pseudo-contact (and, transitively,
   *  the 1:1 default chat). The user's own group chats survive — the
   *  bot just stops being a member after the contact is removed. */
  @WorkerThread
  public static void onBotRemoved(@NonNull Context context, @NonNull BotConfig bot) {
    try {
      DcContext dc = DcHelper.getContext(context);
      if (bot.botContactId > 0) {
        dc.deleteContact(bot.botContactId);
      }
    } catch (Throwable t) {
      Log.w(TAG, "onBotRemoved cleanup failed (non-fatal)", t);
    }
  }
}
