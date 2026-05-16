package org.thoughtcrime.securesms.reactions;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.emoji2.emojipicker.EmojiPickerView;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.Reactions;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.ViewUtil;

public class AddReactionView extends LinearLayout {
  private AppCompatTextView[] defaultReactionViews;
  private AppCompatTextView anyReactionView;
  // Telegram-style single-tap action icons sitting under the
  // reactions row. Bound lazily because they live in a layout
  // subtree that is only inflated when the host fragment exists.
  private AppCompatImageView actionReplyView;
  private AppCompatImageView actionQuoteFragmentView;
  private AppCompatImageView actionEditView;
  private AppCompatImageView actionCopyView;
  private AppCompatImageView actionForwardView;
  private AppCompatImageView actionDeleteView;
  private View actionsRow;
  private boolean anyReactionClearsReaction;
  private Context context;
  private DcContext dcContext;
  private Rpc rpc;
  private DcMsg msgToReactTo;
  private AddReactionListener listener;
  private QuickActionListener actionListener;

  public AddReactionView(Context context) {
    super(context);
  }

  public AddReactionView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  private void init() {
    if (context == null) {
      context = getContext();
      dcContext = DcHelper.getContext(context);
      rpc = DcHelper.getRpc(getContext());
      defaultReactionViews =
          new AppCompatTextView[] {
            findViewById(R.id.reaction_0),
            findViewById(R.id.reaction_1),
            findViewById(R.id.reaction_2),
            findViewById(R.id.reaction_3),
            findViewById(R.id.reaction_4),
          };
      for (int i = 0; i < defaultReactionViews.length; i++) {
        final int ii = i;
        defaultReactionViews[i].setOnClickListener(v -> defaultReactionClicked(ii));
      }
      anyReactionView = findViewById(R.id.reaction_any);
      anyReactionView.setOnClickListener(v -> anyReactionClicked());

      // Telegram-style action icons. They are optional — only
      // wired when the host layout actually inflated them.
      actionsRow        = findViewById(R.id.add_reaction_actions);
      actionReplyView         = findViewById(R.id.action_reply);
      actionQuoteFragmentView = findViewById(R.id.action_quote_fragment);
      actionEditView          = findViewById(R.id.action_edit);
      actionCopyView          = findViewById(R.id.action_copy);
      actionForwardView       = findViewById(R.id.action_forward);
      actionDeleteView        = findViewById(R.id.action_delete);
      if (actionReplyView != null) {
        actionReplyView.setOnClickListener(v -> dispatchAction(Action.REPLY));
      }
      if (actionQuoteFragmentView != null) {
        actionQuoteFragmentView.setOnClickListener(
            v -> dispatchAction(Action.QUOTE_FRAGMENT));
      }
      if (actionEditView != null) {
        actionEditView.setOnClickListener(v -> dispatchAction(Action.EDIT));
      }
      if (actionCopyView != null) {
        actionCopyView.setOnClickListener(v -> dispatchAction(Action.COPY));
      }
      if (actionForwardView != null) {
        actionForwardView.setOnClickListener(v -> dispatchAction(Action.FORWARD));
      }
      if (actionDeleteView != null) {
        actionDeleteView.setOnClickListener(v -> dispatchAction(Action.DELETE));
      }
    }
  }

  public void show(DcMsg msgToReactTo, View parentView, AddReactionListener listener) {
    init(); // init delayed as needed

    // 2.49.38: only info bubbles are categorically excluded. In
    // 2.49.37 we also bailed out when canSend()==false, which
    // hides the popup entirely on channel posts the user is just
    // subscribed to — even though Copy / Forward / Delete are
    // still perfectly valid actions there. We now show the popup
    // unconditionally for non-info messages and let
    // updateActionsVisibility() and the reactions row decide what
    // to render.
    if (msgToReactTo.isInfo()) {
      return;
    }

    this.msgToReactTo = msgToReactTo;
    this.listener = listener;

    boolean canSend = dcContext.getChat(msgToReactTo.getChatId()).canSend();

    // Hide the entire reactions row when sending is forbidden —
    // reactions in Delta Chat are real outgoing messages and they
    // would fail to deliver. Quick-actions row stays.
    View reactionsRow = findViewById(R.id.add_reaction_row);
    if (reactionsRow != null) {
      reactionsRow.setVisibility(canSend ? View.VISIBLE : View.GONE);
    }

    updateActionsVisibility(msgToReactTo);

    final String existingReaction = canSend ? getSelfReaction() : null;
    if (canSend) {
      boolean existingHilited = false;
      for (AppCompatTextView defaultReactionView : defaultReactionViews) {
        if (defaultReactionView.getText().toString().equals(existingReaction)) {
          defaultReactionView.setBackground(
              ContextCompat.getDrawable(context, R.drawable.reaction_pill_background_selected));
          existingHilited = true;
        } else {
          defaultReactionView.setBackground(null);
        }
      }

      if (existingReaction != null && !existingHilited) {
        anyReactionView.setText(existingReaction);
        anyReactionView.setBackground(
            ContextCompat.getDrawable(context, R.drawable.reaction_pill_background_selected));
        anyReactionClearsReaction = true;
      } else {
        anyReactionView.setText("⋯");
        anyReactionView.setBackground(null);
        anyReactionClearsReaction = false;
      }
    }

    final int offset = (int) (this.getHeight() * 0.666);
    int x = (int) parentView.getX();
    if (msgToReactTo.isOutgoing()) {
      x += parentView.getWidth() - offset - this.getWidth();
    } else {
      x += offset;
    }
    ViewUtil.setLeftMargin(this, Math.max(x, 0));

    int y = Math.max((int) parentView.getY() - offset, offset / 2);
    ViewUtil.setTopMargin(this, y);

    setVisibility(View.VISIBLE);
  }

