package org.opentmf.query.tmf630.jsonb.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbColumn;

/**
 * A JSONB-backed row with two declared columns: {@code sendTime} in {@code sent_at} (the partition
 * key of the IT's table) and {@code owner}. The table is created with SQL by the IT.
 */
@Entity
@Table(name = DeclaredMessageRow.TABLE)
@Tmf630JsonbBacked(domainType = DeclaredMessage.class)
public class DeclaredMessageRow {

  public static final String TABLE = "declared_message";

  @Id private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;

  @Tmf630JsonbColumn(path = "sendTime")
  @Column(name = "sent_at")
  private OffsetDateTime sentAt;

  @Tmf630JsonbColumn(path = "owner")
  private String owner;

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

  public OffsetDateTime getSentAt() {
    return sentAt;
  }

  public void setSentAt(OffsetDateTime sentAt) {
    this.sentAt = sentAt;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }
}
