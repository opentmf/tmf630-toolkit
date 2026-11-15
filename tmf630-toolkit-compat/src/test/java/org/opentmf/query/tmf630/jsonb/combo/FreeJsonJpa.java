package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The JPA twin: a {@code Map} field mapped to a JSON column; rows inserted with SQL. */
@Entity
@Table(name = FreeJsonJpa.TABLE)
public class FreeJsonJpa {

  static final String TABLE = "free_json_jpa";

  @Id
  @Column(length = 80)
  public String id;

  @JdbcTypeCode(SqlTypes.JSON)
  public Map<String, Object> m;
}
