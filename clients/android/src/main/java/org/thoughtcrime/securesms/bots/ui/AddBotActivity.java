package org.thoughtcrime.securesms.bots.ui;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;
import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.bots.BotContactFactory;
import org.thoughtcrime.securesms.bots.BotConfig;
import org.thoughtcrime.securesms.bots.BotPollManager;
import org.thoughtcrime.securesms.bots.BotStore;
import org.thoughtcrime.securesms.bots.TelegramApi;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-step "Add bot" wizard:
 *
 * <ol>
 *   <li>User pastes the {@code @BotFather} token; "Verify" hits
 *       {@code getMe} to confirm the token and to cache the bot's
 *       display name + Telegram user-id.</li>
 *   <li>"Connect" creates a {@link com.b44t.messenger.DcContact}
 *       pseudo-user for the bot (an addressable participant that can
 *       later be added to any chat or broadcast) plus a 1:1 home chat
 *       with that pseudo-user, then persists the {@link BotConfig}.</li>
 * </ol>
 *
 * <p>The bot is now a regular contact in the BMChat address book.
 * The user can drag it into any group / broadcast list via the standard
 * "Add member" picker; once it is a member, every Telegram update is
 * mirrored into that chat. This matches the Telegram mental model where
 * a bot is a virtual user, not a dedicated channel.
 */
public class AddBotActivity extends PassphraseRequiredActionBarActivity {

  private static final String TAG = "AddBotActivity";

  public static Intent newIntent(Context ctx) {
    return new Intent(ctx, AddBotActivity.class);
  }

  private final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  private final Handler MAIN = new Handler(Looper.getMainLooper());

  private TextInputEditText tokenInput;
  private Button verifyBtn;
  private Button saveBtn;
  private TextView statusView;

