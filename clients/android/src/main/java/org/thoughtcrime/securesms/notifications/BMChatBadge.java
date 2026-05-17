package org.thoughtcrime.securesms.notifications;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.ApplicationContext;

import me.leolin.shortcutbadger.ShortcutBadger;

/**
 * Centralised launcher-icon badge updater for BMChat.
 *
 * Stock Pixel / Samsung / Sony Xperia One UI launchers honour the
 * {@code Notification#setNumber()} call already wired into
 * {@link NotificationCenter}. Older / OEM launchers (Xiaomi MIUI, Huawei EMUI,
 * Asus ZenUI, LG, Vivo / OPPO ColorOS, Sony pre-Xperia One) need an explicit
 * intent broadcast — {@link ShortcutBadger} knows the per-OEM protocol and is
 * a no-op on launchers that already support the platform notification badge.
 *
 * The badge always reflects the total number of "fresh" messages across all
 * accounts so it survives mark-as-read, account switching and notification
 * cancellation, and so it shows zero (i.e. clears the badge) once the user
 * has read everything.
 */
public final class BMChatBadge {

  private static final String TAG = "BMChatBadge";

  private BMChatBadge() {}

  /**
   * Recompute the unread badge from the core and apply it to the launcher
   * icon. Safe to call from any thread.
   */
  public static void refresh(Context context) {
    if (context == null) return;
    final Context appContext = context.getApplicationContext();
    Runnable r = () -> {
      try {
        int total = countFreshMessages(appContext);
        applyBadge(appContext, total);
      } catch (Throwable t) {
        Log.w(TAG, "refresh failed", t);
      }
    };
    // Touching DcAccounts/DcContext on the UI thread is fine but counting
    // across all accounts can take a few ms — schedule on a background thread.
    new Thread(r, "bmchat-badge").start();
  }

  /**
   * Synchronous variant: refresh from a thread that is already a worker
   * (e.g. NotificationCenter's notify pipeline). Avoids spawning yet another
   * thread per incoming message.
   */
  public static void refreshSync(Context context) {
    if (context == null) return;
    Context appContext = context.getApplicationContext();
    try {
      int total = countFreshMessages(appContext);
      applyBadge(appContext, total);
    } catch (Throwable t) {
      Log.w(TAG, "refreshSync failed", t);
    }
  }

  private static int countFreshMessages(Context appContext) {
    int total = 0;
    try {
      int[] accountIds = ApplicationContext.getDcAccounts().getAll();
      for (int accountId : accountIds) {
        DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
        if (dcContext == null) continue;
        if (dcContext.isMuted()) continue;
        int[] freshMsgs = dcContext.getFreshMsgs();
        if (freshMsgs != null) total += freshMsgs.length;
      }
    } catch (Throwable t) {
      Log.w(TAG, "countFreshMessages failed", t);
    }
    return Math.max(total, 0);
  }

  private static void applyBadge(Context appContext, int count) {
    try {
      // ShortcutBadger handles the OEM-specific intent broadcasts itself and
      // returns false when the launcher does not implement any of them; that
      // is fine because the platform NotificationManager API has already done
      // its job for the launchers that support it (Pixel, Samsung One UI, ...).
      ShortcutBadger.applyCount(appContext, count);
      // Some Samsung launchers cache the previous count when the new value is
      // 0, so we issue an explicit removeCount() in that case.
      if (count == 0) {
        ShortcutBadger.removeCount(appContext);
      }
    } catch (Throwable t) {
      Log.w(TAG, "applyBadge failed (count=" + count + ")", t);
    }
    // Safe-to-ignore on Android < 8: the launcher reads the broadcast directly.
    if (Build.VERSION.SDK_INT < 26) return;
  }
}
