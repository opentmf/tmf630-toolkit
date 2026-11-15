package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/** The JSONB row of {@link FreeJsonDomain}, written with SQL, never through Hibernate. */
@Entity
@Table(name = FreeJsonRow.TABLE)
@Tmf630JsonbBacked(domainType = FreeJsonDomain.class)
public class FreeJsonRow {

  static final String TABLE = "free_json_row";

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
