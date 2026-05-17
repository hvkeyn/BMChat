package org.thoughtcrime.securesms;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import androidx.annotation.NonNull;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;

public class ConnectivityActivity extends WebViewActivity implements DcEventCenter.DcEventDelegate {
  @Override
  protected void onCreate(Bundle state, boolean ready) {
    super.onCreate(state, ready);
    setForceDark();
    getSupportActionBar().setTitle(R.string.connectivity);
    refresh();

    DcHelper.getEventCenter(this).addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(this).removeObservers(this);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // do not call super.onPrepareOptionsMenu() as the default "Search" menu is not needed
    return true;
  }

  private void refresh() {
    String html = DcHelper.getContext(this).getConnectivityHtml();
    // BMChat is e-mail-first, so the upstream "Хранилище на сервере / Storage
    // on server" reporting that surfaces a stand-alone "Не поддерживается
    // вашим провайдером" / "Not supported by your provider" line for plain
    // IMAP servers (it lives next to "Входящие сообщения" with no header,
    // see screenshot from May 8) is just visual noise here. Strip the bare
    // status line — and any sibling section headers that now contain only
    // that line — before rendering.
    html =
        html
            .replaceAll(
                "(?si)<[^>]*>\\s*Не поддерживается вашим провайдером\\.?\\s*</[^>]*>",
                "")
            .replaceAll(
                "(?si)<[^>]*>\\s*Not supported by your provider\\.?\\s*</[^>]*>",
                "")
            // After the bullet is gone an empty <h3>Storage on Server</h3>
            // would still take a slot — drop any header followed only by
            // whitespace and the next header.
            .replaceAll(
                "(?si)<h3[^>]*>[^<]*</h3>\\s*(?=<h3|</body)",
                "");
    html =
        html.replace(
            "</style>",
            " html { color-scheme: dark light; }"
                + " .bmchat-stats{margin-top:2rem;padding-top:.7rem;border-top:1px solid rgba(128,128,128,.35)}"
                + " .bmchat-stats-grid{display:grid;grid-template-columns:1fr 1fr;gap:.7rem;margin-top:.8rem}"
                + " .bmchat-stat{border:1px solid rgba(128,128,128,.35);border-radius:10px;padding:.75rem;background:rgba(128,128,128,.08)}"
                + " .bmchat-stat strong{display:block;font-size:1.45rem;line-height:1.2}"
                + " .bmchat-stat span{display:block;margin-top:.2rem;color:#999;font-size:.9rem}"
                + " .bmchat-stat-wide{grid-column:1/-1}"
                + "</style>");
    String statsHtml = buildStatisticsHtml(DcHelper.getContext(this));
    if (html.contains("</body>")) {
      html = html.replace("</body>", statsHtml + "</body>");
    } else {
      html += statsHtml;
    }
    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
  }

  private String buildStatisticsHtml(DcContext dcContext) {
    Stats stats = new Stats();
    Set<Integer> seenChats = new HashSet<>();
    collectChatStats(dcContext, dcContext.getChatlist(DcContext.DC_GCL_NO_SPECIALS, null, 0), stats, seenChats);
    collectChatStats(
        dcContext,
        dcContext.getChatlist(DcContext.DC_GCL_ARCHIVED_ONLY | DcContext.DC_GCL_NO_SPECIALS, null, 0),
        stats,
        seenChats);
    stats.contacts = countRealContacts(dcContext.getContacts(0, null));
    stats.verifiedContacts = countRealContacts(dcContext.getContacts(DcContext.DC_GCL_VERIFIED_ONLY, null));

    StringBuilder html = new StringBuilder();
    html.append("<section class=\"bmchat-stats\"><h3>")
        .append(TextUtils.htmlEncode(getString(R.string.bmchat_connectivity_stats_title)))
        .append("</h3><p>")
        .append(TextUtils.htmlEncode(getString(R.string.bmchat_connectivity_stats_hint)))
        .append("</p><div class=\"bmchat-stats-grid\">");
    appendStat(html, R.string.bmchat_stats_contacts, stats.contacts, R.string.bmchat_stats_contacts_hint);
    appendStat(html, R.string.bmchat_stats_verified_contacts, stats.verifiedContacts, R.string.bmchat_stats_verified_contacts_hint);
    appendStat(html, R.string.bmchat_stats_chats, stats.chats, R.string.bmchat_stats_chats_hint);
    appendStat(html, R.string.bmchat_stats_groups, stats.groups, R.string.bmchat_stats_groups_hint);
    appendStat(html, R.string.bmchat_stats_messages, stats.messages, R.string.bmchat_stats_messages_hint);
    appendStat(html, R.string.bmchat_stats_attachments, stats.attachments, R.string.bmchat_stats_attachments_hint);
    appendStat(html, R.string.bmchat_stats_incoming, stats.incoming, R.string.bmchat_stats_incoming_hint);
    appendStat(html, R.string.bmchat_stats_outgoing, stats.outgoing, R.string.bmchat_stats_outgoing_hint);
    appendStat(html, R.string.bmchat_stats_delivered, stats.delivered, R.string.bmchat_stats_delivered_hint);
    appendStat(html, R.string.bmchat_stats_read, stats.read, R.string.bmchat_stats_read_hint);
    appendStat(html, R.string.bmchat_stats_failed, stats.failed, R.string.bmchat_stats_failed_hint);
    appendStat(html, R.string.bmchat_stats_pending, stats.pending, R.string.bmchat_stats_pending_hint);
    appendWideStat(html, R.string.bmchat_stats_chat_mix, buildChatMix(stats));
    html.append("</div></section>");
    return html.toString();
  }

