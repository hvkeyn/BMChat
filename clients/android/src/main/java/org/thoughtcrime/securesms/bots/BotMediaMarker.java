package org.thoughtcrime.securesms.bots;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embeds and extracts BMChat-only "inline video" metadata inside the
 * caption of a regular image message.
 *
 * <p>Telegram videos larger than 20 MB cannot be downloaded through
 * the cloud Bot API. Instead of dropping them, BMChat fetches the
 * video <em>thumbnail</em> (always small enough), publishes the
 * thumbnail as a normal {@code DC_MSG_IMAGE}, and stamps a hidden
 * marker into the caption so that {@link
 * org.thoughtcrime.securesms.ConversationItem ConversationItem}
 * can:
 *
 * <ul>
 *   <li>render the {@code play_overlay} circle on top of the
 *       thumbnail (Telegram-style "tap to play");</li>
 *   <li>intercept the thumbnail click and route to {@link
 *       org.thoughtcrime.securesms.bots.ui.TgMediaPlayerActivity}
 *       with a Range-aware streaming URL;</li>
 *   <li>strip the marker from the visible body text — the user
 *       should never see the raw token in chat.</li>
 * </ul>
 *
 * <p>The marker is wrapped in zero-width spaces so it survives the
 * underlying mime store (which is just a string in {@code DcMsg.text})
 * without ever rendering as visible glyphs even if a downstream
 * client doesn't know about BMChat.
 *
 * <p>Format (single line, kept short on purpose):
 * <pre>
 *   \u200B[bmchat:tgvideo url=&lt;proxyUrl&gt;;size=&lt;bytes&gt;;dur=&lt;seconds&gt;;mime=&lt;mime&gt;]\u200B
 * </pre>
 *
 * <p>This class is intentionally pure / static so it can be reused
 * from the dispatcher (write side) and the view layer (read side)
 * without dragging in Android dependencies.
 */
public final class BotMediaMarker {

  /** Zero-width space wrap. Hidden on every reasonable renderer. */
  private static final String WRAP = "\u200B";
  private static final String OPEN = WRAP + "[bmchat:tgvideo ";
  private static final String CLOSE = "]" + WRAP;

  /** Match either with or without the zero-width wrapping so we are
   *  forgiving when an upstream pipeline strips invisibles. */
  private static final Pattern MARKER = Pattern.compile(
      "\\u200B?\\[bmchat:tgvideo ([^\\]]+)]\\u200B?",
      Pattern.CASE_INSENSITIVE);

  private BotMediaMarker() {}

  /** Lightweight value object — just enough to drive the in-chat
   *  inline player without touching the rest of the message. */
  public static final class Info {
    public final @NonNull String url;
    public final long sizeBytes;       // 0 if unknown
    public final int durationSeconds;  // 0 if unknown
    public final @Nullable String mime;

    Info(@NonNull String url, long sizeBytes, int durationSeconds, @Nullable String mime) {
      this.url = url;
      this.sizeBytes = sizeBytes;
      this.durationSeconds = durationSeconds;
      this.mime = mime;
    }
  }

  /** Serialize a marker for embedding into a caption. Result already
   *  includes the zero-width wrap, so the caller only needs to
   *  concatenate it with the rest of the body. */
  public static @NonNull String build(@NonNull String url,
                                      long sizeBytes,
                                      int durationSeconds,
                                      @Nullable String mime) {
    StringBuilder sb = new StringBuilder(OPEN);
    sb.append("url=").append(url);
    if (sizeBytes > 0) sb.append(";size=").append(sizeBytes);
    if (durationSeconds > 0) sb.append(";dur=").append(durationSeconds);
    if (mime != null && !mime.isEmpty()) sb.append(";mime=").append(mime);
    sb.append(CLOSE);
    return sb.toString();
  }

  /** Find and parse the first marker present in {@code caption}.
   *  Returns {@code null} if none is found. */
  public static @Nullable Info parse(@Nullable String caption) {
    if (caption == null || caption.isEmpty()) return null;
    Matcher m = MARKER.matcher(caption);
    if (!m.find()) return null;
    String body = m.group(1);
    if (body == null) return null;

    String url = null;
    long size = 0;
    int dur = 0;
    String mime = null;
    for (String kv : body.split(";")) {
      int eq = kv.indexOf('=');
      if (eq <= 0) continue;
      String k = kv.substring(0, eq).trim();
      String v = kv.substring(eq + 1).trim();
      if (v.isEmpty()) continue;
      switch (k) {
        case "url":  url = v; break;
        case "size": size = parseLongSafe(v); break;
        case "dur":  dur = (int) parseLongSafe(v); break;
        case "mime": mime = v; break;
        default: /* ignore unknown keys, forward-compat */ break;
      }
    }
    if (url == null) return null;
    return new Info(url, size, dur, mime);
  }

  /** Strip the marker out of a caption, collapsing any extra
   *  whitespace it leaves behind. Used by the view layer before
   *  putting the caption into the body TextView. */
  public static @NonNull String strip(@Nullable String caption) {
    if (caption == null || caption.isEmpty()) return "";
    String out = MARKER.matcher(caption).replaceAll("");
    // The marker is usually placed on its own line; clean up the
    // double newline we'd otherwise leave behind.
    out = out.replaceAll("\\n{3,}", "\n\n").trim();
    return out;
  }

  private static long parseLongSafe(String s) {
    try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
  }
}
