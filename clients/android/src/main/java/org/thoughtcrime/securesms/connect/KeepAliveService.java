package org.thoughtcrime.securesms.connect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.notifications.NotificationCenter;
import org.thoughtcrime.securesms.util.IntentUtils;
import org.thoughtcrime.securesms.util.Prefs;

/**
 * Foreground service that keeps the IMAP IDLE socket alive while the
 * user has BMChat in the background. The implementation is the
 * classical Delta-chat pattern: as soon as {@link #onCreate} or
 * {@link #onStartCommand} fires we attach a visible FGS notification.
 *
 * <h2>What we deliberately avoid</h2>
 *
 * <p>BMChat 2.49.45–2.49.52 experimented with several "invisible
 * foreground service" tricks (per-transition detach/re-attach,
 * 600 ms transient stopForeground, debounced re-attach…). All of
 * them broke in production on Android 12+ Samsung One UI:
 *
 * <ul>
 *   <li>{@code ForegroundServiceDidNotStartInTimeException} when a
 *       caller invoked {@code startForegroundService} for an already
 *       running service (Android's 5-second deadline applies to the
 *       <em>next</em> startForeground call too).</li>
 *   <li>OEM aggressive task-killers reaping the service whenever the
 *       FGS notification was temporarily detached.</li>
 *   <li>"Empty BMChat" ghost rows in the shade because Samsung One UI
 *       insists on rendering the app label even when title and body
 *       are blank.</li>
 * </ul>
 *
 * <p>This file is now intentionally <strong>boring</strong>: every
 * entry point ends up calling {@link #startForeground} synchronously,
 * the notification carries a real, single-line title, and there is
 * no detach path. Tested to keep IMAP IDLE alive on Samsung S21
 * Android 15.
 */
public class KeepAliveService extends Service {

  private static final String TAG = "KeepAliveService";

  static KeepAliveService s_this = null;

  /** Tracks whether {@link #startForeground} has been called. */
  private boolean isInForeground = false;

  /**
   * BMChat 2.49.85: cached snapshot used to redraw the FGS notification in place. Updated by the
   * status listener whenever connectivity / fresh-message counters change so we can call
   * {@link NotificationManager#notify} without rebuilding from scratch each time.
   */
  private volatile BMChatStatusManager.StatusInfo cachedStatus;
  private final BMChatStatusManager.StatusListener statusListener =
      info -> {
        cachedStatus = info;
        if (!isInForeground) return;
        try {
          NotificationManager nm = getSystemService(NotificationManager.class);
          if (nm != null) nm.notify(NotificationCenter.ID_PERMANENT, createNotification());
        } catch (Throwable t) {
          Log.w(TAG, "Failed to refresh status notification", t);
        }
      };

  /**
   * Starts the service when the user has opted into "reliable mode".
   * Safe to call from any thread, idempotent: when the service is
   * already running we use {@link #ensureForeground} to satisfy
   * Android's 5-second startForeground deadline instead of issuing a
   * fresh {@code startForegroundService} call (which would crash with
   * {@code ForegroundServiceDidNotStartInTimeException} on Android
   * 12+).
   */
  public static void maybeStartSelf(Context context) {
    if (!Prefs.reliableService(context)) return;
    KeepAliveService self = s_this;
    if (self != null) {
      // Service already alive — just make sure the FGS notification
      // is attached. Re-invoking startForegroundService here is what
      // produced the Samsung One UI crash chain.
      self.ensureForeground();
      return;
    }
    startSelf(context);
  }

  /**
   * Legacy alias for {@link #maybeStartSelf(Context)}. BMChat
   * 2.49.45–2.49.52 used a more elaborate deferred-start scheme that
   * deliberately skipped attaching FGS until the first activity was
   * visible; in 2.49.53 we keep the method signature for compatibility
   * with {@code ApplicationContext.onCreate} but the body is now
   * identical to the regular entry point.
   */
  public static void maybeStartSelfIfUiUsed(Context context) {
    maybeStartSelf(context);
  }

  public static void startSelf(Context context) {
    try {
      ContextCompat.startForegroundService(context, new Intent(context, KeepAliveService.class));
    } catch (Exception e) {
      Log.i(TAG, "Error calling ContextCompat.startForegroundService()", e);
    }
  }

  /** No-op: kept so callers across the codebase still compile. */
  public static void onUiForeground() {
    KeepAliveService self = s_this;
    if (self == null) return;
    // Make sure FGS is attached — Samsung One UI sometimes drops the
    // notification when the device transitions out of "Power saving"
    // mode and we want to refresh it on first foreground.
    self.ensureForeground();
  }

