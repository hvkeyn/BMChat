package org.thoughtcrime.securesms.bots;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Convert one Telegram {@code Update} object into one or more BMChat
 * messages and post them into the configured destination chat.
 *
 * <p>Supported Telegram payloads:
 * <ul>
 *     <li>{@code message} / {@code channel_post} (and their {@code edited_*}
 *         variants),
 *     <li>{@code text} (with full {@code entities[]} formatting),
 *     <li>{@code photo[]} (largest size is downloaded),
 *     <li>{@code video}, {@code video_note}, {@code animation},
 *     <li>{@code audio}, {@code voice},
 *     <li>{@code document}, {@code sticker} (incl. animated/webp),
 *     <li>{@code caption} + {@code caption_entities},
 *     <li>{@code reply_markup.inline_keyboard} (rendered as a trailing
 *         bullet list of buttons + URLs),
 *     <li>{@code forward_from} / {@code forward_from_chat} / {@code forward_sender_name}
 *         (rendered as a "Переслано от: …" header),
 *     <li>{@code reply_to_message} (rendered as a "↪ В ответ на: …" header),
 *     <li>{@code poll} (question + options),
 *     <li>{@code location}, {@code venue}, {@code contact}.
 * </ul>
 */
public final class TelegramMessageDispatcher {

  private static final String TAG = "TgDispatcher";

  /** Cap on attachment size to avoid pulling 2 GB monsters over mobile data. */
  /**
   * Hard cap on direct download size. Anything bigger goes through
   * the BMChat proxy fallback instead — Telegram's cloud Bot API
   * caps {@code getFile} at ~20 MB so the previous 100 MB ceiling
   * was effectively unreachable. We keep a generous client-side
   * cap so that smaller files still try the direct path (cheaper
   * for both the user and the VPS), but raise it just enough that
   * the cap doesn't kick in before Telegram's own 20 MB does.
   */
  private static final long MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L; // 50 MB

  private final Context appContext;
  private final TelegramApi api;
  private final BotConfig bot;
  private final DcContext dcContext;

  public TelegramMessageDispatcher(@NonNull Context appContext,
                                   @NonNull TelegramApi api,
                                   @NonNull BotConfig bot,
                                   @NonNull DcContext dcContext) {
    this.appContext = appContext.getApplicationContext();
    this.api = api;
    this.bot = bot;
    this.dcContext = dcContext;
  }

  /**
   * Process one Telegram update. Returns the highest {@code update_id}
   * seen (so the caller can advance the offset), or 0 when there was
   * nothing to do.
   */
  /**
   * Per-batch counters returned by {@link #handleUpdateBatchDetailed}
   * so the poll manager can show "Получено: 5 · в очередь: 5" style
   * diagnostic toasts.
   */
  public static final class BatchStats {
    public long newestUpdateId;
    public int queued;
    public int published;
    public int dropped;
  }

  @WorkerThread
  public long handleUpdate(JSONObject update) {
    if (update == null) return 0L;
    long updateId = update.optLong("update_id", 0L);
    JSONObject msg = firstMessageLike(update);
    if (msg == null) return updateId;

    if (isBotControlCommand(msg)) {
      // /start, /help, /settings, etc. — these are commands that the bot
      // user types AT the bot to trigger something on the Telegram side
      // (e.g. wake the bot up, request a menu). They are not content the
      // user wanted broadcast back into BMChat. Telegram itself never
      // re-posts them anywhere either.
      return updateId;
    }

    if (bot.manualReview) {
      try {
        new PendingPostStore(appContext).enqueue(bot, update);
      } catch (Throwable t) {
        Log.w(TAG, "PendingPostStore.enqueue failed for bot " + bot.id, t);
      }
      return updateId;
    }

    publishParsed(update, msg);
    return updateId;
  }

  /**
   * Process a whole {@code getUpdates} response in one go. Items that
   * share a {@code media_group_id} (Telegram's transport for
   * forward/copy of a photo+video album, up to 10 items) are handled as
   * a single logical post — caption from the first item is preserved,
   * every part is published in order to the target chats, and in manual
   * review mode the queue stores them as one entry the user can drop
   * or publish atomically.
   *
   * @return the largest seen {@code update_id}, suitable for {@code
   *         BotConfig.withProgress(...)}; 0 when {@code updates} was
   *         empty or null.
   */
  @WorkerThread
  public long handleUpdateBatch(@Nullable JSONArray updates) {
    return handleUpdateBatchDetailed(updates).newestUpdateId;
  }

  @WorkerThread
  public BatchStats handleUpdateBatchDetailed(@Nullable JSONArray updates) {
    BatchStats stats = new BatchStats();
    if (updates == null || updates.length() == 0) return stats;

    int i = 0;
    while (i < updates.length()) {
      JSONObject upd = updates.optJSONObject(i);
      if (upd == null) { i++; continue; }
      long id = upd.optLong("update_id", 0L);
      if (id > stats.newestUpdateId) stats.newestUpdateId = id;

      JSONObject msg = firstMessageLike(upd);
      String groupId = msg != null ? msg.optString("media_group_id", "") : "";

      if (msg == null || groupId.isEmpty()) {
        // single-shot path also covers the "non-message update" case
        // (msg == null) so handleSingle can record it as dropped and
        // log a Device Talk note. Without this branch such updates
        // would silently fall through to the album collector below
        // and never be accounted for.
        handleSingle(upd, msg, stats);
        i++;
        continue;
      }

      // Collect every adjacent update that carries the same
      // media_group_id. Stops at the first non-matching item.
      List<JSONObject> album = new ArrayList<>();
      album.add(upd);
      int j = i + 1;
      while (j < updates.length()) {
        JSONObject next = updates.optJSONObject(j);
        if (next == null) { j++; continue; }
        JSONObject nextMsg = firstMessageLike(next);
        if (nextMsg == null) break;
        if (!groupId.equals(nextMsg.optString("media_group_id", ""))) break;
        long nextId = next.optLong("update_id", 0L);
        if (nextId > stats.newestUpdateId) stats.newestUpdateId = nextId;
        album.add(next);
        j++;
      }
      i = j;

      handleAlbum(album, groupId, stats);
    }

    return stats;
  }

