package org.thoughtcrime.securesms;

import android.os.Bundle;
import android.view.Menu;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;

public class ConnectivityActivity extends WebViewActivity implements DcEventCenter.DcEventDelegate {
  @Override
  protected void onCreate(Bundle state, boolean ready) {
    super.onCreate(state, ready);
    setForceDark();
    getSupportActionBar().setTitle(R.string.connectivity);
    refresh();

    DcHelper.getEventCenter(this).addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(this).removeObservers(this);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // do not call super.onPrepareOptionsMenu() as the default "Search" menu is not needed
    return true;
  }

  private void refresh() {
    String html = DcHelper.getContext(this).getConnectivityHtml();
    // BMChat is e-mail-first, so the upstream "Хранилище на сервере / Storage
    // on server" reporting that surfaces a stand-alone "Не поддерживается
    // вашим провайдером" / "Not supported by your provider" line for plain
    // IMAP servers (it lives next to "Входящие сообщения" with no header,
    // see screenshot from May 8) is just visual noise here. Strip the bare
    // status line — and any sibling section headers that now contain only
    // that line — before rendering.
    html =
        html
            .replaceAll(
                "(?si)<[^>]*>\\s*Не поддерживается вашим провайдером\\.?\\s*</[^>]*>",
                "")
            .replaceAll(
                "(?si)<[^>]*>\\s*Not supported by your provider\\.?\\s*</[^>]*>",
                "")
            // After the bullet is gone an empty <h3>Storage on Server</h3>
            // would still take a slot — drop any header followed only by
            // whitespace and the next header.
            .replaceAll(
                "(?si)<h3[^>]*>[^<]*</h3>\\s*(?=<h3|</body)",
                "");
    html = html.replace("</style>", " html { color-scheme: dark light; }</style>");
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    refresh();
  }
}
