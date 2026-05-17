package org.thoughtcrime.securesms.bots.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContext;

import org.json.JSONObject;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.bots.BotConfig;
import org.thoughtcrime.securesms.bots.BotStore;
import org.thoughtcrime.securesms.bots.PendingPostRenderer;
import org.thoughtcrime.securesms.bots.PendingPostStore;
import org.thoughtcrime.securesms.bots.TelegramApi;
import org.thoughtcrime.securesms.bots.TelegramMessageDispatcher;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.MessageMarkdown;

import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen preview of a single queued bot post.
 *
 * <p>Opened by tapping a card in {@link PendingPostsActivity}. Shows
 * the post the same way the recipient will see it once published:
 * the album collage / single photo on top, then the rendered Markdown
 * caption (bold / italic / quote / link), with a sticky action bar
 * that re-exposes the three queue actions — publish, schedule, drop —
 * so the user can decide without having to back out of the preview.
 *
 * <p>Photo download + composite happen on a background executor; while
 * that's in flight the screen shows a "Loading media…" placeholder so
 * the user can already read the caption and perform actions.
 */
public class PendingPostPreviewActivity extends PassphraseRequiredActionBarActivity {

  private static final String TAG = "PendingPostPreview";
  private static final String EXTRA_BOT_ID = "bot_id";
  private static final String EXTRA_ENTRY_ID = "entry_id";

  public static @NonNull Intent newIntent(@NonNull Context ctx,
                                          @NonNull String botId,
                                          @NonNull String entryId) {
    Intent i = new Intent(ctx, PendingPostPreviewActivity.class);
    i.putExtra(EXTRA_BOT_ID, botId);
    i.putExtra(EXTRA_ENTRY_ID, entryId);
    return i;
  }

  private final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  private final Handler MAIN = new Handler(Looper.getMainLooper());

  private String botId;
  private String entryId;
  private BotStore botStore;
  private PendingPostStore queue;

