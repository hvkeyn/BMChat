package org.thoughtcrime.securesms.bmchat;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class RelaySecondDeviceActivity extends BaseActionBarActivity {
  public static final String EXTRA_SID = "sid";
  public static final String EXTRA_KEY = "key";
  public static final String EXTRA_RELAY = "relay";

  private static final String TAG = "BMChatRelaySecondDevice";

  private DcContext dcContext;
  private TextView statusView;
  private TextView codeView;
  private ProgressBar progressBar;
  private String sid;
  private String keyBase64Url;
  private String relayBaseUrl;
  private String code;
  private boolean handledExportFile;

  private final DcEventCenter.DcEventDelegate imexDelegate =
      new DcEventCenter.DcEventDelegate() {
        @Override
        public void handleEvent(@NonNull DcEvent event) {
          if (event.getAccountId() != dcContext.getAccountId()) return;
          if (event.getId() == DcContext.DC_EVENT_IMEX_PROGRESS) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(Math.max(0, Math.min(1000, event.getData1Int())));
          } else if (event.getId() == DcContext.DC_EVENT_IMEX_FILE_WRITTEN && !handledExportFile) {
            handledExportFile = true;
            File backupFile = new File(event.getData2Str());
            uploadBackup(backupFile);
          }
        }
      };

  @Override
  protected void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    dcContext = DcHelper.getContext(this);
    sid = getIntent().getStringExtra(EXTRA_SID);
    keyBase64Url = getIntent().getStringExtra(EXTRA_KEY);
    relayBaseUrl = getIntent().getStringExtra(EXTRA_RELAY);

    if (sid == null || keyBase64Url == null || relayBaseUrl == null) {
      finishWithError(getString(R.string.bmchat_multidevice_relay_bad_link));
      return;
    }
    if (dcContext == null || dcContext.isConfigured() != 1) {
      finishWithError(getString(R.string.bmchat_multidevice_helper_qr_need_profile));
      return;
    }

    setupUi();
    startExport();
  }

  @Override
  protected void onDestroy() {
    DcHelper.getEventCenter(this).removeObservers(imexDelegate);
    super.onDestroy();
  }

  private void setupUi() {
    setTitle(R.string.multidevice_title);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    int pad = ViewUtil.dpToPx(this, 24);
    root.setPadding(pad, pad, pad, pad);

    statusView = new TextView(this);
    statusView.setText(R.string.bmchat_multidevice_relay_exporting);
    statusView.setTextSize(18);
    statusView.setGravity(Gravity.CENTER);
    root.addView(statusView, new LinearLayout.LayoutParams(-1, -2));

    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setMax(1000);
    progressBar.setIndeterminate(true);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
    progressParams.setMargins(0, ViewUtil.dpToPx(this, 24), 0, ViewUtil.dpToPx(this, 24));
    root.addView(progressBar, progressParams);

    codeView = new TextView(this);
    codeView.setTextSize(36);
    codeView.setGravity(Gravity.CENTER);
    codeView.setLetterSpacing(0.18f);
    codeView.setText("");
    root.addView(codeView, new LinearLayout.LayoutParams(-1, -2));

    TextView hint = new TextView(this);
    hint.setText(R.string.bmchat_multidevice_relay_mobile_hint);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(0, ViewUtil.dpToPx(this, 16), 0, 0);
    root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

    setContentView(root);
  }

  private void startExport() {
    code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
    DcHelper.getEventCenter(this).addObserver(DcContext.DC_EVENT_IMEX_FILE_WRITTEN, imexDelegate);
    DcHelper.getEventCenter(this).addObserver(DcContext.DC_EVENT_IMEX_PROGRESS, imexDelegate);
    Util.runOnBackground(
        () -> {
          try {
            dcContext.imex(DcContext.DC_IMEX_EXPORT_BACKUP, DcHelper.getImexDir().getAbsolutePath());
          } catch (Exception e) {
            Log.e(TAG, "backup export failed", e);
            Util.runOnMain(() -> finishWithError(getString(R.string.error) + ": " + e.getMessage()));
          }
        });
  }

  private void uploadBackup(@NonNull File backupFile) {
    statusView.setText(R.string.bmchat_multidevice_relay_uploading);
    progressBar.setIndeterminate(true);
    Util.runOnBackground(
        () -> {
          try {
            byte[] encrypted = encryptBackup(backupFile);
            String codeHash = sha256Hex(sid + ":" + keyBase64Url + ":" + code);
            URL url =
                new URL(
                    relayBaseUrl.replaceAll("/+$", "")
                        + "/session/"
                        + sid
                        + "/blob?code_hash="
                        + codeHash);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15 * 60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(encrypted.length);
            try (OutputStream out = new BufferedOutputStream(conn.getOutputStream())) {
              out.write(encrypted);
            }
            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
              throw new IllegalStateException("relay HTTP " + responseCode);
            }
            // Remove the temporary plaintext backup once ciphertext is on the relay.
            //noinspection ResultOfMethodCallIgnored
            backupFile.delete();
            Util.runOnMain(
                () -> {
                  progressBar.setIndeterminate(false);
                  progressBar.setProgress(1000);
                  statusView.setText(R.string.bmchat_multidevice_relay_done);
                  codeView.setText(code);
                });
          } catch (Exception e) {
            Log.e(TAG, "relay upload failed", e);
            Util.runOnMain(() -> finishWithError(getString(R.string.error) + ": " + e.getMessage()));
          }
        });
  }

  private byte[] encryptBackup(@NonNull File backupFile) throws Exception {
    byte[] key = decodeBase64Url(keyBase64Url);
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    byte[] input = new byte[(int) backupFile.length()];
    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(backupFile))) {
      int offset = 0;
      while (offset < input.length) {
        int read = in.read(input, offset, input.length - offset);
        if (read < 0) break;
        offset += read;
      }
    }
    byte[] encrypted = cipher.doFinal(input);
    byte[] out = new byte[nonce.length + encrypted.length];
    System.arraycopy(nonce, 0, out, 0, nonce.length);
    System.arraycopy(encrypted, 0, out, nonce.length, encrypted.length);
    return out;
  }

  private static byte[] decodeBase64Url(@NonNull String value) {
    return Base64.decode(value, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
  }

  private static String sha256Hex(@NonNull String value) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
    return sb.toString();
  }

  private void finishWithError(@NonNull String message) {
    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    finish();
  }
}
