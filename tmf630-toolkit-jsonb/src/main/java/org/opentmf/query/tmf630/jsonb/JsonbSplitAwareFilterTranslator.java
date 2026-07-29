package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Combinator;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.Decomposition;
import org.opentmf.query.tmf630.filtering.TmfSplitFilterDecomposer.SplitClauseRef;

/**
 * Phase (c.2 + c.3, full) — split-aware wrapper around {@link JsonbJsonPathTranslator}.
 * Routes filters that target a {@code @Tmf630JsonbSplitCollection} field to a correlated
 * {@code EXISTS} subquery on the child table, since after the split the field no longer
 * lives in the parent's payload. Parent-only filters delegate unchanged to the base
 * translator. Compound filters mixing parent-only clauses and split correlations at the
 * top level are decomposed via {@link TmfSplitFilterDecomposer} and re-composed as
 * {@code (parent-jsonpath) AND EXISTS(...) [AND EXISTS(...)]} in SQL.
 *
 * <p>Supported filter shapes:
 *
 * <ul>
 *   <li><b>Parent-only</b>, e.g. {@code $[?(@.status == 'X')]} — delegates to base
 *       translator, emits {@code jsonb_path_exists(payload, ?)} on the parent payload.
 *   <li><b>Top-level array correlation into any split field</b>, e.g.
 *       {@code $[?(@.items[?(@.state == 'X')])]} — rewrites to
 *       {@code EXISTS (SELECT 1 FROM <childTable> WHERE parent_id = <parentTable>.id AND
 *       jsonb_path_exists(payload, ?))}.
 *   <li><b>Top-level {@code &&} conjunction mixing parent-only clauses and split
 *       correlations</b>, e.g. {@code $[?(@.status == 'X' && @.items[?(@.state == 'Y')])]}
 *       — each half is translated in isolation and AND'd via {@link JsonbClause#and}.
 *       Multiple split correlations (same or different fields) each become their own
 *       {@code EXISTS} subquery.
 * </ul>
 *
 * <p>Rejected via {@link TmfSplitFilterDecomposer} with an actionable message
 * (deferred to a follow-up cut):
 *
 * <ul>
 *   <li>Top-level disjunctions ({@code ||}) mixing parent-side and split-side clauses.
 *   <li>Nested boolean subgroups containing a split reference.
 * </ul>
 *
 * <p>Also rejected here:
 *
 * <ul>
 *   <li>Non-default child payload column names — the delegate emits
 *       {@code jsonb_path_exists(payload, ...)} against a column literally named
 *       {@code payload}.
 * </ul>
 *
 * <p>Assumes the parent row's PK column is named {@code id} (TMF convention).
 */
public class JsonbSplitAwareFilterTranslator {

  private static final String DEFAULT_PAYLOAD_COLUMN = "payload";

  private final JsonbJsonPathTranslator delegate;
  private final JsonbEntityRegistry registry;

  public JsonbSplitAwareFilterTranslator(
      JsonbJsonPathTranslator delegate, JsonbEntityRegistry registry) {
    this.delegate = delegate;
    this.registry = registry;
  }

  /**
   * Translates a {@code filter=} expression into a {@link JsonbClause}, applying
   * split-awareness for the given parent type. When the parent has no split
   * collections, this is equivalent to calling the base translator directly.
   */
  public JsonbClause translate(Class<?> parentType, String filterExpression) {
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(parentType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + parentType.getName()));

    if (metadata.splitCollections().isEmpty()) {
      return delegate.translate(filterExpression);
    }

    Set<String> splitFieldNames =
        metadata.splitCollections().stream()
            .map(JsonbSplitCollectionMetadata::fieldName)
            .collect(Collectors.toSet());
    Decomposition decomposition =
        TmfSplitFilterDecomposer.decompose(filterExpression, splitFieldNames);
    if (decomposition.isEmpty()) {
      return delegate.translate(filterExpression);
    }

    boolean isOr = decomposition.combinator() == Combinator.OR;
    // AND-identity is TRUE (any conjunct suppressed), OR-identity is FALSE.
    JsonbClause combined = isOr ? JsonbClause.alwaysFalse() : JsonbClause.alwaysTrue();
    Optional<String> parentOnlyFilter = decomposition.parentOnlyFilter();
    if (parentOnlyFilter.isPresent()) {
      JsonbClause parentClause = delegate.translate(parentOnlyFilter.get());
      combined = isOr ? combined.or(parentClause) : combined.and(parentClause);
    }
    for (SplitClauseRef ref : decomposition.splitClauses()) {
      JsonbSplitCollectionMetadata split = requireSplitByFieldName(metadata, ref.splitFieldName());
      JsonbClause splitClause = buildExistsSubquery(metadata, split, ref.innerPredicate());
      combined = isOr ? combined.or(splitClause) : combined.and(splitClause);
    }
    return combined;
  }

  private JsonbClause buildExistsSubquery(
      JsonbEntityMetadata metadata,
      JsonbSplitCollectionMetadata split,
      String innerPredicate) {
    if (!DEFAULT_PAYLOAD_COLUMN.equals(split.payloadColumn())) {
      throw new TmfFilteringException(
          "Split-aware filter translation currently supports only the default child"
              + " payload column name ('payload'); split field '"
              + split.fieldName()
              + "' uses '"
              + split.payloadColumn()
              + "'. Non-default child payload columns land in a follow-up c.x cut.");
    }
    JsonbClause innerClause = delegate.translate("$[?(" + innerPredicate + ")]");
    String existsSql =
        "EXISTS (SELECT 1 FROM "
            + split.childTable()
            + " WHERE "
            + split.parentIdColumn()
            + " = "
            + metadata.tableName()
            + ".id AND "
            + innerClause.sql()
            + ")";
    return JsonbClause.of(existsSql, innerClause.params().toArray());
  }

  private static JsonbSplitCollectionMetadata requireSplitByFieldName(
      JsonbEntityMetadata metadata, String fieldName) {
    return metadata.splitCollections().stream()
        .filter(s -> s.fieldName().equals(fieldName))
        .findFirst()
        .orElseThrow(
            () ->
                new TmfFilteringException(
                    "No split collection metadata for field '"
                        + fieldName
                        + "' on "
                        + metadata.domainType().getSimpleName()));
  }
}
