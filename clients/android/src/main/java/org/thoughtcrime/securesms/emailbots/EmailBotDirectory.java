package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mesh-light catalog of <em>public</em> email bots exchanged via e-mail.
 *
 * <p>Protocol: first line {@code BMCHAT-BOT-CATALOG v1 etag=<sha256>}, body is
 * compact JSON {@code {"v":1,"bots":[{...}]}}. Receivers merge into
 * {@code ui.bmchat.bot_directory} (synced on multidevice). Publishers send at
 * most one digest per 24h when the catalog changes.
 */
public final class EmailBotDirectory {

  private static final String TAG = "EmailBotDirectory";
  public static final String MARKER_PREFIX = "BMCHAT-BOT-CATALOG v1";
  public static final String UI_DIRECTORY_KEY = "ui.bmchat.bot_directory";
  public static final String UI_LAST_PUBLISH_KEY = "ui.bmchat.bot_directory_last_publish_ms";
  private static final long MIN_PUBLISH_INTERVAL_MS = 24L * 60L * 60L * 1000L;
  private static final int MAX_OUTBOUND_CONTACTS_PER_DAY = 8;

  private final Context appContext;

  public EmailBotDirectory(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  /**
   * @return {@code true} if the message was consumed as a catalog update.
   */
  public boolean tryIngest(int accountId, @NonNull DcMsg msg) {
    String body = msg.getText();
    if (body == null || body.isEmpty()) return false;
    String first = body.split("\\r?\\n", 2)[0].trim();
    if (!first.startsWith(MARKER_PREFIX)) return false;
    String etag = "";
    int etagIdx = first.indexOf("etag=");
    if (etagIdx >= 0) {
      etag = first.substring(etagIdx + 5).trim();
    }
    String json = body.contains("\n") ? body.substring(body.indexOf('\n') + 1).trim() : "";
    if (json.isEmpty() && body.trim().startsWith("{")) json = body.trim();
    try {
      mergeCatalog(accountId, etag, new JSONObject(json));
      return true;
    } catch (Throwable t) {
      Log.w(TAG, "catalog ingest failed", t);
      return false;
    }
  }

  @NonNull
  public List<DirectoryEntry> getEntries(int accountId) {
    try {
      DcContext dc = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (dc == null || !dc.isOk()) return Collections.emptyList();
      String raw = dc.getConfig(UI_DIRECTORY_KEY);
      if (raw == null || raw.isEmpty()) return Collections.emptyList();
      String opened = EmailBotCrypto.openJson(appContext, accountId, raw);
      if (opened == null || opened.isEmpty()) return Collections.emptyList();
      JSONObject root = new JSONObject(opened);
      JSONArray arr = root.optJSONArray("bots");
      if (arr == null) return Collections.emptyList();
      List<DirectoryEntry> out = new ArrayList<>();
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.optJSONObject(i);
        if (o == null) continue;
        String name = o.optString("name", "");
        if (name.isEmpty()) continue;
        out.add(new DirectoryEntry(
            name,
            o.optString("displayName", name),
            o.optString("description", ""),
            o.optString("botEmail", EmailBotContactHelper.makeBotEmail(name)),
            o.optString("publisher", "")));
      }
      return out;
    } catch (Throwable t) {
      Log.w(TAG, "getEntries failed", t);
      return Collections.emptyList();
    }
  }

  /**
   * Mirrors the public bot catalog into encrypted self-chat for multidevice.
   */
  public void publishToSelfChat(int accountId) {
    try {
      DcContext dc = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (dc == null || !dc.isOk()) return;
      String raw = dc.getConfig(UI_DIRECTORY_KEY);
      if (raw == null || raw.isEmpty()) return;
      String opened = EmailBotCrypto.openJson(appContext, accountId, raw);
      if (opened == null || opened.isEmpty()) return;
      JSONObject catalog = new JSONObject(opened);
      String etag = catalog.optString("etag", sha256(opened));
      String payload = MARKER_PREFIX + " etag=" + etag + "\n" + catalog.toString();
      int selfChat = dc.getChatIdByContactId(DcContact.DC_CONTACT_ID_SELF);
      if (selfChat <= 0) selfChat = dc.createChatByContactId(DcContact.DC_CONTACT_ID_SELF);
      if (selfChat <= 0) return;
      dc.sendTextMsg(selfChat, payload);
    } catch (Throwable t) {
      Log.w(TAG, "publishToSelfChat failed", t);
    }
  }

