package org.opentmf.query.tmf630.filtering;

/**
 * A parsed attribute-filter parameter key. {@code implicitEq} is true when the operator was not
 * spelled out in the key ({@code ?attr=x}) but assigned by the implicit-EQ fallback — TMF630
 * value-list (comma-OR) splitting applies only in that case; an explicit suffix such as
 * {@code ?attr.eq=x} keeps its raw value as one literal.
 */
public record ParsedParamKey(String fieldPath, TmfOperator operator, boolean implicitEq) {

  public ParsedParamKey(String fieldPath, TmfOperator operator) {
    this(fieldPath, operator, false);
  }
}
