package org.thoughtcrime.securesms;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.core.util.Consumer;
import androidx.fragment.app.Fragment;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.Util;

public abstract class MessageSelectorFragment extends Fragment
    implements DcEventCenter.DcEventDelegate {
  protected ActionMode actionMode;

  protected abstract void setCorrectMenuVisibility(Menu menu);

  protected ActionMode getActionMode() {
    return actionMode;
  }

  protected DcMsg getSelectedMessageRecord(Set<DcMsg> messageRecords) {
    if (messageRecords.size() == 1) return messageRecords.iterator().next();
    else throw new AssertionError();
  }

  protected void handleDisplayDetails(DcMsg dcMsg) {
    View view = View.inflate(getActivity(), R.layout.message_details_view, null);
    TextView detailsText = view.findViewById(R.id.details_text);
    detailsText.setText(formatMessageDetails(DcHelper.getContext(getContext()).getMsgInfo(dcMsg.getId())));

    AlertDialog d =
        new AlertDialog.Builder(getActivity())
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .create();
    d.show();
  }

  private CharSequence formatMessageDetails(String rawInfo) {
    if (rawInfo == null || rawInfo.trim().isEmpty()) {
      return "";
    }

    Pattern statusPattern = Pattern.compile("^(Sent|Read):\\s*(.*?)\\s+by\\s+(.+)$");
    SpannableStringBuilder readable = new SpannableStringBuilder();
    StringBuilder technical = new StringBuilder();

    for (String line : rawInfo.split("\\n")) {
      Matcher matcher = statusPattern.matcher(line.trim());
      if (matcher.matches()) {
        appendStatusLine(readable, matcher.group(1), matcher.group(2), matcher.group(3));
      } else {
        if (technical.length() > 0) technical.append('\n');
        technical.append(line);
      }
    }

    if (readable.length() == 0) {
      return rawInfo;
    }
    if (technical.length() > 0) {
      if (readable.length() > 0) readable.append('\n');
      appendBold(readable, getString(R.string.bmchat_msg_info_technical));
      readable.append('\n').append(technical.toString().trim());
    }
    return readable;
  }

  private void appendStatusLine(
      SpannableStringBuilder target, String kind, String timestamp, String actor) {
    if (target.length() > 0) target.append('\n');
    appendBold(
        target,
        "Sent".equals(kind)
            ? getString(R.string.bmchat_msg_info_sent)
            : getString(R.string.bmchat_msg_info_read));
    target.append('\n');
    appendBold(target, actor);
    if (timestamp != null && !timestamp.trim().isEmpty()) {
      target.append('\n').append(timestamp.trim());
    }
    target.append('\n');
  }

  private static void appendBold(SpannableStringBuilder target, String text) {
    int start = target.length();
    target.append(text == null ? "" : text.trim());
    target.setSpan(new StyleSpan(Typeface.BOLD), start, target.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  protected void handleDeleteMessages(int chatId, final Set<DcMsg> messageRecords) {
    handleDeleteMessages(chatId, DcMsg.msgSetToIds(messageRecords), null, null);
  }

  protected void handleDeleteMessages(
      int chatId,
      final Set<DcMsg> messageRecords,
      Consumer<int[]> deleteForMeListenerExtra,
      Consumer<int[]> deleteForAllListenerExtra) {
    handleDeleteMessages(
        chatId,
        DcMsg.msgSetToIds(messageRecords),
        deleteForMeListenerExtra,
        deleteForAllListenerExtra);
  }

  protected void handleDeleteMessages(
      int chatId,
      final int[] messageIds,
      Consumer<int[]> deleteForMeListenerExtra,
      Consumer<int[]> deleteForAllListenerExtra) {
    DcContext dcContext = DcHelper.getContext(getContext());
    DcChat dcChat = dcContext.getChat(chatId);
    boolean canDeleteForAll = true;
    if (dcChat.isEncrypted() && dcChat.canSend() && !dcChat.isSelfTalk()) {
      for (int msgId : messageIds) {
        DcMsg msg = dcContext.getMsg(msgId);
        if (!msg.isOutgoing() || msg.isInfo()) {
          canDeleteForAll = false;
          break;
        }
      }
    } else {
      canDeleteForAll = false;
    }

    String text =
        getActivity()
            .getResources()
            .getQuantityString(R.plurals.ask_delete_messages, messageIds.length, messageIds.length);
    int positiveBtnLabel = dcChat.isSelfTalk() ? R.string.delete : R.string.delete_for_me;

    DialogInterface.OnClickListener deleteForMeListener =
        (d, which) -> {
          Util.runOnAnyBackgroundThread(() -> dcContext.deleteMsgs(messageIds));
          if (actionMode != null) actionMode.finish();
          if (deleteForMeListenerExtra != null) deleteForMeListenerExtra.accept(messageIds);
        };
    AlertDialog.Builder builder =
        new AlertDialog.Builder(requireActivity())
            .setMessage(text)
            .setCancelable(true)
            .setNeutralButton(android.R.string.cancel, null)
            .setPositiveButton(positiveBtnLabel, deleteForMeListener);

    if (canDeleteForAll) {
      DialogInterface.OnClickListener deleteForAllListener =
          (d, which) -> {
            Util.runOnAnyBackgroundThread(() -> dcContext.sendDeleteRequest(messageIds));
            if (actionMode != null) actionMode.finish();
            if (deleteForAllListenerExtra != null) deleteForAllListenerExtra.accept(messageIds);
          };
      builder.setNegativeButton(R.string.delete_for_everyone, deleteForAllListener);
      AlertDialog dialog = builder.show();
      Util.redButton(dialog, AlertDialog.BUTTON_NEGATIVE);
      Util.redPositiveButton(dialog);
    } else {
      AlertDialog dialog = builder.show();
      Util.redPositiveButton(dialog);
    }
  }

  protected void handleSaveAttachment(final Set<DcMsg> messageRecords) {
    SaveAttachmentTask.showWarningDialog(
        getContext(),
        (dialogInterface, i) -> {
          if (StorageUtil.canWriteToMediaStore(getContext())) {
            performSave(messageRecords);
            return;
          }

          Permissions.with(getActivity())
              .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
              .alwaysGrantOnSdk30()
              .ifNecessary()
              .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
              .onAllGranted(() -> performSave(messageRecords))
              .execute();
        });
  }

  private void performSave(Set<DcMsg> messageRecords) {
    // BMChat 2.49.86: skip messages whose original file is not on disk yet — otherwise we end up
    // saving the thumbnail JPEG instead of the actual video/audio (the "сохраняет фото вместо
    // видео" bug). Trigger a full download for every undownloaded record and ask the user to
    // retry once everything is local.
    DcContext dcContext = DcHelper.getContext(getContext());
    java.util.List<DcMsg> ready = new java.util.ArrayList<>();
    int pending = 0;
    for (DcMsg message : messageRecords) {
      int state = message.getDownloadState();
      if (state == DcMsg.DC_DOWNLOAD_DONE) {
        ready.add(message);
      } else {
        pending++;
        if (state == DcMsg.DC_DOWNLOAD_AVAILABLE) {
          dcContext.downloadFullMsg(message.getId());
        }
      }
    }
    if (pending > 0) {
      Toast.makeText(
              getContext(),
              ready.isEmpty()
                  ? R.string.bmchat_save_need_download
                  : R.string.bmchat_save_downloading,
              Toast.LENGTH_LONG)
          .show();
      if (ready.isEmpty()) {
        if (actionMode != null) actionMode.finish();
        return;
      }
    }
    SaveAttachmentTask.Attachment[] attachments =
        new SaveAttachmentTask.Attachment[ready.size()];
    int index = 0;
    for (DcMsg message : ready) {
      String mime = message.getFilemime();
      String filePath = message.getFile();
      Uri uri =
          (filePath != null && !filePath.isEmpty())
              ? Uri.fromFile(new java.io.File(filePath))
              : Uri.fromFile(message.getFileAsFile());
      // BMChat 2.49.87: align MIME with ViewType to avoid saving a video as a `.jpg` thumbnail.
      int vt = message.getType();
      if (vt == DcMsg.DC_MSG_VIDEO && (mime == null || !mime.startsWith("video/"))) {
        mime = "video/mp4";
      } else if ((vt == DcMsg.DC_MSG_AUDIO || vt == DcMsg.DC_MSG_VOICE)
          && (mime == null || !mime.startsWith("audio/"))) {
        mime = "audio/mpeg";
      }
      if (mime == null) mime = "application/octet-stream";
      attachments[index] =
          new SaveAttachmentTask.Attachment(uri, mime, message.getDateReceived(), message.getFilename());
      index++;
    }
    // BMChat 2.49.87: explicit "Saving N files…" feedback in addition to the ProgressDialog.
    Toast.makeText(
            getContext(),
            getString(R.string.bmchat_save_started, String.valueOf(attachments.length)),
            Toast.LENGTH_SHORT)
        .show();
    SaveAttachmentTask saveTask = new SaveAttachmentTask(getContext());
    saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, attachments);
    if (actionMode != null) actionMode.finish();
  }

  protected void handleShowInChat(final DcMsg dcMsg) {
    Intent intent = new Intent(getContext(), ConversationActivity.class);
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, dcMsg.getChatId());
    intent.putExtra(
        ConversationActivity.STARTING_POSITION_EXTRA,
        DcMsg.getMessagePosition(dcMsg, DcHelper.getContext(getContext())));
    startActivity(intent);
  }

  protected void handleShare(final DcMsg dcMsg) {
    DcHelper.openForViewOrShare(getContext(), dcMsg.getId(), Intent.ACTION_SEND);
  }

  protected void handleResendMessage(final Set<DcMsg> dcMsgsSet) {
    int[] ids = DcMsg.msgSetToIds(dcMsgsSet);
    DcContext dcContext = DcHelper.getContext(getContext());
    Util.runOnAnyBackgroundThread(
        () -> {
          boolean success = dcContext.resendMsgs(ids);
          Util.runOnMain(
              () -> {
                Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) return;
                if (success) {
                  actionMode.finish();
                  Toast.makeText(getContext(), R.string.sending, Toast.LENGTH_SHORT).show();
                } else {
                  new AlertDialog.Builder(activity)
                      .setMessage(dcContext.getLastError())
                      .setCancelable(false)
                      .setPositiveButton(android.R.string.ok, null)
                      .show();
                }
              });
        });
  }
}
