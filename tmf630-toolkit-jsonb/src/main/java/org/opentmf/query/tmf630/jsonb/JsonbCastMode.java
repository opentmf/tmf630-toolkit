package org.opentmf.query.tmf630.jsonb;

/**
 * What a typed comparison or sort does with a stored value its cast cannot read
 * ({@code opentmf.tmf630.jsonb.cast-mode}, design §6).
 */
public enum JsonbCastMode {

  /**
   * The default, as in 3.5.2 and the design's recommended default: the plain cast,
   * {@code (payload->>'ld')::date}. One stored value the cast cannot read ({@code "someday"} in a
   * date field, {@code "abc"} in a number field) fails the whole request.
   */
  STRICT,

  /**
   * Opt-in: the value is read only where PostgreSQL's input function accepts it, {@code CASE WHEN
   * pg_input_is_valid(t, 'date') THEN (t)::date END}, for dates, times, numbers and booleans
   * alike, so an unreadable value is no match in a filter and sorts as a null. Needs PostgreSQL 16
   * or later; an expression index on the plain cast does not serve the guarded expression.
   */
  LENIENT
}
