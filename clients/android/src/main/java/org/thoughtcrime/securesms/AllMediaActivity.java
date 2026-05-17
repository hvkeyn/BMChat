package org.thoughtcrime.securesms;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;
import androidx.viewpager.widget.ViewPager;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;
import com.google.android.material.tabs.TabLayout;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import org.thoughtcrime.securesms.components.audioplay.AudioPlaybackViewModel;
import org.thoughtcrime.securesms.components.audioplay.BMChatMiniPlayerView;
import org.thoughtcrime.securesms.components.audioplay.ChatAudioQueueProvider;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.service.AudioPlaybackService;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.ViewUtil;

public class AllMediaActivity extends PassphraseRequiredActionBarActivity
    implements DcEventCenter.DcEventDelegate {
  private static final String TAG = "AllMediaActivity";

  public static final String CHAT_ID_EXTRA = "chat_id";
  public static final String CONTACT_ID_EXTRA = "contact_id";
  public static final String FORCE_GALLERY = "force_gallery";

  // BMChat 2.49.80 (Phase 3): Telegram-style Shared Media browser uses
  // separate tabs per media kind. The {@link Kind} enum drives which
  // fragment is instantiated for a tab; viewtypes are still propagated to
  // the underlying loaders that accept up to three DC_MSG_* types.
  enum Kind {
    APPS,
    PHOTOS,
    VIDEOS,
    AUDIO,
    FILES,
    LINKS
  }

  static class TabData {
    final Kind kind;
    final int title;
    final int type1;
    final int type2;
    final int type3;

    TabData(Kind kind, int title, int type1, int type2, int type3) {
      this.kind = kind;
      this.title = title;
      this.type1 = type1;
      this.type2 = type2;
      this.type3 = type3;
    }
  }

  private DcContext dcContext;
  private int chatId;
  private int contactId;

  private final ArrayList<TabData> tabs = new ArrayList<>();
  private Toolbar toolbar;
  private TabLayout tabLayout;
  private ViewPager viewPager;

  private @Nullable MediaController mediaController;
  private ListenableFuture<MediaController> mediaControllerFuture;
  private AudioPlaybackViewModel playbackViewModel;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
    dcContext = DcHelper.getContext(this);
  }

  @Override
  protected void onCreate(Bundle bundle, boolean ready) {
    // BMChat 2.49.80 (Phase 3): Telegram-style tabs in the Shared Media
    // browser. Photos and Videos are split into separate tabs, Audio
    // covers both music and voice notes, and a dedicated Links tab lists
    // every URL discovered in chat texts and captions.
    tabs.add(new TabData(Kind.APPS, R.string.webxdc_apps, DcMsg.DC_MSG_WEBXDC, 0, 0));
    tabs.add(
        new TabData(Kind.PHOTOS, R.string.bmchat_tab_photos, DcMsg.DC_MSG_IMAGE, DcMsg.DC_MSG_GIF, 0));
    tabs.add(new TabData(Kind.VIDEOS, R.string.bmchat_tab_videos, DcMsg.DC_MSG_VIDEO, 0, 0));
    tabs.add(new TabData(Kind.AUDIO, R.string.audio, DcMsg.DC_MSG_AUDIO, DcMsg.DC_MSG_VOICE, 0));
    tabs.add(new TabData(Kind.FILES, R.string.files, DcMsg.DC_MSG_FILE, 0, 0));
    tabs.add(new TabData(Kind.LINKS, R.string.tab_links, 0, 0, 0));

    setContentView(R.layout.all_media_activity);

    initializeResources();

    setSupportActionBar(this.toolbar);
    ActionBar supportActionBar = getSupportActionBar();
    if (supportActionBar != null) {
      supportActionBar.setDisplayHomeAsUpEnabled(true);
      supportActionBar.setTitle(
          isGlobalGallery() ? R.string.menu_all_media : R.string.apps_and_media);
    }

    this.tabLayout.setupWithViewPager(viewPager);
    this.viewPager.setAdapter(new AllMediaPagerAdapter(getSupportFragmentManager()));
    if (getIntent().getBooleanExtra(FORCE_GALLERY, false)) {
      // Default to the Photos tab so the long-existing "open gallery" flow
      // still lands on the most useful kind of media.
      for (int i = 0; i < tabs.size(); i++) {
        if (tabs.get(i).kind == Kind.PHOTOS) {
          this.viewPager.setCurrentItem(i, false);
          break;
        }
      }
    }

    BMChatMiniPlayerView miniPlayer = findViewById(R.id.bmchat_miniplayer);
    if (miniPlayer != null) {
      miniPlayer.setOnNavigateListener(
          (accId, targetChatId, msgId) -> {
            // The Shared Media browser does not own a conversation view, so
            // any tap on the mini-player should hand off to the proper
            // ConversationActivity for the track that is currently playing.
            Intent intent = new Intent(this, ConversationActivity.class);
            intent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA, accId);
            intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, targetChatId);
            intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, msgId);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
          });
    }

    DcEventCenter eventCenter = DcHelper.getEventCenter(this);
    eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CONTACTS_CHANGED, this);

    int accountId = DcHelper.getAccounts(this).getSelectedAccount().getAccountId();
    playbackViewModel = new ViewModelProvider(this).get(AudioPlaybackViewModel.class);
    playbackViewModel.setQueueProvider(new ChatAudioQueueProvider(this, chatId, accountId));
    initializeMediaController();
  }

  @Override
  public void onDestroy() {
    DcHelper.getEventCenter(this).removeObservers(this);
    if (mediaController != null) {
      MediaController.releaseFuture(mediaControllerFuture);
      mediaController = null;
      playbackViewModel.setMediaController(null);
    }
    playbackViewModel.setQueueProvider(null);
    super.onDestroy();
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {}

  private void initializeResources() {
    chatId = getIntent().getIntExtra(CHAT_ID_EXTRA, 0);
    contactId = getIntent().getIntExtra(CONTACT_ID_EXTRA, 0);

    if (contactId != 0) {
      chatId = dcContext.getChatIdByContactId(contactId);
    }

    if (chatId != 0) {
      DcChat dcChat = dcContext.getChat(chatId);
      if (!dcChat.isMultiUser()) {
        final int[] members = dcContext.getChatContacts(chatId);
        contactId = members.length >= 1 ? members[0] : 0;
      }
    }

    this.viewPager = ViewUtil.findById(this, R.id.pager);
    this.toolbar = ViewUtil.findById(this, R.id.toolbar);
    this.tabLayout = ViewUtil.findById(this, R.id.tab_layout);
  }

  private void initializeMediaController() {
    SessionToken sessionToken =
        new SessionToken(this, new ComponentName(this, AudioPlaybackService.class));
    mediaControllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
    mediaControllerFuture.addListener(
        () -> {
          try {
            mediaController = mediaControllerFuture.get();
            addActivityContext(this.getIntent().getExtras(), this.getClass().getName());
            playbackViewModel.setMediaController(mediaController);
          } catch (Exception e) {
            Log.e(TAG, "Error connecting to audio playback service", e);
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private void addActivityContext(Bundle extras, String activityClassName) {
    if (mediaController == null) return;

    Bundle commandArgs = new Bundle();
    commandArgs.putString("activity_class", activityClassName);
    if (extras != null) {
      commandArgs.putAll(extras);
    }

    SessionCommand updateContextCommand =
        new SessionCommand("UPDATE_ACTIVITY_CONTEXT", Bundle.EMPTY);

    mediaController.sendCustomCommand(updateContextCommand, commandArgs);
  }

  private boolean isGlobalGallery() {
    return contactId == 0 && chatId == 0;
  }

  private class AllMediaPagerAdapter extends FragmentStatePagerAdapter {
    private Object currentFragment = null;

    AllMediaPagerAdapter(FragmentManager fragmentManager) {
      super(fragmentManager);
    }

    @Override
    public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
      super.setPrimaryItem(container, position, object);
      if (currentFragment != null && currentFragment != object) {
        ActionMode action = null;
        if (currentFragment instanceof MessageSelectorFragment) {
          action = ((MessageSelectorFragment) currentFragment).getActionMode();
        }
        if (action != null) {
          action.finish();
        }
      }
      currentFragment = object;
    }

    @NonNull
    @Override
    public Fragment getItem(int position) {
      TabData data = tabs.get(position);
      Fragment fragment;
      Bundle args = new Bundle();
      int effectiveChatId = (chatId == 0 && !isGlobalGallery()) ? -1 : chatId;

      switch (data.kind) {
        case PHOTOS:
        case VIDEOS:
          fragment = new AllMediaGalleryFragment();
          args.putInt(AllMediaGalleryFragment.CHAT_ID_EXTRA, effectiveChatId);
          args.putInt(AllMediaGalleryFragment.VIEWTYPE1, data.type1);
          args.putInt(AllMediaGalleryFragment.VIEWTYPE2, data.type2);
          args.putInt(AllMediaGalleryFragment.VIEWTYPE3, data.type3);
          break;
        case LINKS:
          fragment = new AllMediaLinksFragment();
          args.putInt(AllMediaLinksFragment.CHAT_ID_EXTRA, effectiveChatId);
          break;
        case APPS:
        case AUDIO:
        case FILES:
        default:
          fragment = new AllMediaDocumentsFragment();
          args.putInt(AllMediaDocumentsFragment.CHAT_ID_EXTRA, effectiveChatId);
          args.putInt(AllMediaDocumentsFragment.VIEWTYPE1, data.type1);
          args.putInt(AllMediaDocumentsFragment.VIEWTYPE2, data.type2);
          break;
      }
      fragment.setArguments(args);
      return fragment;
    }

    @Override
    public int getCount() {
      return tabs.size();
    }

    @Override
    public CharSequence getPageTitle(int position) {
      return getString(tabs.get(position).title);
    }
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    super.onOptionsItemSelected(item);

    int itemId = item.getItemId();
    if (itemId == android.R.id.home) {
      finish();
      return true;
    }

    return false;
  }
}
