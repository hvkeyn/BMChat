package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.R;

/**
 * BMChat 2.49.85: replaces the old "BMChat активен — готов получать сообщения в фоне" foreground
 * notification with a live status surface — a coloured indicator for the IMAP connectivity, the
 * timestamp of the last successful sync and the unread/fresh message counters across every
 * account. The data is sourced from the DC core APIs (`getConnectivity()`, `getFreshMsgs()`) and
 * refreshed both event-driven (via {@link DcEventCenter}) and on a one-minute timer so the
 * "обновлено N минут назад" string stays accurate.
 *
 * <p>The manager is intentionally a singleton without strong references to UI or services — the
 * {@link KeepAliveService} subscribes via {@link #setListener(StatusListener)} and pulls the
 * latest snapshot whenever it needs to rebuild the notification. Any other consumer (the in-app
 * "Подключение" screen, the system tray badge, etc.) can do the same without bloating this file.
 */
public final class BMChatStatusManager {

  /** Indicator severity that maps to a coloured glyph and notification accent. */
  public enum Severity {
    /** DC_CONNECTIVITY_CONNECTED — IMAP IDLE socket is up and idle. */
    OK,
    /** DC_CONNECTIVITY_CONNECTING / WORKING — actively probing the server right now. */
    WORKING,
    /** DC_CONNECTIVITY_NOT_CONNECTED or no accounts at all. */
    OFFLINE,
  }

  /** Snapshot returned to the notification builder. */
  public static final class StatusInfo {
    public final @NonNull Severity severity;
    public final int connectivity;
    public final int unread;
    public final int accountsTotal;
    public final long lastSyncMs;

    StatusInfo(
        @NonNull Severity severity,
        int connectivity,
        int unread,
        int accountsTotal,
        long lastSyncMs) {
      this.severity = severity;
      this.connectivity = connectivity;
      this.unread = unread;
      this.accountsTotal = accountsTotal;
      this.lastSyncMs = lastSyncMs;
    }

    /** "● Online", "● Подключение…", "● Нет связи". */
    public @NonNull String title(@NonNull Context ctx) {
      switch (severity) {
        case OK:
          return ctx.getString(R.string.bmchat_status_online);
        case WORKING:
          return ctx.getString(R.string.bmchat_status_working);
        default:
          return ctx.getString(R.string.bmchat_status_offline);
      }
    }

    /** Compact one-line summary for {@link android.app.Notification#contentText}. */
    public @NonNull String summary(@NonNull Context ctx) {
      String last = lastSyncMs == 0L
          ? ctx.getString(R.string.bmchat_status_no_sync_yet)
          : DateUtils.getRelativeTimeSpanString(lastSyncMs).toString();
      if (unread > 0) {
        return ctx.getString(R.string.bmchat_status_summary_with_unread, unread, last);
      }
      return ctx.getString(R.string.bmchat_status_summary, last);
    }

    /**
     * Multi-line body for BigTextStyle. We deliberately omit the {@link #title(Context)} line
     * here — the notification {@code contentTitle} is already rendered above the big-text block,
     * so repeating «BMChat ● Связь есть» on the first body line just wastes the user's screen
     * real estate. The body now lists only the three rotating statistics.
     */
    public @NonNull String details(@NonNull Context ctx) {
      StringBuilder sb = new StringBuilder();
      String last = lastSyncMs == 0L
          ? ctx.getString(R.string.bmchat_status_no_sync_yet)
          : DateUtils.getRelativeTimeSpanString(lastSyncMs).toString();
      sb.append(ctx.getString(R.string.bmchat_status_unread_line, unread)).append('\n');
      sb.append(ctx.getString(R.string.bmchat_status_last_sync_line, last)).append('\n');
      sb.append(ctx.getString(R.string.bmchat_status_accounts_line, accountsTotal));
      return sb.toString();
    }
  }

  /** Interface implemented by {@link KeepAliveService}. */
  public interface StatusListener {
    void onStatusChanged(@NonNull StatusInfo info);
  }

  private static volatile BMChatStatusManager instance;

  public static @NonNull BMChatStatusManager getInstance(@NonNull Context context) {
    BMChatStatusManager local = instance;
    if (local == null) {
      synchronized (BMChatStatusManager.class) {
        local = instance;
        if (local == null) {
          local = new BMChatStatusManager(context.getApplicationContext());
          instance = local;
        }
      }
    }
    return local;
  }

