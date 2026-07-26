package org.opentmf.query.tmf630.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VersionComparatorsTest {

  @Test
  @DisplayName("LEX sorts strings lexicographically")
  void lexNaturalOrder() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.LEX);
    List<String> sorted = new java.util.ArrayList<>(List.of("2", "1", "10"));
    sorted.sort(comparator);
    assertEquals(List.of("1", "10", "2"), sorted);
  }

  @Test
  @DisplayName("NUMERIC_STRING sorts DNext-convention numeric versions natural-numeric")
  void numericStringDNextConvention() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.NUMERIC_STRING);
    List<String> sorted = new java.util.ArrayList<>(List.of("0", "1", "2", "10", "13", "28"));
    sorted.sort(comparator);
    assertEquals(List.of("0", "1", "2", "10", "13", "28"), sorted);
  }

  @Test
  @DisplayName("NUMERIC_STRING max picks the largest numeric value")
  void numericStringMax() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.NUMERIC_STRING);
    String max = List.of("0", "1", "10", "13", "2", "28").stream().max(comparator).orElseThrow();
    assertEquals("28", max);
  }

  @Test
  @DisplayName("NUMERIC_STRING falls back to lex for non-numeric values")
  void numericStringNonNumericFallsBackToLex() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.NUMERIC_STRING);
    // Non-numeric "abc" and "10" compared lex — "1" < "a".
    assertEquals(-1, Integer.signum(comparator.compare("10", "abc")));
  }

  @Test
  @DisplayName("SEMVER sorts semver-shaped versions numerically per component")
  void semverComponentWise() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.SEMVER);
    List<String> sorted = new java.util.ArrayList<>(List.of("1.10", "1.9", "2.0", "1.0"));
    sorted.sort(comparator);
    assertEquals(List.of("1.0", "1.9", "1.10", "2.0"), sorted);
  }

  @Test
  @DisplayName("SEMVER treats missing components as zero (1 == 1.0 == 1.0.0)")
  void semverMissingComponentsAreZero() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.SEMVER);
    assertEquals(0, comparator.compare("1", "1.0"));
    assertEquals(0, comparator.compare("1.0", "1.0.0"));
    assertEquals(0, comparator.compare("1", "1.0.0"));
  }

  @Test
  @DisplayName("SEMVER max picks the largest semver value")
  void semverMax() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.SEMVER);
    String max = List.of("1.0", "1.9", "1.10", "2.0", "2.3.1").stream().max(comparator).orElseThrow();
    assertEquals("2.3.1", max);
  }

  @Test
  @DisplayName("SEMVER falls back to lex on non-integer components")
  void semverNonIntegerComponentFallsBackToLex() {
    Comparator<String> comparator = VersionComparators.forOrder(VersionOrder.SEMVER);
    // Non-integer component → whole comparison degrades to lex.
    // "1.0-beta" contains "0-beta" which is not parseable.
    int result = comparator.compare("1.0-beta", "1.0");
    // Lex: "1.0-beta" > "1.0" (longer, common prefix).
    assertEquals(1, Integer.signum(result));
  }

  @Test
  @DisplayName("all orders sort null last regardless of direction")
  void allOrdersNullsLast() {
    for (VersionOrder order : VersionOrder.values()) {
      Comparator<String> comparator = VersionComparators.forOrder(order);
      List<String> sorted = new java.util.ArrayList<>(java.util.Arrays.asList("1", null, "2"));
      sorted.sort(comparator);
      assertEquals(null, sorted.get(sorted.size() - 1), order.name() + " should sort null last");
    }
  }
}
