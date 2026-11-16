package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbBacked;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbColumn;

/**
 * The declared twin of {@link ComboRow}: the same payloads, and every scalar of
 * {@link DeclaredComboDomain}, at the top level and nested, also in a declared column. Written
 * with SQL ({@link DeclaredColumnParityIT}), never through Hibernate.
 */
@Entity
@Table(name = DeclaredComboRow.TABLE)
@Tmf630JsonbBacked(domainType = DeclaredComboDomain.class)
@SuppressWarnings("unused")
public class DeclaredComboRow {

  public static final String TABLE = "declared_combo_row";

  /** The kinds a column copies: key, SQL type. */
  static final String[][] COLUMNS = {
    {"s", "text"},
    {"i", "integer"},
    {"l", "bigint"},
    {"d", "numeric"},
    {"f", "double precision"},
    {"b", "boolean"},
    {"ld", "date"},
    {"t", "timestamptz"},
    {"e", "text"},
    {"w", "text"},
  };

  @Id private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  private String payload;

  @Tmf630JsonbColumn(path = "s")
  @Column(name = "top_s")
  private String topS;

  @Tmf630JsonbColumn(path = "n.s")
  @Column(name = "n_s")
  private String nestedS;

  @Tmf630JsonbColumn(path = "i")
  @Column(name = "top_i")
  private Integer topI;

  @Tmf630JsonbColumn(path = "n.i")
  @Column(name = "n_i")
  private Integer nestedI;

  @Tmf630JsonbColumn(path = "l")
  @Column(name = "top_l")
  private Long topL;

  @Tmf630JsonbColumn(path = "n.l")
  @Column(name = "n_l")
  private Long nestedL;

  @Tmf630JsonbColumn(path = "d")
  @Column(name = "top_d")
  private BigDecimal topD;

  @Tmf630JsonbColumn(path = "n.d")
  @Column(name = "n_d")
  private BigDecimal nestedD;

  @Tmf630JsonbColumn(path = "f")
  @Column(name = "top_f")
  private Double topF;

  @Tmf630JsonbColumn(path = "n.f")
  @Column(name = "n_f")
  private Double nestedF;

  @Tmf630JsonbColumn(path = "b")
  @Column(name = "top_b")
  private Boolean topB;

  @Tmf630JsonbColumn(path = "n.b")
  @Column(name = "n_b")
  private Boolean nestedB;

  @Tmf630JsonbColumn(path = "ld")
  @Column(name = "top_ld")
  private LocalDate topLD;

  @Tmf630JsonbColumn(path = "n.ld")
  @Column(name = "n_ld")
  private LocalDate nestedLD;

  @Tmf630JsonbColumn(path = "t")
  @Column(name = "top_t")
  private OffsetDateTime topT;

  @Tmf630JsonbColumn(path = "n.t")
  @Column(name = "n_t")
  private OffsetDateTime nestedT;

  @Tmf630JsonbColumn(path = "e")
  @Column(name = "top_e")
  private String topE;

  @Tmf630JsonbColumn(path = "n.e")
  @Column(name = "n_e")
  private String nestedE;

  @Tmf630JsonbColumn(path = "w")
  @Column(name = "top_w")
  private String topW;

  @Tmf630JsonbColumn(path = "n.w")
  @Column(name = "n_w")
  private String nestedW;
}
