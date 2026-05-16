package org.thoughtcrime.securesms.storage;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.StorageCategoryUsage;
import chat.delta.rpc.types.StorageChatUsage;
import chat.delta.rpc.types.StorageClearRequest;
import chat.delta.rpc.types.StorageClearResult;
import chat.delta.rpc.types.StorageUsage;
import com.b44t.messenger.DcContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class StorageManagementActivity extends PassphraseRequiredActionBarActivity {
  private static final String TAG = "StorageManagementActivity";

  private Rpc rpc;
  private DcContext dcContext;
  private StorageUsage usage;

  private TextView totalText;
  private TextView freeText;
  private TextView evictableText;
  private StorageDonutView donutView;
  private LinearLayout categoryList;
  private LinearLayout chatList;
  private TextView emptyText;
  private final Map<String, CheckBox> categoryChecks = new HashMap<>();
  private final Map<Integer, CheckBox> chatChecks = new HashMap<>();

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    dcContext = DcHelper.getContext(this);
    rpc = DcHelper.getRpc(this);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(R.string.bmchat_storage_title);
    }
    setContentView(buildContentView());
    loadStorageUsage();
  }

  @Override
  public boolean onSupportNavigateUp() {
    finish();
    return true;
  }

  @NonNull
  private View buildContentView() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    root.setPadding(pad, pad, pad, pad + dp(24));
    scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

    LinearLayout header = card();
    header.setGravity(Gravity.CENTER_HORIZONTAL);
    donutView = new StorageDonutView(this);
    header.addView(donutView, new LinearLayout.LayoutParams(dp(160), dp(160)));
    totalText = titleText();
    totalText.setGravity(Gravity.CENTER);
    header.addView(totalText, new LinearLayout.LayoutParams(-1, -2));
    freeText = subtitleText();
    freeText.setGravity(Gravity.CENTER);
    header.addView(freeText, new LinearLayout.LayoutParams(-1, -2));
    evictableText = subtitleText();
    evictableText.setGravity(Gravity.CENTER);
    header.addView(evictableText, new LinearLayout.LayoutParams(-1, -2));
    root.addView(header);

    root.addView(sectionTitle(R.string.bmchat_storage_by_type));
    categoryList = card();
    root.addView(categoryList);

    root.addView(sectionTitle(R.string.bmchat_storage_by_chat));
    chatList = card();
    root.addView(chatList);

    emptyText = subtitleText();
    emptyText.setText(R.string.bmchat_storage_loading);
    emptyText.setGravity(Gravity.CENTER);
    root.addView(emptyText, margins(new LinearLayout.LayoutParams(-1, -2), 0, 12, 0, 0));

    Button clear = primaryButton(R.string.bmchat_storage_clear_selected);
    clear.setOnClickListener(v -> confirmClearSelected());
    root.addView(clear, margins(new LinearLayout.LayoutParams(-1, dp(48)), 0, 16, 0, 0));

    Button autoCleanup = secondaryButton(R.string.bmchat_storage_auto_cleanup);
    autoCleanup.setOnClickListener(v -> showAutoCleanupDialog());
    root.addView(autoCleanup, margins(new LinearLayout.LayoutParams(-1, dp(48)), 0, 10, 0, 0));

    Button serverCleanup = dangerButton(R.string.bmchat_storage_server_cleanup);
    serverCleanup.setOnClickListener(v -> showServerCleanupDialog());
    root.addView(serverCleanup, margins(new LinearLayout.LayoutParams(-1, dp(48)), 0, 10, 0, 0));

    return scroll;
  }

  private void loadStorageUsage() {
    emptyText.setText(R.string.bmchat_storage_loading);
    Util.runOnBackground(
        () -> {
          try {
            StorageUsage loaded = rpc.getStorageUsage(dcContext.getAccountId());
            Util.runOnMain(() -> renderStorageUsage(loaded));
          } catch (RpcException e) {
            Log.w(TAG, "Cannot load storage usage", e);
            Util.runOnMain(
                () -> {
                  emptyText.setText(R.string.bmchat_storage_load_error);
                  Toast.makeText(this, R.string.bmchat_storage_load_error, Toast.LENGTH_LONG).show();
                });
          }
        });
  }

  private void renderStorageUsage(@Nullable StorageUsage loaded) {
    usage = loaded;
    categoryChecks.clear();
    chatChecks.clear();
    categoryList.removeAllViews();
    chatList.removeAllViews();

    if (usage == null) {
      emptyText.setVisibility(View.VISIBLE);
      emptyText.setText(R.string.bmchat_storage_empty);
      return;
    }

    totalText.setText(getString(R.string.bmchat_storage_used_fmt, pretty(usage.totalBytes)));
    freeText.setText(getString(R.string.bmchat_storage_free_fmt, pretty(deviceFreeBytes())));
    evictableText.setText(
        getString(R.string.bmchat_storage_evictable_fmt, pretty(usage.evictableBytes)));
    donutView.setUsage(usage);

    if (usage.byCategory != null) {
      for (StorageCategoryUsage item : usage.byCategory) {
        CheckBox box =
            addCheckRow(
                categoryList,
                labelForCategory(item.category),
                getString(
                    R.string.bmchat_storage_row_summary,
                    pretty(item.bytes),
                    pretty(item.evictableBytes)),
                true);
        categoryChecks.put(item.category, box);
      }
    }

    if (usage.byChat != null) {
      for (StorageChatUsage item : usage.byChat) {
        CheckBox box =
            addCheckRow(
                chatList,
                displayChatName(item),
                getString(
                    R.string.bmchat_storage_row_summary,
                    pretty(item.bytes),
                    pretty(item.evictableBytes)),
                true);
        if (item.chatId != null) {
          chatChecks.put(item.chatId, box);
        }
      }
    }

    emptyText.setVisibility(
        categoryChecks.isEmpty() && chatChecks.isEmpty() ? View.VISIBLE : View.GONE);
    emptyText.setText(R.string.bmchat_storage_empty);
  }

  private void confirmClearSelected() {
    if (usage == null) {
      return;
    }
    if (!hasChecked(categoryChecks) || !hasChecked(chatChecks)) {
      Toast.makeText(this, R.string.bmchat_storage_select_something, Toast.LENGTH_LONG).show();
      return;
    }
    StorageClearRequest request = buildClearRequest();
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_storage_clear_selected)
        .setMessage(R.string.bmchat_storage_clear_confirm)
        .setPositiveButton(R.string.bmchat_storage_clear, (d, w) -> clearSelected(request))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private StorageClearRequest buildClearRequest() {
    StorageClearRequest request = new StorageClearRequest();
    List<String> categories = new ArrayList<>();
    for (Map.Entry<String, CheckBox> entry : categoryChecks.entrySet()) {
      if (entry.getValue().isChecked()) {
        categories.add(entry.getKey());
      }
    }
    if (!categories.isEmpty() && categories.size() != categoryChecks.size()) {
      request.categories = categories;
    }

    List<Integer> chatIds = new ArrayList<>();
    for (Map.Entry<Integer, CheckBox> entry : chatChecks.entrySet()) {
      if (entry.getValue().isChecked()) {
        chatIds.add(entry.getKey());
      }
    }
    if (!chatIds.isEmpty() && chatIds.size() != chatChecks.size()) {
      request.chatIds = chatIds;
    }
    return request;
  }

  private <T> boolean hasChecked(@NonNull Map<T, CheckBox> checks) {
    if (checks.isEmpty()) {
      return true;
    }
    for (CheckBox checkBox : checks.values()) {
      if (checkBox.isChecked()) {
        return true;
      }
    }
    return false;
  }

  private void clearSelected(@NonNull StorageClearRequest request) {
    ProgressDialog progress = new ProgressDialog(this);
    progress.setIndeterminate(true);
    progress.setMessage(getString(R.string.bmchat_storage_clearing));
    progress.setCancelable(false);
    progress.show();
    Util.runOnBackground(
        () -> {
          try {
            StorageClearResult result = rpc.clearLocalStorage(dcContext.getAccountId(), request);
            Util.runOnMain(
                () -> {
                  progress.dismiss();
                  showClearResult(result);
                  loadStorageUsage();
                });
          } catch (RpcException e) {
            Log.w(TAG, "Cannot clear local storage", e);
            Util.runOnMain(
                () -> {
                  progress.dismiss();
                  Toast.makeText(this, R.string.bmchat_storage_clear_error, Toast.LENGTH_LONG)
                      .show();
                });
          }
        });
  }

  private void showClearResult(@Nullable StorageClearResult result) {
    long freed = result == null ? 0L : safeLong(result.freedBytes);
    long skipped = result == null ? 0L : safeLong(result.skippedBytes);
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_storage_clear_done)
        .setMessage(getString(R.string.bmchat_storage_clear_done_body, pretty(freed), pretty(skipped)))
        .setPositiveButton(android.R.string.ok, null)
        .show();
  }

  private void showAutoCleanupDialog() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(4);
    content.setPadding(pad, pad, pad, 0);

    CheckBox enabled = new CheckBox(this);
    enabled.setText(R.string.bmchat_storage_auto_enabled);
    enabled.setChecked(prefs.getBoolean(StorageCleanupWorker.PREF_AUTO_ENABLED, false));
    content.addView(enabled);

    Spinner age = spinner(
        new String[] {
          getString(R.string.bmchat_storage_keep_forever),
          getString(R.string.bmchat_storage_keep_3_days),
          getString(R.string.bmchat_storage_keep_1_week),
          getString(R.string.bmchat_storage_keep_1_month)
        });
    long currentAge = prefs.getLong(StorageCleanupWorker.PREF_AUTO_AGE_SECONDS, 0L);
    age.setSelection(currentAge == 259200L ? 1 : currentAge == 604800L ? 2 : currentAge == 2592000L ? 3 : 0);
    content.addView(label(R.string.bmchat_storage_auto_age));
    content.addView(age);

    Spinner limit = spinner(
        new String[] {
          getString(R.string.bmchat_storage_limit_none),
          "1 GB",
          "5 GB",
          "10 GB"
        });
    long currentLimit = prefs.getLong(StorageCleanupWorker.PREF_AUTO_MAX_BYTES, 0L);
    limit.setSelection(currentLimit == gb(1) ? 1 : currentLimit == gb(5) ? 2 : currentLimit == gb(10) ? 3 : 0);
    content.addView(label(R.string.bmchat_storage_auto_limit));
    content.addView(limit);

    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_storage_auto_cleanup)
        .setView(content)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              long ageSeconds = new long[] {0L, 259200L, 604800L, 2592000L}[age.getSelectedItemPosition()];
              long maxBytes = new long[] {0L, gb(1), gb(5), gb(10)}[limit.getSelectedItemPosition()];
              prefs
                  .edit()
                  .putBoolean(StorageCleanupWorker.PREF_AUTO_ENABLED, enabled.isChecked())
                  .putLong(StorageCleanupWorker.PREF_AUTO_AGE_SECONDS, ageSeconds)
                  .putLong(StorageCleanupWorker.PREF_AUTO_MAX_BYTES, maxBytes)
                  .apply();
              if (enabled.isChecked()) {
                StorageCleanupWorker.schedule(this);
              } else {
                StorageCleanupWorker.cancel(this);
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void showServerCleanupDialog() {
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(4), dp(4), dp(4), 0);
    TextView warning = subtitleText();
    warning.setText(R.string.bmchat_storage_server_warning);
    content.addView(warning);

    Spinner duration =
        spinner(
            new String[] {
              getString(R.string.off),
              getString(R.string.autodel_at_once),
              getString(R.string.bmchat_storage_keep_1_month),
              getString(R.string.bmchat_storage_keep_3_months),
              getString(R.string.bmchat_storage_keep_1_year)
            });
    int current = dcContext.getConfigInt("delete_server_after");
    duration.setSelection(
        current == 1 ? 1 : current == 2592000 ? 2 : current == 7776000 ? 3 : current == 31536000 ? 4 : 0);
    content.addView(label(R.string.bmchat_storage_server_after));
    content.addView(duration);

    CheckBox understood = new CheckBox(this);
    understood.setText(R.string.bmchat_storage_server_understand);
    content.addView(understood);

    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(R.string.bmchat_storage_server_cleanup)
            .setView(content)
            .setPositiveButton(R.string.bmchat_storage_apply_danger, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    Util.redPositiveButton(dialog);
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              int seconds =
                  new int[] {0, 1, 2592000, 7776000, 31536000}[duration.getSelectedItemPosition()];
              if (seconds > 0 && !understood.isChecked()) {
                Toast.makeText(this, R.string.bmchat_storage_server_need_confirm, Toast.LENGTH_LONG)
                    .show();
                return;
              }
              if (seconds > 0) {
                int count = dcContext.estimateDeletionCount(true, seconds);
                Toast.makeText(
                        this,
                        getString(R.string.bmchat_storage_server_estimate_fmt, count),
                        Toast.LENGTH_LONG)
                    .show();
              }
              dcContext.setConfigInt("delete_server_after", seconds);
              dcContext.maybeNetwork();
              dialog.dismiss();
            });
  }

  private CheckBox addCheckRow(
      @NonNull LinearLayout parent, @NonNull String title, @NonNull String subtitle, boolean checked) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(8), 0, dp(8));

    CheckBox box = new CheckBox(this);
    box.setChecked(checked);
    row.addView(box, new LinearLayout.LayoutParams(dp(48), dp(48)));

    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView titleView = titleText();
    titleView.setText(title);
    titleView.setTextSize(16);
    TextView subtitleView = subtitleText();
    subtitleView.setText(subtitle);
    texts.addView(titleView);
    texts.addView(subtitleView);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

    row.setOnClickListener(v -> box.setChecked(!box.isChecked()));
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return box;
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(16), dp(16), dp(16));
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(ContextCompat.getColor(this, R.color.white));
    bg.setCornerRadius(dp(18));
    bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.gray10));
    card.setBackground(bg);
    card.setClipToOutline(false);
    card.setLayoutParams(margins(new LinearLayout.LayoutParams(-1, -2), 0, 8, 0, 8));
    return card;
  }

  private TextView sectionTitle(int resId) {
    TextView title = titleText();
    title.setText(resId);
    title.setTextSize(18);
    return title;
  }

  private TextView titleText() {
    TextView text = new TextView(this);
    text.setTextColor(ContextCompat.getColor(this, R.color.black));
    text.setTypeface(Typeface.DEFAULT_BOLD);
    return text;
  }

  private TextView subtitleText() {
    TextView text = new TextView(this);
    text.setTextColor(ContextCompat.getColor(this, R.color.gray65));
    text.setTextSize(14);
    return text;
  }

  private TextView label(int resId) {
    TextView label = titleText();
    label.setText(resId);
    label.setTextSize(14);
    label.setPadding(0, dp(10), 0, dp(4));
    return label;
  }

  private Button primaryButton(int resId) {
    Button button = new Button(this);
    button.setText(resId);
    button.setTextColor(ContextCompat.getColor(this, R.color.white));
    button.setBackgroundColor(ContextCompat.getColor(this, R.color.delta_accent));
    return button;
  }

  private Button secondaryButton(int resId) {
    Button button = new Button(this);
    button.setText(resId);
    return button;
  }

  private Button dangerButton(int resId) {
    Button button = secondaryButton(resId);
    button.setTextColor(0xffff0c16);
    return button;
  }

  private Spinner spinner(String[] values) {
    Spinner spinner = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(adapter);
    return spinner;
  }

  private LinearLayout.LayoutParams margins(
      LinearLayout.LayoutParams params, int left, int top, int right, int bottom) {
    params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
    return params;
  }

  private String displayChatName(@NonNull StorageChatUsage item) {
    if (item.chatId != null) {
      try {
        String name = dcContext.getChat(item.chatId).getName();
        if (!TextUtils.isEmpty(name)) {
          return name;
        }
      } catch (Throwable ignored) {
      }
    }
    return TextUtils.isEmpty(item.name) ? getString(R.string.bmchat_storage_unknown_chat) : item.name;
  }

  private String labelForCategory(@Nullable String category) {
    if ("images".equals(category)) return getString(R.string.bmchat_storage_category_images);
    if ("videos".equals(category)) return getString(R.string.bmchat_storage_category_videos);
    if ("audio".equals(category)) return getString(R.string.bmchat_storage_category_audio);
    if ("files".equals(category)) return getString(R.string.bmchat_storage_category_files);
    if ("webxdc".equals(category)) return getString(R.string.bmchat_storage_category_webxdc);
    return getString(R.string.bmchat_storage_category_other);
  }

  private String pretty(@Nullable Long value) {
    return Util.getPrettyFileSize(safeLong(value));
  }

  private long safeLong(@Nullable Long value) {
    return value == null ? 0L : value;
  }

  private long deviceFreeBytes() {
    StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
    return stat.getAvailableBytes();
  }

  private long gb(int value) {
    return value * 1024L * 1024L * 1024L;
  }

  private int dp(int value) {
    return ViewUtil.dpToPx(this, value);
  }

  public static final class StorageDonutView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private StorageUsage usage;
    private final int[] colors = {
      0xff7B1226, 0xffC62A48, 0xffF08CA0, 0xff8F1830, 0xffB66A7A, 0xffD8B6BD
    };

    public StorageDonutView(Context context) {
      super(context);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.BUTT);
    }

    void setUsage(@Nullable StorageUsage usage) {
      this.usage = usage;
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int size = Math.min(getWidth(), getHeight());
      float stroke = size * 0.14f;
      paint.setStrokeWidth(stroke);
      rect.set(stroke, stroke, size - stroke, size - stroke);

      paint.setColor(0xffeeeeee);
      canvas.drawArc(rect, -90, 360, false, paint);
      long total = usage == null ? 0L : safe(usage.blobdirBytes);
      if (usage == null || usage.byCategory == null || total <= 0) {
        return;
      }
      float start = -90f;
      int index = 0;
      for (StorageCategoryUsage item : usage.byCategory) {
        long bytes = safe(item.bytes);
        if (bytes <= 0) continue;
        float sweep = (bytes * 360f) / total;
        paint.setColor(colors[index++ % colors.length]);
        canvas.drawArc(rect, start, sweep, false, paint);
        start += sweep;
      }
    }

    private static long safe(Long value) {
      return value == null ? 0L : value;
    }
  }
}
