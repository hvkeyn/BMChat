package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcMsg;

import org.thoughtcrime.securesms.bots.ui.TgMediaPlayerActivity;

/**
 * BMChat 2.49.90: helpers for Telegram-bot videos that could not be
 * downloaded directly (&gt;20 MB Bot API cap) and were published as a
 * {@code DC_MSG_IMAGE} poster plus a hidden {@link BotMediaMarker}.
 *
 * <p>Callers that would normally open {@code MediaPreviewActivity} or
 * save the on-disk JPEG must go through this class first so the user
 * gets the progressive proxy stream instead of a static thumbnail.
 */
public final class TgStreamableMedia {

  private TgStreamableMedia() {}

  /** True when {@code msg} is an image poster for a streamable TG video. */
  public static boolean isPoster(@Nullable DcMsg msg) {
    if (msg == null) return false;
    return BotMediaMarker.parse(msg.getText()) != null;
  }

  @Nullable
  public static BotMediaMarker.Info info(@Nullable DcMsg msg) {
    if (msg == null) return null;
    return BotMediaMarker.parse(msg.getText());
  }

  /**
   * Launch {@link TgMediaPlayerActivity} when {@code msg} carries a
   * marker. Returns {@code true} when the intent was started.
   */
  public static boolean openPlayer(@NonNull Context context, @NonNull DcMsg msg) {
    BotMediaMarker.Info info = info(msg);
    if (info == null) return false;
    context.startActivity(buildPlayerIntent(context, info));
    return true;
  }

  public static @NonNull Intent buildPlayerIntent(
      @NonNull Context context, @NonNull BotMediaMarker.Info info) {
    Intent i = TgMediaPlayerActivity.newIntent(context, info.url, null, info.mime);
    if (info.sizeBytes > 0) {
      i.putExtra(TgMediaPlayerActivity.EXTRA_SIZE_BYTES, info.sizeBytes);
    }
    return i;
  }
}
