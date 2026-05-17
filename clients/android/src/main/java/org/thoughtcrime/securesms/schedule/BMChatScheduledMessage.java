package org.thoughtcrime.securesms.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * BMChat 2.49.84 (Phase 4B): a single message that the user asked to send later.
 *
 * <p>Persisted as JSON inside {@link BMChatScheduledMessageStore}. The store keeps the file in
 * {@code filesDir/scheduled/queue.json} so that scheduled messages survive process death and
 * device reboots — {@link BMChatScheduledMessageWorker} re-arms the corresponding WorkManager job
 * after a boot via {@link BMChatScheduledMessageScheduler#rescheduleAll}.
 */
public final class BMChatScheduledMessage {

  /** Stable identifier; doubles as the unique WorkManager tag. */
  public final @NonNull String id;

  /** DC chat where the message will be sent. */
  public final int chatId;

  /** Epoch milliseconds — when the message must be sent out. */
  public final long scheduledAtMs;

  /** Plain-text body. May be empty when only an attachment is attached. */
  public final @NonNull String body;

  /**
   * One of {@code DcMsg.DC_MSG_TEXT}/{@code DC_MSG_IMAGE}/{@code DC_MSG_VIDEO}/etc. We persist a
   * raw int rather than an enum to avoid coupling to DC internals.
   */
  public final int viewType;

  /** Absolute path of the cached attachment, or {@code null} for text-only messages. */
  public final @Nullable String attachmentPath;

  /** Original filename for the attachment (visible in chat). */
  public final @Nullable String originalFileName;

  /** Mime type — used purely for logging / debugging. */
  public final @Nullable String mimeType;

  /** DC msg id of the quoted message, or 0 if none. */
  public final int quoteMsgId;

  /** Epoch milliseconds — when the entry was created. */
  public final long createdAtMs;

  public BMChatScheduledMessage(
      @NonNull String id,
      int chatId,
      long scheduledAtMs,
      @NonNull String body,
      int viewType,
      @Nullable String attachmentPath,
      @Nullable String originalFileName,
      @Nullable String mimeType,
      int quoteMsgId,
      long createdAtMs) {
    this.id = id;
    this.chatId = chatId;
    this.scheduledAtMs = scheduledAtMs;
    this.body = body;
    this.viewType = viewType;
    this.attachmentPath = attachmentPath;
    this.originalFileName = originalFileName;
    this.mimeType = mimeType;
    this.quoteMsgId = quoteMsgId;
    this.createdAtMs = createdAtMs;
  }

  @NonNull
  JSONObject toJson() throws JSONException {
    JSONObject obj = new JSONObject();
    obj.put("id", id);
    obj.put("chatId", chatId);
    obj.put("scheduledAtMs", scheduledAtMs);
    obj.put("body", body);
    obj.put("viewType", viewType);
    if (attachmentPath != null) obj.put("attachmentPath", attachmentPath);
    if (originalFileName != null) obj.put("originalFileName", originalFileName);
    if (mimeType != null) obj.put("mimeType", mimeType);
    obj.put("quoteMsgId", quoteMsgId);
    obj.put("createdAtMs", createdAtMs);
    return obj;
  }

  @NonNull
  static BMChatScheduledMessage fromJson(@NonNull JSONObject obj) throws JSONException {
    return new BMChatScheduledMessage(
        obj.getString("id"),
        obj.getInt("chatId"),
        obj.getLong("scheduledAtMs"),
        obj.optString("body", ""),
        obj.getInt("viewType"),
        obj.has("attachmentPath") ? obj.optString("attachmentPath", null) : null,
        obj.has("originalFileName") ? obj.optString("originalFileName", null) : null,
        obj.has("mimeType") ? obj.optString("mimeType", null) : null,
        obj.optInt("quoteMsgId", 0),
        obj.optLong("createdAtMs", System.currentTimeMillis()));
  }
}
