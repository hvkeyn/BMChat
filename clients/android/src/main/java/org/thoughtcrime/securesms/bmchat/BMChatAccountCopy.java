package org.thoughtcrime.securesms.bmchat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * Writes a Delta Chat backup into the public Download/BMChat folder. That folder is not removed
 * when the app is uninstalled, and the user can delete it in the file manager.
 */
public final class BMChatAccountCopy {
  private static final String TAG = "BMChatAccountCopy";
  public static final String DISPLAY_PATH = "Download/BMChat";
  private static final String RELATIVE_PATH = "Download/BMChat/";
  private static final String PREF = "bmchat_account_copy";
  private static final String KEY_LAST_MS = "last_ms";
  private static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  private BMChatAccountCopy() {}

  public static boolean isRunning() {
    return RUNNING.get();
  }

  /** Empty directory the core can write a backup into. Not visible after uninstall. */
  @NonNull
  public static File prepareExportDir(@NonNull Context context) {
    File dir = new File(context.getCacheDir(), "bmchat-account-copy");
    File[] old = dir.listFiles();
    if (old != null) {
      for (File file : old) {
        file.delete();
      }
    }
    dir.mkdirs();
    return dir;
  }

  /**
   * Copies finished {@code delta-chat-backup-*.tar} files from the export directory into
   * Download/BMChat and removes the temporary files.
   */
  public static int publishFinishedExports(@NonNull Context context) throws IOException {
    File dir = new File(context.getCacheDir(), "bmchat-account-copy");
    File[] files = dir.listFiles();
    int published = 0;
    if (files != null) {
      for (File file : files) {
        String name = file.getName();
        if (name.startsWith("delta-chat-backup-") && name.endsWith(".tar")) {
          publishOne(context, file);
          published++;
        }
        file.delete();
      }
    }
    if (published > 0) {
      context
          .getApplicationContext()
          .getSharedPreferences(PREF, Context.MODE_PRIVATE)
          .edit()
          .putLong(KEY_LAST_MS, System.currentTimeMillis())
          .apply();
    }
    return published;
  }

  /** Starts an export. Returns false when another copy is already running. */
  public static boolean beginExport(@NonNull Context context, @NonNull DcContext[] accounts) {
    if (accounts.length == 0 || !RUNNING.compareAndSet(false, true)) {
      return false;
    }
    Context app = context.getApplicationContext();
    File dir = prepareExportDir(app);
    AtomicInteger index = new AtomicInteger(0);
    DcEventCenter center = DcHelper.getEventCenter(app);
    DcEventCenter.DcEventDelegate watcher =
        new DcEventCenter.DcEventDelegate() {
          @Override
          public boolean runOnMain() {
            return false;
          }

          @Override
          public void handleEvent(@NonNull DcEvent event) {
            if (event.getId() != DcContext.DC_EVENT_IMEX_PROGRESS) {
              return;
            }
            int progress = (int) event.getData1Int();
            if (progress != 0 && progress != 1000) {
              return;
            }
            int next = index.incrementAndGet();
            if (next < accounts.length) {
              accounts[next].imex(DcContext.DC_IMEX_EXPORT_BACKUP, dir.getAbsolutePath());
              return;
            }
            center.removeObservers(this);
            try {
              int count = publishFinishedExports(app);
              if (count > 0) {
                Log.i(TAG, "Account copy saved to " + DISPLAY_PATH);
              }
            } catch (IOException e) {
              Log.w(TAG, "Could not publish account copy", e);
            } finally {
              RUNNING.set(false);
            }
          }
        };
    center.addObserver(DcContext.DC_EVENT_IMEX_PROGRESS, watcher);
    accounts[0].imex(DcContext.DC_IMEX_EXPORT_BACKUP, dir.getAbsolutePath());
    return true;
  }

  /** Saves every configured account at most once a day. Failures stay in the log. */
  public static void maybeStart(@NonNull Context context) {
    Context app = context.getApplicationContext();
    long last =
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_LAST_MS, 0L);
    if (System.currentTimeMillis() - last < INTERVAL_MS) {
      return;
    }
    DcAccounts accounts = DcHelper.getAccounts(app);
    ArrayList<DcContext> configured = new ArrayList<>();
    for (int id : accounts.getAll()) {
      DcContext account = accounts.getAccount(id);
      if (account != null && account.isConfigured() == 1) {
        configured.add(account);
      }
    }
    if (configured.isEmpty()) {
      return;
    }
    beginExport(app, configured.toArray(new DcContext[0]));
  }

  private static void publishOne(@NonNull Context context, @NonNull File tar) throws IOException {
    if (Build.VERSION.SDK_INT >= 29) {
      publishMediaStore(context, tar);
    } else {
      publishLegacy(tar);
    }
  }

  private static void publishMediaStore(@NonNull Context context, @NonNull File tar)
      throws IOException {
    ContentResolver resolver = context.getContentResolver();
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, tar.getName());
    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-tar");
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH);
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri == null) {
      throw new IOException("Could not create " + DISPLAY_PATH + "/" + tar.getName());
    }
    try (InputStream in = new FileInputStream(tar);
        OutputStream out = resolver.openOutputStream(uri)) {
      if (out == null) {
        throw new IOException("Could not open " + DISPLAY_PATH + "/" + tar.getName());
      }
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
    } catch (IOException e) {
      resolver.delete(uri, null, null);
      throw e;
    }
    values.clear();
    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
    resolver.update(uri, values, null, null);
    prune(resolver);
  }

  private static void publishLegacy(@NonNull File tar) throws IOException {
    File dir =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BMChat");
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IOException("Could not create " + dir.getAbsolutePath());
    }
    File dest = new File(dir, tar.getName());
    try (InputStream in = new FileInputStream(tar);
        OutputStream out = new FileOutputStream(dest)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) > 0) {
        out.write(buffer, 0, read);
      }
    }
  }

  private static void prune(@NonNull ContentResolver resolver) {
    String[] projection = {MediaStore.MediaColumns._ID};
    String selection =
        MediaStore.MediaColumns.RELATIVE_PATH
            + " LIKE ? AND "
            + MediaStore.MediaColumns.DISPLAY_NAME
            + " LIKE ?";
    String[] args = {RELATIVE_PATH + "%", "delta-chat-backup-%.tar"};
    ArrayList<Long> ids = new ArrayList<>();
    try (android.database.Cursor cursor =
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
      if (cursor == null) {
        return;
      }
      while (cursor.moveToNext()) {
        ids.add(cursor.getLong(0));
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not list saved copies", e);
      return;
    }
    for (int i = 3; i < ids.size(); i++) {
      resolver.delete(
          MediaStore.Downloads.EXTERNAL_CONTENT_URI,
          MediaStore.MediaColumns._ID + "=?",
          new String[] {Long.toString(ids.get(i))});
    }
  }

}
