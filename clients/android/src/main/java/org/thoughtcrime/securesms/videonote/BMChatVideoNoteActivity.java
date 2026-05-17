package org.thoughtcrime.securesms.videonote;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import org.thoughtcrime.securesms.R;

/**
 * BMChat 2.49.82 (Phase 5): Telegram-style round video note recorder.
 *
 * <p>The activity captures a square video from the front camera, clipped to
 * a circle in the preview via {@link ViewOutlineProvider}, and returns the
 * resulting MP4 to the caller as an {@code EXTRA_RESULT_URI}. Captions and
 * delivery are handled by the caller so this screen stays focused on the
 * recording experience alone.
 */
public class BMChatVideoNoteActivity extends AppCompatActivity {

  private static final String TAG = "BMChatVideoNote";

  public static final String EXTRA_RESULT_URI = "bmchat.videonote.result_uri";

  // Telegram limits round video notes to 60 seconds. We mirror that exactly
  // so the resulting files don't grow into unsendable monsters.
  private static final long MAX_DURATION_MS = 60_000L;

  private static final int REQUEST_PERMS = 0xBC01;

  private PreviewView previewView;
  private TextView timerView;
  private ImageButton recordBtn;
  private ImageButton switchCameraBtn;
  private View progressRing;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private VideoCapture<Recorder> videoCapture;
  @Nullable private Recording recording;
  @Nullable private File outputFile;

