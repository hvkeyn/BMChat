// called when the user click the "clear" or "mark read" button in the system notification

package org.thoughtcrime.securesms.notifications;

import static com.b44t.messenger.DcChat.DC_CHAT_NO_CHAT;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

public class MarkReadReceiver extends BroadcastReceiver {
  public static final String MARK_NOTICED_ACTION =
      "org.thoughtcrime.securesms.notifications.MARK_NOTICED";
  public static final String CANCEL_ACTION = "org.thoughtcrime.securesms.notifications.CANCEL";
  // BMChat: fired by the system when the user swipes the bundled group
  // summary away. Without it, dismissing the summary leaves the per-chat
  // children alive and the next incoming message recreates a "ghost"
  // summary from stale in-memory state.
  public static final String SUMMARY_DISMISSED_ACTION =
      "org.thoughtcrime.securesms.notifications.SUMMARY_DISMISSED";
  public static final String ACCOUNT_ID_EXTRA = "account_id";
  public static final String CHAT_ID_EXTRA = "chat_id";
  public static final String MSG_ID_EXTRA = "msg_id";

  @Override
  public void onReceive(final Context context, Intent intent) {
    String action = intent.getAction();
    boolean markNoticed = MARK_NOTICED_ACTION.equals(action);
    boolean summaryDismissed = SUMMARY_DISMISSED_ACTION.equals(action);
    if (!markNoticed && !summaryDismissed && !CANCEL_ACTION.equals(action)) {
      return;
    }

    final int accountId = intent.getIntExtra(ACCOUNT_ID_EXTRA, 0);
    if (accountId == 0) return;

    if (summaryDismissed) {
      // Summary swipes do not target a specific chat — just clear our
      // local bookkeeping so the next event rebuilds the group cleanly.
      Util.runOnAnyBackgroundThread(
          () -> {
            DcHelper.getNotificationCenter(context).onSummaryDismissed(accountId);
            DcHelper.getNotificationCenter(context).reconcileAccount(accountId);
          });
      return;
    }

    final int chatId = intent.getIntExtra(CHAT_ID_EXTRA, DC_CHAT_NO_CHAT);
    final int msgId = intent.getIntExtra(MSG_ID_EXTRA, 0);
    if (chatId == DC_CHAT_NO_CHAT) {
      return;
    }

    Util.runOnAnyBackgroundThread(
        () -> {
          DcHelper.getNotificationCenter(context).removeNotifications(accountId, chatId);
          if (markNoticed) {
            DcContext dcContext = DcHelper.getAccounts(context).getAccount(accountId);
            dcContext.marknoticedChat(chatId);
            dcContext.markseenMsgs(new int[] {msgId});
          }
          DcHelper.getNotificationCenter(context).reconcileAccount(accountId);
        });
  }
}
