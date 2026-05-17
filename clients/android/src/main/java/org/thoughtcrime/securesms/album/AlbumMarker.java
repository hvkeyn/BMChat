package org.thoughtcrime.securesms.album;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embeds and extracts BMChat-only "media album" metadata inside the
 * caption of a regular image / video / file message.
 *
 * <p>BMChat lets the user pick several photos and videos at once
 * (Telegram-style multi-select). The Delta Chat core has no native
 * concept of "media groups", so each picked file goes out as its own
 * {@link com.b44t.messenger.DcMsg DcMsg}. To still give the receiver
 * the impression of one logical album we stamp every outgoing message
 * with the same {@code album_id} plus its position inside the album,
 * and the renderer then:
 *
 * <ul>
 *   <li>strips the marker from the visible caption text
 *       (the user never sees the raw token),</li>
 *   <li>shows a small "Альбом N/M" badge above the bubble so the
 *       receiver instantly understands these messages belong
 *       together.</li>
 * </ul>
 *
 * <p>The marker is wrapped in zero-width spaces so it survives the
 * underlying mime store (which is just a string in {@code DcMsg.text})
 * without ever rendering as visible glyphs even if a downstream
 * client doesn't know about BMChat.
 *
 * <p>Format (single line, kept short on purpose):
 *
 * <pre>
 *   \u200B[bmchat:album id=&lt;album-id&gt;;idx=&lt;1-based&gt;;total=&lt;n&gt;]\u200B
 * </pre>
 *
 * <p>The marker is purely additive: legacy clients (older BMChat /
 * plain Delta Chat / web mail) ignore it and just see N independent
 * messages, exactly the way they did before this feature was added.
 *
 * <p>This class is intentionally pure / static so it can be reused
 * from the send path and the view layer without dragging in Android
 * dependencies.
 */
public final class AlbumMarker {

  /** Zero-width space wrap. Hidden on every reasonable renderer. */
  private static final String WRAP = "\u200B";
  private static final String OPEN = WRAP + "[bmchat:album ";
  private static final String CLOSE = "]" + WRAP;

  /** Match with or without zero-width wrapping so we are forgiving
   *  when an upstream pipeline strips invisibles (some SMTP MTAs do). */
  private static final Pattern MARKER =
      Pattern.compile("\\u200B?\\[bmchat:album ([^\\]]+)]\\u200B?", Pattern.CASE_INSENSITIVE);

  private AlbumMarker() {}

  /** Lightweight value object describing a position inside an album. */
  public static final class Info {
    public final String albumId;
    public final int index; // 1-based
    public final int total;

    public Info(String albumId, int index, int total) {
      this.albumId = albumId;
      this.index = index;
      this.total = total;
    }
  }

  /** Returns the supplied caption with the album marker appended. */
  public static @NonNull String append(@Nullable String caption, @NonNull Info info) {
    StringBuilder sb = new StringBuilder();
    if (caption != null && !caption.isEmpty()) {
      sb.append(caption);
      // newline between user-visible caption and the invisible marker
      // so a future fallback renderer (one that doesn't strip the
      // marker) does not glue the marker to the caption text.
      sb.append('\n');
    }
    sb.append(OPEN);
    sb.append("id=").append(escape(info.albumId));
    sb.append(";idx=").append(info.index);
    sb.append(";total=").append(info.total);
    sb.append(CLOSE);
    return sb.toString();
  }

  /** Returns the parsed marker from the supplied text, or {@code null} if absent / malformed. */
  public static @Nullable Info parse(@Nullable String text) {
    if (text == null || text.isEmpty() || !text.contains("[bmchat:album ")) {
      return null;
    }
    Matcher m = MARKER.matcher(text);
    if (!m.find()) return null;
    String body = m.group(1);
    if (body == null) return null;
    String albumId = null;
    int idx = -1;
    int total = -1;
    for (String part : body.split(";")) {
      part = part.trim();
      int eq = part.indexOf('=');
      if (eq <= 0) continue;
      String key = part.substring(0, eq);
      String value = part.substring(eq + 1);
      switch (key) {
        case "id":
          albumId = unescape(value);
          break;
        case "idx":
          idx = parseSafeInt(value);
          break;
        case "total":
          total = parseSafeInt(value);
          break;
      }
    }
    if (albumId == null || albumId.isEmpty() || idx <= 0 || total <= 0) return null;
    if (idx > total) return null;
    return new Info(albumId, idx, total);
  }

