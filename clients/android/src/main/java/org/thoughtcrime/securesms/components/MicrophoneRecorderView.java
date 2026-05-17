package org.thoughtcrime.securesms.components;

import android.Manifest;
import android.content.Context;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.ViewUtil;

public final class MicrophoneRecorderView extends FrameLayout implements View.OnTouchListener {

  enum State {
    NOT_RUNNING,
    RUNNING_HELD,
    RUNNING_LOCKED
  }

  /**
   * BMChat 2.49.83 — Telegram-style recording mode toggle. A single tap on the recorder button
   * switches between {@link #VOICE} (microphone) and {@link #VIDEO_NOTE} (round video). A
   * long-press starts recording in the currently selected mode.
   */
  public enum Mode {
    VOICE,
    VIDEO_NOTE
  }

  public static final int ANIMATION_DURATION = 200;

  /** BMChat 2.49.83 — Max time we wait for ACTION_UP before promoting to hold-to-record. */
  private static final long TAP_TIMEOUT_MS = 180L;

  private FloatingRecordButton floatingRecordButton;
  private LockDropTarget lockDropTarget;
  private ImageButton toggleButton;
  private @Nullable Listener listener;
  private @NonNull State state = State.NOT_RUNNING;

  private @NonNull Mode mode = Mode.VOICE;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable holdPromoter = this::tryStartHoldRecording;
  private int touchSlopPx;
  private long pressDownTime;
  private float pressDownX;
  private float pressDownY;
  private boolean holdTriggered;
  private boolean tapDisarmed;

  public MicrophoneRecorderView(Context context) {
    super(context);
  }

  public MicrophoneRecorderView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    floatingRecordButton =
        new FloatingRecordButton(getContext(), findViewById(R.id.quick_audio_fab));
    lockDropTarget = new LockDropTarget(getContext(), findViewById(R.id.lock_drop_target));

