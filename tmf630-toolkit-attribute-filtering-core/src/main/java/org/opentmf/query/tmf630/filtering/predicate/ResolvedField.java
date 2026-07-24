package org.opentmf.query.tmf630.filtering.predicate;

public record ResolvedField(String fieldPath, Class<?> javaType, boolean leafIsCollection) {

  public ResolvedField(String fieldPath, Class<?> javaType) {
    this(fieldPath, javaType, false);
  }
}
