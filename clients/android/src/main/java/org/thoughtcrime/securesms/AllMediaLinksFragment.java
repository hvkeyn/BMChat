package org.thoughtcrime.securesms;

import static com.b44t.messenger.DcChat.DC_CHAT_NO_CHAT;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import java.util.List;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.database.loaders.BMChatLinksLoader;
import org.thoughtcrime.securesms.database.loaders.BMChatLinksLoader.LinkEntry;
import org.thoughtcrime.securesms.util.ViewUtil;

/**
 * BMChat 2.49.80 (Phase 3): Telegram-style Links tab for the Shared Media
 * browser. Renders every URL discovered in the chat's messages and lets
 * the user open them in a browser or copy them to the clipboard.
 */
public class AllMediaLinksFragment extends Fragment
    implements LoaderManager.LoaderCallbacks<List<LinkEntry>>,
        DcEventCenter.DcEventDelegate,
        AllMediaLinksAdapter.OnLinkClickListener {

  public static final String CHAT_ID_EXTRA = "chat_id";

  private RecyclerView recyclerView;
  private TextView emptyHint;
  private AllMediaLinksAdapter adapter;
  private int chatId;

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    chatId = getArguments().getInt(CHAT_ID_EXTRA, -1);
    getLoaderManager().initLoader(0, null, this);
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.bmchat_all_media_links_fragment, container, false);
    this.recyclerView = ViewUtil.findById(view, R.id.bmchat_links_recycler);
    this.emptyHint = ViewUtil.findById(view, R.id.bmchat_links_empty);

    this.adapter = new AllMediaLinksAdapter(this);
    this.recyclerView.setAdapter(adapter);
    this.recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
    this.recyclerView.setHasFixedSize(true);
    ViewUtil.applyWindowInsets(recyclerView, true, false, true, true);

    DcEventCenter eventCenter = DcHelper.getEventCenter(requireContext());
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
    getLoaderManager().restartLoader(0, null, this);
  }

  @NonNull
  @Override
  public Loader<List<LinkEntry>> onCreateLoader(int id, Bundle args) {
    return new BMChatLinksLoader(requireContext(), chatId);
  }

  @Override
  public void onLoadFinished(@NonNull Loader<List<LinkEntry>> loader, List<LinkEntry> data) {
    if (adapter == null) return;
    adapter.setItems(data);

    boolean empty = data.isEmpty();
    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
    if (chatId == DC_CHAT_NO_CHAT) {
      emptyHint.setText(R.string.bmchat_tab_all_links_empty_hint);
    }
  }

  @Override
  public void onLoaderReset(@NonNull Loader<List<LinkEntry>> loader) {
    if (adapter != null) {
      adapter.setItems(java.util.Collections.emptyList());
    }
  }

  @Override
  public void onLinkClicked(@NonNull LinkEntry entry) {
    Context ctx = getContext();
    if (ctx == null) return;
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(entry.url));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(ctx, R.string.no_app_to_handle_data, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void onLinkLongClicked(@NonNull LinkEntry entry) {
    Context ctx = getContext();
    if (ctx == null) return;

    // Telegram-style sheet with a focused set of actions: open, copy,
    // share, and jump to the source message inside the chat.
    String[] options =
        new String[] {
          ctx.getString(R.string.open),
          ctx.getString(R.string.bmchat_copy_link),
          ctx.getString(R.string.menu_share),
          ctx.getString(R.string.show_in_chat)
        };

    new AlertDialog.Builder(ctx)
        .setTitle(entry.url)
        .setItems(
            options,
            (dialog, which) -> {
              switch (which) {
                case 0:
                  onLinkClicked(entry);
                  break;
                case 1:
                  copyToClipboard(ctx, entry.url);
                  break;
                case 2:
                  shareLink(ctx, entry.url);
                  break;
                case 3:
                  jumpToMessage(ctx, entry);
                  break;
                default:
                  break;
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void copyToClipboard(Context ctx, String url) {
    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    if (cm != null) {
      cm.setPrimaryClip(ClipData.newPlainText("link", url));
      Toast.makeText(ctx, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }
  }

  private void shareLink(Context ctx, String url) {
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, url);
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.menu_share)));
  }

  private void jumpToMessage(Context ctx, LinkEntry entry) {
    if (entry.chatId == 0) return;
    Intent intent = new Intent(ctx, ConversationActivity.class);
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, entry.chatId);
    intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, entry.msgId);
    ctx.startActivity(intent);
  }
}
