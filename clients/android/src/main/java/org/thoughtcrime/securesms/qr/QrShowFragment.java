package org.thoughtcrime.securesms.qr;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGImageView;
import com.caverock.androidsvg.SVGParseException;
import java.io.File;
import java.io.FileOutputStream;
import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.ScaleStableImageView;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.Util;

public class QrShowFragment extends Fragment implements DcEventCenter.DcEventDelegate {

  private static final String TAG = "QrShowFragment";
  public static final int WHITE = 0xFFFFFFFF;
  private static final int BLACK = 0xFF000000;
  private static final int WIDTH = 400;
  private static final int HEIGHT = 400;
  private static final String CHAT_ID = "chat_id";

  private int chatId = 0;

  private int numJoiners;

  private DcEventCenter dcEventCenter;

  private DcContext dcContext;

  private View.OnClickListener scanClicklistener;

  public QrShowFragment() {
    this(null);
  }

  public QrShowFragment(View.OnClickListener scanClicklistener) {
    super();
    this.scanClicklistener = scanClicklistener;
  }

  @Override
  public void onCreate(Bundle bundle) {
    super.onCreate(bundle);

    getActivity()
        .getWindow()
        .addFlags(
            WindowManager.LayoutParams
                .FLAG_KEEP_SCREEN_ON); // keeping the screen on also avoids falling back from IDLE
    // to POLL
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.qr_show_fragment, container, false);

    dcContext = DcHelper.getContext(getActivity());
    dcEventCenter = DcHelper.getEventCenter(getActivity());

    Bundle extras = getActivity().getIntent().getExtras();
    if (extras != null) {
      chatId = extras.getInt(CHAT_ID);
    }

    dcEventCenter.addObserver(DcContext.DC_EVENT_SECUREJOIN_INVITER_PROGRESS, this);

    numJoiners = 0;

    ScaleStableImageView backgroundView = view.findViewById(R.id.background);
    Drawable drawable;
    if (DynamicTheme.isDarkTheme(getActivity())) {
      drawable = getActivity().getResources().getDrawable(R.drawable.background_hd_dark);
    } else {
      drawable = getActivity().getResources().getDrawable(R.drawable.background_hd);
    }
    backgroundView.setImageDrawable(drawable);

    SVGImageView imageView = view.findViewById(R.id.qrImage);
    try {
      SVG svg = SVG.getFromString(fixSVG(dcContext.getSecurejoinQrSvg(chatId)));
      imageView.setSVG(svg);
    } catch (SVGParseException e) {
      e.printStackTrace();
    } catch (Exception e) {
      e.printStackTrace();
      Activity activity = getActivity();
      if (activity != null) {
        activity.finish();
      }
    }

    view.findViewById(R.id.share_link_button).setOnClickListener((v) -> showInviteLinkDialog());
    Button scanBtn = view.findViewById(R.id.scan_qr_button);
    if (scanClicklistener != null) {
      scanBtn.setVisibility(View.VISIBLE);
      scanBtn.setOnClickListener(scanClicklistener);
    } else {
      scanBtn.setVisibility(View.GONE);
    }

