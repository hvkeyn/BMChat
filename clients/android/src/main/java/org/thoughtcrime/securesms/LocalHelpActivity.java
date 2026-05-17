package org.thoughtcrime.securesms;

import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import androidx.activity.OnBackPressedCallback;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;
import org.thoughtcrime.securesms.util.TextUtil;
import org.thoughtcrime.securesms.util.Util;

public class LocalHelpActivity extends WebViewActivity {
  private static final String TAG = "LocalHelpActivity";
  public static final String SECTION_EXTRA = "section_extra";

  @Override
  protected boolean allowInLockedMode() {
    return true;
  }

  @Override
  protected void onCreate(Bundle state, boolean ready) {
    super.onCreate(state, ready);
    setForceDark();
    getSupportActionBar().setTitle(getString(R.string.menu_help));

    String section = getIntent().getStringExtra(SECTION_EXTRA);
    String helpPath = "help/LANG/help.html";
    String helpLang = "en";
    try {
      Locale locale = Util.getLocale();
      String appLang = locale.getLanguage();
      String appCountry = locale.getCountry();
      if (assetExists(helpPath.replace("LANG", appLang))) {
        helpLang = appLang;
      } else if (assetExists(helpPath.replace("LANG", appLang + "_" + appCountry))) {
        helpLang = appLang + "_" + appCountry;
      } else {
        appLang = appLang.substring(0, 2);
        if (assetExists(helpPath.replace("LANG", appLang))) {
          helpLang = appLang;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                  webView.goBack();
                } else {
                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                }
              }
            });

    String resolvedPath = helpPath.replace("LANG", helpLang);
    try {
      String html = readAssetAsUtf8(resolvedPath);
      webView.loadDataWithBaseURL(
          "file:///android_asset/help/" + helpLang + "/",
          html,
          "text/html; charset=UTF-8",
          "UTF-8",
          null);
      if (section != null && section.startsWith("#")) {
        String hash = JSONObject.quote(section.substring(1));
        webView.postDelayed(() -> webView.loadUrl("javascript:location.hash=" + hash), 100);
      }
    } catch (IOException e) {
      Log.w(TAG, "failed to load help as UTF-8, falling back to file URL", e);
      webView.loadUrl("file:///android_asset/" + resolvedPath + (section != null ? section : ""));
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    super.onPrepareOptionsMenu(menu);
    this.getMenuInflater().inflate(R.menu.local_help, menu);

    MenuItem item = menu.findItem(R.id.report_issue);
    if (item != null) {
      item.setTitle(TextUtil.markAsExternal(getString(R.string.global_menu_help_report_desktop)));
    }

    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);
    int itemId = item.getItemId();
    if (itemId == R.id.log_scroll_up) {
      webView.scrollTo(0, 0);
      return true;
    } else if (itemId == R.id.report_issue) {
      openOnlineUrl("https://github.com/hvkeyn/BMChat/issues");
      return true;
    }
    return false;
  }

  private boolean assetExists(String fileName) {
    // test using AssetManager.open();
    // AssetManager.list() is unreliable eg. on my Android 7 Moto G
    // and also reported to be pretty slow.
    boolean exists = false;
    try {
      AssetManager assetManager = getResources().getAssets();
      InputStream is = assetManager.open(fileName);
      exists = true;
      is.close();
    } catch (Exception e) {
      ; // a non-existent asset is no error, the function's purpose is to check exactly that.
    }
    return exists;
  }

  private String readAssetAsUtf8(String fileName) throws IOException {
    try (InputStream input = getResources().getAssets().open(fileName);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      String html = output.toString(StandardCharsets.UTF_8.name());
      if (!html.isEmpty() && html.charAt(0) == '\uFEFF') {
        html = html.substring(1);
      }
      return html;
    }
  }
}
