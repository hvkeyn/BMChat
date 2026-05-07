package org.thoughtcrime.securesms.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.guava.Optional;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight self-update checker for BMChat.
 *
 * <p>Polls {@code http://5.187.4.132/update.json}, compares the published
 * {@code versionCode} with {@link BuildConfig#VERSION_CODE}, and if newer
 * downloads the APK to the app cache, verifies its SHA-256, then asks the
 * user to install it via {@link Intent#ACTION_VIEW} with
 * {@code application/vnd.android.package-archive}.
 *
 * <p>Runs at most once every {@link #MIN_CHECK_INTERVAL_MS}. Network access
 * is performed on a background thread; UI prompts on the main thread.
 *
 * <p>The verification chain is:
 * <ol>
 *   <li>SHA-256 of the downloaded file must match the manifest;
 *   <li>Android's package installer rejects an APK whose signing certificate
 *       differs from the currently installed one.
 * </ol>
 */
public final class BMChatUpdater {

    private static final String TAG = "BMChatUpdater";

    private static final String UPDATE_MANIFEST_URL = "http://5.187.4.132/update.json";

    private static final long MIN_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long FOREGROUND_DELAY_MS  = 8L * 1000L;

    private static final String PREFS_NAME              = "bmchat-updater";
    private static final String PREF_LAST_CHECK_AT      = "last-check-at";
    private static final String PREF_DECLINED_VERSION   = "declined-version-code";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bmchat-updater");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private BMChatUpdater() {}

    /** Hooks the updater into a foreground {@code Activity}. Safe to call from
     *  any {@code Activity#onStart} — the actual network/UI work is deferred
     *  by {@link #FOREGROUND_DELAY_MS} so the main launch path is not slowed
     *  down. */
    @MainThread
    public static void scheduleForActivity(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        MAIN.postDelayed(() -> checkInBackground(activity), FOREGROUND_DELAY_MS);
    }

    @AnyThread
    private static void checkInBackground(final Activity activity) {
        if (!RUNNING.compareAndSet(false, true)) return;
        EXEC.submit(() -> {
            try {
                runCheck(activity);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "update check failed", t);
            } finally {
                RUNNING.set(false);
            }
        });
    }

    @AnyThread
    private static void runCheck(final Activity activity) throws Exception {
        Context ctx = activity.getApplicationContext();
        long now = System.currentTimeMillis();
        long last = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_LAST_CHECK_AT, 0L);
        if (now - last < MIN_CHECK_INTERVAL_MS) return;

        Manifest manifest = fetchManifest();
        if (manifest == null) return;

        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_CHECK_AT, now).apply();

        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return;

        long declined = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(PREF_DECLINED_VERSION, 0L);
        if (declined == manifest.versionCode) return;

        MAIN.post(() -> promptUser(activity, manifest));
    }

    @MainThread
    private static void promptUser(final Activity activity, final Manifest m) {
        if (activity.isFinishing()) return;
        String body = "Доступно обновление BMChat " + m.versionName + ".\n\n"
                + "Размер: " + Formatter.formatShortFileSize(activity, m.size)
                + (m.notes != null && !m.notes.isEmpty() ? "\n\n" + m.notes : "");
        new AlertDialog.Builder(activity)
                .setTitle("Обновление BMChat")
                .setMessage(body)
                .setCancelable(true)
                .setPositiveButton("Установить", (d, w) -> beginDownload(activity, m))
                .setNegativeButton("Позже", (d, w) -> {})
                .setNeutralButton("Не предлагать эту версию", (d, w) -> {
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(PREF_DECLINED_VERSION, m.versionCode).apply();
                })
                .show();
    }

    @MainThread
    private static void beginDownload(final Activity activity, final Manifest m) {
        EXEC.submit(() -> {
            try {
                File apk = downloadApk(activity.getApplicationContext(), m);
                MAIN.post(() -> launchInstaller(activity, apk));
            } catch (Throwable t) {
                android.util.Log.w(TAG, "download failed", t);
                MAIN.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("Не удалось загрузить обновление")
                        .setMessage(String.valueOf(t.getMessage()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            }
        });
    }

    @MainThread
    private static void launchInstaller(final Activity activity, final File apk) {
        if (activity.isFinishing()) return;
        // Android 8+: explicit per-app permission to install unknown sources.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageManager pm = activity.getPackageManager();
            if (pm != null && !pm.canRequestPackageInstalls()) {
                try {
                    activity.startActivity(new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (ActivityNotFoundException ignore) {}
                return;
            }
        }
        Uri uri = FileProvider.getUriForFile(
                activity,
                BuildConfig.APPLICATION_ID + ".fileprovider",
                apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            android.util.Log.w(TAG, "no installer activity", e);
        }
    }

    // ---------------------------------------------------------------------
    //  Network and crypto helpers.
    // ---------------------------------------------------------------------

    @AnyThread
    private static @Nullable Manifest fetchManifest() {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(UPDATE_MANIFEST_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent",
                    "BMChat/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            byte[] body = readAll(conn.getInputStream(), 64 * 1024);
            return Manifest.parse(body);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8 * 1024];
        int total = 0;
        for (int n; (n = in.read(tmp)) > 0;) {
            total += n;
            if (total > max) throw new IOException("manifest too large");
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static File downloadApk(Context ctx, Manifest m) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        File out = new File(dir, "bmchat-" + m.versionCode + ".apk");
        if (out.exists() && verifyHash(out, m.sha256)) return out;
        File tmp = new File(dir, out.getName() + ".part");
        if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.delete();

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(m.url).openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(60_000);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] tmpBuf = new byte[16 * 1024];
                long total = 0;
                long limit = Math.max(m.size > 0 ? m.size : 0, 200L * 1024L * 1024L);
                for (int n; (n = in.read(tmpBuf)) > 0;) {
                    total += n;
                    if (total > limit) throw new IOException("apk too large");
                    fos.write(tmpBuf, 0, n);
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (!verifyHash(tmp, m.sha256)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("sha-256 mismatch");
        }
        if (!tmp.renameTo(out)) throw new IOException("rename failed");
        return out;
    }

    private static boolean verifyHash(File f, String expectedHex) {
        if (expectedHex == null || expectedHex.isEmpty()) return false;
        try (InputStream in = new java.io.FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] tmp = new byte[64 * 1024];
            for (int n; (n = in.read(tmp)) > 0;) md.update(tmp, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString().equalsIgnoreCase(expectedHex);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Lightweight typed wrapper around the {@code update.json} manifest. */
    static final class Manifest {
        final long versionCode;
        final String versionName;
        final String url;
        final String sha256;
        final long size;
        final @Nullable String notes;

        private Manifest(long versionCode, String versionName, String url,
                         String sha256, long size, @Nullable String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
        }

        static @Nullable Manifest parse(byte[] body) {
            try {
                String text = new String(body, "UTF-8");
                JSONObject o = new JSONObject(text);
                long code = o.optLong("versionCode", 0L);
                String name = o.optString("versionName", "");
                String url = o.optString("url", "");
                String sha = o.optString("sha256", "");
                long size = o.optLong("size", 0L);
                String notes = o.has("notes") ? o.optString("notes", null) : null;
                if (code <= 0 || url.isEmpty() || sha.isEmpty()) return null;
                if (!url.startsWith("http://5.187.4.132/")
                    && !url.startsWith("https://5.187.4.132/")) {
                    return null;
                }
                return new Manifest(code, name, url, sha, size, notes);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // Kept for tests/diagnostics.
    @SuppressWarnings("unused")
    static Optional<Long> getInstalledVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Optional.of(pi.getLongVersionCode());
            }
            //noinspection deprecation
            return Optional.of((long) pi.versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return Optional.absent();
        }
    }
}
