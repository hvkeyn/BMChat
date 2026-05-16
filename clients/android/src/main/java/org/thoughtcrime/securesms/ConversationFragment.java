/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import static com.b44t.messenger.DcContact.DC_CONTACT_ID_SELF;
import static org.thoughtcrime.securesms.util.ShareUtil.setForwardingMessageIds;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.MessageReadReceipt;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import org.thoughtcrime.securesms.ConversationAdapter.ItemClickListener;
import org.thoughtcrime.securesms.components.audioplay.AudioPlaybackViewModel;
import org.thoughtcrime.securesms.components.reminder.DozeReminder;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.reactions.AddReactionView;
import org.thoughtcrime.securesms.reactions.ReactionsDetailsFragment;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.relay.EditRelayActivity;
import org.thoughtcrime.securesms.util.AccessibilityUtil;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.thoughtcrime.securesms.util.views.ConversationAdaptiveActionsToolbar;

@SuppressLint("StaticFieldLeak")
public class ConversationFragment extends MessageSelectorFragment {
  private static final String TAG = "ConversationFragment";

  private static final int SCROLL_ANIMATION_THRESHOLD = 50;

  private final ActionModeCallback actionModeCallback = new ActionModeCallback();
  private final ItemClickListener selectionClickListener =
      new ConversationFragmentItemClickListener();

  private ConversationFragmentListener listener;

  private Recipient recipient;
  private long chatId;
  private int startingPosition;
  private boolean firstLoad;
  private RecyclerView list;
  private RecyclerView.ItemDecoration lastSeenDecoration;
  private StickyHeaderDecoration dateDecoration;
  private View scrollToBottomButton;
  private View floatingLocationButton;
  private View bottomDivider;
  private AddReactionView addReactionView;
  private TextView noMessageTextView;
  private Timer reloadTimer;

  public boolean isPaused;
  private Debouncer markseenDebouncer;
  private Rpc rpc;
  private boolean pendingAddBottomInsets;
  private boolean pendingRemoveBottomInsets;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    rpc = DcHelper.getRpc(getContext());