  /** Returns {@code text} with any album marker stripped. */
  public static @NonNull String strip(@Nullable String text) {
    if (text == null || text.isEmpty()) return "";
    if (!text.contains("[bmchat:album ")) return text;
    String stripped = MARKER.matcher(text).replaceAll("");
    // Remove the trailing newline we inserted in {@link #append} so the
    // visible caption ends cleanly (single trailing newline only).
    while (stripped.endsWith("\n") || stripped.endsWith("\r")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  private static int parseSafeInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String escape(String value) {
    // Album ids are short alphanumeric tokens we generate ourselves,
    // but be defensive in case a future caller sneaks "]" or ";" in.
    return value.replace("]", "_").replace(";", "_");
  }

  private static String unescape(String value) {
    return value;
  }

  // ─────────────────────────────────────────────────────────────────────
  //  Live position recompute
  // ─────────────────────────────────────────────────────────────────────
  //
  // Once the user starts deleting individual photos out of an album,
  // the original {@code idx} / {@code total} stamped at send-time go
  // stale — the second photo of a 3-photo album whose first photo was
  // deleted is actually "1 / 2", not "2 / 3". We could rewrite the
  // marker on every deletion, but that requires touching the email
  // transport (the marker travels in the message text and is signed
  // already). Far simpler: the renderer consults the chat once at
  // bind-time and reports the *currently observed* position based on
  // which siblings are still alive in the local DB.
  //
  // The result is cached very briefly (1.5 s) to amortise the per-bind
  // cost across the typical ConversationAdapter frame, but invalidated
  // promptly enough that a delete + redraw cycle picks up the new
  // total on the next refresh — in practice this looks instant to the
  // user since notifyDataSetChanged already runs on the same animation
  // tick.

  /**
   * Snapshot of an album's currently-alive members in a given chat.
   * Holds the live message IDs sorted by their database row id, so the
   * "1 / N" numbering remains stable across redraws.
   */
  public static final class LiveAlbumSnapshot {
    public final String albumId;
    public final int[] msgIdsSorted;
    public final long capturedAtMs;

    public LiveAlbumSnapshot(String albumId, int[] msgIdsSorted) {
      this.albumId = albumId;
      this.msgIdsSorted = msgIdsSorted;
      this.capturedAtMs = System.currentTimeMillis();
    }

    public int total() {
      return msgIdsSorted.length;
    }

    /** 1-based index of {@code msgId} among living siblings, or -1 if absent. */
    public int indexOf(int msgId) {
      for (int i = 0; i < msgIdsSorted.length; i++) {
        if (msgIdsSorted[i] == msgId) return i + 1;
      }
      return -1;
    }
  }

  /** Per-chat per-album snapshot cache, key = "<accId>/<chatId>/<albumId>". */
  private static final java.util.Map<String, LiveAlbumSnapshot> LIVE_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static final long LIVE_CACHE_TTL_MS = 1500L;

  /**
   * Recompute the position {@code (idx, total)} of the supplied
   * {@code msgId} inside the album declared by {@code stamped}, given
   * the current state of the local DB.
   *
   * <p>Returns:
   * <ul>
   *   <li>{@code stamped} unchanged when the message is the only
   *       surviving member (caller should treat the message as
   *       non-album in that case),</li>
   *   <li>a fresh {@link Info} reflecting the live position
   *       otherwise.</li>
   * </ul>
   *
   * <p>Returns {@code null} when the message is no longer found in the
   * album (just-deleted view holder hanging around for an animation
   * tick) — caller should fall through to the standard non-album
   * render path.
   *
   * @param dcContext the Delta core context, used to enumerate chat
   *                  messages
   * @param chatId    chat the message lives in
   * @param msgId     id of the currently-bound message
   * @param stamped   marker parsed from the message's text
   */
  public static @Nullable Info livePosition(
      @NonNull com.b44t.messenger.DcContext dcContext,
      int chatId,
      int msgId,
      @NonNull Info stamped) {
    LiveAlbumSnapshot snap = liveSnapshot(dcContext, chatId, stamped.albumId);
    if (snap == null || snap.total() == 0) return null;
    int liveIdx = snap.indexOf(msgId);
    if (liveIdx < 0) return null;
    return new Info(stamped.albumId, liveIdx, snap.total());
  }

  /**
   * Public accessor for {@link #liveSnapshot}. Used by ConversationItem when
   * it needs the full ordered list of live album members (not just the
   * (idx,total) of one of them) to populate the in-bubble grid layout.
   */
  public static @Nullable LiveAlbumSnapshot liveMembers(
      @NonNull com.b44t.messenger.DcContext dcContext, int chatId, @NonNull String albumId) {
    return liveSnapshot(dcContext, chatId, albumId);
  }

  /** Internal: build (or fetch from cache) a snapshot for the album. */
  private static @Nullable LiveAlbumSnapshot liveSnapshot(
      @NonNull com.b44t.messenger.DcContext dcContext, int chatId, @NonNull String albumId) {
    String key = dcContext.getAccountId() + "/" + chatId + "/" + albumId;
    LiveAlbumSnapshot cached = LIVE_CACHE.get(key);
    long now = System.currentTimeMillis();
    if (cached != null && (now - cached.capturedAtMs) < LIVE_CACHE_TTL_MS) {
      return cached;
    }

    int[] all = dcContext.getChatMsgs(chatId, 0, 0);
    if (all == null || all.length == 0) {
      LIVE_CACHE.remove(key);
      return null;
    }

    java.util.ArrayList<Integer> matched = new java.util.ArrayList<>(all.length);
    for (int id : all) {
      if (id <= 0) continue;
      try {
        com.b44t.messenger.DcMsg m = dcContext.getMsg(id);
        if (m == null) continue;
        Info info = parse(m.getText());
        if (info == null) continue;
        if (!albumId.equals(info.albumId)) continue;
        matched.add(id);
      } catch (Throwable ignored) {
        // skip malformed rows
      }
    }
    if (matched.isEmpty()) {
      LIVE_CACHE.remove(key);
      return null;
    }
    int[] arr = new int[matched.size()];
    for (int i = 0; i < arr.length; i++) arr[i] = matched.get(i);
    LiveAlbumSnapshot fresh = new LiveAlbumSnapshot(albumId, arr);
    LIVE_CACHE.put(key, fresh);
    return fresh;
  }

  /**
   * Drop every cached snapshot. Call after operations that can mutate
   * the album set (in particular: deletions) so the next bind cycle
   * re-scans the chat.
   */
  public static void invalidateLiveCache() {
    LIVE_CACHE.clear();
  }
}
