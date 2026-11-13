package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;

/** Row entity of the combination suite; the suite creates and fills its table with SQL. */
@Entity
@Table(name = ComboSplitRow.TABLE)
@Tmf630JsonbBacked(domainType = ComboSplitDomain.class)
public class ComboSplitRow {

  public static final String TABLE = "combo_split_row";

  public static final String CHILD_TABLE = "combo_split_item";

  @Id private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;

  public String getId() {
    return id;
  }

  public String getPayload() {
    return payload;
  }
}
