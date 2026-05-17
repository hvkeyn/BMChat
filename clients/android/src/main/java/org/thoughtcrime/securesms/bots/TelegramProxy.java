package org.thoughtcrime.securesms.bots;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Build signed, AES-GCM encrypted URLs that point at the BMChat
 * Telegram media proxy on the VPS.
 *
 * <p>Background: Telegram's cloud Bot API silently caps {@code getFile}
 * at ~20&nbsp;MB. Videos forwarded into a bot (and most non-trivial
 * documents) therefore cannot be downloaded by the client directly,
 * and the previous behaviour was to publish a plain "[медиа video не
 * удалось скачать]" fallback — losing the actual content. The proxy
 * lets the VPS download on the client's behalf and stream the bytes
 * back; for files within the 20&nbsp;MB cap that just works, files
 * past it surface a clear error message from the server.
 *
 * <p>Security model. The payload sent to the proxy contains the bot
 * token. We never expose it in plaintext URLs — even though our VPS
 * is only reachable over HTTP today, the URL is captured in chat
 * messages that may be forwarded by recipients. Each URL therefore
 * carries an AES-GCM-256 envelope keyed by {@link #SECRET}, holding
 * the bot token, the Telegram file_id, the media kind and an
 * expiration timestamp. The proxy decrypts with the same secret,
 * rejects URLs whose timestamp passed, and only then performs the
 * Telegram API call. Anyone forwarding the URL after expiry sees a
 * 410 Gone instead of a free download endpoint.
 *
 * <p>The URL format is:
 * <pre>
 *   http://5.187.4.132/tgmedia/&lt;url_safe_b64( nonce || ciphertext_with_tag )&gt;
 * </pre>
 * — i.e. one self-contained token, no server-side state, no
 * registration round-trip.
 */
public final class TelegramProxy {

  private static final String TAG = "TelegramProxy";

  /**
   * Base URL of the BMChat media proxy. The trailing slash is part
   * of the path — clients append the encrypted token to it.
   */
  public static final String PROXY_BASE = "http://5.187.4.132/tgmedia/";

  /**
   * Shared secret. Both this constant and the VPS-side script keep
   * the SAME bytes. Treat it like a deployment key: rotating it
   * means re-deploying both sides at the same time. The value lives
   * in the source tree because BMChat builds are open-source and we
   * have no second runtime store yet; future versions should pull
   * it from a per-build {@code gradle.properties} entry instead.
   */
  private static final String SECRET =
      "9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f"
          + "9e3c8f2a7b4d5e6f1a8b9c0d2e3f4a5b";

  /** AES-GCM expects a 12-byte nonce. */
  private static final int NONCE_LEN = 12;
  /** GCM auth tag, 128 bits. */
  private static final int TAG_BITS = 128;
  /** Default URL lifetime: long enough for chat sync + a couple of
   *  re-reads, short enough that a forwarded message stops working
   *  before the receiver could share it externally. */
  private static final long DEFAULT_TTL_MS = 7L * 24L * 60L * 60L * 1000L; // 7 days

  /**
   * Cache of derived AES keys per secret. Java's SHA-256 call is
   * fast but doing it on every URL build adds up when a 10-image
   * album is published.
   */
  private static final ConcurrentMap<String, byte[]> KEY_CACHE = new ConcurrentHashMap<>();

  private static final SecureRandom RNG = new SecureRandom();

  private TelegramProxy() {}

  /**
   * Returns a signed proxy URL pointing at the given Telegram file,
   * or {@code null} if encryption fails. The URL is safe to embed in
   * a chat message body — recipients tapping it open the file in
   * their browser (or the BMChat in-app browser, which honours
   * BMChat-IP redirects).
   *
   * @param botToken  Telegram Bot API token of the bot that received
   *                  the original update. Must not be empty.
   * @param fileId    Telegram file_id of the attachment.
   * @param mimeType  MIME type from Telegram (used by the proxy as
   *                  the {@code Content-Type} response header).
   * @param fileName  Original filename (proxy uses it as a
   *                  Content-Disposition hint).
   */
  @Nullable
  public static String buildUrl(@NonNull String botToken,
                                @NonNull String fileId,
                                @Nullable String mimeType,
                                @Nullable String fileName) {
    return buildUrl(botToken, fileId, mimeType, fileName, DEFAULT_TTL_MS);
  }

  @Nullable
  public static String buildUrl(@NonNull String botToken,
                                @NonNull String fileId,
                                @Nullable String mimeType,
                                @Nullable String fileName,
                                long ttlMs) {
    if (TextUtils.isEmpty(botToken) || TextUtils.isEmpty(fileId)) return null;
    try {
      JSONObject payload = new JSONObject();
      payload.put("v", 1);
      payload.put("exp", System.currentTimeMillis() + Math.max(60_000L, ttlMs));
      payload.put("t", botToken);
      payload.put("f", fileId);
      if (!TextUtils.isEmpty(mimeType)) payload.put("m", mimeType);
      if (!TextUtils.isEmpty(fileName)) payload.put("n", fileName);

      byte[] plaintext = payload.toString().getBytes(StandardCharsets.UTF_8);
      byte[] nonce = new byte[NONCE_LEN];
      RNG.nextBytes(nonce);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, keyFor(SECRET), new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ct = cipher.doFinal(plaintext);

      byte[] envelope = new byte[nonce.length + ct.length];
      System.arraycopy(nonce, 0, envelope, 0, nonce.length);
      System.arraycopy(ct, 0, envelope, nonce.length, ct.length);

      String token = android.util.Base64.encodeToString(envelope,
          android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
      return PROXY_BASE + token;
    } catch (Throwable t) {
      Log.w(TAG, "buildUrl failed", t);
      return null;
    }
  }

  private static SecretKeySpec keyFor(@NonNull String secret) throws Exception {
    byte[] cached = KEY_CACHE.get(secret);
    if (cached == null) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      cached = md.digest(secret.getBytes(StandardCharsets.UTF_8));
      KEY_CACHE.put(secret, cached);
    }
    return new SecretKeySpec(cached, "AES");
  }
}
