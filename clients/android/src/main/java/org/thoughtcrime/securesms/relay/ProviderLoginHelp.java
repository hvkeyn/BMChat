package org.thoughtcrime.securesms.relay;

import android.content.Context;
import androidx.annotation.Nullable;
import org.thoughtcrime.securesms.R;

/**
 * BMChat: in-app knowledge base about popular mail providers that require an "app password"
 * instead of the regular web-cabinet password for IMAP/SMTP access. The provider DB in
 * deltachat-core-rust already reports {@code Status::Preparation} for these domains, but the
 * hint there is generic and easy to skip. This helper provides a domain-specific instruction
 * with a one-tap link to the right settings page, so a user who hits "Invalid login or
 * password" understands what to do.
 */
public final class ProviderLoginHelp {

  public static final class Hint {
    public final int titleRes;
    public final int messageRes;
    @Nullable public final String settingsUrl;
    @Nullable public final String settingsButtonLabel;

    private Hint(int titleRes, int messageRes, @Nullable String url, @Nullable String label) {
      this.titleRes = titleRes;
      this.messageRes = messageRes;
      this.settingsUrl = url;
      this.settingsButtonLabel = label;
    }
  }

  private ProviderLoginHelp() {}

  /** Returns a hint for the given e-mail address, or {@code null} if the domain is unknown. */
  @Nullable
  public static Hint forEmail(@Nullable String email) {
    String domain = extractDomain(email);
    if (domain == null) return null;
    return forDomain(domain);
  }

  /** Returns a hint for the given domain (without "@"), or {@code null} if unknown. */
  @Nullable
  public static Hint forDomain(String domain) {
    if (domain == null) return null;
    switch (domain) {
      case "rambler.ru":
      case "autorambler.ru":
      case "myrambler.ru":
      case "rambler.ua":
      case "lenta.ru":
      case "ro.ru":
      case "r0.ru":
        return new Hint(
            R.string.bmchat_login_help_rambler_title,
            R.string.bmchat_login_help_rambler_message,
            "https://id.rambler.ru/account/mail-clients",
            "Открыть настройки Rambler");

      case "mail.ru":
      case "inbox.ru":
      case "list.ru":
      case "bk.ru":
      case "internet.ru":
        return new Hint(
            R.string.bmchat_login_help_mailru_title,
            R.string.bmchat_login_help_mailru_message,
            "https://account.mail.ru/user/2-step-auth/passwords",
            "Открыть настройки Mail.ru");

      case "yandex.ru":
      case "yandex.com":
      case "yandex.ua":
      case "yandex.by":
      case "yandex.kz":
      case "ya.ru":
      case "narod.ru":
        return new Hint(
            R.string.bmchat_login_help_yandex_title,
            R.string.bmchat_login_help_yandex_message,
            "https://id.yandex.ru/security/app-passwords",
            "Открыть настройки Яндекс ID");

      case "gmail.com":
      case "googlemail.com":
        return new Hint(
            R.string.bmchat_login_help_gmail_title,
            R.string.bmchat_login_help_gmail_message,
            "https://myaccount.google.com/apppasswords",
            "Открыть пароли приложений Google");

      case "outlook.com":
      case "hotmail.com":
      case "live.com":
      case "msn.com":
        return new Hint(
            R.string.bmchat_login_help_outlook_title,
            R.string.bmchat_login_help_outlook_message,
            "https://account.live.com/proofs/AppPassword",
            "Открыть настройки Microsoft");

      case "yahoo.com":
      case "yahoo.co.uk":
      case "yahoo.de":
      case "yahoo.fr":
      case "yahoo.it":
      case "ymail.com":
        return new Hint(
            R.string.bmchat_login_help_yahoo_title,
            R.string.bmchat_login_help_yahoo_message,
            "https://login.yahoo.com/account/security/app-passwords",
            "Открыть настройки Yahoo");

      case "icloud.com":
      case "me.com":
      case "mac.com":
        return new Hint(
            R.string.bmchat_login_help_icloud_title,
            R.string.bmchat_login_help_icloud_message,
            "https://account.apple.com/account/manage",
            "Открыть Apple ID");

      default:
        return null;
    }
  }

  /**
   * Returns {@code true} when the configuration error reported by the core looks like an
   * authentication failure (wrong login/password). DNS/TLS/network errors do not pass.
   */
  public static boolean looksLikeAuthError(@Nullable String error) {
    if (error == null) return false;
    String lower = error.toLowerCase();
    return lower.contains("invalid login or password")
        || lower.contains("login failed")
        || lower.contains("authentication failed")
        || lower.contains("auth failed")
        || lower.contains("authenticationfailed")
        || lower.contains("bad credentials")
        || lower.contains("incorrect credentials")
        || lower.contains("5.7.0")
        || lower.contains("5.7.8")
        || lower.contains("permission denied");
  }

  /** Convenience wrapper used by callers that already know both pieces of context. */
  @Nullable
  public static Hint forErrorAndEmail(@Nullable String error, @Nullable String email) {
    if (!looksLikeAuthError(error)) {
      return null;
    }
    return forEmail(email);
  }

  /** Human-readable provider name for the supplied email, or {@code null}. */
  @Nullable
  public static String providerNameForEmail(Context ctx, @Nullable String email) {
    Hint h = forEmail(email);
    if (h == null) return null;
    return ctx.getString(h.titleRes);
  }

  @Nullable
  private static String extractDomain(@Nullable String email) {
    if (email == null) return null;
    int at = email.lastIndexOf('@');
    if (at <= 0 || at >= email.length() - 1) return null;
    return email.substring(at + 1).trim().toLowerCase();
  }
}
