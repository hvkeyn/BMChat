package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.emailbots.EmailBotCrypto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persistent storage for the user's connected Telegram bots.
 *
 * <p>Backed by a private {@link SharedPreferences} file. The bot list is
 * serialised as a JSON array; the value is then run through a fixed XOR
 * key + Base64 so a casual filesystem dump (or an unencrypted device
 * backup) doesn't expose all of the user's Telegram tokens in plaintext.
 *
 * <p>This is <em>not</em> cryptographic protection — it is "don't be the
 * worst-case option". A user who really wants to keep tokens out of
 * backups should rely on Android's full-disk encryption.
 */
public final class BotStore {

  private static final String TAG = "BotStore";
  private static final String PREFS = "bmchat-bots";
  private static final String KEY_LIST = "bots";
  /** Must match desktop {@code UI_CONFIG_KEY}. */
  public static final String UI_CONFIG_KEY = "ui.bmchat.telegram_bots";

  // 32 bytes — plenty for XOR over typical bot token lengths.
  private static final byte[] OBF_KEY = new byte[] {
      0x42, 0x4d, 0x43, 0x68, 0x61, 0x74, 0x2d, 0x42,
      0x6f, 0x74, 0x53, 0x74, 0x6f, 0x72, 0x65, 0x21,
      0x76, 0x31, 0x2d, 0x6f, 0x62, 0x66, 0x75, 0x73,
      0x63, 0x61, 0x74, 0x69, 0x6f, 0x6e, 0x21, 0x21
  };

  private final Context appContext;

  public BotStore(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  private SharedPreferences prefs() {
    return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @NonNull
  public synchronized List<BotConfig> getAll() {
    reloadFromUiConfig();
    return readPrefs();
  }

  /** Reload bots from all accounts' ui-config (call on app start / resume). */
  public synchronized void reloadFromUiConfig() {
    java.util.Map<String, BotConfig> merged = new java.util.LinkedHashMap<>();
    for (BotConfig b : readPrefs()) {
      merged.put(b.id, b);
    }
    for (int accountId : DcHelper.getAccounts(appContext).getAll()) {
      DcContext ctx = DcHelper.getAccounts(appContext).getAccount(accountId);
      if (ctx == null || !ctx.isOk()) continue;
      for (BotConfig b : readUiConfigForAccount(ctx.getAccountId(), ctx)) {
        merged.put(b.id, b);
      }
    }
    writePrefs(new ArrayList<>(merged.values()), false);
  }

  @NonNull
  private List<BotConfig> readPrefs() {
    String raw = prefs().getString(KEY_LIST, null);
    if (raw == null) return Collections.emptyList();
    String json = deobfuscate(raw);
    if (json == null) return Collections.emptyList();
    List<BotConfig> out = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(json);
      for (int i = 0; i < arr.length(); i++) {
        try {
          out.add(BotConfig.fromJson(arr.getJSONObject(i)));
        } catch (Throwable t) {
          Log.w(TAG, "skip malformed bot at index " + i, t);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "readPrefs parse failed", t);
    }
    return out;
  }

  @NonNull
  private List<BotConfig> readUiConfigForAccount(int accountId, @NonNull DcContext ctx) {
    List<BotConfig> out = new ArrayList<>();
    try {
      String raw = ctx.getConfig(UI_CONFIG_KEY);
      if (raw == null || raw.isEmpty()) return out;
      String opened = EmailBotCrypto.openJson(appContext, accountId, raw);
      if (opened == null || opened.isEmpty()) return out;
      JSONObject root = new JSONObject(opened);
      JSONArray arr = root.optJSONArray("bots");
      if (arr == null) return out;
      for (int i = 0; i < arr.length(); i++) {
        try {
          out.add(BotConfig.fromJson(arr.getJSONObject(i)));
        } catch (Throwable t) {
          Log.w(TAG, "skip ui-config bot at " + i, t);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "readUiConfigForAccount failed", t);
    }
    return out;
  }

  private void persistUiConfig(@NonNull List<BotConfig> all, boolean publishSync) {
    java.util.Map<Integer, JSONArray> byAccount = new java.util.LinkedHashMap<>();
    for (BotConfig b : all) {
      JSONArray arr = byAccount.get(b.dcAccountId);
      if (arr == null) {
        arr = new JSONArray();
        byAccount.put(b.dcAccountId, arr);
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
        String sealed = EmailBotCrypto.sealJson(appContext, accountId, wrapper.toString());
        ctx.setConfig(UI_CONFIG_KEY, sealed);
        if (publishSync) {
          TelegramBotSync.publishNow(appContext, accountId, wrapper.toString());
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "persistUiConfig failed", t);
    }
  }

  /** Merges bots from an encrypted self-chat sync message. */
  synchronized void mergeFromSyncJson(int localAccountId, @NonNull JSONArray arr) {
    List<BotConfig> all = new ArrayList<>();
    for (BotConfig b : readPrefs()) {
      if (b.dcAccountId != localAccountId) all.add(b);
    }
    for (int i = 0; i < arr.length(); i++) {
      try {
        BotConfig b = BotConfig.fromJson(arr.getJSONObject(i));
        all.add(b.withTarget(localAccountId, b.targetDcChatId));
      } catch (Throwable t) {
        Log.w(TAG, "skip sync bot at " + i, t);
      }
    }
    writePrefs(all, false);
    persistUiConfig(all, false);
  }

  public synchronized void saveAll(@NonNull List<BotConfig> bots) {
    writePrefs(bots, true);
  }

  private void writePrefs(@NonNull List<BotConfig> bots, boolean publishSync) {
    JSONArray arr = new JSONArray();
    for (BotConfig b : bots) {
      try {
        arr.put(b.toJson());
      } catch (Throwable t) {
        Log.w(TAG, "skip serialising bot " + b.id, t);
      }
    }
    String obf = obfuscate(arr.toString());
    prefs().edit().putString(KEY_LIST, obf).apply();
    persistUiConfig(bots, publishSync);
  }

  public synchronized void upsert(@NonNull BotConfig updated) {
    List<BotConfig> all = new ArrayList<>(getAll());
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
  }

  public synchronized void remove(@NonNull String botId) {
    List<BotConfig> all = new ArrayList<>(getAll());
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i).id.equals(botId)) {
        all.remove(i);
        saveAll(all);
        return;
      }
    }
  }

  @Nullable
  public synchronized BotConfig get(@NonNull String botId) {
    for (BotConfig b : getAll()) if (b.id.equals(botId)) return b;
    return null;
  }

  /** Generate a fresh bot id. Use when adding a new bot. */
  public static String generateId() {
    return UUID.randomUUID().toString();
  }

  // ---------------------------------------------------------------------
  //  obfuscation helpers
  // ---------------------------------------------------------------------

  private static String obfuscate(String plain) {
    if (plain == null) return null;
    byte[] in = plain.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[in.length];
    for (int i = 0; i < in.length; i++) {
      out[i] = (byte) (in[i] ^ OBF_KEY[i % OBF_KEY.length]);
    }
    return Base64.encodeToString(out, Base64.NO_WRAP);
  }

  private static String deobfuscate(String b64) {
    if (b64 == null) return null;
    try {
      byte[] in = Base64.decode(b64, Base64.NO_WRAP);
      byte[] out = new byte[in.length];
      for (int i = 0; i < in.length; i++) {
        out[i] = (byte) (in[i] ^ OBF_KEY[i % OBF_KEY.length]);
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (Throwable t) {
      Log.w(TAG, "deobfuscate failed", t);
      return null;
    }
  }
}
