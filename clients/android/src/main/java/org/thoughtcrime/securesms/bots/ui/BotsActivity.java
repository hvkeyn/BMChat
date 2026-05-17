package org.thoughtcrime.securesms.bots.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.bots.BotConfig;
import org.thoughtcrime.securesms.bots.BotContactFactory;
import org.thoughtcrime.securesms.bots.BotPollManager;
import org.thoughtcrime.securesms.bots.BotStore;
import org.thoughtcrime.securesms.bots.PendingPostStore;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Main hub for connected Telegram bots: lists every {@link BotConfig},
 * exposes a FAB to open {@link AddBotActivity}, and a menu item to
 * trigger a manual poll cycle ("Проверить сейчас").
 */
public class BotsActivity extends PassphraseRequiredActionBarActivity {

  public static Intent newIntent(Context ctx) {
    return new Intent(ctx, BotsActivity.class);
  }

  private BotStore store;
  private BotsAdapter adapter;
  private ListView listView;
  private View emptyView;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_bots_list);

    store = new BotStore(getApplicationContext());

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_bots_title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    listView = findViewById(R.id.bots_list);
    emptyView = findViewById(R.id.empty_view);

    adapter = new BotsAdapter();
    listView.setAdapter(adapter);
    listView.setOnItemClickListener((parent, view, position, id) ->
        showBotMenu(adapter.getItem(position)));

    final FloatingActionButton fab = findViewById(R.id.fab_add_bot);
    fab.setOnClickListener(v -> startActivity(AddBotActivity.newIntent(this)));

    // Edge-to-edge: keep the last bot card and the "+" FAB above the
    // system gesture bar / 3-button bar (regression visible on
    // screenshot 4 from May 9 — the BMChatBot card almost touches the
    // nav pill on Samsung One UI).
    final View root = findViewById(R.id.bots_root);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      listView.setPadding(
          listView.getPaddingLeft(),
          listView.getPaddingTop(),
          listView.getPaddingRight(),
          bars.bottom);
      emptyView.setPadding(
          emptyView.getPaddingLeft(),
          emptyView.getPaddingTop(),
          emptyView.getPaddingRight(),
          bars.bottom);
      ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
      int baseMargin = (int) (16 * getResources().getDisplayMetrics().density);
      lp.bottomMargin = baseMargin + bars.bottom;
      fab.setLayoutParams(lp);
      return windowInsets;
    });

    BotPollManager.ensurePeriodicScheduled(getApplicationContext());
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.bots, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == android.R.id.home) {
      finish();
      return true;
    }
    if (id == R.id.menu_bots_check_now) {
      BotPollManager.pollAllAsync(getApplicationContext(), this::refresh);
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void refresh() {
    adapter.setData(store.getAll());
    boolean empty = adapter.getCount() == 0;
    emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
    listView.setVisibility(empty ? View.GONE : View.VISIBLE);
  }

  // ---------------------------------------------------------------------
  //  per-bot context menu
  // ---------------------------------------------------------------------

  private void showBotMenu(BotConfig bot) {
    final boolean hasAttachments = !bot.attachedChatIds.isEmpty();
    final int queueSize = new PendingPostStore(getApplicationContext()).pendingCount(bot);

    final List<String> items = new ArrayList<>();
    final List<Integer> codes = new ArrayList<>();
    items.add(getString(R.string.bmchat_bots_open_home));     codes.add(0);
    items.add(getString(R.string.bmchat_bots_add_to_chat));   codes.add(1);
    if (hasAttachments) {
      items.add(getString(R.string.bmchat_bots_remove_from_chat)); codes.add(5);
    }
    items.add(getString(bot.manualReview
        ? R.string.bmchat_bots_manual_review_disable
        : R.string.bmchat_bots_manual_review_enable));        codes.add(6);
    items.add(getString(queueSize > 0
            ? R.string.bmchat_bots_open_queue_n_fmt
            : R.string.bmchat_bots_open_queue,
        queueSize));                                          codes.add(7);
    items.add(bot.paused
        ? getString(R.string.bmchat_bots_resume)
        : getString(R.string.bmchat_bots_pause));             codes.add(2);
    items.add(getString(R.string.bmchat_bots_check_now));     codes.add(3);
    items.add(getString(R.string.bmchat_bots_remove));        codes.add(4);

    new AlertDialog.Builder(this)
        .setTitle(bot.displayName())
        .setItems(items.toArray(new String[0]), (dialog, which) -> {
          switch (codes.get(which)) {
            case 0: openHomeChat(bot); break;
            case 1: showAddToChatPicker(bot); break;
            case 2:
              store.upsert(bot.withPaused(!bot.paused));
              refresh();
              break;
            case 3:
              BotPollManager.pollAllAsync(getApplicationContext(), this::refresh);
              break;
            case 4: confirmRemoveBot(bot); break;
            case 5: showRemoveFromChatPicker(bot); break;
            case 6:
              store.upsert(bot.withManualReview(!bot.manualReview));
              android.widget.Toast.makeText(this,
                  !bot.manualReview
                      ? R.string.bmchat_bots_manual_review_on
                      : R.string.bmchat_bots_manual_review_off,
                  android.widget.Toast.LENGTH_SHORT).show();
              refresh();
              break;
            case 7:
              startActivity(PendingPostsActivity.newIntent(this, bot.id));
              break;
            default: break;
          }
        })
        .show();
  }

  private void openHomeChat(BotConfig bot) {
    int chatId = bot.targetDcChatId;
    if (chatId <= 0 && bot.botContactId > 0) {
      try {
        chatId = DcHelper.getContext(this).getChatIdByContactId(bot.botContactId);
      } catch (Throwable ignored) {}
    }
    if (chatId <= 0) {
      new AlertDialog.Builder(this)
          .setMessage(R.string.bmchat_bots_no_home_chat)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }
    Intent intent = new Intent(this,
        org.thoughtcrime.securesms.ConversationActivity.class);
    intent.putExtra(org.thoughtcrime.securesms.ConversationActivity.CHAT_ID_EXTRA, chatId);
    startActivity(intent);
  }

  /**
   * Inline chat-picker for "Add bot to chat…".
   *
   * <p>Shows every multi-user chat the user can post into — group, mailing
   * list, broadcast (channel) — regardless of encryption. The pseudo-contact
   * is <em>not</em> added as a real Delta-Chat member (Delta-core rejects
   * key-less contacts in encrypted rooms); instead the chat id is recorded
   * locally in {@link BotConfig#attachedChatIds} and {@link
   * org.thoughtcrime.securesms.bots.TelegramMessageDispatcher} mirrors
   * Telegram updates into it through the user's own SMTP credentials,
   * prefixed with a "🤖 BotName" header.
   *
   * <p>1:1 chats are excluded because Telegram bots can never be added to
   * private conversations between two real users — only to groups and
   * channels.
   */
  private void showAddToChatPicker(BotConfig bot) {
    DcContext dc = DcHelper.getContext(this);
    final List<Integer> ids = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    try {
      DcChatlist list = dc.getChatlist(0, null, 0);
      int n = list.getCnt();
      for (int i = 0; i < n; i++) {
        int chatId = list.getChatId(i);
        if (chatId <= 0) continue;
        DcChat chat = list.getChat(i);
        if (chat == null) continue;
        if (chat.isContactRequest()) continue;
        if (!chat.canSend()) continue;
        if (!chat.isMultiUser()) continue;
        if (chat.isDeviceTalk() || chat.isSelfTalk()) continue;
        if (chat.getId() == bot.targetDcChatId) continue;
        if (bot.attachedChatIds.contains(chatId)) continue;
        ids.add(chatId);
        String name = chat.getName();
        if (chat.isOutBroadcast() || chat.isInBroadcast()) {
          name += " · " + getString(R.string.bmchat_bots_label_channel);
        }
        labels.add(name);
      }
    } catch (Throwable t) {
      // best-effort — fall through to "no chats" branch below
    }

    if (ids.isEmpty()) {
      new AlertDialog.Builder(this)
          .setMessage(R.string.bmchat_bots_no_eligible_chats)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }

    String[] arr = labels.toArray(new String[0]);
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_bots_pick_chat_title)
        .setItems(arr, (d, which) -> {
          int chatId = ids.get(which);
          try {
            store.upsert(bot.withAttachedChat(chatId));
            android.widget.Toast.makeText(this,
                getString(R.string.bmchat_bots_added_to_fmt, labels.get(which)),
                android.widget.Toast.LENGTH_SHORT).show();
            refresh();
            BotPollManager.pollAllAsync(getApplicationContext(), this::refresh);
          } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                R.string.bmchat_bots_add_failed,
                android.widget.Toast.LENGTH_LONG).show();
          }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  /**
   * Lists every chat the bot is currently attached to (1:1 home + locally
   * tracked group/channel attachments) and lets the user detach the bot
   * from any of them. Detaching just removes the entry from
   * {@link BotConfig#attachedChatIds}; the chat itself is not modified.
   */
  private void showRemoveFromChatPicker(BotConfig bot) {
    DcContext dc = DcHelper.getContext(this);
    final List<Integer> ids = new ArrayList<>();
    final List<String> labels = new ArrayList<>();
    for (Integer chatId : bot.attachedChatIds) {
      if (chatId == null || chatId <= 0) continue;
      try {
        DcChat chat = dc.getChat(chatId);
        if (chat == null || chat.getId() <= 0) continue;
        ids.add(chatId);
        String name = chat.getName();
        if (chat.isOutBroadcast() || chat.isInBroadcast()) {
          name += " · " + getString(R.string.bmchat_bots_label_channel);
        }
        labels.add(name);
      } catch (Throwable ignored) {}
    }

    if (ids.isEmpty()) {
      new AlertDialog.Builder(this)
          .setMessage(R.string.bmchat_bots_not_attached_anywhere)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }

    String[] arr = labels.toArray(new String[0]);
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_bots_remove_from_chat_title)
        .setItems(arr, (d, which) -> {
          int chatId = ids.get(which);
          store.upsert(bot.withoutAttachedChat(chatId));
          android.widget.Toast.makeText(this,
              getString(R.string.bmchat_bots_removed_from_fmt, labels.get(which)),
              android.widget.Toast.LENGTH_SHORT).show();
          refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void confirmRemoveBot(BotConfig bot) {
    new AlertDialog.Builder(this)
        .setMessage(getString(R.string.bmchat_bots_remove_confirm_fmt, bot.displayName()))
        .setPositiveButton(R.string.delete, (d, w) -> {
          BotContactFactory.onBotRemoved(getApplicationContext(), bot);
          store.remove(bot.id);
          refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  // ---------------------------------------------------------------------
  //  list adapter
  // ---------------------------------------------------------------------

  private final class BotsAdapter extends BaseAdapter {
    private List<BotConfig> data = java.util.Collections.emptyList();

    void setData(List<BotConfig> d) {
      this.data = d;
      notifyDataSetChanged();
    }

    @Override public int getCount() { return data.size(); }
    @Override public BotConfig getItem(int position) { return data.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View v = convertView;
      if (v == null) {
        v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_bot, parent, false);
      }
      BotConfig bot = data.get(position);
      ((TextView) v.findViewById(R.id.bot_name))
          .setText("🤖 " + bot.displayName());

      ImageView avatarIv = v.findViewById(R.id.bot_avatar);
      if (avatarIv != null) {
        Bitmap circle = loadCircularAvatar(bot.avatarPath);
        if (circle != null) {
          avatarIv.setImageBitmap(circle);
        } else {
          avatarIv.setImageResource(R.drawable.icon_notification);
        }
      }

      int memberCount = countChatMemberships(bot);
      PendingPostStore store = new PendingPostStore(getApplicationContext());
      int pendingCount = store.pendingCount(bot);
      int totalCount = store.count(bot);
      String meta = bot.telegramUsername != null ? "@" + bot.telegramUsername : "";
      if (memberCount > 0) {
        if (!meta.isEmpty()) meta += " · ";
        meta += getResources().getQuantityString(
            R.plurals.bmchat_bots_member_of_n_chats, memberCount, memberCount);
      }
      // Show only the pending size in the meta line — the previous "count
      // everything" badge made the chip stay at "2" even after every post
      // had been published, which is the regression visible on screenshots
      // 3 and 4. The full history is still reachable through the "Очередь
      // сообщений" screen.
      if (!meta.isEmpty()) meta += " · ";
      if (pendingCount > 0) {
        meta += getString(R.string.bmchat_bots_queue_count_fmt, pendingCount);
      } else if (totalCount > 0) {
        meta += getString(R.string.bmchat_bots_queue_count_history_fmt, totalCount);
      } else {
        meta += getString(R.string.bmchat_bots_queue_count_empty);
      }
      ((TextView) v.findViewById(R.id.bot_meta)).setText(meta);

      String statusText;
      if (bot.paused) {
        statusText = getString(R.string.bmchat_bots_status_paused);
      } else if (bot.lastPolledAtMs <= 0) {
        statusText = getString(R.string.bmchat_bots_status_never_polled);
      } else {
        String ts = DateFormat.getTimeFormat(getApplicationContext())
            .format(new Date(bot.lastPolledAtMs));
        statusText = getString(R.string.bmchat_bots_status_last_check_fmt, ts);
      }
      ((TextView) v.findViewById(R.id.bot_status)).setText(statusText);
      return v;
    }

    /**
     * Count chats this bot is wired into: the implicit 1:1 home chat (if
     * it still exists) plus every locally attached multi-user chat. Used
     * for the "Bot is in N chats" subtitle in the bot list.
     */
    private int countChatMemberships(BotConfig bot) {
      int count = 0;
      try {
        DcAccounts accounts = DcHelper.getAccounts(BotsActivity.this);
        DcContext dc = accounts.getAccount(bot.dcAccountId);
        if (dc == null) return 0;
        if (bot.botContactId > 0) {
          if (dc.getChatIdByContactId(bot.botContactId) > 0) count++;
        } else if (bot.targetDcChatId > 0) {
          DcChat home = dc.getChat(bot.targetDcChatId);
          if (home != null && home.getId() > 0) count++;
        }
        for (Integer attached : bot.attachedChatIds) {
          if (attached == null || attached <= 0) continue;
          DcChat chat = dc.getChat(attached);
          if (chat != null && chat.getId() > 0) count++;
        }
      } catch (Throwable ignored) {}
      return count;
    }

    /**
     * Render the cached bot avatar (downloaded from Telegram during
     * {@code BotContactFactory.buildContact}) as a circular bitmap so it
     * fits the 40dp ImageView in the bot list cleanly. Returns
     * {@code null} when the avatar is missing or unreadable.
     */
    private @Nullable Bitmap loadCircularAvatar(@Nullable String path) {
      if (path == null || path.isEmpty()) return null;
      try {
        Bitmap source = BitmapFactory.decodeFile(path);
        if (source == null) return null;
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        BitmapShader shader = new BitmapShader(source,
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(shader);
        float dx = (side - source.getWidth()) / 2f;
        float dy = (side - source.getHeight()) / 2f;
        canvas.translate(dx, dy);
        canvas.drawCircle(source.getWidth() / 2f, source.getHeight() / 2f,
            side / 2f, paint);
        return output;
      } catch (Throwable t) {
        return null;
      }
    }
  }
}
