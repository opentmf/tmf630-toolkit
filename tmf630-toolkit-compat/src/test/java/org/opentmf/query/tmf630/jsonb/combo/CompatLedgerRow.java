package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** The ledger probe's JPA root, with a joined collection. */
@Entity
@Table(name = "compat_ledger_row")
public class CompatLedgerRow {

  @Id
  @Column(length = 40)
  public String id;

  public String s;
  public Integer n;
  public LocalDate ld;
  public String t;

  @Enumerated(EnumType.STRING)
  public ComboStage state;

  @OneToMany(mappedBy = "row", cascade = CascadeType.ALL)
  public List<CompatLedgerItem> items = new ArrayList<>();
}
