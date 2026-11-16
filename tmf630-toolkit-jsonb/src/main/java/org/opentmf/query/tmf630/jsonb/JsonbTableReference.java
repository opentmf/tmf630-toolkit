package org.opentmf.query.tmf630.jsonb;

/**
 * Whether a row entity's table name can be written into the module's SQL as a table reference
 * (since 3.6.0). The name comes from {@code @Table(name)} or the class name, never from a request,
 * so this is a startup check against a name that only works by accident, such as {@code message m},
 * which a {@code SELECT} reads as a table with an alias and an {@code INSERT} refuses.
 *
 * <p>Compatible on purpose: every form PostgreSQL accepts as a table reference passes, among them a
 * quoted mixed-case name ({@code "CommunicationMessage"}), a {@code $} or a non-ASCII letter in an
 * unquoted name, and two or three dot-separated parts, each quoted or not. Only what cannot be part
 * of a table reference is refused, and only outside double quotes: whitespace, {@code ;}, a comment
 * marker ({@code --}, {@code /*}) and a parenthesis. A double quote with no closing quote opens
 * nothing, so what follows it counts as outside quotes.
 */
final class JsonbTableReference {

  private JsonbTableReference() {}

  /**
   * What makes {@code name} no table reference, as a short phrase for the startup message, or
   * {@code null} when it is one.
   */
  static String problem(String name) {
    int i = 0;
    while (i < name.length()) {
      char c = name.charAt(i);
      if (c == '"') {
        int close = closingQuote(name, i);
        if (close > i) {
          i = close + 1;
          continue;
        }
      }
      String found = forbiddenAt(name, i);
      if (found != null) {
        return found + " outside double quotes";
      }
      i++;
    }
    return null;
  }

  /** The index of the quote closing the one at {@code open} ({@code ""} is an escaped quote). */
  private static int closingQuote(String name, int open) {
    int i = open + 1;
    while (i < name.length()) {
      if (name.charAt(i) == '"') {
        if (i + 1 < name.length() && name.charAt(i + 1) == '"') {
          i += 2;
          continue;
        }
        return i;
      }
      i++;
    }
    return -1;
  }

  private static String forbiddenAt(String name, int i) {
    char c = name.charAt(i);
    if (Character.isWhitespace(c)) {
      return "whitespace";
    }
    if (c == ';') {
      return "';'";
    }
    if (c == '(' || c == ')') {
      return "a parenthesis";
    }
    if (i + 1 < name.length()) {
      String two = name.substring(i, i + 2);
      if (two.equals("--") || two.equals("/*")) {
        return "a comment marker";
      }
    }
    return null;
  }
}
