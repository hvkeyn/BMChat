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
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
    store.reloadFromUiConfig();
    int accountId = DcHelper.getContext(this).getAccountId();
    List<EmailBotConfig> bots = store.getForAccount(accountId);
    adapter.setData(bots);
    emptyView.setVisibility(bots.isEmpty() ? View.VISIBLE : View.GONE);
  }

  private void openEditor(@Nullable EmailBotConfig bot) {
    startActivity(EmailBotEditActivity.newIntent(this, bot == null ? null : bot.id));
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
      holder.subtitle.setText(subtitle);

      holder.status.setText(b.enabled
          ? R.string.bmchat_email_bot_status_enabled
          : R.string.bmchat_email_bot_status_disabled);

      holder.itemView.setOnClickListener(v -> openEditor(b));
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