  /**
   * Counterpart of {@link #onUiForeground()}. Used to be the place
   * where we re-attached a previously detached FGS notification; now
   * it just makes sure the service is still alive.
   */
  public static void onUiBackground(Context appContext) {
    KeepAliveService self = s_this;
    if (self == null) {
      maybeStartSelf(appContext);
      return;
    }
    self.ensureForeground();
  }

  /**
   * Calls {@link #startForeground} idempotently. Guarded by
   * {@link #isInForeground} so we don't spam the system when the user
   * flips between activities at high speed.
   */
  private void ensureForeground() {
    if (isInForeground) return;
    try {
      startForeground(NotificationCenter.ID_PERMANENT, createNotification());
      isInForeground = true;
    } catch (Throwable t) {
      Log.w(TAG, "ensureForeground failed", t);
    }
  }

  @Override
  public void onCreate() {
    Log.i("BMChat", "*** KeepAliveService.onCreate()");
    s_this = this;
    BMChatStatusManager manager = BMChatStatusManager.getInstance(this);
    manager.start();
    manager.setListener(statusListener);
    ensureForeground();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i("BMChat", "*** KeepAliveService.onStartCommand()");
    // CRITICAL: Android 12+ requires startForeground() within ~5 s of
    // every startForegroundService() call, even when the service was
    // already running. Without this branch any subsequent invocation
    // would crash the process with ForegroundServiceDidNotStartInTimeException.
    ensureForeground();
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    Log.i("BMChat", "*** KeepAliveService.onDestroy()");
    isInForeground = false;
    if (s_this == this) s_this = null;
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    stopSelf();
  }

  public static KeepAliveService getInstance() {
    return s_this;
  }

  /* The notification ******************************************************/

  private Notification createNotification() {
    Intent intent = new Intent(this, ConversationListActivity.class);
    PendingIntent contentIntent =
        PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());

    BMChatStatusManager.StatusInfo info = cachedStatus;
    if (info == null) {
      info = BMChatStatusManager.getInstance(this).buildStatus();
      cachedStatus = info;
    }

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
    builder.setPriority(NotificationCompat.PRIORITY_MIN);
    builder.setOngoing(true);
    builder.setCategory(NotificationCompat.CATEGORY_STATUS);
    builder.setShowWhen(false);
    builder.setSilent(true);
    builder.setVisibility(NotificationCompat.VISIBILITY_SECRET);

    // BMChat 2.49.85: tint the icon by the current connectivity severity. setColor() draws the
    // small icon and the action buttons in the supplied accent on most launchers, which gives
    // the user the "one-glance" cue they asked for (зелёный/жёлтый/красный).
    int accentRes;
    switch (info.severity) {
      case OK:
        accentRes = R.color.bmchat_status_ok;
        break;
      case WORKING:
        accentRes = R.color.bmchat_status_working;
        break;
      case MAIL_FALLBACK:
        accentRes = R.color.bmchat_status_mail;
        break;
      default:
        accentRes = R.color.bmchat_status_offline;
        break;
    }
    builder.setColor(ContextCompat.getColor(this, accentRes));
    builder.setColorized(false);
    builder.setContentIntent(contentIntent);
    builder.setSmallIcon(R.drawable.notification_permanent);

    builder.setContentTitle(info.title(this));
    builder.setContentText(info.summary(this));
    builder.setStyle(new NotificationCompat.BigTextStyle().bigText(info.details(this)));
    if (info.unread > 0) builder.setNumber(info.unread);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      createFgNotificationChannel(this);
      builder.setChannelId(NotificationCenter.CH_PERMANENT);
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED);
    }
    return builder.build();
  }

  private static boolean ch_created = false;

  @RequiresApi(Build.VERSION_CODES.O)
  private static void createFgNotificationChannel(Context context) {
    if (!ch_created) {
      ch_created = true;
      NotificationManager notificationManager =
          context.getSystemService(NotificationManager.class);

      // BMChat: cleanup of obsolete fg channels from previous BMChat builds.
      try {
        notificationManager.deleteNotificationChannel("bmchat_fg_notification_ch");
        notificationManager.deleteNotificationChannel("bmchat_fg_notification_ch_v2");
        notificationManager.deleteNotificationChannel("bmchat_fg_notification_ch_v3");
        notificationManager.deleteNotificationChannel("dc_foreground_notification_ch");
      } catch (Throwable ignored) {
      }

      NotificationChannel channel =
          new NotificationChannel(
              NotificationCenter.CH_PERMANENT,
              context.getString(R.string.notify_background_connection_channel),
              NotificationManager.IMPORTANCE_MIN);
      channel.setDescription(
          context.getString(R.string.notify_background_connection_channel_description));
      channel.setShowBadge(false);
      channel.setSound(null, null);
      channel.enableLights(false);
      channel.enableVibration(false);
      channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
      notificationManager.createNotificationChannel(channel);
    }
  }
}
