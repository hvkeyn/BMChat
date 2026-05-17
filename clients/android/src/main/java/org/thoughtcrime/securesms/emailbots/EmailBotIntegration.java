package org.thoughtcrime.securesms.emailbots;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Process-wide entry point for the e-mail-bot subsystem.
 *
 * <p>Owns the singleton {@link EmailBotDispatcher} that processes every
 * incoming Delta-chat message. Code under {@code DcEventCenter} fans
 * out events through this class so callers don't need to manage the
 * lifecycle of the dispatcher themselves (which would otherwise have
 * been awkward because event-center initialisation happens before
 * {@code ApplicationContext.onCreate} finishes).
 */
public final class EmailBotIntegration {

  private static volatile EmailBotIntegration INSTANCE;

  private final EmailBotDispatcher dispatcher;

  private EmailBotIntegration(@NonNull Context context) {
    this.dispatcher = new EmailBotDispatcher(context);
  }

  @NonNull
  public static EmailBotIntegration get(@NonNull Context context) {
    EmailBotIntegration local = INSTANCE;
    if (local == null) {
      synchronized (EmailBotIntegration.class) {
        local = INSTANCE;
        if (local == null) {
          local = new EmailBotIntegration(context.getApplicationContext());
          INSTANCE = local;
        }
      }
    }
    return local;
  }

  @NonNull
  public EmailBotDispatcher getDispatcher() { return dispatcher; }
}
