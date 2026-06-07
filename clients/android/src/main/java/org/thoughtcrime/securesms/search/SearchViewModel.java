package org.thoughtcrime.securesms.search;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.search.model.SearchResult;
import org.thoughtcrime.securesms.util.Util;

class SearchViewModel extends ViewModel {
  private static final String TAG = "SearchViewModel";
  private final ObservingLiveData searchResult;
  private String lastQuery;
  private final DcContext dcContext;
  private final Context appContext;
  private boolean forwarding = false;
  private boolean inBgSearch;
  private boolean needsAnotherBgSearch;

  SearchViewModel(@NonNull Context context) {
    this.appContext = context.getApplicationContext();
    this.dcContext = DcHelper.getContext(this.appContext);
    this.searchResult = new ObservingLiveData();
  }

  LiveData<SearchResult> getSearchResult() {
    return searchResult;
  }

  public void setForwardingMode(boolean forwarding) {
    this.forwarding = forwarding;
  }

  void updateQuery(String query) {
    lastQuery = query;
    updateQuery();
  }

  public void updateQuery() {
    if (inBgSearch) {
      needsAnotherBgSearch = true;
      Log.i(TAG, "... search call debounced");
    } else {
      inBgSearch = true;
      Util.runOnBackground(
          () -> {
            Util.sleep(100);
            needsAnotherBgSearch = false;
            queryAndCallback(lastQuery, searchResult::postValue);

            while (needsAnotherBgSearch) {
              Util.sleep(100);
              needsAnotherBgSearch = false;
              Log.i(TAG, "... executing debounced search call");
              queryAndCallback(lastQuery, searchResult::postValue);
            }

            inBgSearch = false;
          });
    }
  }

  private void queryAndCallback(@NonNull String query, @NonNull SearchViewModel.Callback callback) {
    int overallCnt = 0;

    if (TextUtils.isEmpty(query)) {
      callback.onResult(SearchResult.EMPTY);
      return;
    }

    // #1 search for chats
    long startMs = System.currentTimeMillis();
    DcChatlist conversations =
        dcContext.getChatlist(forwarding ? DcContext.DC_GCL_FOR_FORWARDING : 0, query, 0);
    overallCnt += conversations.getCnt();
    Log.i(TAG, "⏰ getChatlist(" + query + "): " + (System.currentTimeMillis() - startMs) + "ms");

    // #2 search for contacts (+ email bots from every local profile / catalog)
    startMs = System.currentTimeMillis();
    int[] contacts = dcContext.getContacts(DcContext.DC_GCL_ADD_SELF, query);
    int[] botContacts = org.thoughtcrime.securesms.emailbots.EmailBotSearchHelper
        .matchContactIds(appContext, dcContext.getAccountId(), query);
    if (botContacts.length > 0) {
      java.util.LinkedHashSet<Integer> merged = new java.util.LinkedHashSet<>();
      for (int c : contacts) merged.add(c);
      for (int c : botContacts) merged.add(c);
      org.thoughtcrime.securesms.emailbots.EmailBotStore botStore =
          new org.thoughtcrime.securesms.emailbots.EmailBotStore(appContext);
      java.util.LinkedHashSet<Integer> pruned = new java.util.LinkedHashSet<>();
      for (Integer contactId : merged) {
        com.b44t.messenger.DcContact contact = dcContext.getContact(contactId);
        String slug =
            org.thoughtcrime.securesms.emailbots.EmailBotContactHelper.nameFromBotEmail(
                contact != null ? contact.getAddr() : "");
        if (!slug.isEmpty()) {
          org.thoughtcrime.securesms.emailbots.EmailBotConfig bot =
              botStore.findByName(dcContext.getAccountId(), slug);
          if (bot == null) bot = botStore.findByNameGlobal(slug);
          if (bot != null && bot.botChatId > 0) continue;
        }
        pruned.add(contactId);
      }
      contacts = new int[pruned.size()];
      int i = 0;
      for (Integer c : pruned) contacts[i++] = c;
    }
    overallCnt += contacts.length;

    if (!query.equals(lastQuery) && overallCnt > 0) {
      Log.i(TAG, "... skipping searchMsgs(), more recent search pending");
      callback.onResult(new SearchResult(query, contacts, conversations, new int[0]));
      return;
    }
    Log.i(TAG, "⏰ getContacts(" + query + "): " + (System.currentTimeMillis() - startMs) + "ms");

    // #3 search for messages
    if (forwarding) {
      Log.i(TAG, "... searchMsgs() disabled by caller");
      callback.onResult(new SearchResult(query, contacts, conversations, new int[0]));
      return;
    }

    if (query.length() <= 1) {
      Log.i(TAG, "... skipping searchMsgs(), string too short");
      callback.onResult(new SearchResult(query, contacts, conversations, new int[0]));
      return;
    }

    if (!query.equals(lastQuery) && overallCnt > 0) {
      Log.i(TAG, "... skipping searchMsgs(), more recent search pending");
      callback.onResult(new SearchResult(query, contacts, conversations, new int[0]));
      return;
    }

    startMs = System.currentTimeMillis();
    int[] messages = dcContext.searchMsgs(0, query);
    Log.i(TAG, "⏰ searchMsgs(" + query + "): " + (System.currentTimeMillis() - startMs) + "ms");

    callback.onResult(new SearchResult(query, contacts, conversations, messages));
  }

  @NonNull
  String getLastQuery() {
    return lastQuery == null ? "" : lastQuery;
  }

  @Override
  protected void onCleared() {}

  private static class ObservingLiveData extends MutableLiveData<SearchResult> {}

  public static class Factory extends ViewModelProvider.NewInstanceFactory {

    private final Context context;

    public Factory(@NonNull Context context) {
      this.context = context;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return modelClass.cast(new SearchViewModel(context));
    }
  }

  public interface Callback {
    void onResult(@NonNull SearchResult result);
  }
}
