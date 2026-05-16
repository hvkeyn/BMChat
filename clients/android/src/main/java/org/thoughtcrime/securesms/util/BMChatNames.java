package org.thoughtcrime.securesms.util;

import android.text.TextUtils;

/**
 * BMChat-specific display-name helpers.
 *
 * <p>Delta Chat falls back to the raw e-mail address when an Autocrypt or vCard "Display Name"
 * has not been received from the peer yet. That looks unfriendly to a casual user (the chat list
 * looks like an inbox of e-mails). When we don't have anything better, derive a humanised label
 * from the local-part of the address — e.g. {@code blockytime@yandex.ru} becomes
 * {@code Blockytime}, {@code john.doe@example.com} becomes {@code John Doe}.
 *
 * <p>The original raw address is left untouched whenever a real name is already known.
 */
public final class BMChatNames {

  private BMChatNames() {}

  /**
   * Return a friendlier fallback name when {@code rawName} is a bare e-mail address, otherwise
   * return {@code rawName} unchanged.
   */
  public static String humanize(String rawName) {
    if (TextUtils.isEmpty(rawName)) return rawName;
    int at = rawName.indexOf('@');
    if (at <= 0) return rawName;
    // Looks like "local@domain". If the prefix already contains spaces it is probably already
    // a name, do not mangle it.
    String local = rawName.substring(0, at);
    if (local.indexOf(' ') >= 0) return rawName;

    String pretty = local.replace('_', ' ').replace('.', ' ').replace('-', ' ').trim();
    if (TextUtils.isEmpty(pretty)) return rawName;

    StringBuilder sb = new StringBuilder(pretty.length());
    boolean capitalise = true;
    for (int i = 0; i < pretty.length(); i++) {
      char c = pretty.charAt(i);
      if (Character.isWhitespace(c)) {
        capitalise = true;
        sb.append(c);
      } else if (capitalise) {
        sb.append(Character.toUpperCase(c));
        capitalise = false;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Same as {@link #humanize(String)} but uses {@code addr} as the source when {@code rawName} is
   * blank — handy for {@code DcContact} where {@code getDisplayName()} may already return the
   * address.
   */
  public static String humanize(String rawName, String addr) {
    if (TextUtils.isEmpty(rawName)) return humanize(addr);
    return humanize(rawName);
  }
}