  private void handleSingle(@NonNull JSONObject update,
                            @Nullable JSONObject msg,
                            @NonNull BatchStats stats) {
    if (msg == null) {
      // Telegram batches frequently contain non-message updates like
      // message_reaction, message_reaction_count, chat_member,
      // chat_join_request, business_connection, business_message,
      // poll_answer, my_chat_member, callback_query, etc. They are
      // valid updates but we cannot turn them into a chat post —
      // count them as dropped silently. We used to surface every
      // skip into Device Talk for debugging; that turned the device
      // chat into a spam stream once the bot integration was stable
      // (screenshot 2 from May 10), so we only log to logcat now.
      stats.dropped++;
      Log.d(TAG, "skipped non-message update " + describeUpdateKind(update));
      return;
    }
    if (isBotControlCommand(msg)) {
      // /start and friends are intentionally not republished —
      // logging this into Device Talk produced one device message
      // per Telegram /start command and confused the user.
      stats.dropped++;
      Log.d(TAG, "skipped bot command update_id=" + update.optLong("update_id", 0L));
      return;
    }
    // Always log the post into the per-bot queue first. This is the
    // single source of truth the user sees in the planner UI: every
    // received post shows up there with a status badge ("⏸ ждёт",
    // "✓ опубликовано", "⏰ HH:MM"). When manualReview is off we
    // immediately publish and flip the status to "опубликовано" so the
    // entry is essentially a history item the user can also drop or
    // re-schedule.
    EnqueueResult er = safeEnqueue(update);
    Log.i(TAG, "handleSingle bot=" + bot.id
        + " enqueued=" + (er.entry != null)
        + (er.entry != null ? (" entryId=" + er.entry.entryId) : "")
        + " manualReview=" + bot.manualReview);
    // Only surface a Device Talk diagnostic when something actually
    // broke during the enqueue. Successful enqueues used to drop a
    // "queue +1 (пост)" message for every received update, which the
    // user reported as noise on the May 10 screenshots.
    if (er.entry == null) {
      diagnoseEnqueue(null, er.error, /*album=*/0);
    }

    if (er.entry != null) stats.queued++;
    if (bot.manualReview) {
      return;
    }
    publishParsed(update, msg);
    stats.published++;
    if (er.entry != null) {
      try { new PendingPostStore(appContext).markPublished(bot, er.entry.entryId); }
      catch (Throwable ignored) {}
    }
  }

  private void handleAlbum(@NonNull List<JSONObject> parts, @NonNull String groupId,
                           @NonNull BatchStats stats) {
    if (parts.isEmpty()) return;

    JSONObject firstMsg = firstMessageLike(parts.get(0));
    if (firstMsg != null && isBotControlCommand(firstMsg)) {
      stats.dropped += parts.size();
      return;
    }

    EnqueueResult er = safeEnqueueAlbum(parts, groupId);
    Log.i(TAG, "handleAlbum bot=" + bot.id + " size=" + parts.size()
        + " groupId=" + groupId
        + " enqueued=" + (er.entry != null)
        + (er.entry != null ? (" entryId=" + er.entry.entryId) : "")
        + " manualReview=" + bot.manualReview);
    // Same rationale as handleSingle: success path stays out of
    // Device Talk; only failures still reach the user.
    if (er.entry == null) {
      diagnoseEnqueue(null, er.error, /*album=*/parts.size());
    }

    if (er.entry != null) stats.queued += parts.size();
    if (bot.manualReview) {
      return;
    }
    publishAlbum(parts);
    stats.published += parts.size();
    if (er.entry != null) {
      try { new PendingPostStore(appContext).markPublished(bot, er.entry.entryId); }
      catch (Throwable ignored) {}
    }
  }

  /**
   * Holder for {@link #safeEnqueue}/{@link #safeEnqueueAlbum} results.
   * Either {@link #entry} is non-null (and {@link #error} is empty) or
   * {@link #error} carries a fully-described failure reason — never the
   * literal string "null", which is what produced the confusing
   * "ENQUEUE FAIL error: null" device-message lines on screenshots from
   * 2.49.25.
   */
  private static final class EnqueueResult {
    @Nullable final PendingPostStore.Entry entry;
    @Nullable final String error;
    EnqueueResult(@Nullable PendingPostStore.Entry e, @Nullable String err) {
      this.entry = e; this.error = err;
    }
  }

  /**
   * Catch every conceivable failure path inside the queue store and
   * surface a precise, non-null error string. We deliberately call the
   * store via reflection-free direct API but wrap individual operations
   * in their own try blocks so we can tell the user exactly which step
   * failed (instantiate / serialise / write).
   */
  private @NonNull EnqueueResult safeEnqueue(@NonNull JSONObject update) {
    PendingPostStore store;
    try {
      store = new PendingPostStore(appContext);
    } catch (Throwable t) {
      return new EnqueueResult(null, "store init: " + describeThrowable(t));
    }
    try {
      PendingPostStore.Entry e = store.enqueue(bot, update);
      if (e == null) {
        return new EnqueueResult(null, "enqueue returned null entry");
      }
      return new EnqueueResult(e, null);
    } catch (Throwable t) {
      Log.w(TAG, "PendingPostStore.enqueue failed for bot " + bot.id, t);
      return new EnqueueResult(null, "enqueue: " + describeThrowable(t));
    }
  }

  private @NonNull EnqueueResult safeEnqueueAlbum(@NonNull List<JSONObject> parts,
                                                  @NonNull String groupId) {
    if (parts.isEmpty()) {
      return new EnqueueResult(null, "album: empty parts list");
    }
    PendingPostStore store;
    try {
      store = new PendingPostStore(appContext);
    } catch (Throwable t) {
      return new EnqueueResult(null, "store init: " + describeThrowable(t));
    }
    try {
      PendingPostStore.Entry e = store.enqueueAlbum(bot, parts, groupId);
      if (e == null) {
        return new EnqueueResult(null, "enqueueAlbum returned null entry");
      }
      return new EnqueueResult(e, null);
    } catch (Throwable t) {
      Log.w(TAG, "PendingPostStore.enqueueAlbum failed for bot " + bot.id, t);
      return new EnqueueResult(null, "enqueueAlbum: " + describeThrowable(t));
    }
  }

  /**
   * Per-bot diagnostic trail for the queue: every enqueue (success or
   * failure) is mirrored as a Delta-Chat device message into the
   * built-in "Device messages" chat. This gives the user an
   * always-visible audit log they can scroll without enabling logcat,
   * which is invaluable when the planner UI claims "queue is empty"
   * yet a poll just reported "received: 5". The line records bot id,
   * the resolved entry id, queue size right after the call, and the
   * caught exception message if any.
   *
   * <p>Cheap — one line per accepted Telegram update — and easy to
   * disable later by gating on a {@code BotConfig.debugDiagnostics}
   * flag if it ever becomes noisy.
   */
  /** First top-level key of the update other than {@code update_id}. */
  private static String describeUpdateKind(@NonNull JSONObject update) {
    java.util.Iterator<String> it = update.keys();
    while (it.hasNext()) {
      String k = it.next();
      if (!"update_id".equals(k)) return k;
    }
    return "(unknown)";
  }

