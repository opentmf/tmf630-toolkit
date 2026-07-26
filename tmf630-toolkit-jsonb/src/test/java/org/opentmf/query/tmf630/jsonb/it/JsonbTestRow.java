package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/**
 * Test row entity. Payload stored as {@code String} with {@code @JdbcTypeCode(SqlTypes.JSON)} —
 * Hibernate's built-in {@code JsonJdbcType} handles the JDBC binding directly, avoiding
 * the {@code JsonNode}-needs-a-jackson-mapper-module setup that would pull in
 * extra deps just for this IT. The b.5 executor reads the same column via
 * {@code JdbcClient} and deserialises through the toolkit's own {@link
 * com.fasterxml.jackson.databind.ObjectMapper}.
 */
@Entity
@Table(name = "jsonb_test_row")
@Tmf630JsonbBacked(domainType = JsonbTestDomain.class)
public class JsonbTestRow {

  @Id private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }
}
