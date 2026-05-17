package org.thoughtcrime.securesms.update;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;

/**
 * Foreground service that downloads a BMChat APK in the background
 * with a live progress notification. Replaces the previous
 * {@code ProgressDialog} flow, which died as soon as the user
 * switched away from the app or rotated the screen.
 *
 * <p>The progress notification is dismissable only by stopping the
 * download (system gesture) — the foreground priority guarantees the
 * process keeps running while bytes are still flowing.
 *
 * <p>Lifecycle:
 * <pre>
 *   START_DOWNLOAD  → showProgress(0%) → fetch → showProgress(50%) → …
 *                                            ↓
 *                                  showReadyToInstall (regular notification with PendingIntent)
 *                                            ↓
 *                                    user taps → launches PackageInstaller
 *   STOP_DOWNLOAD   → cancel HttpURLConnection + remove notification.
 * </pre>
 */
public final class UpdateDownloadService extends Service {

    private static final String TAG = "UpdateDownloadService";

    public  static final String ACTION_START   = "bmchat.update.START_DOWNLOAD";
    public  static final String ACTION_STOP    = "bmchat.update.STOP_DOWNLOAD";
    public  static final String ACTION_INSTALL = "bmchat.update.INSTALL_READY";

    /** LocalBroadcast action that broadcasts progress/state changes so
     *  in-app UI (e.g. the conversation-list banner) can render them. */
    public  static final String ACTION_PROGRESS_BROADCAST =
            "bmchat.update.PROGRESS_BROADCAST";

    /**
     * Snapshot of the most recently broadcast download state. Held as
     * a static so newly-mounted UI (e.g. ConversationListActivity
     * coming back from background) can render the live state without
     * having to wait for the next progress tick.
     */
    public enum State { IDLE, RUNNING, READY, ERROR }
    public static volatile @NonNull State   STATE   = State.IDLE;
    public static volatile int              PROGRESS = 0;
    public static volatile long             DOWNLOADED = 0L;
    public static volatile long             TOTAL = 0L;
    public static volatile @Nullable String VERSION_NAME = null;
    public static volatile @Nullable String READY_APK_PATH = null;
    public static volatile @Nullable String ERROR_MSG = null;

    public  static final String EXTRA_VERSION_CODE = "versionCode";
    public  static final String EXTRA_VERSION_NAME = "versionName";
    public  static final String EXTRA_URL          = "url";
    public  static final String EXTRA_SHA256       = "sha256";
    public  static final String EXTRA_SIZE         = "size";
    /** Optional list of mirror host prefixes (e.g. {@code "http://a.b"})
     *  the service will fall through to if {@link #EXTRA_URL} fails. */
    public  static final String EXTRA_MIRRORS      = "mirrors";

    private static final String CHANNEL = "bmchat-update-downloads";
    private static final int NOTIF_PROGRESS_ID = 0xBD0010;
    private static final int NOTIF_READY_ID    = 0xBD0011;

