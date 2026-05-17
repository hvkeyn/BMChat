package org.thoughtcrime.securesms.database.loaders;

import android.content.Context;
import android.util.Patterns;
import androidx.annotation.NonNull;
import androidx.loader.content.AsyncTaskLoader;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * BMChat 2.49.80 (Phase 3): Scans messages in a single chat (or every chat
 * when {@code chatId == 0}) and extracts every URL found in either the
 * message body or any quoted text. The result feeds the Telegram-style
 * Links tab in the Shared Media browser.
 *
 * <p>The loader purposefully only inspects text-bearing messages and reuses
 * Android's {@link Patterns#WEB_URL} so the scanner stays cheap even for
 * very long chat histories.
 */
public class BMChatLinksLoader extends AsyncTaskLoader<List<BMChatLinksLoader.LinkEntry>> {

  /** One discovered link, paired with the originating message metadata. */
  public static class LinkEntry {
    public final String url;
    public final String context;
    public final int msgId;
    public final int chatId;
    public final long timestamp;
    public final String senderName;

    public LinkEntry(
        @NonNull String url,
        @NonNull String context,
        int msgId,
        int chatId,
        long timestamp,
        @NonNull String senderName) {
      this.url = url;
      this.context = context;
      this.msgId = msgId;
      this.chatId = chatId;
      this.timestamp = timestamp;
      this.senderName = senderName;
    }
  }

  // Android's WEB_URL pattern matches "example.com" without a scheme;
  // we only collect matches that actually start with a scheme so the user
  // never sees bare-word false positives such as e-mail addresses.
  private static final Pattern URL_PATTERN = Patterns.WEB_URL;
  private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://");

  private final int chatId;

  public BMChatLinksLoader(@NonNull Context context, int chatId) {
    super(context);
    this.chatId = chatId;
    onContentChanged();
  }

  @Override
  protected void onStartLoading() {
    if (takeContentChanged()) {
      forceLoad();
    }
  }

  @Override
  protected void onStopLoading() {
    cancelLoad();
  }

  @Override
  public List<LinkEntry> loadInBackground() {
    List<LinkEntry> result = new ArrayList<>();
    if (chatId == -1) {
      return result;
    }

    DcContext dc = DcHelper.getContext(getContext());
    Set<String> dedupe = new HashSet<>();
    if (chatId == 0) {
      // Global media entry point: walk every chat once. The dedupe map is
      // keyed by URL + message id, so the cost stays linear in messages.
      com.b44t.messenger.DcChatlist chatlist = dc.getChatlist(0, null, 0);
      int count = chatlist.getCnt();
      for (int i = 0; i < count; i++) {
        int cid = chatlist.getChatId(i);
        int[] msgs = dc.getChatMsgs(cid, 0, 0);
        extractFromMessages(dc, msgs, result, dedupe);
      }
      return result;
    }

    int[] msgs = dc.getChatMsgs(chatId, 0, 0);
    extractFromMessages(dc, msgs, result, dedupe);
    return result;
  }

  private void extractFromMessages(
      DcContext dc, int[] msgIds, List<LinkEntry> out, Set<String> dedupe) {
    // Walk messages newest-first so the resulting list mirrors what users
    // expect in Telegram: the latest link they shared sits on top.
    for (int i = msgIds.length - 1; i >= 0; i--) {
      DcMsg msg = dc.getMsg(msgIds[i]);
      String text = msg.getText();
      if (text == null || text.isEmpty()) {
        continue;
      }
      Matcher m = URL_PATTERN.matcher(text);
      while (m.find()) {
        String candidate = m.group();
        if (candidate == null) continue;
        if (!HAS_SCHEME.matcher(candidate).find()) {
          // Promote schemeless matches that still look like links so the
          // user can tap them, but keep dedupe stable by the canonical form.
          candidate = "https://" + candidate;
        }
        String key = candidate.toLowerCase();
        if (dedupe.add(key + "#" + msg.getId())) {
          String sender = msg.getSenderName(dc.getContact(msg.getFromId()));
          out.add(
              new LinkEntry(
                  candidate, text, msg.getId(), msg.getChatId(), msg.getTimestamp(), sender));
        }
      }
    }
  }
}
