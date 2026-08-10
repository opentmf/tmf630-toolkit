package org.opentmf.query.tmf630.jsonb.it.parity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/** JSONB row entity for the parity IT — payload holds one serialized {@link ParityDomain}. */
@Entity
@Table(name = "parity_row")
@Tmf630JsonbBacked(domainType = ParityDomain.class)
public class ParityRow {

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
