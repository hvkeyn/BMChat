package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcLot;
import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.Util;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches incoming messages for BMChat invite links embedded in the body
 * (sent over plain e-mail by another BMChat user via
 * {@link org.thoughtcrime.securesms.qr.QrShowFragment#sendInviteByEmail}).
 *
 * When such a link is recognised the SecureJoin handshake is started
 * automatically — that handshake itself runs purely over e-mail (Autocrypt
 * encrypted IMAP/SMTP messages, no third-party server). The visible chat is
 * left in place; if the handshake fails (the link expired, or the sender
 * does not actually run BMChat) the user can still tap the link manually.
 *
 * Each invite link is processed at most once per app process; legitimate
 * duplicates are harmless because the core also de-duplicates them, but we
 * avoid the round trip and the toast spam.
 */
public final class BMChatInviteAutoAcceptor {

  private static final String TAG = "BMChatInviteAuto";

  /** Matches anything that looks like a BMChat invite URL (current host + legacy ones). */
  private static final Pattern INVITE_PATTERN;

  static {
    StringBuilder hosts = new StringBuilder();
    hosts.append(Pattern.quote(Util.INVITE_HOST));
    for (String legacy : Util.LEGACY_INVITE_HOSTS) {
      hosts.append('|').append(Pattern.quote(legacy));
    }
    String hostsAlt = hosts.toString();
    INVITE_PATTERN = Pattern.compile(
        "(https?://(?:" + hostsAlt + ")(?:/|/i|/i/|/)?#[^\\s\"'<>]+)",
        Pattern.CASE_INSENSITIVE);
  }

  private static final Set<String> seenLinks = new HashSet<>();
  private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "bmchat-invite-auto");
    t.setDaemon(true);
    return t;
  });

  private BMChatInviteAutoAcceptor() {}

  public static void onIncomingMsg(Context appContext, DcContext dcContext, int chatId, int msgId) {
    if (appContext == null || dcContext == null || msgId == 0) return;
    final DcMsg msg;
    try {
      msg = dcContext.getMsg(msgId);
    } catch (Throwable t) {
      Log.w(TAG, "getMsg failed", t);
      return;
    }
    if (msg == null) return;
    String text = msg.getText();
    if (text == null || text.isEmpty()) return;
    final String link = findInviteLink(text);
    if (link == null) return;
    synchronized (seenLinks) {
      if (!seenLinks.add(link)) return;
    }
    final String canonicalLink = Util.rewriteInviteLink(link);
    final Context ctxRef = appContext.getApplicationContext();
    worker.submit(() -> processLink(ctxRef, dcContext, canonicalLink));
  }

  private static String findInviteLink(String text) {
    Matcher m = INVITE_PATTERN.matcher(text);
    return m.find() ? m.group(1) : null;
  }

  private static void processLink(Context appContext, DcContext dcContext, String link) {
    try {
      DcLot qr = dcContext.checkQr(link);
      if (qr == null) return;
      int state = qr.getState();
      switch (state) {
        case DcContext.DC_QR_ASK_VERIFYCONTACT:
        case DcContext.DC_QR_ASK_VERIFYGROUP:
        case DcContext.DC_QR_ASK_JOIN_BROADCAST:
          int chatId = dcContext.joinSecurejoin(link);
          if (chatId > 0) {
            postToast(appContext, R.string.bmchat_invite_email_auto_accepted);
          } else {
            Log.w(TAG, "joinSecurejoin returned 0 for link " + link);
          }
          break;
        default:
          Log.d(TAG, "ignored invite link, state=" + state);
          break;
      }
    } catch (Throwable t) {
      Log.w(TAG, "processLink failed", t);
    }
  }

  private static void postToast(Context appContext, int resId) {
    new Handler(Looper.getMainLooper()).post(
        () -> Toast.makeText(appContext, resId, Toast.LENGTH_LONG).show());
  }
}
