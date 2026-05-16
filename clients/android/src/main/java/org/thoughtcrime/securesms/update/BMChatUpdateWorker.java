package org.thoughtcrime.securesms.update;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic, fully-background update probe.
 *
 * <p>Until BMChat 2.49.57 the updater only polled {@code update.json}
 * while a BMChat activity was in the foreground. That is fine for a
 * messenger that the user opens daily, but skips users who keep BMChat
 * pinned in the background and only check it occasionally — they could
 * stay several versions behind without ever noticing. Worse, when a
 * critical patch ships (the FGS-deadline crash fix in 2.49.53 was a
 * canonical example), users who never re-opened the app would not see
 * the recommendation to upgrade until they hit the bug themselves.
 *
 * <p>This worker is registered from {@link
 * org.thoughtcrime.securesms.ApplicationContext#onCreate ApplicationContext.onCreate}
 * with WorkManager's periodic API. Cadence:
 *
 * <ul>
 *   <li><b>Period</b> 6 hours (so a fresh release reaches a backgrounded
 *       client within roughly half a day in the worst case),</li>
 *   <li><b>Flex</b> 1 hour (WorkManager batches the wake-up with other
 *       jobs to save battery),</li>
 *   <li><b>Network constraint</b> {@code CONNECTED} — no point probing
 *       update.json on airplane-mode device.</li>
 * </ul>
 *
 * <p>The actual work is delegated to {@link
 * BMChatUpdater#checkSilentlyFromBackground(Context)} which posts a
 * low-priority "Доступно обновление" notification when a newer version
 * is found. No dialog (no Activity around), no heads-up — exactly the
 * "ненавязчиво" behaviour the user asked for.
 */
public final class BMChatUpdateWorker extends Worker {

  private static final String TAG = "BMChatUpdateWorker";

  /** Unique work name keeps WorkManager from queueing duplicates. */
  public static final String UNIQUE_NAME = "bmchat-update-probe";

  public BMChatUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public Result doWork() {
    try {
      boolean found = BMChatUpdater.checkSilentlyFromBackground(getApplicationContext());
      if (found) {
        Log.i(TAG, "newer manifest found, gentle notification posted");
      }
      return Result.success();
    } catch (Throwable t) {
      Log.w(TAG, "background update probe failed", t);
      // Use retry so WorkManager re-attempts on its own backoff schedule
      // when, for example, the network was down. We still succeed in the
      // "no new version" path so we don't burn the retry budget for the
      // common case.
      return Result.retry();
    }
  }

  /**
   * Idempotent; safe to call from {@link
   * org.thoughtcrime.securesms.ApplicationContext#onCreate ApplicationContext.onCreate}
   * on every process launch. WorkManager keeps the existing schedule
   * via {@link ExistingPeriodicWorkPolicy#KEEP}.
   */
  public static void schedule(@NonNull Context applicationContext) {
    Constraints constraints =
        new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();

    PeriodicWorkRequest request =
        new PeriodicWorkRequest.Builder(
                BMChatUpdateWorker.class, 6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build();

    WorkManager.getInstance(applicationContext)
        .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
  }
}