  /**
   * Publishes enabled bots to ui-config and optionally gossips one small
   * e-mail per day to a few recent contacts (bounded traffic).
   */
  public void publishIfNeeded(int accountId, @NonNull List<EmailBotConfig> bots) {
    try {
      DcContext dc = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (dc == null || !dc.isOk()) return;

      JSONArray pub = new JSONArray();
      for (EmailBotConfig b : bots) {
        if (!b.enabled) continue;
        JSONObject o = new JSONObject();
        o.put("name", b.name);
        o.put("displayName", b.displayName);
        if (b.description != null) o.put("description", b.description);
        o.put("botEmail", EmailBotContactHelper.makeBotEmail(b.name));
        pub.put(o);
      }
      if (pub.length() == 0) return;

      String etag = sha256(pub.toString());
      JSONObject catalog = new JSONObject();
      catalog.put("v", 1);
      catalog.put("etag", etag);
      catalog.put("bots", pub);
      catalog.put("updatedAtMs", System.currentTimeMillis());
      dc.setConfig(UI_DIRECTORY_KEY,
          EmailBotCrypto.sealJson(appContext, accountId, catalog.toString()));
      publishToSelfChat(accountId);

      long last = 0L;
      try {
        String lastRaw = dc.getConfig(UI_LAST_PUBLISH_KEY);
        if (lastRaw != null) last = Long.parseLong(lastRaw);
      } catch (Throwable ignored) {}
      if (System.currentTimeMillis() - last < MIN_PUBLISH_INTERVAL_MS) return;

      // Catalog lives in ui-config (multidevice). Optional gossip: one small mail
      // per day to a few contacts when ui.bmchat.bot_directory_gossip=1.
      String gossip = dc.getConfig("ui.bmchat.bot_directory_gossip");
      if ("1".equals(gossip)) {
        String payload = MARKER_PREFIX + " etag=" + etag + "\n" + catalog.toString();
        gossipToContacts(dc, payload);
        dc.setConfig(UI_LAST_PUBLISH_KEY, Long.toString(System.currentTimeMillis()));
      }
    } catch (Throwable t) {
      Log.w(TAG, "publishIfNeeded failed", t);
    }
  }

  private void gossipToContacts(@NonNull DcContext dc, @NonNull String payload) {
    int[] contactIds = dc.getContacts(0, null);
    if (contactIds == null || contactIds.length == 0) return;
    int sent = 0;
    for (int cid : contactIds) {
      if (sent >= MAX_OUTBOUND_CONTACTS_PER_DAY) break;
      if (cid <= 0 || cid == DcContact.DC_CONTACT_ID_SELF) continue;
      try {
        int chatId = dc.getChatIdByContactId(cid);
        if (chatId <= 0) chatId = dc.createChatByContactId(cid);
        if (chatId <= 0) continue;
        dc.sendTextMsg(chatId, payload);
        sent++;
      } catch (Throwable t) {
        Log.w(TAG, "gossip catalog to contact " + cid, t);
      }
    }
  }

  private void mergeCatalog(int accountId, @NonNull String etag, @NonNull JSONObject incoming)
      throws Exception {
    DcContext dc = DcHelper.getAccounts(appContext).getAccount(accountId);
    if (dc == null || !dc.isOk()) return;

    Map<String, JSONObject> merged = new LinkedHashMap<>();
    String existingRaw = dc.getConfig(UI_DIRECTORY_KEY);
    if (existingRaw != null && !existingRaw.isEmpty()) {
      String opened = EmailBotCrypto.openJson(appContext, accountId, existingRaw);
      if (opened == null) opened = existingRaw;
      JSONObject existing = new JSONObject(opened);
      JSONArray arr = existing.optJSONArray("bots");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.optJSONObject(i);
          if (o != null) merged.put(o.optString("name", "").toLowerCase(Locale.ROOT), o);
        }
      }
    }
    JSONArray incBots = incoming.optJSONArray("bots");
    if (incBots != null) {
      for (int i = 0; i < incBots.length(); i++) {
        JSONObject o = incBots.optJSONObject(i);
        if (o == null) continue;
        String name = o.optString("name", "").toLowerCase(Locale.ROOT);
        if (!name.isEmpty()) merged.put(name, o);
      }
    }
    JSONArray out = new JSONArray();
    for (JSONObject o : merged.values()) out.put(o);
    JSONObject root = new JSONObject();
    root.put("v", 1);
    root.put("etag", etag.isEmpty() ? sha256(out.toString()) : etag);
    root.put("bots", out);
    root.put("updatedAtMs", System.currentTimeMillis());
    dc.setConfig(UI_DIRECTORY_KEY,
        EmailBotCrypto.sealJson(appContext, accountId, root.toString()));
  }

  @NonNull
  private static String sha256(@NonNull String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : dig) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Throwable t) {
      return Integer.toHexString(s.hashCode());
    }
  }

  public static final class DirectoryEntry {
    @NonNull public final String name;
    @NonNull public final String displayName;
    @NonNull public final String description;
    @NonNull public final String botEmail;
    @NonNull public final String publisher;

    DirectoryEntry(@NonNull String name,
                   @NonNull String displayName,
                   @NonNull String description,
                   @NonNull String botEmail,
                   @NonNull String publisher) {
      this.name = name;
      this.displayName = displayName;
      this.description = description;
      this.botEmail = botEmail;
      this.publisher = publisher;
    }
  }
}