    DcEventCenter eventCenter = DcHelper.getEventCenter(getContext());
    eventCenter.addObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_REACTIONS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_DELIVERED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_FAILED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_READ, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);

    markseenDebouncer = new Debouncer(800);
    reloadTimer = new Timer("reloadTimer", false);
    reloadTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            Util.runOnMain(ConversationFragment.this::reloadList);
          }
        },
        60 * 1000,
        60 * 1000);
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_fragment, container, false);
    list = ViewUtil.findById(view, android.R.id.list);
    scrollToBottomButton = ViewUtil.findById(view, R.id.scroll_to_bottom_button);
    floatingLocationButton = ViewUtil.findById(view, R.id.floating_location_button);
    addReactionView = ViewUtil.findById(view, R.id.add_reaction_view);
    // Wire the Telegram-style one-tap action icons that now sit
    // under the reactions row. Each icon piggy-backs on the
    // existing handle* methods that the action-mode toolbar uses,
    // so behaviour stays identical no matter how the user
    // triggered it.
    addReactionView.setQuickActionListener((action, message) -> {
      if (actionMode != null) actionMode.finish();
      java.util.Set<DcMsg> one = java.util.Collections.singleton(message);
      switch (action) {
        case REPLY:          handleReplyMessage(message); break;
        case QUOTE_FRAGMENT: handleQuoteSelection(message); break;
        case EDIT:           handleEditMessage(message); break;
        case COPY:           handleCopyMessage(one); break;
        case FORWARD:        handleForwardMessage(one); break;
        case DELETE: {
          AudioPlaybackViewModel pvm =
              new ViewModelProvider(requireActivity()).get(AudioPlaybackViewModel.class);
          handleDeleteMessages(
              (int) chatId, one, pvm::stopByIds, pvm::stopByIds);
          break;
        }
      }
    });
    noMessageTextView = ViewUtil.findById(view, R.id.no_messages_text_view);
    bottomDivider = ViewUtil.findById(view, R.id.bottom_divider);

    scrollToBottomButton.setOnClickListener(v -> scrollToBottom());

    final SetStartingPositionLinearLayoutManager layoutManager =
        new SetStartingPositionLinearLayoutManager(
            getActivity(), LinearLayoutManager.VERTICAL, true);

    list.setHasFixedSize(false);
    list.setLayoutManager(layoutManager);
    list.setItemAnimator(null);

    new ConversationItemSwipeCallback(msg -> actionMode == null, this::handleReplyMessage)
        .attachToRecyclerView(list);

    // setLayerType() is needed to allow larger items (long texts in our case)
    // with hardware layers, drawing may result in errors as "OpenGLRenderer: Path too large to be
    // rendered into a texture"
    list.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

    if (pendingAddBottomInsets) {
      bottomDivider.setVisibility(View.GONE);
      ViewUtil.forceApplyWindowInsets(list, false, true, false, true);
      ViewUtil.forceApplyWindowInsetsAsMargin(scrollToBottomButton, true, true, true, true);
      pendingAddBottomInsets = false;
    }

    if (pendingRemoveBottomInsets) {
      bottomDivider.setVisibility(View.VISIBLE);
      ViewUtil.forceApplyWindowInsets(list, false, true, false, false);
      ViewUtil.forceApplyWindowInsetsAsMargin(scrollToBottomButton, true, true, true, false);
      pendingRemoveBottomInsets = false;
    }

    return view;
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    initializeResources();
    initializeListAdapter();

    if (savedInstanceState != null) {
      pendingQuoteSourceMsgId =
          savedInstanceState.getInt(STATE_PENDING_QUOTE_SRC, 0);
    }
  }

  private void setNoMessageText() {
    DcChat dcChat = getListAdapter().getChat();
    if (dcChat.isMultiUser()) {
      if (dcChat.isInBroadcast() || dcChat.isOutBroadcast()) {
        noMessageTextView.setText(R.string.chat_new_channel_hint);
      } else if (dcChat.isUnpromoted()) {
        noMessageTextView.setText(R.string.chat_new_group_hint);
      } else {
        noMessageTextView.setText(R.string.chat_no_messages);
      }
    } else if (dcChat.isSelfTalk()) {
      noMessageTextView.setText(R.string.saved_messages_explain);
    } else if (dcChat.isDeviceTalk()) {
      noMessageTextView.setText(R.string.device_talk_explain);
    } else if (!dcChat.isEncrypted()) {
      noMessageTextView.setText(R.string.chat_unencrypted_explanation);
    } else {
      String message = getString(R.string.chat_new_one_to_one_hint, dcChat.getName());
      noMessageTextView.setText(message);
    }
  }

  public void handleAddBottomInsets() {
    if (bottomDivider != null) {
      bottomDivider.setVisibility(View.GONE);
      ViewUtil.forceApplyWindowInsets(list, false, true, false, true);
      ViewUtil.forceApplyWindowInsetsAsMargin(scrollToBottomButton, false, false, false, true);
      pendingAddBottomInsets = false;
    } else {
      pendingAddBottomInsets = true;
    }
  }

  public void handleRemoveBottomInsets() {
    if (bottomDivider != null) {
      bottomDivider.setVisibility(View.VISIBLE);
      ViewUtil.forceApplyWindowInsets(list, false, true, false, false);
      ViewUtil.forceApplyWindowInsetsAsMargin(scrollToBottomButton, false, false, false, false);
      pendingRemoveBottomInsets = false;
    } else {
      pendingRemoveBottomInsets = true;
    }
  }

  @Override
  public void onDestroy() {
    DcHelper.getEventCenter(getContext()).removeObservers(this);
    reloadTimer.cancel();
    super.onDestroy();
  }

  @Override
  public void onAttach(Activity activity) {
    super.onAttach(activity);
    this.listener = (ConversationFragmentListener) activity;
  }

  @Override
  public void onResume() {
    super.onResume();
    Util.runOnBackground(
        () -> {
          try {
            rpc.marknoticedChat(rpc.getSelectedAccountId(), (int) chatId);
          } catch (RpcException e) {
            Log.e(TAG, "RPC error", e);
          }
        });
    if (list.getAdapter() != null) {
      list.getAdapter().notifyDataSetChanged();
    }

    if (isPaused) {
      isPaused = false;
      markseenDebouncer.publish(() -> manageMessageSeenState());
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    setLastSeen(System.currentTimeMillis());
    isPaused = true;
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    dateDecoration.onConfigurationChanged(newConfig);
  }

  public void onNewIntent() {
    if (actionMode != null) {
      actionMode.finish();
    }

    initializeResources();
    initializeListAdapter();

    if (chatId == -1) {
      reloadList();
      updateLocationButton();
    }
  }

  public void moveToLastSeen() {
    if (list == null || getListAdapter() == null) {
      Log.w(TAG, "Tried to move to last seen position, but we hadn't initialized the view yet.");
      return;
    }

    if (getListAdapter().getLastSeenPosition() < 0) {
      return;
    }
    final int lastSeenPosition = getListAdapter().getLastSeenPosition() + 1;
    if (lastSeenPosition > 0) {
      list.post(
          () ->
              ((LinearLayoutManager) list.getLayoutManager())
                  .scrollToPositionWithOffset(lastSeenPosition, list.getHeight()));
    }
  }

  public void hideAddReactionView() {
    addReactionView.hide();
  }

  private void initializeResources() {
    this.chatId =
        this.getActivity().getIntent().getIntExtra(ConversationActivity.CHAT_ID_EXTRA, -1);
    this.recipient = Recipient.from(getActivity(), Address.fromChat((int) this.chatId));
    this.startingPosition =
        this.getActivity()
            .getIntent()
            .getIntExtra(ConversationActivity.STARTING_POSITION_EXTRA, -1);
    this.firstLoad = true;

    OnScrollListener scrollListener = new ConversationScrollListener(getActivity());
    list.addOnScrollListener(scrollListener);
  }

  private void initializeListAdapter() {
    if (this.recipient != null && this.chatId != -1) {
      ConversationAdapter adapter =
          new ConversationAdapter(
              getActivity(),
              this.recipient.getChat(),
              GlideApp.with(this),
              selectionClickListener,
              this.recipient);
      list.setAdapter(adapter);
      AudioPlaybackViewModel playbackViewModel =
          new ViewModelProvider(requireActivity()).get(AudioPlaybackViewModel.class);
      adapter.setPlaybackViewModel(playbackViewModel);
      adapter.setAudioPlayPauseListener(((ConversationActivity) requireActivity()));

      if (dateDecoration != null) {
        list.removeItemDecoration(dateDecoration);
      }
      dateDecoration = new StickyHeaderDecoration(adapter, false, false);
      list.addItemDecoration(dateDecoration);

      int freshMsgs = 0;
      try {
        freshMsgs = rpc.getFreshMsgCnt(rpc.getSelectedAccountId(), (int) chatId);
      } catch (RpcException e) {
        Log.e(TAG, "RPC error", e);
      }
      SetStartingPositionLinearLayoutManager layoutManager =
          (SetStartingPositionLinearLayoutManager) list.getLayoutManager();
      if (startingPosition > -1) {
        layoutManager.setStartingPosition(startingPosition);
      } else if (freshMsgs > 0) {
        layoutManager.setStartingPosition(freshMsgs - 1);
      }

      reloadList();
      updateLocationButton();

      if (lastSeenDecoration != null) {
        list.removeItemDecoration(lastSeenDecoration);
      }
      if (freshMsgs > 0) {
        getListAdapter().setLastSeenPosition(freshMsgs - 1);
        lastSeenDecoration = new ConversationAdapter.LastSeenHeader(getListAdapter());
        list.addItemDecoration(lastSeenDecoration);
      }
    }
  }

  @Override
  protected void setCorrectMenuVisibility(Menu menu) {
    Set<DcMsg> messageRecords = getListAdapter().getSelectedItems();

    if (actionMode != null && messageRecords.size() == 0) {
      actionMode.finish();
      return;
    }

    if (messageRecords.size() > 1) {
      menu.findItem(R.id.menu_context_details).setVisible(false);
      menu.findItem(R.id.menu_context_share).setVisible(false);
      menu.findItem(R.id.menu_context_reply).setVisible(false);
      menu.findItem(R.id.menu_context_edit).setVisible(false);
      menu.findItem(R.id.menu_context_reply_privately).setVisible(false);
      menu.findItem(R.id.menu_context_quote_selection).setVisible(false);
      menu.findItem(R.id.menu_add_to_home_screen).setVisible(false);
      menu.findItem(R.id.menu_toggle_save).setVisible(false);
    } else {
      DcMsg messageRecord = messageRecords.iterator().next();
      DcChat chat = getListAdapter().getChat();
      menu.findItem(R.id.menu_context_details).setVisible(true);
      menu.findItem(R.id.menu_context_share).setVisible(messageRecord.hasFile());
      boolean canReply = canReplyToMsg(messageRecord);
      menu.findItem(R.id.menu_context_reply).setVisible(chat.canSend() && canReply);
      // Quote-fragment is only meaningful when the source message has
      // text to quote *and* the chat accepts a reply at all.
      boolean canQuoteFragment = chat.canSend()
          && canReply
          && messageRecord.getText() != null
          && !messageRecord.getText().trim().isEmpty();
      menu.findItem(R.id.menu_context_quote_selection).setVisible(canQuoteFragment);
      menu.findItem(R.id.menu_context_edit)
          .setVisible(chat.isEncrypted() && chat.canSend() && canEditMsg(messageRecord));
      boolean showReplyPrivately = chat.isMultiUser() && !messageRecord.isOutgoing() && canReply;
      menu.findItem(R.id.menu_context_reply_privately).setVisible(showReplyPrivately);
      menu.findItem(R.id.menu_add_to_home_screen)
          .setVisible(messageRecord.getType() == DcMsg.DC_MSG_WEBXDC);

      boolean saved = messageRecord.getSavedMsgId() != 0;
      MenuItem toggleSave = menu.findItem(R.id.menu_toggle_save);
      toggleSave.setVisible(messageRecord.canSave() && !chat.isSelfTalk());
      toggleSave.setIcon(
          saved ? R.drawable.baseline_bookmark_remove_24 : R.drawable.baseline_bookmark_border_24);
      toggleSave.setTitle(saved ? R.string.unsave : R.string.save);
    }

    // if one of the selected items cannot be saved, disable saving.
    boolean canSave = true;
    // if one of the selected items is not from self, disable resending.
    boolean canResend = true;
    for (DcMsg messageRecord : messageRecords) {
      if (canSave && !messageRecord.hasFile()) {
        canSave = false;
      }
      if (canResend && !messageRecord.isOutgoing()) {
        canResend = false;
      }
      if (!canSave && !canResend) {
        break;
      }
    }
    menu.findItem(R.id.menu_context_save_attachment).setVisible(canSave);
    menu.findItem(R.id.menu_resend).setVisible(canResend);
  }

  static boolean canReplyToMsg(DcMsg dcMsg) {
    return !dcMsg.isInfo();
  }

  static boolean canEditMsg(DcMsg dcMsg) {
    return dcMsg.isOutgoing()
        && !dcMsg.isInfo()
        && dcMsg.getType() != DcMsg.DC_MSG_CALL
        && !dcMsg.hasHtml()
        && !dcMsg.getText().isEmpty();
  }

  public void handleClearChat() {
    AudioPlaybackViewModel playbackViewModel =
        new ViewModelProvider(requireActivity()).get(AudioPlaybackViewModel.class);

    handleDeleteMessages(
        (int) chatId,
        getListAdapter().getMessageIds(),
        playbackViewModel::stopByIds,
        playbackViewModel::stopByIds);
  }

  private ConversationAdapter getListAdapter() {
    return (ConversationAdapter) list.getAdapter();
  }

  public void reload(Recipient recipient, long chatId) {
    this.recipient = recipient;

    if (this.chatId != chatId) {
      this.chatId = chatId;
      initializeListAdapter();
    }
  }

  public void scrollToTop() {
    ConversationAdapter adapter = (ConversationAdapter) list.getAdapter();
    if (adapter.getItemCount() > 0) {
      final int pos = adapter.getItemCount() - 1;
      list.post(
          () -> {
            list.getLayoutManager().scrollToPosition(pos);
          });
    }
  }

  public void scrollToBottom() {
    if (((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition()
            < SCROLL_ANIMATION_THRESHOLD
        && !AccessibilityUtil.areAnimationsDisabled(getContext())) {
      list.smoothScrollToPosition(0);
    } else {
      list.scrollToPosition(0);
    }
  }

  void setLastSeen(long lastSeen) {
    ConversationAdapter adapter = getListAdapter();
    if (adapter != null) {
      adapter.setLastSeen(lastSeen);
      if (lastSeenDecoration != null) {
        list.removeItemDecoration(lastSeenDecoration);
      }
      if (lastSeen > 0) {
        lastSeenDecoration = new ConversationAdapter.LastSeenHeader(adapter);
        list.addItemDecoration(lastSeenDecoration);
      }
    }
  }

  private void handleCopyMessage(final Set<DcMsg> dcMsgsSet) {
    List<DcMsg> dcMsgsList = new LinkedList<>(dcMsgsSet);
    Collections.sort(
        dcMsgsList, (lhs, rhs) -> Long.compare(lhs.getDateReceived(), rhs.getDateReceived()));
    boolean singleMsg = dcMsgsList.size() == 1;

    StringBuilder result = new StringBuilder();

    DcContext dcContext = DcHelper.getContext(getContext());
    DcMsg prevMsg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
    for (DcMsg msg : dcMsgsList) {
      if (result.length() > 0) {
        result.append("\n\n");
      }

      if (msg.getFromId() != prevMsg.getFromId() && !singleMsg) {
        DcContact contact = dcContext.getContact(msg.getFromId());
        result.append(msg.getSenderName(contact)).append(":\n");
      }
      if (msg.getType() == DcMsg.DC_MSG_TEXT || (singleMsg && !msg.getText().isEmpty())) {
        result.append(msg.getText());
      } else {
        result.append(msg.getSummarytext(10000000));
      }

      prevMsg = msg;
    }

    if (result.length() > 0) {
      Util.writeTextToClipboard(getActivity(), result.toString());
      Toast.makeText(
              getActivity(),
              getActivity().getResources().getString(R.string.copied_to_clipboard),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void handleForwardMessage(final Set<DcMsg> messageRecords) {
    Intent composeIntent = new Intent();
    int[] msgIds = DcMsg.msgSetToIds(messageRecords);
    try {
      setForwardingMessageIds(composeIntent, msgIds, rpc.getSelectedAccountId());
      ConversationListRelayingActivity.start(this, composeIntent);
      getActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    } catch (RpcException e) {
      Log.e(TAG, "RPC error", e);
    }
  }

  private void handleSaveSticker(final DcMsg message) {
    if (message.getType() != DcMsg.DC_MSG_STICKER) {
      return;
    }

    File stickerFile = message.getFileAsFile();
    if (stickerFile == null || !stickerFile.exists()) {
      Toast.makeText(getContext(), R.string.error, Toast.LENGTH_SHORT).show();
      return;
    }

    // Create stickers directory in internal storage
    File stickersDir = new File(getContext().getFilesDir(), "stickers");
    if (!stickersDir.exists()) {
      stickersDir.mkdirs();
    }

    // Copy sticker to stickers directory
    String extension = stickerFile.getName();
    int dotPos = extension.lastIndexOf('.');
    if (dotPos >= 0) {
      extension = extension.substring(dotPos + 1);
    }
    String fileName = System.currentTimeMillis() + "." + extension;
    File destFile = new File(stickersDir, fileName);

    try (java.io.FileInputStream in = new java.io.FileInputStream(stickerFile);
        java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
      byte[] buffer = new byte[1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      Toast.makeText(getContext(), R.string.saved, Toast.LENGTH_SHORT).show();

      // Refresh sticker picker to show the newly added sticker
      if (getActivity() instanceof ConversationActivity) {
        ((ConversationActivity) getActivity()).refreshStickerPicker();
      }
    } catch (Exception e) {
      Log.e(TAG, "Error saving sticker", e);
      Toast.makeText(getContext(), R.string.error, Toast.LENGTH_SHORT).show();
    }
  }

  @SuppressLint("RestrictedApi")
  private void handleReplyMessage(final DcMsg message) {
    if (getActivity() != null) {
      //noinspection ConstantConditions
      ((AppCompatActivity) getActivity()).getSupportActionBar().collapseActionView();
    }

    listener.handleReplyMessage(message);
  }

  @SuppressLint("RestrictedApi")
  private void handleEditMessage(final DcMsg message) {
    if (getActivity() != null) {
      //noinspection ConstantConditions
      ((AppCompatActivity) getActivity()).getSupportActionBar().collapseActionView();
    }

    listener.handleEditMessage(message);
  }

  /**
   * Launch the Telegram-style {@code QuoteSelectorActivity} so the
   * user can highlight a fragment of the source message with the
   * standard Android selection handles. The chosen substring is
   * returned via {@code onActivityResult} and forwarded to
   * {@link ConversationFragmentListener#handleQuoteFragment}, which
   * inserts it into the composer as a Markdown blockquote.
   *
   * <p>Replaced the older AlertDialog-with-EditText approach in
   * 2.49.37 — selection handles inside a dialog were unreliable on
   * Samsung One UI and Xiaomi MIUI, and the new full-screen surface
   * matches the layout the user requested from screenshots 4–5 of
   * the May 13 feedback.
   */
  private void handleQuoteSelection(final DcMsg msg) {
    if (getContext() == null) return;
    final String fullText = msg.getText() == null ? "" : msg.getText();
    if (fullText.trim().isEmpty()) return;
    pendingQuoteSourceMsgId = msg.getId();
    String author = "";
    try {
      com.b44t.messenger.DcContact c =
          DcHelper.getContext(getContext()).getContact(msg.getFromId());
      if (c != null) author = c.getDisplayName();
    } catch (Throwable ignored) {}
    Intent i = org.thoughtcrime.securesms.quote.QuoteSelectorActivity
        .newIntent(getContext(), author, fullText);
    startActivityForResult(i, REQUEST_QUOTE_SELECTOR);
  }

  /** Tracks the DcMsg id being quoted while {@link QuoteSelectorActivity}
   *  is on screen — the fragment can be destroyed/re-created across the
   *  trip, so we keep the id in {@link #onSaveInstanceState}. */
  private static final String STATE_PENDING_QUOTE_SRC = "bmchat-pending-quote-src";
  private static final int REQUEST_QUOTE_SELECTOR = 0xBCA1;
  private int pendingQuoteSourceMsgId = 0;

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putInt(STATE_PENDING_QUOTE_SRC, pendingQuoteSourceMsgId);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode,
                               @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_QUOTE_SELECTOR
        || resultCode != android.app.Activity.RESULT_OK
        || data == null) {
      pendingQuoteSourceMsgId = 0;
      return;
    }
    String fragment = data.getStringExtra(
        org.thoughtcrime.securesms.quote.QuoteSelectorActivity.RESULT_FRAGMENT);
    if (fragment == null || fragment.trim().isEmpty()) {
      pendingQuoteSourceMsgId = 0;
      return;
    }
    int srcId = pendingQuoteSourceMsgId;
    pendingQuoteSourceMsgId = 0;
    if (srcId <= 0) return;
    DcMsg msg = DcHelper.getContext(getContext()).getMsg(srcId);
    if (msg != null && msg.getId() != 0) {
      listener.handleQuoteFragment(msg, fragment);
    }
  }

  private void handleReplyMessagePrivately(final DcMsg msg) {

    if (getActivity() != null) {
      DcContext dcContext = DcHelper.getContext(getActivity());
      int privateChatId = dcContext.createChatByContactId(msg.getFromId());
      DcMsg replyMsg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      replyMsg.setQuote(msg);
      dcContext.setDraft(privateChatId, replyMsg);

      Intent intent = new Intent(getActivity(), ConversationActivity.class);
      intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, privateChatId);
      intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      getActivity().startActivity(intent);
    } else {
      Log.e(TAG, "Activity was null");
    }
  }

  private void handleToggleSave(final Set<DcMsg> messageRecords) {
    DcMsg msg = getSelectedMessageRecord(messageRecords);
    try {
      if (msg.getSavedMsgId() != 0) {
        rpc.deleteMessages(
            rpc.getSelectedAccountId(), Collections.singletonList(msg.getSavedMsgId()));
      } else {
        rpc.saveMsgs(rpc.getSelectedAccountId(), Collections.singletonList(msg.getId()));
      }
    } catch (RpcException e) {
      Log.e(TAG, "RPC error", e);
    }
  }

  private void reloadList() {
    reloadList(false);
  }

  private void reloadList(boolean chatModified) {
    ConversationAdapter adapter = getListAdapter();
    if (adapter == null) {
      return;
    }

    // if chat is a contact request and is accepted/blocked, the DcChat object must be reloaded,
    // otherwise DcChat.canSend() returns wrong values
    if (chatModified) {
      adapter.reloadChat();
    }

    int oldCount = 0;
    int oldIndex = 0;
    int pixelOffset = 0;
    if (!firstLoad) {
      oldCount = adapter.getItemCount();
      oldIndex =
          ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
      View firstView = list.getLayoutManager().findViewByPosition(oldIndex);
      pixelOffset =
          (firstView == null)
              ? 0
              : list.getBottom() - firstView.getBottom() - list.getPaddingBottom();
    }

    if (getContext() == null) {
      Log.e(TAG, "reloadList: getContext() was null");
      return;
    }

    DcContext dcContext = DcHelper.getContext(getContext());

    long startMs = System.currentTimeMillis();
    int[] msgs = dcContext.getChatMsgs((int) chatId, 0, 0);
    Log.i(TAG, "⏰ getChatMsgs(" + chatId + "): " + (System.currentTimeMillis() - startMs) + "ms");

    // Hide consecutive "Member X added/removed" duplicates that the
    // core occasionally records when SecureJoin emits both a local
    // join receipt and the encrypted vc-contact-confirm reply for the
    // same contact (screenshot 1 from May 10). We only filter the
    // display list — the raw rows stay in the DB so other clients
    // see the same timeline they always did.
    msgs = org.thoughtcrime.securesms.connect.BMChatInfoMsgDedupe.dedupe(dcContext, msgs);

    adapter.changeData(msgs);

    if (firstLoad) {
      if (startingPosition >= 0) {
        getListAdapter().pulseHighlightItem(startingPosition);
      }
      firstLoad = false;
    } else if (oldIndex > 0) {
      int newIndex = oldIndex + msgs.length - oldCount;

      if (newIndex < 0) {
        newIndex = 0;
        pixelOffset = 0;
      } else if (newIndex >= msgs.length) {
        newIndex = msgs.length - 1;
        pixelOffset = 0;
      }

      ((LinearLayoutManager) list.getLayoutManager())
          .scrollToPositionWithOffset(newIndex, pixelOffset);
    }

    if (!adapter.isActive()) {
      setNoMessageText();
      noMessageTextView.setVisibility(View.VISIBLE);
    } else {
      noMessageTextView.setVisibility(View.GONE);
    }

    if (!isPaused) {
      markseenDebouncer.publish(() -> manageMessageSeenState());
    }
  }

  private void updateLocationButton() {
    floatingLocationButton.setVisibility(
        DcHelper.getContext(getContext()).isSendingLocationsToChat((int) chatId)
            ? View.VISIBLE
            : View.GONE);
  }

  private void scrollAndHighlight(final int pos, boolean smooth) {
    list.post(
        () -> {
          if (smooth && !AccessibilityUtil.areAnimationsDisabled(getContext())) {
            list.smoothScrollToPosition(pos);
          } else {
            list.scrollToPosition(pos);
          }
          getListAdapter().pulseHighlightItem(pos);
        });
  }

  public void scrollToMsgId(final int msgId) {
    ConversationAdapter adapter = (ConversationAdapter) list.getAdapter();
    int position = adapter.msgIdToPosition(msgId);
    if (position != -1) {
      scrollAndHighlight(position, false);
    } else {
      Log.e(TAG, "msgId {} not found for scrolling");
    }
  }

  private void scrollMaybeSmoothToMsgId(final int msgId) {
    LinearLayoutManager layout = ((LinearLayoutManager) list.getLayoutManager());
    boolean smooth = false;
    ConversationAdapter adapter = (ConversationAdapter) list.getAdapter();
    if (adapter == null) return;
    int position = adapter.msgIdToPosition(msgId);
    if (layout != null) {
      int distance1 = Math.abs(position - layout.findFirstVisibleItemPosition());
      int distance2 = Math.abs(position - layout.findLastVisibleItemPosition());
      int distance = Math.min(distance1, distance2);
      smooth = distance < 15;
      Log.i(TAG, "Scrolling to destMsg, smoth: " + smooth + ", distance: " + distance);
    }

    if (position != -1) {
      scrollAndHighlight(position, smooth);
    } else {
      Log.e(TAG, "msgId not found for scrolling: " + msgId);
    }
  }

  public interface ConversationFragmentListener {
    void handleReplyMessage(DcMsg messageRecord);

    void handleEditMessage(DcMsg messageRecord);

    /**
     * Insert {@code fragment} into the composer as a Markdown
     * blockquote so the next sent message visibly cites the
     * substring the user picked. Implementations should leave the
     * composer focused with the caret positioned after the quote.
     */
    void handleQuoteFragment(@NonNull DcMsg sourceMessage, @NonNull String fragment);
  }

  private class ConversationScrollListener extends OnScrollListener {

    private final Animation scrollButtonInAnimation;
    private final Animation scrollButtonOutAnimation;

    private boolean wasAtBottom = true;
    private boolean wasAtZoomScrollHeight = false;

    // private long    lastPositionId        = -1;

    ConversationScrollListener(@NonNull Context context) {
      this.scrollButtonInAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_in);
      this.scrollButtonOutAnimation = AnimationUtils.loadAnimation(context, R.anim.fade_scale_out);

      this.scrollButtonInAnimation.setDuration(100);
      this.scrollButtonOutAnimation.setDuration(50);
    }

    @Override
    public void onScrolled(final RecyclerView rv, final int dx, final int dy) {
      boolean currentlyAtBottom = isAtBottom();
      boolean currentlyAtZoomScrollHeight = isAtZoomScrollHeight();
      //            int     positionId                  = getHeaderPositionId();

      if (currentlyAtZoomScrollHeight && !wasAtZoomScrollHeight) {
        ViewUtil.animateIn(scrollToBottomButton, scrollButtonInAnimation);
      } else if (currentlyAtBottom && !wasAtBottom) {
        ViewUtil.animateOut(scrollToBottomButton, scrollButtonOutAnimation, View.INVISIBLE);
      }

      //      if (positionId != lastPositionId) {
      //        bindScrollHeader(conversationDateHeader, positionId);
      //      }

      wasAtBottom = currentlyAtBottom;
      wasAtZoomScrollHeight = currentlyAtZoomScrollHeight;
      //            lastPositionId        = positionId;

      markseenDebouncer.publish(() -> manageMessageSeenState());

      ConversationFragment.this.addReactionView.move(dy);
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
      // Telegram-parity (2.49.38): the reactions + quick-actions
      // popup must vanish the moment the user starts dragging the
      // conversation. Without this, the popup hovers detached from
      // its source bubble while messages slide past — confusing the
      // user and visually broken on long-scroll chats. SCROLL_STATE_DRAGGING
      // fires on the very first finger move, before any momentum
      // takes over, which is exactly when we want to dismiss.
      if (newState == RecyclerView.SCROLL_STATE_DRAGGING
          && actionMode == null) {
        // Skip when actionMode is up — there the popup is paired
        // with the multi-select toolbar and dismissing it via
        // scroll would create an ambiguous half-state.
        hideAddReactionView();
      }
    }

    private boolean isAtBottom() {
      if (list.getChildCount() == 0) return true;

      View bottomView = list.getChildAt(0);
      int firstVisibleItem =
          ((LinearLayoutManager) list.getLayoutManager()).findFirstVisibleItemPosition();
      boolean isAtBottom = (firstVisibleItem == 0);

      return isAtBottom && bottomView.getBottom() <= list.getHeight();
    }

    private boolean isAtZoomScrollHeight() {
      return ((LinearLayoutManager) list.getLayoutManager())
              .findFirstCompletelyVisibleItemPosition()
          > 4;
    }

    //       private int getHeaderPositionId() {
    //           return
    // ((LinearLayoutManager)list.getLayoutManager()).findLastVisibleItemPosition();
    //       }

    //       private void bindScrollHeader(HeaderViewHolder headerViewHolder, int positionId) {
    //           if (((ConversationAdapter)list.getAdapter()).getHeaderId(positionId) != -1) {
    //               ((ConversationAdapter)
    // list.getAdapter()).onBindHeaderViewHolder(headerViewHolder, positionId);
    //           }
    //       }
  }

  private void manageMessageSeenState() {

    LinearLayoutManager layoutManager = (LinearLayoutManager) list.getLayoutManager();

    int firstPos = layoutManager.findFirstVisibleItemPosition();
    int lastPos = layoutManager.findLastVisibleItemPosition();
    if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) {
      return;
    }

    ArrayList<Integer> ids = new ArrayList<>(lastPos - firstPos + 1);
    for (int pos = firstPos; pos <= lastPos; pos++) {
      DcMsg message = ((ConversationAdapter) list.getAdapter()).getMsg(pos);
      if (message.getFromId() != DC_CONTACT_ID_SELF) {
        ids.add(message.getId());
      }
    }
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            rpc.markseenMsgs(rpc.getSelectedAccountId(), ids);
          } catch (RpcException e) {
            Log.e(TAG, "RPC error", e);
          }
        });
  }

  private class ConversationFragmentItemClickListener implements ItemClickListener {

    @Override
    public void onItemClick(DcMsg messageRecord, View view) {
      if (actionMode != null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();

        if (getListAdapter().getSelectedItems().size() == 0) {
          actionMode.finish();
        } else {
          hideAddReactionView();
          Menu menu = actionMode.getMenu();
          setCorrectMenuVisibility(menu);
          ConversationAdaptiveActionsToolbar.adjustMenuActions(
              menu, 10, requireActivity().getWindow().getDecorView().getMeasuredWidth());
          actionMode.setTitle(String.valueOf(getListAdapter().getSelectedItems().size()));
          actionMode.setTitleOptionalHint(
              false); // the title represents important information, also indicating implicitly,
          // more items can be selected
        }
      } else if (DozeReminder.isDozeReminderMsg(getContext(), messageRecord)) {
        DozeReminder.dozeReminderTapped(getContext());
      } else if (StatsSending.isStatsSendingDeviceMsg(getContext(), messageRecord)) {
        StatsSending.statsDeviceMsgTapped(getActivity());
      } else if (messageRecord.getInfoType() == DcMsg.DC_INFO_WEBXDC_INFO_MESSAGE) {
        if (messageRecord.getParent() != null) {
          // if the parent webxdc message still exists
          WebxdcActivity.openWebxdcActivity(
              getContext(), messageRecord.getParent(), messageRecord.getWebxdcHref());
        }
      } else if (messageRecord.getInfoType() == DcMsg.DC_INFO_LOCATIONSTREAMING_ENABLED) {
        WebxdcActivity.openMaps(getContext(), (int) chatId);
      } else if (messageRecord.getInfoType() == DcMsg.DC_INFO_CHAT_DESCRIPTION_CHANGED) {
        Intent intent = new Intent(getContext(), ProfileActivity.class);
        intent.putExtra(ProfileActivity.CHAT_ID_EXTRA, (int) chatId);
        startActivity(intent);
      } else {
        int infoContactId = messageRecord.getInfoContactId();
        if (infoContactId != 0 && infoContactId != DC_CONTACT_ID_SELF) {
          Intent intent = new Intent(getContext(), ProfileActivity.class);
          intent.putExtra(ProfileActivity.CONTACT_ID_EXTRA, infoContactId);
          startActivity(intent);
        } else {
          String self_mail = DcHelper.getContext(getContext()).getConfig("configured_mail_user");
          if (self_mail != null
              && !self_mail.isEmpty()
              && messageRecord.getText().contains(self_mail)
              && getListAdapter().getChat().isDeviceTalk()) {
            // This is a device message informing the user that the password is wrong
            Intent intent = new Intent(getActivity(), EditRelayActivity.class);
            intent.putExtra(EditRelayActivity.EXTRA_ADDR, self_mail);
            startActivity(intent);
          } else if (canQuickPopupFor(messageRecord)) {
            // Telegram-parity: a single tap on a regular chat bubble
            // opens the reactions + quick-actions popup directly,
            // no need to long-press first. Skips bubbles that have
            // their own dedicated handling (info, webxdc info,
            // call cards, etc.) — they already return earlier in
            // this method.
            showQuickActionsPopup(messageRecord, view);
          }
        }
      }
    }

    @Override
    public void onItemLongClick(DcMsg messageRecord, View view) {
      // 2.49.38 split: short tap = Telegram-style popup, long-press
      // = enter multi-select. Long-press now NEVER opens the
      // reactions popup; it instead toggles the message into the
      // selection set and brings up the top action-mode toolbar
      // (delete / forward / star / etc.) — same UX as Telegram's
      // "Forward 2" mode in the user's screenshot #2. If the popup
      // was floating from a previous short tap we hide it so the
      // user does not get both UIs at once.
      hideAddReactionView();
      if (actionMode == null) {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();
        actionMode = ((AppCompatActivity) getActivity()).startSupportActionMode(actionModeCallback);
      } else {
        ((ConversationAdapter) list.getAdapter()).toggleSelection(messageRecord);
        list.getAdapter().notifyDataSetChanged();
        if (getListAdapter().getSelectedItems().isEmpty()) {
          actionMode.finish();
        } else {
          Menu menu = actionMode.getMenu();
          setCorrectMenuVisibility(menu);
          ConversationAdaptiveActionsToolbar.adjustMenuActions(
              menu, 10, requireActivity().getWindow().getDecorView().getMeasuredWidth());
          actionMode.setTitle(String.valueOf(getListAdapter().getSelectedItems().size()));
          actionMode.setTitleOptionalHint(false);
        }
      }
    }

    /**
     * True iff a single short tap on this bubble should open the
     * Telegram-style reactions + quick-actions popup instead of
     * doing nothing. We exclude bubbles whose tap is already used
     * for navigating to another screen (info-message variants,
     * webxdc info, device-talk reminders), so users never lose the
     * existing click affordances.
     */
    private boolean canQuickPopupFor(DcMsg m) {
      if (m == null || m.isInfo()) return false;
      int t = m.getType();
      return t == DcMsg.DC_MSG_TEXT
          || t == DcMsg.DC_MSG_IMAGE
          || t == DcMsg.DC_MSG_GIF
          || t == DcMsg.DC_MSG_STICKER
          || t == DcMsg.DC_MSG_AUDIO
          || t == DcMsg.DC_MSG_VOICE
          || t == DcMsg.DC_MSG_VIDEO
          || t == DcMsg.DC_MSG_FILE
          || t == DcMsg.DC_MSG_VCARD;
    }

    /**
     * Show the reactions + quick-actions popup WITHOUT entering the
     * multi-select action mode. Used by the one-tap entry-point so
     * the user can react / reply / edit / forward / delete in a
     * single gesture, exactly like the long-press flow but lighter.
     */
    private void showQuickActionsPopup(DcMsg messageRecord, View view) {
      hideAddReactionView();
      addReactionView.show(
          messageRecord,
          view,
          () -> hideAddReactionView());
    }

    private void jumpToOriginal(DcMsg original) {
      if (original == null) {
        Log.i(
            TAG,
            "Clicked on a quote or jump-to-original whose original message was deleted/non-existing.");
        Toast.makeText(
                getContext(),
                R.string.ConversationFragment_quoted_message_not_found,
                Toast.LENGTH_SHORT)
            .show();
        return;
      }

      int foreignChatId = original.getChatId();
      if (foreignChatId != 0 && foreignChatId != chatId) {
        Intent intent = new Intent(getActivity(), ConversationActivity.class);
        intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, foreignChatId);
        int start = DcMsg.getMessagePosition(original, DcHelper.getContext(getContext()));
        intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, start);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        ((ConversationActivity) getActivity()).hideSoftKeyboard();
        if (getActivity() != null) {
          getActivity().startActivity(intent);
        } else {
          Log.e(TAG, "Activity was null");
        }
      } else {
        scrollMaybeSmoothToMsgId(original.getId());
      }
    }

    @Override
    public void onJumpToOriginalClicked(DcMsg messageRecord) {
      jumpToOriginal(DcHelper.getContext(getContext()).getMsg(messageRecord.getOriginalMsgId()));
    }

    @Override
    public void onQuoteClicked(DcMsg messageRecord) {
      jumpToOriginal(messageRecord.getQuotedMsg());
    }

    @Override
    public void onShowFullClicked(DcMsg messageRecord) {
      Intent intent = new Intent(getActivity(), FullMsgActivity.class);
      intent.putExtra(FullMsgActivity.MSG_ID_EXTRA, messageRecord.getId());
      intent.putExtra(
          FullMsgActivity.BLOCK_LOADING_REMOTE, getListAdapter().getChat().isContactRequest());
      startActivity(intent);
      getActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    }

    @Override
    public void onDownloadClicked(DcMsg messageRecord) {
      DcHelper.getContext(getContext()).downloadFullMsg(messageRecord.getId());
    }

    @Override
    public void onReactionClicked(DcMsg messageRecord) {
      ReactionsDetailsFragment dialog = ReactionsDetailsFragment.newInstance(messageRecord.getId());
      dialog.show(getActivity().getSupportFragmentManager(), null);
    }

    @Override
    public void onReadReceiptsClicked(DcMsg messageRecord) {
      showReadReceiptsDialog(messageRecord);
    }

    @Override
    public void onStickerClicked(DcMsg messageRecord) {
      new AlertDialog.Builder(getContext())
          .setMessage(R.string.ask_add_sticker_to_collection)
          .setPositiveButton(
              R.string.ok,
              (dialog, which) -> {
                handleSaveSticker(messageRecord);
              })
          .setNegativeButton(R.string.cancel, null)
          .show();
    }

    private void showReadReceiptsDialog(@NonNull DcMsg messageRecord) {
      try {
        List<MessageReadReceipt> receipts =
            rpc.getMessageReadReceipts(DcHelper.getContext(getContext()).getAccountId(), messageRecord.getId());
        StringBuilder body = new StringBuilder();
        if (receipts.isEmpty()) {
          body.append(getString(R.string.bmchat_read_receipts_empty));
        } else {
          for (MessageReadReceipt receipt : receipts) {
            DcContact contact =
                DcHelper.getContext(getContext()).getContact(receipt.contactId);
            String name = contact.getDisplayName();
            if (name == null || name.trim().isEmpty()) {
              name = contact.getAddr();
            }
            long timestampMillis = receipt.timestamp == null ? 0 : receipt.timestamp * 1000L;
            String when =
                timestampMillis > 0
                    ? DateUtils.getExtendedRelativeTimeSpanString(requireContext(), timestampMillis)
                    : "";
            if (body.length() > 0) body.append('\n');
            body.append(name);
            if (!when.isEmpty()) {
              body.append(" · ").append(when);
            }
          }
        }
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.bmchat_read_receipts_title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show();
      } catch (RpcException e) {
        Toast.makeText(getContext(), R.string.bmchat_read_receipts_error, Toast.LENGTH_SHORT).show();
      }
    }
  }

  private class ActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.conversation_context, menu);

      mode.setTitle("1");

      Util.redMenuItem(menu, R.id.menu_context_delete_message);
      setCorrectMenuVisibility(menu);
      ConversationAdaptiveActionsToolbar.adjustMenuActions(
          menu, 10, requireActivity().getWindow().getDecorView().getMeasuredWidth());
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
      return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      ((ConversationAdapter) list.getAdapter()).clearSelection();
      list.getAdapter().notifyDataSetChanged();

      actionMode = null;
      hideAddReactionView();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      hideAddReactionView();
      int itemId = item.getItemId();
      AudioPlaybackViewModel playbackViewModel =
          new ViewModelProvider(requireActivity()).get(AudioPlaybackViewModel.class);

      if (itemId == R.id.menu_context_copy) {
        handleCopyMessage(getListAdapter().getSelectedItems());
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_context_delete_message) {
        handleDeleteMessages(
            (int) chatId,
            getListAdapter().getSelectedItems(),
            playbackViewModel::stopByIds,
            playbackViewModel::stopByIds);
        return true;
      } else if (itemId == R.id.menu_context_share) {
        DcHelper.openForViewOrShare(
            getContext(),
            getSelectedMessageRecord(getListAdapter().getSelectedItems()).getId(),
            Intent.ACTION_SEND);
        return true;
      } else if (itemId == R.id.menu_context_details) {
        handleDisplayDetails(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_context_forward) {
        handleForwardMessage(getListAdapter().getSelectedItems());
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_add_to_home_screen) {
        WebxdcActivity.addToHomeScreen(
            getActivity(), getSelectedMessageRecord(getListAdapter().getSelectedItems()).getId());
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_context_save_attachment) {
        handleSaveAttachment(getListAdapter().getSelectedItems());
        return true;
      } else if (itemId == R.id.menu_context_reply) {
        handleReplyMessage(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_context_edit) {
        handleEditMessage(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_context_reply_privately) {
        handleReplyMessagePrivately(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
        return true;
      } else if (itemId == R.id.menu_context_quote_selection) {
        handleQuoteSelection(getSelectedMessageRecord(getListAdapter().getSelectedItems()));
        actionMode.finish();
        return true;
      } else if (itemId == R.id.menu_resend) {
        handleResendMessage(getListAdapter().getSelectedItems());
        return true;
      } else if (itemId == R.id.menu_toggle_save) {
        handleToggleSave(getListAdapter().getSelectedItems());
        actionMode.finish();
        return true;
      }
      return false;
    }
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    switch (event.getId()) {
      case DcContext.DC_EVENT_MSGS_CHANGED:
        if (event.getData1Int() == 0 // deleted messages or batch insert
            || event.getData1Int() == chatId) {
          reloadList();
        }
        break;

      case DcContext.DC_EVENT_REACTIONS_CHANGED:
      case DcContext.DC_EVENT_INCOMING_MSG:
      case DcContext.DC_EVENT_MSG_DELIVERED:
      case DcContext.DC_EVENT_MSG_FAILED:
      case DcContext.DC_EVENT_MSG_READ:
        if (event.getData1Int() == chatId) {
          reloadList();
        }
        break;

      case DcContext.DC_EVENT_CHAT_MODIFIED:
        if (event.getData1Int() == chatId) {
          updateLocationButton();
          reloadList(true);
        }
        break;
    }

    // removing the "new message" marker on incoming messages may be a bit unexpected,
    // esp. when a series of message is coming in and after the first, the screen is turned on,
    // the "new message" marker will flash for a short moment and disappear.
    /*if (eventId == DcContext.DC_EVENT_INCOMING_MSG && isResumed()) {
        setLastSeen(-1);
    }*/
  }
}
