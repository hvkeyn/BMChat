package org.thoughtcrime.securesms.components.audioplay;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import com.google.common.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.service.AudioPlaybackService;

/**
 * Telegram-style floating mini player. The view binds itself to the
 * {@link AudioPlaybackService} via a fresh {@link MediaController} on
 * attach and tears down on detach, so it can live in any activity that
 * just inflates it (typically {@code ConversationListActivity} and
 * {@code ConversationActivity}).
 *
 * <p>While the controller reports {@code STATE_IDLE} or no current media
 * item, the view sets its own {@code visibility = GONE} so it does not
 * eat any chrome below it. As soon as a track starts playing it slides in
 * with the standard activity layout pass.
 *
 * <p>Tap on the body raises {@link OnNavigateListener#onNavigateToTrack},
 * letting the hosting activity decide how to navigate to the
 * conversation that owns the current message.
 */
public class BMChatMiniPlayerView extends FrameLayout {

  private static final String TAG = "BMChatMiniPlayer";

  /** Callback fired when the user taps the mini-player body. */
  public interface OnNavigateListener {
    /** All three identifiers are non-zero by construction. */
    void onNavigateToTrack(int accountId, int chatId, int msgId);
  }

  private final FrameLayout root;
  private final ProgressBar progress;
  private final ImageView playPauseButton;
  private final ImageView closeButton;
  private final TextView titleText;
  private final TextView subtitleText;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private @Nullable MediaController controller;
  private @Nullable ListenableFuture<MediaController> controllerFuture;
  private @Nullable Player.Listener controllerListener;
  private @Nullable OnNavigateListener navigateListener;

  private int currentAccountId = 0;
  private int currentMsgId = 0;
  private int currentChatId = 0;

  private final Runnable progressTick =
      new Runnable() {
        @Override
        public void run() {
          updateProgress();
          if (controller != null && controller.isPlaying()) {
            handler.postDelayed(this, 250);
          }
        }
      };

  public BMChatMiniPlayerView(@NonNull Context context) {
    this(context, null);
  }

  public BMChatMiniPlayerView(@NonNull Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public BMChatMiniPlayerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    inflate(context, R.layout.bmchat_miniplayer, this);
    this.root = findViewById(R.id.bmchat_miniplayer_root);
    this.progress = findViewById(R.id.bmchat_miniplayer_progress);
    this.playPauseButton = findViewById(R.id.bmchat_miniplayer_play_pause);
    this.closeButton = findViewById(R.id.bmchat_miniplayer_close);
    this.titleText = findViewById(R.id.bmchat_miniplayer_title);
    this.subtitleText = findViewById(R.id.bmchat_miniplayer_subtitle);

    setVisibility(GONE);

    playPauseButton.setOnClickListener(v -> togglePlayPause());
    closeButton.setOnClickListener(v -> closePlayback());
    root.setOnClickListener(v -> emitNavigate());
  }

