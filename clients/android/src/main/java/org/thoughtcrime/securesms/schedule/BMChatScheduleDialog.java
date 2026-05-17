package org.thoughtcrime.securesms.schedule;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.util.Calendar;
import org.thoughtcrime.securesms.R;

/**
 * BMChat 2.49.84 (Phase 4B): two-step date+time picker for scheduled messages. Mirrors the
 * Telegram "Schedule message" flow — pick a day, then a time, with the obvious "future only"
 * validation.
 */
public final class BMChatScheduleDialog {

  public interface Listener {
    /** Called once the user committed a date+time strictly in the future. */
    void onTimePicked(long epochMillis);
  }

  private BMChatScheduleDialog() {}

  /** Show the picker, defaulting to "an hour from now". */
  public static void show(@NonNull Context context, @NonNull Listener listener) {
    Calendar now = Calendar.getInstance();
    Calendar suggestion = Calendar.getInstance();
    suggestion.add(Calendar.HOUR_OF_DAY, 1);

    new DatePickerDialog(
            context,
            (datePicker, year, month, day) -> {
              Calendar picked = Calendar.getInstance();
              picked.set(year, month, day);

              new TimePickerDialog(
                      context,
                      (timePicker, hour, minute) -> {
                        picked.set(Calendar.HOUR_OF_DAY, hour);
                        picked.set(Calendar.MINUTE, minute);
                        picked.set(Calendar.SECOND, 0);
                        picked.set(Calendar.MILLISECOND, 0);

                        if (picked.getTimeInMillis() <= System.currentTimeMillis()) {
                          Toast.makeText(
                                  context,
                                  R.string.bmchat_schedule_in_the_past,
                                  Toast.LENGTH_LONG)
                              .show();
                          return;
                        }
                        listener.onTimePicked(picked.getTimeInMillis());
                      },
                      suggestion.get(Calendar.HOUR_OF_DAY),
                      suggestion.get(Calendar.MINUTE),
                      DateFormat.is24HourFormat(context))
                  .show();
            },
            suggestion.get(Calendar.YEAR),
            suggestion.get(Calendar.MONTH),
            suggestion.get(Calendar.DAY_OF_MONTH))
        .show();
  }
}
