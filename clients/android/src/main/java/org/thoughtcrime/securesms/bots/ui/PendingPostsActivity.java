package org.thoughtcrime.securesms.bots.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ListView;
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

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.bots.BotConfig;
import org.thoughtcrime.securesms.bots.BotStore;
import org.thoughtcrime.securesms.bots.PendingPostStore;
import org.thoughtcrime.securesms.bots.TelegramApi;
import org.thoughtcrime.securesms.bots.TelegramMessageDispatcher;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Per-bot moderation queue ("Очередь сообщений").
 *
 * <p>Shown when {@link BotConfig#manualReview} is enabled — every Telegram
 * update arriving for that bot is parked in {@link PendingPostStore}
 * instead of being mirrored into BMChat chats. This screen lists the
 * parked entries with a short preview, lets the user publish or drop
 * each one, and offers a top-bar action to clear or publish all at once.
 */
public class PendingPostsActivity extends PassphraseRequiredActionBarActivity {

  private static final String TAG = "PendingPostsActivity";
  private static final String EXTRA_BOT_ID = "bot_id";

  public static Intent newIntent(@NonNull Context ctx, @NonNull String botId) {
    Intent i = new Intent(ctx, PendingPostsActivity.class);
    i.putExtra(EXTRA_BOT_ID, botId);
    return i;
  }

  private final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  private final Handler MAIN = new Handler(Looper.getMainLooper());

  private String botId;
  private BotStore botStore;
  private PendingPostStore queue;
  private PendingAdapter adapter;
  private ListView listView;
  private View emptyView;
  private Button emptyToggle;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_pending_posts);

    botId = getIntent().getStringExtra(EXTRA_BOT_ID);
    if (botId == null) { finish(); return; }
    botStore = new BotStore(getApplicationContext());
    queue = new PendingPostStore(getApplicationContext());

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_bots_queue_title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    listView = findViewById(R.id.pending_list);
    emptyView = findViewById(R.id.empty_view);
    emptyToggle = findViewById(R.id.empty_toggle_manual);
    emptyToggle.setOnClickListener(v -> toggleManualReview());

    adapter = new PendingAdapter();
    listView.setAdapter(adapter);

    // Edge-to-edge: respect the system navigation bar so the per-card
    // action icons (publish/schedule/delete) do not slip behind the
    // gesture pill or 3-button bar — that's the regression visible on
    // screenshot 3 from the user's report. We add the bottom inset as
    // padding (clipToPadding=false in the layout keeps the scroll
    // visually flush). Status-bar inset goes to the toolbar so its
    // title is not clipped on devices with a translucent status bar.
    final View root = findViewById(R.id.pending_root);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      listView.setPadding(
          listView.getPaddingLeft(),
          listView.getPaddingTop(),
          listView.getPaddingRight(),
          bars.bottom);
      // Empty state should also clear the bar so the "Включить ручную
      // модерацию" button is fully tappable.
      emptyView.setPadding(
          emptyView.getPaddingLeft(),
          emptyView.getPaddingTop(),
          emptyView.getPaddingRight(),
          bars.bottom);
      return windowInsets;
    });
  }

  /**
   * Convenience shortcut from the empty state: lets the user enable
   * (or disable) {@link BotConfig#manualReview} without going back to
   * the bot list. Saves them from having to learn that the toggle
   * lives in the parent screen.
   */
  private void toggleManualReview() {
    BotConfig bot = botStore.get(botId);
    if (bot == null) return;
    boolean newState = !bot.manualReview;
    botStore.upsert(bot.withManualReview(newState));
    Toast.makeText(this,
        newState ? R.string.bmchat_bots_manual_review_on
                 : R.string.bmchat_bots_manual_review_off,
        Toast.LENGTH_LONG).show();
    refresh();
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.bots_pending, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    boolean any = adapter != null && adapter.getCount() > 0;
    MenuItem publishAll = menu.findItem(R.id.menu_pending_publish_all);
    MenuItem clearAll = menu.findItem(R.id.menu_pending_clear);
    if (publishAll != null) publishAll.setVisible(any);
    if (clearAll != null) clearAll.setVisible(any);
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == android.R.id.home) { finish(); return true; }
    if (id == R.id.menu_pending_publish_all) { publishAll(); return true; }
    if (id == R.id.menu_pending_clear) { confirmClearAll(); return true; }
    return super.onOptionsItemSelected(item);
  }

  // ---------------------------------------------------------------------
  //  data refresh
  // ---------------------------------------------------------------------

  private void refresh() {
    BotConfig bot = botStore.get(botId);
    List<PendingPostStore.Entry> data = bot != null
        ? queue.list(bot)
        : queue.list(botId);
    adapter.setData(data);
    boolean empty = data.isEmpty();
    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    listView.setVisibility(empty ? View.GONE : View.VISIBLE);

    if (emptyToggle != null && bot != null) {
      emptyToggle.setText(bot.manualReview
          ? R.string.bmchat_bots_manual_review_disable
          : R.string.bmchat_bots_manual_review_enable);
    }

    invalidateOptionsMenu();
  }

  // ---------------------------------------------------------------------
  //  actions
  // ---------------------------------------------------------------------

  private void publishAll() {
    new AlertDialog.Builder(this)
        .setMessage(R.string.bmchat_bots_queue_publish_all_confirm)
        .setPositiveButton(R.string.bmchat_bots_queue_publish, (d, w) -> {
          BotConfig bot = botStore.get(botId);
          List<PendingPostStore.Entry> data = bot != null
              ? queue.list(bot)
              : queue.list(botId);
          for (PendingPostStore.Entry e : data) publishEntryAsync(e, false);
          MAIN.postDelayed(this::refresh, 500);
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void confirmClearAll() {
    new AlertDialog.Builder(this)
        .setMessage(R.string.bmchat_bots_queue_clear_confirm)
        .setPositiveButton(R.string.delete, (d, w) -> {
          BotConfig bot = botStore.get(botId);
          if (bot != null) queue.clear(bot);
          else queue.clear(botId);
          refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Publish one queued entry. Runs the dispatcher off the UI thread so
   * the SMTP/IMAP-side {@code dc_send_msg} doesn't block input. When
   * {@code interactive} is true, also shows a toast on success/failure.
   *
   * <p>Album entries (multiple parts sharing a media_group_id) are
   * published as one — every part is sent in order so the receiver
   * sees the same back-to-back sequence Telegram delivered.
   */
  private void publishEntryAsync(@NonNull PendingPostStore.Entry entry, boolean interactive) {
    BotConfig bot = botStore.get(botId);
    if (bot == null) return;
    if (entry.published) return; // never re-send
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
          // Keep the entry in the list as a "history" item, just flip
          // its status to "опубликовано" — the user can still drop it
          // from history with the third button.
          queue.markPublished(bot, entry.entryId);
          ok = true;
        }
      } catch (Throwable t) {
        Log.w(TAG, "publishEntryAsync failed for " + entry.entryId, t);
      }
      final boolean fOk = ok;
      MAIN.post(() -> {
        if (interactive) {
          Toast.makeText(this,
              fOk ? R.string.bmchat_bots_queue_published_one
                  : R.string.bmchat_bots_queue_publish_failed,
              Toast.LENGTH_SHORT).show();
        }
        refresh();
      });
    });
  }

  // ---------------------------------------------------------------------
  //  list adapter
  // ---------------------------------------------------------------------

  private final class PendingAdapter extends BaseAdapter {
    private List<PendingPostStore.Entry> data = java.util.Collections.emptyList();

    void setData(List<PendingPostStore.Entry> d) {
      this.data = d;
      notifyDataSetChanged();
    }

    @Override public int getCount() { return data.size(); }
    @Override public PendingPostStore.Entry getItem(int p) { return data.get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View v = convertView;
      if (v == null) {
        v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_pending_post, parent, false);
      }
      PendingPostStore.Entry e = data.get(position);

      String when = DateFormat.getTimeFormat(getApplicationContext())
          .format(new Date(e.receivedAtMs));
      StringBuilder meta = new StringBuilder(when);
      int albumSize = e.albumSize();
      if (albumSize > 1) {
        meta.append(" · ").append(getString(R.string.bmchat_bots_queue_album_fmt, albumSize));
      } else if (e.mediaKind != null && !e.mediaKind.isEmpty()) {
        meta.append(" · ").append(getString(R.string.bmchat_bots_queue_media_fmt, e.mediaKind));
      }
      ((TextView) v.findViewById(R.id.pending_meta)).setText(meta.toString());

      TextView status = v.findViewById(R.id.pending_status);
      long now = System.currentTimeMillis();
      boolean scheduled = !e.published && e.publishAtMs > now;
      boolean overdue = !e.published && e.publishAtMs > 0 && e.publishAtMs <= now;
      // Pick a colour bucket per state. The drawable is set rather than
      // tinted so we don't depend on backgroundTint being honoured on
      // every Android version we ship.
      if (e.published) {
        status.setText(R.string.bmchat_bots_queue_status_published);
        status.setBackgroundResource(R.drawable.bot_status_badge_published);
      } else if (scheduled) {
        String at = DateFormat.getTimeFormat(getApplicationContext())
            .format(new Date(e.publishAtMs));
        status.setText(getString(R.string.bmchat_bots_queue_status_scheduled_fmt, at));
        status.setBackgroundResource(R.drawable.bot_status_badge_scheduled);
      } else if (overdue) {
        status.setText(R.string.bmchat_bots_queue_status_due);
        status.setBackgroundResource(R.drawable.bot_status_badge_pending);
      } else {
        status.setText(R.string.bmchat_bots_queue_status_pending);
        status.setBackgroundResource(R.drawable.bot_status_badge_pending);
      }

      String preview = e.preview;
      if (preview == null || preview.isEmpty()) {
        if (albumSize > 1) {
          preview = getString(R.string.bmchat_bots_queue_album_preview_fmt, albumSize);
        } else {
          preview = getString(R.string.bmchat_bots_queue_no_preview);
        }
      }
      TextView previewView = v.findViewById(R.id.pending_preview);
      previewView.setText(preview);
      previewView.setAlpha(e.published ? 0.55f : 1f);

      ImageButton publishBtn = v.findViewById(R.id.pending_publish_btn);
      ImageButton scheduleBtn = v.findViewById(R.id.pending_schedule_btn);
      ImageButton deleteBtn = v.findViewById(R.id.pending_delete_btn);

      if (e.published) {
        publishBtn.setVisibility(View.GONE);
        scheduleBtn.setVisibility(View.GONE);
      } else {
        publishBtn.setVisibility(View.VISIBLE);
        scheduleBtn.setVisibility(View.VISIBLE);
        publishBtn.setContentDescription(getString(scheduled
            ? R.string.bmchat_bots_queue_publish_now
            : R.string.bmchat_bots_queue_publish));
        scheduleBtn.setContentDescription(getString(scheduled
            ? R.string.bmchat_bots_queue_reschedule
            : R.string.bmchat_bots_queue_schedule));
      }

      publishBtn.setOnClickListener(view -> publishEntryAsync(e, true));
      scheduleBtn.setOnClickListener(view -> showSchedulePicker(e));
      deleteBtn.setOnClickListener(view -> {
        BotConfig bot = botStore.get(botId);
        if (bot != null) queue.remove(bot, e.entryId);
        else queue.remove(botId, e.entryId);
        Toast.makeText(PendingPostsActivity.this,
            R.string.bmchat_bots_queue_dropped,
            Toast.LENGTH_SHORT).show();
        refresh();
      });

      // Tap anywhere on the card body (not on the icon buttons) to
      // open the full-screen preview. The card root has clickable=true
      // and a selectableItemBackground so the touch state is visible.
      View card = v.findViewById(R.id.pending_card);
      if (card != null) {
        card.setOnClickListener(view -> startActivity(
            PendingPostPreviewActivity.newIntent(
                PendingPostsActivity.this, botId, e.entryId)));
      }
      return v;
    }
  }

  /**
   * Open a date picker followed by a time picker. The combined value is
   * stored as {@link PendingPostStore.Entry#publishAtMs}; the next
   * polling cycle (every 15 min) will pick the entry up. We intentionally
   * piggy-back on the existing periodic worker rather than spinning up
   * a separate AlarmManager / ScheduledExecutor — the user's mental
   * model is "post within ~15 min of the chosen time" which fits the
   * worker cadence and keeps battery cost flat.
   */
  private void showSchedulePicker(@NonNull PendingPostStore.Entry entry) {
    Calendar c = Calendar.getInstance();
    long base = entry.publishAtMs > System.currentTimeMillis()
        ? entry.publishAtMs
        : System.currentTimeMillis() + 5 * 60 * 1000L; // default +5 min
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
