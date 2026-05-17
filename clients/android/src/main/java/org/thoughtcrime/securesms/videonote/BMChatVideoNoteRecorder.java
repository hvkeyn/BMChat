package org.thoughtcrime.securesms.videonote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;

/**
 * BMChat 2.49.83 — Reusable round video note recorder backed by CameraX.
 *
 * <p>This controller embeds itself in an existing activity/fragment via a {@link PreviewView}. The
 * InputPanel uses it to record a Telegram-style round video while the user is holding the recorder
 * button, then releases it to send.
 *
 * <p>Permissions ({@link android.Manifest.permission#CAMERA} and
 * {@link android.Manifest.permission#RECORD_AUDIO}) must be granted before {@link #bind} is called
 * — the InputPanel checks this via {@link org.thoughtcrime.securesms.permissions.Permissions}.
 */
public final class BMChatVideoNoteRecorder {

  private static final String TAG = "BMChatVideoNoteRec";

  /** Mirror Telegram's 60 second cap. */
  public static final long MAX_DURATION_MS = 60_000L;

  public interface Listener {
    @MainThread
    void onRecordingStarted();

    @MainThread
    void onRecordingProgress(long elapsedMs);

    @MainThread
    void onRecordingFinished(@NonNull Uri resultUri);

    @MainThread
    void onRecordingFailed(@Nullable Throwable error);
  }

  private final @NonNull Context context;
  private final @NonNull PreviewView previewView;
  private final @NonNull LifecycleOwner lifecycleOwner;

  @Nullable private ProcessCameraProvider cameraProvider;
  @Nullable private VideoCapture<Recorder> videoCapture;
  @Nullable private Recording recording;
  @Nullable private File outputFile;
  @Nullable private Listener listener;

  private int lensFacing = CameraSelector.LENS_FACING_FRONT;
  private long recordStartedAt = 0L;
  private boolean bound = false;
  private boolean shouldStartWhenReady = false;
  private boolean recordingCancelledByUser = false;

  public BMChatVideoNoteRecorder(
      @NonNull Context context,
      @NonNull PreviewView previewView,
      @NonNull LifecycleOwner lifecycleOwner) {
    this.context = context.getApplicationContext();
    this.previewView = previewView;
    this.lifecycleOwner = lifecycleOwner;
    applyCircularOutline(previewView);
  }

  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  /** Bind CameraX use cases. Safe to call multiple times. */
  public void bind() {
    if (bound) {
      rebindUseCases();
      return;
    }
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
    future.addListener(
        () -> {
          try {
            cameraProvider = future.get();
            bound = true;
            rebindUseCases();
            if (shouldStartWhenReady) {
              shouldStartWhenReady = false;
              startRecording();
            }
          } catch (Exception e) {
            Log.e(TAG, "Failed to obtain camera provider", e);
            if (listener != null) listener.onRecordingFailed(e);
          }
        },
        ContextCompat.getMainExecutor(context));
  }

  /** Release CameraX resources. */
  public void unbind() {
    if (recording != null) {
      try {
        recording.close();
      } catch (Exception ignored) {
      }
      recording = null;
    }
    if (cameraProvider != null) {
      cameraProvider.unbindAll();
    }
    bound = false;
  }

  public boolean isRecording() {
    return recording != null;
  }

  /** Toggle between front and back cameras. Cannot be called mid-recording. */
  public void switchLens() {
    if (recording != null) return;
    lensFacing =
        (lensFacing == CameraSelector.LENS_FACING_FRONT)
            ? CameraSelector.LENS_FACING_BACK
            : CameraSelector.LENS_FACING_FRONT;
    rebindUseCases();
  }

  /**
   * Start recording immediately, or once the camera provider becomes available. The output goes
   * to a {@code .vn.mp4} file inside the app cache.
   */
  @SuppressLint("MissingPermission") // checked by caller (Permissions.hasAll)
  public void startRecording() {
    if (recording != null) return;
    if (!bound || videoCapture == null) {
      shouldStartWhenReady = true;
      if (!bound) bind();
      return;
    }

    File dir = new File(context.getCacheDir(), "bmchat-videonotes");
    if (!dir.exists() && !dir.mkdirs()) {
      if (listener != null) listener.onRecordingFailed(new IllegalStateException("mkdir failed"));
      return;
    }
    outputFile = new File(dir, "bmchat-vn-" + System.currentTimeMillis() + ".vn.mp4");
    recordingCancelledByUser = false;

    FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();
    recording =
        videoCapture
            .getOutput()
            .prepareRecording(context, options)
            .withAudioEnabled()
            .start(
                ContextCompat.getMainExecutor(context),
                event -> {
                  if (event instanceof VideoRecordEvent.Start) {
                    recordStartedAt = System.currentTimeMillis();
                    if (listener != null) listener.onRecordingStarted();
                  } else if (event instanceof VideoRecordEvent.Status) {
                    if (listener != null) {
                      long elapsed = System.currentTimeMillis() - recordStartedAt;
                      listener.onRecordingProgress(elapsed);
                      if (elapsed >= MAX_DURATION_MS) stopRecording(false);
                    }
                  } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    Recording done = recording;
                    recording = null;
                    if (recordingCancelledByUser) {
                      deleteOutputFile();
                      return;
                    }
                    if (finalize.hasError()) {
                      Log.e(TAG, "Recording error: " + finalize.getError(), finalize.getCause());
                      deleteOutputFile();
                      if (listener != null) listener.onRecordingFailed(finalize.getCause());
                    } else if (outputFile != null && outputFile.exists()) {
                      if (listener != null) listener.onRecordingFinished(Uri.fromFile(outputFile));
                    } else if (listener != null) {
                      listener.onRecordingFailed(new IllegalStateException("file vanished"));
                    }
                  }
                });
  }

  /**
   * Stop the running recording. If {@code cancel} is true the file is discarded once the recorder
   * has been finalised.
   */
  public void stopRecording(boolean cancel) {
    if (recording == null) {
      shouldStartWhenReady = false;
      return;
    }
    recordingCancelledByUser = cancel;
    recording.stop();
  }

  private void deleteOutputFile() {
    if (outputFile != null) {
      // noinspection ResultOfMethodCallIgnored
      outputFile.delete();
      outputFile = null;
    }
  }

  private void rebindUseCases() {
    if (cameraProvider == null) return;
    cameraProvider.unbindAll();

    Preview preview = new Preview.Builder().build();
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    Recorder recorder =
        new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build();
    videoCapture = VideoCapture.withOutput(recorder);

    CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
    try {
      cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, videoCapture);
    } catch (Exception e) {
      Log.e(TAG, "Failed to bind use cases", e);
      if (listener != null) listener.onRecordingFailed(e);
    }
  }

  private static void applyCircularOutline(@NonNull View view) {
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
}
