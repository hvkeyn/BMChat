/*
 * Copyright (C) 2014-2017 Open Whisper Systems
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

import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_ENABLED;
import static org.thoughtcrime.securesms.connect.DcHelper.CONFIG_PROXY_URL;
import static org.thoughtcrime.securesms.util.ShareUtil.acquireRelayMessageContent;
import static org.thoughtcrime.securesms.util.ShareUtil.getDirectSharingChatId;
import static org.thoughtcrime.securesms.util.ShareUtil.getForwardedMessageAccountId;
import static org.thoughtcrime.securesms.util.ShareUtil.getSharedTitle;
import static org.thoughtcrime.securesms.util.ShareUtil.isDirectSharing;
import static org.thoughtcrime.securesms.util.ShareUtil.isForwarding;
import static org.thoughtcrime.securesms.util.ShareUtil.isRelayingMessageContent;
import static org.thoughtcrime.securesms.util.ShareUtil.resetRelayingMessageContent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.TooltipCompat;
import chat.delta.rpc.types.SecurejoinSource;
import chat.delta.rpc.types.SecurejoinUiPath;
import com.amulyakhare.textdrawable.TextDrawable;
import com.b44t.messenger.DcAccounts;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.ArrayList;
import java.util.Date;
import org.thoughtcrime.securesms.components.AvatarView;
import org.thoughtcrime.securesms.components.SearchToolbar;
import org.thoughtcrime.securesms.connect.AccountManager;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.connect.DirectShareUtil;
import org.thoughtcrime.securesms.geolocation.LocationStreamingService;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.providers.PersistentBlobProvider;
import org.thoughtcrime.securesms.proxy.ProxySettingsActivity;
import org.thoughtcrime.securesms.qr.QrActivity;
import org.thoughtcrime.securesms.qr.QrCodeHandler;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.search.SearchFragment;
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.SaveAttachmentTask;
import org.thoughtcrime.securesms.util.ScreenLockUtil;
import org.thoughtcrime.securesms.util.SendRelayedMessageUtil;
import org.thoughtcrime.securesms.util.ShareUtil;
import org.thoughtcrime.securesms.util.StorageUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class ConversationListActivity extends PassphraseRequiredActionBarActivity
    implements ConversationListFragment.ConversationSelectedListener {
  private static final String TAG = "ConversationListActivity";
  private static final String OPENPGP4FPR = "openpgp4fpr";
  public static final String CLEAR_NOTIFICATIONS = "clear_notifications";
  public static final String ACCOUNT_ID_EXTRA = "account_id";
  public static final String FROM_WELCOME = "from_welcome";
  public static final String FROM_WELCOME_RAW_QR = "from_welcome_raw_qr";

  private ConversationListFragment conversationListFragment;
  public TextView title;
  private AvatarView selfAvatar;
  private ImageView unreadIndicator;
  private SearchFragment searchFragment;
  private SearchToolbar searchToolbar;
  private ImageView searchAction;
  private ViewGroup fragmentContainer;

  /**
   * In-app sticky banner that mirrors the foreground-service update
   * download notification. Lives at the top of the chat list so the
   * user can confirm install without pulling the shade. The banner
   * is hidden by default and bound from {@link #onResume()} once
   * UpdateDownloadService starts publishing state.
   */
  private View updateBanner;
  private android.content.BroadcastReceiver updateBannerReceiver;
  private ViewGroup selfAvatarContainer;

  /**
   * used to store temporarily scanned QR to pass it back to QrCodeHandler when ScreenLockUtil is
   * used
   */
  private String qrData = null;

  private ActivityResultLauncher<Intent> relayLockLauncher;
  private ActivityResultLauncher<Intent> qrScannerLauncher;

  /**
   * used to store temporarily profile ID to delete after authorization is granted via
   * ScreenLockUtil
   */
  private int deleteProfileId = 0;

  private ActivityResultLauncher<Intent> deleteProfileLockLauncher;

  @Override
  protected void onPreCreate() {
    dynamicTheme = new DynamicNoActionBarTheme();
    super.onPreCreate();
  }

  @Override
  protected void onCreate(Bundle icicle, boolean ready) {
    relayLockLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == RESULT_OK) {
                // QrCodeHandler requested user authorization before adding a relay
                // and it was granted, so proceed to add the relay
                if (qrData != null) {
                  new QrCodeHandler(this).addRelay(qrData);
                  qrData = null;
                }
              }
            });
    deleteProfileLockLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == RESULT_OK) {
                if (deleteProfileId != 0) {
                  deleteProfile(deleteProfileId);
                  deleteProfileId = 0;
                }
              }
            });
    qrScannerLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() == RESULT_OK) {
                IntentResult scanResult =
                    IntentIntegrator.parseActivityResult(result.getResultCode(), result.getData());
                qrData = scanResult.getContents();
                new QrCodeHandler(this)
                    .handleQrData(
                        qrData, SecurejoinSource.Scan, SecurejoinUiPath.QrIcon, relayLockLauncher);
              }
            });

    addDeviceMessages(getIntent().getBooleanExtra(FROM_WELCOME, false));
    if (getIntent().getIntExtra(ACCOUNT_ID_EXTRA, -1) <= 0) {
      getIntent().putExtra(ACCOUNT_ID_EXTRA, DcHelper.getContext(this).getAccountId());
    }

    // create view
    setContentView(R.layout.conversation_list_activity);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    selfAvatar = findViewById(R.id.self_avatar);
    selfAvatarContainer = findViewById(R.id.self_avatar_container);
    unreadIndicator = findViewById(R.id.unread_indicator);
    title = findViewById(R.id.toolbar_title);
    searchToolbar = findViewById(R.id.search_toolbar);
    searchAction = findViewById(R.id.search_action);
    fragmentContainer = findViewById(R.id.fragment_container);
    try {
      updateBanner = findViewById(R.id.update_banner);
    } catch (Throwable t) {
      Log.w("ConvListAct", "update banner not present", t);
      updateBanner = null;
    }

    // add margin to avoid content hidden behind system bars
    ViewUtil.applyWindowInsetsAsMargin(searchToolbar, true, true, true, false);

    Bundle bundle = new Bundle();
    conversationListFragment =
        initFragment(R.id.fragment_container, new ConversationListFragment(), bundle);

    initializeSearchListener();

    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (searchToolbar.isVisible()) {
                  searchToolbar.collapse();
                } else {
                  Activity activity = ConversationListActivity.this;
                  if (isRelayingMessageContent(activity)) {
                    int selectedAccId = DcHelper.getContext(activity).getAccountId();
                    int initialAccId = getIntent().getIntExtra(ACCOUNT_ID_EXTRA, selectedAccId);
                    if (initialAccId != selectedAccId) {
                      // allowing to go back is dangerous, it could be activity on previously
                      // selected account,
                      // instead of figuring out account rollback in onResume in each activity
                      // (conversation, gallery, media preview, webxdc, etc.)
                      // just clear the back stack and stay in newly selected account
                      finishAffinity();
                      startActivity(new Intent(activity, ConversationListActivity.class));
                      return;
                    } else {
                      handleResetRelaying();
                    }
                  }

                  setEnabled(false);
                  getOnBackPressedDispatcher().onBackPressed();
                }
              }
            });

    TooltipCompat.setTooltipText(searchAction, getText(R.string.search_explain));

    TooltipCompat.setTooltipText(selfAvatar, getText(R.string.switch_account));
    selfAvatar.setOnClickListener(
        v -> AccountManager.getInstance().showSwitchAccountMenu(this, false));
    findViewById(R.id.avatar_and_title)
        .setOnClickListener(
            v -> {
              if (!isRelayingMessageContent(this)) {
                AccountManager.getInstance().showSwitchAccountMenu(this, false);
              }
            });

    refresh();

    // BMChat: the upstream Delta Chat checkNdkArchitecture() developer
    // dialog has been removed. BMChat releases ship prebuilt
    // libnative-utils.so for every supported ABI under clients/android/libs,
    // so a per-arch ndk-make.sh hint is meaningless to end users and the
    // dialog text linked to deltachat-android/issues, which is forbidden in
    // active BMChat UI by .cursorrules.

    DcHelper.maybeShowMigrationError(this);

    String rawQrString = getIntent().getStringExtra(FROM_WELCOME_RAW_QR);
    // Launch chat directly, if coming from onboarding with a join chat/group QR
    if (rawQrString != null) {
      QrCodeHandler qrCodeHandler = new QrCodeHandler(this);
      qrCodeHandler.secureJoinByQr(rawQrString, SecurejoinSource.Scan, SecurejoinUiPath.Unknown);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    if (isFinishing()) {
      Log.w(TAG, "Activity is finishing, aborting onNewIntent()");
      return;
    }
    super.onNewIntent(intent);
    setIntent(intent);
    if (getIntent().getIntExtra(ACCOUNT_ID_EXTRA, -1) <= 0) {
      getIntent().putExtra(ACCOUNT_ID_EXTRA, DcHelper.getContext(this).getAccountId());
    }
    refresh();
    conversationListFragment.onNewIntent();
    invalidateOptionsMenu();
  }

  private void refresh() {
    int selectedAccId = DcHelper.getContext(this).getAccountId();
    int accountId = getIntent().getIntExtra(ACCOUNT_ID_EXTRA, selectedAccId);
    if (getIntent().getBooleanExtra(CLEAR_NOTIFICATIONS, false)) {
      DcHelper.getNotificationCenter(this).removeAllNotifications(accountId);
    }
    if (accountId != selectedAccId) {
      AccountManager.getInstance().switchAccount(this, accountId);
      onProfileSwitched(accountId);
    } else {
      refreshAvatar();
      refreshUnreadIndicator();
      refreshTitle();
    }

    handleOpenpgp4fpr();
    if (isDirectSharing(this)) {
      openConversation(getDirectSharingChatId(this), -1);
    }
  }

  public void refreshTitle() {
    if (isRelayingMessageContent(this)) {
      if (isForwarding(this)) {
        title.setText(R.string.forward_to);
      } else {
        String titleStr = getSharedTitle(this);
        if (titleStr != null) { // sharing from sendToChat
          title.setText(titleStr);
        } else { // normal sharing
          title.setText(R.string.chat_share_with_title);
        }
      }
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    } else {
      boolean multiProfile = DcHelper.getAccounts(this).getAll().length > 1;
      String defText =
          multiProfile ? DcHelper.getContext(this).getName() : getString(R.string.app_name);
      title.setText(DcHelper.getConnectivitySummary(this, defText));
      // refreshTitle is called by ConversationListFragment when connectivity changes so update
      // connectivity dot here
      selfAvatar.setConnectivity(DcHelper.getContext(this).getConnectivity());
      getSupportActionBar().setDisplayHomeAsUpEnabled(false);
    }
  }

  public void refreshAvatar() {
    if (selfAvatarContainer == null) return;

    if (isRelayingMessageContent(this)) {
      selfAvatarContainer.setVisibility(View.GONE);
    } else {
      selfAvatarContainer.setVisibility(View.VISIBLE);
      DcContext dcContext = DcHelper.getContext(this);
      DcContact self = dcContext.getContact(DcContact.DC_CONTACT_ID_SELF);
      String name = dcContext.getConfig("displayname");
      if (TextUtils.isEmpty(name)) {
        name = self.getAddr();
      }
      selfAvatar.setAvatar(GlideApp.with(this), new Recipient(this, self, name), false);
    }
  }

  public void refreshUnreadIndicator() {
    int unreadCount = 0;
    DcAccounts dcAccounts = DcHelper.getAccounts(this);
    int skipId = dcAccounts.getSelectedAccount().getAccountId();
    for (int accountId : dcAccounts.getAll()) {
      if (accountId != skipId) {
        DcContext dcContext = dcAccounts.getAccount(accountId);
        if (!dcContext.isMuted()) {
          unreadCount += dcContext.getFreshMsgs().length;
        }
      }
    }

    if (unreadCount == 0) {
      unreadIndicator.setVisibility(View.GONE);
    } else {
      unreadIndicator.setImageDrawable(
          TextDrawable.builder()
              .beginConfig()
              .width(ViewUtil.dpToPx(this, 24))
              .height(ViewUtil.dpToPx(this, 24))
              .textColor(Color.WHITE)
              .bold()
              .endConfig()
              .buildRound(
                  String.valueOf(unreadCount), getResources().getColor(R.color.unread_count)));
      unreadIndicator.setVisibility(View.VISIBLE);
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshTitle();
    invalidateOptionsMenu();
    DirectShareUtil.triggerRefreshDirectShare(this);
    if (DcHelper.getContext(this).isSendingLocationsToChat(0)) {
      LocationStreamingService.ensureRunning(this);
    }
    org.thoughtcrime.securesms.update.BMChatUpdater.scheduleForActivity(this);

    // BMChat: reopening the chat list is the user's signal that they
    // are reviewing their mail; clear out any system notifications and
    // launcher badge entries for chats that no longer have unread
    // messages. Cheap, idempotent and survives external markseen events.
    try {
      DcHelper.getNotificationCenter(this).reconcileAllAccounts();
    } catch (Throwable t) {
      Log.w("ConvListAct", "reconcile failed", t);
    }

    // 2.49.39 safety net: never let the optional update banner take
    // down the main chat list activity. If anything throws on resume
    // (R8 stripping a static, a missing resource, a theme attribute
    // not resolving, …) we simply hide the banner and keep going.
    try {
      registerUpdateBannerReceiver();
      refreshUpdateBanner();
    } catch (Throwable t) {
      Log.w("ConvListAct", "update banner setup failed", t);
      if (updateBanner != null) {
        try { updateBanner.setVisibility(View.GONE); } catch (Throwable ignored) {}
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    try {
      unregisterUpdateBannerReceiver();
    } catch (Throwable ignored) {}
  }

  private void registerUpdateBannerReceiver() {
    if (updateBannerReceiver != null) return;
    updateBannerReceiver = new android.content.BroadcastReceiver() {
      @Override
      public void onReceive(android.content.Context c, Intent i) {
        refreshUpdateBanner();
      }
    };
    androidx.localbroadcastmanager.content.LocalBroadcastManager
        .getInstance(getApplicationContext())
        .registerReceiver(
            updateBannerReceiver,
            new android.content.IntentFilter(
                org.thoughtcrime.securesms.update.UpdateDownloadService
                    .ACTION_PROGRESS_BROADCAST));
  }

  private void unregisterUpdateBannerReceiver() {
    if (updateBannerReceiver == null) return;
    try {
      androidx.localbroadcastmanager.content.LocalBroadcastManager
          .getInstance(getApplicationContext())
          .unregisterReceiver(updateBannerReceiver);
    } catch (Throwable ignored) {}
    updateBannerReceiver = null;
  }

  /**
   * Render the banner from the latest UpdateDownloadService snapshot.
   * Always safe to call: hides the banner when the service is idle.
   */
  private void refreshUpdateBanner() {
    if (updateBanner == null) return;
    final org.thoughtcrime.securesms.update.UpdateDownloadService.State state =
        org.thoughtcrime.securesms.update.UpdateDownloadService.STATE;

    if (state == org.thoughtcrime.securesms.update.UpdateDownloadService.State.IDLE) {
      updateBanner.setVisibility(View.GONE);
      return;
    }
    updateBanner.setVisibility(View.VISIBLE);

    TextView title    = updateBanner.findViewById(R.id.update_banner_title);
    TextView subtitle = updateBanner.findViewById(R.id.update_banner_subtitle);
    androidx.appcompat.widget.AppCompatButton primary =
        updateBanner.findViewById(R.id.update_banner_primary);
    android.widget.ProgressBar progress =
        updateBanner.findViewById(R.id.update_banner_progress);

    String versionName =
        org.thoughtcrime.securesms.update.UpdateDownloadService.VERSION_NAME;
    if (versionName == null) versionName = "";

    switch (state) {
      case RUNNING: {
        title.setText(getString(R.string.bmchat_update_banner_running_title_fmt, versionName));
        long dl = org.thoughtcrime.securesms.update.UpdateDownloadService.DOWNLOADED;
        long total = org.thoughtcrime.securesms.update.UpdateDownloadService.TOTAL;
        int pct = org.thoughtcrime.securesms.update.UpdateDownloadService.PROGRESS;
        subtitle.setText(getString(
            R.string.bmchat_update_banner_running_subtitle_fmt,
            android.text.format.Formatter.formatShortFileSize(this, dl),
            android.text.format.Formatter.formatShortFileSize(this, Math.max(total, dl)),
            pct));
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(dl == 0);
        progress.setProgress(pct);
        primary.setText(R.string.bmchat_update_banner_cancel);
        primary.setOnClickListener(v -> {
          Intent stop = new Intent(
              this,
              org.thoughtcrime.securesms.update.UpdateDownloadService.class)
              .setAction(
                  org.thoughtcrime.securesms.update.UpdateDownloadService.ACTION_STOP);
          startService(stop);
        });
        break;
      }
      case READY: {
        title.setText(getString(R.string.bmchat_update_banner_ready_title_fmt, versionName));
        long total = org.thoughtcrime.securesms.update.UpdateDownloadService.TOTAL;
        subtitle.setText(getString(
            R.string.bmchat_update_banner_ready_subtitle_fmt,
            android.text.format.Formatter.formatShortFileSize(this, total)));
        progress.setVisibility(View.GONE);
        primary.setText(R.string.bmchat_update_banner_install);
        final String apkPath =
            org.thoughtcrime.securesms.update.UpdateDownloadService.READY_APK_PATH;
        primary.setOnClickListener(v -> {
          if (apkPath != null) {
            org.thoughtcrime.securesms.update.UpdateDownloadService
                .launchInstaller(this, new java.io.File(apkPath));
          }
        });
        break;
      }
      case ERROR: {
        title.setText(getString(R.string.bmchat_update_banner_error_title_fmt, versionName));
        String err = org.thoughtcrime.securesms.update.UpdateDownloadService.ERROR_MSG;
        subtitle.setText(err == null
            ? getString(R.string.bmchat_update_dl_failed_body)
            : err);
        progress.setVisibility(View.GONE);
        primary.setText(R.string.bmchat_update_banner_dismiss);
        primary.setOnClickListener(v -> {
          org.thoughtcrime.securesms.update.UpdateDownloadService.STATE =
              org.thoughtcrime.securesms.update.UpdateDownloadService.State.IDLE;
          refreshUpdateBanner();
        });
        break;
      }
      default:
        updateBanner.setVisibility(View.GONE);
    }
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuInflater inflater = this.getMenuInflater();
    menu.clear();

    if (isRelayingMessageContent(this)) {
      inflater.inflate(R.menu.forwarding_menu, menu);
      menu.findItem(R.id.menu_export_attachment)
          .setVisible(ShareUtil.isFromWebxdc(this) && ShareUtil.getSharedUris(this).size() == 1);
    } else {
      inflater.inflate(R.menu.text_secure_normal, menu);
      menu.findItem(R.id.menu_global_map).setVisible(Prefs.isLocationStreamingEnabled(this));
      MenuItem proxyItem = menu.findItem(R.id.menu_proxy_settings);
      if (TextUtils.isEmpty(DcHelper.get(this, CONFIG_PROXY_URL))) {
        proxyItem.setVisible(false);
      } else {
        boolean proxyEnabled = DcHelper.getInt(this, CONFIG_PROXY_ENABLED) == 1;
        proxyItem.setIcon(
            proxyEnabled ? R.drawable.ic_proxy_enabled_24 : R.drawable.ic_proxy_disabled_24);
        proxyItem.setVisible(true);
      }
    }

    super.onPrepareOptionsMenu(menu);
    return true;
  }

  private void initializeSearchListener() {
    searchAction.setOnClickListener(
        v -> {
          searchToolbar.display(
              searchAction.getX() + (searchAction.getWidth() / 2),
              searchAction.getY() + (searchAction.getHeight() / 2));
        });

    searchToolbar.setListener(
        new SearchToolbar.SearchListener() {
          @Override
          public void onSearchTextChange(String text) {
            String trimmed = text.trim();

            if (trimmed.length() > 0) {
              if (searchFragment == null) {
                searchFragment = SearchFragment.newInstance();
                getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.fragment_container, searchFragment, null)
                    .commit();
              }
              searchFragment.updateSearchQuery(trimmed);
            } else if (searchFragment != null) {
              getSupportFragmentManager().beginTransaction().remove(searchFragment).commit();
              searchFragment = null;
            }
          }

          @Override
          public void onSearchClosed() {
            if (searchFragment != null) {
              getSupportFragmentManager().beginTransaction().remove(searchFragment).commit();
              searchFragment = null;
            }
          }
        });
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    int itemId = item.getItemId();
    if (itemId == R.id.menu_new_chat) {
      createChat();
      return true;
    } else if (itemId == R.id.menu_invite_friends) {
      shareInvite();
      return true;
    } else if (itemId == R.id.menu_settings) {
      startActivity(new Intent(this, ApplicationPreferencesActivity.class));
      return true;
    } else if (itemId == R.id.menu_qr) {
      Intent intent =
          new IntentIntegrator(this).setCaptureActivity(QrActivity.class).createScanIntent();
      qrScannerLauncher.launch(intent);
      return true;
    } else if (itemId == R.id.menu_global_map) {
      WebxdcActivity.openMaps(this, 0);
      return true;
    } else if (itemId == R.id.menu_proxy_settings) {
      startActivity(new Intent(this, ProxySettingsActivity.class));
      return true;
    } else if (itemId == android.R.id.home) {
      getOnBackPressedDispatcher().onBackPressed();
      return true;
    } else if (itemId == R.id.menu_all_media) {
      startActivity(new Intent(this, AllMediaActivity.class));
      return true;
    } else if (itemId == R.id.menu_export_attachment) {
      handleSaveAttachment();
      return true;
    } else if (itemId == R.id.menu_switch_account) {
      AccountManager.getInstance().showSwitchAccountMenu(this, true);
      return true;
    }

    return false;
  }

  private void handleSaveAttachment() {
    SaveAttachmentTask.showWarningDialog(
        this,
        (dialogInterface, i) -> {
          if (StorageUtil.canWriteToMediaStore(this)) {
            performSave();
            return;
          }

          Permissions.with(this)
              .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
              .alwaysGrantOnSdk30()
              .ifNecessary()
              .withPermanentDenialDialog(getString(R.string.perm_explain_access_to_storage_denied))
              .onAllGranted(this::performSave)
              .execute();
        });
  }

  private void performSave() {
    ArrayList<Uri> uriList = ShareUtil.getSharedUris(this);
    Uri uri = uriList.get(0);
    String mimeType = PersistentBlobProvider.getMimeType(this, uri);
    String fileName = PersistentBlobProvider.getFileName(this, uri);
    SaveAttachmentTask.Attachment[] attachments =
        new SaveAttachmentTask.Attachment[] {
          new SaveAttachmentTask.Attachment(uri, mimeType, new Date().getTime(), fileName)
        };
    SaveAttachmentTask saveTask = new SaveAttachmentTask(this);
    saveTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, attachments);
    getOnBackPressedDispatcher().onBackPressed();
  }

  private void handleOpenpgp4fpr() {
    if (getIntent() != null && Intent.ACTION_VIEW.equals(getIntent().getAction())) {
      Uri uri = getIntent().getData();
      if (uri == null) {
        return;
      }

      if (uri.getScheme().equalsIgnoreCase(OPENPGP4FPR) || Util.isInviteURL(uri)) {
        QrCodeHandler qrCodeHandler = new QrCodeHandler(this);
        String inviteLink = Util.isInviteURL(uri) ? Util.getInviteLinkFromUri(uri) : uri.toString();
        qrCodeHandler.handleOnlySecureJoinQr(inviteLink, SecurejoinSource.ExternalLink, null);
      }
    }
  }

  private void handleResetRelaying() {
    resetRelayingMessageContent(this);
    refreshTitle();
    selfAvatarContainer.setVisibility(View.VISIBLE);
    conversationListFragment.onNewIntent();
    invalidateOptionsMenu();
  }

  @Override
  public void onCreateConversation(int chatId) {
    openConversation(chatId, -1);
  }

  public void openConversation(int chatId, int startingPosition) {
    searchToolbar.clearFocus();

    final DcContext dcContext = DcHelper.getContext(this);
    int fwdAccId = getForwardedMessageAccountId(this);
    if (fwdAccId == dcContext.getAccountId() && dcContext.getChat(chatId).isSelfTalk()) {
      SendRelayedMessageUtil.immediatelyRelay(this, chatId);
      Toast.makeText(
              this,
              DynamicTheme.getCheckmarkEmoji(this) + " " + getString(R.string.saved),
              Toast.LENGTH_SHORT)
          .show();
      handleResetRelaying();
      finish();
    } else {
      Intent intent = new Intent(this, ConversationActivity.class);
      intent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA, dcContext.getAccountId());
      intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
      intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, startingPosition);
      if (isRelayingMessageContent(this)) {
        acquireRelayMessageContent(this, intent);
      }
      startActivity(intent);

      overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
    }
  }

  @Override
  public void onSwitchToArchive() {
    Intent intent = new Intent(this, ConversationListArchiveActivity.class);
    if (isRelayingMessageContent(this)) {
      acquireRelayMessageContent(this, intent);
    }
    startActivity(intent);
    overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }

  private void createChat() {
    Intent intent = new Intent(this, NewConversationActivity.class);
    if (isRelayingMessageContent(this)) {
      acquireRelayMessageContent(this, intent);
    }
    startActivity(intent);
  }

  private void shareInvite() {
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    String inviteURL = Util.rewriteInviteLink(DcHelper.getContext(this).getSecurejoinQr(0));
    intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.invite_friends_text, inviteURL));
    startActivity(Intent.createChooser(intent, getString(R.string.chat_share_with_title)));
  }

  private void addDeviceMessages(boolean fromWelcome) {
    // update messages - for new messages, do not reuse or modify strings but create new ones.
    // it is not needed to keep all past update messages, however, when deleted, also the strings
    // should be deleted.
    try {
      DcContext dcContext = DcHelper.getContext(this);
      final String deviceMsgLabel = "update_2_0_0_android-h";
      if (!dcContext.wasDeviceMsgEverAdded(deviceMsgLabel)) {
        DcMsg msg = null;
        if (!fromWelcome) {
          msg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);

          // InputStream inputStream =
          // getResources().getAssets().open("device-messages/green-checkmark.jpg");
          // String outputFile = DcHelper.getBlobdirFile(dcContext, "green-checkmark", ".jpg");
          // Util.copy(inputStream, new FileOutputStream(outputFile));
          // msg.setFile(outputFile, "image/jpeg");

          msg.setText(getString(R.string.update_2_0, ""));
        }
        dcContext.addDeviceMsg(deviceMsgLabel, msg);

        if (Prefs.getStringPreference(this, Prefs.LAST_DEVICE_MSG_LABEL, "")
            .equals(deviceMsgLabel)) {
          int deviceChatId = dcContext.getChatIdByContactId(DcContact.DC_CONTACT_ID_DEVICE);
          if (deviceChatId != 0) {
            dcContext.marknoticedChat(deviceChatId);
          }
        }
        Prefs.setStringPreference(this, Prefs.LAST_DEVICE_MSG_LABEL, deviceMsgLabel);
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void onProfileSwitched(int profileId) {
    addDeviceMessages(false);
    refreshAvatar();
    refreshUnreadIndicator();
    refreshTitle();
    conversationListFragment.loadChatlistAsync();
  }

  public void onDeleteProfile(int profileId) {
    deleteProfileId = profileId;
    boolean result =
        ScreenLockUtil.applyScreenLock(
            this,
            getString(R.string.delete_account),
            getString(R.string.enter_system_secret_to_continue),
            deleteProfileLockLauncher);
    if (!result) {
      deleteProfile(profileId);
    }
  }

  private void deleteProfile(int profileId) {
    DcAccounts accounts = DcHelper.getAccounts(this);
    boolean selected = profileId == accounts.getSelectedAccount().getAccountId();
    DcHelper.getNotificationCenter(this).removeAllNotifications(profileId);
    accounts.removeAccount(profileId);
    if (selected) {
      DcContext selAcc = accounts.getSelectedAccount();
      if (selAcc.isOk()) {
        AccountManager.getInstance().switchAccount(this, selAcc.getAccountId());
        onProfileSwitched(selAcc.getAccountId());
      } else {
        AccountManager.getInstance().switchAccountAndStartActivity(this, 0);
      }
    } else {
      AccountManager.getInstance().showSwitchAccountMenu(this, false);
    }

    // title update needed to show "Delta Chat" in case there is only one profile left
    refreshTitle();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }
}