  public void setOnNavigateListener(@Nullable OnNavigateListener listener) {
    this.navigateListener = listener;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (controllerFuture != null) {
      return;
    }
    try {
      SessionToken token =
          new SessionToken(
              getContext().getApplicationContext(),
              new ComponentName(getContext().getApplicationContext(), AudioPlaybackService.class));
      controllerFuture =
          new MediaController.Builder(getContext().getApplicationContext(), token).buildAsync();
      ListenableFuture<MediaController> future = controllerFuture;
      future.addListener(
          () -> {
            try {
              controller = future.get();
              attachController();
            } catch (Exception e) {
              Log.w(TAG, "MediaController build failed", e);
            }
          },
          ContextCompat.getMainExecutor(getContext()));
    } catch (Throwable t) {
      Log.w(TAG, "MediaController init failed", t);
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    handler.removeCallbacks(progressTick);
    detachController();
    if (controllerFuture != null) {
      MediaController.releaseFuture(controllerFuture);
      controllerFuture = null;
    }
    controller = null;
  }

  private void attachController() {
    if (controller == null) return;

    controllerListener =
        new Player.Listener() {
          @Override
          public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
            if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED)) {
              refreshAll();
            }
          }
        };
    controller.addListener(controllerListener);
    refreshAll();
  }

  private void detachController() {
    if (controller != null && controllerListener != null) {
      controller.removeListener(controllerListener);
    }
    controllerListener = null;
  }

  private void refreshAll() {
    if (controller == null) {
      setVisibility(GONE);
      return;
    }
    MediaItem item = controller.getCurrentMediaItem();
    int playbackState = controller.getPlaybackState();
    boolean hasContent = item != null && playbackState != Player.STATE_IDLE;
    if (!hasContent) {
      setVisibility(GONE);
      handler.removeCallbacks(progressTick);
      return;
    }
    bindMetadata(item);
    updatePlayPauseIcon();
    setVisibility(VISIBLE);
    handler.removeCallbacks(progressTick);
    handler.post(progressTick);
  }

  private void bindMetadata(@NonNull MediaItem item) {
    int msgId = parseMsgId(item.mediaId);
    if (msgId == currentMsgId && msgId != 0) {
      return; // already bound to this msg
    }
    currentMsgId = msgId;
    currentAccountId = 0;
    currentChatId = 0;

    if (msgId <= 0) {
      titleText.setText(R.string.audio);
      subtitleText.setVisibility(GONE);
      return;
    }

    try {
      DcContext dc = DcHelper.getContext(getContext());
      currentAccountId = dc.getAccountId();
      DcMsg msg = dc.getMsg(msgId);
      currentChatId = msg.getChatId();

      String displayTitle;
      if (msg.getType() == DcMsg.DC_MSG_VOICE) {
        displayTitle = getContext().getString(R.string.voice_message);
      } else {
        String fileName = msg.getFilename();
        displayTitle =
            (fileName != null && !fileName.isEmpty())
                ? fileName
                : getContext().getString(R.string.audio);
      }
      titleText.setText(displayTitle);

      String senderName = "";
      try {
        DcContact sender = dc.getContact(msg.getFromId());
        senderName = sender != null ? sender.getDisplayName() : "";
      } catch (Throwable ignored) {
      }

      String chatName = "";
      try {
        DcChat chat = dc.getChat(currentChatId);
        chatName = chat != null ? chat.getName() : "";
      } catch (Throwable ignored) {
      }

      StringBuilder sub = new StringBuilder();
      if (senderName != null && !senderName.isEmpty()) sub.append(senderName);
      if (chatName != null && !chatName.isEmpty()) {
        if (sub.length() > 0) sub.append(" — ");
        sub.append(chatName);
      }
      if (sub.length() == 0) {
        subtitleText.setVisibility(GONE);
      } else {
        subtitleText.setText(sub.toString());
        subtitleText.setVisibility(VISIBLE);
      }
    } catch (Throwable t) {
      Log.w(TAG, "bindMetadata failed for msgId=" + msgId, t);
      titleText.setText(R.string.audio);
      subtitleText.setVisibility(GONE);
    }
  }

  private void updatePlayPauseIcon() {
    if (controller == null) return;
    boolean playing = controller.isPlaying();
    playPauseButton.setImageResource(playing ? R.drawable.pause_icon : R.drawable.play_icon);
    playPauseButton.setContentDescription(
        getContext().getString(playing ? R.string.menu_pause : R.string.menu_play));
  }

  private void updateProgress() {
    if (controller == null) return;
    long duration = controller.getDuration();
    long position = controller.getCurrentPosition();
    if (duration > 0) {
      int frac = (int) Math.max(0, Math.min(1000, position * 1000L / duration));
      progress.setProgress(frac);
    } else {
      progress.setProgress(0);
    }
  }

  private void togglePlayPause() {
    if (controller == null) return;
    if (controller.isPlaying()) {
      controller.pause();
    } else {
      controller.play();
    }
  }

  private void closePlayback() {
    if (controller != null) {
      controller.stop();
      controller.clearMediaItems();
    }
    setVisibility(GONE);
  }

  private void emitNavigate() {
    if (navigateListener != null && currentMsgId > 0 && currentChatId > 0) {
      navigateListener.onNavigateToTrack(currentAccountId, currentChatId, currentMsgId);
    }
  }

  /**
   * Programmatically set the playback speed on the active session. We
   * keep the helper here so that the speed-toggle in {@code AudioView}
   * can apply the change without holding its own MediaController.
   */
  public void applyPlaybackSpeed(float speed) {
    if (controller == null) return;
    controller.setPlaybackParameters(new PlaybackParameters(speed));
  }

  private static int parseMsgId(@Nullable String mediaId) {
    if (mediaId == null) return 0;
    try {
      return Integer.parseInt(mediaId);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