  private ImageView previewImage;
  private TextView previewBody;
  private TextView previewMeta;
  private TextView previewStatus;
  private Button publishBtn;
  private ImageButton scheduleBtn;
  private ImageButton deleteBtn;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_pending_post_preview);

    botId = getIntent().getStringExtra(EXTRA_BOT_ID);
    entryId = getIntent().getStringExtra(EXTRA_ENTRY_ID);
    if (botId == null || entryId == null) { finish(); return; }

    botStore = new BotStore(getApplicationContext());
    queue = new PendingPostStore(getApplicationContext());

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_bots_queue_preview_title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    previewImage = findViewById(R.id.preview_image);
    previewBody = findViewById(R.id.preview_body);
    previewMeta = findViewById(R.id.preview_meta);
    previewStatus = findViewById(R.id.preview_status);
    publishBtn = findViewById(R.id.preview_publish_btn);
    scheduleBtn = findViewById(R.id.preview_schedule_btn);
    deleteBtn = findViewById(R.id.preview_delete_btn);

    publishBtn.setOnClickListener(v -> publishNow());
    scheduleBtn.setOnClickListener(v -> showSchedulePicker());
    deleteBtn.setOnClickListener(v -> dropEntry());

    // Edge-to-edge: keep the action bar above the gesture pill / nav bar.
    final View root = findViewById(R.id.preview_root);
    final View actions = findViewById(R.id.preview_actions);
    final View scroll = findViewById(R.id.preview_scroll);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      actions.setPadding(
          actions.getPaddingLeft(),
          actions.getPaddingTop(),
          actions.getPaddingRight(),
          bars.bottom);
      // Make sure the body text never tucks under the action bar even
      // when the viewport is short and the user has scrolled to the
      // bottom. The bar's measured height isn't known until the first
      // layout pass, so we re-apply scroll padding once it is.
      actions.post(() -> scroll.setPadding(
          scroll.getPaddingLeft(),
          scroll.getPaddingTop(),
          scroll.getPaddingRight(),
          actions.getHeight() + bars.bottom));
      return windowInsets;
    });
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) { finish(); return true; }
    return super.onOptionsItemSelected(item);
  }

  private @NonNull PendingPostStore.Entry requireEntry() {
    PendingPostStore.Entry found = findEntry();
    if (found == null) {
      // The entry was removed from another screen (e.g. the user
      // dropped it from the list while this preview was in the
      // background). Abort gracefully so we never operate on a
      // stale snapshot.
      throw new IllegalStateException("entry " + entryId + " no longer in queue");
    }
    return found;
  }

  private PendingPostStore.Entry findEntry() {
    BotConfig bot = botStore.get(botId);
    List<PendingPostStore.Entry> entries = bot != null
        ? queue.list(bot)
        : queue.list(botId);
    for (PendingPostStore.Entry e : entries) {
      if (entryId.equals(e.entryId)) return e;
    }
    return null;
  }

  // ---------------------------------------------------------------
  //  rendering
  // ---------------------------------------------------------------

  private void refresh() {
    PendingPostStore.Entry entry = findEntry();
    if (entry == null) { finish(); return; }

    bindMeta(entry);
    bindBody(entry);
    bindActions(entry);
    loadImageAsync(entry);
  }

  private void bindMeta(@NonNull PendingPostStore.Entry e) {
    String when = DateFormat.getTimeFormat(getApplicationContext())
        .format(new Date(e.receivedAtMs));
    String media = PendingPostRenderer.describeMedia(e);
    previewMeta.setText(media.isEmpty() ? when : when + " · " + media);

    long now = System.currentTimeMillis();
    boolean scheduled = !e.published && e.publishAtMs > now;
    boolean overdue = !e.published && e.publishAtMs > 0 && e.publishAtMs <= now;
    if (e.published) {
      previewStatus.setText(R.string.bmchat_bots_queue_status_published);
      previewStatus.setBackgroundResource(R.drawable.bot_status_badge_published);
    } else if (scheduled) {
      String at = DateFormat.getTimeFormat(getApplicationContext())
          .format(new Date(e.publishAtMs));
      previewStatus.setText(getString(R.string.bmchat_bots_queue_status_scheduled_fmt, at));
      previewStatus.setBackgroundResource(R.drawable.bot_status_badge_scheduled);
    } else if (overdue) {
      previewStatus.setText(R.string.bmchat_bots_queue_status_due);
      previewStatus.setBackgroundResource(R.drawable.bot_status_badge_pending);
    } else {
      previewStatus.setText(R.string.bmchat_bots_queue_status_pending);
      previewStatus.setBackgroundResource(R.drawable.bot_status_badge_pending);
    }
  }

  private void bindBody(@NonNull PendingPostStore.Entry e) {
    String body = PendingPostRenderer.renderBody(e.raw);
    if (body.isEmpty() && e.albumSize() > 1) {
      body = getString(R.string.bmchat_bots_queue_preview_album_fmt, e.albumSize());
    }
    SpannableStringBuilder spanned = MessageMarkdown.apply(body);
    previewBody.setText(spanned);
  }

  private void bindActions(@NonNull PendingPostStore.Entry e) {
    // Once a post has been mirrored, "publish" / "schedule" no longer
    // make sense — only "drop from history" stays.
    boolean done = e.published;
    publishBtn.setVisibility(done ? View.GONE : View.VISIBLE);
    scheduleBtn.setVisibility(done ? View.GONE : View.VISIBLE);
    long now = System.currentTimeMillis();
    boolean scheduled = !done && e.publishAtMs > now;
    publishBtn.setText(scheduled
        ? R.string.bmchat_bots_queue_publish_now
        : R.string.bmchat_bots_queue_publish);
  }

  /**
   * Composite photos on a background thread and post the resulting
   * bitmap into the preview ImageView. We deliberately keep the
   * download isolated from the main caption render so the user can
   * already read the body + tap actions while the network is busy.
   */
  private void loadImageAsync(@NonNull PendingPostStore.Entry e) {
    BotConfig bot = botStore.get(botId);
    if (bot == null) {
      previewImage.setVisibility(View.GONE);
      return;
    }
    // Pre-show a placeholder so users with a slow connection know
    // a photo is being fetched rather than missing entirely.
    if (e.albumSize() > 1
        || ("photo".equals(e.mediaKind))) {
      previewImage.setVisibility(View.VISIBLE);
      previewImage.setImageDrawable(null);
      previewImage.setContentDescription(getString(R.string.bmchat_bots_queue_preview_loading));
    } else {
      previewImage.setVisibility(View.GONE);
    }

    EXEC.execute(() -> {
      try {
        TelegramApi api = new TelegramApi(bot.token);
        File composite = PendingPostRenderer.composePreviewImage(
            getApplicationContext(), api, bot, e);
        if (composite == null) {
          MAIN.post(() -> previewImage.setVisibility(View.GONE));
          return;
        }
        // Sample down to fit the screen — full-resolution album
        // composites can easily reach 4 MP and stall the UI thread
        // on decode for a couple hundred ms.
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(composite.getAbsolutePath(), bounds);
        int sample = 1;
        int target = 1600;
        int longer = Math.max(bounds.outWidth, bounds.outHeight);
        while (longer / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(composite.getAbsolutePath(), opts);
        if (bm == null) {
          MAIN.post(() -> previewImage.setVisibility(View.GONE));
          return;
        }
        MAIN.post(() -> {
          previewImage.setVisibility(View.VISIBLE);
          previewImage.setImageBitmap(bm);
        });
      } catch (Throwable t) {
        Log.w(TAG, "loadImageAsync failed", t);
        MAIN.post(() -> previewImage.setVisibility(View.GONE));
      }
    });
  }

  // ---------------------------------------------------------------
  //  actions (mirror PendingPostsActivity, scoped to the single entry)
  // ---------------------------------------------------------------

  private void publishNow() {
    PendingPostStore.Entry entry;
    try { entry = requireEntry(); }
    catch (IllegalStateException ex) { finish(); return; }
    if (entry.published) return;

    BotConfig bot = botStore.get(botId);
    if (bot == null) { finish(); return; }
    EXEC.execute(() -> {
      boolean ok = false;
      try {
        DcAccounts accounts = DcHelper.getAccounts(getApplicationContext());
        DcContext dc = accounts.getAccount(bot.dcAccountId);
        if (dc != null) {
          TelegramApi api = new TelegramApi(bot.token);
          TelegramMessageDispatcher dispatcher =
              new TelegramMessageDispatcher(getApplicationContext(), api, bot, dc);
          if (entry.albumParts != null && entry.albumParts.length() > 1) {
            java.util.List<JSONObject> parts = new java.util.ArrayList<>();
            for (int i = 0; i < entry.albumParts.length(); i++) {
              JSONObject p = entry.albumParts.optJSONObject(i);
              if (p != null) parts.add(p);
            }
            dispatcher.publishAlbum(parts);
          } else {
            dispatcher.publishUpdate(entry.raw);
          }
          queue.markPublished(bot, entry.entryId);
          ok = true;
        }
      } catch (Throwable t) {
        Log.w(TAG, "publishNow failed for " + entry.entryId, t);
      }
      final boolean fOk = ok;
      MAIN.post(() -> {
        Toast.makeText(this,
            fOk ? R.string.bmchat_bots_queue_published_one
                : R.string.bmchat_bots_queue_publish_failed,
            Toast.LENGTH_SHORT).show();
        if (fOk) finish();
        else refresh();
      });
    });
  }

  private void dropEntry() {
    new AlertDialog.Builder(this)
        .setMessage(R.string.bmchat_bots_queue_clear_confirm)
        .setPositiveButton(R.string.bmchat_bots_queue_drop, (d, w) -> {
          BotConfig bot = botStore.get(botId);
          if (bot != null) queue.remove(bot, entryId);
          else queue.remove(botId, entryId);
          Toast.makeText(this, R.string.bmchat_bots_queue_dropped, Toast.LENGTH_SHORT).show();
          finish();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Date+time picker that mirrors the schedule sheet in
   * {@link PendingPostsActivity}. Kept as a private copy on this
   * screen — extracting a shared helper isn't worth the indirection
   * for two ~40-line methods, and inlining keeps the dialog flow
   * self-contained.
   */
  private void showSchedulePicker() {
    PendingPostStore.Entry entry;
    try { entry = requireEntry(); }
    catch (IllegalStateException ex) { finish(); return; }
    if (entry.published) return;

    Calendar c = Calendar.getInstance();
    long base = entry.publishAtMs > System.currentTimeMillis()
        ? entry.publishAtMs
        : System.currentTimeMillis() + 5 * 60 * 1000L;
    c.setTimeInMillis(base);

    DatePickerDialog dpd = new DatePickerDialog(this,
        (datePicker, year, month, day) -> {
          Calendar dc = Calendar.getInstance();
          dc.setTimeInMillis(base);
          dc.set(Calendar.YEAR, year);
          dc.set(Calendar.MONTH, month);
          dc.set(Calendar.DAY_OF_MONTH, day);
          showTimePicker(entry, dc);
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
    dpd.getDatePicker().setMinDate(System.currentTimeMillis() - 60_000);
    dpd.show();
  }

  private void showTimePicker(@NonNull PendingPostStore.Entry entry, @NonNull Calendar dateOnly) {
    int hour = dateOnly.get(Calendar.HOUR_OF_DAY);
    int minute = dateOnly.get(Calendar.MINUTE);
    boolean is24h = DateFormat.is24HourFormat(this);
    new TimePickerDialog(this, (tp, h, m) -> {
      Calendar when = (Calendar) dateOnly.clone();
      when.set(Calendar.HOUR_OF_DAY, h);
      when.set(Calendar.MINUTE, m);
      when.set(Calendar.SECOND, 0);
      when.set(Calendar.MILLISECOND, 0);
      long ms = when.getTimeInMillis();
      if (ms < System.currentTimeMillis()) {
        Toast.makeText(this, R.string.bmchat_bots_queue_schedule_in_past, Toast.LENGTH_LONG).show();
        return;
      }
      BotConfig bot = botStore.get(botId);
      if (bot != null) queue.schedule(bot, entry.entryId, ms);
      else queue.schedule(botId, entry.entryId, ms);
      Toast.makeText(this,
          getString(R.string.bmchat_bots_queue_scheduled_fmt,
              DateFormat.getDateFormat(this).format(when.getTime()),
              DateFormat.getTimeFormat(this).format(when.getTime())),
          Toast.LENGTH_LONG).show();
      refresh();
    }, hour, minute, is24h).show();
  }
}
