package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

/**
 * Pins the regex → LIKE table that replaced querydsl's {@code regexToLike} on JPA roots. The
 * accepted rows are the LIKE-expressible subset; every emitted pattern must match exactly the
 * strings the regex matches, so JPA agrees with the real-regex backends (Mongo, JSONB). The
 * rejected rows are what querydsl used to render silently wrong or throw an unmapped
 * exception on.
 */
class JpaRegexLikeTranslatorTest {

  static Stream<Arguments> accepted() {
    return Stream.of(
        // unanchored bare literal is CONTAINS, as the regex is
        Arguments.of("p", "%p%"),
        Arguments.of("^p", "p%"),
        Arguments.of("p$", "%p"),
        Arguments.of("^p$", "p"),
        // spec example (Part 6 p.22): lazy .*? is the same set of strings as .*
        Arguments.of("Resol.*?", "%Resol%"),
        Arguments.of("^Resol.*?", "Resol%"),
        Arguments.of(".*p.*", "%p%"),
        Arguments.of("a.*b", "%a%b%"),
        Arguments.of("a.b", "%a_b%"),
        Arguments.of("a\\.b", "%a.b%"),
        // SQL wildcards typed by the user are literals, never wildcards
        Arguments.of("%", "%!%%"),
        Arguments.of("_", "%!_%"),
        Arguments.of("^50% off$", "50!% off"),
        Arguments.of("100_percent", "%100!_percent%"),
        // the escape character itself is escaped
        Arguments.of("hello!", "%hello!!%"),
        // escaped metacharacters are literals
        Arguments.of("\\$5", "%$5%"),
        Arguments.of("^\\^caret$", "^caret"),
        Arguments.of("a\\+b", "%a+b%"),
        Arguments.of("\\\\", "%\\%"),
        Arguments.of("a\\/b", "%a/b%"),
        // hyphen and other punctuation are plain literals
        Arguments.of("Pass-through", "%Pass-through%"),
        Arguments.of("NOT_R2S-21", "%NOT!_R2S-21%"),
        // degenerate anchors
        Arguments.of("^", "%"),
        Arguments.of("$", "%"),
        Arguments.of("^$", ""),
        Arguments.of(".*", "%"),
        Arguments.of("", "%"));
  }

  @ParameterizedTest(name = "/{0}/ → LIKE ''{1}''")
  @MethodSource("accepted")
  void translatesTheLikeExpressibleSubset(String regex, String expectedLike) {
    assertEquals(expectedLike, JpaRegexLikeTranslator.translate(regex, "name"));
  }

  @ParameterizedTest(name = "/{0}/ → 400")
  @ValueSource(
      strings = {
        "p+", "p?", "ab*", "[pq]", "(p|q)", "p|q", "p{2}", "\\d", "\\w+", "\\s", "\\b", "\\1",
        "a^b", "a$b", "^^p", "p$$", "trailing\\"
      })
  void rejectsEverythingOutsideTheSubset(String regex) {
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class, () -> JpaRegexLikeTranslator.translate(regex, "name"));
    assertTrue(ex.getMessage().contains("Supported subset on JPA"), ex.getMessage());
    assertTrue(ex.getMessage().contains("'name'"), ex.getMessage());
  }

  @Test
  @DisplayName("rejection names the offending character and its index")
  void rejectionNamesOffender() {
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class, () -> JpaRegexLikeTranslator.translate("ab+", "name"));
    assertTrue(ex.getMessage().contains("uses '+' (at index 2"), ex.getMessage());
  }

  @Test
  @DisplayName("an escaped trailing dollar is a literal, not an anchor")
  void escapedTrailingDollarIsLiteral() {
    assertEquals("%price$%", JpaRegexLikeTranslator.translate("price\\$", "name"));
    // two backslashes = escaped backslash, then a real anchor
    assertEquals("%price\\", JpaRegexLikeTranslator.translate("price\\\\$", "name"));
  }
}
