package org.opentmf.query.tmf630.versioning;

import java.util.Comparator;

/**
 * Shared version-string comparators for the three {@link VersionOrder} modes.
 * Per-backend {@link Tmf630VersionResolver} implementations use these when they fall
 * back to in-JVM sorting (NUMERIC_STRING and SEMVER modes).
 */
public final class VersionComparators {

  private VersionComparators() {}

  /**
   * Returns a {@link Comparator} matching the given {@link VersionOrder}. Nulls sort
   * last regardless of direction — matches TMF-630 nulls-last conventions elsewhere
   * in the toolkit.
   */
  public static Comparator<String> forOrder(VersionOrder order) {
    return switch (order) {
      case LEX -> lexComparator();
      case NUMERIC_STRING -> numericStringComparator();
      case SEMVER -> semverComparator();
    };
  }

  private static Comparator<String> lexComparator() {
    return Comparator.nullsLast(Comparator.naturalOrder());
  }

  private static Comparator<String> numericStringComparator() {
    return Comparator.nullsLast(
        (a, b) -> {
          long left;
          long right;
          try {
            left = Long.parseLong(a);
            right = Long.parseLong(b);
          } catch (NumberFormatException e) {
            // Non-numeric value: fall back to lex ordering rather than throwing.
            // Documented on the enum javadoc — poison values sort by fallback.
            return a.compareTo(b);
          }
          return Long.compare(left, right);
        });
  }

  private static Comparator<String> semverComparator() {
    return Comparator.nullsLast(
        (a, b) -> {
          String[] leftParts = a.split("\\.");
          String[] rightParts = b.split("\\.");
          int len = Math.max(leftParts.length, rightParts.length);
          for (int i = 0; i < len; i++) {
            int leftComponent = safeParseComponent(leftParts, i);
            int rightComponent = safeParseComponent(rightParts, i);
            if (leftComponent < 0 || rightComponent < 0) {
              // Non-integer component anywhere → fall back to lex on the raw strings.
              return a.compareTo(b);
            }
            int cmp = Integer.compare(leftComponent, rightComponent);
            if (cmp != 0) return cmp;
          }
          return 0;
        });
  }

  private static int safeParseComponent(String[] parts, int index) {
    if (index >= parts.length) return 0; // "1" == "1.0" == "1.0.0"
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
