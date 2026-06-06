package org.thoughtcrime.securesms.emailbots.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import org.thoughtcrime.securesms.PassphraseRequiredActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotConfig;
import org.thoughtcrime.securesms.emailbots.EmailBotStore;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Editor for a single {@link EmailBotConfig}. Reached either via
 * {@code EmailBotsActivity}'s "+" FAB (new bot) or by tapping an
 * existing row (edit).
 *
 * <p>The activity is intentionally a single scrollable form rather
 * than a multi-screen wizard: the data model is small enough to fit on
 * one page, and users coming from Telegram's @BotFather are already
 * comfortable with a "configure everything in-line" experience.
 *
 * <p>Validation is intentionally permissive — we only require a
 * non-empty bot name that matches {@code [A-Za-z0-9_-]+}. Empty
 * command tables are allowed (purely webhook-driven bots are a valid
 * configuration).
 */
public class EmailBotEditActivity extends PassphraseRequiredActionBarActivity {

  private static final String EXTRA_BOT_ID = "bmchat.email_bot.id";
  private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_]+");
  private static final Pattern EMAIL_RE = Pattern.compile(
      "[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

  public static Intent newIntent(@NonNull Context ctx, @Nullable String botId) {
    Intent i = new Intent(ctx, EmailBotEditActivity.class);
    if (botId != null) i.putExtra(EXTRA_BOT_ID, botId);
    return i;
  }

  private EmailBotStore store;
  @Nullable private EmailBotConfig existing;

  private EditText nameInput;
  private EditText displayNameInput;
  private EditText descriptionInput;
  private EditText developerEmailInput;
  private SwitchCompat enabledSwitch;
  private LinearLayout commandsContainer;
  private EditText webhookInput;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState, boolean ready) {
    setContentView(R.layout.activity_email_bot_edit);

    store = new EmailBotStore(getApplicationContext());

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setDisplayHomeAsUpEnabled(true);
    }

    nameInput = findViewById(R.id.email_bot_name_input);
    displayNameInput = findViewById(R.id.email_bot_display_name_input);
    descriptionInput = findViewById(R.id.email_bot_description_input);
    developerEmailInput = findViewById(R.id.email_bot_developer_email_input);
    enabledSwitch = findViewById(R.id.email_bot_enabled_switch);
    commandsContainer = findViewById(R.id.email_bot_commands_container);
    webhookInput = findViewById(R.id.email_bot_webhook_input);

    String botId = getIntent().getStringExtra(EXTRA_BOT_ID);
    existing = botId != null ? store.getById(botId) : null;
    if (existing == null) {
      if (bar != null) bar.setTitle(R.string.bmchat_email_bot_new_title);
      // Seed with a placeholder command so the user understands the
      // shape of the data they're about to fill in.
      addCommandRow("start", "Привет! Я бот.");
    } else {
      if (bar != null) {
        bar.setTitle(getString(R.string.bmchat_email_bot_edit_title, existing.name));
      }
      nameInput.setText(existing.name);
      displayNameInput.setText(
          existing.displayName.equals(existing.name) ? "" : existing.displayName);
      descriptionInput.setText(existing.description == null ? "" : existing.description);
      developerEmailInput.setText(
          existing.developerEmail == null ? "" : existing.developerEmail);
      enabledSwitch.setChecked(existing.enabled);
      webhookInput.setText(existing.webhookUrl == null ? "" : existing.webhookUrl);
      for (Map.Entry<String, String> e : existing.commandEntries()) {
        addCommandRow(e.getKey(), e.getValue());
      }
      findViewById(R.id.email_bot_delete_button).setVisibility(View.VISIBLE);
      // Reveal the API-token row for already-saved bots: a brand-new
      // bot has no stable id yet, so the token would change on save.
      findViewById(R.id.email_bot_token_divider).setVisibility(View.VISIBLE);
      View tokenRow = findViewById(R.id.email_bot_token_row);
      tokenRow.setVisibility(View.VISIBLE);
      TextView tokenView = findViewById(R.id.email_bot_token_value);
      tokenView.setText(existing.token);
      findViewById(R.id.email_bot_token_copy).setOnClickListener(v -> {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
          cm.setPrimaryClip(ClipData.newPlainText(
              "BMChat bot token", existing.token));
          Toast.makeText(this, R.string.bmchat_email_bot_token_copied,
              Toast.LENGTH_SHORT).show();
        }
      });
    }

    findViewById(R.id.email_bot_add_command)
        .setOnClickListener(v -> addCommandRow("", ""));
    findViewById(R.id.email_bot_save_button)
        .setOnClickListener(v -> save());
    findViewById(R.id.email_bot_delete_button)
        .setOnClickListener(v -> confirmDelete());
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void addCommandRow(@NonNull String command, @NonNull String reply) {
    View row = LayoutInflater.from(this)
        .inflate(R.layout.item_email_bot_command_row, commandsContainer, false);
    EditText nameField = row.findViewById(R.id.email_bot_command_name);
    EditText replyField = row.findViewById(R.id.email_bot_command_reply);
    AppCompatImageButton deleteBtn = row.findViewById(R.id.email_bot_command_delete);

    nameField.setText(command);
    replyField.setText(reply);
    deleteBtn.setOnClickListener(v -> commandsContainer.removeView(row));

    commandsContainer.addView(row);
  }

  private void save() {
    String name = nameInput.getText().toString().trim();
    if (name.isEmpty()) {
      nameInput.setError(getString(R.string.bmchat_email_bot_error_name_required));
      return;
    }
    if (!NAME_RE.matcher(name).matches()) {
      nameInput.setError(getString(R.string.bmchat_email_bot_error_name_invalid));
      return;
    }
    // BotFather rule: every bot username must end with "bot" — either
    // glued ("helperbot") or with a separator ("helper_bot"). We
    // helpfully auto-append "_bot" when the user forgot, mirroring the
    // affordance Telegram's @BotFather offers via its interactive
    // wizard.
    if (!EmailBotConfig.isValidBotName(name)) {
      name = name + "_bot";
      nameInput.setText(name);
    }

    int accountId = DcHelper.getContext(this).getAccountId();

    EmailBotConfig clash = store.findByName(accountId, name);
    if (clash != null && (existing == null || !clash.id.equals(existing.id))) {
      nameInput.setError(getString(R.string.bmchat_email_bot_error_name_taken));
      return;
    }
    if (store.isNameTakenGlobally(name, existing != null ? existing.id : null)) {
      nameInput.setError(getString(R.string.bmchat_email_bot_error_name_taken));
      return;
    }

    String developerEmail = developerEmailInput.getText().toString().trim();
    if (developerEmail.isEmpty()) {
      developerEmail = null;
    } else if (!EMAIL_RE.matcher(developerEmail).matches()) {
      developerEmailInput.setError(
          getString(R.string.bmchat_email_bot_error_developer_email_invalid));
      return;
    }

    String displayName = displayNameInput.getText().toString().trim();
    if (displayName.isEmpty()) displayName = null;

    Map<String, String> commands = new LinkedHashMap<>();
    int rowCount = commandsContainer.getChildCount();
    for (int i = 0; i < rowCount; i++) {
      View row = commandsContainer.getChildAt(i);
      EditText nameField = row.findViewById(R.id.email_bot_command_name);
      EditText replyField = row.findViewById(R.id.email_bot_command_reply);
      String cmd = nameField.getText().toString().trim();
      String reply = replyField.getText().toString();
      // Skip rows where the user left the command name blank — they
      // were obviously cleared out and meant to be removed.
      if (cmd.isEmpty()) continue;
      // strip the leading slash if the user typed one in
      while (cmd.startsWith("/")) cmd = cmd.substring(1);
      if (cmd.isEmpty()) continue;
      commands.put(cmd.toLowerCase(), reply);
    }

    String description = descriptionInput.getText().toString().trim();
    if (description.isEmpty()) description = null;
    String webhook = webhookInput.getText().toString().trim();
    if (webhook.isEmpty()) webhook = null;

    EmailBotConfig saved;
    if (existing == null) {
      saved = new EmailBotConfig(
          EmailBotStore.newId(),
          name,
          description,
          accountId,
          enabledSwitch.isChecked(),
          commands,
          webhook,
          System.currentTimeMillis(),
          0L,
          0L,
          null,
          displayName,
          null,
          developerEmail,
          null,
          0,
          0);
    } else {
      saved = existing
          .withName(name)
          .withDescription(description)
          .withEnabled(enabledSwitch.isChecked())
          .withCommands(commands)
          .withWebhookUrl(webhook)
          .withDisplayName(displayName)
          .withDeveloperEmail(developerEmail);
    }
    store.upsert(saved);

    Toast.makeText(this, R.string.bmchat_email_bot_saved, Toast.LENGTH_SHORT).show();
    finish();
  }

  private void confirmDelete() {
    if (existing == null) { finish(); return; }
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_email_bot_delete_title)
        .setMessage(getString(R.string.bmchat_email_bot_delete_message, existing.name))
        .setPositiveButton(R.string.delete, (d, w) -> {
          store.delete(existing.id);
          finish();
        })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