    return view;
  }

  public static String fixSVG(String svg) {
    // HACK: move avatar-letter down, baseline alignment not working,
    // see https://github.com/deltachat/deltachat-core-rust/pull/2815#issuecomment-978067378 ,
    // suggestions welcome :)
    String out = svg.replace("y=\"281.136\"", "y=\"296\"");
    out = stripDeltaBranding(out);
    return out;
  }

  /**
   * Removes Delta Chat branding bits embedded in the QR SVG by the bundled
   * native core (footer text/logo, centerpiece "δ" overlay) and rewrites
   * any "i.delta.chat" hyperlinks to the BMChat host. The native lib still
   * ships the Delta artwork; until the core is rebuilt with BMChat assets we
   * scrub it here.
   */
  static String stripDeltaBranding(String svg) {
    if (svg == null) return null;
    String out = svg;
    // 1. Footer wordmark and delta-logo path.
    out = out.replace(">get.delta.chat<", ">BMChat<");
    out = out.replaceAll("(?s)<path[^>]*id=\"path84310\"[^>]*/>", "");
    // 2. Centerpiece "δ" overlay (white circle + black circle + glyph group).
    //    Matched by the unique non-uniform scale used for the glyph.
    out = out.replaceAll(
        "(?s)<g[^>]*transform=\"scale\\(1\\.1342891,0\\.88160947\\)\"[^>]*>.*?</g>",
        "");
    // 3. Hyperlinks pointing at any upstream/legacy invite host.
    for (String legacy : Util.LEGACY_INVITE_HOSTS) {
      out = out.replace("https://" + legacy, Util.INVITE_LINK_PREFIX);
      out = out.replace("http://"  + legacy, Util.INVITE_LINK_PREFIX);
    }
    return out;
  }

  public void shareInviteURL() {
    try {
      Intent intent = new Intent(Intent.ACTION_SEND);
      intent.setType("text/plain");
      String inviteURL = Util.rewriteInviteLink(dcContext.getSecurejoinQr(chatId));
      intent.putExtra(Intent.EXTRA_TEXT, inviteURL);
      startActivity(Intent.createChooser(intent, getString(R.string.chat_share_with_title)));
    } catch (Exception e) {
      Log.e(TAG, "failed to share invite URL", e);
    }
  }

  /**
   * Renders the BMChat-cleaned QR SVG into a 1024x1024 PNG, hands it to the
   * system share sheet via the existing FileProvider so any messenger / mail
   * app can attach it natively. Avoids the previous regression where the
   * share menu had no image option at all.
   */
  public void shareQrImage() {
    final Activity activity = getActivity();
    if (activity == null) return;
    final String inviteURL = Util.rewriteInviteLink(dcContext.getSecurejoinQr(chatId));
    try {
      String rawSvg = dcContext.getSecurejoinQrSvg(chatId);
      if (rawSvg == null || rawSvg.isEmpty()) {
        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
        return;
      }
      String cleanedSvg = fixSVG(rawSvg);
      Bitmap bmp = renderSvgToBitmap(cleanedSvg, 1024);
      if (bmp == null) {
        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
        return;
      }
      File dir = new File(activity.getCacheDir(), "qr-share");
      //noinspection ResultOfMethodCallIgnored
      dir.mkdirs();
      File out = new File(dir, "bmchat-invite-qr.png");
      try (FileOutputStream fos = new FileOutputStream(out)) {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
      } finally {
        bmp.recycle();
      }
      Uri uri = FileProvider.getUriForFile(
          activity,
          BuildConfig.APPLICATION_ID + ".fileprovider",
          out);
      Intent share = new Intent(Intent.ACTION_SEND);
      share.setType("image/png");
      share.putExtra(Intent.EXTRA_STREAM, uri);
      if (inviteURL != null) share.putExtra(Intent.EXTRA_TEXT, inviteURL);
      share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(share, getString(R.string.chat_share_with_title)));
    } catch (Throwable t) {
      Log.e(TAG, "failed to share QR image", t);
      Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
    }
  }

  private static @androidx.annotation.Nullable Bitmap renderSvgToBitmap(String svgText, int sizePx) {
    try {
      SVG svg = SVG.getFromString(svgText);
      svg.setDocumentWidth(sizePx);
      svg.setDocumentHeight(sizePx);
      Picture pic = svg.renderToPicture(sizePx, sizePx);
      Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bmp);
      c.drawColor(Color.WHITE);
      PictureDrawable pd = new PictureDrawable(pic);
      pd.setBounds(0, 0, sizePx, sizePx);
      pd.draw(c);
      return bmp;
    } catch (Throwable t) {
      Log.w(TAG, "renderSvgToBitmap", t);
      return null;
    }
  }

  public void copyQrData() {
    String inviteURL = Util.rewriteInviteLink(dcContext.getSecurejoinQr(chatId));
    Util.writeTextToClipboard(getActivity(), inviteURL);
    Toast.makeText(getActivity(), getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
        .show();
  }

  public void withdrawQr() {
    Activity activity = getActivity();
    String message;
    if (chatId == 0) {
      message = activity.getString(R.string.withdraw_verifycontact_explain);
    } else {
      DcChat chat = dcContext.getChat(chatId);
      if (chat.getType() == DcChat.DC_CHAT_TYPE_GROUP) {
        message = activity.getString(R.string.withdraw_verifygroup_explain, chat.getName());
      } else {
        message = activity.getString(R.string.withdraw_joinbroadcast_explain, chat.getName());
      }
    }
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    builder.setTitle(R.string.withdraw_qr_code);
    builder.setMessage(message);
    builder.setPositiveButton(
        R.string.reset,
        (dialog, which) -> {
          DcContext dcContext = DcHelper.getContext(activity);
          dcContext.setConfigFromQr(dcContext.getSecurejoinQr(chatId));
          activity.finish();
        });
    builder.setNegativeButton(R.string.cancel, null);
    AlertDialog dialog = builder.show();
    Util.redPositiveButton(dialog);
  }

  public void showInviteLinkDialog() {
    View view = View.inflate(getActivity(), R.layout.dialog_share_invite_link, null);
    String inviteURL = Util.rewriteInviteLink(dcContext.getSecurejoinQr(chatId));
    ((TextView) view.findViewById(R.id.invite_link)).setText(inviteURL);
    AlertDialog dialog = new AlertDialog.Builder(getActivity())
        .setView(view)
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.menu_copy_to_clipboard, (d, b) -> copyQrData())
        .setPositiveButton(R.string.menu_share, (d, b) -> shareInviteURL())
        .create();
    // Append a third button "Отправить на e-mail". The dialog has only one
    // POSITIVE / NEUTRAL / NEGATIVE slot so we attach an explicit button via
    // the dialog content instead.
    Button mailBtn = view.findViewById(R.id.bmchat_share_via_email);
    if (mailBtn != null) {
      mailBtn.setOnClickListener(v -> {
        dialog.dismiss();
        showSendByEmailDialog();
      });
    }
    Button qrImageBtn = view.findViewById(R.id.bmchat_share_qr_image);
    if (qrImageBtn != null) {
      qrImageBtn.setOnClickListener(v -> {
        dialog.dismiss();
        shareQrImage();
      });
    }
    dialog.show();
  }

  /**
   * Opens an input dialog asking for a recipient e-mail address. On confirm,
   * BMChat creates (or reuses) a 1:1 chat with that contact and sends the
   * invite link as a regular email through the user's own SMTP server. The
   * receiving side, if running BMChat, will pick the message up via IMAP and
   * the embedded link will trigger the SecureJoin handshake.
   */
  public void showSendByEmailDialog() {
    final Activity activity = getActivity();
    if (activity == null) return;
    final String inviteURL = Util.rewriteInviteLink(dcContext.getSecurejoinQr(chatId));
    if (inviteURL == null || inviteURL.isEmpty()) {
      Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
      return;
    }
    final EditText input = new EditText(activity);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    input.setHint(R.string.email_address);
    int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
    input.setPadding(padding, padding / 2, padding, padding / 2);

    new AlertDialog.Builder(activity)
        .setTitle(R.string.menu_share)
        .setMessage(activity.getString(R.string.bmchat_send_invite_by_email_explain))
        .setView(input)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.menu_send, (d, w) -> {
          String email = input.getText().toString().trim();
          if (!isValidEmail(email)) {
            Toast.makeText(activity, R.string.bad_email_address, Toast.LENGTH_SHORT).show();
            return;
          }
          sendInviteByEmail(email, inviteURL);
        })
        .show();
  }

  private static boolean isValidEmail(String s) {
    return s != null && Patterns.EMAIL_ADDRESS.matcher(s).matches();
  }

  /**
   * Creates (or reuses) a 1:1 chat with `email` and sends a single message
   * with the invite link in the body. The whole transport runs over the
   * user's own IMAP/SMTP credentials — no third-party server is involved.
   */
  private void sendInviteByEmail(String email, String inviteURL) {
    final Activity activity = getActivity();
    try {
      int contactId = dcContext.lookupContactIdByAddr(email);
      if (contactId == 0) {
        contactId = dcContext.createContact(null, email);
      }
      if (contactId == 0) {
        Toast.makeText(activity, R.string.bad_email_address, Toast.LENGTH_SHORT).show();
        return;
      }
      int targetChatId = dcContext.createChatByContactId(contactId);
      if (targetChatId == 0) {
        Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
        return;
      }
      String body = activity.getString(R.string.bmchat_invite_email_body, inviteURL);
      DcMsg msg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      msg.setText(body);
      dcContext.sendMsg(targetChatId, msg);
      Toast.makeText(activity, R.string.bmchat_send_invite_by_email_sent, Toast.LENGTH_LONG).show();
    } catch (Throwable t) {
      Log.e(TAG, "failed to send invite by email", t);
      Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (!DcHelper.isNetworkConnected(getContext())) {
      Toast.makeText(
              getActivity(), R.string.qrshow_join_contact_no_connection_toast, Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    dcEventCenter.removeObservers(this);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    if (event.getId() == DcContext.DC_EVENT_SECUREJOIN_INVITER_PROGRESS) {
      DcContext dcContext = DcHelper.getContext(getActivity());
      int contact_id = event.getData1Int();
      long progress = event.getData2Int();
      String msg = null;
      if (progress == 300) {
        msg =
            String.format(
                getString(R.string.qrshow_x_joining),
                dcContext.getContact(contact_id).getDisplayName());
        numJoiners++;
      } else if (progress == 600) {
        msg =
            String.format(
                getString(R.string.qrshow_x_verified),
                dcContext.getContact(contact_id).getDisplayName());
      } else if (progress == 800) {
        msg =
            String.format(
                getString(R.string.qrshow_x_has_joined_group),
                dcContext.getContact(contact_id).getDisplayName());
      }

      if (msg != null) {
        Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show();
      }

      if (progress == 1000) {
        numJoiners--;
        if (numJoiners <= 0) {
          if (getActivity() != null) getActivity().finish();
        }
      }
    }
  }
}
