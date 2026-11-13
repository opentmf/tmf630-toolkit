package org.opentmf.query.tmf630.jsonb.combo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The ledger probe's Mongo root: its samples are the 2026-11-13 compatibility ledger's. */
@Document(CompatLedgerDoc.COLLECTION)
public class CompatLedgerDoc {

  public static final String COLLECTION = "compat_ledger_doc";

  @Id public String id;
  public String s;
  public Integer n;
  public LocalDate ld;
  public String t;
  public ComboStage state;
  public Object o;
  public Map<String, String> m;
  public List<Item> items;

  /** An array element. */
  public static class Item {
    public String b;
    public String name;
    public Object value;
  }
}
