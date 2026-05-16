package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * WorkManager worker that drives the periodic bot polling cycle.
 *
 * <p>Runs at most once every 15 minutes (the WorkManager floor for
 * periodic work) and only when the device has connectivity. The actual
 * work is delegated to {@link BotPollManager#pollAllBlocking(Context)}.
 */
public final class BotPollWorker extends Worker {

  private static final String TAG = "BotPollWorker";

  public BotPollWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public Result doWork() {
    try {
      int n = BotPollManager.pollAllBlocking(getApplicationContext());
      if (n > 0) Log.i(TAG, "republished " + n + " Telegram update(s)");
      return Result.success();
    } catch (Throwable t) {
      Log.w(TAG, "doWork failed", t);
      return Result.retry();
    }
  }
}
