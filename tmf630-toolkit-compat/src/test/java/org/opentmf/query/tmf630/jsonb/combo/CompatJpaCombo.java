package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The JPA twin of {@link ComboDomain}'s top-level scalar fields: one typed column per kind a
 * column can hold. Only the cells a typed column can store exist here (a value of the declared
 * type, or none): a JPA column cannot hold a number in a String field, an object, a map or a list,
 * so those kinds and the nested and array positions have no JPA cell.
 */
@Entity
@Table(name = "compat_jpa_combo")
public class CompatJpaCombo {

  @Id
  @Column(length = 80)
  public String id;

  public String s;
  public Integer i;
  public Long l;

  @Column(precision = 20, scale = 4)
  public BigDecimal d;

  public Double f;
  public Boolean b;
  public LocalDate ld;
  public OffsetDateTime t;

  @Enumerated(EnumType.STRING)
  public ComboColor e;

  @Enumerated(EnumType.STRING)
  public ComboStage w;
}
