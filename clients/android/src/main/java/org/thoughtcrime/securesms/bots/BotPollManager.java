package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Drives the polling cycle for all configured Telegram bots.
 *
 * <p>Two entry points:
 * <ul>
 *     <li>{@link #pollAllBlocking(Context)} — runs on the calling thread,
 *         used by {@link BotPollWorker} and by the manual "Проверить
 *         сейчас" button,
 *     <li>{@link #pollAllAsync(Context, Runnable)} — fire-and-forget
 *         from the UI.
 * </ul>
 *
 * <p>{@link #ensurePeriodicScheduled(Context)} sets up a 15-minute
 * {@link PeriodicWorkRequest} (the WorkManager minimum) and is idempotent.
 */
public final class BotPollManager {

  /**
   * Aggregated counters for one polling cycle, surfaced in the manual
   * "Проверить сейчас" toast so the user can tell apart "received but
   * queued for review" from "received and auto-published".
   */
  public static final class PollResult {
    public int received;   // total Telegram updates returned by getUpdates
    public int published;  // updates that the dispatcher mirrored into chats
    public int queued;     // updates that landed in PendingPostStore
    public int dropped;    // /start, etc. — not republished anywhere
  }

  private static final String TAG = "BotPollMgr";
  private static final String UNIQUE_WORK = "bmchat-bots-poll";

  private static final ExecutorService EXEC =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BotPollMgr");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
      });

  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private BotPollManager() {}

  // ---------------------------------------------------------------------
  //  scheduling
  // ---------------------------------------------------------------------

  @AnyThread
  public static void ensurePeriodicScheduled(@NonNull Context context) {
    Constraints constraints = new Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build();
    PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
        BotPollWorker.class, 15, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
            PeriodicWorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .build();
    WorkManager.getInstance(context.getApplicationContext())
        .enqueueUniquePeriodicWork(UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP, req);
  }

  @AnyThread
  public static void cancelPeriodic(@NonNull Context context) {
    WorkManager.getInstance(context.getApplicationContext())
        .cancelUniqueWork(UNIQUE_WORK);
  }

  // ---------------------------------------------------------------------
  //  poll cycle
  // ---------------------------------------------------------------------

  /** Poll every active bot. Returns the number of new messages republished. */
  @WorkerThread
  public static int pollAllBlocking(@NonNull Context context) {
    return pollAllBlockingDetailed(context).received;
  }

  @WorkerThread
  public static PollResult pollAllBlockingDetailed(@NonNull Context context) {
    Context appContext = context.getApplicationContext();
    PollResult result = new PollResult();
    BotStore store = new BotStore(appContext);
    List<BotConfig> bots = store.getAll();
    if (bots.isEmpty()) return result;

    DcAccounts accounts = DcHelper.getAccounts(appContext);
    for (BotConfig bot : bots) {
      if (bot.paused) continue;
      try {
        DcContext dcContext = accounts.getAccount(bot.dcAccountId);
        if (dcContext == null) {
          Log.w(TAG, "missing DcContext for account " + bot.dcAccountId
              + " — bot " + bot.id + " skipped");
          continue;
        }
        pollOne(appContext, bot, dcContext, store, result);
      } catch (Throwable t) {
        Log.w(TAG, "poll failed for bot " + bot.id, t);
      }
    }
    return result;
  }

  @WorkerThread
  private static int pollOne(@NonNull Context context,
                             @NonNull BotConfig bot,
                             @NonNull DcContext dcContext,
                             @NonNull BotStore store,
                             @NonNull PollResult result) throws IOException {
    TelegramApi api = new TelegramApi(bot.token);

    // Refresh getMe info if it's missing or older than a day.
    BotConfig refreshed = bot;
    if (bot.telegramBotId == 0 || bot.telegramName == null
        || (System.currentTimeMillis() - bot.lastPolledAtMs) > TimeUnit.DAYS.toMillis(1)) {
      try {
        JSONObject me = api.getMe();
        if (me != null) {
          JSONObject meResult = me.optJSONObject("result");
          if (meResult != null) {
            String username = meResult.optString("username", null);
            String fname = meResult.optString("first_name", null);
            long id = meResult.optLong("id", 0L);
            refreshed = bot.withMeta(username, fname, bot.avatarPath, id);
            store.upsert(refreshed);
          }
        }
      } catch (Throwable t) {
        Log.w(TAG, "getMe refresh failed for " + bot.id, t);
      }
    }

    long offset = refreshed.lastUpdateId == 0 ? 0L : refreshed.lastUpdateId + 1L;
    JSONObject resp;
    try {
      // Use a short timeout (0 s) for periodic polls so we never tie up
      // a WorkManager job for 25 seconds. Manual polls already get
      // routed through this same path, which is fine — Telegram will
      // return whatever is queued immediately.
      resp = api.getUpdates(offset, 0);
    } catch (IOException e) {
      Log.w(TAG, "getUpdates failed for " + bot.id + ": " + e.getMessage());
      return 0;
    }

    if (resp == null) return 0;
    JSONArray updates = resp.optJSONArray("result");
    if (updates == null || updates.length() == 0) {
      store.upsert(refreshed.withProgress(refreshed.lastUpdateId,
          System.currentTimeMillis()));
      return 0;
    }

    // Re-read the bot row right before constructing the dispatcher so we
    // pick up any flags the user toggled in the UI while getUpdates was
    // in flight (e.g. they enabled "manual review" between scheduling
    // this poll and the response coming back). Otherwise the queue
    // logic below would silently fall through to auto-publish using a
    // stale snapshot of BotConfig.
    BotConfig liveBeforeDispatch = store.get(refreshed.id);
    if (liveBeforeDispatch != null) refreshed = liveBeforeDispatch;

    TelegramMessageDispatcher dispatcher =
        new TelegramMessageDispatcher(context, api, refreshed, dcContext);

    // Before processing fresh getUpdates, publish anything that was
    // scheduled for this moment or earlier. We piggy-back on the
    // periodic poll cycle so users don't need a separate worker.
    publishDueScheduledEntries(context, dispatcher, refreshed, result);

    TelegramMessageDispatcher.BatchStats batch = dispatcher.handleUpdateBatchDetailed(updates);
    long newest = batch.newestUpdateId;
    int processed = updates.length();

    result.received += processed;
    result.queued += batch.queued;
    result.published += batch.published;
    result.dropped += batch.dropped;

    // Re-read once more before the progress write. Without this the
    // withProgress(...) copy would carry the old flags forward and
    // overwrite (e.g.) a manual-review toggle the user just made — the
    // classic read-modify-write race against UI edits.
    BotConfig liveAfterDispatch = store.get(refreshed.id);
    if (liveAfterDispatch != null) refreshed = liveAfterDispatch;

    if (newest < refreshed.lastUpdateId) newest = refreshed.lastUpdateId;
    store.upsert(refreshed.withProgress(newest, System.currentTimeMillis()));
    return processed;
  }

  // ---------------------------------------------------------------------
  //  async wrapper for UI buttons
  // ---------------------------------------------------------------------

  @AnyThread
  public static void pollAllAsync(@NonNull Context context, @NonNull Runnable onDone) {
    Context appContext = context.getApplicationContext();
    EXEC.execute(() -> {
      PollResult res = new PollResult();
      try {
        res = pollAllBlockingDetailed(appContext);
      } catch (Throwable t) {
        Log.w(TAG, "pollAllAsync failed", t);
      }
      final PollResult fres = res;
      MAIN.post(() -> {
        try { Toast.makeText(appContext, formatToast(fres), Toast.LENGTH_LONG).show(); }
        catch (Throwable ignored) {}
        try { onDone.run(); } catch (Throwable ignored) {}
      });
    });
  }

  /**
   * Walk this bot's queue, look for entries whose {@code publishAtMs}
   * has arrived, and publish each of them through the live dispatcher.
   * Designed to run on the polling worker thread — every periodic
   * 15-min tick acts as a "publish-due-posts" tick as well.
   */
  @WorkerThread
  private static void publishDueScheduledEntries(@NonNull Context ctx,
                                                 @NonNull TelegramMessageDispatcher dispatcher,
                                                 @NonNull BotConfig bot,
                                                 @NonNull PollResult result) {
    PendingPostStore store = new PendingPostStore(ctx.getApplicationContext());
    long now = System.currentTimeMillis();
    List<PendingPostStore.Entry> due = store.dueEntries(bot, now);
    for (PendingPostStore.Entry e : due) {
      try {
        if (e.albumParts != null && e.albumParts.length() > 1) {
          List<JSONObject> parts = new java.util.ArrayList<>(e.albumParts.length());
          for (int i = 0; i < e.albumParts.length(); i++) {
            JSONObject p = e.albumParts.optJSONObject(i);
            if (p != null) parts.add(p);
          }
          dispatcher.publishAlbum(parts);
          result.published += parts.size();
        } else {
          dispatcher.publishUpdate(e.raw);
          result.published += 1;
        }
        store.markPublished(bot, e.entryId);
      } catch (Throwable t) {
        Log.w(TAG, "scheduled publish failed for " + e.entryId, t);
      }
    }
  }

  private static String formatToast(@NonNull PollResult r) {
    if (r.received == 0) return "Нет новых сообщений у ботов";
    StringBuilder sb = new StringBuilder("Получено: ").append(r.received);
    if (r.published > 0) sb.append(" · в чат: ").append(r.published);
    if (r.queued > 0) sb.append(" · в очередь/журнал: ").append(r.queued);
    if (r.dropped > 0) sb.append(" · пропущено: ").append(r.dropped);
    return sb.toString();
  }
}
