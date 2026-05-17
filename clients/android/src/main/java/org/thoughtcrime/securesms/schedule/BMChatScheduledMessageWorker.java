package org.thoughtcrime.securesms.schedule;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * BMChat 2.49.84 (Phase 4B): WorkManager job that actually sends a previously-scheduled message.
 *
 * <p>The scheduler enqueues one job per entry in the queue with the correct initial delay; when
 * WorkManager fires it we look the entry up, build a {@link DcMsg} that mirrors what the user
 * staged, push it via {@link DcContext#sendMsg(int, DcMsg)} and pop it from the queue.
 */
public final class BMChatScheduledMessageWorker extends Worker {

  private static final String TAG = "BMChatScheduleWorker";

  static final String KEY_ID = "bmchat.schedule.id";

  public BMChatScheduledMessageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @Override
  public @NonNull Result doWork() {
    String id = getInputData().getString(KEY_ID);
    if (id == null) {
      Log.w(TAG, "Worker fired without an id");
      return Result.failure();
    }
    Context context = getApplicationContext();
    BMChatScheduledMessageStore store = new BMChatScheduledMessageStore(context);
    BMChatScheduledMessage entry = store.findById(id);
    if (entry == null) {
      Log.w(TAG, "Entry vanished before worker fired: " + id);
      return Result.success();
    }

    DcContext dcContext = DcHelper.getContext(context);
    DcMsg msg = new DcMsg(dcContext, entry.viewType);
    msg.setText(entry.body == null ? "" : entry.body);
    if (entry.attachmentPath != null) {
      msg.setFileAndDeduplicate(entry.attachmentPath, entry.originalFileName, entry.mimeType);
    }
    if (entry.quoteMsgId != 0) {
      try {
        DcMsg quoted = dcContext.getMsg(entry.quoteMsgId);
        if (quoted != null && quoted.getId() != 0) msg.setQuote(quoted);
      } catch (Exception e) {
        Log.w(TAG, "Failed to attach quote for " + id, e);
      }
    }

    int sent = dcContext.sendMsg(entry.chatId, msg);
    if (sent == 0) {
      String lastError = dcContext.getLastError();
      Log.w(TAG, "sendMsg returned 0 for " + id + ": " + lastError);
      // Keep the entry around so the user can see it and retry from the UI.
      return Result.retry();
    }
    store.remove(id);
    Log.i(TAG, "Sent scheduled message " + id + " as DcMsg " + sent);
    return Result.success();
  }
}
