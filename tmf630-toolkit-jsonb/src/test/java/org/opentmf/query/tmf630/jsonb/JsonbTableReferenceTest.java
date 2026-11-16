package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonbTableReferenceTest {

  /** Every form PostgreSQL accepts as a table reference keeps working. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "message",
        "communication_message",
        "\"CommunicationMessage\"",
        "\"My Table\"",
        "\"a;b\"",
        "\"a--b\"",
        "\"a(b)\"",
        "\"say \"\"hi\"\"\"",
        "message$v2",
        "mesaj_ğ",
        "dnms.message",
        "\"dnms\".\"message\"",
        "dnms.\"Message\"",
        "catalog.dnms.message",
        "a.b.c.d",
        "MessageRow",
        " message ",
        "\tmessage\n",
        " \"Message Row\" ",
        "\"dot.row\"",
        "\"Cat\".\"Dnms\".\"Message\"",
        "`message`",
        "a/b",
        "a-b"
      })
  void aTableReferencePasses(String name) {
    assertThat(JsonbTableReference.problem(name)).isNull();
  }

  static Stream<Arguments> notTableReferences() {
    return Stream.of(
        Arguments.of("message m", "whitespace outside double quotes"),
        Arguments.of("message\tm", "whitespace outside double quotes"),
        Arguments.of("message;drop", "';' outside double quotes"),
        Arguments.of("message--x", "a comment marker outside double quotes"),
        Arguments.of("message/*x*/", "a comment marker outside double quotes"),
        Arguments.of("message(x)", "a parenthesis outside double quotes"),
        Arguments.of("\"dnms\".message m", "whitespace outside double quotes"),
        Arguments.of("\"open;x", "a double quote that is never closed"),
        Arguments.of("\"open", "a double quote that is never closed"),
        Arguments.of("\"message\"m", "text directly after a closing double quote"),
        Arguments.of("\"dnms\".\"message\"m", "text directly after a closing double quote"),
        Arguments.of("message,other", "',' outside double quotes"),
        Arguments.of("message?", "'?' outside double quotes"),
        Arguments.of(" message m ", "whitespace outside double quotes"));
  }

  @ParameterizedTest
  @MethodSource("notTableReferences")
  void whatCannotBeATableReferenceIsNamed(String name, String problem) {
    assertThat(JsonbTableReference.problem(name)).isEqualTo(problem);
  }
}
