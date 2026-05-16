package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.R;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BMChat profile broadcaster.
 *
 * <p>Delta Chat by default attaches the user's display name and avatar
 * (Autocrypt {@code From:} display name + {@code Chat-User-Avatar} header)
 * only on outgoing messages, and remembers per-contact whether the current
 * avatar has already been delivered (the core's {@code selfavatar_sent}
 * column). That works fine when the user is actively writing, but BMChat
 * users frequently change their display name or photo and then never type
 * anything — so peers keep seeing the previous avatar (or just the bare
 * e-mail address) for days or weeks.
 *
 * <p>This helper actively pushes a single short text message into every
 * relevant 1:1 chat after a profile change. The message itself is just
 * a marker, but it carries the new {@code From:} name and the new
 * {@code Chat-User-Avatar} blob, so the receiver's core updates the
 * cached display name and avatar immediately.
 *
 * <p>Group chats are skipped on purpose — broadcasting a "profile updated"
 * line into every group with a hundred members is far too spammy. Group
 * peers will pick up the new avatar/name on the next regular message the
 * user posts there.
 */
public final class BMChatProfilePublisher {

  private static final String TAG = "BMChatProfilePub";

  /** Single-thread executor: we always want updates to run sequentially. */
  private static final ExecutorService WORKER =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bmchat-profile-publish");
        t.setDaemon(true);
        return t;
      });

  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  /** Re-entrancy guard so a double-tap on "Сохранить" can't fan out twice. */
  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  /** Hard cap so we don't accidentally spam an enormous address book. */
  private static final int MAX_RECIPIENTS = 200;

  private BMChatProfilePublisher() {}

  /**
   * Broadcast the current profile (display name + avatar) to every active
   * 1:1 peer in the given account. Runs in the background.
   *
   * @param showToast {@code true} to surface a toast on the UI thread when
   *                  the broadcast is finished. Use {@code false} for
   *                  silent fan-outs (e.g. from automatic
   *                  {@code SelfavatarChanged}/{@code DC_EVENT_SELFAVATAR_CHANGED} hooks).
   */
  @AnyThread
  public static void publishToActiveContacts(@Nullable Context appContext,
                                             @Nullable DcContext dcContext,
                                             boolean showToast) {
    if (appContext == null || dcContext == null) return;
    if (!RUNNING.compareAndSet(false, true)) return;
    final Context ctxRef = appContext.getApplicationContext();
    final DcContext dcRef = dcContext;

    WORKER.submit(() -> {
      int sent = 0;
      try {
        DcChatlist list = dcRef.getChatlist(0, null, 0);
        if (list == null) return;
        Set<Integer> seenContactIds = new HashSet<>();

        for (int i = 0; i < list.getCnt() && sent < MAX_RECIPIENTS; i++) {
          int chatId = list.getChatId(i);
          if (chatId <= 0) continue;

          DcChat chat;
          try {
            chat = dcRef.getChat(chatId);
          } catch (Throwable t) {
            continue;
          }
          if (chat == null) continue;

          // Skip everything that isn't a real 1:1 conversation with a
          // human peer.
          if (chat.isMultiUser()) continue;
          if (chat.isSelfTalk() || chat.isDeviceTalk()) continue;
          if (chat.isMailingList()) continue;
          if (chat.isContactRequest()) continue;
          if (!chat.canSend()) continue;

          int[] memberIds = dcRef.getChatContacts(chatId);
          if (memberIds == null || memberIds.length == 0) continue;
          int peerId = memberIds[0];
          if (peerId == DcContact.DC_CONTACT_ID_SELF
              || peerId == DcContact.DC_CONTACT_ID_INFO
              || peerId == DcContact.DC_CONTACT_ID_DEVICE) {
            continue;
          }
          if (!seenContactIds.add(peerId)) continue;

          try {
            // The actual content is intentionally minimal. The receiver's
            // core extracts the From-display-name + Chat-User-Avatar header
            // from this message and updates its contact card. The body
            // doubles as a UX hint so the peer understands why a near-empty
            // line just appeared in the chat.
            String body = ctxRef.getString(R.string.bmchat_profile_updated_marker);
            int sentMsgId = dcRef.sendTextMsg(chatId, body);
            if (sentMsgId > 0) {
              sent++;
            }
          } catch (Throwable t) {
            Log.w(TAG, "sendTextMsg failed for chat " + chatId, t);
          }
        }
      } catch (Throwable t) {
        Log.w(TAG, "publishToActiveContacts failed", t);
      } finally {
        RUNNING.set(false);
        if (showToast) {
          final int total = sent;
          MAIN.post(() -> {
            try {
              String msg;
              if (total == 0) {
                msg = ctxRef.getString(R.string.bmchat_profile_publish_no_targets);
              } else {
                msg = ctxRef.getString(
                    R.string.bmchat_profile_publish_done_n, total);
              }
              Toast.makeText(ctxRef, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {
            }
          });
        }
      }
    });
  }

  /** Convenience overload that uses the active {@link ApplicationContext}. */
  @MainThread
  public static void publishToActiveContacts(@Nullable Context context,
                                             boolean showToast) {
    if (context == null) return;
    Context appCtx = context.getApplicationContext();
    DcContext dcCtx = DcHelper.getContext(appCtx);
    publishToActiveContacts(appCtx, dcCtx, showToast);
  }
}
