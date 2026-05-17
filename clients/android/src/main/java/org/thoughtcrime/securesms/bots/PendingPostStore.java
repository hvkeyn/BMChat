package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-bot queue of Telegram updates that are waiting for the user to
 * approve before being mirrored into BMChat chats.
 *
 * <p>When a bot has {@link BotConfig#manualReview} = {@code true}, the
 * dispatcher serialises every incoming Telegram update into this store
 * (one entry per update) instead of publishing it. The user opens the
 * "Очередь сообщений" UI for that bot, reviews each pending post, and
 * decides individually:
 *
 * <ul>
 *   <li>"Опубликовать" — fed back through
 *       {@link TelegramMessageDispatcher#publishUpdate(JSONObject)} and
 *       removed from the queue,</li>
 *   <li>"Удалить" — dropped without publishing.</li>
 * </ul>
 *
 * <p>Each entry stores the full Telegram {@code Update} JSON (so the
 * dispatcher can rebuild the same render later), plus a small UI cache
 * (preview text, a flag for "has media", received-at timestamp) so the
 * planner can show a list without re-parsing the entire update.
 *
 * <p>Backed by a private {@link SharedPreferences} file keyed on
 * {@code "bot." + botId}; the value is a JSON array of entries. The
 * store is process-wide synchronised — concurrent polls and UI taps
 * cannot lose entries.
 */
public final class PendingPostStore {

  private static final String TAG = "PendingPostStore";
  private static final String PREFS = "bmchat-bot-pending";

  private final Context appContext;

  public PendingPostStore(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  private SharedPreferences prefs() {
    return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  // ---------------------------------------------------------------------
  //  data class
  // ---------------------------------------------------------------------

  public static final class Entry {
    /** Stable id for the row — used by UI as ListView item key. */
    @NonNull public final String entryId;
    /** Telegram {@code update_id} (also fine to use as a tie-breaker). */
    public final long updateId;
    /** Wall-clock when the dispatcher saw this update. */
    public final long receivedAtMs;
    /** Short preview line for the planner list (≤ 140 chars). */
    @NonNull public final String preview;
    /** "photo" / "video" / "document" / "audio" / null. */
    @Nullable public final String mediaKind;
    /** Full original Telegram Update JSON for the first/only item. */
    @NonNull public final JSONObject raw;
    /**
     * For Telegram media-group ("album") updates this holds every
     * member of the group in arrival order. {@link #raw} is identical
     * to {@code albumParts.get(0)}. For non-album entries this is
     * {@code null} or a 1-element list.
     */
    @Nullable public final JSONArray albumParts;
    /** Telegram media_group_id ("" / null when this is a single post). */
    @Nullable public final String mediaGroupId;
    /** {@code true} once the post was actually mirrored into BMChat. */
    public final boolean published;
    /** Wall-clock when {@link #published} flipped to {@code true}; 0 otherwise. */
    public final long publishedAtMs;
    /**
     * If {@code > 0}, the user asked us to publish this post at that
     * wall-clock time. The next polling cycle that runs after this
     * time will pick the entry up automatically. Cleared back to 0
     * when {@link #published} flips.
     */
    public final long publishAtMs;

    public Entry(@NonNull String entryId, long updateId, long receivedAtMs,
                 @NonNull String preview, @Nullable String mediaKind,
                 @NonNull JSONObject raw,
                 @Nullable JSONArray albumParts,
                 @Nullable String mediaGroupId,
                 boolean published, long publishedAtMs, long publishAtMs) {
      this.entryId = entryId;
      this.updateId = updateId;
      this.receivedAtMs = receivedAtMs;
      this.preview = preview;
      this.mediaKind = mediaKind;
      this.raw = raw;
      this.albumParts = albumParts;
      this.mediaGroupId = mediaGroupId;
      this.published = published;
      this.publishedAtMs = publishedAtMs;
      this.publishAtMs = publishAtMs;
    }

    public int albumSize() {
      return albumParts != null ? albumParts.length() : 1;
    }

    public Entry withPublished(long atMs) {
      return new Entry(entryId, updateId, receivedAtMs, preview, mediaKind, raw,
          albumParts, mediaGroupId, true, atMs, 0L);
    }

    public Entry withPublishAt(long atMs) {
      return new Entry(entryId, updateId, receivedAtMs, preview, mediaKind, raw,
          albumParts, mediaGroupId, published, publishedAtMs, atMs);
    }

    JSONObject toJson() throws JSONException {
      JSONObject o = new JSONObject();
      o.put("entryId", entryId);
      o.put("updateId", updateId);
      o.put("receivedAtMs", receivedAtMs);
      o.put("preview", preview);
      if (mediaKind != null) o.put("mediaKind", mediaKind);
      o.put("raw", raw);
      if (albumParts != null && albumParts.length() > 0) {
        o.put("albumParts", albumParts);
      }
      if (mediaGroupId != null && !mediaGroupId.isEmpty()) {
        o.put("mediaGroupId", mediaGroupId);
      }
      if (published) o.put("published", true);
      if (publishedAtMs > 0) o.put("publishedAtMs", publishedAtMs);
      if (publishAtMs > 0) o.put("publishAtMs", publishAtMs);
      return o;
    }

    static Entry fromJson(@NonNull JSONObject o) throws JSONException {
      return new Entry(
          o.optString("entryId", String.valueOf(o.optLong("updateId", 0L))),
          o.optLong("updateId", 0L),
          o.optLong("receivedAtMs", 0L),
          o.optString("preview", ""),
          o.optString("mediaKind", null),
          o.optJSONObject("raw") != null ? o.getJSONObject("raw") : new JSONObject(),
          o.optJSONArray("albumParts"),
          o.optString("mediaGroupId", null),
          o.optBoolean("published", false),
          o.optLong("publishedAtMs", 0L),
          o.optLong("publishAtMs", 0L));
    }
  }

  // ---------------------------------------------------------------------
  //  read / write
  // ---------------------------------------------------------------------

  @NonNull
  public synchronized List<Entry> list(@NonNull String botId) {
    String raw = prefs().getString(key(botId), null);
    if (TextUtils.isEmpty(raw)) return new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(raw);
      List<Entry> out = new ArrayList<>(arr.length());
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(Entry.fromJson(o));
      }
      return out;
    } catch (Throwable t) {
      Log.w(TAG, "failed to parse pending list for " + botId, t);
      return new ArrayList<>();
    }
  }

  public synchronized int count(@NonNull String botId) {
    return list(botId).size();
  }

  /**
   * Read through the stable Telegram identity key, not just the local
   * BMChat row id. During the bot feature iterations users often
   * removed/re-added the same Telegram bot; that creates a fresh
   * {@link BotConfig#id}, while Telegram's getUpdates stream is still
   * the same token. If the poller consumed updates under one local id
   * and the user opened another duplicate row, the queue appeared empty.
   *
   * <p>This method merges the canonical token-hash queue with the legacy
   * UUID queue and writes the merged list back to the canonical key, so
   * old entries become visible after one screen refresh.
   */
  @NonNull
  public synchronized List<Entry> list(@NonNull BotConfig bot) {
    String canonical = queueKey(bot);
    List<Entry> merged = mergeEntries(listByKey(canonical), list(bot.id));
    if (!merged.isEmpty()) saveByKey(canonical, merged);
    return merged;
  }

  public synchronized int count(@NonNull BotConfig bot) {
    return list(bot).size();
  }

  /**
   * Number of entries the user can still act on — i.e. not yet
   * published. Used by {@link
   * org.thoughtcrime.securesms.bots.ui.BotsActivity} to decide whether
   * to show the "очередь: N" badge: the previous "count everything"
   * version made the badge stay at 2 even after every post had been
   * mirrored, which is the regression visible on screenshots 3 and 4.
   */
  public synchronized int pendingCount(@NonNull BotConfig bot) {
    int n = 0;
    for (Entry e : list(bot)) {
      if (!e.published) n++;
    }
    return n;
  }

  /**
   * Build an {@link Entry} from a Telegram update JSON and append it
   * to the queue for {@code botId}. Returns the newly created entry.
   */
  @NonNull
  public synchronized Entry enqueue(@NonNull String botId, @NonNull JSONObject update) {
    long updateId = update.optLong("update_id", 0L);
    String entryId = updateId > 0
        ? botId + "-" + updateId
        : botId + "-" + System.currentTimeMillis() + "-" + System.nanoTime();

    JSONObject inner = firstMessageLike(update);
    String preview = makePreview(inner);
    String media = detectMediaKind(inner);

    Entry e = new Entry(entryId, updateId, System.currentTimeMillis(),
        preview, media, update, null, null,
        false, 0L, 0L);

    List<Entry> existing = new ArrayList<>(list(botId));
    // Drop any duplicate (same updateId) so polling retries don't bloat
    // the queue.
    boolean replaced = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (old != null && updateId > 0 && old.updateId == updateId) {
        existing.set(i, preservePublishedState(old, e));
        replaced = true;
        break;
      }
    }
    if (!replaced) existing.add(e);
    save(botId, existing);
    return e;
  }

  @NonNull
  public synchronized Entry enqueue(@NonNull BotConfig bot, @NonNull JSONObject update) {
    return enqueueByKey(queueKey(bot), bot.id, update);
  }

  /**
   * Append a Telegram media-group ("album") as a single queue entry.
   * The entry can later be published or dropped atomically — a click
   * on "Опубликовать" sends every part in order via
   * {@link TelegramMessageDispatcher#publishAlbum(java.util.List)}.
   *
   * <p>Idempotent on {@code mediaGroupId} when it is set: re-enqueueing
   * the same album (e.g. a getUpdates retry) replaces the previous
   * entry instead of duplicating it.
   */
  @NonNull
  public synchronized Entry enqueueAlbum(@NonNull String botId,
                                          @NonNull java.util.List<JSONObject> parts,
                                          @Nullable String mediaGroupId) {
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("enqueueAlbum needs ≥ 1 part");
    }
    JSONObject first = parts.get(0);
    long firstUpdateId = first.optLong("update_id", 0L);
    String entryId;
    if (mediaGroupId != null && !mediaGroupId.isEmpty()) {
      entryId = botId + "-album-" + mediaGroupId;
    } else if (firstUpdateId > 0) {
      entryId = botId + "-album-" + firstUpdateId;
    } else {
      entryId = botId + "-album-" + System.currentTimeMillis();
    }

    JSONArray arr = new JSONArray();
    for (JSONObject p : parts) arr.put(p);

    JSONObject firstInner = firstMessageLike(first);
    String preview = makePreview(firstInner);
    String mediaKind = detectMediaKind(firstInner);

    Entry e = new Entry(entryId, firstUpdateId, System.currentTimeMillis(),
        preview, mediaKind, first, arr, mediaGroupId,
        false, 0L, 0L);

    List<Entry> existing = new ArrayList<>(list(botId));
    boolean replaced = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (entryId.equals(old.entryId)) {
        existing.set(i, e);
        replaced = true;
        break;
      }
    }
    if (!replaced) existing.add(e);
    save(botId, existing);
    return e;
  }

  @NonNull
  public synchronized Entry enqueueAlbum(@NonNull BotConfig bot,
                                         @NonNull java.util.List<JSONObject> parts,
                                         @Nullable String mediaGroupId) {
    return enqueueAlbumByKey(queueKey(bot), bot.id, parts, mediaGroupId);
  }

  // ---------------------------------------------------------------------
  //  state mutators (called from dispatcher / scheduler / UI)
  // ---------------------------------------------------------------------

  /** Mark the entry as published right now; clears any pending schedule. */
  public synchronized void markPublished(@NonNull String botId, @NonNull String entryId) {
    List<Entry> existing = new ArrayList<>(list(botId));
    boolean changed = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (!entryId.equals(old.entryId)) continue;
      existing.set(i, old.withPublished(System.currentTimeMillis()));
      changed = true;
      break;
    }
    if (changed) save(botId, existing);
  }

  public synchronized void markPublished(@NonNull BotConfig bot, @NonNull String entryId) {
    markPublishedByKey(queueKey(bot), entryId);
    markPublishedByKey(key(bot.id), entryId);
  }

  /** Schedule the entry for publication at the given wall-clock time. */
  public synchronized void schedule(@NonNull String botId, @NonNull String entryId, long whenMs) {
    List<Entry> existing = new ArrayList<>(list(botId));
    boolean changed = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (!entryId.equals(old.entryId)) continue;
      if (old.published) return;
      existing.set(i, old.withPublishAt(whenMs));
      changed = true;
      break;
    }
    if (changed) save(botId, existing);
  }

  public synchronized void schedule(@NonNull BotConfig bot, @NonNull String entryId, long whenMs) {
    scheduleByKey(queueKey(bot), entryId, whenMs);
    scheduleByKey(key(bot.id), entryId, whenMs);
  }

  /**
   * Returns every entry that should now be published according to its
   * {@link Entry#publishAtMs} — i.e. not already published and the
   * scheduled time has arrived. Used by the scheduler tick in
   * {@link BotPollManager}.
   */
  @NonNull
  public synchronized List<Entry> dueEntries(@NonNull String botId, long nowMs) {
    List<Entry> all = list(botId);
    List<Entry> out = new ArrayList<>();
    for (Entry e : all) {
      if (e.published) continue;
      if (e.publishAtMs <= 0) continue;
      if (e.publishAtMs > nowMs) continue;
      out.add(e);
    }
    return out;
  }

  @NonNull
  public synchronized List<Entry> dueEntries(@NonNull BotConfig bot, long nowMs) {
    List<Entry> all = list(bot);
    List<Entry> out = new ArrayList<>();
    for (Entry e : all) {
      if (e.published) continue;
      if (e.publishAtMs <= 0) continue;
      if (e.publishAtMs > nowMs) continue;
      out.add(e);
    }
    return out;
  }

  public synchronized void remove(@NonNull String botId, @NonNull String entryId) {
    List<Entry> existing = new ArrayList<>(list(botId));
    int n = existing.size();
    existing.removeIf(e -> entryId.equals(e.entryId));
    if (existing.size() != n) save(botId, existing);
  }

  public synchronized void remove(@NonNull BotConfig bot, @NonNull String entryId) {
    removeByKey(queueKey(bot), entryId);
    removeByKey(key(bot.id), entryId);
  }

  public synchronized void clear(@NonNull String botId) {
    prefs().edit().remove(key(botId)).apply();
  }

  public synchronized void clear(@NonNull BotConfig bot) {
    prefs().edit().remove(queueKey(bot)).remove(key(bot.id)).apply();
  }

  private void save(@NonNull String botId, @NonNull List<Entry> entries) {
    saveByKey(key(botId), entries);
  }

  private void saveByKey(@NonNull String prefKey, @NonNull List<Entry> entries) {
    if (entries.isEmpty()) {
      prefs().edit().remove(prefKey).commit();
      return;
    }
    JSONArray arr = new JSONArray();
    for (Entry e : entries) {
      try { arr.put(e.toJson()); } catch (JSONException ignored) {}
    }
    prefs().edit().putString(prefKey, arr.toString()).commit();
  }

  private static String key(@NonNull String botId) { return "bot." + botId; }

  private synchronized List<Entry> listByKey(@NonNull String prefKey) {
    String raw = prefs().getString(prefKey, null);
    if (TextUtils.isEmpty(raw)) return new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(raw);
      List<Entry> out = new ArrayList<>(arr.length());
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        out.add(Entry.fromJson(o));
      }
      return out;
    } catch (Throwable t) {
      Log.w(TAG, "failed to parse pending list for key " + prefKey, t);
      return new ArrayList<>();
    }
  }

  private Entry enqueueByKey(@NonNull String prefKey,
                             @NonNull String idPrefix,
                             @NonNull JSONObject update) {
    long updateId = update.optLong("update_id", 0L);
    String entryId = updateId > 0
        ? idPrefix + "-" + updateId
        : idPrefix + "-" + System.currentTimeMillis() + "-" + System.nanoTime();

    JSONObject inner = firstMessageLike(update);
    String preview = makePreview(inner);
    String media = detectMediaKind(inner);

    Entry e = new Entry(entryId, updateId, System.currentTimeMillis(),
        preview, media, update, null, null,
        false, 0L, 0L);

    List<Entry> existing = new ArrayList<>(listByKey(prefKey));
    boolean replaced = false;
    for (int i = 0; i < existing.size(); i++) {
      if (sameLogicalPost(existing.get(i), e)) {
        existing.set(i, preservePublishedState(existing.get(i), e));
        replaced = true;
        break;
      }
    }
    if (!replaced) existing.add(e);
    saveByKey(prefKey, existing);
    return e;
  }

  private Entry enqueueAlbumByKey(@NonNull String prefKey,
                                  @NonNull String idPrefix,
                                  @NonNull java.util.List<JSONObject> parts,
                                  @Nullable String mediaGroupId) {
    if (parts.isEmpty()) {
      throw new IllegalArgumentException("enqueueAlbum needs >= 1 part");
    }
    JSONObject first = parts.get(0);
    long firstUpdateId = first.optLong("update_id", 0L);
    String entryId;
    if (mediaGroupId != null && !mediaGroupId.isEmpty()) {
      entryId = idPrefix + "-album-" + mediaGroupId;
    } else if (firstUpdateId > 0) {
      entryId = idPrefix + "-album-" + firstUpdateId;
    } else {
      entryId = idPrefix + "-album-" + System.currentTimeMillis();
    }

    JSONArray arr = new JSONArray();
    for (JSONObject p : parts) arr.put(p);

    JSONObject firstInner = firstMessageLike(first);
    String preview = makePreview(firstInner);
    String mediaKind = detectMediaKind(firstInner);

    Entry e = new Entry(entryId, firstUpdateId, System.currentTimeMillis(),
        preview, mediaKind, first, arr, mediaGroupId,
        false, 0L, 0L);

    List<Entry> existing = new ArrayList<>(listByKey(prefKey));
    boolean replaced = false;
    for (int i = 0; i < existing.size(); i++) {
      if (sameLogicalPost(existing.get(i), e)) {
        existing.set(i, preservePublishedState(existing.get(i), e));
        replaced = true;
        break;
      }
    }
    if (!replaced) existing.add(e);
    saveByKey(prefKey, existing);
    return e;
  }

  private void markPublishedByKey(@NonNull String prefKey, @NonNull String entryId) {
    List<Entry> existing = new ArrayList<>(listByKey(prefKey));
    boolean changed = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (!entryId.equals(old.entryId)) continue;
      existing.set(i, old.withPublished(System.currentTimeMillis()));
      changed = true;
      break;
    }
    if (changed) saveByKey(prefKey, existing);
  }

  private void scheduleByKey(@NonNull String prefKey, @NonNull String entryId, long whenMs) {
    List<Entry> existing = new ArrayList<>(listByKey(prefKey));
    boolean changed = false;
    for (int i = 0; i < existing.size(); i++) {
      Entry old = existing.get(i);
      if (!entryId.equals(old.entryId)) continue;
      if (old.published) return;
      existing.set(i, old.withPublishAt(whenMs));
      changed = true;
      break;
    }
    if (changed) saveByKey(prefKey, existing);
  }

  private void removeByKey(@NonNull String prefKey, @NonNull String entryId) {
    List<Entry> existing = new ArrayList<>(listByKey(prefKey));
    int n = existing.size();
    existing.removeIf(e -> entryId.equals(e.entryId));
    if (existing.size() != n) saveByKey(prefKey, existing);
  }

  private static List<Entry> mergeEntries(@NonNull List<Entry> a, @NonNull List<Entry> b) {
    Map<String, Entry> out = new LinkedHashMap<>();
    for (Entry e : a) out.put(logicalKey(e), e);
    for (Entry e : b) {
      String k = logicalKey(e);
      Entry old = out.get(k);
      out.put(k, old == null ? e : preservePublishedState(old, e));
    }
    return new ArrayList<>(out.values());
  }

  private static boolean sameLogicalPost(@NonNull Entry a, @NonNull Entry b) {
    return logicalKey(a).equals(logicalKey(b));
  }

  private static String logicalKey(@NonNull Entry e) {
    if (e.mediaGroupId != null && !e.mediaGroupId.isEmpty()) {
      return "album:" + e.mediaGroupId;
    }
    if (e.updateId > 0) return "update:" + e.updateId;
    return "entry:" + e.entryId;
  }

  private static Entry preservePublishedState(@NonNull Entry old, @NonNull Entry fresh) {
    if (old.published) return fresh.withPublished(old.publishedAtMs);
    if (old.publishAtMs > 0) return fresh.withPublishAt(old.publishAtMs);
    return fresh;
  }

  private static String queueKey(@NonNull BotConfig bot) {
    if (bot.token != null && !bot.token.isEmpty()) {
      return "bot.token." + sha256Hex(bot.token);
    }
    return key(bot.id);
  }

  private static String sha256Hex(@NonNull String value) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] bytes = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        String h = Integer.toHexString(b & 0xff);
        if (h.length() == 1) sb.append('0');
        sb.append(h);
      }
      return sb.toString();
    } catch (Throwable t) {
      return String.valueOf(value.hashCode());
    }
  }

  // ---------------------------------------------------------------------
  //  preview helpers (mirror of TelegramMessageDispatcher#firstMessageLike)
  // ---------------------------------------------------------------------

  private static @Nullable JSONObject firstMessageLike(@NonNull JSONObject update) {
    String[] keys = new String[] {
        "message", "channel_post",
        "business_message", "edited_business_message",
        "edited_message", "edited_channel_post"
    };
    for (String k : keys) {
      JSONObject v = update.optJSONObject(k);
      if (v != null) return v;
    }
    return null;
  }

  private static @NonNull String makePreview(@Nullable JSONObject m) {
    if (m == null) return "";
    String text = m.optString("text", "");
    if (text.isEmpty()) text = m.optString("caption", "");
    if (text.isEmpty()) {
      String kind = detectMediaKind(m);
      if (kind != null) return "[" + kind + "]";
      return "";
    }
    text = text.replace('\n', ' ').trim();
    if (text.length() > 140) text = text.substring(0, 137) + "…";
    return text;
  }

  private static @Nullable String detectMediaKind(@Nullable JSONObject m) {
    if (m == null) return null;
    if (m.has("photo")) return "photo";
    if (m.has("video")) return "video";
    if (m.has("animation")) return "animation";
    if (m.has("document")) return "document";
    if (m.has("audio")) return "audio";
    if (m.has("voice")) return "voice";
    if (m.has("video_note")) return "video_note";
    if (m.has("sticker")) return "sticker";
    if (m.has("poll")) return "poll";
    if (m.has("location")) return "location";
    if (m.has("contact")) return "contact";
    return null;
  }
}
