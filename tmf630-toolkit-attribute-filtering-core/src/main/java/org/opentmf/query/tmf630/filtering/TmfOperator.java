package org.opentmf.query.tmf630.filtering;

public enum TmfOperator {
  EQ("eq"),
  NE("ne"),
  EQI("eqi"),
  NEI("nei"),
  GT("gt"),
  GTE("gte"),
  LT("lt"),
  LTE("lte"),
  BETWEEN("between"),
  IN("in"),
  NIN("nin"),
  IS_NULL("isnull"),
  IS_NOT_NULL("isnotnull"),
  LIKE("like"),
  LIKEI("likei"),
  CONTAINS("contains"),
  CONTAINSI("containsi"),
  STARTS_WITH("startswith"),
  STARTS_WITHI("startswithi"),
  ENDS_WITH("endswith"),
  ENDS_WITHI("endswithi"),
  REGEX("regex"),
  REGEXI("regexi");

  private final String suffix;

  TmfOperator(String suffix) {
    this.suffix = suffix;
  }

  public String suffix() {
    return suffix;
  }

  public boolean isNoValueOperator() {
    return this == IS_NULL || this == IS_NOT_NULL;
  }

  public boolean isMultiValueOperator() {
    return this == IN || this == NIN || this == BETWEEN;
  }
}
