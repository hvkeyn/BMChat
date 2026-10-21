package org.thoughtcrime.securesms.relay;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import chat.delta.rpc.types.EnteredLoginParam;
import chat.delta.rpc.types.TransportListEntry;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.registration.PulsingFloatingActionButton;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.qr.QrCodeHandler;
import org.thoughtcrime.securesms.util.ScreenLockUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class RelayListActivity extends BaseActionBarActivity
    implements RelayListAdapter.OnRelayClickListener, DcEventCenter.DcEventDelegate {

  private static final String TAG = "RelayListActivity";
  public static final String EXTRA_QR_DATA = "qr_data";

  /** Same format as the Desktop "Export connection data" file. */
  private static final int MAILBOX_CONFIG_VERSION = 1;

  private RelayListAdapter adapter;
  private Rpc rpc;
  private int accId;

  /**
   * QR provided via Intent extras needs to be saved to pass it to QrCodeHandler when authorization
   * finishes
   */
  private String qrData = null;

  private ActivityResultLauncher<Intent> screenLockLauncher;
  private ActivityResultLauncher<String> exportLauncher;
  private ActivityResultLauncher<String[]> importLauncher;

  /** Relay selected for context menu via onRelayLongClick() */
  private TransportListEntry contextMenuRelay = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_relay_list);

    screenLockLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result.getResultCode() != RESULT_OK) {
                // if user canceled unlocking, then finish
                finish();
                return;
              }
              // user authorized, then proceed to handle the QR data
              if (qrData != null) {
                new QrCodeHandler(this).handleOnlyAddRelayQr(qrData, null);
                qrData = null;
              }
            });

    exportLauncher =
        registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> {
              if (uri != null) {
                writeConnectionBackup(uri);
              }
            });

    importLauncher =
        registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
              if (uri != null) {
                readConnectionBackup(uri);
              }
            });

    rpc = DcHelper.getRpc(this);
    accId = DcHelper.getContext(this).getAccountId();

    ActionBar actionBar = getSupportActionBar();
    if (actionBar != null) {
      actionBar.setTitle(R.string.transports);
      actionBar.setDisplayHomeAsUpEnabled(true);
    }

    RecyclerView recyclerView = findViewById(R.id.relay_list);
    PulsingFloatingActionButton fabAdd = findViewById(R.id.fab_add_relay);

    // add padding to avoid content hidden behind system bars
    ScrollView scrollView = findViewById(R.id.relay_scroll_view);
    ViewUtil.applyWindowInsets(scrollView);
    // Apply insets to prevent fab from being covered by system bars
    ViewUtil.applyWindowInsetsAsMargin(fabAdd);

    qrData = getIntent().getStringExtra(EXTRA_QR_DATA);
    if (qrData != null) {
      // when the activity is opened with a QR data, we need to ask for authorization first
      boolean result =
          ScreenLockUtil.applyScreenLock(
              this,
              getString(R.string.add_transport),
              getString(R.string.enter_system_secret_to_continue),
              screenLockLauncher);
      if (!result) {
        new QrCodeHandler(this).handleOnlyAddRelayQr(qrData, null);
      }
    }

    fabAdd.setOnClickListener(v -> startActivity(new Intent(this, EditRelayActivity.class)));

    LinearLayoutManager layoutManager = new LinearLayoutManager(this);
    // Add the default divider (uses the theme’s `android.R.attr.listDivider`)
    DividerItemDecoration divider =
        new DividerItemDecoration(recyclerView.getContext(), layoutManager.getOrientation());
    recyclerView.addItemDecoration(divider);
    recyclerView.setLayoutManager(layoutManager);

    adapter = new RelayListAdapter(this);
    recyclerView.setAdapter(adapter);

    loadRelays();

    DcEventCenter eventCenter = DcHelper.getEventCenter(this);
    eventCenter.addObserver(DcContext.DC_EVENT_CONFIGURE_PROGRESS, this);
    eventCenter.addObserver(DcContext.DC_EVENT_TRANSPORTS_MODIFIED, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(this).removeObservers(this);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.relay_list, menu);
    return true;
  }

  private void loadRelays() {
    Util.runOnAnyBackgroundThread(
        () -> {
          String mainRelayAddr = "";
          try {
            mainRelayAddr = rpc.getConfig(accId, DcHelper.CONFIG_CONFIGURED_ADDRESS);
          } catch (RpcException e) {
            Log.e(TAG, "RPC.getConfig() failed", e);
          }
          String finalMainRelayAddr = mainRelayAddr;

          try {
            List<TransportListEntry> relays = rpc.listTransportsEx(accId);

            Util.runOnMain(() -> adapter.setRelays(relays, finalMainRelayAddr));
          } catch (RpcException e) {
            Log.e(TAG, "RPC.listTransports() failed", e);
            Util.runOnMain(() -> adapter.setRelays(null, finalMainRelayAddr));
          }
        });
  }

  @Override
  public void onRelayClick(TransportListEntry relay) {
    if (relay.param.addr != null && !relay.param.addr.equals(adapter.getMainRelay())) {
      Util.runOnAnyBackgroundThread(
          () -> {
            try {
              rpc.setConfig(accId, DcHelper.CONFIG_CONFIGURED_ADDRESS, relay.param.addr);
            } catch (RpcException e) {
              Log.e(TAG, "RPC.setConfig() failed", e);
            }

            loadRelays();
          });
    }
  }

  @Override
  public void onRelayLongClick(View view, TransportListEntry relay) {
    contextMenuRelay = relay;
    registerForContextMenu(view);
    openContextMenu(view);
    unregisterForContextMenu(view);
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
    super.onCreateContextMenu(menu, v, menuInfo);
    getMenuInflater().inflate(R.menu.relay_item_context, menu);

    boolean nonNullAddr = contextMenuRelay != null && contextMenuRelay.param.addr != null;
    boolean isMain = nonNullAddr && contextMenuRelay.param.addr.equals(adapter.getMainRelay());

    Util.redMenuItem(menu, R.id.menu_delete_relay);
    menu.findItem(R.id.menu_delete_relay).setVisible(!isMain);
  }

  @Override
  public void onContextMenuClosed(android.view.Menu menu) {
    super.onContextMenuClosed(menu);
    contextMenuRelay = null;
  }

  @Override
  public boolean onContextItemSelected(@NonNull MenuItem item) {
    if (contextMenuRelay == null) return super.onContextItemSelected(item);

    int itemId = item.getItemId();
    if (itemId == R.id.menu_edit_relay) {
      onRelayEdit(contextMenuRelay);
      contextMenuRelay = null;
      return true;
    } else if (itemId == R.id.menu_delete_relay) {
      onRelayDelete(contextMenuRelay);
      contextMenuRelay = null;
      return true;
    }

    return super.onContextItemSelected(item);
  }

  /**
   * Re-runs configuration for every mailbox of this account. Chat history, keys and the database
   * stay untouched: only the IMAP/SMTP connection data is applied again, which is what is needed
   * after a password change or when the server dropped the account.
   */
  private void reconnectAccount() {
    Toast.makeText(this, R.string.bmchat_relay_reconnect_started, Toast.LENGTH_SHORT).show();
    Util.runOnAnyBackgroundThread(
        () -> {
          int done = 0;
          try {
            List<EnteredLoginParam> relays = rpc.listTransports(accId);
            if (relays != null) {
              for (EnteredLoginParam relay : relays) {
                if (relay == null || relay.addr == null) continue;
                rpc.addOrUpdateTransport(accId, relay);
                done++;
              }
            }
          } catch (RpcException e) {
            Log.e(TAG, "reconnect failed", e);
            final String reason = e.getMessage() == null ? "" : e.getMessage();
            Util.runOnMain(
                () ->
                    Toast.makeText(
                            this,
                            getString(R.string.bmchat_relay_reconnect_failed) + " " + reason,
                            Toast.LENGTH_LONG)
                        .show());
            return;
          }

          final int count = done;
          Util.runOnMain(
              () -> {
                Toast.makeText(
                        this,
                        getString(R.string.bmchat_relay_reconnect_done, count),
                        Toast.LENGTH_SHORT)
                    .show();
                loadRelays();
              });
        });
  }

  private void confirmExportConnectionData() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.bmchat_relay_export)
        .setMessage(R.string.bmchat_relay_export_warning)
        .setPositiveButton(
            R.string.bmchat_relay_export,
            (d, which) -> {
              String stamp =
                  new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
              exportLauncher.launch("bmchat-connection-" + stamp + ".json");
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void writeConnectionBackup(Uri uri) {
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            List<EnteredLoginParam> mailboxes = rpc.listTransports(accId);
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("bmchatMailboxConfig", MAILBOX_CONFIG_VERSION);
            root.set(
                "mailboxes",
                mapper.valueToTree(mailboxes == null ? new ArrayList<EnteredLoginParam>() : mailboxes));
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new IOException("cannot open output stream");
            try {
              out.write(json.getBytes(StandardCharsets.UTF_8));
              out.flush();
            } finally {
              out.close();
            }

            Util.runOnMain(
                () ->
                    Toast.makeText(this, R.string.bmchat_relay_export_done, Toast.LENGTH_SHORT)
                        .show());
          } catch (Exception e) {
            Log.e(TAG, "export connection data failed", e);
            Util.runOnMain(
                () ->
                    Toast.makeText(this, R.string.bmchat_relay_export_failed, Toast.LENGTH_LONG)
                        .show());
          }
        });
  }

  private void readConnectionBackup(Uri uri) {
    Util.runOnAnyBackgroundThread(
        () -> {
          try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("cannot open input stream");
            String text;
            try {
              ByteArrayOutputStream buffer = new ByteArrayOutputStream();
              byte[] chunk = new byte[8192];
              int read;
              while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
              }
              text = buffer.toString(StandardCharsets.UTF_8.name());
            } finally {
              in.close();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(text);
            if (root == null
                || root.path("bmchatMailboxConfig").asInt(-1) != MAILBOX_CONFIG_VERSION
                || !root.path("mailboxes").isArray()) {
              throw new IllegalArgumentException("not a BMChat connection backup");
            }

            List<EnteredLoginParam> mailboxes = new ArrayList<>();
            for (JsonNode node : root.path("mailboxes")) {
              EnteredLoginParam param = mapper.treeToValue(node, EnteredLoginParam.class);
              if (param == null || param.addr == null || param.password == null) {
                throw new IllegalArgumentException("mailbox without address or password");
              }
              mailboxes.add(param);
            }
            if (mailboxes.isEmpty()) throw new IllegalArgumentException("no mailboxes");

            int imported = 0;
            for (EnteredLoginParam param : mailboxes) {
              rpc.addOrUpdateTransport(accId, param);
              imported++;
            }

            final int count = imported;
            Util.runOnMain(
                () -> {
                  Toast.makeText(
                          this,
                          getString(R.string.bmchat_relay_import_done, count),
                          Toast.LENGTH_SHORT)
                      .show();
                  loadRelays();
                });
          } catch (Exception e) {
            Log.e(TAG, "import connection data failed", e);
            Util.runOnMain(
                () ->
                    Toast.makeText(this, R.string.bmchat_relay_import_invalid, Toast.LENGTH_LONG)
                        .show());
          }
        });
  }

  private void onRelayEdit(TransportListEntry relay) {
    Intent intent = new Intent(this, EditRelayActivity.class);
    intent.putExtra(EditRelayActivity.EXTRA_ADDR, relay.param.addr);
    startActivity(intent);
  }

  private void onRelayDelete(TransportListEntry relay) {
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(R.string.remove_transport)
            .setMessage(getString(R.string.confirm_remove_or_hide_transport_x, relay.param.addr))
            .setPositiveButton(
                R.string.remove_transport,
                (d, which) -> {
                  try {
                    rpc.deleteTransport(accId, relay.param.addr);
                    loadRelays();
                  } catch (RpcException e) {
                    Log.e(TAG, "RPC.deleteTransport() failed", e);
                  }
                })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(
                R.string.hide_from_contacts,
                (d, which) -> {
                  try {
                    rpc.setTransportUnpublished(accId, relay.param.addr, true);
                    loadRelays();
                  } catch (RpcException e) {
                    Log.e(TAG, "cannot unpublish relay: ", e);
                  }
                })
            .show();
    Util.redPositiveButton(dialog);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int itemId = item.getItemId();
    if (itemId == android.R.id.home) {
      finish();
      return true;
    } else if (itemId == R.id.menu_reconnect_account) {
      reconnectAccount();
      return true;
    } else if (itemId == R.id.menu_export_connection) {
      confirmExportConnectionData();
      return true;
    } else if (itemId == R.id.menu_import_connection) {
      importLauncher.launch(new String[] {"*/*"});
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    int eventId = event.getId();
    if (eventId == DcContext.DC_EVENT_CONFIGURE_PROGRESS) {
      if (event.getData1Int() == 1000) loadRelays();
    } else if (eventId == DcContext.DC_EVENT_TRANSPORTS_MODIFIED) {
      loadRelays();
    }
  }
}
