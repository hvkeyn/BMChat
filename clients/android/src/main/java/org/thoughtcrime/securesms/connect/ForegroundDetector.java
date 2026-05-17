package org.thoughtcrime.securesms.connect;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import org.thoughtcrime.securesms.ApplicationContext;

@SuppressLint("NewApi")
public class ForegroundDetector implements Application.ActivityLifecycleCallbacks {

  private int refs = 0;
  private static ForegroundDetector Instance = null;
  private final ApplicationContext application;

  public static ForegroundDetector getInstance() {
    return Instance;
  }

  public ForegroundDetector(ApplicationContext application) {
    Instance = this;
    this.application = application;
    application.registerActivityLifecycleCallbacks(this);
  }

  public boolean isForeground() {
    return refs > 0;
  }

  public boolean isBackground() {
    return refs == 0;
  }

  @Override
  public void onActivityStarted(@NonNull Activity activity) {
    if (refs == 0) {
      Log.i(
          "BMChat",
          "++++++++++++++++++ first ForegroundDetector.onActivityStarted() ++++++++++++++++++");
      DcHelper.getAccounts(application).startIo();
      if (DcHelper.isNetworkConnected(application)) {
        new Thread(
                () -> {
                  Log.i("BMChat", "calling maybeNetwork()");
                  DcHelper.getAccounts(application).maybeNetwork();
                  Log.i("BMChat", "maybeNetwork() returned");
                })
            .start();
      }
      // BMChat 2.49.53: call into KeepAliveService.onUiForeground so
      // it can re-attach FGS if the OS happened to drop it (e.g.
      // exiting power-save mode). NEVER call startForegroundService
      // again here — on Android 12+ the second call must complete
      // startForeground() within 5 s, which an already-running
      // service can't satisfy from onStartCommand alone, and the
      // resulting ForegroundServiceDidNotStartInTimeException crashes
      // the whole process. The bug surfaced as random crashes when
      // returning to the app from the recents screen or after long
      // background periods.
      try {
        KeepAliveService.onUiForeground();
      } catch (Throwable t) {
        Log.w("BMChat", "onUiForeground failed", t);
      }
    }

    refs++;
  }

  @Override
  public void onActivityStopped(@NonNull Activity activity) {
    if (refs <= 0) {
      Log.w("BMChat", "invalid call to ForegroundDetector.onActivityStopped()");
      return;
    }

    refs--;

    if (refs == 0) {
      Log.i(
          "BMChat",
          "++++++++++++++++++ last ForegroundDetector.onActivityStopped() ++++++++++++++++++");
      // BMChat: the user just put BMChat into the background. Re-attach
      // the foreground-service notification so the OS keeps the IDLE
      // socket alive even if Doze tries to evict the process.
      try {
        KeepAliveService.onUiBackground(application);
      } catch (Throwable t) {
        Log.w("BMChat", "onUiBackground failed", t);
      }
    }
  }

  @Override
  public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) {}

  @Override
  public void onActivityResumed(@NonNull Activity activity) {}

  @Override
  public void onActivityPaused(@NonNull Activity activity) {
    // pause/resume will also be called when the app is partially covered by a dialog
  }

  @Override
  public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

  @Override
  public void onActivityDestroyed(@NonNull Activity activity) {}
}