  private void diagnoseEnqueue(@Nullable PendingPostStore.Entry entry,
                               @Nullable String error,
                               int albumSize) {
    try {
      String botLabel = bot.telegramName != null && !bot.telegramName.isEmpty()
          ? bot.telegramName
          : (bot.telegramUsername != null ? "@" + bot.telegramUsername : bot.id);
      // Total counter is best-effort — failures here must not hide the
      // real enqueue failure reason from the user.
      int total = -1;
      try { total = new PendingPostStore(appContext).count(bot); } catch (Throwable ignored) {}
      String tag = albumSize > 1 ? ("альбом · " + albumSize) : "пост";
      String line;
      if (entry != null) {
        String preview = entry.preview != null && !entry.preview.isEmpty()
            ? entry.preview : "(no preview)";
        line = "🤖 [" + botLabel + "] queue +1 (" + tag + ")"
            + (total >= 0 ? " · всего: " + total : "")
            + "\nentryId: " + entry.entryId
            + "\npreview: " + preview;
      } else {
        // Always print the literal fallback strings, never just the
        // raw variable, so the user never sees the bare word "null".
        String safeError = (error == null || error.isEmpty() || "null".equals(error))
            ? "(no exception captured — entry was null)"
            : error;
        line = "🤖 [" + botLabel + "] queue ENQUEUE FAIL (" + tag + ")"
            + "\nbot.id: " + bot.id
            + "\ntoken set: " + (bot.token != null && !bot.token.isEmpty())
            + "\nreason: " + safeError;
      }
      DcMsg dm = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
      dm.setText(line);
      dcContext.addDeviceMsg("bmchat-bot-diag-" + System.nanoTime(), dm);
    } catch (Throwable t) {
      Log.w(TAG, "diagnoseEnqueue failed", t);
    }
  }

  /**
   * Format an exception in a way that survives {@code null} messages,
   * anonymous-class names, and missing stack frames — anything that
   * could otherwise collapse into a literal "null" string and confuse
   * the user (which is exactly the regression that produced the
   * mysterious "ENQUEUE FAIL error: null" lines on screenshots from
   * 2.49.25).
   */
  private static @NonNull String describeThrowable(@Nullable Throwable t) {
    if (t == null) return "(no exception captured)";
    String cls = t.getClass().getName();
    if (cls == null || cls.isEmpty()) cls = "Throwable";
    String msg = t.getMessage();
    String topFrame = "";
    StackTraceElement[] stack = t.getStackTrace();
    if (stack != null && stack.length > 0 && stack[0] != null) {
      StackTraceElement f = stack[0];
      topFrame = " @ " + f.getClassName() + "." + f.getMethodName()
          + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
    }
    return cls + (msg == null || msg.isEmpty() ? " (no message)" : ": " + msg) + topFrame;
  }

  /**
   * Publish a previously enqueued update on demand (e.g. user tapped
   * "Опубликовать" in the planner UI). Uses the same path as the
   * automatic flow, including target-chat resolution and edit detection.
   */
  @WorkerThread
  public void publishUpdate(@NonNull JSONObject update) {
    JSONObject msg = firstMessageLike(update);
    if (msg == null) return;
    publishParsed(update, msg);
  }

  /**
   * Same as {@link #publishUpdate(JSONObject)} but for a Telegram media
   * group: each part is sent individually so the result mirrors the
   * Telegram album as closely as Delta-Chat will allow (BMChat has no
   * native "album" type yet — the pieces just appear back-to-back).
   *
   * <p>The caption (if any) is kept on the first part only, matching
   * how Telegram itself attaches captions to media-group items.
   */
  @WorkerThread
  public void publishAlbum(@NonNull List<JSONObject> parts) {
    List<Integer> targets = resolveTargetChats();
    if (targets.isEmpty()) {
      Log.w(TAG, "bot " + bot.id + " is not a member of any chat — album dropped");
      return;
    }
    // 1) Try to publish the whole media-group as a SINGLE composite
    //    image (Telegram-style album bubble) — one DcMsg per chat with
    //    the caption attached. Falls through to the legacy "one DcMsg
    //    per part" path on any failure (mixed media types, decode
    //    error, no targets etc.).
    if (publishAlbumAsCollage(parts, targets)) return;
    publishAlbumPartByPart(parts, targets);
  }

  /**
   * Composite album path. Returns {@code true} when a single composite
   * DcMsg was successfully posted into every target chat, {@code false}
   * to signal the caller should fall back to the per-part loop.
   *
   * <p>Triggers only when:
   * <ul>
   *   <li>every part has a {@code photo[]} attachment (mixed albums,
   *       e.g. photo + video, are rare and look better as separate
   *       messages anyway);</li>
   *   <li>the resulting collage stays under {@link
   *       AlbumComposer#CANVAS_WIDTH}-driven JPEG bounds;</li>
   *   <li>at least 2 photos actually downloaded.</li>
   * </ul>
   *
   * <p>Caption + entities + forward-attribution come from the first
   * part, exactly mirroring how Telegram itself renders captions
   * (only the first item carries the body in a media-group bubble).
   */
  private boolean publishAlbumAsCollage(@NonNull List<JSONObject> parts,
                                        @NonNull List<Integer> targets) {
    if (parts.size() < 2) return false;

    JSONObject firstMsg = firstMessageLike(parts.get(0));
    if (firstMsg == null) return false;

    java.util.List<File> photoFiles = new java.util.ArrayList<>();
    int totalParts = 0;
    for (JSONObject upd : parts) {
      JSONObject msg = firstMessageLike(upd);
      if (msg == null) continue;
      AttachmentInfo a = pickAttachment(msg);
      if (a == null || a.viewType != DcMsg.DC_MSG_IMAGE) {
        // Non-photo or no-media part → composite path can't represent
        // this faithfully. Bail out and let the legacy path handle it.
        return false;
      }
      File f = downloadAttachment(a);
      if (f == null) continue;
      photoFiles.add(f);
      totalParts++;
    }
    if (photoFiles.size() < 2) return false;

    File outDir = new File(appContext.getCacheDir(), "bots/" + bot.id);
    if (!outDir.exists() && !outDir.mkdirs()) return false;
    File composite = new File(outDir,
        "album_" + System.currentTimeMillis() + ".jpg");
    File built = AlbumComposer.compose(photoFiles, composite);
    if (built == null) return false;

    AttachmentInfo collageInfo = new AttachmentInfo(
        /*fileId=*/ "",
        /*fileName=*/ "album_" + photoFiles.size() + ".jpg",
        /*mimeType=*/ "image/jpeg",
        /*viewType=*/ DcMsg.DC_MSG_IMAGE,
        /*width=*/ AlbumComposer.CANVAS_WIDTH,
        /*height=*/ 0,
        /*duration=*/ 0,
        /*size=*/ built.length(),
        /*type=*/ "album");

    String caption = buildBodyText(firstMsg, /*forAlbum=*/ true,
        /*albumSize=*/ totalParts);

    for (int chatId : targets) {
      try {
        sendAttachment(chatId, built, collageInfo, caption);
      } catch (Throwable t) {
        Log.w(TAG, "publishAlbumAsCollage send failed for chat " + chatId, t);
      }
    }
    return true;
  }

