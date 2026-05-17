package org.thoughtcrime.securesms;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import org.thoughtcrime.securesms.database.loaders.BMChatLinksLoader.LinkEntry;

/**
 * BMChat 2.49.80 (Phase 3): RecyclerView adapter backing the
 * Telegram-style Links tab in the Shared Media browser.
 */
public class AllMediaLinksAdapter extends RecyclerView.Adapter<AllMediaLinksAdapter.ViewHolder> {

  /** Click / long-click callbacks for the host fragment. */
  public interface OnLinkClickListener {
    void onLinkClicked(@NonNull LinkEntry entry);

    void onLinkLongClicked(@NonNull LinkEntry entry);
  }

  private final List<LinkEntry> items = new ArrayList<>();
  private final OnLinkClickListener listener;

  public AllMediaLinksAdapter(@NonNull OnLinkClickListener listener) {
    this.listener = listener;
  }

  public void setItems(@NonNull List<LinkEntry> entries) {
    items.clear();
    items.addAll(entries);
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v =
        LayoutInflater.from(parent.getContext())
            .inflate(R.layout.bmchat_all_media_link_item, parent, false);
    return new ViewHolder(v);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    LinkEntry entry = items.get(position);
    holder.url.setText(entry.url);

    String surroundingText = stripUrl(entry.context, entry.url);
    if (surroundingText.isEmpty()) {
      holder.context.setVisibility(View.GONE);
    } else {
      holder.context.setVisibility(View.VISIBLE);
      holder.context.setText(surroundingText);
    }

    CharSequence relative =
        DateUtils.getRelativeTimeSpanString(
            entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
    String date =
        entry.senderName.isEmpty() ? relative.toString() : relative + " · " + entry.senderName;
    holder.date.setText(date);

    holder.itemView.setOnClickListener(v -> listener.onLinkClicked(entry));
    holder.itemView.setOnLongClickListener(
        v -> {
          listener.onLinkLongClicked(entry);
          return true;
        });
  }

  private String stripUrl(String text, String url) {
    // Keep the surrounding context but drop the URL itself so the cell
    // doesn't show the same string twice when the message text was the
    // URL alone.
    if (text == null) return "";
    String stripped = text.replace(url, "").trim();
    return stripped;
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  static class ViewHolder extends RecyclerView.ViewHolder {
    final TextView url;
    final TextView context;
    final TextView date;

    ViewHolder(@NonNull View itemView) {
      super(itemView);
      url = itemView.findViewById(R.id.bmchat_link_url);
      context = itemView.findViewById(R.id.bmchat_link_context);
      date = itemView.findViewById(R.id.bmchat_link_date);
    }
  }
}
