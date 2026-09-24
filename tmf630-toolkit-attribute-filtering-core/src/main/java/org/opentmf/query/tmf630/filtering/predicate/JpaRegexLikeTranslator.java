package org.opentmf.query.tmf630.filtering.predicate;

import org.opentmf.query.tmf630.filtering.TmfFilteringException;

/**
 * Translates the LIKE-expressible subset of a regular expression into a SQL {@code LIKE} pattern
 * for JPA-rooted queries, and rejects everything outside that subset.
 *
 * <p>Why this exists: querydsl-jpa renders {@code Ops.MATCHES} through its own
 * {@code regexToLike}, which rewrites only {@code .*} and {@code .}, leaves every other
 * metacharacter literal, passes SQL wildcards ({@code %}, {@code _}) through uninterpreted, and
 * throws an unmapped {@code QueryException} on {@code ^}, {@code [} and {@code \d}. The same URL
 * therefore returned different rows on JPA than on Mongo/JSONB, or a 500. This translator is
 * the replacement: for the subset it accepts, the {@code LIKE} it emits matches exactly the
 * strings the regex matches, so all three backends agree; for anything else it throws a
 * {@link TmfFilteringException} (HTTP 400) naming the subset.
 *
 * <p>Accepted subset:
 *
 * <ul>
 *   <li>literal characters — {@code %}, {@code _} and the escape character are escaped so they
 *       match themselves;
 *   <li>{@code \} followed by a non-alphanumeric character — that character, literally;
 *   <li>{@code .} — any single character ({@code _});
 *   <li>{@code .*} and {@code .*?} — any sequence ({@code %});
 *   <li>a leading {@code ^} and a trailing {@code $} — anchors; an unanchored side is padded with
 *       {@code %}, so a bare {@code p} means <em>contains</em> {@code p}, exactly as the regex
 *       does.
 * </ul>
 *
 * <p>Rejected: {@code +}, {@code ?} (except in {@code .*?}), {@code *} not preceded by {@code .},
 * {@code [ ]}, {@code ( )}, {@code { }}, {@code |}, {@code ^} / {@code $} anywhere but the ends,
 * {@code \} followed by a letter or digit ({@code \d}, {@code \w}, {@code \s}, {@code \1}, …) and a
 * trailing lone {@code \}.
 */
final class JpaRegexLikeTranslator {

  /** Escape character of the emitted pattern; querydsl uses the same one for LIKE-family ops. */
  static final char ESCAPE = '!';

  static final String SUPPORTED_SUBSET =
      "literal characters, '\\'-escaped metacharacters, '.', '.*', '.*?', a leading '^', a"
          + " trailing '$' and the 'i' flag";

  private static final String REJECTED_METACHARACTERS = "*+?[]{}()|^$";

  private JpaRegexLikeTranslator() {}

  /**
   * @param regex the pattern as the caller wrote it (no delimiters, no flags)
   * @param fieldPath the field the predicate targets, for the rejection message
   * @return the equivalent {@code LIKE} pattern, escaped with {@link #ESCAPE}
   * @throws TmfFilteringException when the pattern uses a feature outside the subset
   */
  static String translate(String regex, String fieldPath) {
    boolean anchoredStart = regex.startsWith("^");
    boolean anchoredEnd = hasUnescapedTrailingDollar(regex);
    int from = anchoredStart ? 1 : 0;
    int to = anchoredEnd ? regex.length() - 1 : regex.length();

    StringBuilder like = new StringBuilder(regex.length() + 2);
    int i = from;
    while (i < to) {
      i = translateAtom(regex, i, to, like, fieldPath);
    }

    if (!anchoredStart && !startsWithWildcard(like)) {
      like.insert(0, '%');
    }
    if (!anchoredEnd && !endsWithWildcard(like)) {
      like.append('%');
    }
    return like.toString();
  }

  /** Consumes one regex atom at {@code i}, appends its LIKE form and returns the next index. */
  private static int translateAtom(
      String regex, int i, int end, StringBuilder like, String fieldPath) {
    char c = regex.charAt(i);
    if (c == '\\') {
      return translateEscape(regex, i, end, like, fieldPath);
    }
    if (c == '.') {
      return translateDot(regex, i, end, like);
    }
    if (REJECTED_METACHARACTERS.indexOf(c) >= 0) {
      throw reject(regex, fieldPath, c, i);
    }
    appendLiteral(like, c);
    return i + 1;
  }

  private static int translateEscape(
      String regex, int i, int end, StringBuilder like, String fieldPath) {
    if (i + 1 >= end) {
      throw reject(regex, fieldPath, '\\', i);
    }
    char escaped = regex.charAt(i + 1);
    if (Character.isLetterOrDigit(escaped)) {
      // \d, \w, \s, \b, \1, \n … are classes, boundaries, back-references or control
      // characters — none of them has a LIKE form.
      throw reject(regex, fieldPath, escaped, i + 1);
    }
    appendLiteral(like, escaped);
    return i + 2;
  }

  private static int translateDot(String regex, int i, int end, StringBuilder like) {
    if (i + 1 < end && regex.charAt(i + 1) == '*') {
      like.append('%');
      // `.*?` (lazy) matches the same set of strings as `.*` — laziness only changes which
      // match is reported, never whether one exists.
      boolean lazy = i + 2 < end && regex.charAt(i + 2) == '?';
      return lazy ? i + 3 : i + 2;
    }
    like.append('_');
    return i + 1;
  }

  private static void appendLiteral(StringBuilder like, char c) {
    if (c == '%' || c == '_' || c == ESCAPE) {
      like.append(ESCAPE);
    }
    like.append(c);
  }

  private static boolean hasUnescapedTrailingDollar(String regex) {
    if (!regex.endsWith("$")) {
      return false;
    }
    int backslashes = 0;
    for (int i = regex.length() - 2; i >= 0 && regex.charAt(i) == '\\'; i--) {
      backslashes++;
    }
    return backslashes % 2 == 0;
  }

  private static boolean startsWithWildcard(StringBuilder like) {
    return !like.isEmpty() && like.charAt(0) == '%';
  }

  private static boolean endsWithWildcard(StringBuilder like) {
    if (like.isEmpty()) {
      return false;
    }
    int n = like.length();
    return like.charAt(n - 1) == '%' && (n < 2 || like.charAt(n - 2) != ESCAPE);
  }

  private static TmfFilteringException reject(
      String regex, String fieldPath, char offending, int index) {
    return new TmfFilteringException(
        "Regex pattern for field '"
            + fieldPath
            + "' uses '"
            + offending
            + "' (at index "
            + index
            + " of '"
            + regex
            + "'), which the JPA backend cannot render faithfully as SQL LIKE. Supported subset on"
            + " JPA: "
            + SUPPORTED_SUBSET
            + ". For full regex semantics on a relational database use a JSONB-backed entity"
            + " (@Tmf630JsonbBacked), or a MongoDB backend.");
  }
}
