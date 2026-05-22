/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMediaGalleryElement;
import com.b44t.messenger.DcMsg;
import java.io.IOException;
import java.util.WeakHashMap;
import org.thoughtcrime.securesms.components.MediaView;
import org.thoughtcrime.securesms.components.viewpager.ExtendedOnPageChangedListener;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.loaders.PagingMediaLoader;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientModifiedListener;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.SaveAttachmentTask.Attachment;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.Util;

/** Activity for displaying media attachments in-app */
public class MediaPreviewActivity extends PassphraseRequiredActionBarActivity
    implements RecipientModifiedListener, LoaderManager.LoaderCallbacks<DcMediaGalleryElement> {

  private static final String TAG = "MediaPreviewActivity";

  public static final String ACTIVITY_TITLE_EXTRA = "activity_title";
  public static final String EDIT_AVATAR_CHAT_ID = "avatar_for_chat_id";
  public static final String ADDRESS_EXTRA = "address";
  public static final String OUTGOING_EXTRA = "outgoing";
  public static final String LEFT_IS_RECENT_EXTRA = "left_is_recent";
  public static final String DC_MSG_ID = "dc_msg_id";
  public static final String OPENED_FROM_PROFILE = "opened_from_profile";

  /** USE ONLY IF YOU HAVE NO MESSAGE ID! */
  public static final String DATE_EXTRA = "date";

  /** USE ONLY IF YOU HAVE NO MESSAGE ID! */
  public static final String SIZE_EXTRA = "size";

  @Nullable private DcMsg messageRecord;
  private DcContext dcContext;
  private MediaItem initialMedia;
  private ViewPager mediaPager;
  private Recipient conversationRecipient;
  private boolean leftIsRecent;

  private int restartItem = -1;

  private int editAvatarChatId = 0;

  @Override
  protected void onPreCreate() {
    dynamicTheme =
        new DynamicTheme() {
          public void onCreate(Activity activity) {
            activity.setTheme(R.style.TextSecure_DarkTheme); // force dark theme
          }

          public void onResume(Activity activity) {}
        };
    super.onPreCreate();
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    setFullscreenIfPossible();
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    setContentView(R.layout.media_preview_activity);
    applyOrientation(getResources().getConfiguration());

    editAvatarChatId = getIntent().getIntExtra(EDIT_AVATAR_CHAT_ID, 0);
    @Nullable String title = getIntent().getStringExtra(ACTIVITY_TITLE_EXTRA);
    if (title != null) {
      getSupportActionBar().setTitle(title);
    }

    initializeViews();
    initializeResources();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void setFullscreenIfPossible() {
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
  }

  @Override
  public void onModified(Recipient recipient) {
    Util.runOnMain(this::initializeActionBar);
  }

  @SuppressWarnings("ConstantConditions")
  private void initializeActionBar() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      CharSequence relativeTimeSpan;

      if (mediaItem.date > 0) {
        relativeTimeSpan = DateUtils.getExtendedRelativeTimeSpanString(this, mediaItem.date);
      } else {
        relativeTimeSpan = getString(R.string.draft);
      }

      String title;
      if (mediaItem.outgoing) {
        title = getString(R.string.self);
      } else {
        int fromId = dcContext.getMsg(mediaItem.msgId).getFromId();
        title = dcContext.getContact(fromId).getDisplayName();
      }

      // BMChat 2.49.58: when the currently shown photo / video belongs to a
      // Telegram-style media album we attach a "1 / N" suffix to the title and
      // the album's caption (if any) to the subtitle, so the viewer immediately
      // knows where they are inside the group — exactly the way Telegram's
      // grouped media preview labels every page.
      try {
        com.b44t.messenger.DcMsg msg = dcContext.getMsg(mediaItem.msgId);
        if (msg != null) {
          org.thoughtcrime.securesms.album.AlbumMarker.Info albumInfo =
              org.thoughtcrime.securesms.album.AlbumMarker.parse(msg.getText());
          if (albumInfo != null && albumInfo.total > 1) {
            title = title + " • " + albumInfo.index + " / " + albumInfo.total;
          }
        }
      } catch (Throwable t) {
        // Defensive: never break the preview if album parsing fails.
      }

      getSupportActionBar().setTitle(title);
      getSupportActionBar().setSubtitle(relativeTimeSpan);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    initializeMedia();
  }

  @Override
  public void onPause() {
    super.onPause();
    // BMChat 2.49.81 (Phase 4): when the system shrinks the activity into
    // Picture-in-Picture we want playback to keep running, otherwise the
    // floating window would freeze. cleanupMedia() is only run on real
    // exits / configuration changes.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode()) {
      return;
    }
    restartItem = cleanupMedia();
  }

  @Override
  protected void onUserLeaveHint() {
    super.onUserLeaveHint();
    // BMChat 2.49.81 (Phase 4): Telegram-style PiP — when the user swipes
    // home / opens another app while watching a video, drop into a
    // floating PiP window so playback survives the multitask jump.
    maybeEnterPictureInPicture();
  }

  /**
   * Enters PiP if the current media item is a video and the device
   * supports the feature. Silently no-ops on phones/launchers that
   * don't expose PiP.
   */
  private void maybeEnterPictureInPicture() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
    if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;

    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null || !MediaUtil.isVideoType(mediaItem.type)) return;

    try {
      PictureInPictureParams params =
          new PictureInPictureParams.Builder()
              // Standard 16:9 frame fits both landscape captures and most
              // portrait phone videos without weird letterboxing.
              .setAspectRatio(new Rational(16, 9))
              .build();
      enterPictureInPictureMode(params);
    } catch (IllegalStateException | IllegalArgumentException e) {
      Log.w(TAG, "PiP not available", e);
    }
  }

  @Override
  public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
    super.onPictureInPictureModeChanged(isInPip, newConfig);
    // BMChat 2.49.81 (Phase 4): hide the action bar / save & share toolbar
    // when shrinking into PiP so the small floating window shows just the
    // video; restore them when the user expands the activity back.
    if (getSupportActionBar() != null) {
      if (isInPip) {
        getSupportActionBar().hide();
      } else {
        getSupportActionBar().show();
      }
    }
    invalidateOptionsMenu();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    initializeResources();
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    applyOrientation(newConfig);
  }

  /**
   * BMChat 2.49.88: belt-and-suspenders landscape immersive mode. We hit a Samsung One UI quirk
   * where the new {@code WindowInsetsControllerCompat.hide(systemBars())} call from 2.49.87 was
   * silently ignored by the OS — status bar + action bar stayed visible and the video kept
   * sliding down. Now we cover three Android generations at once: (1) the modern AndroidX
   * inset controller; (2) the pre-30 legacy {@code setSystemUiVisibility} flag combo that One UI
   * still honours; (3) the cutout layout mode so we get the whole screen even on devices with a
   * notch. The flag set matches Telegram's video viewer almost verbatim — `IMMERSIVE_STICKY`
   * means the system bars peek transiently on swipe-from-edge and auto-hide again.
   *
   * <p>We also run the same hide on the activity action bar and on the inflated layout root
   * (background to black, no padding, no insets) — that part has been there since 2.49.87 and
   * by itself wasn't enough but stays as a safety net so the StyledPlayerView background never
   * shows grey 16dp gutters in landscape.
   */
  private void applyOrientation(@NonNull Configuration config) {
    boolean landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
    Window window = getWindow();
    if (window != null) {
      // Modern AndroidX path (API 30+ properly, AndroidX backport for older).
      androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !landscape);
      androidx.core.view.WindowInsetsControllerCompat controller =
          androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView());
      if (controller != null) {
        if (landscape) {
          controller.setSystemBarsBehavior(
              androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
          controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        } else {
          controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        }
      }

      // Legacy systemUiVisibility flag combo — One UI / MIUI still listen to this even on API 33.
      // We post() so the changes survive the immediate post-onConfigurationChanged layout pass
      // and Samsung's status-bar restorer doesn't undo them.
      View decor = window.getDecorView();
      if (landscape) {
        Runnable apply =
            () ->
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        apply.run();
        decor.post(apply);
      } else {
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
      }

      // Cutout / notch: in landscape let the content extend under the cutout.
      if (Build.VERSION.SDK_INT >= 28) {
        WindowManager.LayoutParams params = window.getAttributes();
        params.layoutInDisplayCutoutMode =
            landscape
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        window.setAttributes(params);
      }
    }
    View root = findViewById(android.R.id.content);
    if (root instanceof ViewGroup) {
      ViewGroup container = (ViewGroup) root;
      View previewRoot = container.getChildCount() > 0 ? container.getChildAt(0) : null;
      if (previewRoot != null) {
        previewRoot.setFitsSystemWindows(!landscape);
        if (landscape) {
          previewRoot.setPadding(0, 0, 0, 0);
          previewRoot.setBackgroundColor(0xFF000000);
        } else {
          previewRoot.setBackgroundResource(R.color.gray95);
        }
        previewRoot.requestApplyInsets();
      }
    }
    if (getSupportActionBar() != null) {
      if (landscape) {
        getSupportActionBar().hide();
      } else {
        getSupportActionBar().show();
      }
    }
  }

  private void initializeViews() {
    mediaPager = findViewById(R.id.media_pager);
    mediaPager.setOffscreenPageLimit(1);
    mediaPager.addOnPageChangeListener(new ViewPagerListener());
  }

  private void initializeResources() {
    Address address = getIntent().getParcelableExtra(ADDRESS_EXTRA);

    final Context context = getApplicationContext();
    this.dcContext = DcHelper.getContext(context);
    final int msgId = getIntent().getIntExtra(DC_MSG_ID, DcMsg.DC_MSG_NO_ID);

    if (msgId == DcMsg.DC_MSG_NO_ID) {
      messageRecord = null;
      long date = getIntent().getLongExtra(DATE_EXTRA, 0);
      long size = getIntent().getLongExtra(SIZE_EXTRA, 0);
      initialMedia =
          new MediaItem(
              null,
              getIntent().getData(),
              null,
              getIntent().getType(),
              DcMsg.DC_MSG_NO_ID,
              date,
              size,
              false);

      if (address != null) {
        conversationRecipient = Recipient.from(context, address);
      } else {
        conversationRecipient = null;
      }
    } else {
      messageRecord = dcContext.getMsg(msgId);
      initialMedia =
          new MediaItem(
              Recipient.fromChat(context, msgId),
              Uri.fromFile(messageRecord.getFileAsFile()),
              messageRecord.getFilename(),
              messageRecord.getFilemime(),
              messageRecord.getId(),
              messageRecord.getDateReceived(),
              messageRecord.getFilebytes(),
              messageRecord.isOutgoing());
      conversationRecipient = Recipient.fromChat(context, msgId);
    }
    leftIsRecent = getIntent().getBooleanExtra(LEFT_IS_RECENT_EXTRA, false);
    restartItem = -1;
  }

  private void initializeMedia() {

    // if you search for the place where the media are loaded, go to 'onCreateLoader'.

    Log.w(TAG, "Loading Part URI: " + initialMedia);
    if (messageRecord != null) {
      getSupportLoaderManager().restartLoader(0, null, this);
    } else {
      mediaPager.setAdapter(
          new SingleItemPagerAdapter(
              this,
              GlideApp.with(this),
              getWindow(),
              initialMedia.uri,
              initialMedia.name,
              initialMedia.type,
              initialMedia.size));
    }
  }

  private int cleanupMedia() {
    int restartItem = mediaPager.getCurrentItem();

    mediaPager.removeAllViews();
    mediaPager.setAdapter(null);

    return restartItem;
  }

  private void editAvatar() {
    Intent intent = new Intent(this, GroupCreateActivity.class);
    intent.putExtra(GroupCreateActivity.EDIT_GROUP_CHAT_ID, editAvatarChatId);
    startActivity(intent);
    finish(); // avoid the need to update the enlarged-avatar
  }

  private void showOverview() {
    if (getIntent().getBooleanExtra(OPENED_FROM_PROFILE, false)) {
      finish();
    } else if (conversationRecipient.getAddress().isDcChat()) {
      Intent intent = new Intent(this, AllMediaActivity.class);
      intent.putExtra(
          AllMediaActivity.CHAT_ID_EXTRA, conversationRecipient.getAddress().getDcChatId());
      intent.putExtra(AllMediaActivity.FORCE_GALLERY, true);
      startActivity(intent);
      finish();
    } else if (conversationRecipient.getAddress().isDcContact()) {
      Intent intent = new Intent(this, AllMediaActivity.class);
      intent.putExtra(
          AllMediaActivity.CONTACT_ID_EXTRA, conversationRecipient.getAddress().getDcContactId());
      intent.putExtra(AllMediaActivity.FORCE_GALLERY, true);
      startActivity(intent);
      finish();
    }
  }

  private void share() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem != null) {
      DcHelper.openForViewOrShare(this, mediaItem.msgId, Intent.ACTION_SEND);
    }
  }

  @SuppressWarnings("CodeBlock2Expr")
  @SuppressLint("InlinedApi")
  private void saveToDisk() {
    MediaItem mediaItem = getCurrentMediaItem();

    if (mediaItem != null) {
      SaveAttachmentTask.showWarningDialog(
          this,
          (dialogInterface, i) -> {
            if (StorageUtil.canWriteToMediaStore(this)) {
              performSavetoDisk(mediaItem);
              return;
            }

            Permissions.with(this)
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .alwaysGrantOnSdk30()
                .ifNecessary()
                .withPermanentDenialDialog(
                    getString(R.string.perm_explain_access_to_storage_denied))
                .onAllGranted(
                    () -> {
                      performSavetoDisk(mediaItem);
                    })
                .execute();
          });
    }
  }

  private void performSavetoDisk(@NonNull MediaItem mediaItem) {
    String contentType = mediaItem.type;
    String fileName = mediaItem.name;
    Uri sourceUri = mediaItem.uri;

    if (mediaItem.msgId != DcMsg.DC_MSG_NO_ID) {
      DcMsg dcMsg = dcContext.getMsg(mediaItem.msgId);
      int state = dcMsg.getDownloadState();
      if (state != DcMsg.DC_DOWNLOAD_DONE) {
        // BMChat 2.49.86 + 87: when DC core only has the partial download (thumbnail JPEG for a
        // video, header-only for audio) saving would produce the «video as picture» bug. Trigger
        // a full download and ask the user to retry once the blob is local.
        if (state == DcMsg.DC_DOWNLOAD_AVAILABLE) {
          dcContext.downloadFullMsg(mediaItem.msgId);
        }
        android.widget.Toast.makeText(
                this,
                state == DcMsg.DC_DOWNLOAD_IN_PROGRESS
                    ? R.string.bmchat_save_downloading
                    : R.string.bmchat_save_need_download,
                android.widget.Toast.LENGTH_LONG)
            .show();
        return;
      }

      // BMChat 2.49.87: even with DC_DOWNLOAD_DONE the MIME and the on-disk file Delta core
      // hands back can be out of sync for media that the sender embedded as a thumbnail (e.g.
      // forwarded videos where the receiver only ever sees the auto-generated JPEG preview).
      // In that case `getFilemime()` returns `image/jpeg` and `getFileAsFile()` points at the
      // preview JPEG, so SaveAttachmentTask would dutifully drop a JPEG into Pictures with a
      // .jpg extension — exactly the «saved a picture instead of the video» bug the user kept
      // reporting. Trust the ViewType (which always reflects the original intent of the
      // message), force a video/audio MIME and copy bytes from `dcMsg.getFile()` directly so
      // routing into Movies/Music is unambiguous.
      int viewType = dcMsg.getType();
      String dcFile = dcMsg.getFile();
      if (dcFile != null && !dcFile.isEmpty()) {
        sourceUri = Uri.fromFile(new java.io.File(dcFile));
      }
      if (viewType == DcMsg.DC_MSG_VIDEO && (contentType == null || !contentType.startsWith("video/"))) {
        Log.w(TAG, "Save: VIDEO viewtype but MIME=" + contentType + "; coercing to video/mp4");
        contentType = "video/mp4";
      } else if ((viewType == DcMsg.DC_MSG_AUDIO || viewType == DcMsg.DC_MSG_VOICE)
          && (contentType == null || !contentType.startsWith("audio/"))) {
        Log.w(TAG, "Save: AUDIO viewtype but MIME=" + contentType + "; coercing to audio/mpeg");
        contentType = "audio/mpeg";
      }
    }

    if (contentType == null) contentType = "application/octet-stream";

    // BMChat 2.49.87: surface that the save started so users on slow disks see progress even
    // before the ProgressDialog has a chance to lay out. We pass the resolved MIME family so
    // the toast reads «Saving video…» / «Saving audio…» / «Saving image…».
    String kind = contentType.startsWith("video/") ? "video"
        : contentType.startsWith("audio/") ? "audio"
        : contentType.startsWith("image/") ? "image"
        : "file";
    android.widget.Toast.makeText(
            this, getString(R.string.bmchat_save_started, kind), android.widget.Toast.LENGTH_SHORT)
        .show();
    Log.i(TAG, "Save: uri=" + sourceUri + " mime=" + contentType + " name=" + fileName);

    SaveAttachmentTask saveTask = new SaveAttachmentTask(MediaPreviewActivity.this);
    long saveDate = (mediaItem.date > 0) ? mediaItem.date : System.currentTimeMillis();
    saveTask.executeOnExecutor(
        AsyncTask.THREAD_POOL_EXECUTOR,
        new Attachment(sourceUri, contentType, saveDate, fileName));
  }

  private void showInChat() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null || mediaItem.msgId == DcMsg.DC_MSG_NO_ID) {
      Log.w(TAG, "mediaItem missing.");
      return;
    }

    DcMsg dcMsg = dcContext.getMsg(mediaItem.msgId);
    if (dcMsg.getId() == DcMsg.DC_MSG_NO_ID) {
      Log.w(TAG, "cannot get message object.");
      return;
    }

    Intent intent = new Intent(this, ConversationActivity.class);
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, dcMsg.getChatId());
    intent.putExtra(
        ConversationActivity.STARTING_POSITION_EXTRA, DcMsg.getMessagePosition(dcMsg, dcContext));
    startActivity(intent);
  }

  @SuppressLint("StaticFieldLeak")
  private void deleteMedia() {
    MediaItem mediaItem = getCurrentMediaItem();
    if (mediaItem == null || mediaItem.msgId == DcMsg.DC_MSG_NO_ID) {
      return;
    }

    DcMsg dcMsg = dcContext.getMsg(mediaItem.msgId);
    DcChat dcChat = dcContext.getChat(dcMsg.getChatId());

    String text = getResources().getQuantityString(R.plurals.ask_delete_messages, 1, 1);
    int positiveBtnLabel = dcChat.isSelfTalk() ? R.string.delete : R.string.delete_for_me;
    final int[] messageIds = new int[] {mediaItem.msgId};

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setMessage(text);
    builder.setCancelable(true);
    builder.setNeutralButton(android.R.string.cancel, null);
    builder.setPositiveButton(
        positiveBtnLabel,
        (dialogInterface, which) -> {
          Util.runOnAnyBackgroundThread(() -> dcContext.deleteMsgs(messageIds));
          finish();
        });

    if (dcChat.isEncrypted() && dcChat.canSend() && !dcChat.isSelfTalk() && dcMsg.isOutgoing()) {
      builder.setNegativeButton(
          R.string.delete_for_everyone,
          (d, which) -> {
            Util.runOnAnyBackgroundThread(() -> dcContext.sendDeleteRequest(messageIds));
            finish();
          });
      AlertDialog dialog = builder.show();
      Util.redButton(dialog, AlertDialog.BUTTON_NEGATIVE);
      Util.redPositiveButton(dialog);
    } else {
      AlertDialog dialog = builder.show();
      Util.redPositiveButton(dialog);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);

    menu.clear();
    MenuInflater inflater = this.getMenuInflater();
    inflater.inflate(R.menu.media_preview, menu);
    Util.redMenuItem(menu, R.id.delete);

    if (!isMediaInDb()) {
      menu.findItem(R.id.media_preview__overview).setVisible(false);
      menu.findItem(R.id.media_preview__share).setVisible(false);
      menu.findItem(R.id.delete).setVisible(false);
      menu.findItem(R.id.show_in_chat).setVisible(false);
    }

    if (editAvatarChatId == 0) {
      menu.findItem(R.id.media_preview__edit).setVisible(false);
    }

    // BMChat 2.49.87: Mute item only makes sense for video. For static images we just hide it.
    MenuItem muteItem = menu.findItem(R.id.bmchat_media_preview__mute);
    if (muteItem != null) {
      MediaItem current = getCurrentMediaItem();
      boolean isVideo = current != null && current.type != null && current.type.startsWith("video/");
      muteItem.setVisible(isVideo);
      if (isVideo) {
        MediaItemAdapter adapter = (MediaItemAdapter) mediaPager.getAdapter();
        MediaView mv = adapter == null ? null : adapter.getMediaViewFor(mediaPager.getCurrentItem());
        boolean muted = mv != null && mv.isVideoMuted();
        muteItem.setTitle(muted ? R.string.bmchat_unmute_audio : R.string.bmchat_mute_audio);
      }
    }

    return true;
  }

  /**
   * BMChat 2.49.87: flip mute state on the current page's MediaView and refresh the menu so the
   * item title updates to match. If we can't find a video — silently no-op (the item is hidden
   * for non-video pages anyway).
   */
  private void toggleVideoMute(@NonNull MenuItem item) {
    MediaItemAdapter adapter = (MediaItemAdapter) mediaPager.getAdapter();
    if (adapter == null) return;
    MediaView mv = adapter.getMediaViewFor(mediaPager.getCurrentItem());
    if (mv == null) return;
    boolean nextMuted = !mv.isVideoMuted();
    mv.setMuted(nextMuted);
    item.setTitle(nextMuted ? R.string.bmchat_unmute_audio : R.string.bmchat_mute_audio);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    int itemId = item.getItemId();
    if (itemId == R.id.media_preview__edit) {
      editAvatar();
      return true;
    } else if (itemId == R.id.media_preview__overview) {
      showOverview();
      return true;
    } else if (itemId == R.id.media_preview__share) {
      share();
      return true;
    } else if (itemId == R.id.bmchat_media_preview__mute) {
      toggleVideoMute(item);
      return true;
    } else if (itemId == R.id.save) {
      saveToDisk();
      return true;
    } else if (itemId == R.id.delete) {
      deleteMedia();
      return true;
    } else if (itemId == R.id.show_in_chat) {
      showInChat();
      return true;
    } else if (itemId == android.R.id.home) {
      finish();
      return true;
    }

    return false;
  }

  private boolean isMediaInDb() {
    return conversationRecipient != null;
  }

  private @Nullable MediaItem getCurrentMediaItem() {
    MediaItemAdapter adapter = (MediaItemAdapter) mediaPager.getAdapter();

    if (adapter != null) {
      return adapter.getMediaItemFor(mediaPager.getCurrentItem());
    } else {
      return null;
    }
  }

  public static boolean isTypeSupported(final Slide slide) {
    return slide != null && (slide.hasVideo() || slide.hasImage());
  }

  @Override
  public Loader<DcMediaGalleryElement> onCreateLoader(int id, Bundle args) {
    return new PagingMediaLoader(this, messageRecord, false);
  }

  @Override
  public void onLoadFinished(
      Loader<DcMediaGalleryElement> loader, @Nullable DcMediaGalleryElement data) {
    if (data != null) {
      @SuppressWarnings("ConstantConditions")
      DcMediaPagerAdapter adapter =
          new DcMediaPagerAdapter(this, GlideApp.with(this), getWindow(), data, leftIsRecent);
      mediaPager.setAdapter(adapter);
      adapter.setActive(true);

      if (restartItem < 0) mediaPager.setCurrentItem(data.getPosition());
      else mediaPager.setCurrentItem(restartItem);
    }
  }

  @Override
  public void onLoaderReset(Loader<DcMediaGalleryElement> loader) {}

  private class ViewPagerListener extends ExtendedOnPageChangedListener {

    @Override
    public void onPageSelected(int position) {
      super.onPageSelected(position);

      MediaItemAdapter adapter = (MediaItemAdapter) mediaPager.getAdapter();

      if (adapter != null) {
        MediaItem item = adapter.getMediaItemFor(position);
        if (item.recipient != null) item.recipient.addListener(MediaPreviewActivity.this);

        initializeActionBar();
        // BMChat 2.49.88: refresh the action bar menu so the mute toggle hides itself on
        // image pages and reappears when the user swipes to the next video.
        invalidateOptionsMenu();
      }
    }

    @Override
    public void onPageUnselected(int position) {
      MediaItemAdapter adapter = (MediaItemAdapter) mediaPager.getAdapter();

      if (adapter != null) {
        try {
          MediaItem item = adapter.getMediaItemFor(position);
          if (item.recipient != null) item.recipient.removeListener(MediaPreviewActivity.this);
        } catch (IllegalArgumentException e) {
          Log.w(TAG, "Ignoring invalid position index");
        }
        adapter.pause(position);
      }
    }
  }

  private static class SingleItemPagerAdapter extends PagerAdapter implements MediaItemAdapter {

    private final GlideRequests glideRequests;
    private final Window window;
    private final Uri uri;
    private final String name;
    private final String mediaType;
    private final long size;

    private final LayoutInflater inflater;

    SingleItemPagerAdapter(
        @NonNull Context context,
        @NonNull GlideRequests glideRequests,
        @NonNull Window window,
        @NonNull Uri uri,
        @Nullable String name,
        @NonNull String mediaType,
        long size) {
      this.glideRequests = glideRequests;
      this.window = window;
      this.uri = uri;
      this.name = name;
      this.mediaType = mediaType;
      this.size = size;
      this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
      return 1;
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
      return view == object;
    }

    @Override
    public @NonNull Object instantiateItem(@NonNull ViewGroup container, int position) {
      View itemView = inflater.inflate(R.layout.media_view_page, container, false);
      MediaView mediaView = itemView.findViewById(R.id.media_view);

      try {
        mediaView.set(glideRequests, window, uri, name, mediaType, size, true);
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      container.addView(itemView);

      return itemView;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
      MediaView mediaView = ((FrameLayout) object).findViewById(R.id.media_view);
      mediaView.cleanup();

      container.removeView((FrameLayout) object);
    }

    @Override
    public MediaItem getMediaItemFor(int position) {
      return new MediaItem(null, uri, name, mediaType, DcMsg.DC_MSG_NO_ID, -1, -1, true);
    }

    @Override
    public void pause(int position) {}

    @Nullable
    @Override
    public MediaView getMediaViewFor(int position) {
      return null;
    }
  }

  private static class DcMediaPagerAdapter extends PagerAdapter implements MediaItemAdapter {

    private final WeakHashMap<Integer, MediaView> mediaViews = new WeakHashMap<>();

    private final Context context;
    private final GlideRequests glideRequests;
    private final Window window;
    private final DcMediaGalleryElement gallery;
    private final boolean leftIsRecent;

    private boolean active;
    private int autoPlayPosition;

    DcMediaPagerAdapter(
        @NonNull Context context,
        @NonNull GlideRequests glideRequests,
        @NonNull Window window,
        @NonNull DcMediaGalleryElement gallery,
        boolean leftIsRecent) {
      this.context = context.getApplicationContext();
      this.glideRequests = glideRequests;
      this.window = window;
      this.gallery = gallery;
      this.leftIsRecent = leftIsRecent;
      this.autoPlayPosition = gallery.getPosition();
    }

    public void setActive(boolean active) {
      this.active = active;
      notifyDataSetChanged();
    }

    @Override
    public int getCount() {
      if (!active) return 0;
      else return gallery.getCount();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
      return view == object;
    }

    @Override
    public @NonNull Object instantiateItem(@NonNull ViewGroup container, int position) {
      View itemView =
          LayoutInflater.from(context).inflate(R.layout.media_view_page, container, false);
      MediaView mediaView = itemView.findViewById(R.id.media_view);
      boolean autoplay = position == autoPlayPosition;
      int cursorPosition = getCursorPosition(position);

      autoPlayPosition = -1;

      gallery.moveToPosition(cursorPosition);

      DcMsg msg = gallery.getMessage();

      try {
        //noinspection ConstantConditions
        mediaView.set(
            glideRequests,
            window,
            Uri.fromFile(msg.getFileAsFile()),
            msg.getFilename(),
            msg.getFilemime(),
            msg.getFilebytes(),
            autoplay);
      } catch (IOException e) {
        Log.w(TAG, e);
      }

      mediaViews.put(position, mediaView);
      container.addView(itemView);

      return itemView;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
      MediaView mediaView = ((FrameLayout) object).findViewById(R.id.media_view);
      mediaView.cleanup();

      mediaViews.remove(position);
      container.removeView((FrameLayout) object);
    }

    public MediaItem getMediaItemFor(int position) {
      gallery.moveToPosition(getCursorPosition(position));
      DcMsg msg = gallery.getMessage();

      if (msg.getFile() == null) throw new AssertionError();

      return new MediaItem(
          Recipient.fromChat(context, msg.getId()),
          Uri.fromFile(msg.getFileAsFile()),
          msg.getFilename(),
          msg.getFilemime(),
          msg.getId(),
          msg.getDateReceived(),
          msg.getFilebytes(),
          msg.isOutgoing());
    }

    @Override
    public void pause(int position) {
      MediaView mediaView = mediaViews.get(position);
      if (mediaView != null) mediaView.pause();
    }

    @Nullable
    @Override
    public MediaView getMediaViewFor(int position) {
      return mediaViews.get(position);
    }

    private int getCursorPosition(int position) {
      if (leftIsRecent) return position;
      else return gallery.getCount() - 1 - position;
    }
  }

  private static class MediaItem {
    private final @Nullable Recipient recipient;
    private final @NonNull Uri uri;
    private final @Nullable String name;
    private final @NonNull String type;
    private final int msgId;
    private final long date;
    private final long size;
    private final boolean outgoing;

    private MediaItem(
        @Nullable Recipient recipient,
        @NonNull Uri uri,
        @Nullable String name,
        @NonNull String type,
        int msgId,
        long date,
        long size,
        boolean outgoing) {
      this.recipient = recipient;
      this.uri = uri;
      this.name = name;
      this.type = type;
      this.msgId = msgId;
      this.date = date;
      this.size = size;
      this.outgoing = outgoing;
    }
  }

  interface MediaItemAdapter {
    MediaItem getMediaItemFor(int position);

    void pause(int position);

    /**
     * BMChat 2.49.87: lookup helper so the activity can route the mute toggle to whichever
     * MediaView is currently on screen. May return {@code null} (e.g. for the
     * SingleItemPagerAdapter or before the view is instantiated) — callers must null-check.
     */
    @Nullable
    MediaView getMediaViewFor(int position);
  }
}
