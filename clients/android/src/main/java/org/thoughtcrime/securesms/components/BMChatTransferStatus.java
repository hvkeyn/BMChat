package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.b44t.messenger.DcMsg;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.AccessibilityUtil;
import org.thoughtcrime.securesms.util.Util;

/**
 * Human-readable file transfer state for chat bubbles and the message-details
 * dialog. While IMAP downloads a file in slices, {@link BMChatTransferProgress}
 * fills the bar and the line shows bytes done, the total and the time left.
 * Until the first slice arrives the bar stays indeterminate and the line
 * shows how long the transfer has already been running.
 */
public final class BMChatTransferStatus {

  public enum Phase {
    NONE,
    PREPARING,
    SENDING,
    SEND_FAILED,
    RECEIVING,
    AVAILABLE,
    FAILED,
    DOWNLOADED,
    SENT
  }

  private BMChatTransferStatus() {}

  public static boolean hasAttachment(@NonNull DcMsg msg) {
    switch (msg.getType()) {
      case DcMsg.DC_MSG_IMAGE:
      case DcMsg.DC_MSG_GIF:
      case DcMsg.DC_MSG_STICKER:
      case DcMsg.DC_MSG_AUDIO:
      case DcMsg.DC_MSG_VOICE:
      case DcMsg.DC_MSG_VIDEO:
      case DcMsg.DC_MSG_FILE:
      case DcMsg.DC_MSG_WEBXDC:
        return true;
      default:
        return msg.getFilebytes() > 0 || !TextUtils.isEmpty(msg.getFilename());
    }
  }

  public static @NonNull Phase phase(@NonNull DcMsg msg) {
    int download = msg.getDownloadState();
    if (download == DcMsg.DC_DOWNLOAD_IN_PROGRESS) return Phase.RECEIVING;
    if (download == DcMsg.DC_DOWNLOAD_AVAILABLE) return Phase.AVAILABLE;
    if (download == DcMsg.DC_DOWNLOAD_FAILURE) return Phase.FAILED;
    if (!hasAttachment(msg)) return Phase.NONE;
    if (msg.isPreparing()) return Phase.PREPARING;
    if (msg.isPending()) return Phase.SENDING;
    if (msg.isFailed()) return Phase.SEND_FAILED;
    if (msg.isOutgoing() && (msg.isDelivered() || msg.isRemoteRead())) return Phase.SENT;
    if (!msg.isOutgoing() && download == DcMsg.DC_DOWNLOAD_DONE) return Phase.DOWNLOADED;
    return Phase.NONE;
  }

  /** Bubble chrome: only while the file is still moving or waiting for a tap. */
  public static boolean showInBubble(@NonNull Phase phase) {
    switch (phase) {
      case PREPARING:
      case SENDING:
      case SEND_FAILED:
      case RECEIVING:
      case AVAILABLE:
      case FAILED:
        return true;
      default:
        return false;
    }
  }

  public static boolean isBusy(@NonNull Phase phase) {
    return phase == Phase.PREPARING || phase == Phase.SENDING || phase == Phase.RECEIVING;
  }

  public static void bind(
      @NonNull View row,
      @NonNull TextView label,
      @NonNull LinearProgressIndicator bar,
      @NonNull DcMsg msg,
      boolean includeName,
      int normalColor,
      boolean tintFailure) {
    Phase phase = phase(msg);
    if (!showInBubble(phase)) {
      row.setVisibility(View.GONE);
      bar.setVisibility(View.GONE);
      return;
    }
    Context context = row.getContext();
    String text = oneLine(context, msg, phase, includeName);
    label.setVisibility(View.VISIBLE);
    label.setText(text);
    label.setContentDescription(text);
    if (tintFailure && (phase == Phase.FAILED || phase == Phase.SEND_FAILED)) {
      label.setTextColor(ContextCompat.getColor(context, R.color.bmchat_status_offline));
    } else {
      label.setTextColor(normalColor);
    }
    row.setVisibility(View.VISIBLE);
    bindCancel(row, msg, phase);
    int msgId = msg.getId();
    if (isBusy(phase)) {
      bar.setVisibility(View.VISIBLE);
      bar.setMax(1000);
      applyProgress(context, label, bar, msg, phase, includeName, text);
      BMChatTransferProgress.watch(
          row,
          msgId,
          () ->
              applyProgress(
                  context,
                  label,
                  bar,
                  msg,
                  phase,
                  includeName,
                  oneLine(context, msg, phase, includeName)));
    } else {
      BMChatTransferProgress.clear(msgId);
      bar.setVisibility(View.GONE);
    }
  }

  private static void bindCancel(@NonNull View row, @NonNull DcMsg msg, @NonNull Phase phase) {
    View cancel = row.findViewById(R.id.bmchat_transfer_cancel);
    if (cancel == null) return;
    boolean show =
        isBusy(phase) || phase == Phase.FAILED || phase == Phase.SEND_FAILED;
    cancel.setVisibility(show ? View.VISIBLE : View.GONE);
    if (!show) {
      cancel.setOnClickListener(null);
      return;
    }
    cancel.setOnClickListener(
        v -> {
          Context context = v.getContext();
          new AlertDialog.Builder(context)
              .setTitle(R.string.bmchat_transfer_cancel_title)
              .setMessage(R.string.bmchat_transfer_cancel_body)
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(
                  R.string.bmchat_transfer_cancel,
                  (dialog, which) ->
                      DcHelper.getContext(context).deleteMsgs(new int[] {msg.getId()}))
              .show();
        });
  }

