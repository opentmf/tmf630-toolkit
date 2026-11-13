package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/** The JSONB row of {@link CompatElementSamples}, written with SQL, never through Hibernate. */
@Entity
@Table(name = CompatElementRow.TABLE)
@Tmf630JsonbBacked(domainType = CompatElementDomain.class)
public class CompatElementRow {

  static final String TABLE = "compat_element_row";

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
