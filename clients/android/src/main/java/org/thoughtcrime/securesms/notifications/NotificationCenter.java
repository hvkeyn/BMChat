package org.thoughtcrime.securesms.notifications;

import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PRIVATE_TAG;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.app.TaskStackBuilder;
import androidx.core.graphics.drawable.IconCompat;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.ConversationActivity;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.contacts.avatars.ContactPhoto;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.preferences.widgets.NotificationPrivacyPreference;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BMChatNames;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.IntentUtils;
import org.thoughtcrime.securesms.util.JsonUtils;
import org.thoughtcrime.securesms.util.Pair;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.Util;

public class NotificationCenter {
  private static final String TAG = "NotificationCenter";
  @NonNull private final ApplicationContext context;
  private volatile ChatData visibleChat = null;
  private volatile Pair<Integer, Integer> visibleWebxdc = null;
  private volatile long lastAudibleNotification = 0;
  private static final long MIN_AUDIBLE_PERIOD_MILLIS = TimeUnit.SECONDS.toMillis(2);

  // Map<accountId, Map<chatId, lines>, contains the last lines of each chat for each account
  private final HashMap<Integer, HashMap<Integer, LinkedHashMap<Integer, String>>> inboxes =
      new HashMap<>();

  public NotificationCenter(Context context) {
    this.context = ApplicationContext.getInstance(context);
  }

  private @Nullable Uri effectiveSound(
      ChatData chatData) { // chatData=null: return app-global setting
    if (chatData == null) {
      chatData = new ChatData(0, 0);
    }
    @Nullable
    Uri chatRingtone = Prefs.getChatRingtone(context, chatData.accountId, chatData.chatId);
    if (chatRingtone != null) {
      return chatRingtone;
    } else {
      @NonNull Uri appDefaultRingtone = Prefs.getNotificationRingtone(context);
      if (!TextUtils.isEmpty(appDefaultRingtone.toString())) {
        return appDefaultRingtone;
      }
    }
    return null;
  }

  private boolean effectiveVibrate(ChatData chatData) { // chatData=null: return app-global setting
    if (chatData == null) {
      chatData = new ChatData(0, 0);
    }
    Prefs.VibrateState vibrate = Prefs.getChatVibrate(context, chatData.accountId, chatData.chatId);
    if (vibrate == Prefs.VibrateState.ENABLED) {
      return true;
    } else if (vibrate == Prefs.VibrateState.DISABLED) {
      return false;
    }
    return Prefs.isNotificationVibrateEnabled(context);
  }

  private boolean requiresIndependentChannel(ChatData chatData) {
    if (chatData == null) {
      chatData = new ChatData(0, 0);
    }
    return Prefs.getChatRingtone(context, chatData.accountId, chatData.chatId) != null
        || Prefs.getChatVibrate(context, chatData.accountId, chatData.chatId)
            != Prefs.VibrateState.DEFAULT;
  }

  private int getLedArgb(String ledColor) {
    int argb;
    try {
      argb = Color.parseColor(ledColor);
    } catch (Exception e) {
      argb = Color.rgb(0xFF, 0xFF, 0xFF);
    }
    return argb;
  }

  private PendingIntent getOpenChatlistIntent(int accountId) {
    Intent intent = new Intent(context, ConversationListActivity.class);
    intent.putExtra(ConversationListActivity.ACCOUNT_ID_EXTRA, accountId);
    intent.putExtra(ConversationListActivity.CLEAR_NOTIFICATIONS, true);
    intent.setData(Uri.parse("custom://" + accountId));
    return PendingIntent.getActivity(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());
  }

