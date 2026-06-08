package org.thoughtcrime.securesms;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.qr.QrShowActivity;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class ProfileFragment extends Fragment
    implements ProfileAdapter.ItemClickListener, DcEventCenter.DcEventDelegate {

  private static final String TAG = "ProfileFragment";
  public static final String CHAT_ID_EXTRA = "chat_id";
  public static final String CONTACT_ID_EXTRA = "contact_id";

  private ActivityResultLauncher<Intent> pickContactLauncher;
  private ProfileAdapter adapter;
  private ActionMode actionMode;
  private final ActionModeCallback actionModeCallback = new ActionModeCallback();

  private DcContext dcContext;
  protected int chatId;
  private int contactId;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);

    chatId = getArguments() != null ? getArguments().getInt(CHAT_ID_EXTRA, -1) : -1;
    contactId = getArguments().getInt(CONTACT_ID_EXTRA, -1);
    dcContext = DcHelper.getContext(requireContext());
    pickContactLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              Intent data = result.getData();
              Log.i(
                  TAG,
                  "Received result from activity, resultCode="
                      + result.getResultCode()
                      + ", data="
                      + data);
              if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                List<Integer> selected =
                    data.getIntegerArrayListExtra(ContactMultiSelectionActivity.CONTACTS_EXTRA);
                List<Integer> deselected =
                    data.getIntegerArrayListExtra(
                        ContactMultiSelectionActivity.DESELECTED_CONTACTS_EXTRA);
                Util.runOnAnyBackgroundThread(
                    () -> {
                      DcChat chat = dcContext.getChat(chatId);
                      boolean isBroadcast = chat != null && chat.isOutBroadcast();

                      if (deselected != null && !isBroadcast) {
                        // Broadcasts join via QR/invite-link, not via
                        // dc_remove_contact_from_chat — leave membership
                        // edits to the recipient there.
                        Log.i(TAG, deselected.size() + " members removed");
                        int[] members = dcContext.getChatContacts(chatId);
                        for (int contactId : deselected) {
                          for (int memberId : members) {
                            if (memberId == contactId) {
                              dcContext.removeContactFromChat(chatId, memberId);
                              break;
                            }
                          }
                        }
                      }

                      if (selected != null) { // Add new members
                        Log.i(TAG, selected.size() + " members selected (broadcast=" + isBroadcast + ")");
                        if (isBroadcast) {
                          inviteContactsToBroadcast(chatId, selected);
                        } else {
                          for (Integer contactId : selected) {
                            if (contactId != null) {
                              dcContext.addContactToChat(chatId, contactId);
                            }
                          }
                        }
                      }
                    });
              }
            });
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.profile_fragment, container, false);
    adapter = new ProfileAdapter(this, GlideApp.with(this), this);

    RecyclerView list = ViewUtil.findById(view, R.id.recycler_view);

    // add padding to avoid content hidden behind system bars
    ViewUtil.applyWindowInsets(list);

    list.setAdapter(adapter);
    list.setLayoutManager(
        new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));

    update();

    DcEventCenter eventCenter = DcHelper.getEventCenter(requireContext());
    eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CONTACTS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
    return view;
  }

  @Override
  public void onDestroyView() {
    DcHelper.getEventCenter(requireContext()).removeObservers(this);
    super.onDestroyView();
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    update();
  }

  public void refreshContent() {
    update();
  }

  private void update() {
    int[] memberList = null;
    DcChatlist sharedChats = null;

    DcChat dcChat = null;
    DcContact dcContact = null;
    if (contactId > 0) {
      dcContact = dcContext.getContact(contactId);
    }
    if (chatId > 0) {
      dcChat = dcContext.getChat(chatId);
    }

    if (dcChat != null && (dcChat.isMultiUser() || dcChat.isOutBroadcast())) {
      memberList = dcContext.getChatContacts(chatId);
    } else if (contactId > 0 && contactId != DcContact.DC_CONTACT_ID_SELF) {
      sharedChats = dcContext.getChatlist(0, null, contactId);
    }

    adapter.changeData(memberList, dcContact, sharedChats, dcChat);
  }

  // handle events
  // =========================================================================

  @Override
  public void onSettingsClicked(int settingsId) {
    switch (settingsId) {
      case ProfileAdapter.ITEM_ALL_MEDIA_BUTTON:
        if (chatId > 0) {
          Intent intent = new Intent(getActivity(), AllMediaActivity.class);
          intent.putExtra(AllMediaActivity.CHAT_ID_EXTRA, chatId);
          startActivity(intent);
        }
        break;
      case ProfileAdapter.ITEM_SEND_MESSAGE_BUTTON:
        onSendMessage();
        break;
      case ProfileAdapter.ITEM_INTRODUCED_BY:
        onVerifiedByClicked();
        break;
    }
  }

  @Override
  public void onStatusLongClicked(boolean isMultiUser) {
    Context context = requireContext();
    new AlertDialog.Builder(context)
        .setTitle(isMultiUser ? R.string.chat_description : R.string.pref_default_status_label)
        .setItems(
            new CharSequence[] {context.getString(R.string.menu_copy_to_clipboard)},
            (dialogInterface, i) -> {
              Util.writeTextToClipboard(context, adapter.getStatusText());
              Toast.makeText(
                      context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                  .show();
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  @Override
  public void onMemberLongClicked(int contactId) {
    if (contactId > DcContact.DC_CONTACT_ID_LAST_SPECIAL
        || contactId == DcContact.DC_CONTACT_ID_SELF) {
      if (actionMode == null) {
        DcChat dcChat = dcContext.getChat(chatId);
        if (dcChat.canSend() && dcChat.isEncrypted()) {
          adapter.toggleMemberSelection(contactId);
          actionMode =
              ((AppCompatActivity) requireActivity()).startSupportActionMode(actionModeCallback);
        }
      } else {
        onMemberClicked(contactId);
      }
    }
  }

  @Override
  public void onMemberClicked(int contactId) {
    if (actionMode != null) {
      if (contactId > DcContact.DC_CONTACT_ID_LAST_SPECIAL
          || contactId == DcContact.DC_CONTACT_ID_SELF) {
        adapter.toggleMemberSelection(contactId);
        if (adapter.getSelectedMembersCount() == 0) {
          actionMode.finish();
          actionMode = null;
        } else {
          actionMode.setTitle(String.valueOf(adapter.getSelectedMembersCount()));
        }
      }
    } else if (contactId == DcContact.DC_CONTACT_ID_ADD_MEMBER) {
      onAddMember();
    } else if (contactId == DcContact.DC_CONTACT_ID_QR_INVITE) {
      onQrInvite();
    } else if (contactId > DcContact.DC_CONTACT_ID_LAST_SPECIAL) {
      Intent intent = new Intent(getContext(), ProfileActivity.class);
      intent.putExtra(ProfileActivity.CONTACT_ID_EXTRA, contactId);
      startActivity(intent);
    }
  }

  @Override
  public void onAvatarClicked() {
    ProfileActivity activity = (ProfileActivity) getActivity();
    activity.onEnlargeAvatar();
  }

  public void onAddMember() {
    DcChat dcChat = dcContext.getChat(chatId);
    Intent intent = new Intent(getContext(), ContactMultiSelectionActivity.class);
    ArrayList<Integer> preselectedContacts = new ArrayList<>();
    if (dcChat != null && dcChat.isOutBroadcast()) {
      // For broadcast/channel invites we don't preselect existing recipients
      // because Delta-core doesn't expose a simple "is X already subscribed
      // via QR?" check, and the goal here is "send invitations" rather than
      // "edit a member list" — sending the same invite link twice is fine.
    } else {
      for (int memberId : dcContext.getChatContacts(chatId)) {
        preselectedContacts.add(memberId);
      }
    }
    intent.putExtra(ContactSelectionListFragment.PRESELECTED_CONTACTS, preselectedContacts);
    pickContactLauncher.launch(intent);
  }

  /**
   * Send a "join my channel" invite link to every selected contact's 1:1
   * chat. Channels (broadcast lists) in Delta-core do not accept blind
   * {@code dc_add_contact_to_chat} — recipients have to walk through the
   * QR/SecureJoin flow themselves. This wraps that flow in a one-tap UX:
   * you pick contacts, BMChat opens (or reuses) the 1:1 chat with each of
   * them and posts the channel's invite link. The recipient taps it,
   * lands in {@link org.thoughtcrime.securesms.connect.BMChatInviteAutoAcceptor}
   * and is auto-joined.
   */
  private void inviteContactsToBroadcast(int broadcastChatId, List<Integer> contactIds) {
    if (contactIds == null || contactIds.isEmpty()) return;
    String inviteUrl;
    try {
      inviteUrl = Util.rewriteInviteLink(dcContext.getSecurejoinQr(broadcastChatId));
    } catch (Throwable t) {
      Log.w(TAG, "getSecurejoinQr failed for broadcast " + broadcastChatId, t);
      return;
    }
    if (inviteUrl == null || inviteUrl.isEmpty()) {
      Log.w(TAG, "no invite URL for broadcast " + broadcastChatId);
      return;
    }
    DcChat broadcast = dcContext.getChat(broadcastChatId);
    String channelName = broadcast != null ? broadcast.getName() : "";
    String body = getString(R.string.bmchat_channel_invite_body_fmt, channelName, inviteUrl);

    org.thoughtcrime.securesms.emailbots.EmailBotStore botStore =
        new org.thoughtcrime.securesms.emailbots.EmailBotStore(requireContext());
    int accountId = dcContext.getAccountId();
    int sent = 0;
    int skipped = 0;
    int botsAttached = 0;
    for (Integer contactId : contactIds) {
      if (contactId == null || contactId <= 0) continue;
      if (contactId == DcContact.DC_CONTACT_ID_SELF) {
        skipped++;
        continue;
      }
      org.thoughtcrime.securesms.emailbots.EmailBotConfig emailBot =
          botStore.findByContactId(accountId, contactId);
      if (emailBot != null) {
        try {
          botStore.upsert(emailBot.withAttachedChat(broadcastChatId));
          botsAttached++;
        } catch (Throwable t) {
          Log.w(TAG, "attach email bot to channel failed contact=" + contactId, t);
          skipped++;
        }
        continue;
      }
      org.thoughtcrime.securesms.bots.BotStore tgBotStore =
          new org.thoughtcrime.securesms.bots.BotStore(requireContext());
      org.thoughtcrime.securesms.bots.BotConfig tgBot =
          tgBotStore.findByContactId(accountId, contactId);
      if (tgBot != null) {
        try {
          tgBotStore.upsert(tgBot.withAttachedChat(broadcastChatId));
          botsAttached++;
        } catch (Throwable t) {
          Log.w(TAG, "attach telegram bot to channel failed contact=" + contactId, t);
          skipped++;
        }
        continue;
      }
      try {
        int dmChatId = dcContext.createChatByContactId(contactId);
        if (dmChatId <= 0) { skipped++; continue; }
        dcContext.sendTextMsg(dmChatId, body);
        sent++;
      } catch (Throwable t) {
        Log.w(TAG, "channel invite to contact " + contactId + " failed", t);
        skipped++;
      }
    }
    final int fSent = sent;
    final int fSkipped = skipped;
    final int fBots = botsAttached;
    Util.runOnMain(() -> {
      Context ctx = getContext();
      if (ctx == null) return;
      String msg;
      if (fBots > 0 && fSent == 0 && fSkipped == 0) {
        msg = getResources().getQuantityString(
            R.plurals.bmchat_email_bot_attached_to_channel, fBots, fBots);
      } else if (fSent > 0 && fSkipped == 0) {
        msg = getResources().getQuantityString(
            R.plurals.bmchat_channel_invite_sent, fSent, fSent);
      } else if (fSent > 0) {
        msg = getString(R.string.bmchat_channel_invite_partial_fmt, fSent, fSkipped);
      } else if (fBots > 0) {
        msg = getResources().getQuantityString(
            R.plurals.bmchat_email_bot_attached_to_channel, fBots, fBots);
      } else {
        msg = getString(R.string.bmchat_channel_invite_failed);
      }
      Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    });
  }

  public void onQrInvite() {
    Intent qrIntent = new Intent(getContext(), QrShowActivity.class);
    qrIntent.putExtra(QrShowActivity.CHAT_ID, chatId);
    startActivity(qrIntent);
  }

  @Override
  public void onSharedChatClicked(int chatId) {
    Intent intent = new Intent(getContext(), ConversationActivity.class);
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
    requireContext().startActivity(intent);
    requireActivity().finish();
  }

  private void onVerifiedByClicked() {
    DcContact dcContact = dcContext.getContact(contactId);
    int verifierId = dcContact.getVerifierId();
    if (verifierId != 0 && verifierId != DcContact.DC_CONTACT_ID_SELF) {
      Intent intent = new Intent(getContext(), ProfileActivity.class);
      intent.putExtra(ProfileActivity.CONTACT_ID_EXTRA, verifierId);
      startActivity(intent);
    }
  }

  private void onSendMessage() {
    DcContact dcContact = dcContext.getContact(contactId);
    int chatId = dcContext.createChatByContactId(dcContact.getId());
    if (chatId != 0) {
      Intent intent = new Intent(getActivity(), ConversationActivity.class);
      intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
      requireActivity().startActivity(intent);
      requireActivity().finish();
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      mode.getMenuInflater().inflate(R.menu.profile_context, menu);
      menu.findItem(R.id.delete).setVisible(true);
      menu.findItem(R.id.details).setVisible(false);
      menu.findItem(R.id.show_in_chat).setVisible(false);
      menu.findItem(R.id.save).setVisible(false);
      menu.findItem(R.id.share).setVisible(false);
      menu.findItem(R.id.menu_resend).setVisible(false);
      menu.findItem(R.id.menu_select_all).setVisible(false);
      mode.setTitle("1");

      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
      return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem menuItem) {
      if (menuItem.getItemId() == R.id.delete) {
        final Collection<Integer> toDelIds = adapter.getSelectedMembers();
        StringBuilder readableToDelList = new StringBuilder();
        for (Integer toDelId : toDelIds) {
          if (readableToDelList.length() > 0) {
            readableToDelList.append(", ");
          }
          readableToDelList.append(dcContext.getContact(toDelId).getDisplayName());
        }
        DcChat dcChat = dcContext.getChat(chatId);
        AlertDialog dialog =
            new AlertDialog.Builder(requireContext())
                .setPositiveButton(
                    R.string.remove_desktop,
                    (d, which) -> {
                      for (Integer toDelId : toDelIds) {
                        dcContext.removeContactFromChat(chatId, toDelId);
                      }
                      mode.finish();
                    })
                .setNegativeButton(android.R.string.cancel, null)
                .setMessage(
                    getString(
                        dcChat.isOutBroadcast()
                            ? R.string.ask_remove_from_channel
                            : R.string.ask_remove_members,
                        readableToDelList))
                .show();
        Util.redPositiveButton(dialog);
        return true;
      }
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      actionMode = null;
      adapter.clearSelection();
    }
  }
}
