package org.thoughtcrime.securesms.album;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.mms.GlideApp;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram-style media picker — a 3-column grid of recent photos and videos
 * with numbered checkboxes for ordered multi-selection.
 *
 * <p>This is BMChat 2.49.63's <i>skeleton</i> for the picker the user asked
 * for. It is fully self-contained and testable on its own (you can launch it
 * with {@code am start -n …/.album.BMChatGalleryPickerActivity}). Hooking it
 * up to the attach-button popup (replacing the OS gallery intent) and the
 * "send album" pipeline is the second half of the work and will land in a
 * follow-up; this commit is the visible UI scaffold so you can already
 * reason about the look-and-feel.
 *
 * <p>What works in this skeleton:
 *
 * <ul>
 *   <li>Loads the most recent ~500 image/video items from {@link MediaStore}
 *       (combined query, sorted by {@code DATE_ADDED DESC}).</li>
 *   <li>Renders them in a 3-column {@link RecyclerView} grid.</li>
 *   <li>Tapping a tile cycles its <em>selection number</em>: 1, 2, 3, … so
 *       the album order matches the order the user picked, exactly like
 *       Telegram. Tapping a numbered tile clears it and renumbers the rest.</li>
 *   <li>The action bar shows "Send (n)" once at least one tile is picked.</li>
 * </ul>
 *
 * <p>What's still TODO (intentionally out of scope for this commit):
 *
 * <ul>
 *   <li>Pass the selection back to ConversationActivity via Activity result.</li>
 *   <li>Caption editing per-album with @-mentions / formatting toolbar.</li>
 *   <li>Switch between "all media" / "photos only" / "videos only" tabs.</li>
 *   <li>Camera capture button as the first tile.</li>
 *   <li>Scoped-storage permission flow on Android 14+ (READ_MEDIA_VISUAL_USER_SELECTED).</li>
 * </ul>
 */
public class BMChatGalleryPickerActivity extends AppCompatActivity {

  /** Maximum number of tiles to fetch — Telegram caps an album at 10 anyway. */
  private static final int LOAD_LIMIT = 500;

