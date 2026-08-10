package org.opentmf.query.tmf630.filtering;

import java.util.List;

/**
 * One parsed attribute-filter clause from the TMF-630 URL grammar — backend-neutral: field
 * path, operator and split string values, with no predicate construction, field resolution
 * or type coercion applied. Terminals ({@code Tmf630PredicateArgumentResolver} for
 * QueryDSL, {@code Tmf630JsonbClauseBuilder} for JSONB) turn this into their native
 * predicate form.
 *
 * <p>{@code valueGroups} preserves the two-level TMF-630 value structure: the outer list
 * has one group per repeated parameter occurrence ({@code ?attr=a&attr=b}); each inner
 * list holds that occurrence's escape-aware split elements ({@code ?attr=a,b} under
 * implicit-EQ list splitting). Terminals must OR the elements <em>within</em> a group and
 * combine the groups per {@code Tmf630FilterSettings.combineRepeatedValues()}; multi-value
 * operators ({@code in}/{@code nin}/{@code between}) flatten all groups into one value
 * list instead. No-value operators carry an empty {@code valueGroups}.
 *
 * <p>{@code rawKey} is the normalized parameter key (post encoded-operator rewrite) —
 * terminals should use it verbatim in error messages so REJECT diagnostics match the
 * request as the caller spelled it.
 *
 * @param rawKey normalized parameter key, for diagnostics
 * @param fieldPath dotted field path (equal to {@code rawKey} for implicit-EQ keys)
 * @param operator the resolved TMF operator
 * @param implicitEq true when the operator came from the implicit-EQ fallback, not an
 *     explicit suffix
 * @param valueGroups split values; outer per parameter occurrence, inner per list element.
 *     Elements may be {@code null} (a {@code null} raw value passes through for the value
 *     converter's own null handling).
 */
public record Tmf630AttributeClause(
    String rawKey,
    String fieldPath,
    TmfOperator operator,
    boolean implicitEq,
    List<List<String>> valueGroups) {}