  private PendingIntent getOpenChatIntent(ChatData chatData) {
    Intent intent = new Intent(context, ConversationActivity.class);
    intent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA, chatData.accountId);
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatData.chatId);
    intent.setData(Uri.parse("custom://" + chatData.accountId + "." + chatData.chatId));
    return TaskStackBuilder.create(context)
        .addNextIntentWithParentStack(intent)
        .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());
  }

  private PendingIntent getRemoteReplyIntent(ChatData chatData, int msgId) {
    Intent intent = new Intent(RemoteReplyReceiver.REPLY_ACTION);
    intent.setClass(context, RemoteReplyReceiver.class);
    intent.setData(Uri.parse("custom://" + chatData.accountId + "." + chatData.chatId));
    intent.putExtra(RemoteReplyReceiver.ACCOUNT_ID_EXTRA, chatData.accountId);
    intent.putExtra(RemoteReplyReceiver.CHAT_ID_EXTRA, chatData.chatId);
    intent.putExtra(RemoteReplyReceiver.MSG_ID_EXTRA, msgId);
    intent.setPackage(context.getPackageName());
    return PendingIntent.getBroadcast(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());
  }

  private PendingIntent getMarkAsReadIntent(ChatData chatData, int msgId, boolean markNoticed) {
    Intent intent =
        new Intent(
            markNoticed ? MarkReadReceiver.MARK_NOTICED_ACTION : MarkReadReceiver.CANCEL_ACTION);
    intent.setClass(context, MarkReadReceiver.class);
    intent.setData(Uri.parse("custom://" + chatData.accountId + "." + chatData.chatId));
    intent.putExtra(MarkReadReceiver.ACCOUNT_ID_EXTRA, chatData.accountId);
    intent.putExtra(MarkReadReceiver.CHAT_ID_EXTRA, chatData.chatId);
    intent.putExtra(MarkReadReceiver.MSG_ID_EXTRA, msgId);
    intent.setPackage(context.getPackageName());
    return PendingIntent.getBroadcast(
        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());
  }

  /**
   * Build a {@link PendingIntent} that is fired by the system when the user
   * swipes the group summary away. Without it, Android keeps the summary
   * alive even after every child notification has been auto-cancelled,
   * leaving a ghostly "BMChat" entry in the drawer (issue reported by the
   * user as a persistent empty BMChat icon in the notification shade).
   */
  private PendingIntent getSummaryDeleteIntent(int accountId) {
    Intent intent = new Intent(MarkReadReceiver.SUMMARY_DISMISSED_ACTION);
    intent.setClass(context, MarkReadReceiver.class);
    intent.setData(Uri.parse("custom-summary://" + accountId));
    intent.putExtra(MarkReadReceiver.ACCOUNT_ID_EXTRA, accountId);
    intent.setPackage(context.getPackageName());
    return PendingIntent.getBroadcast(
        context, accountId, intent, PendingIntent.FLAG_UPDATE_CURRENT | IntentUtils.FLAG_MUTABLE());
  }

  /**
   * Total number of fresh messages across every non-muted account, used as
   * the value of {@link NotificationCompat.Builder#setNumber(int)} on the
   * group summary. Samsung One UI / MIUI / EMUI launchers ignore the
   * legacy ShortcutBadger broadcast and instead read this number off the
   * most recent notification with a non-zero count, so the summary is the
   * single source of truth for the launcher badge.
   */
  private int totalFreshAcrossAccounts() {
    int total = 0;
    try {
      int[] accountIds = ApplicationContext.getDcAccounts().getAll();
      for (int accountId : accountIds) {
        DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
        if (dcContext == null) continue;
        if (dcContext.isMuted()) continue;
        int[] fresh = dcContext.getFreshMsgs();
        if (fresh != null) total += fresh.length;
      }
    } catch (Throwable t) {
      Log.w(TAG, "totalFreshAcrossAccounts failed", t);
    }
    return Math.max(total, 0);
  }

  /**
   * Total number of fresh messages inside a single account; used to decide
   * whether the group summary for that account is still meaningful.
   */
  private int totalFreshInAccount(int accountId) {
    try {
      DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
      if (dcContext == null || dcContext.isMuted()) return 0;
      int[] fresh = dcContext.getFreshMsgs();
      return fresh != null ? fresh.length : 0;
    } catch (Throwable t) {
      Log.w(TAG, "totalFreshInAccount failed", t);
      return 0;
    }
  }

  private String visibleSummary(@NonNull DcMsg msg) {
    return org.thoughtcrime.securesms.update.UpdateBroadcast.strip(msg.getSummarytext(2000));
  }

  // Groups and Notification channel groups
  // --------------------------------------------------------------------------------------------

  // this is just to further organize the appearance of channels in the settings UI
  private static final String CH_GRP_MSG = "chgrp_msg";

  // this is to group together notifications as such, maybe including a summary,
  // see https://developer.android.com/training/notify-user/group.html
  private static final String GRP_MSG = "grp_msg";

  // Notification IDs
  // --------------------------------------------------------------------------------------------

  public static final int ID_PERMANENT = 1;
  public static final int ID_MSG_SUMMARY = 2;
  public static final int ID_GENERIC = 3;
  public static final int ID_FETCH = 4;
  public static final int ID_MSG_OFFSET =
      0; // msgId is added - as msgId start at 10, there are no conflicts with lower numbers

  // Notification channels
  // --------------------------------------------------------------------------------------------

  // Overview:
  // - since SDK 26 (Oreo), a NotificationChannel is a MUST for notifications
  // - NotificationChannels are defined by a channelId
  //   and its user-editable settings have a higher precedence as the Notification.Builder setting
  // - once created, NotificationChannels cannot be modified programmatically
  // - NotificationChannels can be deleted, however, on re-creation with the same id,
  //   it becomes un-deleted with the old user-defined settings
  //
  // How we use Notification channel:
  // - We include the delta-chat-notifications settings into the name of the channelId
  // - The chatId is included only, if there are separate sound- or vibration-settings for a chat
  // - This way, we have stable and few channelIds and the user
  //   can edit the notifications in Delta Chat as well as in the system

  // channelIds: CH_MSG_* are used here, the other ones from outside (defined here to have some
  // overview)
  public static final String CH_MSG_PREFIX = "ch_msg";
  // BMChat: bumped 5 -> 6 in 2.49.8 and 6 -> 7 in 2.49.9 to force a fresh channel with
  // IMPORTANCE_HIGH / VISIBILITY_PUBLIC and an unconditional default sound. Devices that
  // upgraded from older builds may still hold a legacy channel with reduced importance
  // which Android refuses to upgrade in place.
  // BMChat 2.49.54: bumped 7 -> 8 so the channel is recreated with an explicit
  // vibration pattern. Samsung One UI / MIUI silently ignore enableVibration(true)
  // unless a pattern is supplied, which was why users who turned vibration on did
  // not feel anything when a new message arrived.
  public static final String CH_MSG_VERSION = "8";

  // BMChat: standard "tap-tap" vibration pattern used for incoming messages. The
  // initial 0 means "vibrate immediately"; the rest is a wait/vibrate sequence in
  // milliseconds (250 ms pause, 300 ms vibrate, 200 ms pause, 300 ms vibrate).
  private static final long[] DEFAULT_VIBRATE_PATTERN = new long[] {0, 300, 200, 300};
  // BMChat: bumped to _v3 so the channel is recreated as IMPORTANCE_MIN with no badge,
  // collapsing the persistent "BMChat is running in background" drawer entry into a
  // bare status-bar icon.
  public static final String CH_PERMANENT = "bmchat_fg_notification_ch_v4";
  public static final String CH_GENERIC = "ch_generic";
  public static final String CH_CALLS_PREFIX = "call_chan";

  private boolean notificationChannelsSupported() {
    return Build.VERSION.SDK_INT >= 26;
  }

  // full name is "ch_msgV_HASH" or "ch_msgV_HASH.ACCOUNTID.CHATID"
  private String computeChannelId(
      String ledColor, boolean vibrate, @Nullable Uri ringtone, ChatData chatData) {
    String channelId = CH_MSG_PREFIX;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(ledColor.getBytes());
      md.update(vibrate ? (byte) 1 : (byte) 0);
      md.update((ringtone != null ? ringtone.toString() : "").getBytes());
      String hash = String.format("%X", new BigInteger(1, md.digest())).substring(0, 16);

      channelId = CH_MSG_PREFIX + CH_MSG_VERSION + "_" + hash;
      if (chatData != null) {
        channelId += String.format(".%d.%d", chatData.accountId, chatData.chatId);
      }

    } catch (Exception e) {
      Log.e(TAG, e.toString());
    }
    return channelId;
  }

  // return ChatData(ACCOUNTID, CHATID) from "ch_msgV_HASH.ACCOUNTID.CHATID" or null
  private ChatData parseNotificationChannelChat(String channelId) {
    try {
      int point = channelId.lastIndexOf(".");
      if (point > 0) {
        int chatId = Integer.parseInt(channelId.substring(point + 1));
        channelId = channelId.substring(0, point);
        point = channelId.lastIndexOf(".");
        if (point > 0) {
          int accountId = Integer.parseInt(channelId.substring(point + 1));
          return new ChatData(accountId, chatId);
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private String getNotificationChannelGroup(NotificationManagerCompat notificationManager) {
    if (notificationChannelsSupported()
        && notificationManager.getNotificationChannelGroup(CH_GRP_MSG) == null) {
      NotificationChannelGroup chGrp =
          new NotificationChannelGroup(CH_GRP_MSG, context.getString(R.string.pref_chats));
      notificationManager.createNotificationChannelGroup(chGrp);
    }
    return CH_GRP_MSG;
  }

  private String getNotificationChannel(
      NotificationManagerCompat notificationManager, ChatData chatData, DcChat dcChat) {
    String channelId = CH_MSG_PREFIX;

    if (notificationChannelsSupported()) {
      try {
        // get all values we'll use as settings for the NotificationChannel
        String ledColor = Prefs.getNotificationLedColor(context);
        boolean defaultVibrate = effectiveVibrate(chatData);
        @Nullable Uri ringtone = effectiveSound(chatData);
        boolean isIndependent = requiresIndependentChannel(chatData);

        // get channel id from these settings
        channelId =
            computeChannelId(ledColor, defaultVibrate, ringtone, isIndependent ? chatData : null);

        // user-visible name of the channel -
        // we just use the name of the chat or "Default"
        // (the name is shown in the context of the group "Chats" - that should be enough context)
        String name = context.getString(R.string.def);
        if (isIndependent) {
          name = dcChat.getName();
        }

        // check if there is already a channel with the given name
        List<NotificationChannel> channels = notificationManager.getNotificationChannels();
        boolean channelExists = false;
        String currentVersionPrefix = CH_MSG_PREFIX + CH_MSG_VERSION + "_";
        for (int i = 0; i < channels.size(); i++) {
          String currChannelId = channels.get(i).getId();
          if (currChannelId.startsWith(CH_MSG_PREFIX)) {
            // this is one of the message channels handled here ...
            if (currChannelId.equals(channelId)) {
              // ... this is the actually required channel, fine :)
              // update the name to reflect localize changes and chat renames
              channelExists = true;
              channels.get(i).setName(name);
            } else if (!currChannelId.startsWith(currentVersionPrefix)) {
              // BMChat: legacy channel from an older CH_MSG_VERSION; user installs that
              // jumped through 2.49.<7 may have a stale channel with reduced importance
              // (e.g. IMPORTANCE_DEFAULT/LOW) that Android refuses to upgrade in place.
              // Drop it so the freshly created v6 channel can take over heads-up duties.
              notificationManager.deleteNotificationChannel(currChannelId);
            } else {
              // ... another v6 message channel, delete if it is not in use.
              ChatData currChat = parseNotificationChannelChat(currChannelId);
              if (!currChannelId.equals(
                  computeChannelId(
                      ledColor, effectiveVibrate(currChat), effectiveSound(currChat), currChat))) {
                notificationManager.deleteNotificationChannel(currChannelId);
              }
            }
          }
        }

        // create a channel with the given settings;
        // we cannot change the settings, however, this is handled by using different values for
        // chId
        if (!channelExists) {
          NotificationChannel channel =
              new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH);
          channel.setDescription("Informs about new messages.");
          channel.setGroup(getNotificationChannelGroup(notificationManager));
          channel.enableVibration(defaultVibrate);
          if (defaultVibrate) {
            // BMChat: Samsung One UI and MIUI quietly drop the vibration when only
            // enableVibration(true) is set. Supplying an explicit pattern forces the
            // OEM notification stack to actually trigger the haptic motor.
            channel.setVibrationPattern(DEFAULT_VIBRATE_PATTERN);
          }
          channel.setShowBadge(true);
          // BMChat: heads-up notifications must be visible on the lock screen too,
          // otherwise an incoming message during screen-off / lock looks like the
          // app does not deliver anything.
          channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

          if (!ledColor.equals("none")) {
            channel.enableLights(true);
            channel.setLightColor(getLedArgb(ledColor));
          } else {
            channel.enableLights(false);
          }

          // BMChat: heads-up reliably triggers only when the channel has a sound;
          // Samsung/MIUI silently demote a high-importance "silent" channel to a
          // drawer entry. So if the user has not picked a custom ringtone we
          // unconditionally fall back to the platform default notification sound.
          Uri effectiveRingtone = ringtone;
          if (effectiveRingtone == null || TextUtils.isEmpty(effectiveRingtone.toString())) {
            try {
              effectiveRingtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            } catch (Throwable t) {
              effectiveRingtone = null;
            }
          }
          if (effectiveRingtone != null) {
            channel.setSound(
                effectiveRingtone,
                new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build());
          }

          notificationManager.createNotificationChannel(channel);
        }
      } catch (Exception e) {
        Log.e(TAG, "Error in getNotificationChannel()", e);
      }
    }

    return channelId;
  }

  public String getCallNotificationChannel(
      NotificationManagerCompat notificationManager, ChatData chatData, String name) {
    String channelId = CH_CALLS_PREFIX + "-" + chatData.accountId + "-" + chatData.chatId;

    if (notificationChannelsSupported()) {
      try {
        name = "(calls) " + name;

        // check if there is already a channel with the given name
        List<NotificationChannel> channels = notificationManager.getNotificationChannels();
        boolean channelExists = false;
        for (int i = 0; i < channels.size(); i++) {
          String currChannelId = channels.get(i).getId();
          if (currChannelId.startsWith(CH_CALLS_PREFIX)) {
            // this is one of the calls channels handled here ...
            if (currChannelId.equals(channelId)) {
              // ... this is the actually required channel, fine :)
              // update the name to reflect localize changes and chat renames
              channelExists = true;
              channels.get(i).setName(name);
            }
          }
        }

        // create the channel
        if (!channelExists) {
          NotificationChannel channel =
              new NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_MAX);
          channel.setDescription("Informs about incoming calls.");
          channel.setShowBadge(true);

          Uri ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
          channel.setSound(
              ringtone,
              new AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build());
          notificationManager.createNotificationChannel(channel);
        }
      } catch (Exception e) {
        Log.e(TAG, "Error in getCallNotificationChannel()", e);
      }
    }

    return channelId;
  }

  // add notifications & co.
  // --------------------------------------------------------------------------------------------

  public void notifyMessage(int accountId, int chatId, int msgId) {
    // BMChat 2.49.61: explicit entry/exit logs on every notify path. Users
    // were reporting that incoming messages and reactions arrived in the
    // chat but produced no system notification, and we had no easy way to
    // tell *where* the silent skip happened. Tag every gate-keeper return
    // with a Log.i so a single `adb logcat -s NotificationCenter` shows the
    // exact reason.
    android.util.Log.i(
        TAG,
        "notifyMessage entry account=" + accountId + " chat=" + chatId + " msg=" + msgId);
    Util.runOnAnyBackgroundThread(
        () -> {
          DcContext dcContext = context.getDcAccounts().getAccount(accountId);
          DcChat dcChat = dcContext.getChat(chatId);

          DcMsg dcMsg = dcContext.getMsg(msgId);
          NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);

          DcContact senderContact = dcContext.getContact(dcMsg.getFromId());
          String senderName =
              BMChatNames.humanize(
                  dcMsg.getSenderName(senderContact), senderContact.getAddr());

          String shortLine =
              privacy.isDisplayMessage()
                  ? visibleSummary(dcMsg)
                  : context.getString(R.string.notify_new_message);
          if (dcChat.isMultiUser() && privacy.isDisplayContact()) {
            shortLine = senderName + ": " + shortLine;
          }
          String tickerLine = shortLine;
          if (!dcChat.isMultiUser() && privacy.isDisplayContact()) {
            tickerLine = senderName + ": " + tickerLine;

            if (dcMsg.getOverrideSenderName() != null) {
              // There is an "overridden" display name on the message, so, we need to prepend the
              // display name to the message,
              // i.e. set the shortLine to be the same as the tickerLine.
              shortLine = tickerLine;
            }
          }

          DcMsg quotedMsg = dcMsg.getQuotedMsg();
          boolean isMention = dcChat.isMultiUser() && quotedMsg != null && quotedMsg.isOutgoing();

          maybeAddNotification(accountId, dcChat, msgId, shortLine, tickerLine, true, isMention);
        });
  }

  public void notifyReaction(int accountId, int contactId, int msgId, String reaction) {
    Util.runOnAnyBackgroundThread(
        () -> {
          DcContext dcContext = context.getDcAccounts().getAccount(accountId);
          DcMsg dcMsg = dcContext.getMsg(msgId);

          NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);
          if (!privacy.isDisplayContact() || !privacy.isDisplayMessage()) {
            return; // showing "New Message" is wrong and showing "New Reaction" is already content.
            // just do nothing.
          }

          DcContact sender = dcContext.getContact(contactId);
          String senderName =
              BMChatNames.humanize(sender.getDisplayName(), sender.getAddr());
          String shortLine =
              context.getString(
                  R.string.reaction_by_other,
                  senderName,
                  reaction,
                  visibleSummary(dcMsg));
          DcChat dcChat = dcContext.getChat(dcMsg.getChatId());
          maybeAddNotification(
              accountId, dcChat, msgId, shortLine, shortLine, false, dcChat.isMultiUser());
        });
  }

  public void notifyWebxdc(int accountId, int contactId, int msgId, String text) {
    Util.runOnAnyBackgroundThread(
        () -> {
          NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);
          if (!privacy.isDisplayContact() || !privacy.isDisplayMessage()) {
            return; // showing "New Message" is wrong, just do nothing.
          }

          DcContext dcContext = context.getDcAccounts().getAccount(accountId);
          DcMsg dcMsg = dcContext.getMsg(msgId);
          DcMsg parentMsg;
          if (dcMsg.getType() == DcMsg.DC_MSG_WEBXDC) {
            parentMsg = dcMsg;
          } else { // info message, get parent xdc
            parentMsg = dcMsg.getParent() != null ? dcMsg.getParent() : dcMsg;
          }

          if (Util.equals(visibleWebxdc, new Pair<>(accountId, parentMsg.getId()))) {
            return; // do not notify if the app is already open
          }

          JSONObject info = parentMsg.getWebxdcInfo();
          final String name = JsonUtils.optString(info, "name");
          String shortLine = name.isEmpty() ? text : (name + ": " + text);
          DcChat dcChat = dcContext.getChat(dcMsg.getChatId());
          maybeAddNotification(
              accountId, dcChat, msgId, shortLine, shortLine, false, dcChat.isMultiUser());
        });
  }

  @WorkerThread
  private void maybeAddNotification(
      int accountId,
      DcChat dcChat,
      int msgId,
      String shortLine,
      String tickerLine,
      boolean playInChatSound,
      boolean isMention) {
    DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
    int chatId = dcChat.getId();
    ChatData chatData = new ChatData(accountId, chatId);
    isMention = isMention && dcContext.isMentionsEnabled();

    if (dcContext.isMuted() || (!isMention && dcChat.isMuted())) {
      android.util.Log.i(
          TAG,
          "maybeAddNotification skip muted account=" + accountId + " chat=" + chatId
              + " accMuted=" + dcContext.isMuted() + " chatMuted=" + dcChat.isMuted());
      return;
    }

    // BMChat suppresses notifications for noise that is not part of a real
    // chat: mailing lists. Unencrypted contact requests (classic email)
    // *do* surface a notification — BMChat is also used as a plain email
    // client, and users complained that bare emails arrived completely
    // silently because they all land in the contact-request inbox at first.
    if (dcChat.isMailingList()) {
      android.util.Log.i(
          TAG, "maybeAddNotification skip mailingList chat=" + chatId);
      return;
    }

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && !notificationManager.areNotificationsEnabled()) {
      android.util.Log.w(
          TAG,
          "maybeAddNotification skip: app-level notifications disabled (POST_NOTIFICATIONS)");
      return;
    }

    if (Util.equals(visibleChat, chatData)) {
      android.util.Log.i(
          TAG, "maybeAddNotification skip: chat is currently visible chat=" + chatId);
      if (playInChatSound && Prefs.isInChatNotifications(context)) {
        InChatSounds.getInstance(context).playIncomingSound();
      }
      return;
    }
    android.util.Log.i(
        TAG, "maybeAddNotification proceeding chat=" + chatId + " msg=" + msgId);

    NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);
    long now = System.currentTimeMillis();
    boolean signal = (now - lastAudibleNotification) > MIN_AUDIBLE_PERIOD_MILLIS;
    if (signal) {
      lastAudibleNotification = now;
    }

    // create a basic notification
    // even without a name or message displayed,
    // it makes sense to use separate notification channels and to open the respective chat directly
    // -
    // the user may eg. have chosen a different sound
    String notificationChannel = getNotificationChannel(notificationManager, chatData, dcChat);

    LinkedHashMap<Integer, String> messagesForInbox = null;
    if (privacy.isDisplayContact() && privacy.isDisplayMessage()) {
      synchronized (inboxes) {
        HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox = inboxes.get(accountId);
        if (accountInbox == null) {
          accountInbox = new HashMap<>();
          inboxes.put(accountId, accountInbox);
        }
        LinkedHashMap<Integer, String> messages = accountInbox.get(chatId);
        if (messages == null) {
          messages = new LinkedHashMap<>();
          accountInbox.put(chatId, messages);
        }
        messages.put(msgId, shortLine);
        messagesForInbox = new LinkedHashMap<>(messages);
      }
    }

    int cnt = dcContext.getFreshMsgCount(chatId);
    buildAndShowChatNotification(
        accountId,
        chatId,
        msgId,
        dcContext,
        dcChat,
        notificationChannel,
        shortLine,
        tickerLine,
        signal,
        messagesForInbox,
        cnt,
        true);
  }

  @WorkerThread
  private void buildAndShowChatNotification(
      int accountId,
      int chatId,
      int msgId,
      DcContext dcContext,
      DcChat dcChat,
      String notificationChannel,
      String contentText,
      String ticker,
      boolean signal,
      LinkedHashMap<Integer, String> messagesForInbox,
      int messageCount,
      boolean includeSummary) {
    try {
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      NotificationPrivacyPreference privacy = Prefs.getNotificationPrivacy(context);
      ChatData chatData = new ChatData(accountId, chatId);

      // BMChat: even on Android 8+ where importance is decided by the channel, keep the
      // legacy priority high so old OEM ROMs (some Samsung One UI variants) actually pop a
      // heads-up bubble for incoming messages instead of silently appending to the drawer.
      int legacyPriority =
          Math.max(Prefs.getNotificationPriority(context), NotificationCompat.PRIORITY_HIGH);

      NotificationCompat.Builder builder =
          new NotificationCompat.Builder(context, notificationChannel)
              .setSmallIcon(R.drawable.icon_notification)
              .setColor(context.getResources().getColor(R.color.delta_primary))
              .setPriority(legacyPriority)
              .setCategory(NotificationCompat.CATEGORY_MESSAGE)
              // BMChat: heads-up + lock-screen visibility for incoming messages.
              .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
              .setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)
              .setOnlyAlertOnce(!signal)
              .setAutoCancel(true)
              .setWhen(System.currentTimeMillis())
              .setShowWhen(true)
              .setContentText(contentText)
              .setDeleteIntent(getMarkAsReadIntent(chatData, msgId, false))
              .setContentIntent(getOpenChatIntent(chatData));

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        builder.setGroup(GRP_MSG + "." + accountId);
        // BMChat: there is no longer a summary notification — every
        // child is the one that rings, so each chat alerts the user
        // exactly once when a fresh message lands. Android still
        // clusters them into a stack when there are several, but
        // without an empty "BMChat" header.
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
      }

      String accountTag = dcContext.getConfig(CONFIG_PRIVATE_TAG);
      if (accountTag.isEmpty() && ApplicationContext.getDcAccounts().getAll().length > 1) {
        accountTag = dcContext.getName();
      }

      if (privacy.isDisplayContact()) {
        String chatTitle = dcChat.getName();
        if (!dcChat.isMultiUser()) {
          int[] memberIds = dcContext.getChatContacts(chatId);
          if (memberIds.length >= 1) {
            DcContact peer = dcContext.getContact(memberIds[0]);
            chatTitle = BMChatNames.humanize(peer.getDisplayName(), peer.getAddr());
          }
        }
        builder.setContentTitle(chatTitle);
        if (!TextUtils.isEmpty(accountTag)) {
          builder.setSubText(accountTag);
        }
      }

      if (ticker != null) {
        builder.setTicker(ticker);
      }

      // Set sound, vibrate, led for systems that do not have notification channels
      if (!notificationChannelsSupported()) {
        if (signal) {
          Uri sound = effectiveSound(chatData);
          if (sound == null || TextUtils.isEmpty(sound.toString())) {
            // BMChat: fall back to the platform default notification sound so a
            // freshly installed device with no ringtone preference still beeps.
            sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
          }
          if (sound != null) {
            builder.setSound(sound);
          }
          boolean vibrate = effectiveVibrate(chatData);
          if (vibrate) {
            builder.setVibrate(DEFAULT_VIBRATE_PATTERN);
          }
        }
        String ledColor = Prefs.getNotificationLedColor(context);
        if (!ledColor.equals("none")) {
          builder.setLights(getLedArgb(ledColor), 500, 2000);
        }
      } else if (signal && effectiveVibrate(chatData)) {
        // BMChat: on Android 8+ the channel decides everything, but some OEM
        // builds (Samsung One UI 6/7, MIUI 14) still consult the legacy
        // notification fields. Setting an explicit pattern here is a harmless
        // belt-and-braces guarantee that the device actually vibrates.
        builder.setVibrate(DEFAULT_VIBRATE_PATTERN);
      }

      // Set avatar (chat avatar for collapsed view; MessagingStyle replaces it with the
      // sender avatar in expanded view).
      if (privacy.isDisplayContact()) {
        Bitmap bitmap = getAvatar(dcChat);
        if (bitmap != null) {
          builder.setLargeIcon(bitmap);
        }
      }

      // Add buttons that allow some actions without opening Delta Chat.
      // If privacy options are enabled, the buttons are not added.
      if (privacy.isDisplayContact() && privacy.isDisplayMessage()) {
        try {
          PendingIntent inNotificationReplyIntent = getRemoteReplyIntent(chatData, msgId);
          PendingIntent markReadIntent = getMarkAsReadIntent(chatData, msgId, true);

          NotificationCompat.Action markAsReadAction =
              new NotificationCompat.Action(
                  R.drawable.check, context.getString(R.string.mark_as_read_short), markReadIntent);

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationCompat.Action replyAction =
                new NotificationCompat.Action.Builder(
                        R.drawable.ic_reply_white_36dp,
                        context.getString(R.string.notify_reply_button),
                        inNotificationReplyIntent)
                    .addRemoteInput(
                        new RemoteInput.Builder(RemoteReplyReceiver.EXTRA_REMOTE_REPLY)
                            .setLabel(context.getString(R.string.notify_reply_button))
                            .build())
                    .build();
            builder.addAction(replyAction);
          }

          NotificationCompat.Action wearableReplyAction =
              new NotificationCompat.Action.Builder(
                      R.drawable.ic_reply,
                      context.getString(R.string.notify_reply_button),
                      inNotificationReplyIntent)
                  .addRemoteInput(
                      new RemoteInput.Builder(RemoteReplyReceiver.EXTRA_REMOTE_REPLY)
                          .setLabel(context.getString(R.string.notify_reply_button))
                          .build())
                  .build();
          builder.addAction(markAsReadAction);
          builder.extend(
              new NotificationCompat.WearableExtender()
                  .addAction(markAsReadAction)
                  .addAction(wearableReplyAction));
        } catch (Exception e) {
          Log.w(TAG, e);
        }
      }

      // BMChat: MessagingStyle gives a proper Telegram/Signal-like preview with sender
      // name and avatar per line, both in heads-up and expanded drawer entries.
      if (privacy.isDisplayContact() && privacy.isDisplayMessage() && messagesForInbox != null) {
        try {
          String selfName = dcContext.getConfig("displayname");
          if (TextUtils.isEmpty(selfName)) selfName = dcContext.getName();
          if (TextUtils.isEmpty(selfName)) selfName = "Me";
          Person selfPerson =
              new Person.Builder().setName(selfName).setKey("self." + accountId).build();

          NotificationCompat.MessagingStyle style =
              new NotificationCompat.MessagingStyle(selfPerson);
          if (dcChat.isMultiUser()) {
            style.setConversationTitle(dcChat.getName());
            style.setGroupConversation(true);
          } else {
            style.setGroupConversation(false);
          }

          for (Map.Entry<Integer, String> entry : messagesForInbox.entrySet()) {
            int mid = entry.getKey();
            String fallback = entry.getValue();
            try {
              DcMsg m = dcContext.getMsg(mid);
              if (m == null) {
                style.addMessage(fallback, System.currentTimeMillis(), (Person) null);
                continue;
              }
              DcContact senderContact = dcContext.getContact(m.getFromId());
              String senderName =
                  BMChatNames.humanize(
                      senderContact.getDisplayName(), senderContact.getAddr());
              Person.Builder pb =
                  new Person.Builder()
                      .setName(senderName)
                      .setKey("sender." + senderContact.getId());
              Bitmap senderAvatar = getAvatarForContact(senderContact);
              if (senderAvatar != null) {
                pb.setIcon(IconCompat.createWithBitmap(senderAvatar));
              }
              Person person = pb.build();

              String text = visibleSummary(m);
              if (TextUtils.isEmpty(text)) text = fallback;
              long ts = m.getTimestamp() * 1000L;
              if (ts <= 0) ts = System.currentTimeMillis();
              style.addMessage(text, ts, person);
            } catch (Throwable t) {
              style.addMessage(fallback, System.currentTimeMillis(), (Person) null);
            }
          }
          builder.setStyle(style);
        } catch (Exception e) {
          Log.w(TAG, e);
        }
      }

      // BMChat: with the group summary gone (see below) every child row
      // is also the badge driver. We put the total fresh-message count
      // across every account in setNumber() so the launcher icon shows
      // the same total the user sees in the chat list, regardless of
      // which notification happens to be the "freshest" on Samsung
      // One UI / MIUI / EMUI launchers.
      int totalFresh = totalFreshAcrossAccounts();
      builder.setContentInfo(String.valueOf(messageCount));
      builder.setNumber(totalFresh > 0 ? totalFresh : messageCount);

      // Show notification
      // try..catch potentially needed for very specific devices
      try {
        notificationManager.notify(
            String.valueOf(accountId), ID_MSG_OFFSET + chatId, builder.build());
      } catch (Exception e) {
        Log.e(TAG, "cannot add notification", e);
      }

      // BMChat: refresh the launcher icon unread badge for OEMs that don't
      // pick up Notification.setNumber() on their own.
      BMChatBadge.refreshSync(context);

      // BMChat: deliberately *not* posting a group summary notification.
      // The Telegram-style UX the user wants is one row per chat,
      // automatically clustered by Android 7+ via setGroup(), and
      // nothing else in the drawer. The previous "bare BMChat / BMChat"
      // entry the user complained about was exactly this summary
      // showing up empty whenever a child notification was dismissed
      // before its sibling, or after a process restart that lost the
      // in-memory inbox state. Removing the summary entirely makes the
      // failure mode go away. Any legacy summary that may still be
      // around from an older version is cleaned up by
      // reconcileAccount() on app resume.
      try {
        notificationManager.cancel(String.valueOf(accountId), ID_MSG_SUMMARY);
      } catch (Exception ignored) {
      }
    } catch (Exception e) {
      Log.e(TAG, "cannot show notification", e);
    }
  }

  @WorkerThread
  private void rebuildNotification(
      int accountId, int chatId, LinkedHashMap<Integer, String> messages) {
    try {
      DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
      DcChat dcChat = dcContext.getChat(chatId);

      if (dcContext.isMuted() || dcChat.isMuted()) {
        return;
      }

      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          && !notificationManager.areNotificationsEnabled()) {
        return;
      }

      // Get the latest message ID (last entry in LinkedHashMap)
      Integer latestMsgId = null;
      String lastLine = null;
      for (Map.Entry<Integer, String> entry : messages.entrySet()) {
        latestMsgId = entry.getKey();
        lastLine = entry.getValue();
      }
      if (latestMsgId == null || lastLine == null) {
        return;
      }

      ChatData chatData = new ChatData(accountId, chatId);
      String notificationChannel = getNotificationChannel(notificationManager, chatData, dcChat);

      int cnt = dcContext.getFreshMsgCount(chatId);
      buildAndShowChatNotification(
          accountId,
          chatId,
          latestMsgId,
          dcContext,
          dcChat,
          notificationChannel,
          lastLine,
          null,
          false,
          messages,
          cnt,
          false);

    } catch (Exception e) {
      Log.e(TAG, "cannot rebuild notification", e);
    }
  }

  /**
   * BMChat: avatar for a single sender, used for {@link NotificationCompat.MessagingStyle}
   * Person icons in group chats so that each message row renders the actual sender photo.
   */
  public Bitmap getAvatarForContact(DcContact dcContact) {
    if (dcContact == null) return null;
    try {
      Recipient recipient = new Recipient(context, dcContact);
      return renderAvatarBitmap(recipient);
    } catch (Throwable t) {
      Log.w(TAG, t);
      return null;
    }
  }

  public Bitmap getAvatar(DcChat dcChat) {
    Recipient recipient = new Recipient(context, dcChat);
    return renderAvatarBitmap(recipient);
  }

  private Bitmap renderAvatarBitmap(Recipient recipient) {
    try {
      Drawable drawable;
      ContactPhoto contactPhoto = recipient.getContactPhoto(context);
      if (contactPhoto != null) {
        drawable =
            GlideApp.with(context.getApplicationContext())
                .load(contactPhoto)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .circleCrop()
                .submit(
                    context
                        .getResources()
                        .getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                    context
                        .getResources()
                        .getDimensionPixelSize(android.R.dimen.notification_large_icon_height))
                .get();

      } else {
        drawable =
            recipient
                .getFallbackContactPhoto()
                .asDrawable(context, recipient.getFallbackAvatarColor());
      }
      if (drawable != null) {
        int wh = context.getResources().getDimensionPixelSize(R.dimen.contact_photo_target_size);
        return BitmapUtil.createFromDrawable(drawable, wh, wh);
      }
    } catch (Exception e) {
      Log.w(TAG, e);
    }

    return null;
  }

  public void removeNotification(int accountId, int chatId, int msgId) {
    boolean shouldCancelNotification = false;
    LinkedHashMap<Integer, String> remainingMessages = null;

    synchronized (inboxes) {
      HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox = inboxes.get(accountId);
      if (accountInbox != null) {
        LinkedHashMap<Integer, String> messages = accountInbox.get(chatId);
        if (messages != null) {
          messages.remove(msgId);
          if (messages.isEmpty()) {
            accountInbox.remove(chatId);
            shouldCancelNotification = true;
          } else {
            remainingMessages = new LinkedHashMap<>(messages);
          }
        } else {
          // The inbox does not know about this message — most likely the
          // process was restarted after the notification was posted, so
          // our in-memory cache is empty even though Android still holds
          // the live notification. Cancel it anyway so swipe / mark-read
          // actions on a stale entry don't leave a phantom in the drawer.
          shouldCancelNotification = true;
        }
      } else {
        shouldCancelNotification = true;
      }
    }

    if (shouldCancelNotification) {
      try {
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        String tag = String.valueOf(accountId);
        notificationManager.cancel(tag, ID_MSG_OFFSET + chatId);
        // The group summary stays alive as long as anything in this
        // account is still unread, even if it lives in a different chat.
        if (totalFreshInAccount(accountId) == 0) {
          notificationManager.cancel(tag, ID_MSG_SUMMARY);
        }
      } catch (Exception e) {
        Log.w(TAG, e);
      }
    } else if (remainingMessages != null && !remainingMessages.isEmpty()) {
      rebuildNotification(accountId, chatId, remainingMessages);
    }

    BMChatBadge.refresh(context);
  }

  public void removeNotifications(int accountId, int chatId) {
    synchronized (inboxes) {
      HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox = inboxes.get(accountId);
      if (accountInbox != null) {
        accountInbox.remove(chatId);
      }
    }

    // Cancel notifications irrespective of the in-memory inboxes map:
    // a restart may have left live notifications in the system tray
    // without an entry here, and we still want to clear them out.
    try {
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      String tag = String.valueOf(accountId);
      notificationManager.cancel(tag, ID_MSG_OFFSET + chatId);
      if (totalFreshInAccount(accountId) == 0) {
        notificationManager.cancel(tag, ID_MSG_SUMMARY);
      }
    } catch (Exception e) {
      Log.w(TAG, e);
    }

    BMChatBadge.refresh(context);
  }

  public void removeAllNotifications(int accountId) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    String tag = String.valueOf(accountId);
    synchronized (inboxes) {
      HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox = inboxes.get(accountId);
      notificationManager.cancel(tag, ID_MSG_SUMMARY);
      if (accountInbox != null) {
        for (Integer chatId : accountInbox.keySet()) {
          notificationManager.cancel(tag, ID_MSG_OFFSET + chatId);
        }
        accountInbox.clear();
      }
    }
    // Also sweep anything still alive that the in-memory inbox didn't know
    // about (process restart, race with core events, …).
    cancelStrayNotifications(notificationManager, tag);
    BMChatBadge.refresh(context);
  }

  /**
   * BMChat: synchronise the system notification tray with the core's
   * current fresh-message state for an account. Cancels any per-chat
   * notification whose chat no longer has unread messages and drops the
   * group summary when nothing is left.
   *
   * <p>Called from {@code ConversationListActivity.onResume()} and after
   * {@code DC_EVENT_MSGS_NOTICED} so a "phantom BMChat" entry in the
   * drawer cannot survive a restart, a remote read from another device,
   * or any other path that races with our local bookkeeping.
   */
  public void reconcileAccount(int accountId) {
    try {
      NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
      String tag = String.valueOf(accountId);
      DcContext dcContext = ApplicationContext.getDcAccounts().getAccount(accountId);
      if (dcContext == null) return;

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        android.service.notification.StatusBarNotification[] active = null;
        try {
          NotificationManager nm = (NotificationManager) context.getSystemService(
              Context.NOTIFICATION_SERVICE);
          if (nm != null) active = nm.getActiveNotifications();
        } catch (Throwable ignored) {
        }
        if (active != null) {
          for (android.service.notification.StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            if (!tag.equals(sbn.getTag())) continue;
            int nid = sbn.getId();
            if (nid == ID_MSG_SUMMARY) continue;
            if (nid <= ID_MSG_OFFSET) continue;
            int chatId = nid - ID_MSG_OFFSET;
            if (dcContext.getFreshMsgCount(chatId) <= 0) {
              notificationManager.cancel(tag, nid);
              synchronized (inboxes) {
                HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox =
                    inboxes.get(accountId);
                if (accountInbox != null) accountInbox.remove(chatId);
              }
            }
          }
        }
      }

      // BMChat: there is no longer a group summary in the new layout.
      // Drop any legacy summary that survived an upgrade from <2.49.45
      // unconditionally — keeping it would resurrect the "bare BMChat
      // entry in the shade" complaint the user reported.
      notificationManager.cancel(tag, ID_MSG_SUMMARY);
    } catch (Throwable t) {
      Log.w(TAG, "reconcileAccount failed", t);
    }
    BMChatBadge.refresh(context);
  }

  /** Reconcile every known account; safe to call from any thread. */
  public void reconcileAllAccounts() {
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            int[] ids = ApplicationContext.getDcAccounts().getAll();
            for (int id : ids) reconcileAccount(id);
          } catch (Throwable t) {
            Log.w(TAG, "reconcileAllAccounts failed", t);
          }
        });
  }

  /**
   * Last-resort sweep: walk every active status-bar notification posted by
   * this app with the given tag and cancel anything that looks like a
   * per-chat row but is no longer represented in {@link #inboxes}. Keeps
   * us correct when {@link #removeAllNotifications(int)} runs on a
   * restarted process whose in-memory inbox is empty.
   */
  private void cancelStrayNotifications(NotificationManagerCompat notificationManager, String tag) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
    try {
      NotificationManager nm = (NotificationManager) context.getSystemService(
          Context.NOTIFICATION_SERVICE);
      if (nm == null) return;
      for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
        if (sbn == null) continue;
        if (!tag.equals(sbn.getTag())) continue;
        int nid = sbn.getId();
        if (nid == ID_PERMANENT) continue; // foreground service
        if (nid == ID_FETCH) continue;     // fetch foreground service
        if (nid == ID_GENERIC) continue;
        notificationManager.cancel(tag, nid);
      }
    } catch (Throwable ignored) {
    }
  }

  /**
   * Called by {@link MarkReadReceiver} when the group summary is swiped
   * away. The user explicitly said "I'm not interested in these right
   * now", so we drop the in-memory inbox state for that account and let
   * any future incoming message rebuild it from scratch. We deliberately
   * do *not* call markseen on the core: the messages stay unread in the
   * chat list, just like Telegram does.
   */
  public void onSummaryDismissed(int accountId) {
    synchronized (inboxes) {
      HashMap<Integer, LinkedHashMap<Integer, String>> accountInbox = inboxes.get(accountId);
      if (accountInbox != null) accountInbox.clear();
    }
    BMChatBadge.refresh(context);
  }

  public void updateVisibleChat(int accountId, int chatId) {
    Util.runOnAnyBackgroundThread(
        () -> {
          if (accountId != 0 && chatId != 0) {
            visibleChat = new ChatData(accountId, chatId);
            removeNotifications(accountId, chatId);
          } else {
            visibleChat = null;
          }
        });
  }

  public void clearVisibleChat() {
    visibleChat = null;
  }

  public void updateVisibleWebxdc(int accountId, int msgId) {
    if (accountId != 0 && msgId != 0) {
      visibleWebxdc = new Pair<>(accountId, msgId);
    } else {
      visibleWebxdc = null;
    }
  }

  public void clearVisibleWebxdc() {
    visibleWebxdc = null;
  }

  public void maybePlaySendSound(DcChat dcChat) {
    if (Prefs.isInChatNotifications(context) && !dcChat.isMuted()) {
      InChatSounds.getInstance(context).playSendSound();
    }
  }

  public static class ChatData {
    public final int accountId;
    public final int chatId;

    public ChatData(int accountId, int chatId) {
      this.accountId = accountId;
      this.chatId = chatId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ChatData chatData = (ChatData) o;
      return accountId == chatData.accountId && chatId == chatData.chatId;
    }
  }
}
