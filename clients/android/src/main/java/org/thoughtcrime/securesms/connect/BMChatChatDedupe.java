package org.thoughtcrime.securesms.connect;

import android.content.Context;
import android.util.Log;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Across-clients fallout from Delta-Chat's key-contact model: when the same
 * e-mail is reached through different Autocrypt fingerprints (e.g. the peer
 * recreates their account or the user already had a classic-mail contact),
 * the core happily creates a brand-new contact + 1:1 chat. From the BMChat
 * user's point of view this looks like duplicate chats with the same e-mail.
 *
 * BMChat policy is "one chat per e-mail". This helper enforces that on top
 * of the core: it walks the chatlist (sorted newest-first), groups visible
 * 1:1 chats by normalized e-mail, keeps the most recent one and archives
 * the rest. Archiving is non-destructive — the historical messages remain
 * accessible from the archive — but the user is no longer presented with
 * mirror entries on the home screen.
 */
public final class BMChatChatDedupe {

  private static final String TAG = "BMChatChatDedupe";

  private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "bmchat-chat-dedupe");
    t.setDaemon(true);
    return t;
  });
  private static final AtomicBoolean pending = new AtomicBoolean(false);

  private BMChatChatDedupe() {}

  /**
   * Schedule a background scan that archives duplicate 1:1 chats. Multiple
   * calls in quick succession collapse into a single scan.
   */
  public static void scheduleScan(Context appContext, DcContext dcContext) {
    if (appContext == null || dcContext == null) return;
    if (!pending.compareAndSet(false, true)) return;
    final Context ctxRef = appContext.getApplicationContext();
    worker.submit(() -> {
      try {
        scanAndArchive(ctxRef, dcContext);
      } catch (Throwable t) {
        Log.w(TAG, "scan failed", t);
      } finally {
        pending.set(false);
      }
    });
  }

  /**
   * Synchronous variant for the SecureJoin / e-mail invite flow, which
   * already runs on a worker thread and wants to settle the chat list
   * before navigating to the conversation.
   */
  public static int runNow(DcContext dcContext) {
    try {
      return scanAndArchive(null, dcContext);
    } catch (Throwable t) {
      Log.w(TAG, "runNow failed", t);
      return 0;
    }
  }

  private static int scanAndArchive(Context appContext, DcContext dcContext) {
    if (dcContext == null) return 0;
    DcChatlist chatlist = dcContext.getChatlist(0, null, 0);
    if (chatlist == null) return 0;
    int total = chatlist.getCnt();
    Map<String, List<Integer>> byAddr = new LinkedHashMap<>();
    for (int i = 0; i < total; i++) {
      int chatId = chatlist.getChatId(i);
      if (chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL) continue;
      DcChat chat = dcContext.getChat(chatId);
      if (chat == null) continue;
      if (chat.getType() != DcChat.DC_CHAT_TYPE_SINGLE) continue;
      if (chat.isSelfTalk() || chat.isDeviceTalk()) continue;
      if (chat.getVisibility() == DcChat.DC_CHAT_VISIBILITY_ARCHIVED) continue;
      int[] contacts = dcContext.getChatContacts(chatId);
      if (contacts == null || contacts.length == 0) continue;
      int contactId = contacts[0];
      if (contactId == DcContact.DC_CONTACT_ID_SELF) continue;
      String addr;
      try {
        addr = dcContext.getContact(contactId).getAddr();
      } catch (Throwable t) {
        continue;
      }
      if (addr == null) continue;
      String key = addr.trim().toLowerCase(Locale.ROOT);
      if (key.isEmpty()) continue;
      List<Integer> bucket = byAddr.get(key);
      if (bucket == null) {
        bucket = new ArrayList<>(2);
        byAddr.put(key, bucket);
      }
      bucket.add(chatId);
    }
    int archived = 0;
    for (Map.Entry<String, List<Integer>> e : byAddr.entrySet()) {
      List<Integer> ids = e.getValue();
      if (ids.size() < 2) continue;
      // chatlist is ordered newest-first, so ids.get(0) is the freshest chat -> kept.
      int keep = ids.get(0);
      for (int j = 1; j < ids.size(); j++) {
        int dup = ids.get(j);
        try {
          dcContext.setChatVisibility(dup, DcChat.DC_CHAT_VISIBILITY_ARCHIVED);
          archived++;
          Log.i(TAG, "archived duplicate chat " + dup + " for " + e.getKey()
              + " (kept " + keep + ")");
        } catch (Throwable t) {
          Log.w(TAG, "setChatVisibility failed for chat " + dup, t);
        }
      }
    }
    return archived;
  }
}
