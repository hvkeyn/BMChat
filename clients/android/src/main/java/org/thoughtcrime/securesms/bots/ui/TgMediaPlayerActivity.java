package org.thoughtcrime.securesms.bots.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.AppBarLayout;

import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.bots.TgMediaSaveTask;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.IntentUtils;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.permissions.Permissions;

import java.util.Locale;

/**
 * Lightweight progressive-download player for BMChat Telegram media
 * proxy URLs (i.e. {@code http://5.187.4.132/tgmedia/...} payloads).
 *
 * <p>The activity hosts a stock {@link VideoView} with a custom
 * controls overlay matching Telegram's built-in player: play /
 * pause, scrub bar, current and total time, speed cycle
 * (0.5×, 1×, 1.25×, 1.5×, 2×) and a fullscreen toggle that hides
 * the toolbar plus the system status / nav bars and locks the
 * device into landscape — same as Telegram. Tap on the video
 * toggles the controls overlay, just like Telegram.
 *
 * <p>Speed control uses {@link MediaPlayer#setPlaybackParams(PlaybackParams)}
 * which is available on API 23+. The video uses the native
 * MediaPlayer pipeline, so it transparently issues HTTP Range
 * requests against the URL — exactly what the BMChat tgproxy
 * forwards to Telegram's CDN — and we get a seekable "starts
 * playing as it loads" UX with no third-party player dependency.
 *
 * <p>For non-streamable media kinds (audio, voice, document, …) we
 * bail to {@link IntentUtils#showInBrowser(android.app.Activity, String)}
 * so the system picker (browser + media apps) handles the URL.
 */
public class TgMediaPlayerActivity extends PassphraseRequiredActionBarActivity {

  private static final String TAG = "TgMediaPlayer";

  public static final String EXTRA_URL = "url";
  public static final String EXTRA_TITLE = "title";
  public static final String EXTRA_MIME = "mime";
  public static final String EXTRA_SIZE_BYTES = "size_bytes";

  // Speeds the user can cycle through by tapping the "1x" badge. Order
  // matches Telegram's: half, normal, fast, faster, double.
  private static final float[] SPEEDS = new float[] { 0.5f, 1.0f, 1.25f, 1.5f, 2.0f };
  private int speedIdx = 1; // start at 1.0x

  private static final long CONTROLS_AUTOHIDE_MS = 3500L;

  public static @NonNull Intent newIntent(@NonNull Context ctx, @NonNull String url) {
    return newIntent(ctx, url, null, null);
  }

  public static @NonNull Intent newIntent(@NonNull Context ctx, @NonNull String url,
                                          @Nullable String title, @Nullable String mime) {
    Intent i = new Intent(ctx, TgMediaPlayerActivity.class);
    i.putExtra(EXTRA_URL, url);
    if (title != null) i.putExtra(EXTRA_TITLE, title);
    if (mime != null) i.putExtra(EXTRA_MIME, mime);
    return i;
  }

  private VideoView videoView;
  private ProgressBar spinner;
  private TextView errorView;

  private AppBarLayout appbar;
  private LinearLayout controls;
  private ImageButton playPauseButton;
  private ImageButton fullscreenButton;
  private TextView speedButton;
  private TextView timeCurrentView;
  private TextView timeTotalView;
  private SeekBar seekBar;

  private MediaPlayer mediaPlayer; // captured in onPrepared, used for speed control
  private boolean isPrepared = false;
  private boolean isFullscreen = false;
  private boolean userSeeking = false;
  private boolean muted = false;
  private @Nullable String streamUrl;
  private @Nullable String streamMime;
  private long streamSizeBytes;
  private @Nullable TgMediaSaveTask activeSaveTask;

  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private final Runnable progressTick = new Runnable() {
    @Override
    public void run() {
      try {
        updateProgress();
      } finally {
        uiHandler.postDelayed(this, 500L);
      }
    }
  };
  private final Runnable autoHideControls = () -> setControlsVisible(false);

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    // Keep the screen on while a video is playing — matches Telegram's
    // built-in player and avoids "screen sleeps during the clip".
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    setContentView(R.layout.activity_tg_media_player);

