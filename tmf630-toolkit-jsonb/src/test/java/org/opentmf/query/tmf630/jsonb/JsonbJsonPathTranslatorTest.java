package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

class JsonbJsonPathTranslatorTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");
  private final JsonbJsonPathTranslator translator =
      new JsonbJsonPathTranslator(extractor, "payload");

  @Test
  @DisplayName("simple equality filter is tested with the @? operator, quotes converted")
  void simpleEquality() {
    JsonbClause c = translator.translate("$[?(@.status == 'Pending')]");
    assertThat(c.sql()).isEqualTo("payload @?? ?::jsonpath");
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
  @DisplayName("array correlation @.arr[?(...)] as a predicate rewrites to exists(@.arr[*] ? (...))")
  void arrayCorrelation() {
    JsonbClause c =
        translator.translate(
            "$[?(@.externalReference[?(@.name == 'REF' && @.id == 'X')])]");
    assertThat(c.params())
        .containsExactly(
            "$ ? (exists(@.externalReference[*] ? (@.name == \"REF\" && @.id == \"X\")))");
  }

  @Test
  @DisplayName("array correlation next to &&, || and ! is wrapped in exists(...) at each place")
  void arrayCorrelationInsideBooleanOperators() {
    assertThat(
            translator
                .translate("$[?(@.status == 'X' || @.items[?(@.state == 'Y')] && @.p > 1)]")
                .params())
        .containsExactly(
            "$ ? (@.status == \"X\" || exists(@.items[*] ? (@.state == \"Y\")) && @.p > 1)");
    assertThat(translator.translate("$[?(!(@.items[?(@.state == 'Y')]))]").params())
        .containsExactly("$ ? (!(exists(@.items[*] ? (@.state == \"Y\"))))");
    assertThat(translator.translate("$[?(@.items[?(@.state == 'Y')] )]").params())
        .containsExactly("$ ? (exists(@.items[*] ? (@.state == \"Y\")) )");
  }

  @Test
  @DisplayName("array filter used as an operand (accessor + comparison after it) stays unwrapped")
  void arrayFilterAsOperandIsNotWrapped() {
    assertThat(translator.translate("$[?(@.items[?(@.state == 'Y')].id == 'i1')]").params())
        .containsExactly("$ ? (@.items[*] ? (@.state == \"Y\").id == \"i1\")");
    // a filter after the operand's accessor makes the whole chain a predicate again
    assertThat(
            translator.translate("$[?(@.items[?(@.state == 'Y')].tags[?(@ == 't')])]").params())
        .containsExactly("$ ? (exists(@.items[*] ? (@.state == \"Y\").tags[*] ? (@ == \"t\")))");
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
  @DisplayName("== null is the configured isnull test, not a comparison with a stored null")
  void nullLiteral() {
    // == null is the isnull test (MISSING_ONLY: the key absent), as on the other backends
    JsonbClause c = translator.translate("$[?(@.optional == null)]");
    assertThat(c.params()).containsExactly("$ ? (!(exists(@.optional)))");
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
  @DisplayName("nested array-match inside array-match composes recursively, one exists per level")
  void nestedArrayCorrelation() {
    JsonbClause c =
        translator.translate(
            "$[?(@.a[?(@.b[?(@.c == 'X')])])]");
    assertThat(c.params())
        .containsExactly("$ ? (exists(@.a[*] ? (exists(@.b[*] ? (@.c == \"X\")))))");
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

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "$[?(@.a == 1) || (@.b == 2)]",
        "$[?(@.a == 1) + (1)]",
        "$[?(@.a == 1).double()]",
        "$[?(@.a == 1))]",
        "$[?((@.a == 1)]",
        "$[?(@.items[?(@.s == 'x'))])]"
      })
  @DisplayName("a body that leaves the $[?(...)] wrapper, or leaves a parenthesis open, is refused")
  void bodyLeavingTheWrapperIsRefused(String filter) {
    JsonbJsonPathTranslator regex = new JsonbJsonPathTranslator(extractor, "payload", true);
    assertThatThrownBy(() -> regex.translate(filter))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("parentheses");
  }

  @Test
  @DisplayName("parentheses inside string and regex literals do not count toward the wrapper")
  void parenthesesInsideLiteralsStayAccepted() {
    JsonbJsonPathTranslator regex = new JsonbJsonPathTranslator(extractor, "payload", true);
    assertThat(regex.translate("$[?(@.name == 'a) || (b')]").params())
        .containsExactly("$ ? (@.name == \"a) || (b\")");
    assertThat(regex.translate("$[?(@.name == \"x(\")]").params())
        .containsExactly("$ ? (@.name == \"x(\")");
    assertThat(regex.translate("$[?(@.name =~ /^a\\)/)]").params())
        .containsExactly("$ ? (@.name like_regex \"^a\\\\)\")");
    assertThat(regex.translate("$[?(@.name =~ /^(a|b)$/i || (@.n == 1))]").params())
        .containsExactly("$ ? (@.name like_regex \"^(a|b)$\" flag \"i\" || (@.n == 1))");
  }

  @Test
  @DisplayName("filter= in / nin are refused with the core's 400, as on JPA and Mongo")
  void inAndNinAreRefusedAsOnTheOtherBackends() {
    assertThatThrownBy(() -> translator.translate("$[?(@.s in ['a', 'b'])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage("Unsupported token in jsonPath filter near: in ['a', 'b']");
    assertThatThrownBy(() -> translator.translate("$[?(@.s == 'x' && @.t nin ['a'])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage("Unsupported token in jsonPath filter near: nin ['a']");
    // " in " inside a string literal is a value, not the operator
    assertThat(translator.translate("$[?(@.s == 'x in [y]')]").params())
        .containsExactly("$ ? (@.s == \"x in [y]\")");
  }

  @Test
  @DisplayName("the in / nin refusal does not fire inside a literal or on a field named like them")
  void inRefusalSparesLiteralsAndFieldNames() {
    assertThat(translator.translate("$[?(@.s == 'made in germany')]").params())
        .containsExactly("$ ? (@.s == \"made in germany\")");
    assertThat(translator.translate("$[?(@.s == \"nin ['a']\")]").params())
        .containsExactly("$ ? (@.s == \"nin ['a']\")");
    JsonbJsonPathTranslator regex = new JsonbJsonPathTranslator(extractor, "payload", true);
    assertThat(regex.translate("$[?(@.s =~ / in /)]").params())
        .containsExactly("$ ? (@.s like_regex \" in \")");
    assertThat(translator.translate("$[?(@.info == 'x')]").params())
        .containsExactly("$ ? (@.info == \"x\")");
    assertThat(translator.translate("$[?(@.ninja == 'x')]").params())
        .containsExactly("$ ? (@.ninja == \"x\")");
    assertThat(translator.translate("$[?(@.in == 'x' && @.nin == 'y')]").params())
        .containsExactly("$ ? (@.in == \"x\" && @.nin == \"y\")");
  }

  @Test
  @DisplayName("!@.field, == null and != null follow isnull MISSING_ONLY: the key absent / present")
  void nullTestsUnderMissingOnly() {
    assertThat(translator.translate("$[?(!@.s)]").params())
        .containsExactly("$ ? (!(exists(@.s)))");
    assertThat(translator.translate("$[?(@.s == null)]").params())
        .containsExactly("$ ? (!(exists(@.s)))");
    assertThat(translator.translate("$[?(@.s != null)]").params())
        .containsExactly("$ ? (exists(@.s))");
    assertThat(translator.translate("$[?(@.a.b == null && ! @.c)]").params())
        .containsExactly("$ ? (!(exists(@.a.b)) && !(exists(@.c)))");
    assertThat(translator.translate("$[?(@.items[?(!@.b)])]").params())
        .containsExactly("$ ? (exists(@.items[*] ? (!(exists(@.b)))))");
    // != stays a comparison when its right side is not the null literal
    assertThat(translator.translate("$[?(@.s != 'x')]").params())
        .containsExactly("$ ? (@.s != \"x\")");
  }

  @Test
  @DisplayName("under isnull NULLISH a JSON null is no value too")
  void nullTestsUnderNullish() {
    JsonbJsonPathTranslator nullish =
        new JsonbJsonPathTranslator(extractor, "payload", false, IsnullSemantics.NULLISH);
    assertThat(nullish.translate("$[?(!@.s)]").params())
        .containsExactly("$ ? (!(exists(@.s ? (@ != null))))");
    assertThat(nullish.translate("$[?(@.s == null)]").params())
        .containsExactly("$ ? (!(exists(@.s ? (@ != null))))");
    assertThat(nullish.translate("$[?(@.s != null)]").params())
        .containsExactly("$ ? (exists(@.s ? (@ != null)))");
  }

  @Test
  @DisplayName("negating an array match is refused with the core's 400")
  void negatedArrayMatchIsRefused() {
    assertThatThrownBy(() -> translator.translate("$[?(!@.items[?(@.b == 'x')])]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessage("Negation of array-match expressions is not supported in jsonPath filter.");
  }

  @Test
  @DisplayName("=~ /pattern/ lowers to like_regex with the pattern as a double-quoted literal")
  void regexOperator() {
    JsonbJsonPathTranslator regex = new JsonbJsonPathTranslator(extractor, "payload", true);
    assertThat(regex.translate("$[?(@.status =~ /^Resol.*?/)]").params())
        .containsExactly("$ ? (@.status like_regex \"^Resol.*?\")");
    assertThat(regex.translate("$[?(@.status =~ /^resol.*/i)]").params())
        .containsExactly("$ ? (@.status like_regex \"^resol.*\" flag \"i\")");
    // backslashes survive as regex escapes: the jsonpath literal doubles them
    assertThat(regex.translate("$[?(@.name =~ /a\\.b/)]").params())
        .containsExactly("$ ? (@.name like_regex \"a\\\\.b\")");
    // a literal slash inside the pattern, and =~ inside || and inside array correlation
    assertThat(
            regex.translate("$[?(@.href =~ /\\/v4\\// || @.items[?(@.id =~ /^X/i)])]").params())
        .containsExactly(
            "$ ? (@.href like_regex \"\\\\/v4\\\\/\""
                + " || exists(@.items[*] ? (@.id like_regex \"^X\" flag \"i\")))");
  }

  @Test
  @DisplayName("=~ is gated by regex.enabled and accepts only the i flag")
  void regexOperatorGates() {
    assertThatThrownBy(() -> translator.translate("$[?(@.status =~ /x/)]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("disabled");
    JsonbJsonPathTranslator regex = new JsonbJsonPathTranslator(extractor, "payload", true);
    assertThatThrownBy(() -> regex.translate("$[?(@.status =~ /x/m)]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("supported: i");
    assertThatThrownBy(() -> regex.translate("$[?(@.status =~ 'x')]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("/pattern/");
    assertThatThrownBy(() -> regex.translate("$[?(@.status =~ /x)]"))
        .isInstanceOf(TmfFilteringException.class)
        .hasMessageContaining("Unterminated");
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