  private final @NonNull Context appContext;
  private final @NonNull Handler mainHandler = new Handler(Looper.getMainLooper());
  private volatile @NonNull StatusListener listener = info -> {};
  private volatile long lastSyncMs = 0L;
  private volatile boolean started = false;

  private final Runnable tick = new Runnable() {
    @Override
    public void run() {
      // Re-emit the current snapshot once a minute so the "X минут назад" string stays fresh
      // without depending on receiving DC events. The actual sync timestamp only changes via
      // markSynced(), so this is a pure UI nudge.
      try {
        listener.onStatusChanged(buildStatus());
      } catch (Throwable ignored) {
      }
      mainHandler.postDelayed(this, 60_000L);
    }
  };

  private BMChatStatusManager(@NonNull Context appContext) {
    this.appContext = appContext;
  }

  public synchronized void start() {
    if (started) return;
    started = true;
    DcEventCenter eventCenter = DcHelper.getEventCenter(appContext);
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this::handleEvent);
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_INCOMING_MSG, this::handleEvent);
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_MSGS_NOTICED, this::handleEvent);
    mainHandler.postDelayed(tick, 60_000L);
  }

  /** Used by the foreground service to receive updates as they happen. */
  public void setListener(@NonNull StatusListener listener) {
    this.listener = listener;
    // Push the current snapshot immediately so the very first notification already has data.
    try {
      listener.onStatusChanged(buildStatus());
    } catch (Throwable ignored) {
    }
  }

  /** Build a fresh snapshot from the DC core. Cheap; safe to call from any thread. */
  public @NonNull StatusInfo buildStatus() {
    int connectivity = DcContext.DC_CONNECTIVITY_NOT_CONNECTED;
    int unread = 0;
    int accountsTotal = 0;
    try {
      DcAccounts accounts = ApplicationContext.getDcAccounts();
      int[] accountIds = accounts.getAll();
      accountsTotal = accountIds.length;
      DcContext selected = accounts.getSelectedAccount();
      if (selected != null) {
        connectivity = selected.getConnectivity();
      } else if (accountIds.length > 0) {
        connectivity = accounts.getAccount(accountIds[0]).getConnectivity();
      }
      for (int accountId : accountIds) {
        DcContext dc = accounts.getAccount(accountId);
        if (dc == null) continue;
        if (dc.isMuted()) continue;
        int[] fresh = dc.getFreshMsgs();
        if (fresh != null) unread += fresh.length;
      }
    } catch (Throwable ignored) {
      // Core is racing init; fall back to the OFFLINE snapshot.
    }

    Severity severity;
    if (accountsTotal == 0) {
      severity = Severity.OFFLINE;
    } else if (connectivity >= DcContext.DC_CONNECTIVITY_CONNECTED) {
      severity = Severity.OK;
    } else if (connectivity >= DcContext.DC_CONNECTIVITY_CONNECTING) {
      severity = Severity.WORKING;
    } else {
      severity = Severity.OFFLINE;
    }

    return new StatusInfo(severity, connectivity, unread, accountsTotal, lastSyncMs);
  }

  /** Manually mark a successful sync — used by the IMAP fetch worker / IDLE bridge. */
  public void markSynced() {
    lastSyncMs = System.currentTimeMillis();
    listener.onStatusChanged(buildStatus());
  }

  private void handleEvent(@NonNull DcEvent event) {
    int eventId = event.getId();
    if (eventId == DcContext.DC_EVENT_CONNECTIVITY_CHANGED) {
      // A new CONNECTED state is the most reliable proxy for "the IMAP socket just finished a
      // round of work". Update the timestamp here so users see "обновлено только что" right
      // after the green dot appears, mirroring how Telegram-style status lines behave.
      try {
        DcContext selected = ApplicationContext.getDcAccounts().getSelectedAccount();
        if (selected != null
            && selected.getConnectivity() >= DcContext.DC_CONNECTIVITY_CONNECTED) {
          lastSyncMs = System.currentTimeMillis();
        }
      } catch (Throwable ignored) {
      }
    } else if (eventId == DcContext.DC_EVENT_INCOMING_MSG) {
      lastSyncMs = System.currentTimeMillis();
    }
    listener.onStatusChanged(buildStatus());
  }
}
