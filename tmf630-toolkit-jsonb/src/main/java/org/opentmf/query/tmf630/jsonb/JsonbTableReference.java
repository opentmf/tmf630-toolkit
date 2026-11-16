package org.opentmf.query.tmf630.jsonb;

/**
 * Whether a row entity's table name can be written into the module's SQL as ONE table reference
 * (since 3.6.0). The name comes from {@code @Table(name)} or the class name, never from a request,
 * so this is a startup check against a name that only works by accident, such as {@code message m},
 * which a {@code SELECT} reads as a table with an alias and an {@code INSERT} refuses. The name is
 * written into the SQL as it is; the check only reads it.
 *
 * <p>Compatible on purpose: every form PostgreSQL accepts as a table reference passes, among them a
 * quoted mixed-case name ({@code "CommunicationMessage"}), a {@code $} or a non-ASCII letter in an
 * unquoted name, and dot-separated parts, each quoted or not. Whitespace at the two ends of the
 * name passes, as the SQL around it reads it in 3.5.2. Refused, outside double quotes: whitespace
 * inside the name, {@code ;}, {@code ,}, {@code ?} (the JDBC driver reads it as a parameter), a
 * comment marker ({@code --}, {@code /*}) and a parenthesis; text directly before an opening or
 * after a closing double quote other than {@code .} ({@code m"x"} and {@code "m"x} are a table
 * with an alias); and a double quote that is never closed.
 */
final class JsonbTableReference {

  private JsonbTableReference() {}

  /**
   * What makes {@code name} no table reference, as a short phrase for the startup message, or
   * {@code null} when it is one.
   */
  static String problem(String name) {
    String inner = name.strip();
    int i = 0;
    while (i < inner.length()) {
      if (inner.charAt(i) == '"') {
        String quoted = quotedPartProblem(inner, i);
        if (quoted != null) {
          return quoted;
        }
        i = closingQuote(inner, i) + 1;
      } else {
        String found = forbiddenAt(inner, i);
        if (found != null) {
          return found + " outside double quotes";
        }
        i++;
      }
    }
    return null;
  }

  /** What is wrong around the double-quoted part opening at {@code open}, or {@code null}. */
  private static String quotedPartProblem(String name, int open) {
    if (open > 0 && name.charAt(open - 1) != '.') {
      return "text directly before an opening double quote";
    }
    int close = closingQuote(name, open);
    if (close < 0) {
      return "a double quote that is never closed";
    }
    if (close + 1 < name.length() && name.charAt(close + 1) != '.') {
      return "text directly after a closing double quote";
    }
    return null;
  }

  /** The index of the quote closing the one at {@code open} ({@code ""} is an escaped quote). */
  private static int closingQuote(String name, int open) {
    int i = open + 1;
    while (i < name.length()) {
      if (name.charAt(i) != '"') {
        i++;
      } else if (i + 1 < name.length() && name.charAt(i + 1) == '"') {
        i += 2;
      } else {
        return i;
      }
    }
    return -1;
  }

  private static String forbiddenAt(String name, int i) {
    char c = name.charAt(i);
    if (Character.isWhitespace(c)) {
      return "whitespace";
    }
    return switch (c) {
      case ';' -> "';'";
      case ',' -> "','";
      case '?' -> "'?'";
      case '(', ')' -> "a parenthesis";
      default -> commentMarkerAt(name, i);
    };
  }

  private static String commentMarkerAt(String name, int i) {
    if (i + 1 < name.length()) {
      String two = name.substring(i, i + 2);
      if (two.equals("--") || two.equals("/*")) {
        return "a comment marker";
      }
    }
    return null;
  }
}
