package org.thoughtcrime.securesms.contacts;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BMChat "contact transfer code".
 *
 * <p>Lets a user hand over a contact (an e-mail address plus optional display
 * name) to another BMChat user via a short, copy-pasteable code instead of a
 * plaintext address. The payload is encrypted with AES-GCM so the e-mail is not
 * visible in the code itself; every BMChat build shares the same obfuscation
 * key, so any BMChat client can decode a code produced by another one.
 *
 * <p>This is a product-level convenience on top of {@code createContact} – it is
 * NOT a SecureJoin invite and does not transfer encryption keys. For full
 * verified contact exchange the invite link / QR flow should still be used.
 *
 * <p>Format: {@code BMCC1:<base64url(iv[12] || ciphertext+tag)>}
 */
public final class ContactTransferCode {

  public static final String PREFIX = "BMCC1:";

  // Static obfuscation key shared by all BMChat builds. This is intentionally
  // not a secret – its only purpose is to keep the e-mail out of plaintext in
  // the transfer code so it is not trivially readable / scrapeable.
  private static final byte[] KEY = {
      (byte) 0x42, (byte) 0x4d, (byte) 0x43, (byte) 0x68, (byte) 0x61, (byte) 0x74,
      (byte) 0x2d, (byte) 0x63, (byte) 0x6f, (byte) 0x6e, (byte) 0x74, (byte) 0x61,
      (byte) 0x63, (byte) 0x74, (byte) 0x21, (byte) 0x5f
  };

  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final int B64 = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

  private ContactTransferCode() {}

  public static final class Decoded {
    public final String addr;
    public final String name;

    Decoded(String addr, String name) {
      this.addr = addr;
      this.name = name;
    }
  }

  public static boolean looksLikeCode(@Nullable String input) {
    return input != null && input.trim().startsWith(PREFIX);
  }

  /** Builds a transfer code for the given address and (optional) display name. */
  @Nullable
  public static String encode(String addr, @Nullable String name) {
    if (addr == null || addr.isEmpty()) {
      return null;
    }
    try {
      // payload: addr "\n" name  (name may be empty)
      String payload = addr + "\n" + (name == null ? "" : name);
      byte[] plain = payload.getBytes(StandardCharsets.UTF_8);

      byte[] iv = new byte[IV_LEN];
      new SecureRandom().nextBytes(iv);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plain);

      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);

      return PREFIX + Base64.encodeToString(out, B64);
    } catch (Exception e) {
      return null;
    }
  }

  /** Decodes a transfer code; returns null if it is not a valid BMChat code. */
  @Nullable
  public static Decoded decode(@Nullable String input) {
    if (!looksLikeCode(input)) {
      return null;
    }
    try {
      String body = input.trim().substring(PREFIX.length());
      byte[] raw = Base64.decode(body, B64);
      if (raw.length <= IV_LEN) {
        return null;
      }
      byte[] iv = new byte[IV_LEN];
      System.arraycopy(raw, 0, iv, 0, IV_LEN);
      byte[] ct = new byte[raw.length - IV_LEN];
      System.arraycopy(raw, IV_LEN, ct, 0, ct.length);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"),
          new GCMParameterSpec(TAG_BITS, iv));
      byte[] plain = cipher.doFinal(ct);

      String payload = new String(plain, StandardCharsets.UTF_8);
      int nl = payload.indexOf('\n');
      String addr = nl >= 0 ? payload.substring(0, nl) : payload;
      String name = nl >= 0 ? payload.substring(nl + 1) : "";
      if (addr.isEmpty()) {
        return null;
      }
      return new Decoded(addr, name);
    } catch (Exception e) {
      return null;
    }
  }
}
