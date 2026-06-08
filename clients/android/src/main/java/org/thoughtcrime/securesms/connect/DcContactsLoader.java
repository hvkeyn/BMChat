package org.thoughtcrime.securesms.connect;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.bots.BotConfig;
import org.thoughtcrime.securesms.bots.BotStore;
import org.thoughtcrime.securesms.emailbots.EmailBotContactHelper;
import org.thoughtcrime.securesms.util.AsyncLoader;
import org.thoughtcrime.securesms.util.Util;

public class DcContactsLoader extends AsyncLoader<DcContactsLoader.Ret> {

  private final int listflags;
  private final String query;
  private final boolean addScanQRLink;
  private final boolean addCreateGroupLinks;
  private final boolean addCreateContactLink;
  private final boolean blockedContacts;

  public DcContactsLoader(
      Context context,
      int listflags,
      String query,
      boolean addCreateGroupLinks,
      boolean addCreateContactLink,
      boolean addScanQRLink,
      boolean blockedContacts) {
    super(context);
    this.listflags = listflags;
    this.query = (query == null || query.isEmpty()) ? null : query;
    this.addScanQRLink = addScanQRLink;
    this.addCreateGroupLinks = addCreateGroupLinks;
    this.addCreateContactLink = addCreateContactLink;
    this.blockedContacts = blockedContacts;
  }

  @Override
  public @NonNull DcContactsLoader.Ret loadInBackground() {
    DcContext dcContext = DcHelper.getContext(getContext());
    if (blockedContacts) {
      int[] blocked_ids = dcContext.getBlockedContacts();
      return new Ret(blocked_ids);
    }

    int[] contact_ids = dcContext.getContacts(listflags, query);
    contact_ids = mergeEmailBotContacts(dcContext.getAccountId(), contact_ids, query);
    int[] additional_items = new int[0];
    if (query == null && addScanQRLink) {
      additional_items = Util.appendInt(additional_items, DcContact.DC_CONTACT_ID_QR_INVITE);
    }
    boolean hasExistingContactForQuery =
        query != null && dcContext.mayBeValidAddr(query) && dcContext.lookupContactIdByAddr(query) > 0;
    if (addCreateContactLink && !dcContext.isChatmail() && !hasExistingContactForQuery) {
      additional_items =
          Util.appendInt(additional_items, DcContact.DC_CONTACT_ID_NEW_CLASSIC_CONTACT);
    }
    if (query == null && addCreateGroupLinks) {
      additional_items = Util.appendInt(additional_items, DcContact.DC_CONTACT_ID_NEW_GROUP);
      additional_items = Util.appendInt(additional_items, DcContact.DC_CONTACT_ID_NEW_BROADCAST);

      if (!dcContext.isChatmail()) {
        additional_items =
            Util.appendInt(additional_items, DcContact.DC_CONTACT_ID_NEW_UNENCRYPTED_GROUP);
      }
    }
    int[] all_ids = new int[contact_ids.length + additional_items.length];
    System.arraycopy(additional_items, 0, all_ids, 0, additional_items.length);
    System.arraycopy(contact_ids, 0, all_ids, additional_items.length, contact_ids.length);
    return new Ret(all_ids);
  }

  /** Ensures email/Telegram bot pseudo-contacts appear in pickers. */
  private int[] mergeEmailBotContacts(int accountId, int[] contactIds, String filter) {
    try {
      com.b44t.messenger.DcContext dc = DcHelper.getContext(getContext());
      java.util.LinkedHashSet<Integer> botIds = new java.util.LinkedHashSet<>();
      if (filter != null && !filter.isEmpty()) {
        for (int cid : org.thoughtcrime.securesms.emailbots.EmailBotSearchHelper
            .matchContactIds(getContext(), accountId, filter)) {
          if (cid > 0) botIds.add(cid);
        }
        BotStore tgStore = new BotStore(getContext());
        String bare = filter.trim().toLowerCase(java.util.Locale.ROOT);
        if (bare.startsWith("@")) bare = bare.substring(1);
        for (BotConfig b : tgStore.getAll()) {
          if (b.dcAccountId != accountId || b.botContactId <= 0) continue;
          if (matchesBotFilter(
              b.telegramUsername != null ? b.telegramUsername : "",
              b.telegramName,
              b.description,
              bare,
              filter)) {
            botIds.add(b.botContactId);
          }
        }
      } else {
        org.thoughtcrime.securesms.emailbots.EmailBotStore store =
            new org.thoughtcrime.securesms.emailbots.EmailBotStore(getContext());
        for (org.thoughtcrime.securesms.emailbots.EmailBotConfig b : store.getAll()) {
          if (!b.enabled) continue;
          int cid = b.botContactId;
          if (cid <= 0) {
            cid = EmailBotContactHelper.ensureSearchableContact(dc, b);
          }
          if (cid > 0) botIds.add(cid);
        }
        BotStore tgStore = new BotStore(getContext());
        for (BotConfig b : tgStore.getAll()) {
          if (b.dcAccountId != accountId || b.botContactId <= 0) continue;
          botIds.add(b.botContactId);
        }
      }
      if (botIds.isEmpty()) return contactIds;
      java.util.LinkedHashSet<Integer> merged = new java.util.LinkedHashSet<>(botIds);
      for (int id : contactIds) merged.add(id);
      int[] out = new int[merged.size()];
      int i = 0;
      for (Integer id : merged) out[i++] = id;
      return out;
    } catch (Throwable ignored) {
      return contactIds;
    }
  }

  private static boolean matchesBotFilter(@NonNull String name,
                                          @Nullable String displayName,
                                          @Nullable String description,
                                          @NonNull String bare,
                                          @NonNull String trimmed) {
    String n = name.toLowerCase(java.util.Locale.ROOT);
    if (n.equals(bare) || n.startsWith(bare) || ("@" + n).equals(trimmed.toLowerCase(java.util.Locale.ROOT))) {
      return true;
    }
    if (displayName != null && !displayName.isEmpty()) {
      String dn = displayName.toLowerCase(java.util.Locale.ROOT);
      if (dn.equals(bare) || dn.contains(bare)) return true;
    }
    if (description != null && !description.isEmpty()
        && description.toLowerCase(java.util.Locale.ROOT).contains(bare)) {
      return true;
    }
    return false;
  }

  public static class Ret {
    public final int[] ids;

    Ret(int[] ids) {
      this.ids = ids;
    }
  }
}
