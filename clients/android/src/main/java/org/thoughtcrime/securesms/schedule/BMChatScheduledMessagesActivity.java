package org.thoughtcrime.securesms.schedule;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;
import java.util.Date;
import java.util.List;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * BMChat 2.49.84 (Phase 4B): a list of every scheduled message the user has pending, broken down
 * by chat. The screen is intentionally programmatic so that it can be reached from any chat,
 * without owning yet another layout resource.
 */
public final class BMChatScheduledMessagesActivity extends AppCompatActivity {

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setTheme(R.style.TextSecure_LightTheme);
    rebuild();
  }

  @Override
  protected void onResume() {
    super.onResume();
    rebuild();
  }

  private void rebuild() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setFitsSystemWindows(true);

    Toolbar toolbar = new Toolbar(this);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(R.string.bmchat_scheduled_messages);
    }
    toolbar.setNavigationOnClickListener(v -> finish());
    root.addView(
        toolbar,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    NestedScrollView scroll = new NestedScrollView(this);
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    container.setPadding(pad, pad, pad, pad);
    scroll.addView(container);

    BMChatScheduledMessageStore store = new BMChatScheduledMessageStore(this);
    List<BMChatScheduledMessage> messages = store.getAll();

    if (messages.isEmpty()) {
      TextView empty = new TextView(this);
      empty.setText(R.string.bmchat_schedule_empty);
      empty.setTextSize(16);
      empty.setPadding(0, pad * 2, 0, pad * 2);
      container.addView(empty);
    } else {
      for (BMChatScheduledMessage entry : messages) {
        container.addView(buildRow(entry, store));
      }
    }

    root.addView(
        scroll,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setContentView(root);
  }

  private @NonNull View buildRow(
      @NonNull BMChatScheduledMessage entry, @NonNull BMChatScheduledMessageStore store) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (12 * getResources().getDisplayMetrics().density);
    row.setPadding(pad, pad, pad, pad);
    row.setBackgroundResource(R.drawable.touch_highlight_background);

    String chatName = "";
    try {
      chatName = DcHelper.getContext(this).getChat(entry.chatId).getName();
    } catch (Throwable ignored) {
    }

    TextView title = new TextView(this);
    title.setTextSize(15);
    title.setText(
        chatName.isEmpty()
            ? entry.body
            : (chatName + "  ·  " + (entry.body.isEmpty() ? "📎" : entry.body)));
    title.setMaxLines(3);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(title);

    TextView meta = new TextView(this);
    meta.setTextSize(12);
    android.util.TypedValue secondary = new android.util.TypedValue();
    if (getTheme().resolveAttribute(android.R.attr.textColorSecondary, secondary, true)) {
      meta.setTextColor(getResources().getColor(secondary.resourceId));
    }
    String when =
        DateFormat.getMediumDateFormat(this).format(new Date(entry.scheduledAtMs))
            + " "
            + DateFormat.getTimeFormat(this).format(new Date(entry.scheduledAtMs));
    meta.setText(getString(R.string.bmchat_schedule_pending, when));
    row.addView(meta);

    row.setOnClickListener(
        v ->
            new AlertDialog.Builder(BMChatScheduledMessagesActivity.this)
                .setMessage(R.string.bmchat_schedule_cancel)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                    android.R.string.ok,
                    (d, w) -> {
                      BMChatScheduledMessageScheduler.cancel(
                          BMChatScheduledMessagesActivity.this, entry.id);
                      store.remove(entry.id);
                      Toast.makeText(
                              BMChatScheduledMessagesActivity.this,
                              R.string.bmchat_schedule_cancelled,
                              Toast.LENGTH_SHORT)
                          .show();
                      rebuild();
                    })
                .show());

    return row;
  }
}
