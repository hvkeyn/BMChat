package org.thoughtcrime.securesms.bots;

import android.content.ContentResolver;
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
 */
public class TgMediaSaveTask
    extends ProgressDialogAsyncTask<TgMediaSaveTask.Request, Void, Pair<Integer, Uri>> {

  private static final String TAG = "TgMediaSaveTask";

  static final int SUCCESS = 0;
  private static final int FAILURE = 1;

  public static final class Request {
    public final @NonNull String url;
    public final @Nullable String mime;
    public final @Nullable String fileName;

    public Request(@NonNull String url, @Nullable String mime, @Nullable String fileName) {
      this.url = url;
      this.mime = mime;
      this.fileName = fileName;
    }
  }

  public TgMediaSaveTask(@NonNull Context context) {
    super(
        context,
        context.getString(R.string.bmchat_save_started, "video"),
        context.getString(R.string.one_moment));
  }

  @Override
  protected Pair<Integer, Uri> doInBackground(Request... requests) {
    if (requests == null || requests.length == 0 || requests[0] == null) {
      return new Pair<>(FAILURE, null);
    }
    Request req = requests[0];
    Context ctx = getContext();
    if (ctx == null) return new Pair<>(FAILURE, null);
    try {
      Uri uri = streamToMovies(ctx, req);
      return uri == null ? new Pair<>(FAILURE, null) : new Pair<>(SUCCESS, uri);
    } catch (Exception e) {
      Log.w(TAG, "save failed url=" + req.url, e);
      return new Pair<>(FAILURE, null);
    }
  }

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
    conn.setReadTimeout(120_000);
    conn.setRequestProperty("Accept", "*/*");
    int code = conn.getResponseCode();
    if (code < 200 || code >= 300) {
      Log.w(TAG, "HTTP " + code + " for " + req.url);
      ctx.getContentResolver().delete(outUri, null, null);
      return null;
    }

    try (InputStream in = conn.getInputStream();
        OutputStream out = ctx.getContentResolver().openOutputStream(outUri, "w")) {
      if (out == null) return null;
      byte[] buf = new byte[8192];
      int n;
      long total = 0;
      while ((n = in.read(buf)) >= 0) {
        if (n > 0) {
          out.write(buf, 0, n);
          total += n;
        }
      }
      if (total <= 0) {
        ctx.getContentResolver().delete(outUri, null, null);
        return null;
      }
      ContentValues done = new ContentValues();
      done.put(MediaStore.MediaColumns.SIZE, total);
      if (Build.VERSION.SDK_INT > 28) {
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
      }
      ctx.getContentResolver().update(outUri, done, null, null);
    } finally {
      conn.disconnect();
    }
    return outUri;
  }

  @Override
  protected void onPostExecute(Pair<Integer, Uri> result) {
    super.onPostExecute(result);
    Context ctx = getContext();
    if (ctx == null) return;
    if (result.first() == SUCCESS) {
      Toast.makeText(
              ctx,
              ctx.getString(R.string.bmchat_save_succeeded, "Movies/BMChat"),
              Toast.LENGTH_LONG)
          .show();
    } else {
      Toast.makeText(ctx, R.string.error, Toast.LENGTH_LONG).show();
    }
  }
}