  /**
   * Legacy fallback: send each part as its own DcMsg, with caption on
   * the first part only and forward-attribution on every part. Kept
   * for non-photo media-groups (animations, videos, mixed bags) where
   * the collage compositor can't preserve fidelity.
   */
  private void publishAlbumPartByPart(@NonNull List<JSONObject> parts,
                                      @NonNull List<Integer> targets) {
    boolean firstSeen = false;
    for (JSONObject upd : parts) {
      JSONObject msg = firstMessageLike(upd);
      if (msg == null) continue;
      boolean edit = isEdit(upd);
      JSONObject toPost = msg;
      if (firstSeen) {
        // Strip caption/text on subsequent items so we don't repeat the
        // same caption N times when Telegram clients echo it on every
        // part of the group.
        try {
          JSONObject clone = new JSONObject(msg.toString());
          clone.remove("caption");
          clone.remove("caption_entities");
          clone.remove("text");
          clone.remove("entities");
          toPost = clone;
        } catch (Throwable ignored) {}
      }
      for (int chatId : targets) {
        try {
          postMessage(toPost, chatId, edit);
        } catch (Throwable t) {
          Log.w(TAG, "postMessage (album) failed for chat " + chatId, t);
        }
      }
      firstSeen = true;
    }
  }

  /**
   * Re-render the body text + forward attribution + inline keyboard for
   * a single Telegram message — same logic as {@link #postMessage} but
   * exposed as a string so the collage path can attach it as a caption
   * to the composed bitmap.
   */
  private String buildBodyText(@NonNull JSONObject m, boolean forAlbum, int albumSize) {
    String forwardAttribution = describeForwardAttribution(m);
    String text = m.optString("text", "");
    JSONArray entities = m.optJSONArray("entities");
    String caption = m.optString("caption", "");
    JSONArray captionEntities = m.optJSONArray("caption_entities");
    String body;
    if (!TextUtils.isEmpty(text)) {
      body = TelegramFormatter.render(text, entities);
    } else if (!TextUtils.isEmpty(caption)) {
      body = TelegramFormatter.render(caption, captionEntities);
    } else {
      body = "";
    }
    if (forwardAttribution != null) {
      body = body.isEmpty() ? forwardAttribution : forwardAttribution + "\n\n" + body;
    }
    String keyboard = TelegramFormatter.renderInlineKeyboard(m.optJSONObject("reply_markup"));
    if (!keyboard.isEmpty()) {
      body = body.isEmpty() ? keyboard : body + "\n" + keyboard;
    }
    // We deliberately do NOT add a "(альбом · N)" tail here — the
    // composite bitmap already shows N photos; an extra label would
    // just be noise.
    return body;
  }

  private void publishParsed(@NonNull JSONObject update, @NonNull JSONObject msg) {
    List<Integer> targets = resolveTargetChats();
    if (targets.isEmpty()) {
      Log.w(TAG, "bot " + bot.id + " is not a member of any chat — nothing to post into");
      return;
    }

    boolean edit = isEdit(update);
    for (int chatId : targets) {
      try {
        postMessage(msg, chatId, edit);
      } catch (Throwable t) {
        Log.w(TAG, "postMessage failed for chat " + chatId, t);
      }
    }
  }

  /**
   * True when the Telegram message is a slash-command directed at the bot
   * itself. Most commonly {@code /start}, but any "/cmd" or "/cmd@botname"
   * message — typically sent by users to a bot to trigger its own logic —
   * should not be republished into BMChat chats.
   *
   * <p>Heuristic: the message is plain text, the very first character is
   * a slash, and Telegram tagged a {@code bot_command} entity on offset 0.
   * Falls back to a stricter "starts with /start" guarantee if entities
   * are missing.
   */
  private static boolean isBotControlCommand(JSONObject m) {
    String text = m.optString("text", "");
    if (text.isEmpty() || text.charAt(0) != '/') return false;
    JSONArray entities = m.optJSONArray("entities");
    if (entities != null) {
      for (int i = 0; i < entities.length(); i++) {
        JSONObject e = entities.optJSONObject(i);
        if (e == null) continue;
        if (!"bot_command".equals(e.optString("type"))) continue;
        if (e.optInt("offset", -1) == 0) return true;
      }
    }
    String head = text.split("[\\s@]", 2)[0];
    return head.equalsIgnoreCase("/start");
  }

  /**
   * Build the list of {@code DcChat} ids the dispatcher should mirror
   * Telegram updates into.
   *
   * <p>Two sources, in priority order:
   *
   * <ol>
   *   <li>The implicit "home" 1:1 chat with the bot's pseudo-contact —
   *       always included so the user can read raw bot output even if
   *       the bot has not been attached anywhere else.</li>
   *   <li>{@link BotConfig#attachedChatIds} — every multi-user chat the
   *       user explicitly attached the bot to via "Add bot to chat…".
   *       These are tracked locally; the bot is <em>not</em> a real SMTP
   *       member of these chats, the dispatcher just posts on the user's
   *       behalf with a "🤖 BotName" header.</li>
   * </ol>
   *
   * <p>{@link BotConfig#targetDcChatId} (the field carried over from
   * pre-2.49.16 builds) is treated as a legacy hint for the home chat
   * when {@code botContactId} is missing.
   *
   * <p>Result is deduplicated (insertion order preserved), guaranteed
   * non-empty if at least one chat is reachable, and skips chats that
   * have been deleted / archived in a way the user cannot send into.
   */
  private List<Integer> resolveTargetChats() {
    LinkedHashSet<Integer> out = new LinkedHashSet<>();
    int botContactId = bot.botContactId;

    if (botContactId > 0) {
      try {
        int oneOnOne = dcContext.getChatIdByContactId(botContactId);
        if (oneOnOne > 0 && isUsableChat(oneOnOne)) out.add(oneOnOne);
      } catch (Throwable t) {
        Log.w(TAG, "getChatIdByContactId failed", t);
      }
    } else if (bot.targetDcChatId > 0) {
      // Pre-2.49.16 bot — its targetDcChatId is the auto-created channel.
      if (isUsableChat(bot.targetDcChatId)) out.add(bot.targetDcChatId);
    }

    for (Integer chatId : bot.attachedChatIds) {
      if (chatId == null || chatId <= 0) continue;
      if (isUsableChat(chatId)) out.add(chatId);
    }

    return new ArrayList<>(out);
  }

