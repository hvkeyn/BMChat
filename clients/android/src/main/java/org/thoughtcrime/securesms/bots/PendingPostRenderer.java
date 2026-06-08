package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only helper that turns a {@link PendingPostStore.Entry} into the
 * exact strings + bitmap the user will see once the post is mirrored
 * into a chat. It is the data backend of the "Превью поста" screen
 * ({@link org.thoughtcrime.securesms.bots.ui.PendingPostPreviewActivity}).
 *
 * <p>The renderer intentionally duplicates only the smallest possible
 * subset of {@link TelegramMessageDispatcher} — caption + forward
 * attribution + photo composite — so the preview screen does not need
 * a live {@link com.b44t.messenger.DcContext} or any IMAP/SMTP setup.
 *
 * <p>Splitting it out also makes the publish path (which still owns
 * authentic side-effects) easy to keep package-private.
 *
 * <ul>
 *   <li>{@link #renderBody(JSONObject)} returns the Markdown body —
 *       the same string the dispatcher passes to {@code dc_send_msg}
 *       once the post is approved. The preview screen feeds it to
 *       {@link org.thoughtcrime.securesms.util.MessageMarkdown} just
 *       like {@link org.thoughtcrime.securesms.ConversationItem} does
 *       so the on-screen text matches the recipient's view 1:1.</li>
 *   <li>{@link #composePreviewImage(Context, TelegramApi, BotConfig,
 *       PendingPostStore.Entry)} downloads + composites the album's
 *       photos into a single JPEG. Returns {@code null} when the post
 *       has no photo media or downloads fail; the UI then falls back
 *       to a text-only preview.</li>
 * </ul>
 *
 * <p>All methods are pure given the inputs and free of UI / DB writes.
 */
public final class PendingPostRenderer {

  private static final String TAG = "PendingPostRenderer";

  private PendingPostRenderer() {}

  // ---------------------------------------------------------------
  //  text rendering
  // ---------------------------------------------------------------

  /**
   * Build the Markdown body for one Telegram update. Mirrors
   * {@link TelegramMessageDispatcher}'s buildBodyText: forward
   * attribution on top, then the rendered text/caption with
   * {@link TelegramFormatter}, then the inline keyboard. The result
   * is the same string the dispatcher would feed into {@code dc_send_msg}.
   */
  @NonNull
  public static String renderBody(@NonNull JSONObject update) {
    JSONObject m = TelegramMessageDispatcher.firstMessageLike(update);
    if (m == null) return "";
    String forward = TelegramMessageDispatcher.describeForwardAttribution(m);

    String text = m.optString("text", "");
    JSONArray entities = m.optJSONArray("entities");
    String caption = m.optString("caption", "");
    JSONArray captionEntities = m.optJSONArray("caption_entities");

    String body;
    if (!TextUtils.isEmpty(text)) {
      body = TelegramFormatter.render(text, entities);
    } else if (!TextUtils.isEmpty(caption)) {
      body = TelegramFormatter.render(caption, captionEntities);
    } else {
      body = "";
    }
    String forwardLine = TelegramMessageDispatcher.formatForwardAttribution(forward);
    if (forwardLine != null) {
      body = body.isEmpty() ? forwardLine : forwardLine + "\n\n" + body;
    }
    String keyboard = TelegramFormatter.renderInlineKeyboard(m.optJSONObject("reply_markup"));
    if (!keyboard.isEmpty()) {
      body = body.isEmpty() ? keyboard : body + "\n" + keyboard;
    }
    return body;
  }

  /**
   * Short, human-readable description of the media in this post — used
   * as the second line of the preview header (e.g. "альбом · 5", "фото",
   * "видео · 0:42"). Returns an empty string when there is no media.
   */
  @NonNull
  public static String describeMedia(@NonNull PendingPostStore.Entry e) {
    int n = e.albumSize();
    if (n > 1) return "album · " + n;
    if (e.mediaKind != null && !e.mediaKind.isEmpty()) return e.mediaKind;
    return "";
  }

  // ---------------------------------------------------------------
  //  image composite (photo / album)
  // ---------------------------------------------------------------

