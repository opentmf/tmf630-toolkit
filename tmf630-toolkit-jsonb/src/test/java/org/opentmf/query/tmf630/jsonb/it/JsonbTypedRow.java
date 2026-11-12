package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/**
 * Row entity of the equality-parity IT. Rows are written with SQL, never through Hibernate; the
 * entity exists so the table is created and registered as a {@code @Tmf630JsonbBacked} root.
 */
@Entity
@Table(name = JsonbTypedRow.TABLE)
@Tmf630JsonbBacked(domainType = JsonbTypedDomain.class)
public class JsonbTypedRow {

  public static final String TABLE = "jsonb_typed_row";

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
