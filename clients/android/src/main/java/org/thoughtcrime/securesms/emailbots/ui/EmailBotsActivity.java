package org.thoughtcrime.securesms.emailbots.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotConfig;
import org.thoughtcrime.securesms.emailbots.EmailBotStore;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists every {@link EmailBotConfig} owned by the currently-selected
 * account and lets the user pick one to edit (or hit the FAB to
 * create a new bot). The screen is the email-bots equivalent of
 * {@code BotsActivity} for Telegram bots.
 *
 * <p>Bots are scoped to the currently-active BMChat account because
 * every reply is sent through that account's IMAP/SMTP credentials.
 * Removing the account in BMChat acts as Telegram's "wipe webhook":
 * the bot stops responding without leaving stale state in the cloud.
 */
public class EmailBotsActivity extends PassphraseRequiredActionBarActivity {

  public static Intent newIntent(Context ctx) {
    return new Intent(ctx, EmailBotsActivity.class);
  }

  private EmailBotStore store;
  private BotsAdapter adapter;
  private RecyclerView list;
  private View emptyView;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_email_bots);

    store = new EmailBotStore(getApplicationContext());

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_email_bots_title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    list = findViewById(R.id.email_bots_list);
    emptyView = findViewById(R.id.email_bots_empty);
    list.setLayoutManager(new LinearLayoutManager(this));
    adapter = new BotsAdapter();
    list.setAdapter(adapter);

    FloatingActionButton fab = findViewById(R.id.email_bots_add);
    fab.setOnClickListener(v -> openEditor(null));
  }

  @Override
  protected void onResume() {
    super.onResume();
    refresh();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // BMChat: Toolbar's home (back-arrow) item is not delivered to
    // PassphraseRequiredActionBarActivity by default — we must close
    // the activity explicitly, otherwise tapping the arrow does
    // nothing (regression visible on screenshot 2 from 15 May).
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void refresh() {
    int accountId = DcHelper.getContext(this).getAccountId();
    new Thread(() -> {
      org.thoughtcrime.securesms.emailbots.EmailBotSync.syncAccount(
          getApplicationContext(), accountId);
      runOnUiThread(() -> {
        List<EmailBotConfig> bots = store.getForAccount(accountId);
        adapter.setData(bots);
        emptyView.setVisibility(bots.isEmpty() ? View.VISIBLE : View.GONE);
      });
    }, "emailbot-list-sync").start();
  }

  private void openEditor(@Nullable EmailBotConfig bot) {
    startActivity(EmailBotEditActivity.newIntent(this, bot == null ? null : bot.id));
  }

  // --------------------------------------------------------------------
  //  per-bot context menu (mirrors BotsActivity)
  // --------------------------------------------------------------------

  private void showBotMenu(EmailBotConfig bot) {
    final boolean hasAttachments = !bot.attachedChatIds.isEmpty();

    final List<String> items = new ArrayList<>();
    final List<Integer> codes = new ArrayList<>();
    items.add(getString(R.string.bmchat_bots_open_home));     codes.add(0);
    items.add(getString(R.string.bmchat_bots_add_to_chat));   codes.add(1);
    if (hasAttachments) {
      items.add(getString(R.string.bmchat_bots_remove_from_chat)); codes.add(5);
    }
    items.add(getString(R.string.menu_edit_name));            codes.add(3);
    items.add(bot.enabled
        ? getString(R.string.bmchat_bots_pause)
        : getString(R.string.bmchat_bots_resume));          codes.add(2);
    items.add(getString(R.string.bmchat_email_bot_delete));   codes.add(4);

    new AlertDialog.Builder(this)
        .setTitle(bot.displayName())
        .setItems(items.toArray(new String[0]), (dialog, which) -> {
          switch (codes.get(which)) {
            case 0: openHomeChat(bot); break;
            case 1: showAddToChatPicker(bot); break;
            case 2:
              store.upsert(bot.withEnabled(!bot.enabled));
              refresh();
              break;
            case 3: openEditor(bot); break;
            case 4: confirmRemoveBot(bot); break;
            case 5: showRemoveFromChatPicker(bot); break;
            default: break;
          }
        })
        .show();
  }

  private void openHomeChat(EmailBotConfig bot) {
    int chatId = bot.botChatId;
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

  private void showAddToChatPicker(EmailBotConfig bot) {
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
        if (chat.getId() == bot.botChatId) continue;
        if (bot.attachedChatIds.contains(chatId)) continue;
        ids.add(chatId);
        String name = chat.getName();
        if (chat.isOutBroadcast() || chat.isInBroadcast()) {
          name += " · " + getString(R.string.bmchat_bots_label_channel);
        }
        labels.add(name);
      }
    } catch (Throwable ignored) {}

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
          } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                R.string.bmchat_bots_add_failed,
                android.widget.Toast.LENGTH_LONG).show();
          }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showRemoveFromChatPicker(EmailBotConfig bot) {
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

  private void confirmRemoveBot(EmailBotConfig bot) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_email_bot_delete_title)
        .setMessage(getString(R.string.bmchat_email_bot_delete_message, bot.name))
        .setPositiveButton(R.string.delete, (d, w) -> {
          store.delete(bot.id);
          refresh();
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  // --------------------------------------------------------------------
  // Adapter
  // --------------------------------------------------------------------

  private final class BotsAdapter extends RecyclerView.Adapter<BotVH> {
    private final List<EmailBotConfig> data = new ArrayList<>();

    void setData(@NonNull List<EmailBotConfig> next) {
      data.clear();
      data.addAll(next);
      notifyDataSetChanged();
    }

    @NonNull @Override
    public BotVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View v = LayoutInflater.from(parent.getContext())
          .inflate(R.layout.item_email_bot, parent, false);
      return new BotVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull BotVH holder, int position) {
      EmailBotConfig b = data.get(position);
      holder.name.setText(b.displayName());
      int cmdCount = b.commands.size();
      String hasWebhook = (b.webhookUrl != null && !b.webhookUrl.isEmpty())
          ? holder.itemView.getContext().getString(R.string.bmchat_email_bot_subtitle_webhook)
          : "";
      String subtitle;
      if (cmdCount == 0 && hasWebhook.isEmpty()) {
        subtitle = holder.itemView.getContext().getString(R.string.bmchat_email_bot_subtitle_empty);
      } else if (cmdCount == 0) {
        subtitle = hasWebhook;
      } else if (hasWebhook.isEmpty()) {
        subtitle = holder.itemView.getContext().getResources()
            .getQuantityString(R.plurals.bmchat_email_bot_subtitle_commands, cmdCount, cmdCount);
      } else {
        subtitle = holder.itemView.getContext().getResources()
            .getQuantityString(R.plurals.bmchat_email_bot_subtitle_commands, cmdCount, cmdCount)
            + " · " + hasWebhook;
      }
      int attachCount = b.attachedChatIds.size();
      if (attachCount > 0) {
        subtitle += " · " + holder.itemView.getContext().getResources()
            .getQuantityString(R.plurals.bmchat_bots_member_of_n_chats, attachCount, attachCount);
      }
      holder.subtitle.setText(subtitle);

      holder.status.setText(b.enabled
          ? R.string.bmchat_email_bot_status_enabled
          : R.string.bmchat_email_bot_status_disabled);

      holder.itemView.setOnClickListener(v -> showBotMenu(b));
    }

    @Override public int getItemCount() { return data.size(); }
  }

  private static final class BotVH extends RecyclerView.ViewHolder {
    final TextView name;
    final TextView subtitle;
    final TextView status;
    BotVH(@NonNull View v) {
      super(v);
      name = v.findViewById(R.id.email_bot_name);
      subtitle = v.findViewById(R.id.email_bot_subtitle);
      status = v.findViewById(R.id.email_bot_status);
    }
  }
}
