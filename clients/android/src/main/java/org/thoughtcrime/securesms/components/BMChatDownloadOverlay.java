package org.thoughtcrime.securesms.components;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.b44t.messenger.DcMsg;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import org.thoughtcrime.securesms.R;

/**
 * Telegram-style circular download indicator. Renders the four classic
 * states of a partially-fetched media attachment:
 *
 * <ul>
 *   <li>HIDDEN — message body is already on disk, the overlay is gone.
 *   <li>AVAILABLE — the central down-arrow icon is shown; tap triggers a
 *       download. No ring around it yet.
 *   <li>IN_PROGRESS — indeterminate ring spins around the icon. (Core does
 *       not emit byte-level progress today, so we stay indeterminate.)
 *   <li>FAILED — refresh glyph on a static ring; tap retries.
 * </ul>
 *
 * <p>This widget is intentionally lightweight: a circular background View, a
 * Material {@code CircularProgressIndicator} and a 24dp glyph in a 48dp
 * frame. Use {@link #setState(int)} from {@code ConversationItem.bind()}
 * driven by {@link DcMsg#getDownloadState()}; the surrounding click handler
 * should call {@link com.b44t.messenger.DcContext#downloadFullMsg(int)} on
 * the parent message.
 */
public class BMChatDownloadOverlay extends FrameLayout {

  public static final int STATE_HIDDEN = 0;
  public static final int STATE_AVAILABLE = 1;
  public static final int STATE_IN_PROGRESS = 2;
  public static final int STATE_FAILED = 3;

  private final View background;
  private final CircularProgressIndicator progress;
  private final ImageView icon;

  private int state = STATE_HIDDEN;

  public BMChatDownloadOverlay(@NonNull Context context) {
    this(context, null);
  }

  public BMChatDownloadOverlay(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public BMChatDownloadOverlay(
      @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    inflate(context, R.layout.bmchat_download_overlay, this);
    this.background = findViewById(R.id.bmchat_download_overlay_bg);
    this.progress = findViewById(R.id.bmchat_download_overlay_progress);
    this.icon = findViewById(R.id.bmchat_download_overlay_icon);
    setVisibility(GONE);
  }

  public int getState() {
    return state;
  }

  public void setState(int newState) {
    state = newState;
    switch (newState) {
      case STATE_HIDDEN:
        setVisibility(GONE);
        return;
      case STATE_AVAILABLE:
        setVisibility(VISIBLE);
        background.setVisibility(VISIBLE);
        progress.setVisibility(GONE);
        icon.setVisibility(VISIBLE);
        icon.setImageResource(R.drawable.ic_file_download_white_24dp);
        return;
      case STATE_IN_PROGRESS:
        setVisibility(VISIBLE);
        background.setVisibility(VISIBLE);
        icon.setVisibility(VISIBLE);
        icon.setImageResource(R.drawable.ic_file_download_white_24dp);
        progress.setVisibility(VISIBLE);
        progress.show();
        return;
      case STATE_FAILED:
        setVisibility(VISIBLE);
        background.setVisibility(VISIBLE);
        progress.setVisibility(GONE);
        icon.setVisibility(VISIBLE);
        icon.setImageResource(R.drawable.ic_refresh_white_24dp);
        return;
      default:
        setVisibility(GONE);
    }
  }

  /**
   * Map BMChat / Delta Chat core download-state codes onto our four UI
   * states. Anything else (notably {@link DcMsg#DC_DOWNLOAD_DONE}) is
   * treated as HIDDEN so the overlay disappears as soon as the body is on
   * disk.
   */
  public void setFromMessage(@NonNull DcMsg msg) {
    int downloadState = msg.getDownloadState();
    if (downloadState == DcMsg.DC_DOWNLOAD_AVAILABLE) {
      setState(STATE_AVAILABLE);
    } else if (downloadState == DcMsg.DC_DOWNLOAD_IN_PROGRESS) {
      setState(STATE_IN_PROGRESS);
    } else if (downloadState == DcMsg.DC_DOWNLOAD_FAILURE) {
      setState(STATE_FAILED);
    } else {
      setState(STATE_HIDDEN);
    }
  }
}