  private static void applyProgress(
      @NonNull Context context,
      @NonNull TextView label,
      @NonNull LinearProgressIndicator bar,
      @NonNull DcMsg msg,
      @NonNull Phase phase,
      boolean includeName,
      @NonNull String fallback) {
    BMChatTransferProgress.Sample sample = BMChatTransferProgress.get(msg.getId());
    String text = fallback;
    if (sample != null && sample.total > 0) {
      String amount =
          context.getString(
              R.string.bmchat_transfer_bytes,
              Util.getPrettyFileSize(sample.got),
              Util.getPrettyFileSize(sample.total));
      StringBuilder line = new StringBuilder(verb(context, phase));
      if (includeName && !TextUtils.isEmpty(msg.getFilename())) {
        line.append(" · ").append(msg.getFilename());
      }
      line.append(" · ").append(amount);
      long left = sample.secondsLeft();
      if (left >= 0) {
        line.append(" · ");
        if (left < 90) {
          line.append(context.getString(R.string.bmchat_transfer_left_soon));
        } else {
          line.append(
              context.getString(R.string.bmchat_transfer_left_min, (int) ((left + 30) / 60)));
        }
      }
      text = line.toString();
      bar.setIndeterminate(false);
      int permille =
          (int) Math.min(1000, Math.max(0, sample.got * 1000 / Math.max(1, sample.total)));
      bar.setProgressCompat(permille, true);
    } else {
      long elapsed = BMChatTransferProgress.elapsedMs(msg.getId());
      if (elapsed >= 5000) {
        text =
            fallback
                + " · "
                + context.getString(
                    R.string.bmchat_transfer_elapsed, elapsedLabel(context, elapsed));
      }
      if (AccessibilityUtil.areAnimationsDisabled(context)) {
        bar.setIndeterminate(false);
        bar.setProgressCompat(0, false);
      } else {
        bar.setIndeterminate(true);
        bar.show();
      }
    }
    label.setText(text);
    label.setContentDescription(text);
    bar.setContentDescription(text);
  }

  private static @NonNull String elapsedLabel(@NonNull Context context, long elapsedMs) {
    long seconds = elapsedMs / 1000;
    if (seconds < 90) return context.getString(R.string.bmchat_transfer_under_minute);
    return context.getString(R.string.bmchat_transfer_minutes, (int) ((seconds + 30) / 60));
  }

  /**
   * Multiline block for the message-details dialog. Null when the message
   * has no file to describe.
   */
  public static @Nullable String details(@NonNull Context context, @NonNull DcMsg msg) {
    if (!hasAttachment(msg) && msg.getDownloadState() == DcMsg.DC_DOWNLOAD_DONE) {
      return null;
    }
    Phase phase = phase(msg);
    if (phase == Phase.NONE && !hasAttachment(msg)) return null;
    StringBuilder out = new StringBuilder();
    String name = msg.getFilename();
    if (!TextUtils.isEmpty(name)) out.append(name);
    String size = sizeLabel(msg);
    if (size != null) {
      if (out.length() > 0) out.append('\n');
      out.append(size);
    }
    if (phase != Phase.NONE) {
      if (out.length() > 0) out.append('\n');
      out.append(verb(context, phase));
    }
    return out.length() == 0 ? null : out.toString();
  }

  private static @NonNull String oneLine(
      @NonNull Context context, @NonNull DcMsg msg, @NonNull Phase phase, boolean includeName) {
    StringBuilder out = new StringBuilder(verb(context, phase));
    if (includeName && !TextUtils.isEmpty(msg.getFilename())) {
      out.append(" · ").append(msg.getFilename());
    }
    String size = sizeLabel(msg);
    if (size != null) out.append(" · ").append(size);
    return out.toString();
  }

  private static @Nullable String sizeLabel(@NonNull DcMsg msg) {
    long bytes = msg.getFilebytes();
    if (bytes <= 0) return null;
    return Util.getPrettyFileSize(bytes);
  }

  private static @NonNull String verb(@NonNull Context context, @NonNull Phase phase) {
    switch (phase) {
      case PREPARING:
        return context.getString(R.string.bmchat_transfer_preparing);
      case SENDING:
        return context.getString(R.string.bmchat_transfer_sending);
      case SEND_FAILED:
        return context.getString(R.string.bmchat_transfer_send_failed);
      case RECEIVING:
        return context.getString(R.string.bmchat_transfer_receiving);
      case AVAILABLE:
        return context.getString(R.string.bmchat_transfer_not_downloaded);
      case FAILED:
        return context.getString(R.string.download_failed);
      case DOWNLOADED:
        return context.getString(R.string.bmchat_transfer_downloaded);
      case SENT:
        return context.getString(R.string.bmchat_transfer_sent);
      default:
        return "";
    }
  }
}
