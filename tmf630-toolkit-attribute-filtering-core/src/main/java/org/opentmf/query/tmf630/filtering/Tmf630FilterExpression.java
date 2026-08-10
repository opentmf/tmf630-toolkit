package org.opentmf.query.tmf630.filtering;

import java.util.List;
import org.opentmf.query.tmf630.filtering.config.CombineMode;

/**
 * The backend-neutral parse result of one request's TMF-630 filter surface, produced by
 * {@link Tmf630FilterParser}: the attribute clauses (in query-string order), the raw
 * {@code filter=} JsonPath expression, and the resolved
 * {@code filter.combineWithAttributes} mode.
 *
 * @param attributeClauses parsed attribute clauses in parameter order; terminals AND them
 * @param jsonPathFilter the raw {@code filter=} value exactly as received — {@code null}
 *     when the parameter is absent, possibly blank when present-but-empty (terminals must
 *     still hand a blank value to their JsonPath machinery so enabled/disabled checks
 *     fire identically)
 * @param combineWithAttributes how the JsonPath side combines with the attribute side;
 *     {@code AND} when the parameter is absent
 */
public record Tmf630FilterExpression(
    List<Tmf630AttributeClause> attributeClauses,
    String jsonPathFilter,
    CombineMode combineWithAttributes) {

  public boolean hasJsonPathFilter() {
    return jsonPathFilter != null;
  }
}
