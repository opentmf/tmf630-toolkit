package org.opentmf.query.tmf630.jsonb;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

/**
 * Phase (c.2 + c.3, first cut) — split-aware wrapper around {@link JsonbJsonPathTranslator}.
 * Routes filters that target a {@code @Tmf630JsonbSplitCollection} field to a correlated
 * {@code EXISTS} subquery on the child table, since after the split the field no longer
 * lives in the parent's payload. Parent-only filters delegate unchanged to the base
 * translator.
 *
 * <p>Supported filter shapes in this first cut:
 *
 * <ul>
 *   <li><b>Parent-only</b>, e.g. {@code $[?(@.status == 'X')]} — delegates to base
 *       translator, emits {@code jsonb_path_exists(payload, ?)} on the parent payload.
 *   <li><b>Top-level array correlation into ONE split field</b>, e.g.
 *       {@code $[?(@.items[?(@.state == 'X')])]} where {@code items} is a split
 *       collection — rewrites to
 *       {@code EXISTS (SELECT 1 FROM <childTable> WHERE parent_id = <parentTable>.id AND
 *       jsonb_path_exists(payload, ?))}. The inner predicate flows through the base
 *       translator so its full grammar (compound {@code &&}/{@code ||}, quotes, nested
 *       array match on further nested arrays) is available inside the child predicate.
 * </ul>
 *
 * <p>Rejected with clear error (deferred to a follow-up c.2.x / c.3.x cut):
 *
 * <ul>
 *   <li><b>Compound predicates mixing parent and split fields</b> at the top level,
 *       e.g. {@code $[?(@.status == 'X' && @.items[?(@.state == 'Y')])]}. Handling
 *       these requires a real predicate splitter (walk the parsed AST, group by
 *       target-table, recombine with correct SQL logic).
 *   <li><b>Non-default child payload column names.</b> The delegate emits
 *       {@code jsonb_path_exists(payload, ...)} against a column literally named
 *       {@code payload}; a child table with a different name for its payload column
 *       would need per-split translator wiring.
 * </ul>
 *
 * <p>Assumes the parent row's PK column is named {@code id} (TMF convention). The
 * {@code parent_id} column on the child table is configurable per annotation
 * ({@link JsonbSplitCollectionMetadata#parentIdColumn()}); the parent-side reference
 * is hard-coded to {@code <tableName>.id} for the first cut.
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
   * split-awareness for the given domain type. When the domain has no split
   * collections, this is equivalent to calling the base translator directly.
   */
  public JsonbClause translate(Class<?> domainType, String filterExpression) {
    JsonbEntityMetadata metadata =
        registry
            .forDomainType(domainType)
            .orElseThrow(
                () ->
                    new TmfFilteringException(
                        "No @Tmf630JsonbBacked row entity registered for domain type: "
                            + domainType.getName()));

    if (metadata.splitCollections().isEmpty()) {
      return delegate.translate(filterExpression);
    }

    // Try to match top-level array correlation into each split field.
    for (JsonbSplitCollectionMetadata split : metadata.splitCollections()) {
      Matcher matcher = topLevelCorrelationPattern(split.fieldName()).matcher(filterExpression);
      if (matcher.matches()) {
        return buildExistsSubquery(metadata, split, matcher.group(1));
      }
    }

    // Guard: the filter must not reference any split field in an unsupported shape.
    for (JsonbSplitCollectionMetadata split : metadata.splitCollections()) {
      if (referencesSplitField(filterExpression, split.fieldName())) {
        throw new TmfFilteringException(
            "filter= references split-collection field '"
                + split.fieldName()
                + "' outside the supported top-level array-correlation shape"
                + " $[?(@."
                + split.fieldName()
                + "[?(<inner>)])]. Compound predicates mixing parent and split fields"
                + " are not yet supported (deferred to a later Phase c.x cut). For now:"
                + " use the sub-endpoint (GET /{parent}/{id}/{childRoute}) with its own"
                + " filter=, or restructure the URL to a single top-level array-correlation.");
      }
    }

    // No split reference — parent-only filter, delegate unchanged.
    return delegate.translate(filterExpression);
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
    // Flow the inner predicate through the base translator by wrapping it as its own
    // top-level filter — reuses the full JsonPath grammar (compound &&/||, nested
    // string handling, single→double quote conversion, etc.).
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

  /**
   * Matches {@code $[?(@.<field>[?(<inner>)])]} and the two other wrapper variants
   * ({@code [?(...)]} and {@code $.[?(...)]}). Group 1 is the inner predicate.
   */
  private static Pattern topLevelCorrelationPattern(String splitFieldName) {
    // Note: no non-greedy anchoring on the inner predicate — the last )])] must be the
    // outermost close. Postgres SQL/JSON path translation happens further downstream
    // via the delegate translator so we don't need to fully parse the inner here.
    return Pattern.compile(
        "^\\s*(?:\\$\\s*\\.?)?\\s*\\[\\s*\\?\\s*\\(\\s*@\\."
            + Pattern.quote(splitFieldName)
            + "\\s*\\[\\s*\\?\\s*\\((.+)\\)\\s*]\\s*\\)\\s*]\\s*$",
        Pattern.DOTALL);
  }

  /** Loose check for a bare {@code @.fieldName} reference anywhere in the expression. */
  private static boolean referencesSplitField(String expression, String fieldName) {
    return Pattern.compile("@\\." + Pattern.quote(fieldName) + "\\b").matcher(expression).find();
  }
}