  // verified state
  @Nullable private String verifiedToken;
  @Nullable private JSONObject verifiedMeResult;
  @Nullable private String verifiedUsername;
  @Nullable private String verifiedName;
  private long verifiedBotId;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_add_bot);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setTitle(R.string.bmchat_bots_add);
      bar.setDisplayHomeAsUpEnabled(true);
    }

    tokenInput = findViewById(R.id.token_input);
    verifyBtn = findViewById(R.id.verify_btn);
    saveBtn = findViewById(R.id.save_btn);
    statusView = findViewById(R.id.status_view);

    // Hide the chat-picker step from the existing layout; the v2 flow
    // does not need it any more (per-bot channel is auto-created).
    View pickChatBtn = findViewById(R.id.pick_chat_btn);
    if (pickChatBtn != null) pickChatBtn.setVisibility(View.GONE);
    View pickedChatLabel = findViewById(R.id.picked_chat_label);
    if (pickedChatLabel != null) pickedChatLabel.setVisibility(View.GONE);
    View targetLabel = findViewById(R.id.target_label);
    if (targetLabel != null) targetLabel.setVisibility(View.GONE);
    View divider = findViewById(R.id.divider1);
    if (divider != null) divider.setVisibility(View.GONE);

    tokenInput.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
      @Override public void afterTextChanged(Editable s) {
        if (verifiedToken != null && !verifiedToken.equals(s.toString().trim())) {
          resetVerification();
        }
      }
    });

    verifyBtn.setOnClickListener(v -> verifyToken());
    saveBtn.setOnClickListener(v -> connectBot());
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  // ---------------------------------------------------------------------
  //  step 1: verify token
  // ---------------------------------------------------------------------

  private void verifyToken() {
    String token = tokenInput.getText() != null
        ? tokenInput.getText().toString().trim() : "";
    if (TextUtils.isEmpty(token) || !token.contains(":")) {
      showStatus(getString(R.string.bmchat_bots_invalid_token_format), true);
      return;
    }
    ProgressDialog dialog = new ProgressDialog(this);
    dialog.setMessage(getString(R.string.bmchat_bots_verifying));
    dialog.setCancelable(false);
    dialog.show();
    verifyBtn.setEnabled(false);
    EXEC.execute(() -> {
      JSONObject result = null;
      String error = null;
      try {
        TelegramApi api = new TelegramApi(token);
        JSONObject me = api.getMe();
        if (me == null) {
          error = "Telegram отклонил токен.";
        } else {
          result = me.optJSONObject("result");
          if (result == null) error = "Telegram отклонил токен.";
        }
      } catch (Throwable t) {
        Log.w(TAG, "verifyToken failed", t);
        error = t.getMessage() == null ? "Ошибка сети." : t.getMessage();
      }
      final String fError = error;
      final JSONObject fResult = result;
      MAIN.post(() -> {
        try { dialog.dismiss(); } catch (Throwable ignored) {}
        verifyBtn.setEnabled(true);
        if (fError != null) {
          showStatus(getString(R.string.bmchat_bots_invalid_token) + "\n" + fError, true);
          resetVerification();
        } else {
          verifiedToken = token;
          verifiedMeResult = fResult;
          verifiedUsername = fResult.optString("username", null);
          verifiedName = fResult.optString("first_name", null);
          verifiedBotId = fResult.optLong("id", 0L);
          showStatus(getString(R.string.bmchat_bots_token_ok)
              + "\n" + (verifiedName != null ? verifiedName : "")
              + (verifiedUsername != null ? " (@" + verifiedUsername + ")" : ""), false);
          revealStep2();
        }
      });
    });
  }

  private void resetVerification() {
    verifiedToken = null;
    verifiedMeResult = null;
    verifiedUsername = null;
    verifiedName = null;
    verifiedBotId = 0L;
    saveBtn.setVisibility(View.GONE);
    saveBtn.setEnabled(false);
  }

  private void revealStep2() {
    saveBtn.setVisibility(View.VISIBLE);
    saveBtn.setEnabled(true);
  }

  private void showStatus(String text, boolean isError) {
    statusView.setVisibility(View.VISIBLE);
    statusView.setText(text);
    statusView.setTextColor(getResources().getColor(
        isError ? android.R.color.holo_red_dark : android.R.color.holo_green_dark));
  }

  // ---------------------------------------------------------------------
  //  step 2: build per-bot channel and persist
  // ---------------------------------------------------------------------

  private void connectBot() {
    if (verifiedToken == null || verifiedMeResult == null) return;
    ProgressDialog dialog = new ProgressDialog(this);
    dialog.setMessage(getString(R.string.bmchat_bots_connecting));
    dialog.setCancelable(false);
    dialog.show();
    saveBtn.setEnabled(false);
    EXEC.execute(() -> {
      BotContactFactory.Result built = null;
      String error = null;
      try {
        built = BotContactFactory.buildContact(
            getApplicationContext(), verifiedToken, verifiedMeResult);
      } catch (Throwable t) {
        Log.w(TAG, "buildContact failed", t);
        error = t.getMessage() == null ? "Ошибка создания контакта бота." : t.getMessage();
      }
      if (built == null && error == null) {
        error = "Не удалось зарегистрировать бота. Попробуйте ещё раз.";
      }
      final String fError = error;
      final BotContactFactory.Result fBuilt = built;
      MAIN.post(() -> {
        try { dialog.dismiss(); } catch (Throwable ignored) {}
        if (fError != null) {
          showStatus(fError, true);
          saveBtn.setEnabled(true);
          return;
        }
        BotConfig cfg = new BotConfig(
            BotStore.generateId(),
            verifiedToken,
            fBuilt.telegramUsername,
            fBuilt.telegramName,
            fBuilt.avatarPath,
            fBuilt.telegramBotId,
            fBuilt.dcAccountId,
            fBuilt.defaultChatId,
            0L,
            0L,
            false,
            null,
            fBuilt.botContactId,
            fBuilt.description,
            fBuilt.shortDescription,
            null,
            false);
        new BotStore(getApplicationContext()).upsert(cfg);
        BotPollManager.ensurePeriodicScheduled(getApplicationContext());
        BotPollManager.pollAllAsync(getApplicationContext(), () -> {});
        Toast.makeText(this,
            getString(R.string.bmchat_bots_added_fmt, cfg.displayName()),
            Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
      });
    });
  }
}
