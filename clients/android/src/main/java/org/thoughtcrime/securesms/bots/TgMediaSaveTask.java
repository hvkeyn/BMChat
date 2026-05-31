package org.thoughtcrime.securesms.bots;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.Pair;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.task.ProgressDialogAsyncTask;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * BMChat 2.49.90: stream a Telegram proxy video URL into the user's
 * Movies/BMChat folder via MediaStore. Used when the chat message only
 * has a JPEG poster on disk but the real bytes live behind the signed
 * {@code /tgmedia/…} URL embedded in {@link BotMediaMarker}.
 *
 * <p>BMChat 2.49.93: shows a determinate progress bar while streaming
 * and rejects image/jpeg payloads so a poster thumbnail is never saved
 * as a fake {@code .mp4}.
 */
public class TgMediaSaveTask
    extends ProgressDialogAsyncTask<TgMediaSaveTask.Request, Integer, Pair<Integer, Uri>> {

  private static final String TAG = "TgMediaSaveTask";

  static final int SUCCESS = 0;
  private static final int FAILURE = 1;
  private static final int NOT_VIDEO = 2;

  public static final class Request {
    public final @NonNull String url;
    public final @Nullable String mime;
    public final @Nullable String fileName;
    public final long expectedSizeBytes;

    public Request(@NonNull String url, @Nullable String mime, @Nullable String fileName) {
      this(url, mime, fileName, 0L);
    }

    public Request(
        @NonNull String url,
        @Nullable String mime,
        @Nullable String fileName,
        long expectedSizeBytes) {
      this.url = url;
      this.mime = mime;
      this.fileName = fileName;
      this.expectedSizeBytes = expectedSizeBytes;
    }
  }

  public TgMediaSaveTask(@NonNull Context context) {
    this(context, 0L);
  }

  public TgMediaSaveTask(@NonNull Context context, long expectedSizeBytes) {
    super(
        context,
        context.getString(R.string.bmchat_save_started, "video"),
        context.getString(R.string.bmchat_save_progress, 0));
    enableCancelOnBack();
  }

  @Override
  protected void onProgressUpdate(Integer... values) {
    if (values == null || values.length == 0) return;
    Context ctx = getContext();
    if (ctx == null) return;
    int pct = Math.max(0, Math.min(100, values[0]));
    updateDialogProgress(pct, 100, ctx.getString(R.string.bmchat_save_progress, pct));
  }

  @Override
  protected Pair<Integer, Uri> doInBackground(Request... requests) {
    if (requests == null || requests.length == 0 || requests[0] == null) {
      return new Pair<>(FAILURE, null);
    }
    Request req = requests[0];
    Context ctx = getContext();
    if (ctx == null) return new Pair<>(FAILURE, null);
    publishProgress(0);
    try {
      Uri uri = streamToMovies(ctx, req);
      return uri == null ? new Pair<>(FAILURE, null) : new Pair<>(SUCCESS, uri);
    } catch (NotVideoException e) {
      Log.w(TAG, "save rejected non-video payload url=" + req.url);
      return new Pair<>(NOT_VIDEO, null);
    } catch (Exception e) {
      Log.w(TAG, "save failed url=" + req.url, e);
      return new Pair<>(FAILURE, null);
    }
  }

  private static final class NotVideoException extends Exception {}

  private @Nullable Uri streamToMovies(@NonNull Context ctx, @NonNull Request req)
      throws Exception {
    String mime = req.mime == null || req.mime.isEmpty() ? "video/mp4" : req.mime;
    if (!mime.startsWith("video/")) mime = "video/mp4";
    String name = req.fileName;
    if (name == null || name.isEmpty()) {
      name = "bmchat-tg-" + System.currentTimeMillis() + ".mp4";
    } else if (!name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
      name = name + ".mp4";
    }

    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    values.put(
        MediaStore.MediaColumns.DATE_ADDED,
        TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()));
    if (Build.VERSION.SDK_INT > 28) {
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          Environment.DIRECTORY_MOVIES + "/BMChat/");
    }

    Uri collection = StorageUtil.getVideoUri();
    Uri outUri = ctx.getContentResolver().insert(collection, values);
    if (outUri == null) return null;

    HttpURLConnection conn = (HttpURLConnection) new URL(req.url).openConnection();
    conn.setConnectTimeout(30_000);
    conn.setReadTimeout(300_000);
    conn.setRequestProperty("Accept", "*/*");
    conn.setInstanceFollowRedirects(true);
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      Log.w(TAG, "HTTP " + code + " for " + req.url);
      ctx.getContentResolver().delete(outUri, null, null);
      conn.disconnect();
      return null;
    }

    String responseMime = conn.getContentType();
    if (responseMime != null && responseMime.startsWith("image/")) {
      ctx.getContentResolver().delete(outUri, null, null);
      conn.disconnect();
      throw new NotVideoException();
    }

    long total = conn.getContentLengthLong();
    if (total <= 0 && req.expectedSizeBytes > 0) total = req.expectedSizeBytes;

    try (InputStream in = conn.getInputStream();
        OutputStream out = ctx.getContentResolver().openOutputStream(outUri, "w")) {
      if (out == null) return null;
      byte[] buf = new byte[8192];
      int n;
      long written = 0;
      boolean headerChecked = false;
      while ((n = in.read(buf)) >= 0) {
        if (isCancelled()) {
          ctx.getContentResolver().delete(outUri, null, null);
          return null;
        }
        if (n > 0) {
          if (!headerChecked) {
            if (looksLikeImage(buf, n)) {
              ctx.getContentResolver().delete(outUri, null, null);
              throw new NotVideoException();
            }
            headerChecked = true;
          }
          out.write(buf, 0, n);
          written += n;
          if (total > 0) {
            publishProgress((int) Math.min(100, (written * 100) / total));
          }
        }
      }
      if (written <= 0 || !headerChecked) {
        ctx.getContentResolver().delete(outUri, null, null);
        return null;
      }
      if (total > 0 && written < total / 4) {
        // Suspiciously small for a declared large video — likely a thumbnail blob.
        Log.w(TAG, "save too small written=" + written + " expected~=" + total);
        ctx.getContentResolver().delete(outUri, null, null);
        throw new NotVideoException();
      }
      ContentValues done = new ContentValues();
      done.put(MediaStore.MediaColumns.SIZE, written);
      if (Build.VERSION.SDK_INT > 28) {
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      }
      ctx.getContentResolver().update(outUri, done, null, null);
    } finally {
      conn.disconnect();
    }
    publishProgress(100);
    return outUri;
  }

  /** JPEG / PNG / WEBP magic — must never land in Movies as a fake mp4. */
  private static boolean looksLikeImage(@NonNull byte[] buf, int len) {
    if (len >= 3 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8 && (buf[2] & 0xFF) == 0xFF) {
      return true;
    }
    if (len >= 8
        && buf[0] == 'P'
        && buf[1] == 'N'
        && buf[2] == 'G'
        && buf[3] == 13
        && buf[4] == 10
        && buf[5] == 26
        && buf[6] == 10) {
      return true;
    }
    if (len >= 12
        && buf[0] == 'R'
        && buf[1] == 'I'
        && buf[2] == 'F'
        && buf[3] == 'F'
        && buf[8] == 'W'
        && buf[9] == 'E'
        && buf[10] == 'B'
        && buf[11] == 'P') {
      return true;
    }
    return false;
  }

  @Override
  protected void onPostExecute(Pair<Integer, Uri> result) {
    if (isCancelled()) return;
    super.onPostExecute(result);
    Context ctx = getContext();
    if (ctx == null) return;
    if (result.first() == SUCCESS) {
      Toast.makeText(
              ctx,
              ctx.getString(R.string.bmchat_save_succeeded, "Movies/BMChat"),
              Toast.LENGTH_LONG)
          .show();
    } else if (result.first() == NOT_VIDEO) {
      Toast.makeText(ctx, R.string.bmchat_save_not_video, Toast.LENGTH_LONG).show();
    } else {
      Toast.makeText(ctx, R.string.error, Toast.LENGTH_LONG).show();
    }
  }
}