  public void hide() {
    setVisibility(View.GONE);
  }

  public void move(int dy) {
    if (msgToReactTo != null && getVisibility() == View.VISIBLE) {
      ViewUtil.setTopMargin(this, (int) this.getY() - dy);
    }
  }

  private String getSelfReaction() {
    String result = null;
    try {
      final Reactions reactions =
          rpc.getMessageReactions(dcContext.getAccountId(), msgToReactTo.getId());
      if (reactions != null) {
        final Map<String, List<String>> reactionsByContact = reactions.reactionsByContact;
        final List<String> selfReactions =
            reactionsByContact.get(String.valueOf(DcContact.DC_CONTACT_ID_SELF));
        if (selfReactions != null && !selfReactions.isEmpty()) {
          result = selfReactions.get(0);
        }
      }
    } catch (RpcException e) {
      e.printStackTrace();
    }
    return result;
  }

  private void defaultReactionClicked(int i) {
    final String reaction = defaultReactionViews[i].getText().toString();
    sendReaction(reaction);

    if (listener != null) {
      listener.onShallHide();
    }
  }

  private void anyReactionClicked() {
    if (anyReactionClearsReaction) {
      sendReaction(null);
    } else {
      View pickerLayout = View.inflate(context, R.layout.reaction_picker, null);

      final AlertDialog alertDialog =
          new AlertDialog.Builder(context)
              .setView(pickerLayout)
              .setTitle(R.string.react)
              .setPositiveButton(R.string.cancel, null)
              .create();

      EmojiPickerView pickerView = ViewUtil.findById(pickerLayout, R.id.emoji_picker);
      pickerView.setOnEmojiPickedListener(
          (it) -> {
            sendReaction(it.getEmoji());
            alertDialog.dismiss();
          });

      alertDialog.show();
    }

    if (listener != null) {
      listener.onShallHide();
    }
  }

  private void sendReaction(final String reaction) {
    try {
      if (reaction == null || reaction.equals(getSelfReaction())) {
        rpc.sendReaction(
            dcContext.getAccountId(), msgToReactTo.getId(), Collections.singletonList(""));
      } else {
        rpc.sendReaction(
            dcContext.getAccountId(), msgToReactTo.getId(), Collections.singletonList(reaction));
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public interface AddReactionListener {
    void onShallHide();
  }

  /** Bind the Telegram-style action row to a host callback. */
  public void setQuickActionListener(QuickActionListener l) {
    this.actionListener = l;
  }

  /**
   * Decides which one-tap icons are visible for the current message.
   * Mirrors the logic that lives in
   * {@code ConversationFragment#setCorrectMenuVisibility} so the
   * floating actions stay in sync with the toolbar action mode.
   */
  private void updateActionsVisibility(DcMsg msg) {
    if (actionsRow == null) return;
    boolean canSend = dcContext.getChat(msg.getChatId()).canSend();
    boolean hasText = msg.getText() != null && !msg.getText().isEmpty();
    boolean isOutgoing = msg.isOutgoing();
    boolean canReply  = canSend && !msg.isInfo();
    boolean canQuoteFragment = canReply && hasText;
    boolean canEdit   = canSend && isOutgoing && hasText;
    boolean canCopy   = hasText;
    boolean canFwd    = !msg.isInfo();
    boolean canDelete = true;

    show(actionReplyView,         canReply);
    show(actionQuoteFragmentView, canQuoteFragment);
    show(actionEditView,          canEdit);
    show(actionCopyView,          canCopy);
    show(actionForwardView,       canFwd);
    show(actionDeleteView,        canDelete);

    boolean anyVisible = canReply || canQuoteFragment || canEdit
        || canCopy || canFwd || canDelete;
    actionsRow.setVisibility(anyVisible ? View.VISIBLE : View.GONE);
  }

  private static void show(View v, boolean visible) {
    if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void dispatchAction(Action action) {
    if (actionListener != null && msgToReactTo != null) {
      actionListener.onQuickAction(action, msgToReactTo);
    }
    if (listener != null) {
      listener.onShallHide();
    }
  }

  public enum Action { REPLY, QUOTE_FRAGMENT, EDIT, COPY, FORWARD, DELETE }

  /** Receives one-tap actions issued from the floating bubble. */
  public interface QuickActionListener {
    void onQuickAction(Action action, DcMsg message);
  }
}
