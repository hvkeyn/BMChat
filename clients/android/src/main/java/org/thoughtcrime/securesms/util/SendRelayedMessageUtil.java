package org.thoughtcrime.securesms.util;

import static org.thoughtcrime.securesms.util.ShareUtil.getForwardedMessageAccountId;
import static org.thoughtcrime.securesms.util.ShareUtil.getForwardedMessageIDs;
import static org.thoughtcrime.securesms.util.ShareUtil.getSharedText;
import static org.thoughtcrime.securesms.util.ShareUtil.getSharedUris;
import static org.thoughtcrime.securesms.util.ShareUtil.isForwarding;
import static org.thoughtcrime.securesms.util.ShareUtil.isSharing;
import static org.thoughtcrime.securesms.util.ShareUtil.resetRelayingMessageContent;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.thoughtcrime.securesms.ConversationListRelayingActivity;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.providers.PersistentBlobProvider;

public class SendRelayedMessageUtil {

  public static void immediatelyRelay(Activity activity, int chatId) {
    immediatelyRelay(activity, new Long[] {(long) chatId});
  }

  public static void immediatelyRelay(Activity activity, final Long[] chatIds) {
    ConversationListRelayingActivity.finishActivity();
    if (isForwarding(activity)) {
      int forwardedMsgAccId = getForwardedMessageAccountId(activity);
      int[] forwardedMessageIDs = getForwardedMessageIDs(activity);
      resetRelayingMessageContent(activity);
      if (forwardedMessageIDs == null || forwardedMsgAccId <= 0) return;

      Util.runOnAnyBackgroundThread(
          () -> {
            DcContext dcContext = DcHelper.getContext(activity);
            int accId = dcContext.getAccountId();
            if (forwardedMsgAccId != accId) {
              Rpc rpc = DcHelper.getRpc(activity);
              List<Integer> list = Util.toList(forwardedMessageIDs);
              for (long longChatId : chatIds) {
                try {
                  rpc.forwardMessagesToAccount(forwardedMsgAccId, list, accId, (int) longChatId);
                } catch (RpcException e) {
                  e.printStackTrace();
                }
              }
              return;
            }

            for (long longChatId : chatIds) {
              int chatId = (int) longChatId;
              if (dcContext.getChat(chatId).isSelfTalk()) {
                for (int msgId : forwardedMessageIDs) {
                  DcMsg msg = dcContext.getMsg(msgId);
                  if (msg.canSave() && msg.getSavedMsgId() == 0 && msg.getChatId() != chatId) {
                    dcContext.saveMsgs(new int[] {msgId});
                  } else {
                    handleForwarding(activity, chatId, new int[] {msgId});
                  }
                }
              } else {
                handleForwarding(activity, chatId, forwardedMessageIDs);
              }
            }
          });
    } else if (isSharing(activity)) {
      ArrayList<Uri> sharedUris = getSharedUris(activity);
      String sharedText = getSharedText(activity);
      resetRelayingMessageContent(activity);
      Util.runOnAnyBackgroundThread(
          () -> {
            for (long chatId : chatIds) {
              sendMultipleMsgs(activity, (int) chatId, sharedUris, sharedText);
            }
          });
    }
  }

  private static void handleForwarding(Context context, int chatId, int[] forwardedMessageIDs) {
    DcContext dcContext = DcHelper.getContext(context);
    dcContext.forwardMsgs(forwardedMessageIDs, chatId);
  }

  public static void sendMultipleMsgs(
      Context context, int chatId, ArrayList<Uri> sharedUris, String sharedText) {
    DcContext dcContext = DcHelper.getContext(context);
    ArrayList<Uri> uris = sharedUris;
    String text = sharedText;

    if (uris.size() == 1) {
      dcContext.sendMsg(chatId, createMessage(context, uris.get(0), text));
    } else {
      if (text != null) {
        dcContext.sendMsg(chatId, createMessage(context, null, text));
      }
      for (Uri uri : uris) {
        dcContext.sendMsg(chatId, createMessage(context, uri, null));
      }
    }
  }

