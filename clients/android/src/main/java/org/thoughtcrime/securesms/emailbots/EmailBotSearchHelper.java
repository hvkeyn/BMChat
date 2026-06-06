package org.thoughtcrime.securesms.emailbots;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.b44t.messenger.DcContext;

import org.thoughtcrime.securesms.connect.DcHelper;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Matches configured and catalogued email bots for sidebar search (@newsbot). */
public final class EmailBotSearchHelper {

  private EmailBotSearchHelper() {}

  @NonNull
  public static int[] matchContactIds(@NonNull Context context,
                                      int accountId,
                                      @NonNull String query) {
    String trimmed = query.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) return new int[0];
    String bare = trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    if (bare.isEmpty()) return new int[0];

    DcContext dc = DcHelper.getAccounts(context).getAccount(accountId);
    if (dc == null || !dc.isOk()) return new int[0];

    EmailBotStore store = new EmailBotStore(context);
    EmailBotDirectory directory = new EmailBotDirectory(context);
    Set<Integer> ids = new LinkedHashSet<>();

    for (EmailBotConfig b : store.getAll()) {
      if (!b.enabled) continue;
      if (!matchesQuery(b.name, b.displayName, b.description, bare, trimmed)) continue;
      int cid = EmailBotContactHelper.ensureSearchableContact(dc, b);
      if (cid > 0) ids.add(cid);
    }

    for (int dirAccountId : DcHelper.getAccounts(context).getAll()) {
      for (EmailBotDirectory.DirectoryEntry e : directory.getEntries(dirAccountId)) {
        if (!matchesQuery(e.name, e.displayName, e.description, bare, trimmed)) continue;
        int cid = dc.lookupContactIdByAddr(e.botEmail);
        if (cid <= 0) {
          String label = e.displayName != null && !e.displayName.isEmpty()
              ? e.displayName : "@" + e.name;
          cid = dc.createContact(label, e.botEmail);
        }
        if (cid > 0) ids.add(cid);
      }
    }

    int[] out = new int[ids.size()];
    int i = 0;
    for (Integer id : ids) out[i++] = id;
    return out;
  }

  private static boolean matchesQuery(@NonNull String name,
                                      @Nullable String displayName,
                                      @Nullable String description,
                                      @NonNull String bare,
                                      @NonNull String trimmed) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.equals(bare) || n.startsWith(bare) || ("@" + n).equals(trimmed)) {
      return true;
    }
    if (!TextUtils.isEmpty(displayName)) {
      String dn = displayName.toLowerCase(Locale.ROOT);
      if (dn.equals(bare) || dn.contains(bare)) return true;
    }
    if (!TextUtils.isEmpty(description)) {
      if (description.toLowerCase(Locale.ROOT).contains(bare)) return true;
    }
    if (bare.contains(" ")) {
      String[] tokens = bare.split("\\s+");
      int hits = 0;
      for (String token : tokens) {
        if (token.isEmpty()) continue;
        if (n.contains(token)) { hits++; continue; }
        if (!TextUtils.isEmpty(displayName)
            && displayName.toLowerCase(Locale.ROOT).contains(token)) {
          hits++;
        }
      }
      if (hits >= tokens.length) return true;
    }
    return false;
  }
}