  private RecyclerView grid;
  private Toolbar toolbar;
  private final List<MediaItem> items = new ArrayList<>();
  private final ArrayList<Uri> selectedOrder = new ArrayList<>();
  private GalleryAdapter adapter;
  private ProcessCameraProvider cameraProvider;
  private ImageCapture imageCapture;
  private PreviewView activeCameraPreview;
  private View cameraFullscreen;
  private PreviewView cameraFullscreenPreview;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // BMChat 2.49.67: make sure the system bars do not draw on top of our
    // toolbar. The default NoActionBar theme leaves the status bar
    // transparent/translucent, which made the "Отправить (N)" action
    // collide with the clock/battery icons. Force-paint the status bar in
    // our brand color and ensure the activity owns the inset, then rely on
    // fitsSystemWindows on the root view to push the toolbar down.
    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    getWindow()
        .addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
    getWindow()
        .setStatusBarColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.delta_primary));
    getWindow()
        .setNavigationBarColor(
            androidx.core.content.ContextCompat.getColor(this, android.R.color.black));

    setContentView(R.layout.bmchat_gallery_picker_activity);

    toolbar = findViewById(R.id.bmchat_gallery_toolbar);
    grid = findViewById(R.id.bmchat_gallery_grid);
    cameraFullscreen = findViewById(R.id.bmchat_gallery_camera_fullscreen);
    cameraFullscreenPreview = findViewById(R.id.bmchat_gallery_camera_fullscreen_preview);
    findViewById(R.id.bmchat_gallery_camera_close).setOnClickListener(v -> closeFullscreenCamera());
    findViewById(R.id.bmchat_gallery_camera_capture).setOnClickListener(v -> captureCameraFrame());
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(R.string.bmchat_gallery_picker_title);
    }

    grid.setLayoutManager(new GridLayoutManager(this, 3));
    adapter = new GalleryAdapter();
    grid.setAdapter(adapter);

    // Android 13+ (API 33) split storage permissions: instead of
    // READ_EXTERNAL_STORAGE we now need READ_MEDIA_IMAGES / READ_MEDIA_VIDEO,
    // and on 14+ optionally READ_MEDIA_VISUAL_USER_SELECTED for the partial
    // grant flow. We ask for whichever set this device understands; if the
    // user denies we still try to load (legacy installs may keep working
    // because the picker fragment reads our own app-owned files first).
    String[] perms;
    if (android.os.Build.VERSION.SDK_INT >= 33) {
      perms =
          new String[] {
              "android.permission.READ_MEDIA_IMAGES",
              "android.permission.READ_MEDIA_VIDEO",
              android.Manifest.permission.CAMERA
          };
    } else {
      perms =
          new String[] {
              android.Manifest.permission.READ_EXTERNAL_STORAGE,
              android.Manifest.permission.CAMERA
          };
    }

    boolean needAsk = false;
    for (String p : perms) {
      if (androidx.core.content.ContextCompat.checkSelfPermission(this, p)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        needAsk = true;
        break;
      }
    }
    if (needAsk) {
      ActivityCompat.requestPermissions(this, perms, 1);
    } else {
      loadItems();
    }
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != 1) return;
    // Be lenient: as long as we got *any* grant we try to list — Android 14
    // partial-grant returns READ_MEDIA_VISUAL_USER_SELECTED only, with the
    // canonical READ_MEDIA_IMAGES still denied, and that's a valid state.
    boolean anyGranted = false;
    for (int g : grantResults) {
      if (g == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        anyGranted = true;
        break;
      }
    }
    if (anyGranted) loadItems();
    else finish();
    if (activeCameraPreview != null && hasCameraPermission()) {
      startCameraPreview(activeCameraPreview);
    }
  }

  /**
   * Pull the most recent {@link #LOAD_LIMIT} images and videos from MediaStore
   * via the unified "Files" volume — keeps a single sorted timeline regardless
   * of media kind, which matches how Telegram's gallery picker presents items.
   */
  private void loadItems() {
    items.clear();
    Uri q = MediaStore.Files.getContentUri("external");
    String[] proj = new String[] {
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.DATE_ADDED};
    String sel =
        MediaStore.Files.FileColumns.MEDIA_TYPE
            + " IN ("
            + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            + ","
            + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            + ")";
    // Android 11+ enforces strict sort-arg parsing and rejects the legacy
    // "<col> DESC LIMIT N" sugar (throws IllegalArgumentException: Invalid
    // token LIMIT). Use the bundle-based query on API 26+ to express the
    // limit/offset explicitly, and fall back to plain sort + manual break on
    // older releases.
    String order = MediaStore.Files.FileColumns.DATE_ADDED + " DESC";
    ContentResolver cr = getContentResolver();
    Cursor c;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      android.os.Bundle args = new android.os.Bundle();
      args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel);
      args.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order);
      args.putInt(ContentResolver.QUERY_ARG_LIMIT, LOAD_LIMIT);
      c = cr.query(q, proj, args, null);
    } else {
      c = cr.query(q, proj, sel, null, order);
    }
    if (c == null) return;
    try (Cursor cursor = c) {
      int idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
      int typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
      int loaded = 0;
      while (cursor.moveToNext() && loaded < LOAD_LIMIT) {
        long id = cursor.getLong(idIdx);
        int type = cursor.getInt(typeIdx);
        Uri base =
            type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        items.add(new MediaItem(Uri.withAppendedPath(base, String.valueOf(id)), type));
        loaded++;
      }
    }
    adapter.notifyDataSetChanged();
  }

  @Override
  public boolean onCreateOptionsMenu(@NonNull Menu menu) {
    menu.add(0, R.id.bmchat_gallery_picker_send, 0, R.string.bmchat_gallery_picker_send)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(@NonNull Menu menu) {
    MenuItem send = menu.findItem(R.id.bmchat_gallery_picker_send);
    if (send != null) {
      send.setVisible(!selectedOrder.isEmpty());
      send.setTitle(getString(R.string.bmchat_gallery_picker_send_n, selectedOrder.size()));
    }
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      setResult(RESULT_CANCELED);
      finish();
      return true;
    }
    if (item.getItemId() == R.id.bmchat_gallery_picker_send) {
      handleSend();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /**
   * Pack the ordered selection into the same {@link Intent} shape the system
   * gallery picker returns to {@link
   * org.thoughtcrime.securesms.ConversationActivity#onActivityResult}: a
   * single {@link Uri} for a one-item pick, or a {@link ClipData} for many.
   * The existing PICK_GALLERY result handler already knows how to turn a
   * multi-uri ClipData into a Telegram-style album via
   * {@code SendRelayedMessageUtil.sendAlbum}, so we plug straight into it
   * without touching the send pipeline.
   */
  private void handleSend() {
    if (selectedOrder.isEmpty()) {
      finish();
      return;
    }
    Intent result = new Intent();
    if (selectedOrder.size() == 1) {
      result.setData(selectedOrder.get(0));
    } else {
      ClipData clip =
          new ClipData(
              "BMChat selection",
              new String[] {"image/*", "video/*"},
              new ClipData.Item(selectedOrder.get(0)));
      for (int i = 1; i < selectedOrder.size(); i++) {
        clip.addItem(new ClipData.Item(selectedOrder.get(i)));
      }
      result.setClipData(clip);
    }
    result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    setResult(RESULT_OK, result);
    finish();
  }

  private boolean hasCameraPermission() {
    return ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        == android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  private void startCameraPreview(@NonNull PreviewView previewView) {
    activeCameraPreview = previewView;
    if (cameraFullscreen != null && cameraFullscreen.getVisibility() == View.VISIBLE) {
      return;
    }
    if (!hasCameraPermission()) {
      return;
    }

    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(
        () -> {
          try {
            cameraProvider = future.get();
            bindCameraPreview(previewView);
          } catch (Exception e) {
            imageCapture = null;
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private void bindCameraPreview(@NonNull PreviewView previewView) {
    if (cameraProvider == null || !hasCameraPermission()) {
      return;
    }

    Preview preview = new Preview.Builder().build();
    ImageCapture capture =
        new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build();
    preview.setSurfaceProvider(previewView.getSurfaceProvider());

    cameraProvider.unbindAll();
    cameraProvider.bindToLifecycle(
        this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture);
    imageCapture = capture;
  }

  private void openFullscreenCamera() {
    if (!hasCameraPermission()) {
      ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.CAMERA}, 1);
      return;
    }
    cameraFullscreen.setVisibility(View.VISIBLE);
    bindFullscreenCameraPreview();
  }

  private void bindFullscreenCameraPreview() {
    if (cameraProvider != null) {
      bindCameraPreview(cameraFullscreenPreview);
      return;
    }

    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(
        () -> {
          try {
            cameraProvider = future.get();
            if (cameraFullscreen.getVisibility() == View.VISIBLE) {
              bindCameraPreview(cameraFullscreenPreview);
            }
          } catch (Exception e) {
            imageCapture = null;
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private void closeFullscreenCamera() {
    cameraFullscreen.setVisibility(View.GONE);
    if (activeCameraPreview != null) {
      startCameraPreview(activeCameraPreview);
    }
  }

  private void captureCameraFrame() {
    if (!hasCameraPermission()) {
      ActivityCompat.requestPermissions(this, new String[] {android.Manifest.permission.CAMERA}, 1);
      return;
    }
    if (imageCapture == null) {
      if (cameraFullscreen.getVisibility() == View.VISIBLE) {
        bindFullscreenCameraPreview();
        android.widget.Toast.makeText(
                this,
                R.string.bmchat_gallery_picker_camera_error,
                android.widget.Toast.LENGTH_SHORT)
            .show();
      } else if (activeCameraPreview != null) {
        openFullscreenCamera();
      } else {
        launchCamera();
      }
      return;
    }

    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, "BMChat_" + System.currentTimeMillis() + ".jpg");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    ImageCapture.OutputFileOptions options =
        new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build();
    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(this),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
            Uri shot = outputFileResults.getSavedUri();
            if (shot != null) {
              addCapturedShot(shot);
              closeFullscreenCamera();
            }
          }

          @Override
          public void onError(@NonNull ImageCaptureException exception) {
            android.widget.Toast.makeText(
                    BMChatGalleryPickerActivity.this,
                    R.string.bmchat_gallery_picker_camera_error,
                    android.widget.Toast.LENGTH_SHORT)
                .show();
          }
        });
  }

  private void addCapturedShot(@NonNull Uri shot) {
    items.add(0, new MediaItem(shot, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE));
    if (selectedOrder.size() < 10) selectedOrder.add(shot);
    adapter.notifyDataSetChanged();
    invalidateOptionsMenu();
    if (getSupportActionBar() != null) {
      getSupportActionBar()
          .setTitle(
              selectedOrder.isEmpty()
                  ? getString(R.string.bmchat_gallery_picker_title)
                  : getString(R.string.bmchat_gallery_picker_title_n, selectedOrder.size()));
    }
  }

  /**
   * Launch the system camera (Telegram-style "tile 0"). The captured image
   * is written into a public MediaStore entry so the URI we get back has
   * a stable, persistable path — important because the result handler in
   * {@link org.thoughtcrime.securesms.ConversationActivity} reads the URI
   * later from a background thread. We then append it to {@code
   * selectedOrder} and finish the picker if the user wants to send just
   * the snapshot, or fall back to the grid so it can be combined with
   * other tiles before pressing "Отправить".
   */
  private static final int REQ_CAMERA = 100;
  private Uri pendingCameraUri = null;

  private void launchCamera() {
    // Reserve a public MediaStore slot up-front so we get a stable Uri
    // we can return to ConversationActivity, regardless of which camera
    // app the user picks.
    android.content.ContentValues values = new android.content.ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, "BMChat_" + System.currentTimeMillis() + ".jpg");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    Uri target =
        getContentResolver()
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    if (target == null) {
      android.widget.Toast.makeText(this, "Camera unavailable", android.widget.Toast.LENGTH_SHORT)
          .show();
      return;
    }
    pendingCameraUri = target;
    Intent take = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
    take.putExtra(MediaStore.EXTRA_OUTPUT, target);
    take.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    take.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      startActivityForResult(take, REQ_CAMERA);
    } catch (android.content.ActivityNotFoundException anf) {
      pendingCameraUri = null;
      android.widget.Toast.makeText(this, "No camera app", android.widget.Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQ_CAMERA) return;
    Uri shot = pendingCameraUri;
    pendingCameraUri = null;
    if (resultCode != RESULT_OK || shot == null) {
      if (shot != null) {
        // Clean up the reserved MediaStore slot when the user cancels.
        getContentResolver().delete(shot, null, null);
      }
      return;
    }
    addCapturedShot(shot);
  }

  @Override
  protected void onDestroy() {
    if (cameraProvider != null) {
      cameraProvider.unbindAll();
    }
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (cameraFullscreen != null && cameraFullscreen.getVisibility() == View.VISIBLE) {
      closeFullscreenCamera();
      return;
    }
    super.onBackPressed();
  }

  /** A single item shown in the grid. */
  private static final class MediaItem {
    final Uri uri;
    final int mediaType; // MediaStore.Files.FileColumns.MEDIA_TYPE_*

    MediaItem(Uri uri, int mediaType) {
      this.uri = uri;
      this.mediaType = mediaType;
    }
  }

  /**
   * Adapter that renders {@link MediaItem}s and tracks ordered selection.
   * Position 0 is always the camera tile (Telegram-style); the actual
   * MediaStore items start at position 1.
   */
  private static final int VIEW_TYPE_CAMERA = 0;
  private static final int VIEW_TYPE_MEDIA = 1;

  private final class GalleryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    @Override
    public int getItemViewType(int position) {
      return position == 0 ? VIEW_TYPE_CAMERA : VIEW_TYPE_MEDIA;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      int layoutRes =
          viewType == VIEW_TYPE_CAMERA
              ? R.layout.bmchat_gallery_picker_tile_camera
              : R.layout.bmchat_gallery_picker_tile;
      View v = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
      // Square tiles: derive height from the parent grid's measured width
      // divided by the column count. Done at view-create time so layout is
      // stable before the first frame.
      int screenW = getResources().getDisplayMetrics().widthPixels;
      int sideLen = screenW / 3;
      ViewGroup.LayoutParams lp = v.getLayoutParams();
      if (lp == null) {
        lp = new ViewGroup.LayoutParams(sideLen, sideLen);
      } else {
        lp.width = sideLen;
        lp.height = sideLen;
      }
      v.setLayoutParams(lp);
      return viewType == VIEW_TYPE_CAMERA ? new CameraHolder(v) : new TileHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
      if (holder instanceof CameraHolder) {
        ((CameraHolder) holder).bind();
      } else {
        ((TileHolder) holder).bind(items.get(position - 1));
      }
    }

    @Override
    public int getItemCount() {
      return items.size() + 1; // +1 for the camera tile
    }
  }

  /** ViewHolder for the camera tile at position 0. */
  private final class CameraHolder extends RecyclerView.ViewHolder {
    final PreviewView previewView;

    CameraHolder(@NonNull View v) {
      super(v);
      previewView = v.findViewById(R.id.bmchat_gallery_camera_preview);
    }

    void bind() {
      startCameraPreview(previewView);
      itemView.setOnClickListener(v -> openFullscreenCamera());
    }
  }

  /** ViewHolder for a single grid tile (thumbnail + numbered selection badge). */
  private final class TileHolder extends RecyclerView.ViewHolder {
    final ImageView thumb;
    final TextView playOverlay;
    final TextView numberBadge;
    final FrameLayout selectionFrame;

    TileHolder(@NonNull View v) {
      super(v);
      thumb = v.findViewById(R.id.bmchat_gallery_tile_thumb);
      playOverlay = v.findViewById(R.id.bmchat_gallery_tile_play);
      numberBadge = v.findViewById(R.id.bmchat_gallery_tile_number);
      selectionFrame = v.findViewById(R.id.bmchat_gallery_tile_frame);
    }

    void bind(MediaItem mi) {
      GlideApp.with(BMChatGalleryPickerActivity.this).load(mi.uri).centerCrop().into(thumb);
      playOverlay.setVisibility(
          mi.mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? View.VISIBLE : View.GONE);

      int idx = selectedOrder.indexOf(mi.uri);
      if (idx >= 0) {
        numberBadge.setVisibility(View.VISIBLE);
        numberBadge.setText(String.valueOf(idx + 1));
        selectionFrame.setForeground(
            getResources().getDrawable(R.drawable.bmchat_gallery_tile_selected_frame));
      } else {
        numberBadge.setVisibility(View.GONE);
        selectionFrame.setForeground(null);
      }

      itemView.setOnClickListener(v -> toggleSelection(mi));
    }
  }

  /**
   * Toggle the selection of a tile. If it was already picked, drop it and
   * renumber the rest; otherwise append it to {@link #selectedOrder} and
   * refresh just the affected positions.
   */
  private void toggleSelection(MediaItem mi) {
    int idx = selectedOrder.indexOf(mi.uri);
    if (idx >= 0) {
      selectedOrder.remove(idx);
    } else {
      // Telegram caps an album at 10 items — same here.
      if (selectedOrder.size() >= 10) return;
      selectedOrder.add(mi.uri);
    }
    adapter.notifyDataSetChanged();
    invalidateOptionsMenu();
    if (getSupportActionBar() != null) {
      if (selectedOrder.isEmpty()) {
        getSupportActionBar().setTitle(R.string.bmchat_gallery_picker_title);
      } else {
        getSupportActionBar()
            .setTitle(getString(R.string.bmchat_gallery_picker_title_n, selectedOrder.size()));
      }
    }
  }
}
