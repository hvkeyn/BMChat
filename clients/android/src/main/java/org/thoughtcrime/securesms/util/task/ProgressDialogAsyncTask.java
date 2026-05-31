package org.thoughtcrime.securesms.util.task;

import android.content.Context;
import android.content.DialogInterface.OnCancelListener;
import android.os.AsyncTask;
import androidx.annotation.Nullable;
import java.lang.ref.WeakReference;
import org.thoughtcrime.securesms.util.views.ProgressDialog;

public abstract class ProgressDialogAsyncTask<Params, Progress, Result>
    extends AsyncTask<Params, Progress, Result> {

  private final WeakReference<Context> contextReference;
  protected ProgressDialog progress;
  private final String title;
  private final String message;
  private boolean cancelable;
  private OnCancelListener onCancelListener;

  public ProgressDialogAsyncTask(Context context, String title, String message) {
    super();
    this.contextReference = new WeakReference<>(context);
    this.title = title;
    this.message = message;
  }

  /** BMChat 2.49.91: wire hardware-back / dialog cancel to {@link #cancel(boolean)}. */
  protected void enableCancelOnBack() {
    setCancelable(
        dialog -> {
          cancel(true);
        });
  }

  @Override
  protected void onCancelled(@Nullable Result result) {
    try {
      if (progress != null) progress.dismiss();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public void setCancelable(@Nullable OnCancelListener onCancelListener) {
    this.cancelable = true;
    this.onCancelListener = onCancelListener;
  }

  @Override
  protected void onPreExecute() {
    final Context context = contextReference.get();
    if (context != null) {
      progress = ProgressDialog.show(context, title, message, true, cancelable, onCancelListener);
    }
  }

  @Override
  protected void onPostExecute(Result result) {
    try {
      if (progress != null) progress.dismiss();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  protected Context getContext() {
    return contextReference.get();
  }

  /** BMChat 2.49.93: update the visible save/download progress bar (0…max). */
  protected void updateDialogProgress(int current, int max, @Nullable CharSequence detail) {
    if (progress == null) return;
    progress.setDeterminateProgress(max, current);
    if (detail != null && !detail.toString().isEmpty()) {
      progress.setMessage(detail);
    }
  }
}
