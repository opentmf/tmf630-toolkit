package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/**
 * Parent-side row entity for the split-collection IT. Payload holds the parent fields
 * only ({@code id}, {@code status}); the {@code items} collection lives in
 * {@code split_order_item} instead.
 */
@Entity
@Table(name = "split_order_row")
@Tmf630JsonbBacked(domainType = SplitOrderDomain.class)
public class SplitOrderRow {

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
