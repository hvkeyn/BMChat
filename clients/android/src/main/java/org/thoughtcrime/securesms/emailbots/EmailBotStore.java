package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent storage for {@link EmailBotConfig}s.
 *
 * <p>Primary store is {@code ui.bmchat.email_bots} on each {@link DcContext}
 * (encrypted at rest; mirrored to other devices via encrypted self-chat sync).
 * SharedPreferences is a local cache
 * only.
 */
public final class EmailBotStore {

  private static final String TAG = "EmailBotStore";
  /** Must match desktop {@code UI_CONFIG_KEY}. */
  public static final String UI_CONFIG_KEY = "ui.bmchat.email_bots";
  private static final String PREFS = "bmchat-email-bots";
  private static final String KEY_LIST = "bots";

  private final Context appContext;

  public EmailBotStore(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  private SharedPreferences prefs() {
    return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /**
   * Reload bots from all accounts' ui-config (call on activity resume).
   */
  public synchronized void reloadFromUiConfig() {
    Map<String, EmailBotConfig> merged = new LinkedHashMap<>();
    // Keep local prefs first so an empty ui-config blob cannot wipe bots.
    for (EmailBotConfig b : readPrefs()) {
      merged.put(b.id, b);
    }
    for (int accountId : DcHelper.getAccounts(appContext).getAll()) {
      DcContext ctx = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (ctx == null || !ctx.isOk()) continue;
      for (EmailBotConfig b : readUiConfigForAccount(ctx.getAccountId(), ctx)) {
        merged.put(b.id, b);
      }
    }
    writePrefs(new ArrayList<>(merged.values()));
  }

  /**
   * Ensures every stored bot owns a <em>local</em> self-only broadcast home
   * chat, migrating legacy 1:1 {@code @bots.bmchat.local} chats that bounced
   * over SMTP. Safe to call repeatedly; only patches ids when something
   * actually changed. Uses {@link #readPrefs()} directly to avoid the
   * reload recursion baked into {@link #getAll()}.
   */
  public synchronized void ensureLocalBotChats() {
    for (EmailBotConfig b : readPrefs()) {
      try {
        EmailBotContactHelper.ensureBotContact(appContext, this, b);
      } catch (Throwable t) {
        Log.w(TAG, "ensureLocalBotChats failed for " + b.id, t);
      }
    }
  }

  @NonNull
  public synchronized List<EmailBotConfig> getAll() {
    reloadFromUiConfig();
    return readPrefs();
  }

  @NonNull
  public synchronized List<EmailBotConfig> getForAccount(int accountId) {
    List<EmailBotConfig> all = getAll();
    List<EmailBotConfig> out = new ArrayList<>(all.size());
    for (EmailBotConfig b : all) {
      if (b.ownerAccountId == accountId) out.add(b);
    }
    return out;
  }

  @Nullable
  public synchronized EmailBotConfig getById(@NonNull String id) {
    for (EmailBotConfig b : getAll()) {
      if (id.equals(b.id)) return b;
    }
    return null;
  }

  @Nullable
  public synchronized EmailBotConfig findByName(int accountId, @NonNull String name) {
    String lower = name.toLowerCase();
    for (EmailBotConfig b : getAll()) {
      if (b.ownerAccountId == accountId && b.name.toLowerCase().equals(lower)) return b;
    }
    return null;
  }

  @Nullable
  public synchronized EmailBotConfig findByChatId(int accountId, int chatId) {
    if (chatId <= 0) return null;
    for (EmailBotConfig b : getAll()) {
      if (b.ownerAccountId == accountId && b.botChatId == chatId) return b;
    }
    try {
      DcContext ctx = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (ctx == null || !ctx.isOk()) return null;
      return EmailBotContactHelper.findBotForHomeChat(ctx, this, accountId, chatId);
    } catch (Throwable t) {
      Log.w(TAG, "findByChatId fallback failed", t);
      return null;
    }
  }

  /** Username must be unique across all bots on this device. */
  public synchronized boolean isNameTakenGlobally(@NonNull String name,
                                                @Nullable String exceptId) {
    String lower = name.toLowerCase(Locale.ROOT);
    for (EmailBotConfig b : getAll()) {
      if (exceptId != null && exceptId.equals(b.id)) continue;
      if (b.name.toLowerCase(Locale.ROOT).equals(lower)) return true;
    }
    return false;
  }

  public synchronized void saveAll(@NonNull List<EmailBotConfig> bots) {
    writePrefs(bots);
    persistUiConfig(bots, true);
  }

  public synchronized void upsert(@NonNull EmailBotConfig updated) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    boolean replaced = false;
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id.equals(updated.id)) {
        all.set(i, updated);
        replaced = true;
        break;
      }
    }
    if (!replaced) all.add(updated);
    saveAll(all);
    try {
      EmailBotContactHelper.ensureBotContact(appContext, this, updated);
      new EmailBotDirectory(appContext).publishIfNeeded(
          updated.ownerAccountId, getForAccount(updated.ownerAccountId));
    } catch (Throwable t) {
      Log.w(TAG, "ensureBotContact after upsert failed", t);
    }
  }

  /** Updates contact/chat ids without re-running contact creation. */
  synchronized void patchContactIds(@NonNull String botId, int contactId, int chatId) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id.equals(botId)) {
        all.set(i, all.get(i).withContactIds(contactId, chatId));
        saveAll(all);
        return;
      }
    }
  }

  public synchronized void delete(@NonNull String id) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    EmailBotConfig removed = null;
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).id.equals(id)) {
        removed = all.get(i);
        all.remove(i);
      }
    }
    saveAll(all);
    if (removed != null && removed.botContactId > 0) {
      try {
        DcContext ctx = DcHelper.getAccounts(appContext).getAccount(removed.ownerAccountId);
        if (ctx != null && ctx.isOk()) {
          ctx.deleteContact(removed.botContactId);
        }
      } catch (Throwable t) {
        Log.w(TAG, "deleteContact failed", t);
      }
    }
  }

  public synchronized void deleteForAccount(int accountId) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).ownerAccountId == accountId) all.remove(i);
    }
    saveAll(all);
  }

  @NonNull
  public static String newId() {
    return UUID.randomUUID().toString();
  }

  // ---------------------------------------------------------------------
  // prefs + ui.bmchat.email_bots
  // ---------------------------------------------------------------------

  @NonNull
  private List<EmailBotConfig> readPrefs() {
    String raw = prefs().getString(KEY_LIST, null);
    if (raw == null) return Collections.emptyList();
    List<EmailBotConfig> out = new ArrayList<>();
    try {
      String json = raw;
      if (raw.startsWith(EmailBotCrypto.PREFIX)) {
        for (int accountId : DcHelper.getAccounts(appContext).getAll()) {
          String dec = EmailBotCrypto.decrypt(appContext, accountId, raw);
          if (!dec.equals(raw)) {
            json = dec;
            break;
          }
        }
      }
      JSONArray arr = new JSONArray(json);
      for (int i = 0; i < arr.length(); i++) {
        try {
          out.add(EmailBotConfig.fromJson(arr.getJSONObject(i)));
        } catch (Throwable t) {
          Log.w(TAG, "skip malformed bot at index " + i, t);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "readPrefs parse failed", t);
    }
    return out;
  }

  private void writePrefs(@NonNull List<EmailBotConfig> bots) {
    JSONArray arr = new JSONArray();
    for (EmailBotConfig b : bots) {
      try {
        arr.put(b.toJson());
      } catch (Throwable t) {
        Log.w(TAG, "skip serialising bot " + b.id, t);
      }
    }
    int accountId = bots.isEmpty() ? 0 : bots.get(0).ownerAccountId;
    String raw = arr.toString();
    if (accountId > 0) {
      raw = EmailBotCrypto.encrypt(appContext, accountId, raw);
    }
    prefs().edit().putString(KEY_LIST, raw).apply();
  }

  @NonNull
  private List<EmailBotConfig> readUiConfigForAccount(int accountId, @NonNull DcContext ctx) {
    List<EmailBotConfig> out = new ArrayList<>();
    try {
      String raw = ctx.getConfig(UI_CONFIG_KEY);
      if (raw == null || raw.isEmpty()) return out;
      String opened = EmailBotCrypto.openJson(
          appContext, ctx.getAccountId(), raw);
      if (opened == null || opened.isEmpty()) return out;
      JSONObject root = new JSONObject(opened);
      JSONArray arr = root.optJSONArray("bots");
      if (arr == null) return out;
      for (int i = 0; i < arr.length(); i++) {
        try {
          out.add(EmailBotConfig.fromJson(arr.getJSONObject(i)));
        } catch (Throwable t) {
          Log.w(TAG, "skip ui-config bot at " + i, t);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "readUiConfigForAccount failed", t);
    }
    return out;
  }

  private void persistUiConfig(@NonNull List<EmailBotConfig> all, boolean publishSync) {
    Map<Integer, JSONArray> byAccount = new LinkedHashMap<>();
    for (EmailBotConfig b : all) {
      JSONArray arr = byAccount.get(b.ownerAccountId);
      if (arr == null) {
        arr = new JSONArray();
        byAccount.put(b.ownerAccountId, arr);
      }
      try {
        arr.put(b.toJson());
      } catch (Throwable t) {
        Log.w(TAG, "skip ui persist for " + b.id, t);
      }
    }
    try {
      for (int accountId : DcHelper.getAccounts(appContext).getAll()) {
        DcContext ctx = DcHelper.getAccounts(appContext).getAccount(accountId);
        if (ctx == null || !ctx.isOk()) continue;
        JSONArray arr = byAccount.get(accountId);
        if (arr == null) continue;
        JSONObject wrapper = new JSONObject();
        wrapper.put("bots", arr);
        wrapper.put("updatedAtMs", System.currentTimeMillis());
        String sealed = EmailBotCrypto.sealJson(
            appContext, accountId, wrapper.toString());
        ctx.setConfig(UI_CONFIG_KEY, sealed);
        if (publishSync) {
          EmailBotSync.publishNow(appContext, accountId, wrapper.toString());
          new EmailBotDirectory(appContext).publishToSelfChat(accountId);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "persistUiConfig failed", t);
    }
  }

  /** Merges bots from an encrypted self-chat sync message. */
  synchronized void mergeFromSyncJson(int localAccountId, @NonNull JSONArray arr) {
    List<EmailBotConfig> all = new ArrayList<>();
    for (EmailBotConfig b : readPrefs()) {
      if (b.ownerAccountId != localAccountId) all.add(b);
    }
    for (int i = 0; i < arr.length(); i++) {
      try {
        EmailBotConfig b = EmailBotConfig.fromJson(arr.getJSONObject(i));
        all.add(b.withOwnerAccountId(localAccountId).withClearedLocalIds());
      } catch (Throwable t) {
        Log.w(TAG, "skip sync bot at " + i, t);
      }
    }
    writePrefs(all);
    persistUiConfig(all, false);
    ensureLocalBotChats();
  }
}
