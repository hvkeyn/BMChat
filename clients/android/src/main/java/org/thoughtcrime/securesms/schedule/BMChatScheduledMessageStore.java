package org.thoughtcrime.securesms.schedule;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * BMChat 2.49.84 (Phase 4B): persistent queue of scheduled messages.
 *
 * <p>Stored as a flat JSON array in {@code filesDir/scheduled/queue.json}; the file is read/written
 * fully each time, which is fine because we don't expect anyone to keep thousands of pending
 * scheduled messages.
 */
public final class BMChatScheduledMessageStore {

  private static final String TAG = "BMChatScheduleStore";
  private static final String DIR_NAME = "scheduled";
  private static final String FILE_NAME = "queue.json";

  private final @NonNull File dir;
  private final @NonNull File queueFile;

  public BMChatScheduledMessageStore(@NonNull Context context) {
    this.dir = new File(context.getFilesDir(), DIR_NAME);
    if (!dir.exists() && !dir.mkdirs()) {
      Log.w(TAG, "Failed to create scheduled directory at " + dir.getAbsolutePath());
    }
    this.queueFile = new File(dir, FILE_NAME);
  }

  /** Root directory where attachments and the queue file live. */
  public @NonNull File getDir() {
    return dir;
  }

  /** Append a new scheduled message. Returns true on success. */
  public synchronized boolean add(@NonNull BMChatScheduledMessage message) {
    List<BMChatScheduledMessage> all = loadAll();
    all.add(message);
    return persist(all);
  }

  /** Remove a scheduled message by id, also unlinking the cached attachment. */
  public synchronized boolean remove(@NonNull String id) {
    List<BMChatScheduledMessage> all = loadAll();
    boolean changed = false;
    Iterator<BMChatScheduledMessage> it = all.iterator();
    while (it.hasNext()) {
      BMChatScheduledMessage entry = it.next();
      if (entry.id.equals(id)) {
        it.remove();
        deleteAttachment(entry);
        changed = true;
        break;
      }
    }
    return changed && persist(all);
  }

  /** Lookup a specific entry by id. */
  public synchronized @Nullable BMChatScheduledMessage findById(@NonNull String id) {
    for (BMChatScheduledMessage entry : loadAll()) {
      if (entry.id.equals(id)) return entry;
    }
    return null;
  }

  /** All scheduled messages, in insertion order. */
  public synchronized @NonNull List<BMChatScheduledMessage> getAll() {
    return loadAll();
  }

  /** Subset filtered by chat. */
  public synchronized @NonNull List<BMChatScheduledMessage> findByChat(int chatId) {
    List<BMChatScheduledMessage> result = new ArrayList<>();
    for (BMChatScheduledMessage entry : loadAll()) {
      if (entry.chatId == chatId) result.add(entry);
    }
    return result;
  }

  /** Persist a non-default queue. Used by reschedule logic after the worker fired. */
  public synchronized boolean replaceAll(@NonNull List<BMChatScheduledMessage> messages) {
    return persist(messages);
  }

  private @NonNull List<BMChatScheduledMessage> loadAll() {
    if (!queueFile.exists() || queueFile.length() == 0) {
      return new ArrayList<>();
    }
    StringBuilder builder = new StringBuilder();
    try (java.io.FileInputStream fis = new java.io.FileInputStream(queueFile);
        java.io.InputStreamReader reader = new java.io.InputStreamReader(fis, StandardCharsets.UTF_8)) {
      char[] buffer = new char[4096];
      int read;
      while ((read = reader.read(buffer)) != -1) builder.append(buffer, 0, read);
    } catch (IOException e) {
      Log.w(TAG, "Failed to read queue file", e);
      return new ArrayList<>();
    }
    try {
      JSONArray array = new JSONArray(builder.toString());
      List<BMChatScheduledMessage> result = new ArrayList<>(array.length());
      for (int i = 0; i < array.length(); i++) {
        result.add(BMChatScheduledMessage.fromJson(array.getJSONObject(i)));
      }
      return result;
    } catch (JSONException e) {
      Log.w(TAG, "Queue file is corrupted, dropping", e);
      // noinspection ResultOfMethodCallIgnored
      queueFile.delete();
      return new ArrayList<>();
    }
  }

  private boolean persist(@NonNull List<BMChatScheduledMessage> all) {
    Collections.sort(all, (a, b) -> Long.compare(a.scheduledAtMs, b.scheduledAtMs));
    JSONArray array = new JSONArray();
    try {
      for (BMChatScheduledMessage entry : all) array.put(entry.toJson());
    } catch (JSONException e) {
      Log.w(TAG, "Failed to serialise queue", e);
      return false;
    }
    try (FileOutputStream fos = new FileOutputStream(queueFile);
        OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
      writer.write(array.toString());
      return true;
    } catch (IOException e) {
      Log.w(TAG, "Failed to write queue file", e);
      return false;
    }
  }

  private void deleteAttachment(@NonNull BMChatScheduledMessage entry) {
    if (entry.attachmentPath == null) return;
    File f = new File(entry.attachmentPath);
    if (f.exists() && f.getAbsolutePath().startsWith(dir.getAbsolutePath())) {
      // Only delete attachments that we copied into our scheduled/ subdir; never touch arbitrary
      // user files.
      // noinspection ResultOfMethodCallIgnored
      f.delete();
    }
  }
}
