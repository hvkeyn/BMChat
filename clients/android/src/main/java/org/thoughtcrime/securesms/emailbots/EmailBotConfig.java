package org.thoughtcrime.securesms.emailbots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for a single BMChat <em>email bot</em>.
 *
 * <p>Email bots are BMChat's answer to "I want Telegram-style bot
 * automation, but the transport must be e-mail because that's where my
 * data lives." They live entirely inside the user's mailbox: every
 * incoming message addressed to the owner is inspected by
 * {@code EmailBotDispatcher}, and if it carries a command for one of
 * the owner's registered bots, BMChat sends the reply through the same
 * IMAP/SMTP credentials the owner is already authenticated with.
 *
 * <p>Like a Telegram bot whose webhook URL has been wiped, an email bot
 * stops functioning the moment its owner account is removed or its
 * IMAP credentials become invalid — there is no central server, the
 * automation runs on the device that holds the mailbox.
 *
 * <p>A bot supports two activation models:
 * <ul>
 *   <li><strong>Slash commands</strong> ({@code /weather Moscow}) — the
 *       reply text comes from the bot's {@link #commands} map and may
 *       contain the {@code {{arg}}} placeholder which is replaced with
 *       everything after the command name.</li>
 *   <li><strong>Webhook</strong> ({@link #webhookUrl}) — when set, the
 *       dispatcher POSTs a JSON payload describing the inbound mail to
 *       this URL and uses the {@code reply} field of the response as
 *       the outgoing message. Webhooks let users plug arbitrary
 *       server-side logic in without changing BMChat code.</li>
 * </ul>
 *
 * <p>The model is intentionally immutable; updates produce a new
 * {@code EmailBotConfig} via the {@code with…} helpers so the store can
 * commit them atomically. See {@link EmailBotStore} for persistence.
 */
public final class EmailBotConfig {

  /** Stable BMChat-side identifier (UUID-ish); rename-safe. */
  @NonNull public final String id;

  /**
   * Bot user-visible name (no leading {@code @}). Used as the
   * activation prefix in the form {@code @botname /command}, and shown
   * in the bots list. Must match {@code [A-Za-z0-9_-]+}.
   */
  @NonNull public final String name;

  /** Free-form description shown in the bot list. */
  @Nullable public final String description;

  /**
   * Owner account id. The bot is only active while this account is
   * present in BMChat — removing it acts as "wipe webhook" in
   * Telegram-speak.
   */
  public final int ownerAccountId;

  /** {@code false} pauses the bot without deleting it. */
  public final boolean enabled;

  /**
   * Static slash-command table. Keys are the command name without the
   * leading slash ({@code "start"}, {@code "weather"}, …); values are
   * the reply template. The placeholder {@code {{arg}}} expands to
   * everything after the command name in the inbound message; the
   * placeholder {@code {{from}}} expands to the sender's e-mail
   * address.
   */
  @NonNull public final Map<String, String> commands;

  /**
   * Optional webhook URL. When non-empty, the dispatcher POSTs a JSON
   * payload describing the inbound mail to this URL on every match
   * (after slash commands have been tried). The response is expected
   * to be {@code {"reply": "..."} } — the {@code reply} field, if
   * present, is sent back to the sender.
   *
   * <p>HTTPS is recommended but plain HTTP is allowed for self-hosted
   * deployments on a LAN / VPS.
   */
  @Nullable public final String webhookUrl;

  /** Wall-clock time of creation, ms since epoch. */
  public final long createdAtMs;

  /** Wall-clock time of the last successful reply, ms since epoch. */
  public final long lastReplyAtMs;

  /** Cumulative number of replies sent. */
  public final long totalReplies;

  /**
   * Bot token, used as the shared secret BMChat presents to the
   * webhook in the {@code X-BMChat-Bot-Token} header and that the
   * webhook reflects back in the {@code "method": "answerCallbackQuery"}
   * authentication path. Generated automatically on bot creation,
   * stable across renames. Mirrors Telegram's {@code 123:ABC…} token.
   */
  @NonNull public final String token;

  /**
   * Human-friendly display name shown in chat lists and the bot info
   * card. Equivalent to Telegram's {@code first_name}. May contain
   * spaces and punctuation — only {@link #name} has to be a slug.
   */
  @NonNull public final String displayName;

  /**
   * Local file system path to the avatar image, or {@code null}.
   * Stored on the device only — there is no central registry of
   * BMChat bots, the avatar exists for in-chat rendering.
   */
  @Nullable public final String avatarPath;

  /**
   * E-mail address of the bot's <em>developer</em>. Every incoming
   * message from a subscribed user is converted into a Telegram-style
   * Update JSON and shipped to this address as an ordinary chat
   * message, identifiable by the {@code X-BMChat-Bot-Update} header.
   * The developer answers from their own client (or a server-side
   * IMAP listener) with a reply tagged {@code X-BMChat-Bot-Reply},
   * which BMChat then dispatches into the originating chat.
   *
   * <p>If this field is {@code null} the bot operates in the legacy
   * static-commands-only mode (BMChat 2.49.47 behaviour).
   */
  @Nullable public final String developerEmail;

  /**
   * E-mail addresses that have already pressed {@code /start} and are
   * therefore allowed to converse with the bot. Mirrors Telegram's
   * "user must press Start to allow the bot to message them" rule —
   * a freshly seen address gets only the welcome reply, anything else
   * is silently dropped until {@code /start} arrives.
   *
   * <p>All values are lower-cased.
   */
  @NonNull public final Set<String> subscribedUsers;

  /** Pseudo-contact id ({@code emailbot.*@bots.bmchat.local}). */
  public final int botContactId;

  /** 1:1 home chat with the pseudo-contact. */
  public final int botChatId;

  /**
   * Groups/channels that receive this bot's outbound posts (webhook/API
   * replies). Mirrors {@link org.thoughtcrime.securesms.bots.BotConfig#attachedChatIds}.
   */
  @NonNull public final List<Integer> attachedChatIds;

  public EmailBotConfig(@NonNull String id,
                        @NonNull String name,
                        @Nullable String description,
                        int ownerAccountId,
                        boolean enabled,
                        @Nullable Map<String, String> commands,
                        @Nullable String webhookUrl,
                        long createdAtMs,
                        long lastReplyAtMs,
                        long totalReplies,
                        @Nullable String token,
                        @Nullable String displayName,
                        @Nullable String avatarPath,
                        @Nullable String developerEmail,
                        @Nullable Set<String> subscribedUsers,
                        int botContactId,
                        int botChatId,
                        @Nullable List<Integer> attachedChatIds) {
    this.id = id;
    this.name = name;
    this.description = description;
    this.ownerAccountId = ownerAccountId;
    this.enabled = enabled;
    this.commands = sanitiseCommands(commands);
    this.webhookUrl = webhookUrl == null || webhookUrl.isEmpty() ? null : webhookUrl;
    this.createdAtMs = createdAtMs > 0 ? createdAtMs : System.currentTimeMillis();
    this.lastReplyAtMs = lastReplyAtMs;
    this.totalReplies = totalReplies;
    this.token = (token == null || token.isEmpty()) ? generateToken(id) : token;
    this.displayName = (displayName == null || displayName.isEmpty()) ? name : displayName;
    this.avatarPath = (avatarPath == null || avatarPath.isEmpty()) ? null : avatarPath;
    this.developerEmail = (developerEmail == null || developerEmail.isEmpty())
        ? null : developerEmail.toLowerCase(Locale.ROOT).trim();
    this.subscribedUsers = sanitiseSubscribed(subscribedUsers);
    this.botContactId = botContactId;
    this.botChatId = botChatId;
    this.attachedChatIds = sanitiseChatIds(attachedChatIds);
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

  private static Set<String> sanitiseSubscribed(@Nullable Set<String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    LinkedHashSet<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = s.trim().toLowerCase(Locale.ROOT);
      if (!n.isEmpty()) out.add(n);
    }
    return Collections.unmodifiableSet(out);
  }

  /** Convenience factory for newly-created bots. */
  public static EmailBotConfig newBot(@NonNull String id,
                                      @NonNull String name,
                                      int ownerAccountId) {
    Map<String, String> defaults = new LinkedHashMap<>();
    defaults.put("start",
        "Привет, {{from}}! Я бот @" + name + ". Команды: /help");
    defaults.put("help",
        "Доступные команды: /start, /help. Расширьте список в BMChat → "
        + "«Email-боты» → «" + name + "».");
    return new EmailBotConfig(
        id, name, null, ownerAccountId, true, defaults, null,
        System.currentTimeMillis(), 0L, 0L, null,
        null, null, null, null, 0, 0, null);
  }

  /**
   * Deterministically derives a stable bot token from the bot id.
   * Format mimics Telegram ({@code <numeric>:<base64ish>}) so existing
   * client libraries can be retargeted with minimal effort.
   */
  @NonNull
  private static String generateToken(@NonNull String botId) {
    long numeric = Math.abs((long) botId.hashCode()) % 1_000_000_000L;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < botId.length() && sb.length() < 35; i++) {
      char c = botId.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
    }
    while (sb.length() < 16) sb.append('a');
    return numeric + ":" + sb.toString();
  }

  // ---------------------------------------------------------------------
  // Immutable updates
  // ---------------------------------------------------------------------

  public EmailBotConfig withOwnerAccountId(int newOwnerAccountId) {
    if (newOwnerAccountId == ownerAccountId) return this;
    return new EmailBotConfig(id, name, description, newOwnerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withEnabled(boolean newEnabled) {
    if (newEnabled == enabled) return this;
    return new EmailBotConfig(id, name, description, ownerAccountId, newEnabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withContactIds(int newContactId, int newChatId) {
    if (newContactId == botContactId && newChatId == botChatId) return this;
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers,
        newContactId, newChatId, attachedChatIds);
  }

  /** Drop device-local ids before merging sync from another device. */
  @NonNull
  public EmailBotConfig withClearedLocalIds() {
    if (botContactId == 0 && botChatId == 0) return this;
    return withContactIds(0, 0);
  }

  public EmailBotConfig withName(@NonNull String newName) {
    return new EmailBotConfig(id, newName, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withDescription(@Nullable String newDescription) {
    return new EmailBotConfig(id, name, newDescription, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withCommands(@Nullable Map<String, String> newCommands) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        newCommands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withWebhookUrl(@Nullable String url) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, url, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withReplySent(long whenMs) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, whenMs, totalReplies + 1, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withDisplayName(@Nullable String newDisplayName) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        newDisplayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withAvatarPath(@Nullable String newAvatarPath) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, newAvatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withDeveloperEmail(@Nullable String newDeveloperEmail) {
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, newDeveloperEmail, subscribedUsers, botContactId, botChatId,
        attachedChatIds);
  }

  public EmailBotConfig withAttachedChat(int chatId) {
    if (chatId <= 0 || attachedChatIds.contains(chatId)) return this;
    LinkedHashSet<Integer> next = new LinkedHashSet<>(attachedChatIds);
    next.add(chatId);
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId,
        new ArrayList<>(next));
  }

  public EmailBotConfig withoutAttachedChat(int chatId) {
    if (chatId <= 0 || !attachedChatIds.contains(chatId)) return this;
    ArrayList<Integer> next = new ArrayList<>(attachedChatIds);
    next.remove(Integer.valueOf(chatId));
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, subscribedUsers, botContactId, botChatId, next);
  }

  public EmailBotConfig withSubscribed(@NonNull String email) {
    String e = email.trim().toLowerCase(Locale.ROOT);
    if (e.isEmpty() || subscribedUsers.contains(e)) return this;
    LinkedHashSet<String> next = new LinkedHashSet<>(subscribedUsers);
    next.add(e);
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, next, botContactId, botChatId, attachedChatIds);
  }

  public EmailBotConfig withoutSubscribed(@NonNull String email) {
    String e = email.trim().toLowerCase(Locale.ROOT);
    if (!subscribedUsers.contains(e)) return this;
    LinkedHashSet<String> next = new LinkedHashSet<>(subscribedUsers);
    next.remove(e);
    return new EmailBotConfig(id, name, description, ownerAccountId, enabled,
        commands, webhookUrl, createdAtMs, lastReplyAtMs, totalReplies, token,
        displayName, avatarPath, developerEmail, next, botContactId, botChatId, attachedChatIds);
  }

  public boolean isSubscribed(@NonNull String email) {
    if (subscribedUsers.isEmpty()) return false;
    return subscribedUsers.contains(email.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * BotFather rule: bot username must end with "bot" (case-insensitive,
   * either {@code helperbot} or {@code helper_bot}).
   */
  public static boolean isValidBotName(@NonNull String slug) {
    if (slug.isEmpty()) return false;
    String lower = slug.toLowerCase(Locale.ROOT);
    return lower.endsWith("bot") || lower.endsWith("_bot");
  }

  // ---------------------------------------------------------------------
  // (de)serialisation
  // ---------------------------------------------------------------------

  public JSONObject toJson() throws JSONException {
    JSONObject o = new JSONObject();
    o.put("id", id);
    o.put("name", name);
    if (description != null) o.put("description", description);
    o.put("ownerAccountId", ownerAccountId);
    o.put("enabled", enabled);
    if (!commands.isEmpty()) {
      JSONArray arr = new JSONArray();
      for (Map.Entry<String, String> e : commands.entrySet()) {
        JSONObject c = new JSONObject();
        c.put("k", e.getKey());
        c.put("v", e.getValue());
        arr.put(c);
      }
      o.put("commands", arr);
    }
    if (webhookUrl != null) o.put("webhookUrl", webhookUrl);
    o.put("createdAtMs", createdAtMs);
    o.put("lastReplyAtMs", lastReplyAtMs);
    o.put("totalReplies", totalReplies);
    o.put("token", token);
    o.put("displayName", displayName);
    if (avatarPath != null) o.put("avatarPath", avatarPath);
    if (developerEmail != null) o.put("developerEmail", developerEmail);
    if (!subscribedUsers.isEmpty()) {
      JSONArray arr = new JSONArray();
      for (String e : subscribedUsers) arr.put(e);
      o.put("subscribedUsers", arr);
    }
    if (botContactId > 0) o.put("botContactId", botContactId);
    if (botChatId > 0) o.put("botChatId", botChatId);
    return o;
  }

  public static EmailBotConfig fromJson(@NonNull JSONObject o) throws JSONException {
    Map<String, String> cmds = new LinkedHashMap<>();
    JSONArray arr = o.optJSONArray("commands");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        JSONObject c = arr.optJSONObject(i);
        if (c == null) continue;
        String k = c.optString("k", null);
        String v = c.optString("v", null);
        if (k != null && !k.isEmpty() && v != null) cmds.put(k.toLowerCase(), v);
      }
    }
    LinkedHashSet<String> subs = new LinkedHashSet<>();
    JSONArray subArr = o.optJSONArray("subscribedUsers");
    if (subArr != null) {
      for (int i = 0; i < subArr.length(); i++) {
        String s = subArr.optString(i, null);
        if (s != null && !s.isEmpty()) subs.add(s);
      }
    }
    ArrayList<Integer> attached = new ArrayList<>();
    JSONArray attachArr = o.optJSONArray("attachedChatIds");
    if (attachArr != null) {
      for (int i = 0; i < attachArr.length(); i++) {
        int cid = attachArr.optInt(i, 0);
        if (cid > 0) attached.add(cid);
      }
    }
    return new EmailBotConfig(
        o.getString("id"),
        o.getString("name"),
        o.optString("description", null),
        o.optInt("ownerAccountId", 0),
        o.optBoolean("enabled", true),
        cmds,
        o.optString("webhookUrl", null),
        o.optLong("createdAtMs", 0L),
        o.optLong("lastReplyAtMs", 0L),
        o.optLong("totalReplies", 0L),
        o.optString("token", null),
        o.optString("displayName", null),
        o.optString("avatarPath", null),
        o.optString("developerEmail", null),
        subs,
        o.optInt("botContactId", 0),
        o.optInt("botChatId", 0),
        attached);
  }

  /**
   * Returns a normalised, lower-cased, deduplicated command map. The
   * dispatcher always looks up commands with their lower-cased key so
   * doing the work here once keeps the hot path branch-free.
   */
  private static Map<String, String> sanitiseCommands(@Nullable Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptyMap();
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : raw.entrySet()) {
      String k = e.getKey();
      String v = e.getValue();
      if (k == null) continue;
      k = k.trim().toLowerCase();
      if (k.isEmpty()) continue;
      // strip a possible leading slash so the keys are uniform
      while (k.startsWith("/")) k = k.substring(1);
      if (k.isEmpty()) continue;
      out.put(k, v == null ? "" : v);
    }
    return Collections.unmodifiableMap(out);
  }

  // ---------------------------------------------------------------------
  // Convenience
  // ---------------------------------------------------------------------

  /**
   * Display name shown in the list. Combines the human-readable
   * {@link #displayName} (Telegram's "first_name") with the slug
   * ({@code @username}) so the user can recognise the bot even if
   * they only know one of the two.
   */
  @NonNull
  public String displayName() {
    if (displayName == null || displayName.isEmpty() || displayName.equalsIgnoreCase(name)) {
      return "@" + name;
    }
    return displayName + " (@" + name + ")";
  }

  /** Convenience: list of commands as ordered key entries for the UI. */
  @NonNull
  public List<Map.Entry<String, String>> commandEntries() {
    return new ArrayList<>(commands.entrySet());
  }
}
