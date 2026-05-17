package org.thoughtcrime.securesms.bots;

import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Tiny Telegram Bot API client over plain {@link HttpURLConnection}.
 *
 * <p>Implements only the methods BMChat actually needs:
 * <ul>
 *     <li>{@code getMe} — used during bot registration to verify the
 *         token and to cache the bot's display name + avatar,
 *     <li>{@code getUpdates} — long-polling pull of incoming messages,
 *     <li>{@code getFile} + {@code download} — fetch attachment files
 *         (photo, document, video, voice, sticker, …).
 * </ul>
 *
 * <p>All calls are blocking and meant to run from a worker thread. The
 * {@link #downloadFile(String, File)} helper streams to disk so a
 * 50 MB Telegram document doesn't load into RAM.
 */
public final class TelegramApi {

  private static final String TAG = "TelegramApi";
  /**
   * Base URL for Telegram Bot API methods. BMChat now routes bot
   * traffic through the BMChat infrastructure VPS, which fronts a
   * locally-hosted {@code tdlib/telegram-bot-api} server in
   * {@code --local} mode. That lifts the 20 MB {@code getFile} cap
   * that the cloud Bot API enforces — videos / documents up to ~2 GB
   * become downloadable, exactly like in the official Telegram apps
   * (which use MTProto directly).
   *
   * <p>Note this is plain HTTP (cleartext is explicitly allowed for
   * 5.187.4.132 in {@code network_security_config.xml}). The body
   * uses standard HTTPS once we get a real domain.
   */
  private static final String API_BASE = "http://5.187.4.132/bot-api/bot";
  private static final String FILE_BASE = "http://5.187.4.132/bot-api/file/bot";

  /** Maximum getUpdates long-poll wait. 25 s is comfortably below
   *  Telegram's 50 s server cap and the typical Android socket timeout. */
  public static final int LONG_POLL_TIMEOUT_S = 25;

  private final String token;

  public TelegramApi(@NonNull String token) {
    this.token = token;
  }

  // ---------------------------------------------------------------------
  //  high-level methods
  // ---------------------------------------------------------------------

  /** {@code getMe} — confirms that the token is valid and reachable. */
  @AnyThread
  @Nullable
  public JSONObject getMe() throws IOException {
    return callApi("getMe", null);
  }

  /** {@code getMyName} — bot's full display name as set in BotFather. */
  @AnyThread
  @Nullable
  public String getMyName() {
    try {
      JSONObject resp = callApi("getMyName", null);
      JSONObject result = resp != null ? resp.optJSONObject("result") : null;
      return result != null ? result.optString("name", null) : null;
    } catch (Throwable t) {
      Log.w(TAG, "getMyName failed", t);
      return null;
    }
  }

  /** {@code getMyDescription} — long bio shown on bot's profile page. */
  @AnyThread
  @Nullable
  public String getMyDescription() {
    try {
      JSONObject resp = callApi("getMyDescription", null);
      JSONObject result = resp != null ? resp.optJSONObject("result") : null;
      String desc = result != null ? result.optString("description", "") : "";
      return desc.isEmpty() ? null : desc;
    } catch (Throwable t) {
      Log.w(TAG, "getMyDescription failed", t);
      return null;
    }
  }

  /** {@code getMyShortDescription} — short bio used as the under-name hint. */
  @AnyThread
  @Nullable
  public String getMyShortDescription() {
    try {
      JSONObject resp = callApi("getMyShortDescription", null);
      JSONObject result = resp != null ? resp.optJSONObject("result") : null;
      String desc = result != null ? result.optString("short_description", "") : "";
      return desc.isEmpty() ? null : desc;
    } catch (Throwable t) {
      Log.w(TAG, "getMyShortDescription failed", t);
      return null;
    }
  }

  /**
   * {@code getMyCommands} — list of commands registered with BotFather.
   * Returns an array of {@code {command, description}} pairs, or {@code null}
   * when the bot has no commands set up.
   */
  @AnyThread
  @Nullable
  public org.json.JSONArray getMyCommands() {
    try {
      JSONObject resp = callApi("getMyCommands", null);
      if (resp == null) return null;
      org.json.JSONArray result = resp.optJSONArray("result");
      return result != null && result.length() > 0 ? result : null;
    } catch (Throwable t) {
      Log.w(TAG, "getMyCommands failed", t);
      return null;
    }
  }

  /**
   * {@code getUpdates} — long-poll incoming events.
   *
   * @param offset  next update id to fetch (returned by Telegram on the
   *                previous call, plus 1). Use 0 to get the latest queue.
   * @param timeout long-poll timeout in seconds. Use 0 for an immediate
   *                non-blocking poll.
   */
  @AnyThread
  @Nullable
  public JSONObject getUpdates(long offset, int timeout) throws IOException {
    String params = "offset=" + offset
        + "&timeout=" + timeout
        + "&allowed_updates=" + URLEncoder.encode(
            "[\"message\",\"channel_post\",\"edited_message\","
                + "\"edited_channel_post\"]", "UTF-8");
    return callApi("getUpdates", params);
  }

  /**
   * {@code getFile} — resolves a Telegram file_id to a downloadable
   * {@code file_path}. Pass that path to {@link #downloadFile(String, File)}.
   */
  @AnyThread
  @Nullable
  public String getFilePath(String fileId) throws IOException {
    JSONObject resp = callApi("getFile",
        "file_id=" + URLEncoder.encode(fileId, "UTF-8"));
    if (resp == null) return null;
    JSONObject result = resp.optJSONObject("result");
    if (result == null) return null;
    return result.optString("file_path", null);
  }

  /**
   * Download the bot's profile photo into {@code dest}. Returns the file
   * if a profile photo exists, {@code null} otherwise (or on any error).
   *
   * <p>Used at "Add bot" time to populate {@link BotConfig#avatarPath}
   * and to set the per-bot chat profile image so the bot appears with
   * its own avatar in the BMChat chat list.
   */
  @AnyThread
  @Nullable
  public java.io.File downloadProfilePhoto(long botUserId, java.io.File dest) {
    try {
      JSONObject resp = callApi("getUserProfilePhotos",
          "user_id=" + botUserId + "&limit=1");
      if (resp == null) return null;
      JSONObject result = resp.optJSONObject("result");
      if (result == null) return null;
      org.json.JSONArray photos = result.optJSONArray("photos");
      if (photos == null || photos.length() == 0) return null;
      org.json.JSONArray sizes = photos.optJSONArray(0);
      if (sizes == null || sizes.length() == 0) return null;
      // pick the largest size
      JSONObject biggest = null;
      int biggestArea = 0;
      for (int i = 0; i < sizes.length(); i++) {
        JSONObject s = sizes.optJSONObject(i);
        if (s == null) continue;
        int w = s.optInt("width", 0);
        int h = s.optInt("height", 0);
        if (w * h >= biggestArea) {
          biggestArea = w * h;
          biggest = s;
        }
      }
      if (biggest == null) return null;
      String pickedFileId = biggest.optString("file_id");
      String filePath = getFilePath(pickedFileId);
      if (filePath == null) return null;
      return downloadFile(filePath, pickedFileId, dest);
    } catch (Throwable t) {
      Log.w(TAG, "downloadProfilePhoto failed", t);
      return null;
    }
  }

  /**
   * Download a file resolved by {@link #getFilePath(String)} to {@code dest}.
   * Returns the destination on success.
   *
   * <p>Handles three transport shapes transparently:
   *
   * <ul>
   *   <li><b>Cloud Bot API</b> (legacy) — file_path is a relative
   *       CDN-style string, fetched over {@link #FILE_BASE}.</li>
   *   <li><b>Local --local Bot API server</b> — file_path is an
   *       absolute filesystem path on the VPS. The local Bot API
   *       server does not serve absolute paths over /file/, so we
   *       route the download through {@code bmchat-tgproxy} which
   *       knows how to read straight from disk. {@code fileId} is
   *       required so we can build the AES-GCM-signed proxy URL.</li>
   *   <li>{@code fileId == null} preserves the old behaviour for
   *       callers that only have a path (e.g. internal helpers).</li>
   * </ul>
   */
  @AnyThread
  public File downloadFile(String filePath, File dest) throws IOException {
    return downloadFile(filePath, null, dest);
  }

  @AnyThread
  public File downloadFile(String filePath, @Nullable String fileId, File dest)
      throws IOException {
    final String urlStr;
    if (filePath != null && filePath.startsWith("/") && fileId != null) {
      // Local Bot API server returned an absolute path. The
      // signed BMChat tgproxy URL points at a streaming endpoint
      // that resolves the same file_id server-side and serves the
      // bytes off disk (Range-aware). No bot token in the URL.
      urlStr = TelegramProxy.buildUrl(token, fileId, null, null);
      if (urlStr == null || urlStr.isEmpty()) {
        throw new IOException("download via proxy: failed to sign URL");
      }
    } else {
      urlStr = FILE_BASE + token + "/" + filePath;
    }
    URL url = new URL(urlStr);
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(15_000);
      conn.setReadTimeout(60_000);
      int code = conn.getResponseCode();
      if (code != 200) throw new IOException("download HTTP " + code);
      try (InputStream in = new BufferedInputStream(conn.getInputStream());
           FileOutputStream out = new FileOutputStream(dest)) {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
      }
      return dest;
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  // ---------------------------------------------------------------------
  //  low-level helpers
  // ---------------------------------------------------------------------

  /** Call any Telegram method, return the parsed JSON or {@code null} on error. */
  @AnyThread
  @Nullable
  private JSONObject callApi(String method, @Nullable String urlEncodedParams)
      throws IOException {
    String urlStr = API_BASE + token + "/" + method
        + (urlEncodedParams != null ? "?" + urlEncodedParams : "");
    URL url = new URL(urlStr);
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(15_000);
      // long-poll requires the read timeout to be larger than the
      // {@code timeout} parameter we pass to getUpdates.
      conn.setReadTimeout((LONG_POLL_TIMEOUT_S + 10) * 1000);
      conn.setRequestProperty("Accept", "application/json");
      int code = conn.getResponseCode();
      InputStream is = code >= 200 && code < 400
          ? conn.getInputStream()
          : conn.getErrorStream();
      byte[] body = readAll(is, 16 * 1024 * 1024); // 16 MB cap on a single response
      String text = new String(body, StandardCharsets.UTF_8);
      JSONObject json = new JSONObject(text);
      if (!json.optBoolean("ok", false)) {
        String err = json.optString("description", "<no description>");
        Log.w(TAG, "Telegram API error " + code + " on " + method + ": " + err);
        if (code == 401 || code == 404) throw new IOException("invalid token");
        return null;
      }
      return json;
    } catch (org.json.JSONException e) {
      throw new IOException("invalid JSON from Telegram", e);
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static byte[] readAll(InputStream in, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] tmp = new byte[8192];
    int total = 0;
    for (int n; (n = in.read(tmp)) > 0; ) {
      total += n;
      if (total > max) throw new IOException("response too large");
      out.write(tmp, 0, n);
    }
    return out.toByteArray();
  }
}
