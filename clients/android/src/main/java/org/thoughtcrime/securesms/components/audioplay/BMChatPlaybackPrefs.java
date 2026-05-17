package org.thoughtcrime.securesms.components.audioplay;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

/**
 * Persistent settings shared by every BMChat audio playback surface
 * (AudioView in chat, BMChatMiniPlayerView, AllMediaActivity). Today
 * it stores just the playback speed, but it keeps a single place to
 * grow other knobs like "auto-play next" or "default-loud".
 */
public final class BMChatPlaybackPrefs {

  private static final String PREFS_NAME = "bmchat_playback";
  private static final String KEY_SPEED = "speed";
  private static final float DEFAULT_SPEED = 1.0f;

  /** Three Telegram-style steps; cycle on each tap of the speed badge. */
  private static final float[] CYCLE = {1.0f, 1.5f, 2.0f};

  private BMChatPlaybackPrefs() {}

  private static SharedPreferences prefs(@NonNull Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static float getSpeed(@NonNull Context context) {
    float v = prefs(context).getFloat(KEY_SPEED, DEFAULT_SPEED);
    if (v <= 0f || v > 4f) return DEFAULT_SPEED;
    return v;
  }

  public static void setSpeed(@NonNull Context context, float speed) {
    if (speed <= 0f) speed = DEFAULT_SPEED;
    prefs(context).edit().putFloat(KEY_SPEED, speed).apply();
  }

  /**
   * Step to the next speed in the cycle (1 -> 1.5 -> 2 -> 1) and
   * persist it. Returns the new value.
   */
  public static float cycleSpeed(@NonNull Context context) {
    float current = getSpeed(context);
    int idx = 0;
    float bestDiff = Float.MAX_VALUE;
    for (int i = 0; i < CYCLE.length; i++) {
      float d = Math.abs(CYCLE[i] - current);
      if (d < bestDiff) {
        bestDiff = d;
        idx = i;
      }
    }
    float next = CYCLE[(idx + 1) % CYCLE.length];
    setSpeed(context, next);
    return next;
  }

  /** Human-friendly label such as "1×", "1.5×", "2×". */
  public static String formatSpeed(float speed) {
    if (Math.abs(speed - Math.round(speed)) < 0.05f) {
      return ((int) Math.round(speed)) + "×";
    }
    return String.format(java.util.Locale.US, "%.1f×", speed);
  }
}
