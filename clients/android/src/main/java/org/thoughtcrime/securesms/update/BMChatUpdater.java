package org.thoughtcrime.securesms.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.ref.WeakReference;

import org.json.JSONObject;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.guava.Optional;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

/**
 * Self-update checker for BMChat.
 *
 * <p>Polls {@code http://5.187.4.132/update.json}, compares the published
 * {@code versionCode} with {@link BuildConfig#VERSION_CODE}, and if newer:
 * <ol>
 *     <li>asks the user once with a single-button dialog whether to update,
 *     <li>downloads the APK to the app cache and verifies its SHA-256,
 *     <li>installs the APK <strong>through the modern
 *         {@link android.content.pm.PackageInstaller} session API</strong>
 *         which only renders the system "Update?" sheet (no second
 *         activity). The session holds an open file descriptor to the
 *         downloaded APK, so the install starts immediately on confirm
 *         and the running app is replaced once the session commits.
 * </ol>
 *
 * <p>Re-checks every {@link #PERIODIC_RECHECK_MS} while the app is in the
 * foreground, so a fresh release published mid-session also triggers the
 * prompt.
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

    /**
     * Ordered list of hosts the updater consults. Each entry is a
     * scheme+authority prefix (no trailing slash). On each tick the
     * updater hits {@code &lt;host&gt;/update.json} in order; the
     * first 200 wins. The manifest may also publish its own
     * {@code "mirrors": [...]} array — those are merged in below the
     * built-in defaults so a freshly added mirror propagates without
     * a client-side rebuild.
     *
     * <p>Order matters:
     *   <ol>
     *     <li>{@code 5.187.4.132} — primary (Fornex / EU).
     *     <li>{@code 158.160.104.107:8080} — WhiteBlade mirror
     *         (Yandex Cloud / RU). Often reachable when the primary
     *         is filtered or routed away.
     *   </ol>
     */
    static final String[] DEFAULT_HOSTS = new String[] {
        "http://5.187.4.132",
        "http://158.160.104.107:8080"
    };

    private static final String MANIFEST_PATH = "/update.json";

    /** Legacy: keep the original constant pointing at the primary so
     *  any external caller that still resolves it (logs, error
     *  messages) keeps working. New code must go through
     *  {@link #fetchManifest(Context)}. */
    private static final String UPDATE_MANIFEST_URL =
        DEFAULT_HOSTS[0] + MANIFEST_PATH;

    /** Don't issue the same network probe more often than this. Tightened from
     *  the original 6 h to 1 h to 15 min: BMChat ships hot-fix builds back to
     *  back, and a one-hour debounce was leaving users staring at outdated
     *  versions for the whole length of their typical messenger session. The
     *  manifest is ~700 bytes, so polling every 15 minutes is irrelevant
     *  network-wise. */
    private static final long MIN_CHECK_INTERVAL_MS = 15L * 60L * 1000L;
    /** When the user returns the process to the foreground, force a fresh
     *  probe even if {@link #MIN_CHECK_INTERVAL_MS} has not elapsed yet, as
     *  long as the previous probe is older than this. Stops two
     *  back-to-back foreground transitions (lock screen, app switcher) from
     *  hammering the manifest, while still re-checking on a real return after
     *  a few minutes away. */
    private static final long FOREGROUND_RESUME_INTERVAL_MS = 60L * 1000L;
    /** Initial delay after launch before the first probe (keeps cold-start snappy). */
    private static final long FOREGROUND_DELAY_MS  = 8L * 1000L;
    /** While the app stays in foreground, re-poke the updater every 15 minutes
     *  — the inner debounce on {@link #MIN_CHECK_INTERVAL_MS} still applies. */
    private static final long PERIODIC_RECHECK_MS  = 15L * 60L * 1000L;

    private static final String PREFS_NAME              = "bmchat-updater";
    private static final String PREF_LAST_CHECK_AT      = "last-check-at";
    private static final String PREF_DECLINED_VERSION   = "declined-version-code";
    /** Last {@link BuildConfig#VERSION_CODE} that performed a check. When the
     *  installed app jumps versions (the user just upgraded) we forcibly clear
     *  the debounce so the freshly-launched build always polls the manifest
     *  once — otherwise a back-to-back hot-fix release would silently sit
     *  behind a stale {@link #PREF_LAST_CHECK_AT} timestamp. */
    private static final String PREF_LAST_CHECK_VC      = "last-check-version-code";

    private static final String INSTALL_ACTION =
            "org.thoughtcrime.securesms.update.BMChatUpdater.INSTALL_RESULT";
    private static final String UPDATE_NOTIF_CHANNEL = "bmchat-updates";
    private static final int UPDATE_NOTIF_ID = 0xBC0DE;
    /** Broadcast that the persistent update notification's "Установить"
     *  action fires; handled by {@link UpdateNotifReceiver} which kicks
     *  the download path on the foreground activity (or, if none, just
     *  launches the chat list and lets bindGlobalLifecycle pick up the
     *  same manifest on the next foreground tick). */
    private static final String NOTIF_INSTALL_ACTION =
            "org.thoughtcrime.securesms.update.BMChatUpdater.NOTIF_INSTALL";
    private static final String NOTIF_DISMISS_ACTION =
            "org.thoughtcrime.securesms.update.BMChatUpdater.NOTIF_DISMISS";

    /** The most recent manifest we offered the user — kept in process so
     *  the notification's "Установить" callback can rebuild the download
     *  job without re-fetching {@code update.json}. Cleared once the user
     *  declines or installs. */
    private static volatile @Nullable Manifest pendingManifest;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bmchat-updater");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static @Nullable Runnable activeRecheck;

    /** Last activity that signalled {@code onResume()}. Used as the host for
     *  the "Update available" dialog so a freshly-foregrounded screen (Chat,
     *  Settings, Help, …) is the one that surfaces the prompt — not just the
     *  chat list. */
    private static @Nullable WeakReference<Activity> topActivityRef;

    /** Number of activities currently in the {@code onStart()/onStop()}
     *  range, used to detect process-level background → foreground transitions
     *  without depending on {@code androidx.lifecycle.ProcessLifecycleOwner}
     *  (which is awkward to initialise from a non-default Application). */
    private static int startedActivities = 0;
    private static boolean lifecycleBound = false;

    /** Last successful manifest fetch timestamp for the "force on resume"
     *  path — independent of {@link #PREF_LAST_CHECK_AT} so background
     *  bookkeeping stays separate from explicit foreground triggers. */
    private static volatile long lastForegroundProbeAtMs = 0L;

    /** Guard against showing the "Update available" prompt twice in a row
     *  (e.g. when the user rotates the device while the dialog is up, or
     *  when a foreground transition fires while a manual check from
     *  Settings is still in flight). */
    private static volatile boolean promptShown = false;
    /** Guard against re-entrant downloads when the user taps the install
     *  button repeatedly. */
    private static volatile boolean downloadInProgress = false;

    private BMChatUpdater() {}

    /**
     * Hooks the updater into the {@link Application} lifecycle so that
     * <ul>
     *     <li>every time the user brings BMChat back to the foreground we
     *         re-probe the manifest (subject to {@link #FOREGROUND_RESUME_INTERVAL_MS}),
     *     <li>any activity — chat list, conversation, settings, help, … — can
     *         host the "Update available" dialog, so the prompt does not
     *         require the user to navigate back to the chat list,
     *     <li>a long foreground session keeps polling every
     *         {@link #PERIODIC_RECHECK_MS} so a new release published while
     *         the user is reading messages is also picked up.
     * </ul>
     *
     * <p>Safe to call multiple times — only the first invocation actually
     * registers the lifecycle callbacks.
     */
    @MainThread
    public static void bindGlobalLifecycle(final Application app) {
        if (app == null || lifecycleBound) return;
        lifecycleBound = true;

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, @Nullable Bundle s) {}
            @Override public void onActivityStarted(Activity a) {
                startedActivities++;
                if (startedActivities == 1) {
                    // Process just transitioned background -> foreground.
                    forceCheckOnForeground(a);
                }
            }
            @Override public void onActivityResumed(Activity a) {
                topActivityRef = new WeakReference<>(a);
                schedulePeriodicRecheck(a);
            }
            @Override public void onActivityPaused(Activity a) {
                if (topActivityRef != null && topActivityRef.get() == a) {
                    topActivityRef = null;
                }
            }
            @Override public void onActivityStopped(Activity a) {
                if (startedActivities > 0) startedActivities--;
                if (startedActivities == 0 && activeRecheck != null) {
                    MAIN.removeCallbacks(activeRecheck);
                    activeRecheck = null;
                }
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle s) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    /** Force a fresh manifest probe right after the user returns to the
     *  foreground. The probe still reuses the standard prompt path; it just
     *  bypasses the silent {@link #MIN_CHECK_INTERVAL_MS} debounce so the
     *  user sees a pending update on next focus, not on the next 15-minute
     *  tick. */
    @MainThread
    private static void forceCheckOnForeground(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        long now = System.currentTimeMillis();
        if (now - lastForegroundProbeAtMs < FOREGROUND_RESUME_INTERVAL_MS) return;
        lastForegroundProbeAtMs = now;
        // Run the probe through the manual code path which ignores the
        // SharedPreferences debounce, but suppresses any user-visible toast
        // since this is an automatic background check.
        EXEC.submit(() -> {
            try {
                Context ctx = activity.getApplicationContext();
                Manifest manifest = fetchManifest(ctx);
                if (manifest == null) return;
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(PREF_LAST_CHECK_AT, System.currentTimeMillis())
                        .putLong(PREF_LAST_CHECK_VC, BuildConfig.VERSION_CODE)
                        .apply();
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) return;
                long declined = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getLong(PREF_DECLINED_VERSION, 0L);
                if (declined == manifest.versionCode) return;
                MAIN.post(() -> {
                    Activity host = currentTopActivity();
                    if (host != null) promptUser(host, manifest);
                });
            } catch (Throwable t) {
                android.util.Log.w(TAG, "foreground update probe failed", t);
            }
        });
    }

    /** Set up (or refresh) the periodic re-check tied to the latest
     *  resumed activity. Idempotent across activity transitions. */
    @MainThread
    private static void schedulePeriodicRecheck(final Activity activity) {
        if (activeRecheck != null) MAIN.removeCallbacks(activeRecheck);
        final Runnable poke = new Runnable() {
            @Override public void run() {
                Activity host = currentTopActivity();
                if (host == null || host.isFinishing()) return;
                checkInBackground(host);
                MAIN.postDelayed(this, PERIODIC_RECHECK_MS);
            }
        };
        activeRecheck = poke;
        MAIN.postDelayed(poke, PERIODIC_RECHECK_MS);
    }

    private static @Nullable Activity currentTopActivity() {
        WeakReference<Activity> ref = topActivityRef;
        if (ref == null) return null;
        Activity a = ref.get();
        return (a == null || a.isFinishing()) ? null : a;
    }

    /** Hooks the updater into a foreground {@code Activity}. Safe to call from
     *  any {@code Activity#onResume} — the actual network/UI work is deferred
     *  by {@link #FOREGROUND_DELAY_MS} so the main launch path is not slowed
     *  down. While the activity stays alive a periodic re-check fires every
     *  {@link #PERIODIC_RECHECK_MS} so a fresh release announced mid-session
     *  is also picked up.
     *
     *  @deprecated Prefer {@link #bindGlobalLifecycle(Application)} which
     *      hooks the whole process and works from every activity, not just
     *      the chat list. Kept as a compatibility entry point. */
    @Deprecated
    @MainThread
    public static void scheduleForActivity(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activeRecheck != null) MAIN.removeCallbacks(activeRecheck);
        final Runnable poke = new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) return;
                checkInBackground(activity);
                MAIN.postDelayed(this, PERIODIC_RECHECK_MS);
            }
        };
        activeRecheck = poke;
        MAIN.postDelayed(poke, FOREGROUND_DELAY_MS);
    }

    /**
     * Manual entry point invoked from "Settings → Advanced → Check for
     * updates". Bypasses the silent debounce and the previously-declined
     * cache, and reports the result inline so the user always knows whether
     * a probe actually happened.
     */
    @MainThread
    public static void checkNowFromUi(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Toast.makeText(activity, "Проверяем обновления BMChat…", Toast.LENGTH_SHORT).show();
        EXEC.submit(() -> {
            try {
                final Manifest manifest =
                        fetchManifest(activity.getApplicationContext());
                if (manifest == null) {
                    MAIN.post(() -> Toast.makeText(activity,
                            "Не удалось получить update.json — проверьте сеть.",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                Context ctx = activity.getApplicationContext();
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(PREF_LAST_CHECK_AT, System.currentTimeMillis())
                        .putLong(PREF_LAST_CHECK_VC, BuildConfig.VERSION_CODE)
                        // Clear any "don't ask again for this version" stamp,
                        // since the user explicitly asked us to check.
                        .remove(PREF_DECLINED_VERSION)
                        .apply();

                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    MAIN.post(() -> Toast.makeText(activity,
                            "У вас уже установлена последняя версия BMChat ("
                                    + BuildConfig.VERSION_NAME + ").",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                MAIN.post(() -> promptUser(activity, manifest));
            } catch (Throwable t) {
                android.util.Log.w(TAG, "manual update check failed", t);
                MAIN.post(() -> Toast.makeText(activity,
                        "Ошибка проверки обновлений: " + t.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
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

    /**
     * Silent (no-Activity) update probe used by {@link BMChatUpdateWorker}.
     *
     * <p>Runs synchronously on the calling thread (the WorkManager
     * worker already provides a background pool) and posts a
     * low-priority "Доступно обновление" notification if a newer
     * build is available and not declined. No dialog — there is no
     * Activity around. The notification opens BMChat on tap and runs
     * the standard install flow on "Установить".
     *
     * <p>Returns {@code true} when a newer manifest was found and a
     * notification was posted; {@code false} otherwise (no network,
     * no new version, version already declined, …). The caller can
     * use the return value to schedule a sooner retry on transient
     * failures, but BMChatUpdateWorker just feeds it back to
     * WorkManager which retries on its own cadence.
     */
    @AnyThread
    public static boolean checkSilentlyFromBackground(@NonNull Context applicationContext) {
        Context ctx = applicationContext.getApplicationContext();
        try {
            long now = System.currentTimeMillis();
            android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long last = prefs.getLong(PREF_LAST_CHECK_AT, 0L);
            long lastVc = prefs.getLong(PREF_LAST_CHECK_VC, 0L);
            if (lastVc != BuildConfig.VERSION_CODE) last = 0L;
            // Background path uses a softer debounce than foreground:
            // WorkManager already throttles us at the OS level (~6h
            // periodic), so any tighter local debounce just turns into
            // wasted wake-ups. We still skip the probe if a foreground
            // check ran <1h ago.
            if (now - last < (60L * 60L * 1000L)) return false;

            Manifest manifest = fetchManifest(ctx);
            if (manifest == null) return false;

            prefs.edit()
                    .putLong(PREF_LAST_CHECK_AT, now)
                    .putLong(PREF_LAST_CHECK_VC, BuildConfig.VERSION_CODE)
                    .apply();

            if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                // No-op: cancel any leftover notification from an older
                // probe (covers the case where the user installed via
                // an out-of-band channel and the sticky banner is now
                // stale).
                cancelUpdateNotification(ctx);
                return false;
            }

            long declined = prefs.getLong(PREF_DECLINED_VERSION, 0L);
            if (declined == manifest.versionCode) return false;

            pendingManifest = manifest;
            showUpdateNotificationGentle(ctx, manifest);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "background update probe failed", t);
            return false;
        }
    }

    /**
     * Low-priority cousin of {@link #showUpdateNotification} used from
     * background checks. Same payload (title, body, install / dismiss
     * actions) but with PRIORITY_DEFAULT instead of PRIORITY_HIGH, so
     * the user sees the entry quietly in the drawer without a heads-up
     * pop or vibration. The user-facing wording is also softer
     * ("Доступно обновление" vs the in-app prompt's "Скачать сейчас")
     * so a passive background find feels like a friendly reminder
     * rather than an alert.
     */
    @AnyThread
    private static void showUpdateNotificationGentle(@NonNull Context ctx, @NonNull Manifest m) {
        ensureUpdateChannel(ctx);

        Intent installIntent = new Intent(ctx, UpdateNotifReceiver.class)
                .setAction(NOTIF_INSTALL_ACTION);
        Intent dismissIntent = new Intent(ctx, UpdateNotifReceiver.class)
                .setAction(NOTIF_DISMISS_ACTION)
                .putExtra("versionCode", m.versionCode);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent installPi = PendingIntent.getBroadcast(ctx, 1, installIntent, piFlags);
        PendingIntent dismissPi = PendingIntent.getBroadcast(ctx, 2, dismissIntent, piFlags);

        Intent contentIntent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent contentPi = contentIntent != null
                ? PendingIntent.getActivity(ctx, 3, contentIntent, piFlags)
                : installPi;

        String body = "Размер: " + Formatter.formatShortFileSize(ctx, m.size);
        if (m.notes != null && !m.notes.isEmpty()) {
            body = body + "\n" + (m.notes.length() > 200 ? m.notes.substring(0, 200) + "…" : m.notes);
        }

        NotificationCompat.Builder n = new NotificationCompat.Builder(ctx, UPDATE_NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle("Доступно обновление BMChat " + m.versionName)
                .setContentText("Нажмите, чтобы установить")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                // PRIORITY_DEFAULT keeps the entry quiet in the drawer;
                // no heads-up, no sound, no vibration. The user sees it
                // when they next swipe down — exactly the "ненавязчиво"
                // behaviour the user asked for.
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setOngoing(false)       // gentle: dismissible by swipe
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .addAction(0, "Установить", installPi)
                .addAction(0, "Пропустить", dismissPi);

        try {
            NotificationManagerCompat.from(ctx).notify(UPDATE_NOTIF_ID, n.build());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "showUpdateNotificationGentle failed", t);
        }
    }

    @AnyThread
    private static void runCheck(final Activity activity) throws Exception {
        Context ctx = activity.getApplicationContext();
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long last = prefs.getLong(PREF_LAST_CHECK_AT, 0L);
        long lastVc = prefs.getLong(PREF_LAST_CHECK_VC, 0L);
        // Reset the debounce when the app itself was updated since the last
        // check: a fresh build should always re-probe the manifest at least
        // once on first launch.
        if (lastVc != BuildConfig.VERSION_CODE) {
            last = 0L;
        }
        if (now - last < MIN_CHECK_INTERVAL_MS) return;

        Manifest manifest = fetchManifest(ctx);
        if (manifest == null) return;

        prefs.edit()
                .putLong(PREF_LAST_CHECK_AT, now)
                .putLong(PREF_LAST_CHECK_VC, BuildConfig.VERSION_CODE)
                .apply();

        if (manifest.versionCode <= BuildConfig.VERSION_CODE) return;

        long declined = prefs.getLong(PREF_DECLINED_VERSION, 0L);
        if (declined == manifest.versionCode) return;

        MAIN.post(() -> promptUser(activity, manifest));
    }

    @MainThread
    private static void promptUser(final Activity activity, final Manifest m) {
        if (activity == null || activity.isFinishing()) return;

        // Always post the persistent notification first. It survives the
        // user dismissing or never seeing the in-app dialog (lock screen,
        // backgrounded app, dialog ignored, etc.). The notification has
        // its own "Установить" action so the user can drive the upgrade
        // without ever opening the app.
        pendingManifest = m;
        showUpdateNotification(activity.getApplicationContext(), m);

        if (promptShown) return;
        promptShown = true;

        String body = "Доступна новая версия BMChat " + m.versionName + "."
                + "\nРазмер: " + Formatter.formatShortFileSize(activity, m.size)
                + (m.notes != null && !m.notes.isEmpty() ? "\n\n" + m.notes : "");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Обновление BMChat")
                .setMessage(body)
                .setCancelable(true)
                .setPositiveButton("Скачать и установить",
                        (d, w) -> beginDownload(activity, m))
                .setNegativeButton("Позже", (d, w) -> {})
                .setNeutralButton("Пропустить эту версию", (d, w) -> {
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(PREF_DECLINED_VERSION, m.versionCode).apply();
                    cancelUpdateNotification(activity.getApplicationContext());
                    pendingManifest = null;
                })
                .create();
        dialog.setOnDismissListener(d -> promptShown = false);
        dialog.show();
    }

    // ---------------------------------------------------------------------
    //  persistent "update available" notification
    // ---------------------------------------------------------------------

    /**
     * Post a persistent (non-dismissable until the user declines or
     * installs) notification advertising the new manifest. The system
     * tray entry is the most reliable place to surface a pending
     * update — users who closed the in-app dialog, who keep BMChat in
     * the background, or who simply never returned to the chat list
     * still see it on every screen wake.
     */
    @AnyThread
    private static void showUpdateNotification(@NonNull Context ctx, @NonNull Manifest m) {
        ensureUpdateChannel(ctx);

        Intent installIntent = new Intent(ctx, UpdateNotifReceiver.class)
                .setAction(NOTIF_INSTALL_ACTION);
        Intent dismissIntent = new Intent(ctx, UpdateNotifReceiver.class)
                .setAction(NOTIF_DISMISS_ACTION)
                .putExtra("versionCode", m.versionCode);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent installPi = PendingIntent.getBroadcast(ctx, 1, installIntent, piFlags);
        PendingIntent dismissPi = PendingIntent.getBroadcast(ctx, 2, dismissIntent, piFlags);

        Intent contentIntent = ctx.getPackageManager()
                .getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent contentPi = contentIntent != null
                ? PendingIntent.getActivity(ctx, 3, contentIntent, piFlags)
                : installPi;

        String body = "Размер: " + Formatter.formatShortFileSize(ctx, m.size);
        if (m.notes != null && !m.notes.isEmpty()) {
            body = body + "\n" + (m.notes.length() > 200 ? m.notes.substring(0, 200) + "…" : m.notes);
        }

        NotificationCompat.Builder n = new NotificationCompat.Builder(ctx, UPDATE_NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.icon_notification)
                .setContentTitle("Обновление BMChat " + m.versionName)
                .setContentText("Размер: " + Formatter.formatShortFileSize(ctx, m.size))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setOngoing(true)        // sticky until install / decline
                .setAutoCancel(false)
                .setContentIntent(contentPi)
                .addAction(0, "Установить", installPi)
                .addAction(0, "Пропустить", dismissPi);

        try {
            NotificationManagerCompat.from(ctx).notify(UPDATE_NOTIF_ID, n.build());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "showUpdateNotification failed", t);
        }
    }

    @AnyThread
    private static void cancelUpdateNotification(@NonNull Context ctx) {
        try {
            NotificationManagerCompat.from(ctx).cancel(UPDATE_NOTIF_ID);
        } catch (Throwable ignored) {}
    }

    @AnyThread
    private static void ensureUpdateChannel(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(UPDATE_NOTIF_CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
                UPDATE_NOTIF_CHANNEL, "Обновления BMChat",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Уведомление, когда вышла новая версия мессенджера BMChat.");
        ch.enableLights(true);
        ch.enableVibration(false);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    /**
     * Receives "Установить" / "Пропустить" taps from the persistent
     * update notification. Routes them back through the in-app
     * download path so the user always lands in the standard install
     * flow (the same one the AlertDialog uses).
     */
    public static final class UpdateNotifReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (NOTIF_DISMISS_ACTION.equals(action)) {
                long vc = intent.getLongExtra("versionCode", 0L);
                if (vc > 0) {
                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(PREF_DECLINED_VERSION, vc).apply();
                }
                cancelUpdateNotification(ctx);
                pendingManifest = null;
                return;
            }
            if (!NOTIF_INSTALL_ACTION.equals(action)) return;
            Manifest m = pendingManifest;
            Activity host = currentTopActivity();
            if (host != null && m != null) {
                MAIN.post(() -> beginDownload(host, m));
                return;
            }
            // No top activity — bring the app to the foreground so the
            // download/install dialogs have somewhere to live; the next
            // foreground tick will re-prompt.
            Intent launch = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        }
    }

    @MainThread
    private static void beginDownload(final Activity activity, final Manifest m) {
        if (activity == null || activity.isFinishing()) return;
        // Hand the actual byte-pulling job off to the foreground
        // service so the download survives backgrounding, rotation
        // and even being swiped away from Recents. The user sees a
        // progress notification in the system tray; tapping the
        // notification opens BMChat, tapping "Отменить" stops it.
        UpdateDownloadService.Manifest dm = new UpdateDownloadService.Manifest(
                m.versionCode, m.versionName, m.url, m.sha256, m.size, m.mirrors);
        UpdateDownloadService.startDownload(activity.getApplicationContext(), dm);
        cancelUpdateNotification(activity.getApplicationContext());
        pendingManifest = null;

        // Surface a small confirmation so the user understands that
        // closing the dialog does *not* stop the download.
        try {
            Toast.makeText(activity,
                    activity.getString(R.string.bmchat_update_dl_starts_in_bg),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    /** Callback for {@link #downloadApk(Context, Manifest, ProgressListener)}. */
    private interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
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
                } catch (Throwable ignore) {}
                Toast.makeText(activity,
                        "Разрешите установку обновлений BMChat и снова откройте приложение.",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        try {
            installViaPackageInstaller(activity.getApplicationContext(), apk);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "PackageInstaller failed; falling back to ACTION_VIEW", t);
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider",
                        apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(i);
            } catch (Throwable t2) {
                android.util.Log.w(TAG, "fallback installer also failed", t2);
            }
        }
    }

    /**
     * Installs the supplied APK via the modern {@link PackageInstaller} session
     * API. The system shows its own concise "Update X?" sheet, the existing
     * BMChat process gets replaced once the session commits, and the installer
     * surfaces a single result broadcast that we listen for to log success.
     */
    @MainThread
    private static void installViaPackageInstaller(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // We've already prompted the user explicitly; suppress the second
            // confirmation sheet on Android 12+ when the app holds
            // REQUEST_INSTALL_PACKAGES.
            try {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            } catch (Throwable ignore) {}
        }
        if (apk.length() > 0) params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite(
                         "bmchat-" + System.currentTimeMillis() + ".apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }
            registerInstallReceiver(ctx);
            Intent statusIntent = new Intent(INSTALL_ACTION).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(
                    ctx, sessionId, statusIntent, flags);
            session.commit(pi.getIntentSender());
        }
    }

    private static volatile boolean receiverRegistered = false;

    private static void registerInstallReceiver(Context ctx) {
        if (receiverRegistered) return;
        synchronized (BMChatUpdater.class) {
            if (receiverRegistered) return;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    int status = intent.getIntExtra(
                            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                    String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            try { c.startActivity(confirm); } catch (Throwable ignore) {}
                        }
                    } else if (status == PackageInstaller.STATUS_SUCCESS) {
                        android.util.Log.i(TAG, "BMChat update installed.");
                    } else {
                        android.util.Log.w(TAG, "install failed status=" + status + " msg=" + msg);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(INSTALL_ACTION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.getApplicationContext().registerReceiver(
                        receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.getApplicationContext().registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        }
    }

    // ---------------------------------------------------------------------
    //  Network and crypto helpers.
    // ---------------------------------------------------------------------

    @AnyThread
    private static @Nullable Manifest fetchManifest() {
        return fetchManifest(null);
    }

    /**
     * Fetches the update manifest with a multi-mirror + P2P fallback
     * chain. On each call we walk the host list (built-in defaults
     * first, then any extras the most recently cached manifest
     * advertised under {@code "mirrors": [...]}), hit
     * {@code &lt;host&gt;/update.json}, and return the first 200.
     * If every host fails and {@code ctx} is supplied, we fall back
     * to the freshest manifest snapshot that a peer relayed inside
     * an incoming chat message — see {@link UpdateBroadcast}.
     */
    @AnyThread
    private static @Nullable Manifest fetchManifest(@Nullable Context ctx) {
        List<String> hosts = candidateHosts(ctx);
        for (String host : hosts) {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(host + MANIFEST_PATH);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(6_000);
                conn.setReadTimeout(6_000);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent",
                        "BMChat/" + BuildConfig.VERSION_NAME);
                int code = conn.getResponseCode();
                if (code == 200) {
                    byte[] body = readAll(conn.getInputStream(), 64 * 1024);
                    Manifest server = Manifest.parse(body);
                    if (server != null && ctx != null) {
                        // Persist the official snapshot so we can
                        // relay it to other peers via outgoing
                        // messages, and so the next probe knows
                        // about any newly published mirrors.
                        cachePeerManifest(ctx, new String(body, "UTF-8"));
                    }
                    if (server != null) {
                        android.util.Log.i(TAG, "manifest from " + host
                                + " vc=" + server.versionCode);
                        return server;
                    }
                }
            } catch (Throwable ignored) {
                // try next host
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        if (ctx == null) return null;
        // All HTTP mirrors failed — fall back to the freshest
        // peer-relayed snapshot we have, if any. Older or
        // self-built manifests are rejected inside Manifest.parse.
        String json = UpdateBroadcast.bestKnownManifestJson(ctx);
        if (json == null) return null;
        try {
            Manifest m = Manifest.parse(json.getBytes("UTF-8"));
            if (m != null) {
                android.util.Log.i(TAG, "using peer-relayed manifest fallback for vc=" + m.versionCode);
            }
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Build the ordered list of hosts to probe for {@code update.json}.
     * Built-in defaults come first; any extra hosts the most recently
     * cached manifest advertised under {@code "mirrors": [...]} are
     * appended (deduplicated, original order preserved). Always
     * includes at least {@link #DEFAULT_HOSTS}.
     */
    @AnyThread
    private static @NonNull List<String> candidateHosts(@Nullable Context ctx) {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.addAll(Arrays.asList(DEFAULT_HOSTS));
        if (ctx != null) {
            try {
                String json = UpdateBroadcast.bestKnownManifestJson(ctx);
                if (json != null) {
                    JSONObject m = new JSONObject(json);
                    JSONArray arr = m.optJSONArray("mirrors");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String h = arr.optString(i, "");
                            h = normaliseHost(h);
                            if (h != null) hosts.add(h);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return new ArrayList<>(hosts);
    }

    /** Sanitise a manifest-supplied mirror entry. Drops trailing slashes,
     *  rejects anything that isn't plain http(s). */
    private static @Nullable String normaliseHost(@Nullable String s) {
        if (s == null) return null;
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        if (s.length() > 200) return null;
        return s;
    }

    /** Drop any peer-relayed {@code update.json} snapshot so the next
     *  {@link #fetchManifest(Context)} probe hits the official servers. */
    @AnyThread
    public static void clearCachedPeerManifest(@NonNull Context ctx) {
        try {
            ctx.getSharedPreferences(UpdateBroadcast.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(UpdateBroadcast.KEY_PEER_MANIFEST_JSON)
                    .remove(UpdateBroadcast.KEY_PEER_MANIFEST_AT_MS)
                    .apply();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "clearCachedPeerManifest failed", t);
        }
    }

    /** Re-download {@code update.json} from official mirrors only. */
    @AnyThread
    public static @Nullable Manifest refetchOfficialManifest(@NonNull Context ctx) {
        clearCachedPeerManifest(ctx);
        return fetchManifest(ctx);
    }

    /** Stash the just-fetched server manifest into the shared
     *  {@link UpdateBroadcast} prefs slot so subsequent outgoing
     *  messages can relay it to peers. */
    @AnyThread
    private static void cachePeerManifest(@NonNull Context ctx, @NonNull String json) {
        try {
            ctx.getSharedPreferences(UpdateBroadcast.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(UpdateBroadcast.KEY_PEER_MANIFEST_JSON, json)
                    .putLong(UpdateBroadcast.KEY_PEER_MANIFEST_AT_MS,
                             System.currentTimeMillis())
                    .apply();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "cachePeerManifest failed", t);
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

    /**
     * Pull the APK described by {@code m}. Tries the manifest's own
     * {@code url} first, then re-points the same path at every other
     * trusted mirror host until one of them serves a body whose
     * SHA-256 matches. SHA-256 is the only thing that gates accept,
     * so it does not matter which mirror actually returned the bytes.
     */
    private static File downloadApk(Context ctx, Manifest m,
                                    @Nullable ProgressListener progress) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        File out = new File(dir, "bmchat-" + m.versionCode + ".apk");
        if (out.exists() && verifyHash(out, m.sha256)) {
            if (progress != null) progress.onProgress(out.length(), out.length());
            return out;
        }
        File tmp = new File(dir, out.getName() + ".part");
        if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
            tmp.delete();

        // Build the ordered list of URLs to try. The manifest's
        // own URL goes first, then we re-host the same path at every
        // mirror (defaults + manifest extras). Duplicates collapse.
        LinkedHashSet<String> urlSet = new LinkedHashSet<>();
        urlSet.add(m.url);
        try {
            URL primary = new URL(m.url);
            String suffix = primary.getFile();
            for (String h : candidateHosts(ctx)) {
                urlSet.add(h + suffix);
            }
            for (String h : m.mirrors) {
                urlSet.add(h + suffix);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "could not derive mirror download URLs", t);
        }

        IOException lastErr = null;
        for (String urlStr : urlSet) {
            HttpURLConnection conn = null;
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            try {
                android.util.Log.i(TAG, "downloading APK from " + urlStr);
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(60_000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    lastErr = new IOException("HTTP " + code + " from " + urlStr);
                    continue;
                }
                long contentLength = conn.getContentLengthLong();
                long total = m.size > 0 ? m.size : (contentLength > 0 ? contentLength : 0);
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] tmpBuf = new byte[64 * 1024];
                    long downloaded = 0;
                    long limit = Math.max(m.size > 0 ? m.size : 0, 200L * 1024L * 1024L);
                    long lastReport = 0L;
                    for (int n; (n = in.read(tmpBuf)) > 0;) {
                        downloaded += n;
                        if (downloaded > limit) throw new IOException("apk too large");
                        fos.write(tmpBuf, 0, n);
                        long nowMs = System.currentTimeMillis();
                        if (progress != null && nowMs - lastReport > 200L) {
                            progress.onProgress(downloaded, total);
                            lastReport = nowMs;
                        }
                    }
                    if (progress != null) progress.onProgress(downloaded, total);
                }
                if (!verifyHash(tmp, m.sha256)) {
                    lastErr = new IOException("sha-256 mismatch from " + urlStr);
                    continue;
                }
                // Success path — promote .part to final, return.
                if (!tmp.renameTo(out)) throw new IOException("rename failed");
                return out;
            } catch (IOException e) {
                lastErr = e;
                android.util.Log.w(TAG, "mirror download failed: " + urlStr + " — " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
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

    /** Lightweight typed wrapper around the {@code update.json} manifest. */
    static final class Manifest {
        final long versionCode;
        final String versionName;
        final String url;
        final String sha256;
        final long size;
        final @Nullable String notes;
        /** Hosts the manifest itself advertises (in addition to
         *  {@link BMChatUpdater#DEFAULT_HOSTS}). Non-null, may be
         *  empty. Each entry is a scheme+authority prefix, no
         *  trailing slash. */
        final @NonNull List<String> mirrors;

        private Manifest(long versionCode, String versionName, String url,
                         String sha256, long size, @Nullable String notes,
                         @NonNull List<String> mirrors) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.notes = notes;
            this.mirrors = mirrors;
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
                // The manifest URL must point at one of the well-known
                // hosts: built-in defaults OR a host the manifest itself
                // declares under "mirrors". This stops a malicious peer
                // from redirecting updates anywhere they like, while
                // still letting us add new mirrors without a client
                // rebuild.
                List<String> mirrors = new ArrayList<>();
                JSONArray arr = o.optJSONArray("mirrors");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String h = normaliseHost(arr.optString(i, ""));
                        if (h != null) mirrors.add(h);
                    }
                }
                if (!isUrlOnTrustedHost(url, mirrors)) return null;
                return new Manifest(code, name, url, sha, size, notes, mirrors);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Returns true iff {@code url} starts with one of the built-in
     *  default hosts or one of the manifest-supplied mirrors. */
    @AnyThread
    private static boolean isUrlOnTrustedHost(@NonNull String url,
                                              @NonNull List<String> mirrors) {
        for (String h : DEFAULT_HOSTS) {
            if (url.startsWith(h + "/")) return true;
        }
        for (String h : mirrors) {
            if (url.startsWith(h + "/")) return true;
        }
        return false;
    }

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
