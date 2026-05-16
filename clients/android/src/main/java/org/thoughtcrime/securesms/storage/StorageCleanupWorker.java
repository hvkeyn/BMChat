package org.thoughtcrime.securesms.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.StorageClearRequest;
import chat.delta.rpc.types.StorageUsage;
import java.util.concurrent.TimeUnit;
import org.thoughtcrime.securesms.connect.DcHelper;

public final class StorageCleanupWorker extends Worker {
  private static final String TAG = "StorageCleanupWorker";
  private static final String UNIQUE_NAME = "bmchat-storage-cleanup";

  public static final String PREF_AUTO_ENABLED = "bmchat_storage_auto_enabled";
  public static final String PREF_AUTO_AGE_SECONDS = "bmchat_storage_auto_age_seconds";
  public static final String PREF_AUTO_MAX_BYTES = "bmchat_storage_auto_max_bytes";

  public StorageCleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
    super(context, params);
  }

  @NonNull
  @Override
  public Result doWork() {
    try {
      Context context = getApplicationContext();
      SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
      if (!prefs.getBoolean(PREF_AUTO_ENABLED, false)) {
        return Result.success();
      }

      long ageSeconds = prefs.getLong(PREF_AUTO_AGE_SECONDS, 0L);
      long maxBytes = prefs.getLong(PREF_AUTO_MAX_BYTES, 0L);
      if (ageSeconds <= 0 && maxBytes <= 0) {
        return Result.success();
      }

      Rpc rpc = DcHelper.getRpc(context);
      int accountId = DcHelper.getContext(context).getAccountId();
      StorageClearRequest request = new StorageClearRequest();
      if (ageSeconds > 0) {
        request.olderThanSeconds = ageSeconds;
      }
      if (maxBytes > 0) {
        StorageUsage usage = rpc.getStorageUsage(accountId);
        long overflow = safeLong(usage == null ? null : usage.blobdirBytes) - maxBytes;
        if (overflow <= 0 && ageSeconds <= 0) {
          return Result.success();
        }
        if (overflow > 0) {
          request.limitBytes = overflow;
        }
      }
      rpc.clearLocalStorage(accountId, request);
      return Result.success();
    } catch (RpcException e) {
      Log.w(TAG, "storage cleanup failed", e);
      return Result.retry();
    } catch (Throwable t) {
      Log.w(TAG, "storage cleanup crashed", t);
      return Result.failure();
    }
  }

  public static void schedule(@NonNull Context context) {
    Constraints constraints = new Constraints.Builder().build();
    PeriodicWorkRequest request =
        new PeriodicWorkRequest.Builder(StorageCleanupWorker.class, 12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build();
    WorkManager.getInstance(context.getApplicationContext())
        .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
  }

  public static void cancel(@NonNull Context context) {
    WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_NAME);
  }

  private static long safeLong(Long value) {
    return value == null ? 0L : value;
  }
}