  private int lensFacing = CameraSelector.LENS_FACING_FRONT;
  private long recordStartedAt = 0L;
  private final Handler timerHandler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.bmchat_video_note_activity);

    previewView = findViewById(R.id.bmchat_video_note_preview);
    timerView = findViewById(R.id.bmchat_video_note_timer);
    recordBtn = findViewById(R.id.bmchat_video_note_record);
    switchCameraBtn = findViewById(R.id.bmchat_video_note_switch_camera);
    progressRing = findViewById(R.id.bmchat_video_note_progress_ring);

    applyCircularOutline(previewView);

    findViewById(R.id.bmchat_video_note_cancel).setOnClickListener(v -> finishCancelled());
    recordBtn.setOnClickListener(v -> toggleRecording());
    switchCameraBtn.setOnClickListener(v -> switchCamera());

    if (!hasPerms()) {
      ActivityCompat.requestPermissions(
          this,
          new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
          REQUEST_PERMS);
      return;
    }

    bindCamera();
  }

  private boolean hasPerms() {
    return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == REQUEST_PERMS) {
      if (hasPerms()) {
        bindCamera();
      } else {
        Toast.makeText(this, R.string.perm_explain_access_to_storage_denied, Toast.LENGTH_LONG)
            .show();
        finishCancelled();
      }
    }
  }

  /**
   * Applies a circular clip to the preview view via {@link ViewOutlineProvider}.
   * MaterialCardView corner radius only goes up to ~50% reliably; using the
   * outline provider gives us a perfect circle regardless of view size.
   */
  private void applyCircularOutline(@NonNull View view) {
    view.setOutlineProvider(
        new ViewOutlineProvider() {
          @Override
          public void getOutline(View v, Outline outline) {
            int size = Math.min(v.getWidth(), v.getHeight());
            int left = (v.getWidth() - size) / 2;
            int top = (v.getHeight() - size) / 2;
            outline.setOval(left, top, left + size, top + size);
          }
        });
    view.setClipToOutline(true);
  }

  private void bindCamera() {
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(
        () -> {
          try {
            cameraProvider = future.get();
            rebindUseCases();
          } catch (Exception e) {
            Log.e(TAG, "Failed to bind CameraX", e);
            Toast.makeText(this, R.string.bmchat_video_note_camera_error, Toast.LENGTH_LONG).show();
            finishCancelled();
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private void rebindUseCases() {
    if (cameraProvider == null) return;
    cameraProvider.unbindAll();

    Preview preview = new Preview.Builder().build();
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    // SD quality is plenty for a phone-sized round bubble and keeps the
    // resulting file small enough to travel over IMAP without scaring users
    // about message size.
    Recorder recorder =
        new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build();
    videoCapture = VideoCapture.withOutput(recorder);

    CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

    try {
      cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
    } catch (Exception e) {
      Log.e(TAG, "Failed to bind use cases", e);
      Toast.makeText(this, R.string.bmchat_video_note_camera_error, Toast.LENGTH_LONG).show();
      finishCancelled();
    }
  }

  private void switchCamera() {
    if (recording != null) return; // can't switch lens mid-recording on most devices
    lensFacing =
        (lensFacing == CameraSelector.LENS_FACING_FRONT)
            ? CameraSelector.LENS_FACING_BACK
            : CameraSelector.LENS_FACING_FRONT;
    rebindUseCases();
  }

  private void toggleRecording() {
    if (recording == null) {
      startRecording();
    } else {
      stopRecording();
    }
  }

  @SuppressWarnings("MissingPermission") // hasPerms() is checked before bindCamera()
  private void startRecording() {
    if (videoCapture == null) return;

    File dir = new File(getCacheDir(), "bmchat-videonotes");
    if (!dir.exists() && !dir.mkdirs()) {
      Toast.makeText(this, R.string.bmchat_video_note_camera_error, Toast.LENGTH_LONG).show();
      return;
    }
    // The ".vn." infix lets ConversationItem render the playback bubble as
    // a circle later instead of the default rectangle.
    outputFile = new File(dir, "bmchat-vn-" + System.currentTimeMillis() + ".vn.mp4");

    FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();
    recording =
        videoCapture
            .getOutput()
            .prepareRecording(this, options)
            .withAudioEnabled()
            .start(
                ContextCompat.getMainExecutor(this),
                event -> {
                  if (event instanceof VideoRecordEvent.Start) {
                    recordStartedAt = System.currentTimeMillis();
                    progressRing.setVisibility(View.VISIBLE);
                    timerHandler.post(timerTick);
                  } else if (event instanceof VideoRecordEvent.Finalize) {
                    timerHandler.removeCallbacks(timerTick);
                    progressRing.setVisibility(View.GONE);
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    if (finalize.hasError()) {
                      Log.e(
                          TAG,
                          "Recording finished with error: " + finalize.getError(),
                          finalize.getCause());
                      Toast.makeText(
                              BMChatVideoNoteActivity.this,
                              R.string.bmchat_video_note_camera_error,
                              Toast.LENGTH_LONG)
                          .show();
                      finishCancelled();
                    } else if (outputFile != null && outputFile.exists()) {
                      finishWithResult(Uri.fromFile(outputFile));
                    } else {
                      finishCancelled();
                    }
                    recording = null;
                  }
                });
  }

  private final Runnable timerTick =
      new Runnable() {
        @Override
        public void run() {
          long elapsed = System.currentTimeMillis() - recordStartedAt;
          if (elapsed >= MAX_DURATION_MS) {
            stopRecording();
            return;
          }
          int seconds = (int) (elapsed / 1000L);
          timerView.setText(String.format("%d:%02d", seconds / 60, seconds % 60));
          timerHandler.postDelayed(this, 250L);
        }
      };

  private void stopRecording() {
    if (recording != null) {
      recording.stop();
    }
  }

  private void finishWithResult(@NonNull Uri uri) {
    Intent data = new Intent();
    data.putExtra(EXTRA_RESULT_URI, uri);
    setResult(RESULT_OK, data);
    finish();
  }

  private void finishCancelled() {
    setResult(RESULT_CANCELED);
    finish();
  }

  @Override
  protected void onDestroy() {
    timerHandler.removeCallbacksAndMessages(null);
    if (recording != null) {
      try {
        recording.close();
      } catch (Exception ignored) {
      }
      recording = null;
    }
    if (cameraProvider != null) {
      cameraProvider.unbindAll();
      cameraProvider = null;
    }
    super.onDestroy();
  }
}