  private boolean isUsableChat(int chatId) {
    try {
      DcChat chat = dcContext.getChat(chatId);
      if (chat == null) return false;
      if (chat.getId() <= 0) return false;
      if (chat.isContactRequest()) return false;
      if (!chat.canSend()) return false;
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  // ---------------------------------------------------------------------
  //  payload extraction
  // ---------------------------------------------------------------------

  static @Nullable JSONObject firstMessageLike(JSONObject update) {
    // Order matters: prefer "fresh" variants over edits because the
    // dispatcher already handles edits as best-effort (no in-place
    // overwrite of the previously posted DcMsg).
    String[] keys = new String[] {
        "message", "channel_post",
        "business_message", "edited_business_message",
        "edited_message", "edited_channel_post"
    };
    for (String k : keys) {
      JSONObject v = update.optJSONObject(k);
      if (v != null) return v;
    }
    return null;
  }

  private static boolean isEdit(JSONObject update) {
    return update.has("edited_message") || update.has("edited_channel_post");
  }

  // ---------------------------------------------------------------------
  //  posting
  // ---------------------------------------------------------------------

  private void postMessage(JSONObject m, int chatId, boolean isEdit) {
    // We deliberately drop the previous "🤖 BotName · sender · date"
    // header and the "↪ In reply to" sub-header. The user's mental
    // model is "post like a Telegram channel post": just the text and
    // the media, no decorative metadata. The chat already shows the
    // bot's identity via its name, and Delta-Chat shows the timestamp
    // in the message bubble itself.
    //
    // Forward-from is the one piece of original-Telegram metadata we
    // keep, in a single inline line at the top of the body, mirroring
    // the way Telegram itself renders forwards. Without this a repost
    // from someone else's channel would lose its provenance and look
    // like our bot wrote it.

    String forwardAttribution = describeForwardAttribution(m);

    // Body: text or caption with entities applied.
    String text = m.optString("text", "");
    JSONArray entities = m.optJSONArray("entities");
    String caption = m.optString("caption", "");
    JSONArray captionEntities = m.optJSONArray("caption_entities");

    String body;
    if (!TextUtils.isEmpty(text)) {
      body = TelegramFormatter.render(text, entities);
    } else if (!TextUtils.isEmpty(caption)) {
      body = TelegramFormatter.render(caption, captionEntities);
    } else {
      body = "";
    }

    if (forwardAttribution != null) {
      body = body.isEmpty() ? forwardAttribution : forwardAttribution + "\n\n" + body;
    }

    // Inline keyboard (Telegram-side action buttons → bullet list of links)
    String keyboard = TelegramFormatter.renderInlineKeyboard(m.optJSONObject("reply_markup"));

    // Special payloads (no media files but have semantic content)
    String special = describeSpecialPayload(m);
    if (special != null) {
      if (!body.isEmpty()) body = body + "\n\n" + special;
      else body = special;
    }

    String fullText = body;
    if (!keyboard.isEmpty()) {
      fullText = fullText.isEmpty() ? keyboard : fullText + "\n" + keyboard;
    }

    AttachmentInfo attachment = pickAttachment(m);
    if (attachment != null) {
      File downloaded = downloadAttachment(attachment);
      if (downloaded != null) {
        sendAttachment(chatId, downloaded, attachment, fullText);
        return;
      }
      // Direct download failed — usually because Telegram's cloud
      // Bot API caps getFile at ~20 MB and the forwarded video /
      // document is bigger than that.
      //
      // For videos / animations we have a much better fallback than
      // a bare markdown link: download just the (always-small)
      // thumbnail, publish it as an inline poster image, and stamp
      // an invisible BotMediaMarker into the caption so the
      // recipient's ConversationItem overlays a play button on the
      // poster and routes taps to the in-app progressive player.
      // Looks and feels like Telegram's native video bubble.
      if (isStreamableVideo(attachment)
          && trySendInlineVideoPoster(chatId, attachment, fullText)) {
        return;
      }

      String proxyLine = buildProxyMediaLine(attachment);
      if (!TextUtils.isEmpty(proxyLine)) {
        fullText = fullText.isEmpty()
            ? proxyLine
            : fullText + "\n\n" + proxyLine;
      } else {
        fullText += "\n\n[медиа " + attachment.type + " не удалось скачать]";
      }
    }

    sendText(chatId, fullText);
  }

  private static boolean isStreamableVideo(@NonNull AttachmentInfo a) {
    if (a.viewType == DcMsg.DC_MSG_VIDEO || a.viewType == DcMsg.DC_MSG_GIF) return true;
    // Channel forwards often arrive as a plain document with video/* MIME.
    return a.mimeType != null && a.mimeType.startsWith("video/");
  }

  /**
   * Build & send the "video poster" message: a thumbnail image with
   * a {@link BotMediaMarker} pointing at the streamable proxy URL.
   * Returns {@code false} when there is no thumbnail or no signed
   * proxy URL, so the caller can fall back to a plain markdown link.
   */
  private boolean trySendInlineVideoPoster(int chatId,
                                           @NonNull AttachmentInfo a,
                                           @NonNull String fullText) {
    String token = bot != null ? bot.token : null;
    if (TextUtils.isEmpty(token)) return false;
    if (TextUtils.isEmpty(a.thumbnailFileId)) return false;

    File thumb = downloadFileById(a.thumbnailFileId, "thumb.jpg");
    if (thumb == null) return false;

    String url = TelegramProxy.buildUrl(token, a.fileId, a.mimeType, a.fileName);
    if (TextUtils.isEmpty(url)) return false;

    String marker = BotMediaMarker.build(url, a.size, a.duration, a.mimeType);
    String body = fullText.isEmpty() ? marker : fullText + "\n\n" + marker;

    // Keep the friendly poster caption: text first (as the user
    // wrote it), the play-marker afterwards (zero-width, so it's
    // invisible). When the recipient's BMChat is too old to
    // understand the marker, they see exactly the same chat layout
    // they used to — text + a (visually weightless) trailing token.
    AttachmentInfo poster = new AttachmentInfo(
        /* fileId */    a.thumbnailFileId,
        /* fileName */  "poster.jpg",
        /* mimeType */  "image/jpeg",
        /* viewType */  DcMsg.DC_MSG_IMAGE,
        a.width, a.height,
        /* duration */  0,
        thumb.length(),
        "video-poster");
    sendAttachment(chatId, thumb, poster, body);
    return true;
  }

  /** Download an arbitrary Telegram file by file_id without the
   *  20 MB safety cap — only call this for files known to be small
   *  (thumbnails, voice notes). */
  private @Nullable File downloadFileById(@NonNull String fileId, @NonNull String name) {
    try {
      String filePath = api.getFilePath(fileId);
      if (filePath == null) return null;
      File dir = new File(appContext.getCacheDir(), "bots/" + bot.id);
      if (!dir.exists() && !dir.mkdirs()) return null;
      File dest = new File(dir, System.currentTimeMillis() + "_" + sanitizeFilename(name));
      return api.downloadFile(filePath, fileId, dest);
    } catch (Throwable t) {
      Log.w(TAG, "downloadFileById failed", t);
      return null;
    }
  }

  /**
   * Build a single line of Markdown describing one media attachment
   * that could not be downloaded directly. Looks like:
   *
   * <pre>📹 [Видео · cat.mp4 · 24,3 МБ](http://5.187.4.132/tgmedia/…)</pre>
   *
   * The proxy URL is AES-GCM-signed by {@link TelegramProxy}, so the
   * bot token is never exposed in plaintext even though the link
   * itself travels in cleartext over the chat. Recipients tapping
   * the link open the file in their browser (cloud Bot API content
   * up to ~20 MB streams as-is; anything bigger gets a clear error
   * page from the proxy until we bring up a local Bot API server).
   *
   * <p>Returns {@code null} when there is no token to sign with —
   * we then fall back to the legacy "не удалось скачать" string.
   */
  private @Nullable String buildProxyMediaLine(@NonNull AttachmentInfo a) {
    String token = bot != null ? bot.token : null;
    if (TextUtils.isEmpty(token) || TextUtils.isEmpty(a.fileId)) return null;
    String url = TelegramProxy.buildUrl(token, a.fileId, a.mimeType, a.fileName);
    if (TextUtils.isEmpty(url)) return null;

    String icon = mediaIcon(a.viewType, a.type);
    String label = humanLabel(a);
    String safeLabel = label.replace('[', '(').replace(']', ')');
    return icon + " [" + safeLabel + "](" + url + ")";
  }

  private static String mediaIcon(int viewType, @Nullable String fallbackType) {
    switch (viewType) {
      case DcMsg.DC_MSG_VIDEO:   return "🎬";
      case DcMsg.DC_MSG_AUDIO:   return "🎵";
      case DcMsg.DC_MSG_VOICE:   return "🎤";
      case DcMsg.DC_MSG_GIF:     return "🌀";
      case DcMsg.DC_MSG_STICKER: return "🌟";
      case DcMsg.DC_MSG_IMAGE:   return "🖼";
      case DcMsg.DC_MSG_FILE:    return "📎";
      default: return fallbackType != null && fallbackType.startsWith("voice") ? "🎤" : "📎";
    }
  }

  private static String humanLabel(@NonNull AttachmentInfo a) {
    StringBuilder sb = new StringBuilder();
    switch (a.viewType) {
      case DcMsg.DC_MSG_VIDEO:   sb.append("Видео"); break;
      case DcMsg.DC_MSG_AUDIO:   sb.append("Аудио"); break;
      case DcMsg.DC_MSG_VOICE:   sb.append("Голосовое"); break;
      case DcMsg.DC_MSG_GIF:     sb.append("GIF"); break;
      case DcMsg.DC_MSG_STICKER: sb.append("Стикер"); break;
      case DcMsg.DC_MSG_IMAGE:   sb.append("Фото"); break;
      case DcMsg.DC_MSG_FILE:    sb.append("Файл"); break;
      default: sb.append(a.type != null ? a.type : "Медиа");
    }
    if (!TextUtils.isEmpty(a.fileName)) sb.append(" · ").append(a.fileName);
    if (a.duration > 0)               sb.append(" · ").append(formatDuration(a.duration));
    if (a.size > 0)                   sb.append(" · ").append(formatSize(a.size));
    return sb.toString();
  }

  private static String formatDuration(int seconds) {
    if (seconds < 60) return seconds + "с";
    int m = seconds / 60;
    int s = seconds % 60;
    if (m < 60) return m + ":" + (s < 10 ? "0" : "") + s;
    int h = m / 60;
    m = m % 60;
    return h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " Б";
    double kb = bytes / 1024.0;
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.1f КБ", kb);
    double mb = kb / 1024.0;
    if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f МБ", mb);
    double gb = mb / 1024.0;
    return String.format(java.util.Locale.ROOT, "%.2f ГБ", gb);
  }

  // ---------------------------------------------------------------------
  //  attachment selection / download
  // ---------------------------------------------------------------------

  private static final class AttachmentInfo {
    final String fileId;
    final String fileName;
    final String mimeType;
    final int viewType; // DcMsg.DC_MSG_*
    final int width;
    final int height;
    final int duration;
    final long size;
    final String type; // human-readable, for fallback messages
    /** Telegram-side {@code thumbnail.file_id} for the media. Small
     *  enough (≤200 KB in practice) to always be downloadable via
     *  cloud Bot API, even when the original video exceeds the
     *  20 MB getFile cap. Used to publish an inline poster image
     *  when the real video can't be fetched. */
    final @Nullable String thumbnailFileId;

    AttachmentInfo(String fileId, String fileName, String mimeType, int viewType,
                   int width, int height, int duration, long size, String type) {
      this(fileId, fileName, mimeType, viewType, width, height, duration, size, type, null);
    }

    AttachmentInfo(String fileId, String fileName, String mimeType, int viewType,
                   int width, int height, int duration, long size, String type,
                   @Nullable String thumbnailFileId) {
      this.fileId = fileId;
      this.fileName = fileName;
      this.mimeType = mimeType;
      this.viewType = viewType;
      this.width = width;
      this.height = height;
      this.duration = duration;
      this.size = size;
      this.type = type;
      this.thumbnailFileId = thumbnailFileId;
    }
  }

  /** Resolve the Telegram-side thumbnail file_id for the given media
   *  JSON object, preferring the explicit {@code thumbnail.file_id}
   *  field (Bot API ≥ 5.0). Returns {@code null} when the media has
   *  no thumbnail (e.g. for audio). */
  private static @Nullable String pickThumbFileId(@Nullable JSONObject media) {
    if (media == null) return null;
    JSONObject t = media.optJSONObject("thumbnail");
    if (t == null) t = media.optJSONObject("thumb"); // legacy alias
    if (t == null) return null;
    String id = t.optString("file_id", "");
    return id.isEmpty() ? null : id;
  }

  /** Pick the most "valuable" media attachment from the Telegram message,
   *  preferring concrete media (photo/video) over wrappers. */
  private static @Nullable AttachmentInfo pickAttachment(JSONObject m) {
    // Photo: array of sizes, pick the largest.
    JSONArray photo = m.optJSONArray("photo");
    if (photo != null && photo.length() > 0) {
      JSONObject biggest = null;
      int biggestArea = 0;
      for (int i = 0; i < photo.length(); i++) {
        JSONObject p = photo.optJSONObject(i);
        if (p == null) continue;
        int w = p.optInt("width", 0);
        int h = p.optInt("height", 0);
        if (w * h >= biggestArea) {
          biggestArea = w * h;
          biggest = p;
        }
      }
      if (biggest != null) {
        return new AttachmentInfo(
            biggest.optString("file_id"), null, "image/jpeg",
            DcMsg.DC_MSG_IMAGE,
            biggest.optInt("width"), biggest.optInt("height"),
            0, biggest.optLong("file_size"), "photo");
      }
    }

    JSONObject animation = m.optJSONObject("animation");
    if (animation != null) {
      String mime = animation.optString("mime_type", "video/mp4");
      int viewType = mime.contains("gif") ? DcMsg.DC_MSG_GIF : DcMsg.DC_MSG_VIDEO;
      return new AttachmentInfo(
          animation.optString("file_id"),
          animation.optString("file_name", "animation.mp4"),
          mime,
          viewType,
          animation.optInt("width"), animation.optInt("height"),
          animation.optInt("duration"), animation.optLong("file_size"),
          "animation",
          pickThumbFileId(animation));
    }

    JSONObject video = m.optJSONObject("video");
    if (video != null) {
      return new AttachmentInfo(
          video.optString("file_id"),
          video.optString("file_name", "video.mp4"),
          video.optString("mime_type", "video/mp4"),
          DcMsg.DC_MSG_VIDEO,
          video.optInt("width"), video.optInt("height"),
          video.optInt("duration"), video.optLong("file_size"),
          "video",
          pickThumbFileId(video));
    }

    JSONObject videoNote = m.optJSONObject("video_note");
    if (videoNote != null) {
      return new AttachmentInfo(
          videoNote.optString("file_id"),
          "video_note.mp4", "video/mp4",
          DcMsg.DC_MSG_VIDEO,
          videoNote.optInt("length"), videoNote.optInt("length"),
          videoNote.optInt("duration"), videoNote.optLong("file_size"),
          "video note",
          pickThumbFileId(videoNote));
    }

    JSONObject voice = m.optJSONObject("voice");
    if (voice != null) {
      return new AttachmentInfo(
          voice.optString("file_id"),
          "voice.ogg",
          voice.optString("mime_type", "audio/ogg"),
          DcMsg.DC_MSG_VOICE,
          0, 0, voice.optInt("duration"), voice.optLong("file_size"),
          "voice");
    }

    JSONObject audio = m.optJSONObject("audio");
    if (audio != null) {
      String name = audio.optString("file_name", "");
      if (TextUtils.isEmpty(name)) {
        String performer = audio.optString("performer", "");
        String title = audio.optString("title", "audio");
        name = (performer.isEmpty() ? "" : performer + " - ") + title + ".mp3";
      }
      return new AttachmentInfo(
          audio.optString("file_id"), name,
          audio.optString("mime_type", "audio/mpeg"),
          DcMsg.DC_MSG_AUDIO,
          0, 0, audio.optInt("duration"), audio.optLong("file_size"),
          "audio");
    }

    JSONObject sticker = m.optJSONObject("sticker");
    if (sticker != null) {
      boolean isAnimatedTgs = sticker.optBoolean("is_animated", false);
      boolean isVideoWebm = sticker.optBoolean("is_video", false);
      String mime;
      String ext;
      int viewType;
      if (isVideoWebm) {
        mime = "video/webm"; ext = "webm"; viewType = DcMsg.DC_MSG_VIDEO;
      } else if (isAnimatedTgs) {
        // .tgs is a gzipped Lottie file; BMChat can't render it, ship as a
        // regular file attachment so the user at least sees the text.
        mime = "application/x-tgsticker"; ext = "tgs"; viewType = DcMsg.DC_MSG_FILE;
      } else {
        mime = "image/webp"; ext = "webp"; viewType = DcMsg.DC_MSG_STICKER;
      }
      return new AttachmentInfo(
          sticker.optString("file_id"),
          "sticker." + ext,
          mime, viewType,
          sticker.optInt("width"), sticker.optInt("height"),
          0, sticker.optLong("file_size"), "sticker");
    }

    JSONObject document = m.optJSONObject("document");
    if (document != null) {
      String mime = document.optString("mime_type", "application/octet-stream");
      String fileName = document.optString("file_name", "document");
      // BMChat 2.49.90: Telegram channel forwards frequently wrap MP4s as
      // generic documents. Treat video/* and audio/* documents like native
      // video/audio so download + poster fallback + proxy streaming work.
      int viewType = DcMsg.DC_MSG_FILE;
      if (mime.startsWith("video/")) {
        viewType = DcMsg.DC_MSG_VIDEO;
      } else if (mime.startsWith("audio/")) {
        viewType = DcMsg.DC_MSG_AUDIO;
      }
      return new AttachmentInfo(
          document.optString("file_id"),
          fileName,
          mime,
          viewType,
          0, 0, 0, document.optLong("file_size"), "document",
          pickThumbFileId(document));
    }

    return null;
  }

  private @Nullable File downloadAttachment(AttachmentInfo a) {
    if (a == null) return null;
    if (a.size > MAX_ATTACHMENT_BYTES) {
      Log.w(TAG, "attachment too large (" + a.size + " bytes); skipping");
      return null;
    }
    try {
      String filePath = api.getFilePath(a.fileId);
      if (filePath == null) return null;
      File dir = new File(appContext.getCacheDir(), "bots/" + bot.id);
      if (!dir.exists() && !dir.mkdirs()) return null;
      String name = a.fileName != null && !a.fileName.isEmpty()
          ? sanitizeFilename(a.fileName)
          : sanitizeFilename(filePath.substring(filePath.lastIndexOf('/') + 1));
      File dest = new File(dir, System.currentTimeMillis() + "_" + name);
      return api.downloadFile(filePath, a.fileId, dest);
    } catch (Throwable t) {
      Log.w(TAG, "downloadAttachment failed", t);
      return null;
    }
  }

  private static String sanitizeFilename(String n) {
    if (n == null) return "file";
    String safe = n.replaceAll("[^A-Za-z0-9_.\\-+()\\[\\]]", "_");
    if (safe.length() > 80) safe = safe.substring(0, 80);
    if (safe.isEmpty()) safe = "file";
    return safe;
  }

  // ---------------------------------------------------------------------
  //  send via DcContext
  // ---------------------------------------------------------------------

  private void sendText(int chatId, String text) {
    if (TextUtils.isEmpty(text)) return;
    try {
      dcContext.sendTextMsg(chatId, text);
    } catch (Throwable t) {
      Log.w(TAG, "sendTextMsg failed", t);
    }
  }

  private void sendAttachment(int chatId, File file, AttachmentInfo a, String text) {
    DcMsg msg = null;
    try {
      msg = new DcMsg(dcContext, a.viewType);
      msg.setFileAndDeduplicate(file.getAbsolutePath(), a.fileName, a.mimeType);
      if (a.width > 0 && a.height > 0) msg.setDimension(a.width, a.height);
      if (a.duration > 0) msg.setDuration(a.duration * 1000);
      if (!TextUtils.isEmpty(text)) msg.setText(text);
      dcContext.sendMsg(chatId, msg);
    } catch (Throwable t) {
      Log.w(TAG, "sendMsg failed", t);
    }
  }

  // ---------------------------------------------------------------------
  //  Telegram-specific descriptors (legacy helpers kept private; the
  //  current postMessage path no longer prepends a header but tests and
  //  potential future "verbose" mode may still want them).
  // ---------------------------------------------------------------------

  /**
   * Compact provenance label for Telegram forwards. Returned as a single
   * line ready to drop into the body of the BMChat message — never a
   * standalone "header". Telegram's own clients render forwards as a
   * small "Forwarded from <X>" line above the content, so this matches
   * user expectations when a bot relays a message it received.
   *
   * <p>Returns {@code null} when the message is not a forward (so the
   * post stays as clean as a non-forwarded one).
   */
  static @Nullable String describeForwardAttribution(JSONObject m) {
    if (m == null) return null;

    // Bot API ≥ 7.0 exposes a richer "forward_origin" sub-object;
    // fall back to the legacy fields when the bot library is older.
    JSONObject origin = m.optJSONObject("forward_origin");
    if (origin != null) {
      String type = origin.optString("type", "");
      switch (type) {
        case "user": {
          JSONObject sender = origin.optJSONObject("sender_user");
          String name = sender != null ? joinName(sender) : "";
          if (!name.isEmpty()) return "↪ От " + name;
          break;
        }
        case "hidden_user": {
          String name = origin.optString("sender_user_name", "");
          if (!name.isEmpty()) return "↪ От " + name;
          break;
        }
        case "chat": {
          JSONObject chat = origin.optJSONObject("sender_chat");
          String title = chat != null ? chat.optString("title", "") : "";
          String sig = origin.optString("author_signature", "");
          if (!title.isEmpty()) {
            return sig.isEmpty() ? "↪ Из «" + title + "»"
                                 : "↪ Из «" + title + "» (" + sig + ")";
          }
          break;
        }
        case "channel": {
          JSONObject chat = origin.optJSONObject("chat");
          String title = chat != null ? chat.optString("title", "") : "";
          String sig = origin.optString("author_signature", "");
          if (!title.isEmpty()) {
            return sig.isEmpty() ? "↪ Из канала «" + title + "»"
                                 : "↪ Из канала «" + title + "» (" + sig + ")";
          }
          break;
        }
        default: break;
      }
    }

    // Legacy fields.
    JSONObject ffc = m.optJSONObject("forward_from_chat");
    if (ffc != null) {
      String title = ffc.optString("title", "");
      String type = ffc.optString("type", "");
      String sig = m.optString("forward_signature", "");
      if (!title.isEmpty()) {
        String prefix = "channel".equals(type) ? "↪ Из канала «" : "↪ Из «";
        return sig.isEmpty() ? prefix + title + "»"
                             : prefix + title + "» (" + sig + ")";
      }
    }
    JSONObject ffu = m.optJSONObject("forward_from");
    if (ffu != null) {
      String name = joinName(ffu);
      if (!name.isEmpty()) return "↪ От " + name;
    }
    String fwdName = m.optString("forward_sender_name", "");
    if (!fwdName.isEmpty()) return "↪ От " + fwdName;

    return null;
  }

  private static @NonNull String joinName(@NonNull JSONObject user) {
    String fname = user.optString("first_name", "");
    String lname = user.optString("last_name", "");
    String full = (fname + " " + lname).trim();
    if (!full.isEmpty()) return full;
    String uname = user.optString("username", "");
    return uname.isEmpty() ? "" : "@" + uname;
  }

  @SuppressWarnings("unused")
  private static @Nullable String describeForward(JSONObject m) {
    return describeForwardAttribution(m);
  }

  @SuppressWarnings("unused")
  private static @Nullable String describeReply(JSONObject reply) {
    if (reply == null) return null;
    String t = reply.optString("text", "");
    if (t.isEmpty()) t = reply.optString("caption", "");
    if (t.isEmpty()) {
      // attachment-only quote
      if (reply.has("photo")) t = "[фото]";
      else if (reply.has("video")) t = "[видео]";
      else if (reply.has("document")) t = "[файл]";
      else if (reply.has("voice")) t = "[голос]";
      else t = "[медиа]";
    }
    if (t.length() > 160) t = t.substring(0, 160) + "…";
    return "↪ В ответ: " + t;
  }

  /** Render Telegram payloads that don't have a file attachment but
   *  carry meaningful structured data (polls, locations, contacts). */
  private static @Nullable String describeSpecialPayload(JSONObject m) {
    JSONObject poll = m.optJSONObject("poll");
    if (poll != null) {
      StringBuilder sb = new StringBuilder("📊 Опрос: ").append(poll.optString("question", ""));
      JSONArray opts = poll.optJSONArray("options");
      if (opts != null) {
        for (int i = 0; i < opts.length(); i++) {
          JSONObject opt = opts.optJSONObject(i);
          if (opt == null) continue;
          sb.append("\n• ").append(opt.optString("text", ""));
          int cnt = opt.optInt("voter_count", -1);
          if (cnt >= 0) sb.append(" (").append(cnt).append(")");
        }
      }
      return sb.toString();
    }
    JSONObject loc = m.optJSONObject("location");
    if (loc != null) {
      double lat = loc.optDouble("latitude", 0.0);
      double lon = loc.optDouble("longitude", 0.0);
      return "📍 Локация: " + lat + ", " + lon
          + "\nhttps://maps.google.com/?q=" + lat + "," + lon;
    }
    JSONObject venue = m.optJSONObject("venue");
    if (venue != null) {
      JSONObject vloc = venue.optJSONObject("location");
      double lat = vloc != null ? vloc.optDouble("latitude", 0.0) : 0.0;
      double lon = vloc != null ? vloc.optDouble("longitude", 0.0) : 0.0;
      return "📍 " + venue.optString("title", "")
          + (venue.optString("address", "").isEmpty()
              ? "" : "\n" + venue.optString("address", ""))
          + "\nhttps://maps.google.com/?q=" + lat + "," + lon;
    }
    JSONObject contact = m.optJSONObject("contact");
    if (contact != null) {
      String fname = contact.optString("first_name", "");
      String lname = contact.optString("last_name", "");
      String phone = contact.optString("phone_number", "");
      return "👤 Контакт: " + (fname + " " + lname).trim() + "\n☎ " + phone;
    }
    return null;
  }
}