  /**
   * BMChat 2.49.57: Telegram-style media album send path.
   *
   * <p>Each photo / video in the picked group goes out as its own DcMsg,
   * but every message has the same {@code album_id} stamped into its
   * caption via {@link
   * org.thoughtcrime.securesms.album.AlbumMarker AlbumMarker} so the
   * receiver can render a "Альбом 1/N" header above each bubble and
   * group the bubbles visually. Documents / audio aren't routed here —
   * those go through {@link #sendMultipleMsgs} unchanged.
   *
   * <p>The shared caption is appended to the {@em first} message of the
   * album only, mirroring Telegram's "single caption per group"
   * convention.
   */
  public static void sendAlbum(
      Context context, int chatId, ArrayList<Uri> mediaUris, String sharedText) {
    if (mediaUris == null || mediaUris.isEmpty()) return;
    DcContext dcContext = DcHelper.getContext(context);
    if (mediaUris.size() == 1) {
      // Not really an album — fall through to the regular send path so
      // there's no stray marker on a single photo.
      dcContext.sendMsg(chatId, createMessage(context, mediaUris.get(0), sharedText));
      return;
    }
    String albumId = newAlbumId();
    int total = mediaUris.size();
    for (int i = 0; i < total; i++) {
      String visibleText = (i == 0 && sharedText != null && !sharedText.isEmpty()) ? sharedText : "";
      String caption =
          org.thoughtcrime.securesms.album.AlbumMarker.append(
              visibleText, new org.thoughtcrime.securesms.album.AlbumMarker.Info(
                  albumId, i + 1, total));
      dcContext.sendMsg(chatId, createMessage(context, mediaUris.get(i), caption));
    }
  }

  private static String newAlbumId() {
    // 12 random hex chars are plenty unique within a chat — collisions
    // between two different albums in the same conversation become
    // statistically negligible (~ 1 in 2.8 * 10^14) and the marker
    // detector also guards via index/total bounds.
    java.security.SecureRandom rnd = new java.security.SecureRandom();
    byte[] buf = new byte[6];
    rnd.nextBytes(buf);
    StringBuilder sb = new StringBuilder(12);
    for (byte b : buf) sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }

  public static boolean containsVideoType(Context context, ArrayList<Uri> uris) {
    for (final Uri uri : uris) {
      final String mimeType = MediaUtil.getMimeType(context, uri);
      if (MediaUtil.isVideoType(mimeType)) {
        return true;
      }
    }
    return false;
  }

  public static DcMsg createMessage(Context context, Uri uri, String text)
      throws NullPointerException {
    DcContext dcContext = DcHelper.getContext(context);
    DcMsg message;
    String mimeType = MediaUtil.getMimeType(context, uri);
    if (uri == null) {
      message = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
    } else if (MediaUtil.isImageType(mimeType)) {
      message = new DcMsg(dcContext, DcMsg.DC_MSG_IMAGE);
    } else if (MediaUtil.isAudioType(mimeType)) {
      message = new DcMsg(dcContext, DcMsg.DC_MSG_AUDIO);
    } else if (MediaUtil.isVideoType(mimeType)) {
      message = new DcMsg(dcContext, DcMsg.DC_MSG_VIDEO);
    } else {
      message = new DcMsg(dcContext, DcMsg.DC_MSG_FILE);
    }

    if (uri != null) {
      setFileFromUri(context, uri, message, mimeType);
    }
    if (text != null) {
      message.setText(text);
    }
    return message;
  }

  private static void setFileFromUri(Context context, Uri uri, DcMsg message, String mimeType) {
    String path;
    DcContext dcContext = DcHelper.getContext(context);
    String filename =
        "cannot-resolve.jpg"; // best guess, this still leads to most images being workable if OS
    // does weird things
    try {

      if (PartAuthority.isLocalUri(uri)) {
        filename = uri.getPathSegments().get(PersistentBlobProvider.FILENAME_PATH_SEGMENT);
      } else if (uri.getScheme().equals("content")) {
        final ContentResolver contentResolver = context.getContentResolver();
        final Cursor cursor = contentResolver.query(uri, null, null, null, null);
        try {
          if (cursor != null && cursor.moveToFirst()) {
            final int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex >= 0) {
              filename = cursor.getString(nameIndex);
            }
          }
        } finally {
          cursor.close();
        }
      }

      path = DcHelper.getBlobdirFile(dcContext, filename, "temp");

      // copy content to this file
      if (path != null) {
        InputStream inputStream = PartAuthority.getAttachmentStream(context, uri);
        OutputStream outputStream = new FileOutputStream(path);
        Util.copy(inputStream, outputStream);
      }
    } catch (Exception e) {
      e.printStackTrace();
      path = null;
    }
    message.setFileAndDeduplicate(path, filename, mimeType);
  }
}
