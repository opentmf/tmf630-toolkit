package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which translated SQL/JSON paths a {@code GIN (payload jsonb_path_ops)} index has a key for. A
 * path counted as keyed must really have one (otherwise a generic plan reads the whole index), so
 * every doubtful shape counts as keyless and keeps the function form.
 */
class JsonbJsonPathIndexKeyTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$ ? (@.a == \"x\")",
        "$ ? (@.receiver.postalAddress.city == \"City42\")",
        "$ ? (@.priority == 42)",
        "$ ? (@.d == -1.5e3)",
        "$ ? (@.b == true)",
        "$ ? (\"x\" == @.a)",
        "$ ? (@.a == \"x\" || @.a == \"y\")",
        "$ ? (@.a == \"x\" && @.n > 5)",
        "$ ? (@.n > 5 && @.a == \"x\")",
        "$ ? ((@.a == \"x\" || @.b == \"y\") && @.n < 3)",
        "$ ? (@.ls[*] == \"a\")",
        "$ ? (exists(@.c[*] ? (@.name == \"k3\" && @.value == \"v10\")))",
        "$ ? (exists(@.item[*] ? (@.state == \"OPEN\" && exists(@.tag[*] ? (@.code == \"t7\")))))",
        "$ ? (exists(@.c[*] ? (@.name == \"k3\")) || @.a == \"x\")",
        "$ ? (@.c[*] ? (@.name == \"k\").value == \"v\")",
        "$ ? (exists(@.ls[*] ? (@ == \"a\")))",
        "$ ? (@.a == \"x || y\")",
        "$ ? (@.n > 5 && @.s == \"a)(b \\\" c\")",
        "$ ? (@.a==\"x\"&&@.n>5)"
      })
  void keyed(String jsonPath) {
    assertThat(JsonbJsonPathIndexKey.hasIndexKey(jsonPath)).as(jsonPath).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "$ ? (@.n > 5)",
        "$ ? (@.n >= 5 && @.n <= 9)",
        "$ ? (@.a like_regex \"x.*\" flag \"i\")",
        "$ ? (@.a starts with \"x\")",
        "$ ? (@.a != \"x\")",
        "$ ? (!(@.a == \"x\"))",
        "$ ? (@.a == \"x\" || @.n > 5)",
        "$ ? (@.n > 5 || @.a == \"x\")",
        "$ ? (exists(@.a))",
        "$ ? (!(exists(@.a)))",
        "$ ? (exists(@.a ? (@ != null)))",
        "$ ? (exists(@.c[*] ? (@.n > 5)))",
        "$ ? (@.a.type() == \"string\")",
        "$ ? (@.items[3].state == \"x\")",
        "$ ? (@.a == @.b)",
        "$ ? (@.a + 1 == 2)",
        "$ ? (@.c[*] ? (@.name == \"k\").value > 3)",
        "$ ? (@.a == \"x\"",
        "$ ? (@.a == \"x)",
        "$ ? (@.a == \"x\") extra",
        "$.a ? (@.b == \"x\")",
        ""
      })
  void keyless(String jsonPath) {
    assertThat(JsonbJsonPathIndexKey.hasIndexKey(jsonPath)).as(jsonPath).isFalse();
  }
}
