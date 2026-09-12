package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

class JsonbJsonPathTranslatorTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbJsonPathTranslator translator =
      new JsonbJsonPathTranslator(extractor, "payload");

  @Test
  @DisplayName("simple equality filter wraps in jsonb_path_exists with converted quotes")
  void simpleEquality() {
    JsonbClause c = translator.translate("$[?(@.status == 'Pending')]");
    assertThat(c.sql()).isEqualTo("jsonb_path_exists(payload, ?::jsonpath)");
    assertThat(c.params()).containsExactly("$ ? (@.status == \"Pending\")");
  }

  @Test
  @DisplayName("bare wrapper form [?(...)] is accepted")
  void bareWrapper() {
    JsonbClause c = translator.translate("[?(@.status == 'Pending')]");
    assertThat(c.params()).containsExactly("$ ? (@.status == \"Pending\")");
  }

  @Test
  @DisplayName("dot-bracket form $.[?(...)] is accepted")
  void dotBracketWrapper() {
    JsonbClause c = translator.translate("$.[?(@.status == 'Pending')]");
    assertThat(c.params()).containsExactly("$ ? (@.status == \"Pending\")");
  }

  @Test
  @DisplayName("compound predicate with && preserves logical operators and quote-converts")
  void compoundAnd() {
    JsonbClause c =
        translator.translate("$[?(@.status == 'Pending' && @.priority >= 1)]");
    assertThat(c.params())
        .containsExactly("$ ? (@.status == \"Pending\" && @.priority >= 1)");
  }

  @Test
  @DisplayName("|| and grouping via parens preserved")
  void orAndGroups() {
    JsonbClause c =
        translator.translate("$[?((@.a == 'X') || (@.b == 'Y'))]");
    assertThat(c.params()).containsExactly("$ ? ((@.a == \"X\") || (@.b == \"Y\"))");
  }

  @Test
  @DisplayName("array correlation @.arr[?(...)] rewrites to @.arr[*] ? (...)")
  void arrayCorrelation() {
    JsonbClause c =
        translator.translate(
            "$[?(@.externalReference[?(@.name == 'REF' && @.id == 'X')])]");
    assertThat(c.params())
        .containsExactly(
            "$ ? (@.externalReference[*] ? (@.name == \"REF\" && @.id == \"X\"))");
  }

  @Test
  @DisplayName("length() equality on collection field uses jsonb_array_length, not path_exists")
  void lengthEquality() {
    JsonbClause c = translator.translate("$[?(@.tags.length() == 0)]");
    assertThat(c.sql()).isEqualTo("jsonb_array_length(payload->'tags') = ?");
    assertThat(c.params()).containsExactly(0);
  }

  @Test
  @DisplayName("length() on dotted path uses #> extraction")
  void lengthOnNestedPath() {
    JsonbClause c = translator.translate("$[?(@.a.b.items.length() == 5)]");
    assertThat(c.sql()).isEqualTo("jsonb_array_length(payload#>'{a,b,items}') = ?");
    assertThat(c.params()).containsExactly(5);
  }

  @Test
  @DisplayName("length() with negative literal is rejected")
  void lengthRejectsNegative() {
    // The regex captures negative integers; the validator rejects them.
    assertThatThrownBy(() -> translator.translate("$[?(@.tags.length() == -1)]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("non-negative");
  }

  @Test
  @DisplayName("null literal in comparison passes through")
  void nullLiteral() {
    JsonbClause c = translator.translate("$[?(@.optional == null)]");
    assertThat(c.params()).containsExactly("$ ? (@.optional == null)");
  }

  @Test
  @DisplayName("numeric and boolean literals pass through unchanged")
  void numericAndBooleanLiterals() {
    JsonbClause c = translator.translate("$[?(@.count > 5 && @.enabled == true)]");
    assertThat(c.params()).containsExactly("$ ? (@.count > 5 && @.enabled == true)");
  }

  @Test
  @DisplayName("embedded double-quote inside single-quoted string is escaped in output")
  void embeddedDoubleQuoteEscaped() {
    JsonbClause c = translator.translate("$[?(@.title == 'The \"Book\"')]");
    assertThat(c.params()).containsExactly("$ ? (@.title == \"The \\\"Book\\\"\")");
  }

  @Test
  @DisplayName("blank or null expression rejected")
  void rejectsBlank() {
    assertThatThrownBy(() -> translator.translate(""))
        .isInstanceOf(TmfFilteringException.class);
    assertThatThrownBy(() -> translator.translate(null))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("expression without a $[?(...)] wrapper is rejected")
  void rejectsUnwrapped() {
    assertThatThrownBy(() -> translator.translate("@.status == 'X'"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("unterminated string literal rejected with clear message")
  void unterminatedString() {
    assertThatThrownBy(() -> translator.translate("$[?(@.status == 'unterminated)]"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("unterminated array-match filter rejected")
  void unterminatedArrayMatch() {
    assertThatThrownBy(() -> translator.translate("$[?(@.arr[?(@.name == 'X')]"))
        .isInstanceOf(TmfFilteringException.class);
  }

  @Test
  @DisplayName("nested array-match inside array-match composes recursively")
  void nestedArrayCorrelation() {
    JsonbClause c =
        translator.translate(
            "$[?(@.a[?(@.b[?(@.c == 'X')])])]");
    assertThat(c.params())
        .containsExactly("$ ? (@.a[*] ? (@.b[*] ? (@.c == \"X\")))");
  }

  @Test
  @DisplayName("wrapper tolerates whitespace around every token, as before")
  void wrapperWhitespaceTolerance() {
    JsonbClause c = translator.translate("  $ . [ ? ( @.status == 'Pending' ) ]  ");
    assertThat(c.params()).containsExactly("$ ? ( @.status == \"Pending\" )");
  }

  @Test
  @DisplayName("length() form tolerates whitespace and a '.length' segment inside the field path")
  void lengthWithWhitespaceAndLengthSegment() {
    JsonbClause c = translator.translate("$[?( @.a.length.b.length() == 2 )]");
    assertThat(c.sql()).isEqualTo("jsonb_array_length(payload#>'{a,length,b}') = ?");
    assertThat(c.params()).containsExactly(2);
  }

  @Test
  @DisplayName("only ASCII whitespace surrounds the wrapper: a trailing U+2028 is rejected, as before")
  void nonAsciiLineSeparatorIsNotWrapperWhitespace() {
    assertThatThrownBy(() -> translator.translate("$[?(@.status == 'x')]\u2028"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("must be wrapped");
  }

  @Test
  @DisplayName("wrapper parsing stays linear on pathological whitespace (no regex backtracking)")
  void wrapperParsingIsLinear() {
    String pathological = "$" + " ".repeat(100_000) + "x";
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThatThrownBy(() -> translator.translate(pathological))
                .isInstanceOf(TmfFilteringException.class));
  }

  @Test
  @DisplayName("null extractor / blank payload column rejected at construction")
  void rejectsInvalidConstruction() {
    assertThatThrownBy(() -> new JsonbJsonPathTranslator(null, "payload"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonbJsonPathTranslator(extractor, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonbJsonPathTranslator(extractor, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
