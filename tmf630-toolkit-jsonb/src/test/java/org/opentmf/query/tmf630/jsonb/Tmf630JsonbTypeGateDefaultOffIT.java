package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.query.tmf630.jsonb.it.JsonbGateDomain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * A service that does nothing sees nothing of the type gate: without
 * {@code opentmf.tmf630.jsonb.querydsl-acceptance=true} no {@link JsonbQuerydslAcceptance} bean
 * exists, and the requests the opt-in refuses ({@link Tmf630JsonbTypeGateIT}) are still built
 * into SQL, as in 3.5.2. The cells are the ones the 3.6.0 compatibility ledger lists as answered by
 * 3.5.2; the answers themselves are the compatibility report's to compare, so they are not repeated
 * here.
 *
 * <p>The settings are the opt-in IT's, without the opt-in: the regex cap is 8 there too, so a
 * pattern of 11 characters passing here shows that the cap is not applied by default either.
 */
@SpringBootTest(
    classes = Tmf630JsonbTypeGateIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.attribute-filtering.regex.max-length=8",
      "opentmf.tmf630.attribute-filtering.on-unknown-json-path-field=REJECT"
    })
@ActiveProfiles("test")
class Tmf630JsonbTypeGateDefaultOffIT {

  @Autowired private ApplicationContext context;
  @Autowired private Tmf630JsonbClauseBuilder clauseBuilder;

  @Test
  void noAcceptanceBeanWithoutTheOptIn() {
    assertThat(context.getBeansOfType(JsonbQuerydslAcceptance.class)).isEmpty();
  }

  static Stream<Arguments> answeredByDefault() {
    return Stream.of(
        // U1 to U3: the string operators and regex on a non-string
        Arguments.of("i.eqi", "5"),
        Arguments.of("b.nei", "true"),
        Arguments.of("e.eqi", "RED"),
        Arguments.of("i.contains", "5"),
        Arguments.of("d.startswith", "2"),
        Arguments.of("items.i.contains", "5"),
        Arguments.of("i.like", "5"),
        Arguments.of("b.regex", "true"),
        Arguments.of("i.regexi", "5"),
        // U4: an ordering on an Object field
        Arguments.of("o.gt", "5"),
        // U5: a pattern longer than the core's cap
        Arguments.of("s.regex", "^abcdefghij"),
        // F1, F2: =~ on a non-string, an ordering on an Object field
        Arguments.of("filter", "$[?(@.i =~ /^5/)]"),
        Arguments.of("filter", "$[?(@.o > 5)]"),
        // F5: the regex cap in filter=
        Arguments.of("filter", "$[?(@.s =~ /^abcdefghij/)]"));
  }

  @ParameterizedTest(name = "{0}={1}")
  @MethodSource("answeredByDefault")
  void builtAsBefore(String param, String value) {
    Map<String, String[]> params = Map.of(param, new String[] {value});

    assertThatCode(() -> clauseBuilder.build(JsonbGateDomain.class, params))
        .doesNotThrowAnyException();
  }
}
