package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.util.ArrayList;
import java.util.Hashtable;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.notifications.BMChatBadge;
import org.thoughtcrime.securesms.service.FetchForegroundService;
import org.thoughtcrime.securesms.util.Util;

public class DcEventCenter {
  private static final String TAG = "DcEventCenter";
  private @NonNull final Hashtable<Integer, ArrayList<DcEventDelegate>> currentAccountObservers =
      new Hashtable<>();
  private @NonNull final Hashtable<Integer, ArrayList<DcEventDelegate>> multiAccountObservers =
      new Hashtable<>();
  private final Object LOCK = new Object();
  private final @NonNull ApplicationContext context;

  public interface DcEventDelegate {
    void handleEvent(@NonNull DcEvent event);

    default boolean runOnMain() {
      return true;
    }
  }

  public DcEventCenter(@NonNull Context context) {
    this.context = ApplicationContext.getInstance(context);
  }

  public void addObserver(int eventId, @NonNull DcEventDelegate observer) {
    addObserver(currentAccountObservers, eventId, observer);
  }

  public void addMultiAccountObserver(int eventId, @NonNull DcEventDelegate observer) {
    addObserver(multiAccountObservers, eventId, observer);
  }

  private void addObserver(
      Hashtable<Integer, ArrayList<DcEventDelegate>> observers,
      int eventId,
      @NonNull DcEventDelegate observer) {
    synchronized (LOCK) {
      ArrayList<DcEventDelegate> idObservers = observers.get(eventId);
      if (idObservers == null) {
        observers.put(eventId, (idObservers = new ArrayList<>()));
      }
      idObservers.add(observer);
    }
  }

  public void removeObserver(int eventId, DcEventDelegate observer) {
    synchronized (LOCK) {
      ArrayList<DcEventDelegate> idObservers = currentAccountObservers.get(eventId);
      if (idObservers != null) {
        idObservers.remove(observer);
      }
      idObservers = multiAccountObservers.get(eventId);
      if (idObservers != null) {
        idObservers.remove(observer);
      }
    }
  }

  public void removeObservers(DcEventDelegate observer) {
    synchronized (LOCK) {
      for (Integer eventId : currentAccountObservers.keySet()) {
        ArrayList<DcEventDelegate> idObservers = currentAccountObservers.get(eventId);
        if (idObservers != null) {
          idObservers.remove(observer);
        }
      }
      for (Integer eventId : multiAccountObservers.keySet()) {
        ArrayList<DcEventDelegate> idObservers = multiAccountObservers.get(eventId);
        if (idObservers != null) {
          idObservers.remove(observer);
        }
      }
    }
  }

  private void sendToMultiAccountObservers(@NonNull DcEvent event) {
    sendToObservers(multiAccountObservers, event);
  }

  private void sendToCurrentAccountObservers(@NonNull DcEvent event) {
    sendToObservers(currentAccountObservers, event);
  }

  private void sendToObservers(
      Hashtable<Integer, ArrayList<DcEventDelegate>> observers, @NonNull DcEvent event) {
    synchronized (LOCK) {
      ArrayList<DcEventDelegate> idObservers = observers.get(event.getId());
      if (idObservers != null) {
        for (DcEventDelegate observer : idObservers) {
          // using try/catch blocks as under some circumstances eg. getContext() may return NULL -
          // and as this function is used virtually everywhere, also in libs,
          // it's not feasible to check all single occurrences.
          if (observer.runOnMain()) {
            Util.runOnMain(
                () -> {
                  try {
                    observer.handleEvent(event);
                  } catch (Exception e) {
                    Log.e(TAG, "Error calling observer.handleEvent()", e);
                  }
                });
          } else {
            Util.runOnBackground(
                () -> {
                  try {
                    observer.handleEvent(event);
                  } catch (Exception e) {
                    Log.e(TAG, "Error calling observer.handleEvent()", e);
                  }
                });
          }
        }
      }
    }
  }

  private final Object lastErrorLock = new Object();
  private boolean showNextErrorAsToast = true;

  public void captureNextError() {
    synchronized (lastErrorLock) {
      showNextErrorAsToast = false;
    }
  }

  public void endCaptureNextError() {
    synchronized (lastErrorLock) {
      showNextErrorAsToast = true;
    }
  }

  private void handleError(int event, String string) {
    // log error
    boolean showAsToast;
    Log.e("BMChat", string);
    synchronized (lastErrorLock) {
      showAsToast = showNextErrorAsToast;
      showNextErrorAsToast = true;
    }

    // show error to user
    Util.runOnMain(
        () -> {
          if (showAsToast) {
            String toastString = null;

            if (event == DcContext.DC_EVENT_ERROR_SELF_NOT_IN_GROUP) {
              toastString = context.getString(R.string.group_self_not_in_group);
            }

            ForegroundDetector foregroundDetector = ForegroundDetector.getInstance();
            if (toastString != null
                && (foregroundDetector == null || foregroundDetector.isForeground())) {
              Toast.makeText(context, toastString, Toast.LENGTH_LONG).show();
            }
          }
        });
  }

  public void handleLogging(@NonNull DcEvent event) {
    final String logPrefix = "[accId=" + event.getAccountId() + "] ";
    switch (event.getId()) {
      case DcContext.DC_EVENT_INFO:
        Log.i("BMChat", logPrefix + event.getData2Str());
        break;

      case DcContext.DC_EVENT_WARNING:
        Log.w("BMChat", logPrefix + event.getData2Str());
        break;

      case DcContext.DC_EVENT_ERROR:
        Log.e("BMChat", logPrefix + event.getData2Str());
        break;
    }
  }