    /** Single-threaded executor: only one APK download at a time. */
    private static final ExecutorService EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bmchat-update-download");
                t.setDaemon(false);
                return t;
            });

    /** Cancelled by {@link #ACTION_STOP}; checked from the IO loop so
     *  we abort the HTTP read promptly when the user dismisses. */
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);

    /** Connection currently servicing the download. Held statically so
     *  that the stop-action receiver can disconnect it from any
     *  thread without having to round-trip through the IO loop. */
    private static volatile @Nullable HttpURLConnection ACTIVE_CONN;

    /** Manifest we are downloading. Kept to render the final
     *  "ready to install" notification without re-parsing intent
     *  extras after the foreground service stops. */
    private static volatile @Nullable Manifest CURRENT;

    @Override public @Nullable IBinder onBind(Intent intent) { return null; }

    /**
     * Convenience static entry point: starts the foreground service
     * with the supplied manifest. Picks the platform-appropriate
     * {@code startForegroundService} on Oreo+ to satisfy the
     * "must call startForeground within 5s" requirement.
     */
    public static void startDownload(@NonNull Context ctx, @NonNull Manifest m) {
        Intent i = new Intent(ctx, UpdateDownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VERSION_CODE, m.versionCode)
                .putExtra(EXTRA_VERSION_NAME, m.versionName)
                .putExtra(EXTRA_URL,         m.url)
                .putExtra(EXTRA_SHA256,      m.sha256)
                .putExtra(EXTRA_SIZE,        m.size);
        if (m.mirrors != null && !m.mirrors.isEmpty()) {
            i.putStringArrayListExtra(EXTRA_MIRRORS,
                    new ArrayList<>(m.mirrors));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            CANCEL.set(true);
            HttpURLConnection c = ACTIVE_CONN;
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) {} }
            NotificationManagerCompat.from(this).cancel(NOTIF_PROGRESS_ID);
            STATE = State.IDLE;
            PROGRESS = 0; DOWNLOADED = 0; TOTAL = 0;
            VERSION_NAME = null; ERROR_MSG = null; READY_APK_PATH = null;
            broadcastProgress(this);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        CANCEL.set(false);
        STATE = State.RUNNING;
        PROGRESS = 0; DOWNLOADED = 0; TOTAL = 0;
        ERROR_MSG = null; READY_APK_PATH = null;

        ArrayList<String> mirrors = intent.getStringArrayListExtra(EXTRA_MIRRORS);
        Manifest m = new Manifest(
                intent.getLongExtra(EXTRA_VERSION_CODE, 0L),
                intent.getStringExtra(EXTRA_VERSION_NAME),
                intent.getStringExtra(EXTRA_URL),
                intent.getStringExtra(EXTRA_SHA256),
                intent.getLongExtra(EXTRA_SIZE, 0L),
                mirrors != null ? mirrors : Collections.<String>emptyList());
        CURRENT = m;
        VERSION_NAME = m.versionName;
        TOTAL = m.size;
        broadcastProgress(this);

        ensureChannel(this);
        Notification initial = buildProgressNotification(this, m, 0, 0, m.size);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC requested in manifest;
            // pass it explicitly so Android 12+ doesn't kill us for
            // missing-type.
            startForeground(
                    NOTIF_PROGRESS_ID,
                    initial,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_PROGRESS_ID, initial);
        }

        EXEC.submit(() -> runDownload(m, startId));
        return START_NOT_STICKY;
    }

    /** Cancel-action PendingIntent used by the progress notification. */
    private static @NonNull PendingIntent makeCancelIntent(@NonNull Context ctx) {
        Intent i = new Intent(ctx, UpdateDownloadService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(ctx, 11, i, flags);
    }

    private static @NonNull Notification buildProgressNotification(
            @NonNull Context ctx, @NonNull Manifest m,
            int pct, long downloaded, long total) {
        String title = ctx.getString(R.string.bmchat_update_dl_title_fmt, m.versionName);
        String body  = downloaded > 0
                ? Formatter.formatShortFileSize(ctx, downloaded)
                  + " / "
                  + Formatter.formatShortFileSize(ctx, total > 0 ? total : downloaded)
                  + "  (" + pct + "%)"
                : ctx.getString(R.string.bmchat_update_dl_starting);

        return new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(100, pct, downloaded == 0)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0,
                        ctx.getString(R.string.bmchat_update_dl_cancel),
                        makeCancelIntent(ctx))
                .build();
    }

    @AnyThread
    private void runDownload(@NonNull Manifest m, int startId) {
        File apk;
        try {
            apk = doDownload(this, m);
        } catch (Throwable t) {
            Log.w(TAG, "background update download failed", t);
            if (!CANCEL.get()) {
                showErrorNotification(this, m, t);
                STATE = State.ERROR;
                ERROR_MSG = t.getMessage() != null
                        ? t.getMessage()
                        : t.getClass().getSimpleName();
                broadcastProgress(this);
            }
            stopForeground(true);
            stopSelf(startId);
            return;
        }

        stopForeground(true);
        NotificationManagerCompat.from(this).cancel(NOTIF_PROGRESS_ID);
        showReadyNotification(this, m, apk);
        STATE = State.READY;
        PROGRESS = 100;
        READY_APK_PATH = apk.getAbsolutePath();
        broadcastProgress(this);
        stopSelf(startId);
    }

    /**
     * Push the current state snapshot to any in-app listeners
     * (typically the conversation-list banner). Uses
     * {@link androidx.localbroadcastmanager.content.LocalBroadcastManager}
     * because the state is process-local and we do NOT want third
     * parties to be able to spoof or sniff update events.
     */
    private static void broadcastProgress(@NonNull Context ctx) {
        Intent i = new Intent(ACTION_PROGRESS_BROADCAST);
        androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(ctx.getApplicationContext())
                .sendBroadcast(i);
    }

    private static void showReadyNotification(
            @NonNull Context ctx, @NonNull Manifest m, @NonNull File apk) {
        ensureChannel(ctx);

        Intent installIntent = new Intent(ctx, UpdateInstallReceiver.class)
                .setAction(ACTION_INSTALL)
                .putExtra("apk_path", apk.getAbsolutePath())
                .putExtra(EXTRA_VERSION_NAME, m.versionName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 12, installIntent, flags);

        Notification n = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle(ctx.getString(R.string.bmchat_update_dl_done_title_fmt, m.versionName))
                .setContentText(ctx.getString(R.string.bmchat_update_dl_done_body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .addAction(0,
                        ctx.getString(R.string.bmchat_update_dl_install_action), pi)
                .build();
        NotificationManagerCompat.from(ctx).notify(NOTIF_READY_ID, n);
    }

    private static void showErrorNotification(
            @NonNull Context ctx, @NonNull Manifest m, @NonNull Throwable t) {
        ensureChannel(ctx);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle(ctx.getString(R.string.bmchat_update_dl_failed_title))
                .setContentText(t.getMessage() != null
                        ? t.getMessage()
                        : ctx.getString(R.string.bmchat_update_dl_failed_body))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        ctx.getString(R.string.bmchat_update_dl_failed_body)
                                + "\n\n" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName())))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        NotificationManagerCompat.from(ctx).notify(NOTIF_PROGRESS_ID, n);
    }

    private File doDownload(@NonNull Context ctx, @NonNull Manifest m) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        File out = new File(dir, "bmchat-" + m.versionCode + ".apk");
        if (out.exists() && verifyHash(out, m.sha256)) {
            return out;
        }
        File tmp = new File(dir, out.getName() + ".part");
        if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.delete();

        // Build the ordered candidate URL list: the manifest's own
        // .url first, then the same path re-hosted at every known
        // mirror (manifest-supplied + built-in defaults).
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        urls.add(m.url);
        try {
            URL u = new URL(m.url);
            String suffix = u.getFile();
            for (String h : m.mirrors)            urls.add(h + suffix);
            for (String h : BMChatUpdater.DEFAULT_HOSTS) urls.add(h + suffix);
        } catch (Throwable ignored) {}

        IOException lastErr = null;
        for (String urlStr : urls) {
            if (CANCEL.get()) throw new IOException("cancelled by user");
            HttpURLConnection conn = null;
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            try {
                Log.i(TAG, "downloading APK from " + urlStr);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(60_000);
                ACTIVE_CONN = conn;
                int code = conn.getResponseCode();
                if (code != 200) {
                    lastErr = new IOException("HTTP " + code + " from " + urlStr);
                    continue;
                }
                long contentLength = conn.getContentLengthLong();
                long total = m.size > 0 ? m.size : (contentLength > 0 ? contentLength : 0);
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    long downloaded = 0;
                    long limit = Math.max(m.size > 0 ? m.size : 0, 200L * 1024L * 1024L);
                    long lastNotify = 0L;
                    for (int n; (n = in.read(buf)) > 0;) {
                        if (CANCEL.get()) throw new IOException("cancelled by user");
                        downloaded += n;
                        if (downloaded > limit) throw new IOException("apk too large");
                        fos.write(buf, 0, n);
                        long nowMs = System.currentTimeMillis();
                        if (nowMs - lastNotify > 500L) {
                            int pct = total > 0
                                    ? (int) Math.min(100, downloaded * 100L / total)
                                    : 0;
                            Notification updated = buildProgressNotification(
                                    ctx, m, pct, downloaded, total);
                            NotificationManagerCompat.from(ctx).notify(NOTIF_PROGRESS_ID, updated);
                            PROGRESS = pct;
                            DOWNLOADED = downloaded;
                            TOTAL = total > 0 ? total : downloaded;
                            broadcastProgress(ctx);
                            lastNotify = nowMs;
                        }
                    }
                }
                if (!verifyHash(tmp, m.sha256)) {
                    lastErr = new IOException("sha-256 mismatch from " + urlStr);
                    continue;
                }
                if (!tmp.renameTo(out)) throw new IOException("rename failed");
                return out;
            } catch (IOException e) {
                lastErr = e;
                Log.w(TAG, "mirror download failed: " + urlStr + " — " + e.getMessage());
            } finally {
                ACTIVE_CONN = null;
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        if (lastErr != null) throw lastErr;
        throw new IOException("no mirror produced a valid APK");
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

    static void ensureChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.bmchat_update_dl_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ctx.getString(R.string.bmchat_update_dl_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** Receiver that the "ready" notification fires when tapped. */
    public static final class UpdateInstallReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String path = intent != null ? intent.getStringExtra("apk_path") : null;
            if (path == null) return;
            launchInstaller(ctx, new File(path));
        }
    }

    /**
     * Static helper: hand the freshly-downloaded APK to the system
     * package installer. Used both by the notification tap path and
     * by the in-app "Install" button on the conversation-list
     * banner — they should behave identically.
     */
    public static void launchInstaller(@NonNull Context ctx, @NonNull File apk) {
        if (!apk.exists()) return;
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    ctx,
                    BuildConfig.APPLICATION_ID + ".fileprovider",
                    apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "Install intent failed", t);
        }
    }

    /**
     * Mini-manifest with just the fields the service needs. Mirrors
     * {@code BMChatUpdater.Manifest} but lives in this package so the
     * service has no dependency on the updater's internals.
     */
    public static final class Manifest {
        public final long versionCode;
        public final String versionName;
        public final String url;
        public final String sha256;
        public final long size;
        /** Optional list of host prefixes (no trailing slash) the
         *  service may fall through to if the primary {@link #url}
         *  fails. Never null; may be empty. */
        public final @NonNull List<String> mirrors;

        public Manifest(long versionCode, String versionName,
                        String url, String sha256, long size) {
            this(versionCode, versionName, url, sha256, size,
                 Collections.<String>emptyList());
        }

        public Manifest(long versionCode, String versionName,
                        String url, String sha256, long size,
                        @NonNull List<String> mirrors) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.url    = url    == null ? "" : url;
            this.sha256 = sha256 == null ? "" : sha256;
            this.size   = size;
            this.mirrors = mirrors;
        }
    }
}
