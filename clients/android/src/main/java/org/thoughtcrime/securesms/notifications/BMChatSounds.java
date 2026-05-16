package org.thoughtcrime.securesms.notifications;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.util.Prefs;

/**
 * In-process companion to {@link NotificationCenter}: plays the short
 * BMChat-branded "sent" tick the user asked for in the May 13, 2026
 * feedback ("звуковые информирования о приходе сообщения нового").
 *
 * <p>Incoming-message sound is already handled by Android itself via
 * the {@code NotificationChannel} we register (see
 * {@code NotificationCenter#getNotificationChannel}). That covers the
 * Telegram-like behaviour when the app is in the background. The gap
 * was a foreground confirmation cue: when the user is already inside
 * a chat, Android suppresses the channel sound so they hear nothing
 * after pressing Send. This class fills that gap with a single
 * SoundPool instance shared across the activity lifecycle.
 *
 * <p>Respects {@link Prefs#isInChatNotifications(Context)} and
 * {@link Prefs#isNotificationsEnabled(Context)} — if either is
 * disabled, {@link #playSent(Context)} becomes a no-op so we never
 * fight a user who explicitly muted the app.
 */
public final class BMChatSounds {

  private static final String TAG = "BMChatSounds";

  /** Volume of the send-confirmation tick relative to the stream max. */
  private static final float SENT_VOLUME = 0.35f;

  private static volatile @Nullable SoundPool pool;
  private static volatile int sentSoundId = 0;

  private BMChatSounds() {}

  /**
   * Plays the short "сообщение ушло" tick. Lazily initialises the
   * shared SoundPool on first use; the system ringtone for
   * notifications is loaded as the asset so we don't ship our own
   * audio file (matches Telegram, which reuses the device alert
   * tone for this purpose).
   *
   * <p>Safe to call from any thread. Failures are swallowed and
   * logged — never let an audio error abort the send pipeline.
   */
  public static void playSent(@NonNull Context context) {
    try {
      // Master switch: pref_enable_notifications gates both visual
      // and audio cues, matching what the settings screen claims.
      android.content.SharedPreferences sp1 =
          androidx.preference.PreferenceManager
              .getDefaultSharedPreferences(context);
      if (!sp1.getBoolean("pref_enable_notifications", true)) return;
      if (!Prefs.isInChatNotifications(context))               return;
      AudioManager am =
          (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (am == null) return;
      // Honour silent / Do Not Disturb so we don't blast a tick at
      // 3 AM when the user explicitly silenced their phone.
      if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;

      SoundPool sp = ensurePool();
      if (sentSoundId == 0) {
        Uri uri = android.media.RingtoneManager.getDefaultUri(
            android.media.RingtoneManager.TYPE_NOTIFICATION);
        if (uri != null) {
          try {
            sentSoundId = sp.load(context.getApplicationContext(),
                                  uri.hashCode(), 1);
            // SoundPool.load() with a Uri isn't supported on all
            // OEMs; fall through to the asset-less mode if it
            // returned zero so we don't keep retrying.
          } catch (Throwable ignored) {
            sentSoundId = -1;
          }
        }
      }
      if (sentSoundId > 0) {
        sp.play(sentSoundId, SENT_VOLUME, SENT_VOLUME, 0, 0, 1f);
      } else {
        // Cheap fallback: a UI feedback tick produced by the
        // system itself. It's quieter and shorter than the
        // notification tone but always available.
        am.playSoundEffect(AudioManager.FX_KEY_CLICK, SENT_VOLUME);
      }
    } catch (Throwable t) {
      Log.w(TAG, "playSent failed", t);
    }
  }

  private static @NonNull SoundPool ensurePool() {
    SoundPool local = pool;
    if (local != null) return local;
    synchronized (BMChatSounds.class) {
      if (pool == null) {
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
            .build();
        pool = new SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build();
      }
      return pool;
    }
  }
}
