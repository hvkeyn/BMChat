package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persistent storage for {@link EmailBotConfig}s.
 *
 * <p>Layout follows {@code BotStore} (the Telegram-bot equivalent): a
 * single private SharedPreferences file holds a JSON array of bot
 * descriptors. Unlike the Telegram store, e-mail bots carry no
 * authentication tokens, so no obfuscation is applied — the contents
 * are plain JSON the user can also export and review.
 */
public final class EmailBotStore {

  private static final String TAG = "EmailBotStore";
  private static final String PREFS = "bmchat-email-bots";
  private static final String KEY_LIST = "bots";

  private final Context appContext;

  public EmailBotStore(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
  }

  private SharedPreferences prefs() {
    return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @NonNull
  public synchronized List<EmailBotConfig> getAll() {
    String raw = prefs().getString(KEY_LIST, null);
    if (raw == null) return Collections.emptyList();
    List<EmailBotConfig> out = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(raw);
      for (int i = 0; i < arr.length(); i++) {
        try {
          out.add(EmailBotConfig.fromJson(arr.getJSONObject(i)));
        } catch (Throwable t) {
          Log.w(TAG, "skip malformed bot at index " + i, t);
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "getAll parse failed", t);
    }
    return out;
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

  public synchronized void saveAll(@NonNull List<EmailBotConfig> bots) {
    JSONArray arr = new JSONArray();
    for (EmailBotConfig b : bots) {
      try {
        arr.put(b.toJson());
      } catch (Throwable t) {
        Log.w(TAG, "skip serialising bot " + b.id, t);
      }
    }
    prefs().edit().putString(KEY_LIST, arr.toString()).apply();
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
  }

  public synchronized void delete(@NonNull String id) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).id.equals(id)) all.remove(i);
    }
    saveAll(all);
  }

  /**
   * Removes every bot owned by {@code accountId}; called when the user
   * deletes an account from BMChat so the "no webhook" semantics fire
   * without leaving orphaned configs behind.
   */
  public synchronized void deleteForAccount(int accountId) {
    List<EmailBotConfig> all = new ArrayList<>(getAll());
    for (int i = all.size() - 1; i >= 0; i--) {
      if (all.get(i).ownerAccountId == accountId) all.remove(i);
    }
    saveAll(all);
  }

  /**
   * Generates a stable random id for a brand-new bot.
   */
  @NonNull
  public static String newId() {
    return UUID.randomUUID().toString();
  }
}