  /**
   * Build a single JPEG bitmap representing the post's photo content.
   *
   * <p>For a one-photo entry this returns the largest photo as-is.
   * For a 2..10-photo Telegram album the photos are downloaded and
   * tiled with {@link AlbumComposer} so the preview matches what the
   * "publish" path mirrors into the chat.
   *
   * <p>Returns {@code null} when the post has no photo media (text,
   * video, document, sticker, …) or when every download fails — the
   * UI then renders a text-only preview.
   */
  @WorkerThread
  @Nullable
  public static File composePreviewImage(@NonNull Context context,
                                         @NonNull TelegramApi api,
                                         @NonNull BotConfig bot,
                                         @NonNull PendingPostStore.Entry e) {
    List<JSONObject> parts = entryParts(e);
    if (parts.isEmpty()) return null;

    File cacheDir = new File(context.getCacheDir(), "bots/" + bot.id + "/preview");
    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
      Log.w(TAG, "cannot create preview cache dir: " + cacheDir);
      return null;
    }

    List<File> photos = new ArrayList<>();
    for (JSONObject upd : parts) {
      JSONObject inner = TelegramMessageDispatcher.firstMessageLike(upd);
      if (inner == null) continue;
      String fileId = pickLargestPhotoFileId(inner);
      if (fileId == null) continue;
      File downloaded = downloadPhoto(api, fileId, cacheDir);
      if (downloaded != null) photos.add(downloaded);
    }
    if (photos.isEmpty()) return null;
    if (photos.size() == 1) return photos.get(0);

    File composite = new File(cacheDir, "preview_" + e.entryId.hashCode() + ".jpg");
    File built = AlbumComposer.compose(photos, composite);
    return built != null ? built : photos.get(0);
  }

  // ---------------------------------------------------------------
  //  helpers (mirror TelegramMessageDispatcher private utilities)
  // ---------------------------------------------------------------

  /**
   * Order-preserving split of a queue entry into individual Telegram
   * Update objects. A non-album entry yields a 1-element list with
   * {@link PendingPostStore.Entry#raw}; an album yields every part.
   */
  @NonNull
  public static List<JSONObject> entryParts(@NonNull PendingPostStore.Entry e) {
    List<JSONObject> out = new ArrayList<>();
    if (e.albumParts != null && e.albumParts.length() > 0) {
      for (int i = 0; i < e.albumParts.length(); i++) {
        JSONObject p = e.albumParts.optJSONObject(i);
        if (p != null) out.add(p);
      }
      if (!out.isEmpty()) return out;
    }
    out.add(e.raw);
    return out;
  }

  @Nullable
  private static String pickLargestPhotoFileId(@NonNull JSONObject inner) {
    JSONArray photo = inner.optJSONArray("photo");
    if (photo == null || photo.length() == 0) return null;
    JSONObject biggest = null;
    int biggestArea = 0;
    for (int i = 0; i < photo.length(); i++) {
      JSONObject p = photo.optJSONObject(i);
      if (p == null) continue;
      int w = p.optInt("width", 0);
      int h = p.optInt("height", 0);
      int area = w * h;
      if (area >= biggestArea) {
        biggestArea = area;
        biggest = p;
      }
    }
    if (biggest == null) return null;
    String id = biggest.optString("file_id", null);
    return TextUtils.isEmpty(id) ? null : id;
  }

  @WorkerThread
  @Nullable
  private static File downloadPhoto(@NonNull TelegramApi api,
                                    @NonNull String fileId,
                                    @NonNull File dir) {
    try {
      String filePath = api.getFilePath(fileId);
      if (filePath == null) return null;
      String safeName = "photo_" + Integer.toHexString(fileId.hashCode()) + ".jpg";
      File dest = new File(dir, safeName);
      if (dest.exists() && dest.length() > 0) return dest;
      return api.downloadFile(filePath, fileId, dest);
    } catch (Throwable t) {
      Log.w(TAG, "downloadPhoto failed for fileId=" + fileId, t);
      return null;
    }
  }

}