    toggleButton = findViewById(R.id.quick_audio_toggle);
    toggleButton.setOnTouchListener(this);
    touchSlopPx = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    applyToggleIcon();
  }

  public @NonNull Mode getMode() {
    return mode;
  }

  /**
   * BMChat 2.49.83 — Switches the input panel between voice and round-video recording without
   * actually starting a recording. The icon on {@code quick_audio_toggle} updates accordingly.
   */
  public void setMode(@NonNull Mode newMode) {
    if (this.mode == newMode) return;
    this.mode = newMode;
    applyToggleIcon();
  }

  private void toggleMode() {
    setMode(mode == Mode.VOICE ? Mode.VIDEO_NOTE : Mode.VOICE);
  }

  private void applyToggleIcon() {
    if (toggleButton == null) return;
    if (mode == Mode.VIDEO_NOTE) {
      toggleButton.setImageResource(R.drawable.ic_videocam_on);
      toggleButton.setContentDescription(getResources().getString(R.string.bmchat_video_note_record));
    } else {
      toggleButton.setImageResource(0);
      // The XML uses ?quick_mic_icon — restore that themed reference.
      android.util.TypedValue typed = new android.util.TypedValue();
      if (getContext().getTheme().resolveAttribute(R.attr.quick_mic_icon, typed, true)) {
        toggleButton.setImageResource(typed.resourceId);
      }
      toggleButton.setContentDescription(getResources().getString(R.string.audio));
    }
  }

  public void cancelAction() {
    if (state != State.NOT_RUNNING) {
      state = State.NOT_RUNNING;
      hideUi();

      if (listener != null) listener.onRecordCanceled();
    }
  }

  public boolean isRecordingLocked() {
    return state == State.RUNNING_LOCKED;
  }

  private void lockAction() {
    if (state == State.RUNNING_HELD) {
      state = State.RUNNING_LOCKED;
      hideUi();

      if (listener != null) listener.onRecordLocked();
    }
  }

  public void unlockAction() {
    if (state == State.RUNNING_LOCKED) {
      state = State.NOT_RUNNING;
      hideUi();

      if (listener != null) listener.onRecordReleased();
    }
  }

  private void hideUi() {
    floatingRecordButton.hide();
    lockDropTarget.hide();
  }

  @Override
  public boolean onTouch(View v, final MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        pressDownTime = System.currentTimeMillis();
        pressDownX = event.getX();
        pressDownY = event.getY();
        holdTriggered = false;
        tapDisarmed = false;
        mainHandler.postDelayed(holdPromoter, TAP_TIMEOUT_MS);
        return true;

      case MotionEvent.ACTION_MOVE:
        float dx = event.getX() - pressDownX;
        float dy = event.getY() - pressDownY;
        if (!holdTriggered && (dx * dx + dy * dy) > touchSlopPx * touchSlopPx) {
          // Movement before TAP_TIMEOUT — promote to hold immediately if permissions allow.
          tapDisarmed = true;
          mainHandler.removeCallbacks(holdPromoter);
          if (state == State.NOT_RUNNING) {
            tryStartHoldRecording();
          }
        }
        if (this.state == State.RUNNING_HELD) {
          this.floatingRecordButton.moveTo(event.getX(), event.getY());
          if (listener != null)
            listener.onRecordMoved(floatingRecordButton.lastOffsetX, event.getRawX());

          int dimensionPixelSize =
              getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
          if (floatingRecordButton.lastOffsetY <= dimensionPixelSize) {
            lockAction();
          }
        }
        break;

      case MotionEvent.ACTION_CANCEL:
        mainHandler.removeCallbacks(holdPromoter);
        if (this.state == State.RUNNING_HELD) {
          state = State.NOT_RUNNING;
          hideUi();
          if (listener != null) listener.onRecordReleased();
        }
        break;

      case MotionEvent.ACTION_UP:
        mainHandler.removeCallbacks(holdPromoter);
        if (this.state == State.RUNNING_HELD) {
          state = State.NOT_RUNNING;
          hideUi();
          if (listener != null) listener.onRecordReleased();
        } else if (!holdTriggered && !tapDisarmed
            && (System.currentTimeMillis() - pressDownTime) <= TAP_TIMEOUT_MS) {
          // Genuine tap — toggle the recorder mode.
          v.performClick();
          toggleMode();
          if (listener != null) listener.onModeToggled(mode);
        }
        break;
    }

    return true;
  }

  private void tryStartHoldRecording() {
    if (state != State.NOT_RUNNING) return;
    if (mode == Mode.VIDEO_NOTE) {
      if (!Permissions.hasAll(getContext(),
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.CAMERA)) {
        if (listener != null) listener.onRecordPermissionRequired();
        return;
      }
    } else if (!Permissions.hasAll(getContext(), Manifest.permission.RECORD_AUDIO)) {
      if (listener != null) listener.onRecordPermissionRequired();
      return;
    }
    holdTriggered = true;
    state = State.RUNNING_HELD;
    floatingRecordButton.display(pressDownX, pressDownY);
    lockDropTarget.display();
    if (listener != null) listener.onRecordPressed();
  }

  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  public interface Listener {
    void onRecordPressed();

    void onRecordReleased();

    void onRecordCanceled();

    void onRecordLocked();

    void onRecordMoved(float offsetX, float absoluteX);

    void onRecordPermissionRequired();

    /**
     * BMChat 2.49.83 — Fired on a short tap (no hold), so the UI can repaint the toggle and the
     * input panel can prepare the new recording mode.
     */
    default void onModeToggled(@NonNull Mode newMode) {}
  }

  private static class FloatingRecordButton {

    private final ImageView recordButtonFab;

    private float startPositionX;
    private float startPositionY;
    private float lastOffsetX;
    private float lastOffsetY;

    FloatingRecordButton(Context context, ImageView recordButtonFab) {
      this.recordButtonFab = recordButtonFab;
      this.recordButtonFab
          .getBackground()
          .setColorFilter(
              context.getResources().getColor(R.color.audio_icon), PorterDuff.Mode.SRC_IN);
    }

    void display(float x, float y) {
      this.startPositionX = x;
      this.startPositionY = y;

      recordButtonFab.setVisibility(View.VISIBLE);

      AnimationSet animation = new AnimationSet(true);
      animation.addAnimation(
          new TranslateAnimation(
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0,
              Animation.ABSOLUTE, 0));

      animation.addAnimation(
          new ScaleAnimation(
              .5f, 1f, .5f, 1f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f));

      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new OvershootInterpolator());

      recordButtonFab.startAnimation(animation);
    }

    void moveTo(float x, float y) {
      lastOffsetX = getXOffset(x);
      lastOffsetY = getYOffset(y);

      if (Math.abs(lastOffsetX) > Math.abs(lastOffsetY)) {
        lastOffsetY = 0;
      } else {
        lastOffsetX = 0;
      }

      recordButtonFab.setTranslationX(lastOffsetX);
      recordButtonFab.setTranslationY(lastOffsetY);
    }

    void hide() {
      recordButtonFab.setTranslationX(0);
      recordButtonFab.setTranslationY(0);
      if (recordButtonFab.getVisibility() != VISIBLE) return;

      AnimationSet animation = new AnimationSet(false);
      Animation scaleAnimation =
          new ScaleAnimation(
              1, 0.5f, 1, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);

      Animation translateAnimation =
          new TranslateAnimation(
              Animation.ABSOLUTE,
              lastOffsetX,
              Animation.ABSOLUTE,
              0,
              Animation.ABSOLUTE,
              lastOffsetY,
              Animation.ABSOLUTE,
              0);

      scaleAnimation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));
      translateAnimation.setInterpolator(new DecelerateInterpolator());
      animation.addAnimation(scaleAnimation);
      animation.addAnimation(translateAnimation);
      animation.setDuration(ANIMATION_DURATION);
      animation.setInterpolator(new AnticipateOvershootInterpolator(1.5f));

      recordButtonFab.setVisibility(View.GONE);
      recordButtonFab.clearAnimation();
      recordButtonFab.startAnimation(animation);
    }

    private float getXOffset(float x) {
      return ViewUtil.isLtr(recordButtonFab)
          ? -Math.max(0, this.startPositionX - x)
          : Math.max(0, x - this.startPositionX);
    }

    private float getYOffset(float y) {
      return Math.min(0, y - this.startPositionY);
    }
  }

  private static class LockDropTarget {

    private final View lockDropTarget;
    private final int dropTargetPosition;

    LockDropTarget(Context context, View lockDropTarget) {
      this.lockDropTarget = lockDropTarget;
      this.dropTargetPosition =
          context.getResources().getDimensionPixelSize(R.dimen.recording_voice_lock_target);
    }

    void display() {
      lockDropTarget.setScaleX(1);
      lockDropTarget.setScaleY(1);
      lockDropTarget.setAlpha(0);
      lockDropTarget.setTranslationY(0);
      lockDropTarget.setVisibility(VISIBLE);
      lockDropTarget
          .animate()
          .setStartDelay(ANIMATION_DURATION * 2)
          .setDuration(ANIMATION_DURATION)
          .setInterpolator(new DecelerateInterpolator())
          .translationY(dropTargetPosition)
          .alpha(1)
          .start();
    }

    void hide() {
      lockDropTarget
          .animate()
          .setStartDelay(0)
          .setDuration(ANIMATION_DURATION)
          .setInterpolator(new LinearInterpolator())
          .scaleX(0)
          .scaleY(0)
          .start();
    }
  }
}
