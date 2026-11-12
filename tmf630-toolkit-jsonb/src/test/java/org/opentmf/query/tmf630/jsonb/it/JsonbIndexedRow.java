package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/**
 * Non-split row entity whose table the index-plan IT seeds in bulk and indexes with {@code GIN
 * (payload jsonb_path_ops)}. Rows are written with SQL, never through Hibernate; the entity
 * exists so the table is created and registered as a {@code @Tmf630JsonbBacked} root.
 */
@Entity
@Table(name = JsonbIndexedRow.TABLE)
@Tmf630JsonbBacked(domainType = JsonbIndexedDomain.class)
public class JsonbIndexedRow {

  public static final String TABLE = "jsonb_indexed_row";

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
