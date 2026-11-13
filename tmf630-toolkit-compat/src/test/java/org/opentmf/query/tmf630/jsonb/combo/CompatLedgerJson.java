package org.opentmf.query.tmf630.jsonb.combo;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** The ledger probe's acceptance-only root (never queried): JSON-typed fields. */
public class CompatLedgerJson {
  public String s;
  public Map<String, String> m;
  public JsonNode j;
  public List<Characteristic> characteristic;

  /** A TMF characteristic: a name and a value of any JSON type. */
  public static class Characteristic {
    public String name;
    public JsonNode value;
  }
}
