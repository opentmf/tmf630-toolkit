package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        "\"Cat\".\"Dnms\".\"Message\"",
        "`message`",
        "a/b",
        "a-b"
      })
  void aTableReferencePasses(String name) {
    assertThat(JsonbTableReference.problem(name)).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "message m|whitespace outside double quotes",
        "message\tm|whitespace outside double quotes",
        "message;drop|';' outside double quotes",
        "message--x|a comment marker outside double quotes",
        "message/*x*/|a comment marker outside double quotes",
        "message(x)|a parenthesis outside double quotes",
        "\"dnms\".message m|whitespace outside double quotes",
        "\"open;x|';' outside double quotes"
      })
  void whatCannotBeATableReferenceIsNamed(String name, String problem) {
    assertThat(JsonbTableReference.problem(name)).isEqualTo(problem);
  }
}
