package org.thoughtcrime.securesms.bots.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * A trimmed-down chat list shown in a "pick a destination" mode for bot
 * configuration.
 *
 * <p>Self-talk is highlighted as the recommended destination because all
 * other chats fan messages out via SMTP — i.e. picking a 1:1 with a
 * friend would actually email Telegram posts to them, which is rarely
 * what the user wants.
 *
 * <p>Returns the picked chat-id and account-id as int extras under
 * {@link #EXTRA_RESULT_CHAT_ID} / {@link #EXTRA_RESULT_ACCOUNT_ID}.
 */
public class BotChatPickerActivity extends PassphraseRequiredActionBarActivity {

  public static final String EXTRA_RESULT_CHAT_ID = "result_chat_id";
  public static final String EXTRA_RESULT_ACCOUNT_ID = "result_account_id";
  public static final String EXTRA_RESULT_CHAT_NAME = "result_chat_name";
  public static final String EXTRA_RESULT_IS_SELF_TALK = "result_is_self_talk";

  public static Intent newIntent(Context ctx) {
    return new Intent(ctx, BotChatPickerActivity.class);
  }

  private List<Row> rows = new ArrayList<>();

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_bot_chat_picker);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_bots_pick_chat_title);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    rows = collectRows();
    ListView list = findViewById(R.id.chat_list);
    Adapter adapter = new Adapter();
    list.setAdapter(adapter);
    list.setOnItemClickListener((parent, view, position, id) -> {
      Row r = rows.get(position);
      Intent data = new Intent();
      data.putExtra(EXTRA_RESULT_CHAT_ID, r.chatId);
      data.putExtra(EXTRA_RESULT_ACCOUNT_ID, r.accountId);
      data.putExtra(EXTRA_RESULT_CHAT_NAME, r.name);
      data.putExtra(EXTRA_RESULT_IS_SELF_TALK, r.isSelfTalk);
      setResult(RESULT_OK, data);
      finish();
    });
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private List<Row> collectRows() {
    List<Row> out = new ArrayList<>();
    DcAccounts accounts = DcHelper.getAccounts(this);
    int[] ids = accounts.getAll();
    for (int accountId : ids) {
      DcContext dc = accounts.getAccount(accountId);
      if (dc == null) continue;
      DcChatlist list = dc.getChatlist(0, null, 0);
      if (list == null) continue;
      // Self-talk first.
      List<Row> selfRows = new ArrayList<>();
      List<Row> normalRows = new ArrayList<>();
      for (int i = 0; i < list.getCnt(); i++) {
        int chatId = list.getChatId(i);
        DcChat chat = dc.getChat(chatId);
        if (chat == null) continue;
        if (chat.isContactRequest()) continue;
        if (!chat.canSend()) continue;
        Row row = new Row();
        row.accountId = accountId;
        row.chatId = chatId;
        row.name = chat.getName();
        row.isSelfTalk = chat.isSelfTalk();
        row.isMultiUser = chat.isMultiUser();
        row.warn = !row.isSelfTalk;
        if (row.isSelfTalk) selfRows.add(row);
        else normalRows.add(row);
      }
      out.addAll(selfRows);
      out.addAll(normalRows);
    }
    return out;
  }

  private static final class Row {
    int accountId;
    int chatId;
    String name;
    boolean isSelfTalk;
    boolean isMultiUser;
    boolean warn;
  }

  private final class Adapter extends BaseAdapter {
    @Override public int getCount() { return rows.size(); }
    @Override public Object getItem(int position) { return rows.get(position); }
    @Override public long getItemId(int position) { return rows.get(position).chatId; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
      View v = convertView;
      if (v == null) {
        v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_bot_chat_picker, parent, false);
      }
      Row r = rows.get(position);
      TextView nameTv = v.findViewById(R.id.chat_name);
      TextView metaTv = v.findViewById(R.id.chat_meta);
      String namePrefix = r.isSelfTalk ? "📝 " : (r.isMultiUser ? "👥 " : "👤 ");
      nameTv.setText(namePrefix + r.name);
      String meta;
      if (r.isSelfTalk) meta = getString(R.string.bmchat_bots_pick_self_recommend);
      else if (r.isMultiUser) meta = getString(R.string.bmchat_bots_pick_group_warn);
      else meta = getString(R.string.bmchat_bots_pick_dm_warn);
      metaTv.setText(meta);
      return v;
    }
  }
}