    Toolbar toolbar = findViewById(R.id.toolbar);
    appbar = findViewById(R.id.tg_player_appbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      String title = getIntent().getStringExtra(EXTRA_TITLE);
      if (TextUtils.isEmpty(title)) title = getString(R.string.bmchat_tg_media_player_title);
      bar.setTitle(title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    videoView = findViewById(R.id.tg_player);
    spinner = findViewById(R.id.tg_player_spinner);
    errorView = findViewById(R.id.tg_player_error);

    controls = findViewById(R.id.tg_player_controls);
    playPauseButton = findViewById(R.id.tg_player_play_pause);
    fullscreenButton = findViewById(R.id.tg_player_fullscreen);
    speedButton = findViewById(R.id.tg_player_speed);
    timeCurrentView = findViewById(R.id.tg_player_time_current);
    timeTotalView = findViewById(R.id.tg_player_time_total);
    seekBar = findViewById(R.id.tg_player_seek);

    String url = getIntent().getStringExtra(EXTRA_URL);
    String mime = getIntent().getStringExtra(EXTRA_MIME);
    if (TextUtils.isEmpty(url)) { finish(); return; }
    streamUrl = url;
    streamMime = mime;
    streamSizeBytes = getIntent().getLongExtra(EXTRA_SIZE_BYTES, 0L);

    // Hand non-video MIME types straight to the system picker — no
    // point spinning up VideoView for a PDF or an mp3.
    if (mime != null && !mime.isEmpty()
        && !mime.startsWith("video/")
        && !mime.equals("application/octet-stream")) {
      IntentUtils.showInBrowser(this, url);
      finish();
      return;
    }

    wireControls(url);

    // BMChat 2.49.91: keep the custom controls row above the system nav bar.
    if (controls != null) {
      org.thoughtcrime.securesms.util.ViewUtil.applyWindowInsetsAsMargin(
          controls, false, false, false, true);
    }

    spinner.setVisibility(View.VISIBLE);
    videoView.setVideoURI(Uri.parse(url));
  }

  private void wireControls(@NonNull String url) {
    // Tapping the root toggles the controls overlay.
    View stage = findViewById(R.id.tg_player_root);
    if (stage != null) {
      stage.setOnClickListener(v -> {
        if (controls.getVisibility() == View.VISIBLE) {
          setControlsVisible(false);
        } else {
          setControlsVisible(true);
        }
      });
    }

    playPauseButton.setOnClickListener(v -> togglePlayback());

    fullscreenButton.setOnClickListener(v -> toggleFullscreen());

    speedButton.setOnClickListener(v -> cycleSpeed());

    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (!fromUser || !isPrepared) return;
        int dur = videoView.getDuration();
        if (dur <= 0) return;
        long target = (long) (dur * (progress / (float) bar.getMax()));
        timeCurrentView.setText(formatTime(target));
      }

      @Override
      public void onStartTrackingTouch(SeekBar bar) {
        userSeeking = true;
        uiHandler.removeCallbacks(autoHideControls);
      }

      @Override
      public void onStopTrackingTouch(SeekBar bar) {
        if (!isPrepared) return;
        int dur = videoView.getDuration();
        if (dur > 0) {
          int seekMs = (int) (dur * (bar.getProgress() / (float) bar.getMax()));
          videoView.seekTo(seekMs);
        }
        userSeeking = false;
        scheduleControlsAutoHide();
      }
    });

    videoView.setOnPreparedListener((MediaPlayer mp) -> {
      mediaPlayer = mp;
      isPrepared = true;
      applyMute();
      spinner.setVisibility(View.GONE);
      int dur = videoView.getDuration();
      timeTotalView.setText(dur > 0 ? formatTime(dur) : "0:00");
      applySpeed();
      // Start playback as soon as the first frame is decoded — same as
      // Telegram. Show the controls briefly so the user knows they're
      // there, then fade them.
      videoView.start();
      refreshPlayPauseIcon();
      setControlsVisible(true);
      uiHandler.removeCallbacks(progressTick);
      uiHandler.post(progressTick);
    });
    videoView.setOnCompletionListener(mp -> {
      refreshPlayPauseIcon();
      setControlsVisible(true);
    });
    videoView.setOnErrorListener((mp, what, extra) -> {
      Log.w(TAG, "VideoView error what=" + what + " extra=" + extra + " url=" + url);
      onPlaybackFailed(url);
      return true;
    });
  }

  private void togglePlayback() {
    if (!isPrepared) return;
    if (videoView.isPlaying()) {
      videoView.pause();
    } else {
      videoView.start();
    }
    refreshPlayPauseIcon();
    scheduleControlsAutoHide();
  }

  private void refreshPlayPauseIcon() {
    if (videoView.isPlaying()) {
      playPauseButton.setImageResource(R.drawable.ic_pause_white_36dp);
    } else {
      playPauseButton.setImageResource(R.drawable.ic_play_arrow_white_36dp);
    }
  }

  private void cycleSpeed() {
    speedIdx = (speedIdx + 1) % SPEEDS.length;
    applySpeed();
    scheduleControlsAutoHide();
  }

  private void applySpeed() {
    float speed = SPEEDS[speedIdx];
    speedButton.setText(formatSpeed(speed));
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
    if (mediaPlayer == null) return;
    try {
      // setPlaybackParams will throw if the player has not been
      // prepared yet — guarded by isPrepared.
      if (!isPrepared) return;
      PlaybackParams params = mediaPlayer.getPlaybackParams();
      params.setSpeed(speed);
      mediaPlayer.setPlaybackParams(params);
    } catch (Throwable t) {
      Log.w(TAG, "setPlaybackParams failed for speed=" + speed, t);
    }
  }

  private void toggleFullscreen() {
    isFullscreen = !isFullscreen;
    if (isFullscreen) {
      // Hide toolbar, force landscape (matches Telegram), and put the
      // system UI into immersive mode so nothing competes with the
      // video frame.
      if (appbar != null) appbar.setVisibility(View.GONE);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
      setSystemUiImmersive(true);
      fullscreenButton.setImageResource(R.drawable.ic_fullscreen_exit_white_24dp);
      fullscreenButton.setContentDescription(
          getString(R.string.bmchat_tg_media_player_exit_fullscreen));
    } else {
      if (appbar != null) appbar.setVisibility(View.VISIBLE);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
      setSystemUiImmersive(false);
      fullscreenButton.setImageResource(R.drawable.ic_fullscreen_white_24dp);
      fullscreenButton.setContentDescription(
          getString(R.string.bmchat_tg_media_player_fullscreen));
    }
    scheduleControlsAutoHide();
  }

  private void setSystemUiImmersive(boolean immersive) {
    try {
      View decor = getWindow().getDecorView();
      WindowInsetsControllerCompat ctrl =
          new WindowInsetsControllerCompat(getWindow(), decor);
      if (immersive) {
        ctrl.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        ctrl.hide(WindowInsetsCompat.Type.systemBars());
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
      } else {
        ctrl.show(WindowInsetsCompat.Type.systemBars());
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
      }
    } catch (Throwable t) {
      Log.w(TAG, "setSystemUiImmersive failed", t);
    }
  }

  private void applyMute() {
    if (mediaPlayer == null) return;
    float vol = muted ? 0f : 1f;
    mediaPlayer.setVolume(vol, vol);
  }

  private void updateMuteMenuItem(@NonNull MenuItem item, boolean muted) {
    item.setTitle(muted ? R.string.bmchat_unmute_audio : R.string.bmchat_mute_audio);
    android.graphics.drawable.Drawable icon =
        androidx.appcompat.content.res.AppCompatResources.getDrawable(
            this,
            muted ? R.drawable.ic_volume_off_white_24dp : R.drawable.ic_volume_up_white_24dp);
    if (icon != null) {
      item.setIcon(icon.mutate());
    }
  }

  private void toggleMute(@NonNull MenuItem item) {
    muted = !muted;
    applyMute();
    updateMuteMenuItem(item, muted);
    invalidateOptionsMenu();
  }

  private void saveToDisk() {
    if (TextUtils.isEmpty(streamUrl)) return;
    if (activeSaveTask != null && activeSaveTask.getStatus() == android.os.AsyncTask.Status.RUNNING) {
      return;
    }
    Runnable startSave =
        () -> {
          activeSaveTask =
              new TgMediaSaveTask(TgMediaPlayerActivity.this, streamSizeBytes);
          activeSaveTask.executeOnExecutor(
              android.os.AsyncTask.THREAD_POOL_EXECUTOR,
              new TgMediaSaveTask.Request(
                  streamUrl, streamMime, null, streamSizeBytes));
        };
    if (StorageUtil.canWriteToMediaStore(this)) {
      startSave.run();
      return;
    }
    Permissions.with(this)
        .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        .alwaysGrantOnSdk30()
        .ifNecessary()
        .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
        .onAllGranted(startSave)
        .execute();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.tg_media_player, menu);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem mute = menu.findItem(R.id.bmchat_tg_player__mute);
    if (mute != null) {
      updateMuteMenuItem(mute, muted);
    }
    return super.onPrepareOptionsMenu(menu);
  }

  private void updateProgress() {
    if (!isPrepared) return;
    int dur = videoView.getDuration();
    int pos = videoView.getCurrentPosition();
    if (dur > 0 && !userSeeking) {
      int scaled = (int) (seekBar.getMax() * (pos / (float) dur));
      seekBar.setProgress(scaled);
      timeCurrentView.setText(formatTime(pos));
    }
    refreshPlayPauseIcon();
  }

  private void setControlsVisible(boolean visible) {
    if (controls == null) return;
    controls.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (visible) {
      // Also re-show the toolbar in non-fullscreen mode so the user
      // can scroll back, just like Telegram.
      if (!isFullscreen && appbar != null) appbar.setVisibility(View.VISIBLE);
      scheduleControlsAutoHide();
    } else {
      // In fullscreen we hide the toolbar too, mirroring Telegram.
      if (isFullscreen && appbar != null) appbar.setVisibility(View.GONE);
      uiHandler.removeCallbacks(autoHideControls);
    }
  }

  private void scheduleControlsAutoHide() {
    uiHandler.removeCallbacks(autoHideControls);
    // Don't auto-hide while paused — there's nothing playing under
    // the controls and the user almost certainly wants to interact.
    if (isPrepared && !videoView.isPlaying()) return;
    uiHandler.postDelayed(autoHideControls, CONTROLS_AUTOHIDE_MS);
  }

  private static @NonNull String formatTime(long ms) {
    long total = ms / 1000L;
    long h = total / 3600L;
    long m = (total % 3600L) / 60L;
    long s = total % 60L;
    if (h > 0) {
      return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
    }
    return String.format(Locale.ROOT, "%d:%02d", m, s);
  }

  private static @NonNull String formatSpeed(float speed) {
    if (Math.abs(speed - Math.round(speed)) < 0.01f) {
      return ((int) speed) + "x";
    }
    return String.format(Locale.ROOT, "%.2fx", speed)
        .replace(".00x", "x")
        .replace(".50x", ".5x");
  }

  private void onPlaybackFailed(@NonNull String url) {
    spinner.setVisibility(View.GONE);
    errorView.setVisibility(View.VISIBLE);
    // Offer the user the system-browser fallback. Plain remote MP4
    // sometimes plays fine in Chrome / a third-party player even
    // when AOSP MediaPlayer can't handle the codec.
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_tg_media_player_error_title)
        .setMessage(R.string.bmchat_tg_media_player_error_explain)
        .setPositiveButton(R.string.bmchat_tg_media_player_open_external, (d, w) -> {
          IntentUtils.showInBrowser(this, url);
          finish();
        })
        .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
        .show();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.bmchat_tg_player__mute) {
      toggleMute(item);
      return true;
    }
    if (id == R.id.bmchat_tg_player__save) {
      saveToDisk();
      return true;
    }
    if (id == android.R.id.home) {
      // In fullscreen the toolbar is hidden, but the back gesture or
      // hardware back will still come here — fall through to finish.
      if (isFullscreen) {
        toggleFullscreen();
        return true;
      }
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onBackPressed() {
    if (isFullscreen) {
      toggleFullscreen();
      return;
    }
    super.onBackPressed();
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    // Re-apply the immersive UI flags after a rotation; otherwise the
    // status / nav bars sometimes pop back into view on Samsung One UI.
    if (isFullscreen) setSystemUiImmersive(true);
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (videoView != null && videoView.isPlaying()) {
      videoView.pause();
    }
    uiHandler.removeCallbacks(progressTick);
    uiHandler.removeCallbacks(autoHideControls);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (isPrepared) {
      uiHandler.post(progressTick);
    }
  }

  @Override
  protected void onDestroy() {
    uiHandler.removeCallbacks(progressTick);
    uiHandler.removeCallbacks(autoHideControls);
    if (videoView != null) {
      try { videoView.stopPlayback(); } catch (Throwable ignored) {}
    }
    mediaPlayer = null;
    super.onDestroy();
  }
}
