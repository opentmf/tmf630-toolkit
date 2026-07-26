package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/**
 * Row entity for the version-resolver IT. Row primary key is a surrogate
 * {@code row_id} so multiple rows can share the same logical id (which lives inside
 * the payload as {@code id}). Composite uniqueness {@code (logical_id, version)}
 * would live at the DB schema level; the IT doesn't enforce it since the resolver
 * doesn't depend on it.
 */
@Entity
@Table(name = "versioned_offering_row")
@Tmf630JsonbBacked(domainType = VersionedOffering.class)
public class VersionedOfferingRow {

  @Id private String rowId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;

  public String getRowId() {
    return rowId;
  }

  public void setRowId(String rowId) {
    this.rowId = rowId;
  }

  public String getPayload() {
    return payload;
  }

  public void setPayload(String payload) {
    this.payload = payload;
  }
}