  private void collectChatStats(
      DcContext dcContext, DcChatlist chatlist, Stats stats, Set<Integer> seenChats) {
    if (chatlist == null) return;
    for (int i = 0; i < chatlist.getCnt(); i++) {
      int chatId = chatlist.getChatId(i);
      if (chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL || !seenChats.add(chatId)) continue;
      DcChat chat = dcContext.getChat(chatId);
      if (chat == null) continue;
      stats.chats++;
      if (chat.isEncrypted()) stats.encryptedChats++;
      if (chat.isMuted()) stats.mutedChats++;
      if (chat.isContactRequest()) stats.contactRequests++;
      if (chat.getVisibility() == DcChat.DC_CHAT_VISIBILITY_PINNED) stats.pinnedChats++;
      if (chat.getVisibility() == DcChat.DC_CHAT_VISIBILITY_ARCHIVED) stats.archivedChats++;
      switch (chat.getType()) {
        case DcChat.DC_CHAT_TYPE_GROUP:
          stats.groups++;
          break;
        case DcChat.DC_CHAT_TYPE_MAILINGLIST:
          stats.mailingLists++;
          break;
        case DcChat.DC_CHAT_TYPE_IN_BROADCAST:
        case DcChat.DC_CHAT_TYPE_OUT_BROADCAST:
          stats.channels++;
          break;
        default:
          stats.singleChats++;
      }
      int[] messageIds = dcContext.getChatMsgs(chatId, 0, 0);
      if (messageIds == null) continue;
      for (int msgId : messageIds) {
        if (msgId <= DcMsg.DC_MSG_ID_DAYMARKER) continue;
        DcMsg msg = dcContext.getMsg(msgId);
        if (msg == null || !msg.isOk()) continue;
        if (msg.isInfo()) {
          stats.infoMessages++;
          continue;
        }
        stats.messages++;
        if (msg.getFromId() == DcContact.DC_CONTACT_ID_SELF) {
          stats.outgoing++;
        } else {
          stats.incoming++;
        }
        if (msg.hasFile()) stats.attachments++;
        switch (msg.getState()) {
          case DcMsg.DC_STATE_OUT_DELIVERED:
            stats.delivered++;
            break;
          case DcMsg.DC_STATE_OUT_MDN_RCVD:
            stats.read++;
            break;
          case DcMsg.DC_STATE_OUT_FAILED:
            stats.failed++;
            break;
          case DcMsg.DC_STATE_OUT_PENDING:
          case DcMsg.DC_STATE_OUT_PREPARING:
          case DcMsg.DC_STATE_OUT_DRAFT:
            stats.pending++;
            break;
        }
      }
    }
  }

  private int countRealContacts(int[] contactIds) {
    if (contactIds == null) return 0;
    int count = 0;
    for (int contactId : contactIds) {
      if (contactId > DcContact.DC_CONTACT_ID_LAST_SPECIAL) count++;
    }
    return count;
  }

  private String buildChatMix(Stats stats) {
    return getString(
        R.string.bmchat_stats_chat_mix_value,
        format(stats.singleChats),
        format(stats.groups),
        format(stats.channels),
        format(stats.archivedChats),
        format(stats.pinnedChats),
        format(stats.contactRequests),
        format(stats.encryptedChats));
  }

  private void appendStat(StringBuilder html, int titleRes, int value, int hintRes) {
    html.append("<div class=\"bmchat-stat\"><b>")
        .append(TextUtils.htmlEncode(getString(titleRes)))
        .append("</b><strong>")
        .append(format(value))
        .append("</strong><span>")
        .append(TextUtils.htmlEncode(getString(hintRes)))
        .append("</span></div>");
  }

  private void appendWideStat(StringBuilder html, int titleRes, String value) {
    html.append("<div class=\"bmchat-stat bmchat-stat-wide\"><b>")
        .append(TextUtils.htmlEncode(getString(titleRes)))
        .append("</b><span>")
        .append(TextUtils.htmlEncode(value))
        .append("</span></div>");
  }

  private String format(int value) {
    return NumberFormat.getIntegerInstance(Locale.getDefault()).format(value);
  }

  private static class Stats {
    int contacts;
    int verifiedContacts;
    int chats;
    int singleChats;
    int groups;
    int channels;
    int mailingLists;
    int archivedChats;
    int pinnedChats;
    int mutedChats;
    int contactRequests;
    int encryptedChats;
    int messages;
    int incoming;
    int outgoing;
    int delivered;
    int read;
    int failed;
    int pending;
    int attachments;
    int infoMessages;
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    refresh();
  }
}