  public long handleEvent(@NonNull DcEvent event) {
    int accountId = event.getAccountId();
    int id = event.getId();

    sendToMultiAccountObservers(event);

    switch (id) {
      case DcContext.DC_EVENT_INCOMING_MSG:
        // BMChat 2.49.61: explicit "incoming" log so users debugging missing
        // notifications can confirm whether the core actually delivered an
        // inbound event or whether the silence is upstream (no IMAP, message
        // not parsed, …). Pair with NotificationCenter logs to trace the
        // full pipeline in `adb logcat -s DeltaChat NotificationCenter`.
        android.util.Log.i(
            "BMChat",
            "DC_EVENT_INCOMING_MSG account=" + accountId
                + " chat=" + event.getData1Int()
                + " msg=" + event.getData2Int());
        DcHelper.getNotificationCenter(context)
            .notifyMessage(accountId, event.getData1Int(), event.getData2Int());
        // BMChat: detect e-mailed invite links and auto-trigger SecureJoin so
        // the user gets a verified contact without having to tap the link.
        if (accountId == context.getDcContext().getAccountId()) {
          BMChatInviteAutoAcceptor.onIncomingMsg(
              context, context.getDcContext(), event.getData1Int(), event.getData2Int());
          // BMChat: collapse mirror 1:1 chats with the same e-mail (e.g. when
          // the peer recreated their account and core spawned a new chat).
          BMChatChatDedupe.scheduleScan(context, context.getDcContext());
        }
        // BMChat: hand the message to the e-mail-bot dispatcher so any
        // registered bot of the owner account can react to it. Bots are
        // scoped per account, so we route every event regardless of
        // which account it belongs to.
        try {
          org.thoughtcrime.securesms.emailbots.EmailBotIntegration.get(context)
              .getDispatcher()
              .onIncomingMessage(accountId, event.getData1Int(), event.getData2Int());
        } catch (Throwable t) {
          android.util.Log.w("DcEventCenter", "email-bot dispatch failed", t);
        }
        break;

      case DcContext.DC_EVENT_INCOMING_REACTION:
        DcHelper.getNotificationCenter(context)
            .notifyReaction(
                accountId, event.getData1Int(), event.getData2Int(), event.getData2Str());
        break;

      case DcContext.DC_EVENT_INCOMING_WEBXDC_NOTIFY:
        DcHelper.getNotificationCenter(context)
            .notifyWebxdc(accountId, event.getData1Int(), event.getData2Int(), event.getData2Str());
        break;

      case DcContext.DC_EVENT_MSGS_NOTICED:
        DcHelper.getNotificationCenter(context).removeNotifications(accountId, event.getData1Int());
        break;

      case DcContext.DC_EVENT_MSG_DELETED:
        DcHelper.getNotificationCenter(context)
            .removeNotification(accountId, event.getData1Int(), event.getData2Int());
        break;

      case DcContext.DC_EVENT_ACCOUNTS_BACKGROUND_FETCH_DONE:
        FetchForegroundService.stop(context);
        break;

      case DcContext.DC_EVENT_IMEX_PROGRESS:
        sendToCurrentAccountObservers(event);
        return 0;
    }

    handleLogging(event);

    if (accountId != context.getDcContext().getAccountId()) {
      return 0;
    }

    switch (id) {
      case DcContext.DC_EVENT_ERROR:
      case DcContext.DC_EVENT_ERROR_SELF_NOT_IN_GROUP:
        handleError(id, event.getData2Str());
        break;

      default:
        sendToCurrentAccountObservers(event);
        break;
    }

    if (id == DcContext.DC_EVENT_CHAT_MODIFIED) {
      // Possibly a chat was deleted or the avatar was changed, directly refresh DirectShare so that
      // a new chat can move up / the chat avatar change is populated
      DirectShareUtil.triggerRefreshDirectShare(context);
      // BMChat: a freshly modified chat may be a brand-new mirror of an
      // existing 1:1 conversation -> archive the older duplicates.
      BMChatChatDedupe.scheduleScan(context, context.getDcContext());
    } else if (id == DcContext.DC_EVENT_CONTACTS_CHANGED
        || id == DcContext.DC_EVENT_MSGS_CHANGED) {
      // Same idea for any contact/message churn that might have introduced
      // a new key-contact for an e-mail we already track.
      BMChatChatDedupe.scheduleScan(context, context.getDcContext());
    }

    // BMChat: keep the launcher icon unread badge in sync with the core's
    // fresh-message count for any event that can plausibly change it.
    switch (id) {
      case DcContext.DC_EVENT_INCOMING_MSG:
      case DcContext.DC_EVENT_INCOMING_REACTION:
      case DcContext.DC_EVENT_INCOMING_WEBXDC_NOTIFY:
      case DcContext.DC_EVENT_MSGS_CHANGED:
      case DcContext.DC_EVENT_MSG_DELETED:
      case DcContext.DC_EVENT_CHAT_MODIFIED:
        BMChatBadge.refresh(context);
        break;
      case DcContext.DC_EVENT_MSGS_NOTICED:
        // BMChat: a read happened — most often from another device or a
        // notification action — so make sure the system tray and the
        // launcher badge stop advertising messages that are no longer
        // unread. reconcileAccount also refreshes the badge.
        try {
          DcHelper.getNotificationCenter(context).reconcileAccount(accountId);
        } catch (Throwable t) {
          BMChatBadge.refresh(context);
        }
        break;
      default:
        break;
    }

    return 0;
  }
}
