package org.thoughtcrime.securesms.quote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.thoughtcrime.securesms.R;

/**
 * Telegram-parity "select part of a message to quote" screen.
 *
 * <p>This is intentionally a barebones {@link AppCompatActivity}
 * (NOT {@code PassphraseRequiredActionBarActivity}) and it does NOT
 * apply our {@code DynamicNoActionBarTheme}. The reason: 2.49.41
 * shipped a version that mixed PassphraseRequiredActionBarActivity
 * + DynamicNoActionBarTheme + setSupportActionBar(toolbar), and on
 * Samsung One UI that combination got stuck in a finish/startActivity
 * loop driven by DynamicTheme.onResume(), spawning literally a
 * hundred ActivityRecord create/destroy entries before the system
 * gave up and yanked the app from the stack (what the user saw as
 * "freezes and exits").
 *
 * <p>Now we keep it dead simple: theme comes from the manifest
 * (no-action-bar), the Toolbar is used as a plain view (no
 * {@code setSupportActionBar}), the back arrow on it is wired
 * manually to {@code finish()}. The body TextView is selectable and
 * we add a single custom "Цитировать выделение" entry to the
 * platform selection floating menu.
 */
public final class QuoteSelectorActivity extends AppCompatActivity {

  public static final String EXTRA_AUTHOR = "author";
  public static final String EXTRA_BODY   = "body";
  /** Result extra carrying the chosen fragment back to the caller. */
  public static final String RESULT_FRAGMENT = "fragment";

  private TextView bodyView;

  public static @NonNull Intent newIntent(
      @NonNull Context ctx,
      @NonNull String author,
      @NonNull String body) {
    return new Intent(ctx, QuoteSelectorActivity.class)
        .putExtra(EXTRA_AUTHOR, author)
        .putExtra(EXTRA_BODY,   body);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_quote_selector);

    Toolbar toolbar = findViewById(R.id.quote_selector_toolbar);
    toolbar.setTitle(R.string.bmchat_quote_selector_title);
    toolbar.setSubtitle(R.string.bmchat_quote_selector_subtitle);
    toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp);
    toolbar.setNavigationOnClickListener(v -> {
      setResult(RESULT_CANCELED);
      finish();
    });

    String author = getIntent().getStringExtra(EXTRA_AUTHOR);
    String body   = getIntent().getStringExtra(EXTRA_BODY);
    if (body == null) body = "";

    TextView authorView = findViewById(R.id.quote_selector_author);
    bodyView            = findViewById(R.id.quote_selector_body);
    Button confirmButton = findViewById(R.id.quote_selector_confirm);

    if (!TextUtils.isEmpty(author)) {
      authorView.setText(author);
    } else {
      authorView.setVisibility(View.GONE);
    }
    bodyView.setText(body);

    bodyView.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
      @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        MenuInflater inflater = mode.getMenuInflater();
        if (inflater != null) {
          inflater.inflate(R.menu.quote_selector_selection, menu);
        }
        return true;
      }
      @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
      }
      @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (item.getItemId() == R.id.menu_quote_selected_part) {
          finishWithSelection();
          return true;
        }
        return false;
      }
      @Override public void onDestroyActionMode(ActionMode mode) {}
    });

    confirmButton.setOnClickListener(v -> finishWithSelection());
  }

  @Override
  public void onBackPressed() {
    setResult(RESULT_CANCELED);
    super.onBackPressed();
  }

  /**
   * Pick whichever range is highlighted; if nothing is selected,
   * return the whole body. Trim trailing whitespace so the receiver
   * does not have to do it.
   */
  private void finishWithSelection() {
    String full = bodyView.getText() != null ? bodyView.getText().toString() : "";
    int s = Math.max(0, bodyView.getSelectionStart());
    int e = Math.min(full.length(), bodyView.getSelectionEnd());
    String fragment;
    if (e > s) {
      fragment = full.substring(s, e).trim();
    } else {
      fragment = full.trim();
    }
    if (fragment.isEmpty()) {
      setResult(RESULT_CANCELED);
    } else {
      Intent data = new Intent().putExtra(RESULT_FRAGMENT, fragment);
      setResult(RESULT_OK, data);
    }
    finish();
  }
}
