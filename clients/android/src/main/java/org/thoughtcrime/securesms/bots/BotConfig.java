package org.thoughtcrime.securesms.bots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Configuration for a single connected Telegram bot.
 *
 * <p>Each {@link BotConfig} represents one bot token registered with
 * {@code @BotFather} on Telegram. BMChat polls the Telegram Bot API on
 * behalf of this token (or, optionally, receives webhooks via the BMChat
 * VPS) and republishes every incoming Telegram message into the BMChat
 * chat addressed by {@link #targetDcChatId}.
 *
 * <p>Tokens are persisted by {@link BotStore} in private
 * {@code SharedPreferences} with light XOR obfuscation against casual
 * backup snooping; they are never logged or transmitted anywhere except
 * to {@code https://api.telegram.org}.
 */
public final class BotConfig {

  /** Stable BMChat-side identifier (UUID-ish) so the user can reorder/rename. */
  @NonNull public final String id;

  /** Plain Telegram Bot API token (e.g. {@code 123456789:ABCDEF...}). */
  @NonNull public final String token;

  /** Telegram bot username without {@code @} (cached after the first {@code getMe}). */
  @Nullable public final String telegramUsername;

  /** Display name as returned by Telegram {@code getMe.first_name}. */
  @Nullable public final String telegramName;

  /** Local cached path to the bot avatar (downloaded from Telegram). */
  @Nullable public final String avatarPath;

  /** Numeric Telegram user_id of the bot itself (self). */
  public final long telegramBotId;

  /** Account ID (DcAccounts) the bot publishes into. */
  public final int dcAccountId;

  /**
   * Target BMChat chat-id where Telegram messages are republished. May be
   * a self-talk, a regular 1:1, or a group; the picker warns the user if
   * the destination is not a self-talk.
   */
  public final int targetDcChatId;

  /** Last processed Telegram {@code update_id}; the next {@code getUpdates}
   *  call uses {@code offset = lastUpdateId + 1}. */
  public final long lastUpdateId;

  /** Wall-clock of the last successful poll, ms since epoch. */
  public final long lastPolledAtMs;

  /** {@code true} when polling is disabled by the user. */
  public final boolean paused;

  /** Optional human note shown in the bots list. */
  @Nullable public final String note;

  /**
   * BMChat-side {@link com.b44t.messenger.DcContact} id of the pseudo
   * contact representing this bot. {@code 0} for legacy entries created
   * before 2.49.15 — {@link #botContactId()} treats that as "unknown".
   */
  public final int botContactId;

  /** Telegram bot long description ({@code getMyDescription}), if any. */
  @Nullable public final String description;

  /** Telegram bot short description ({@code getMyShortDescription}), if any. */
  @Nullable public final String shortDescription;

  /**
   * Local-only list of {@code DcChat} ids the bot has been "attached" to.
   *
   * <p>Unlike Telegram, BMChat cannot insert the pseudo-contact as a real
   * group member because Delta-core refuses key-less contacts inside any
   * encrypted chat (no PGP key). Instead, every chat the user picks via
   * "Add bot to chat…" is recorded here, and the dispatcher publishes
   * Telegram updates into each of these chats locally — sending them
   * through the user's own SMTP credentials, prefixed with a "🤖 BotName"
   * header so other group members see who the post belongs to.
   *
   * <p>Stored as a stable, deduplicated list (insertion order). Always
   * non-{@code null} and never contains zeros / negatives.
   */
  @NonNull public final List<Integer> attachedChatIds;

  /**
   * When {@code true}, incoming Telegram updates are NOT published
   * automatically. Instead they are queued in {@link PendingPostStore}
   * and the user reviews them manually in "Очередь сообщений" — the
   * planner UI lets them publish or discard each post before it reaches
   * any BMChat chat.
   */
  public final boolean manualReview;

  public BotConfig(@NonNull String id,
                   @NonNull String token,
                   @Nullable String telegramUsername,
                   @Nullable String telegramName,
                   @Nullable String avatarPath,
                   long telegramBotId,
                   int dcAccountId,
                   int targetDcChatId,
                   long lastUpdateId,
                   long lastPolledAtMs,
                   boolean paused,
                   @Nullable String note,
                   int botContactId,
                   @Nullable String description,
                   @Nullable String shortDescription,
                   @Nullable List<Integer> attachedChatIds,
                   boolean manualReview) {
    this.id = id;
    this.token = token;
    this.telegramUsername = telegramUsername;
    this.telegramName = telegramName;
    this.avatarPath = avatarPath;
    this.telegramBotId = telegramBotId;
    this.dcAccountId = dcAccountId;
    this.targetDcChatId = targetDcChatId;
    this.lastUpdateId = lastUpdateId;
    this.lastPolledAtMs = lastPolledAtMs;
    this.paused = paused;
    this.note = note;
    this.botContactId = botContactId;
    this.description = description;
    this.shortDescription = shortDescription;
    this.attachedChatIds = sanitiseChatIds(attachedChatIds);
    this.manualReview = manualReview;
  }

  private static List<Integer> sanitiseChatIds(@Nullable List<Integer> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptyList();
    LinkedHashSet<Integer> dedup = new LinkedHashSet<>(raw.size());
    for (Integer v : raw) {
      if (v == null) continue;
      int n = v;
      if (n > 0) dedup.add(n);
    }
    if (dedup.isEmpty()) return Collections.emptyList();
    return Collections.unmodifiableList(new ArrayList<>(dedup));
  }

  /** Returns the bot pseudo-contact id, or {@code 0} if unknown. */
  public int botContactId() { return botContactId; }

  public BotConfig withProgress(long newLastUpdateId, long polledAtMs) {
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        Math.max(newLastUpdateId, lastUpdateId), polledAtMs, paused, note,
        botContactId, description, shortDescription, attachedChatIds, manualReview);
  }

  public BotConfig withMeta(@Nullable String username, @Nullable String name,
                            @Nullable String avatarPath, long telegramBotId) {
    return new BotConfig(id, token, username, name, avatarPath,
        telegramBotId != 0 ? telegramBotId : this.telegramBotId,
        dcAccountId, targetDcChatId, lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, attachedChatIds, manualReview);
  }

  public BotConfig withTarget(int newDcAccountId, int newTargetChatId) {
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, newDcAccountId, newTargetChatId,
        lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, attachedChatIds, manualReview);
  }

  public BotConfig withPaused(boolean newPaused) {
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        lastUpdateId, lastPolledAtMs, newPaused, note,
        botContactId, description, shortDescription, attachedChatIds, manualReview);
  }

  public BotConfig withDescriptions(@Nullable String description,
                                    @Nullable String shortDescription) {
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, attachedChatIds, manualReview);
  }

  public BotConfig withManualReview(boolean newManualReview) {
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, attachedChatIds, newManualReview);
  }

  /** Adds {@code newChatId} to {@link #attachedChatIds}, deduplicating. */
  public BotConfig withAttachedChat(int newChatId) {
    if (newChatId <= 0) return this;
    LinkedHashSet<Integer> next = new LinkedHashSet<>(attachedChatIds);
    next.add(newChatId);
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, new ArrayList<>(next), manualReview);
  }

  /** Removes {@code chatId} from {@link #attachedChatIds}; no-op if missing. */
  public BotConfig withoutAttachedChat(int chatId) {
    if (chatId <= 0 || attachedChatIds.isEmpty()) return this;
    if (!attachedChatIds.contains(chatId)) return this;
    ArrayList<Integer> next = new ArrayList<>(attachedChatIds);
    next.removeAll(Arrays.asList(chatId));
    return new BotConfig(id, token, telegramUsername, telegramName, avatarPath,
        telegramBotId, dcAccountId, targetDcChatId,
        lastUpdateId, lastPolledAtMs, paused, note,
        botContactId, description, shortDescription, next, manualReview);
  }

  public JSONObject toJson() throws JSONException {
    JSONObject o = new JSONObject();
    o.put("id", id);
    o.put("token", token);
    if (telegramUsername != null) o.put("telegramUsername", telegramUsername);
    if (telegramName != null) o.put("telegramName", telegramName);
    if (avatarPath != null) o.put("avatarPath", avatarPath);
    o.put("telegramBotId", telegramBotId);
    o.put("dcAccountId", dcAccountId);
    o.put("targetDcChatId", targetDcChatId);
    o.put("lastUpdateId", lastUpdateId);
    o.put("lastPolledAtMs", lastPolledAtMs);
    o.put("paused", paused);
    if (note != null) o.put("note", note);
    o.put("botContactId", botContactId);
    if (description != null) o.put("description", description);
    if (shortDescription != null) o.put("shortDescription", shortDescription);
    if (!attachedChatIds.isEmpty()) {
      JSONArray arr = new JSONArray();
      for (Integer v : attachedChatIds) arr.put(v.intValue());
      o.put("attachedChatIds", arr);
    }
    o.put("manualReview", manualReview);
    return o;
  }

  public static BotConfig fromJson(JSONObject o) throws JSONException {
    List<Integer> attached;
    JSONArray arr = o.optJSONArray("attachedChatIds");
    if (arr == null || arr.length() == 0) {
      attached = Collections.emptyList();
    } else {
      attached = new ArrayList<>(arr.length());
      for (int i = 0; i < arr.length(); i++) {
        int v = arr.optInt(i, 0);
        if (v > 0) attached.add(v);
      }
    }
    return new BotConfig(
        o.getString("id"),
        o.getString("token"),
        o.optString("telegramUsername", null),
        o.optString("telegramName", null),
        o.optString("avatarPath", null),
        o.optLong("telegramBotId", 0L),
        o.optInt("dcAccountId", 0),
        o.optInt("targetDcChatId", 0),
        o.optLong("lastUpdateId", 0L),
        o.optLong("lastPolledAtMs", 0L),
        o.optBoolean("paused", false),
        o.optString("note", null),
        o.optInt("botContactId", 0),
        o.optString("description", null),
        o.optString("shortDescription", null),
        attached,
        o.optBoolean("manualReview", false));
  }

  public String displayName() {
    if (telegramName != null && !telegramName.isEmpty()) return telegramName;
    if (telegramUsername != null && !telegramUsername.isEmpty()) return "@" + telegramUsername;
    return token.length() > 12 ? token.substring(0, 12) + "…" : token;
  }
}
