package org.thoughtcrime.securesms.connect;

import android.util.Log;

import androidx.annotation.NonNull;

import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import java.util.HashSet;

/**
 * UI-side filter that hides duplicate "Member X added" / "Member X
 * removed" / chat-name / chat-image system messages from the chat
 * conversation view.
 *
 * <p>Background: when a contact accepts a BMChat invite to a channel
 * the Delta Chat core can record the join twice — once from the local
 * SecureJoin {@code joinSecurejoin} call on the inviter's side, and
 * once again from the encrypted {@code vc-contact-confirm} message that
 * the joiner sends back. Both events end up as
 * {@link DcMsg#DC_INFO_MEMBER_ADDED_TO_GROUP} info rows with the same
 * {@code info_contact_id} → the user sees "Добавлен участник X" twice
 * in a row (screenshot 1 from May 10).
 *
 * <p>Patching this in the Rust core is risky — it changes the message
 * timeline that other Delta Chat clients also consume. Filtering at
 * the UI layer is safe and reversible: the duplicate row is still in
 * the database (so other clients render whatever they always rendered)
 * but BMChat hides it on display.
 *
 * <p>The filter only collapses <em>identical</em> consecutive system
 * messages — same {@link DcMsg#getInfoType()} <em>and</em> same
 * {@link DcMsg#getInfoContactId()}. Real two-step events ("X added,
 * then Y added") stay untouched. Day markers and non-info messages
 * also reset the run, so two genuine join events separated by a real
 * post still show both notifications.
 */
public final class BMChatInfoMsgDedupe {

  private static final String TAG = "BMChatInfoMsgDedupe";

  private BMChatInfoMsgDedupe() {}

  /**
   * Return a copy of {@code msgIds} with consecutive duplicate
   * member-add / member-remove info messages collapsed to a single
   * entry. Other message kinds (text, media, info messages of other
   * kinds, day markers) pass through untouched and in original order.
   *
   * <p>Unknown ids and ids that {@link DcContext#getMsg(int)} cannot
   * load are passed through too — never silently drop data we don't
   * understand.
   */
  @NonNull
  public static int[] dedupe(@NonNull DcContext dcContext, @NonNull int[] msgIds) {
    if (msgIds.length < 2) return msgIds;
    int[] out = new int[msgIds.length];
    int outLen = 0;

    // We track the "previous info row" with a tiny tuple key that
    // survives the next iteration via plain locals — avoids an
    // allocation per message which adds up on long chats.
    int prevInfoType = -1;
    int prevInfoContactId = -1;
    int prevInfoMsgId = -1;
    HashSet<Long> seenSinceLastReset = null; // lazy: only allocated when needed

    for (int id : msgIds) {
      // Day markers and other UI-only ids that can't be looked up
      // never need dedupe — keep them as-is and reset the run state
      // so two info messages separated by a marker are independent.
      if (id <= DcMsg.DC_MSG_ID_DAYMARKER) {
        prevInfoType = -1;
        prevInfoContactId = -1;
        prevInfoMsgId = -1;
        seenSinceLastReset = null;
        out[outLen++] = id;
        continue;
      }
      DcMsg m;
      try { m = dcContext.getMsg(id); }
      catch (Throwable t) {
        Log.w(TAG, "getMsg(" + id + ") failed; passing through", t);
        out[outLen++] = id;
        prevInfoType = -1;
        prevInfoContactId = -1;
        prevInfoMsgId = -1;
        seenSinceLastReset = null;
        continue;
      }
      if (m == null || !m.isInfo()) {
        out[outLen++] = id;
        prevInfoType = -1;
        prevInfoContactId = -1;
        prevInfoMsgId = -1;
        seenSinceLastReset = null;
        continue;
      }

      int infoType = m.getInfoType();
      int infoContactId = m.getInfoContactId();
      // Only collapse member-add / member-remove pairs. Other info
      // kinds (chat description changed, e2ee enabled, …) are usually
      // singletons and accidentally collapsing them would hide real
      // events; better safe than sorry.
      boolean canDedupe =
          (infoType == DcMsg.DC_INFO_MEMBER_ADDED_TO_GROUP
              || infoType == DcMsg.DC_INFO_MEMBER_REMOVED_FROM_GROUP)
              && infoContactId > 0;
      if (!canDedupe) {
        out[outLen++] = id;
        prevInfoType = -1;
        prevInfoContactId = -1;
        prevInfoMsgId = -1;
        seenSinceLastReset = null;
        continue;
      }

      // A run of equivalent info rows. The check is consecutive ((==
      // prev)) plus a small per-run set so the same "join" delivered
      // out of order — e.g. local addContactToChat first, then the
      // encrypted vc-contact-confirm a few seconds later — still
      // collapses if it lands inside the same uninterrupted run.
      long key = ((long) infoType << 32) | (infoContactId & 0xFFFFFFFFL);
      if (infoType == prevInfoType && infoContactId == prevInfoContactId) {
        // direct neighbour duplicate — drop it
        continue;
      }
      if (seenSinceLastReset != null && seenSinceLastReset.contains(key)) {
        // A copy already appeared earlier in this uninterrupted run
        // of info rows. Still consider it a duplicate and drop.
        continue;
      }
      out[outLen++] = id;
      prevInfoType = infoType;
      prevInfoContactId = infoContactId;
      prevInfoMsgId = id;
      if (seenSinceLastReset == null) seenSinceLastReset = new HashSet<>(4);
      seenSinceLastReset.add(key);
    }

    if (outLen == msgIds.length) return msgIds; // no-op fast-path
    int[] trimmed = new int[outLen];
    System.arraycopy(out, 0, trimmed, 0, outLen);
    return trimmed;
  }
}
