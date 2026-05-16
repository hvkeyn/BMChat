package org.thoughtcrime.securesms.util;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inline Markdown rendering for chat messages.
 *
 * <p>Telegram bots forward posts that carry rich-text formatting via
 * Telegram's {@code entities} array. We translate that array into
 * a strict subset of GitHub-style Markdown in {@link
 * org.thoughtcrime.securesms.bots.TelegramFormatter}, then this class
 * applies the Markdown markers as Android {@link Spannable} spans so
 * the actual chat bubble renders them with bold/italic/strike/quote
 * styling instead of leaking the raw asterisks/braces into the post
 * body (which is the regression the user reported on screenshots
 * 1–2 from May 9).
 *
 * <p>The supported subset is intentionally narrow so we never
 * misinterpret prose typed by humans. We require:
 * <ul>
 *   <li>{@code **bold**} — exactly two stars, no whitespace adjacent
 *       to the stars, content cannot span a blank line</li>
 *   <li>{@code __italic__} — exactly two underscores, same rules.
 *       The single-underscore form is intentionally NOT recognised
 *       because it collides with words like {@code two_words}.</li>
 *   <li>{@code ~~strike~~}</li>
 *   <li>{@code ||spoiler||} — rendered as same-color foreground over
 *       background, click the span to reveal</li>
 *   <li><code>`code`</code> — single backticks, monospace + faint bg</li>
 *   <li><code>```\ncode block\n```</code> — fenced code block</li>
 *   <li>{@code [label](http://url)} — converted to a {@link URLSpan}
 *       so the existing {@link Linkifier#replaceURLSpan(android.text.SpannableString)}
 *       pipeline picks it up and turns it into a click-to-copy link</li>
 *   <li>Lines starting with {@code "> "} get a {@link QuoteSpan} +
 *       lighter foreground colour, rendered as a left-bar quote</li>
 * </ul>
 *
 * <p>Always returns a {@link SpannableStringBuilder} (never null);
 * empty/null input yields an empty builder. Marker characters that
 * matched a span are removed from the resulting text — the user only
 * sees the styled content.
 */
public final class MessageMarkdown {

  private MessageMarkdown() {}

  // Order matters: we walk these patterns left-to-right against the
  // current builder, find the leftmost+strictest match, apply spans,
  // and recompute. Patterns wrap their content in capture group 1.

  /** Triple-backtick fenced code block: ``` ... ``` (multiline). */
  private static final Pattern PRE = Pattern.compile(
      "(?ms)^```\\s*\\n([\\s\\S]+?)\\n```\\s*$");
  /** Inline code: `foo` (single backticks, no newline). */
  private static final Pattern CODE = Pattern.compile(
      "`([^`\\n]+)`");
  /** Bold: **foo** (no whitespace at boundaries, no newline inside). */
  private static final Pattern BOLD = Pattern.compile(
      "\\*\\*(\\S(?:[^*\\n]*\\S)?)\\*\\*");
  /** Italic: __foo__ (double underscore, same rules). */
  private static final Pattern ITALIC = Pattern.compile(
      "__(\\S(?:[^_\\n]*\\S)?)__");
  /** Strike: ~~foo~~ */
  private static final Pattern STRIKE = Pattern.compile(
      "~~(\\S(?:[^~\\n]*\\S)?)~~");
  /** Underline: ++foo++. Picked because Telegram exposes underline as
   *  its own entity type but Markdown has no canonical syntax — we
   *  pair it with the strike notation to keep the user-typed format
   *  symmetric (~~strike~~ vs ++underline++). */
  private static final Pattern UNDERLINE = Pattern.compile(
      "\\+\\+(\\S(?:[^+\\n]*\\S)?)\\+\\+");
  /** Spoiler: ||foo|| */
  private static final Pattern SPOILER = Pattern.compile(
      "\\|\\|(\\S(?:[^|\\n]*\\S)?)\\|\\|");
  /** Inline link: [label](url) — url must look like a URL, not a free string. */
  private static final Pattern LINK = Pattern.compile(
      "\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)");
  /** Blockquote line starting with "> ". */
  private static final Pattern QUOTE_LINE = Pattern.compile(
      "(?m)^> ?(.*)$");

  /**
   * Apply Markdown styling in-place. Returns the same builder for
   * easy chaining with {@link Linkifier#linkify(android.text.SpannableString)}-like
   * passes. Safe to call on already-styled text.
   */
  public static @NonNull SpannableStringBuilder apply(@NonNull CharSequence text) {
    SpannableStringBuilder sb = text instanceof SpannableStringBuilder
        ? (SpannableStringBuilder) text
        : new SpannableStringBuilder(text);

    // Order is important: handle fenced PRE first so backticks inside
    // it never get reinterpreted as inline code, then inline code
    // (which protects backtick-wrapped runs from later matching),
    // then explicit links, then style markers, finally quote lines.
    applyAll(sb, PRE, span -> new CharacterStyle[] {
        new TypefaceSpan("monospace"),
        new BackgroundColorSpan(0x22808080),
        new RelativeSizeSpan(0.95f)
    });
    applyAll(sb, CODE, span -> new CharacterStyle[] {
        new TypefaceSpan("monospace"),
        new BackgroundColorSpan(0x22808080)
    });
    applyAllLinks(sb);
    applyAll(sb, BOLD, span -> new CharacterStyle[] { new StyleSpan(Typeface.BOLD) });
    applyAll(sb, ITALIC, span -> new CharacterStyle[] { new StyleSpan(Typeface.ITALIC) });
    applyAll(sb, STRIKE, span -> new CharacterStyle[] { new StrikethroughSpan() });
    applyAll(sb, UNDERLINE, span -> new CharacterStyle[] { new UnderlineSpan() });
    applyAllSpoilers(sb);
    applyQuoteLines(sb);
    return sb;
  }

  /**
   * Generic "find leftmost match → strip the wrapper chars → set the
   * spans on the surviving content" loop. We start from offset 0 each
   * iteration because deletions shift later indices; the loop
   * terminates when no further matches exist.
   */
  private static void applyAll(@NonNull SpannableStringBuilder sb,
                               @NonNull Pattern pattern,
                               @NonNull SpanBuilder spans) {
    int from = 0;
    while (true) {
      Matcher m = pattern.matcher(sb);
      if (!m.find(from)) return;
      int outerStart = m.start();
      int outerEnd = m.end();
      int innerStart = m.start(1);
      int innerEnd = m.end(1);
      String inner = sb.subSequence(innerStart, innerEnd).toString();
      // Replace the entire match with just the inner text, then span it.
      sb.replace(outerStart, outerEnd, inner);
      int newEnd = outerStart + inner.length();
      for (CharacterStyle s : spans.build(inner)) {
        sb.setSpan(s, outerStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      from = newEnd;
      if (from > sb.length()) return;
    }
  }

  /**
   * Inline links {@code [label](url)} → {@link URLSpan} on the bare
   * label. Linkifier's {@code replaceURLSpan} pass later swaps that
   * for a {@code LongClickCopySpan} so taps copy the URL.
   */
  private static void applyAllLinks(@NonNull SpannableStringBuilder sb) {
    int from = 0;
    while (true) {
      Matcher m = LINK.matcher(sb);
      if (!m.find(from)) return;
      int outerStart = m.start();
      int outerEnd = m.end();
      String label = m.group(1);
      String url = m.group(2);
      sb.replace(outerStart, outerEnd, label);
      int newEnd = outerStart + label.length();
      sb.setSpan(new URLSpan(url), outerStart, newEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      from = newEnd;
    }
  }

  /**
   * Spoilers: render the inner text in the same colour as the
   * background so it is hidden, then a click flips the spans off and
   * reveals the content. The colour values are picked to look right
   * on both light and dark themes (mid-grey on grey).
   */
  private static void applyAllSpoilers(@NonNull SpannableStringBuilder sb) {
    int from = 0;
    while (true) {
      Matcher m = SPOILER.matcher(sb);
      if (!m.find(from)) return;
      int outerStart = m.start();
      int outerEnd = m.end();
      String inner = m.group(1);
      sb.replace(outerStart, outerEnd, inner);
      final int newEnd = outerStart + inner.length();
      final int hidden = 0xFF8A8A8A;
      final ForegroundColorSpan fg = new ForegroundColorSpan(hidden);
      final BackgroundColorSpan bg = new BackgroundColorSpan(hidden);
      ClickableSpan reveal = new ClickableSpan() {
        @Override public void onClick(@NonNull View widget) {
          // Remove the masking spans on first tap so the text becomes
          // visible. The ClickableSpan itself is kept so the area
          // still feels tappable; subsequent taps are no-ops.
          if (widget instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) widget;
            CharSequence body = tv.getText();
            if (body instanceof Spannable) {
              ((Spannable) body).removeSpan(fg);
              ((Spannable) body).removeSpan(bg);
              tv.invalidate();
            }
          }
        }
      };
      sb.setSpan(fg, outerStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(bg, outerStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(reveal, outerStart, newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      from = newEnd;
    }
  }

  /**
   * Quote blocks: each "> " line gets its own {@link QuoteSpan}
   * (vertical bar) plus a soft-grey {@link ForegroundColorSpan} so it
   * reads as a quote without us having to define a custom drawable.
   * We strip the leading "> " from the visible text.
   */
  private static void applyQuoteLines(@NonNull SpannableStringBuilder sb) {
    // Walk from the end so deletions shift only already-processed text.
    List<int[]> ranges = new ArrayList<>();
    Matcher m = QUOTE_LINE.matcher(sb);
    while (m.find()) {
      ranges.add(new int[] { m.start(), m.end(), m.start(1) });
    }
    Collections.reverse(ranges);
    for (int[] r : ranges) {
      int lineStart = r[0];
      int lineEnd = r[1];
      int contentStart = r[2];
      // Replace "> content" with just "content" so the marker glyph
      // isn't visible inside the bubble.
      String inner = sb.subSequence(contentStart, lineEnd).toString();
      sb.replace(lineStart, lineEnd, inner);
      int newEnd = lineStart + inner.length();
      sb.setSpan(new QuoteSpan(0xFF6BAED6), lineStart, newEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(new ForegroundColorSpan(0xFFAAAAAA), lineStart, newEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      sb.setSpan(new LeadingMarginSpan.Standard(20, 20), lineStart, newEnd,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
  }

  private interface SpanBuilder {
    @NonNull CharacterStyle[] build(@NonNull String inner);
  }

  // Keep the imports fail-safe even if Color.parseColor would be
  // tempting later.
  @SuppressWarnings("unused")
  private static int debugRed() { return Color.RED; }
}
