package org.thoughtcrime.securesms.components;

import android.view.View;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.thoughtcrime.securesms.util.Util;

/**
 * Byte counts emitted by the core while an IMAP partial fetch is in flight.
 * The line is {@code bmchat-xfer <msgId> <got> <total>}.
 */
public final class BMChatTransferProgress {

  public static final class Sample {
    public final long got;
    public final long total;
    public final long atMs;
    public final long firstAtMs;
    public final long firstGot;

    Sample(long got, long total, long atMs, long firstAtMs, long firstGot) {
      this.got = got;
      this.total = total;
      this.atMs = atMs;
      this.firstAtMs = firstAtMs;
      this.firstGot = firstGot;
    }

    /** Seconds until the file is expected to finish, or -1 when the rate is unknown. */
    public long secondsLeft() {
      if (total <= 0 || got >= total || got <= firstGot) return -1;
      long elapsedMs = Math.max(1, atMs - firstAtMs);
      long gained = got - firstGot;
      double bytesPerMs = gained / (double) elapsedMs;
      if (bytesPerMs <= 0) return -1;
      return (long) Math.ceil((total - got) / bytesPerMs / 1000.0);
    }
  }

  private static final class Slot {
    int msgId;
    Runnable refresh;
  }

  private static final Map<Integer, Sample> samples = new ConcurrentHashMap<>();
  private static final Map<Integer, Long> startedAt = new ConcurrentHashMap<>();
  private static final Map<View, Slot> slots = new WeakHashMap<>();

  private BMChatTransferProgress() {}

  public static void noteLine(@Nullable String line) {
    if (line == null || !line.startsWith("bmchat-xfer ")) return;
    String[] parts = line.split(" ");
    if (parts.length < 4) return;
    try {
      int msgId = Integer.parseInt(parts[1]);
      long got = Long.parseLong(parts[2]);
      long total = Long.parseLong(parts[3]);
      long now = System.currentTimeMillis();
      Sample prev = samples.get(msgId);
      long firstAt = prev == null ? now : prev.firstAtMs;
      long firstGot = prev == null ? got : prev.firstGot;
      samples.put(msgId, new Sample(got, total, now, firstAt, firstGot));
      Util.runOnMain(() -> refresh(msgId));
    } catch (NumberFormatException ignored) {
    }
  }

  public static @Nullable Sample get(int msgId) {
    return samples.get(msgId);
  }

  /** Elapsed milliseconds since this bubble first showed a busy transfer. */
  public static long elapsedMs(int msgId) {
    long now = System.currentTimeMillis();
    Long started = startedAt.putIfAbsent(msgId, now);
    return now - (started == null ? now : started);
  }

  public static void clear(int msgId) {
    samples.remove(msgId);
    startedAt.remove(msgId);
  }

  public static void watch(View row, int msgId, Runnable refresh) {
    Slot slot = new Slot();
    slot.msgId = msgId;
    slot.refresh = refresh;
    synchronized (slots) {
      slots.put(row, slot);
    }
  }

  private static void refresh(int msgId) {
    List<Runnable> pending = new ArrayList<>();
    synchronized (slots) {
      Iterator<Map.Entry<View, Slot>> it = slots.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<View, Slot> entry = it.next();
        View row = entry.getKey();
        if (row == null || !row.isAttachedToWindow()) {
          it.remove();
          continue;
        }
        if (entry.getValue().msgId == msgId) {
          pending.add(entry.getValue().refresh);
        }
      }
    }
    for (Runnable refresh : pending) {
      refresh.run();
    }
  }
}
