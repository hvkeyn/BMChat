package org.thoughtcrime.securesms.bots;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Render the rich-text portion of a Telegram message into BMChat-flavoured
 * Markdown.
 *
 * <p>Telegram delivers formatting as a parallel {@code entities} array on
 * top of the plain {@code text} (or {@code caption}). BMChat's chat
 * bubble cannot render HTML inline, but it now (since 2.49.27) parses
 * a strict subset of GitHub-style Markdown via {@link
 * org.thoughtcrime.securesms.util.MessageMarkdown}, which means we can
 * preserve the visual style of every Telegram entity instead of
 * collapsing them to plain text:
 *
 * <ul>
 *   <li>{@code bold} — wrapped in {@code **…**} (renders as <b>bold</b>)</li>
 *   <li>{@code italic} — wrapped in {@code __…__} (renders as <i>italic</i>)</li>
 *   <li>{@code underline} — same as italic (we have no underline span;
 *       falling back to italic keeps it visually distinct from regular
 *       text without inventing an unsupported span)</li>
 *   <li>{@code strikethrough} — wrapped in {@code ~~…~~}</li>
 *   <li>{@code spoiler} — wrapped in {@code ||…||} (renders as a
 *       same-color text-on-background block; tap to reveal)</li>
 *   <li>{@code code} — wrapped in {@code `…`} (monospace + faint bg)</li>
 *   <li>{@code pre} — fenced with triple backticks on their own line</li>
 *   <li>{@code blockquote} / {@code expandable_blockquote} — every line
 *       prefixed with {@code "> "}, mirroring Telegram's vertical-bar
 *       quote rendering</li>
 *   <li>{@code text_link} — emitted as {@code [label](url)}; both the
 *       Markdown parser and the Linkify pass strip the brackets and
 *       attach the URL as an anchor</li>
 *   <li>{@code url}, {@code mention}, {@code hashtag}, {@code cashtag},
 *       {@code email}, {@code phone_number}, {@code bot_command} —
 *       already in the source text, kept verbatim</li>
 *   <li>{@code text_mention} — emitted as {@code @username} (or the
 *       user's first_name if no username)</li>
 * </ul>
 *
 * <p>Stable across {@link CharSequence#charAt(int)} indexing — Telegram
 * documents UTF-16 code-units for offsets, which matches Java strings.
 *
 * <p>Overlapping entities are handled by sorting marks by descending
 * start offset and applying the rightmost-wins rule: e.g. a {@code
 * text_link} that itself contains a {@code bold} inside it produces
 * the link wrapper around the inner bold wrapper, both visible.
 */
public final class TelegramFormatter {

  private TelegramFormatter() {}

  public static String render(String text, JSONArray entities) {
    if (text == null) return "";
    if (entities == null || entities.length() == 0) return text;

    int n = text.length();
    List<Mark> marks = new ArrayList<>();
    for (int i = 0; i < entities.length(); i++) {
      JSONObject e = entities.optJSONObject(i);
      if (e == null) continue;
      int off = e.optInt("offset", -1);
      int len = e.optInt("length", 0);
      if (off < 0 || len <= 0 || off >= n) continue;
      int end = Math.min(off + len, n);
      String type = e.optString("type", "");
      String inner = text.substring(off, end);

      String prefix = null;
      String suffix = null;
      String replacement = null;
      switch (type) {
        case "bold":
          // Telegram entities sometimes include trailing whitespace
          // inside their offset/length range (e.g. "АВТОДОМ "). Our
          // Markdown parser refuses to match wrappers with adjacent
          // whitespace inside, so without the trim() helper the post
          // would render as a literal "**АВТОДОМ **" — the regression
          // visible on screenshot 2 from May 9. trimmedReplacement()
          // moves any leading/trailing spaces OUTSIDE the wrapper so
          // the body always satisfies the strict regex.
          replacement = trimmedReplacement(inner, "**", "**");
          break;
        case "italic":
        case "underline":
          // BMChat has no dedicated underline span — italic is the
          // closest visual match without inventing an unsupported
          // marker.
          replacement = trimmedReplacement(inner, "__", "__");
          break;
        case "strikethrough":
          replacement = trimmedReplacement(inner, "~~", "~~"); break;
        case "spoiler":
          replacement = trimmedReplacement(inner, "||", "||"); break;
        case "code":
          replacement = trimmedReplacement(inner, "`", "`"); break;
        case "pre":
          // Block code keeps newlines inside intentionally, so we
          // only need to ensure the fences sit on their own lines.
          replacement = "\n```\n" + inner + "\n```\n"; break;
        case "blockquote":
        case "expandable_blockquote":
          replacement = quoteEachLine(inner);
          break;
        case "text_link": {
          String url = e.optString("url", "");
          if (url.isEmpty()) {
            // No-op — entity has no URL, leave label as is.
          } else if (inner.equals(url) || inner.trim().equalsIgnoreCase(url.trim())) {
            replacement = url;
          } else {
            replacement = "[" + inner + "](" + url + ")";
          }
          break;
        }
        case "url":
        case "mention":
        case "hashtag":
        case "cashtag":
        case "bot_command":
        case "email":
        case "phone_number":
          // Already in text, keep verbatim.
          break;
        case "text_mention": {
          JSONObject user = e.optJSONObject("user");
          if (user != null) {
            String uname = user.optString("username", "");
            String fname = user.optString("first_name", "");
            if (!uname.isEmpty()) replacement = "@" + uname;
            else if (!fname.isEmpty()) replacement = fname;
          }
          break;
        }
        default:
          break;
      }

      if (replacement != null) {
        marks.add(new Mark(off, end, replacement, null, null));
      } else if (prefix != null) {
        // Legacy code path retained for entity types that don't need
        // edge-trim awareness (none after the bold/italic refactor,
        // but kept so future additions can fall back here cheaply).
        marks.add(new Mark(off, end, null, prefix, suffix == null ? "" : suffix));
      }
    }

    // Sort by offset descending so insertions don't shift the indices of
    // earlier marks.
    Collections.sort(marks, (a, b) -> Integer.compare(b.start, a.start));

    StringBuilder sb = new StringBuilder(text);
    for (Mark m : marks) {
      if (m.replacement != null) {
        sb.replace(m.start, m.end, m.replacement);
      } else {
        sb.insert(m.end, m.suffix);
        sb.insert(m.start, m.prefix);
      }
    }
    return sb.toString();
  }

  /** Render Telegram inline keyboard buttons as a trailing block of links. */
  public static String renderInlineKeyboard(JSONObject replyMarkup) {
    if (replyMarkup == null) return "";
    JSONArray rows = replyMarkup.optJSONArray("inline_keyboard");
    if (rows == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int r = 0; r < rows.length(); r++) {
      JSONArray row = rows.optJSONArray(r);
      if (row == null) continue;
      for (int c = 0; c < row.length(); c++) {
        JSONObject btn = row.optJSONObject(c);
        if (btn == null) continue;
        String text = btn.optString("text", "");
        String url = btn.optString("url", "");
        String callbackData = btn.optString("callback_data", "");
        if (text.isEmpty()) continue;
        sb.append("\n• ").append(text);
        if (!url.isEmpty()) {
          sb.append(" → ").append(url);
        } else if (!callbackData.isEmpty()) {
          sb.append(" (callback)");
        }
      }
    }
    return sb.toString();
  }

  /**
   * Wrap {@code inner} in {@code prefix}/{@code suffix} but move any
   * leading/trailing whitespace outside the wrapper so the resulting
   * Markdown matches our strict parser:
   *
   * <pre>
   *   trimmedReplacement("АВТОДОМ ", "**", "**")  -> "**АВТОДОМ** "
   *   trimmedReplacement(" word",    "__", "__")  -> " __word__"
   *   trimmedReplacement("   ",      "**", "**")  -> "   "  (no-op)
   * </pre>
   *
   * <p>Returns the literal whitespace alone when {@code inner} is
   * blank — there is nothing to style and emitting empty
   * {@code ****} would just confuse the parser.
   */
  private static String trimmedReplacement(String inner, String prefix, String suffix) {
    if (inner == null || inner.isEmpty()) return "";
    int leftWs = 0;
    while (leftWs < inner.length() && Character.isWhitespace(inner.charAt(leftWs))) leftWs++;
    int rightWs = inner.length();
    while (rightWs > leftWs && Character.isWhitespace(inner.charAt(rightWs - 1))) rightWs--;
    if (leftWs >= rightWs) return inner; // all whitespace
    String left = inner.substring(0, leftWs);
    String mid = inner.substring(leftWs, rightWs);
    String right = inner.substring(rightWs);
    return left + prefix + mid + suffix + right;
  }

  /**
   * Prefix every line of a Telegram blockquote with the standard
   * Markdown {@code "> "} marker. The Markdown renderer in
   * {@link org.thoughtcrime.securesms.util.MessageMarkdown} promotes
   * such lines to a quote span (left bar + indent + lighter text).
   */
  private static String quoteEachLine(String inner) {
    String[] lines = inner.split("\n", -1);
    StringBuilder qb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) qb.append('\n');
      qb.append("> ").append(lines[i]);
    }
    return qb.toString();
  }

  private static final class Mark {
    final int start;
    final int end;
    final String replacement;
    final String prefix;
    final String suffix;

    Mark(int start, int end, String replacement, String prefix, String suffix) {
      this.start = start;
      this.end = end;
      this.replacement = replacement;
      this.prefix = prefix;
      this.suffix = suffix;
    }
  }
}
