package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.connect.DcHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts email-bot payloads at rest (ui-config, prefs, sync blobs).
 *
 * <p>Key is derived per account from the configured mailbox address so tokens,
 * webhook URLs and command tables are not stored in plaintext on disk. For
 * transport between the user's own devices, {@link EmailBotSync} additionally
 * copies the ciphertext through the encrypted self-chat (same path as normal
 * multidevice mail).
 */
public final class EmailBotCrypto {

  private static final String TAG = "EmailBotCrypto";
  public static final String PREFIX = "BMCHAT-ENC1:";
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int B64 = Base64.NO_WRAP;

  private EmailBotCrypto() {}

  @NonNull
  public static String encrypt(@NonNull Context context, int accountId, @NonNull String plain) {
    try {
      byte[] key = deriveKey(context, accountId);
      byte[] iv = new byte[IV_LEN];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return PREFIX + Base64.encodeToString(out, B64);
    } catch (Throwable t) {
      Log.w(TAG, "encrypt failed", t);
      return plain;
    }
  }

  @NonNull
  public static String decrypt(@NonNull Context context, int accountId, @NonNull String stored) {
    if (!stored.startsWith(PREFIX)) {
      return stored;
    }
    try {
      byte[] raw = Base64.decode(stored.substring(PREFIX.length()), B64);
      if (raw.length <= IV_LEN) return stored;
      byte[] iv = new byte[IV_LEN];
      System.arraycopy(raw, 0, iv, 0, IV_LEN);
      byte[] ct = new byte[raw.length - IV_LEN];
      System.arraycopy(raw, IV_LEN, ct, 0, ct.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(context, accountId), "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    } catch (Throwable t) {
      Log.w(TAG, "decrypt failed", t);
      return stored;
    }
  }

  /** Wraps JSON for ui-config: {@code {"v":1,"enc":"BMCHAT-ENC1:…"}}. */
  @NonNull
  public static String sealJson(@NonNull Context context, int accountId, @NonNull String json) {
    try {
      org.json.JSONObject o = new org.json.JSONObject();
      o.put("v", 1);
      o.put("enc", encrypt(context, accountId, json));
      return o.toString();
    } catch (Throwable t) {
      Log.w(TAG, "sealJson failed", t);
      return json;
    }
  }

  /** Unwraps sealed ui-config; accepts legacy plaintext {@code {"bots":[…]}}. */
  @Nullable
  public static String openJson(@NonNull Context context, int accountId, @Nullable String raw) {
    if (raw == null || raw.isEmpty()) return null;
    try {
      if (raw.startsWith(PREFIX)) {
        return decrypt(context, accountId, raw);
      }
      org.json.JSONObject o = new org.json.JSONObject(raw);
      if (o.has("enc")) {
        return decrypt(context, accountId, o.getString("enc"));
      }
      return raw;
    } catch (Throwable t) {
      return raw;
    }
  }

  @NonNull
  private static byte[] deriveKey(@NonNull Context context, int accountId) throws Exception {
    DcContext dc = DcHelper.getAccounts(context).getAccount(accountId);
    String addr = "";
    if (dc != null && dc.isOk()) {
      String cfg = dc.getConfig("configured_addr");
      if (cfg != null) addr = cfg.trim().toLowerCase();
    }
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update("bmchat-email-bot-v1|".getBytes(StandardCharsets.UTF_8));
    md.update(Integer.toString(accountId).getBytes(StandardCharsets.UTF_8));
    md.update("|".getBytes(StandardCharsets.UTF_8));
    md.update(addr.getBytes(StandardCharsets.UTF_8));
    return md.digest();
  }
}
