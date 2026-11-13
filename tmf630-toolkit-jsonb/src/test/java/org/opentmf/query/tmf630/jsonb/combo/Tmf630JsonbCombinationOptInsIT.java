package org.opentmf.query.tmf630.jsonb.combo;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * The combination suite's second run, with every JSONB opt-in on: {@code equality=containment} and
 * {@code cast-mode=lenient}. Its expected findings are {@code combination/expected-opt-ins.tsv}; the
 * defaults run is {@link Tmf630JsonbCombinationIT}.
 */
@SpringBootTest(
    classes = Tmf630JsonbCombinationIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      // the QueryDSL reference runs on a neutral root, whose nested-path policy is the JPA one
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa=true",
      "opentmf.tmf630.jsonb.equality=containment",
      "opentmf.tmf630.jsonb.cast-mode=lenient"
    })
class Tmf630JsonbCombinationOptInsIT extends Tmf630JsonbCombinationIT {

  @Override
  protected String mode() {
    return "opt-ins";
  }
}
