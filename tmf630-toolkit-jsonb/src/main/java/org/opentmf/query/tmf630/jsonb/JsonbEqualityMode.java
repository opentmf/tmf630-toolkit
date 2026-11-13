package org.opentmf.query.tmf630.jsonb;

/**
 * How {@code EQ} and {@code IN} compare on a JSONB field ({@code opentmf.tmf630.jsonb.equality}).
 */
public enum JsonbEqualityMode {

  /**
   * The default, as in 3.5.2: the extracted text, cast by the field's declared type,
   * {@code payload->>'state' = ?} or {@code (payload->>'n')::bigint = ?}. An expression index on
   * that exact expression serves it (design §2.2). A value stored in another JSON type is read
   * through its text: a String field holding the number {@code 42} is found by {@code 42}.
   */
  EXTRACTION,

  /**
   * Opt-in: containment, {@code payload @> ?::jsonb}, served by a GIN index on the payload, and
   * type-exact as on the Mongo backend: a value stored in another JSON type is not found. An
   * expression index made for equality no longer serves it.
   */
  CONTAINMENT
}
