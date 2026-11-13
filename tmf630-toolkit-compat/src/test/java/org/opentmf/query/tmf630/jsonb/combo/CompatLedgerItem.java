package org.opentmf.query.tmf630.jsonb.combo;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An element of {@link CompatLedgerRow#items}. */
@Entity
@Table(name = "compat_ledger_item")
public class CompatLedgerItem {

  @Id @GeneratedValue public Long id;
  public String b;
  @ManyToOne public CompatLedgerRow row;
}
