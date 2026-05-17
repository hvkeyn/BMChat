package org.thoughtcrime.securesms.schedule;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * BMChat 2.49.84 (Phase 4B): translates an entry in {@link BMChatScheduledMessageStore} into a
 * corresponding {@link androidx.work.WorkManager} job and back. Keeping all the WorkManager
 * plumbing here means the rest of the codebase (input panel, conversation activity, etc.) never
 * has to touch WorkManager APIs directly.
 */
public final class BMChatScheduledMessageScheduler {

  private static final String TAG = "BMChatSchedule";
  private static final String TAG_PREFIX = "bmchat_scheduled_msg_";

  private BMChatScheduledMessageScheduler() {}

  /** Allocate a fresh id usable as both store key and WorkManager tag. */
  public static @NonNull String newId() {
    return UUID.randomUUID().toString();
  }

  /** Schedule a single message on the WorkManager queue with the right delay. */
  public static void schedule(@NonNull Context context, @NonNull BMChatScheduledMessage entry) {
    long delayMs = entry.scheduledAtMs - System.currentTimeMillis();
    if (delayMs < 0L) delayMs = 0L;

    Data input = new Data.Builder().putString(BMChatScheduledMessageWorker.KEY_ID, entry.id).build();
    OneTimeWorkRequest request =
        new OneTimeWorkRequest.Builder(BMChatScheduledMessageWorker.class)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(TAG_PREFIX + entry.id)
            .build();
    WorkManager.getInstance(context)
        .enqueueUniqueWork(workName(entry.id), ExistingWorkPolicy.REPLACE, request);
    Log.i(TAG, "Scheduled message " + entry.id + " in " + delayMs + " ms");
  }

  /** Cancel the WorkManager job for a previously scheduled message. */
  public static void cancel(@NonNull Context context, @NonNull String id) {
    WorkManager.getInstance(context).cancelUniqueWork(workName(id));
    Log.i(TAG, "Cancelled scheduled message " + id);
  }

  /**
   * Re-arm WorkManager jobs for every entry currently in the store. Called from
   * {@code ApplicationContext.onCreate} so that scheduled messages survive process death and
   * reboots without the user opening the app first.
   */
  public static void rescheduleAll(@NonNull Context context) {
    BMChatScheduledMessageStore store = new BMChatScheduledMessageStore(context);
    long now = System.currentTimeMillis();
    int total = 0;
    for (BMChatScheduledMessage entry : store.getAll()) {
      total++;
      if (entry.scheduledAtMs <= now) {
        // The phone was off when we should have fired; trigger immediately.
        BMChatScheduledMessage rearmed =
            new BMChatScheduledMessage(
                entry.id,
                entry.chatId,
                now,
                entry.body,
                entry.viewType,
                entry.attachmentPath,
                entry.originalFileName,
                entry.mimeType,
                entry.quoteMsgId,
                entry.createdAtMs);
        // schedule with the original entry so we don't have to rewrite the file just to flip
        // the timestamp by a few seconds.
        schedule(context, rearmed);
      } else {
        schedule(context, entry);
      }
    }
    if (total > 0) Log.i(TAG, "Re-armed " + total + " scheduled messages");
  }

  private static @NonNull String workName(@NonNull String id) {
    return TAG_PREFIX + id;
  }
}
