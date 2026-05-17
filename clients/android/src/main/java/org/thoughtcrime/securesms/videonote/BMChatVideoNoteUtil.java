package org.thoughtcrime.securesms.videonote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.b44t.messenger.DcMsg;

/**
 * BMChat 2.49.82 (Phase 5): Shared helpers for Telegram-style round video
 * notes. The "video note" concept does not exist in the Delta Chat core,
 * so we encode the flag in the attachment filename ({@code *.vn.mp4}) on
 * send and decode it on receive without touching the message protocol.
 */
public final class BMChatVideoNoteUtil {

  /** Infix added to the cached recorder filename before the {@code .mp4} ext. */
  public static final String VN_INFIX = ".vn.";

  private BMChatVideoNoteUtil() {}

  /** Returns {@code true} when a Delta Chat message represents a round video note. */
  public static boolean isVideoNote(@Nullable DcMsg msg) {
    if (msg == null) return false;
    if (msg.getType() != DcMsg.DC_MSG_VIDEO) return false;
    String filename = msg.getFilename();
    return filename != null && filename.contains(VN_INFIX);
  }

  /** Same check but operating on a plain filename string. */
  public static boolean isVideoNoteFilename(@NonNull String filename) {
    return filename.contains(VN_INFIX);
  }
}
