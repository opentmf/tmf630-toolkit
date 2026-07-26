package org.opentmf.query.tmf630.jsonb;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.Pageable;

/**
 * Translates a {@link TmfSort} into a Postgres SQL {@code ORDER BY} fragment against
 * a JSONB payload column, plus a {@code LIMIT ? OFFSET ?} pagination tail. Handles
 * three sort-term kinds:
 *
 * <ul>
 *   <li>{@code PLAIN} — rendered as {@code (payload->>'field')::cast DIR [NULLS LAST]}
 *       (Phase b.4).
 *   <li>{@code SIMPLE_RICH} / {@code JSONPATH} — routed through
 *       {@link JsonbCorrelatedSortTranslator}, rendered as
 *       {@code ((jsonb_path_query_first(payload, ?::jsonpath)) #>> '{}')::cast DIR
 *       [NULLS LAST]} with the translated SQL/JSON path bound as a JDBC parameter
 *       (Phase b.6). The {@code #>> '{}'} step strips JSON quoting from scalar
 *       results so the cast operates on raw text.
 * </ul>
 *
 * <p>Cast is picked per {@link JsonbCast#forJavaType(Class)}. Nulls-last decoration is
 * driven by the toolkit-wide {@code opentmf.tmf630.paging.nulls-last} property (Phase
 * a.1) supplied at construction. On Postgres, {@code NULLS LAST} is a native SQL
 * modifier so the semantic outcome and the emitted text always agree — no dialect-based
 * elision like Hibernate does for {@code Sort.Order.nullsLast()}.
 */
public class JsonbSortBuilder {

  private final JsonbPathExtractor extractor;
  private final JsonbCorrelatedSortTranslator correlatedTranslator;
  private final String payloadColumn;
  private final boolean nullsLast;

  public JsonbSortBuilder(JsonbPathExtractor extractor, boolean nullsLast) {
    this(extractor, new JsonbCorrelatedSortTranslator(), "payload", nullsLast);
  }

  public JsonbSortBuilder(
      JsonbPathExtractor extractor,
      JsonbCorrelatedSortTranslator correlatedTranslator,
      String payloadColumn,
      boolean nullsLast) {
    this.extractor = extractor;
    this.correlatedTranslator = correlatedTranslator;
    this.payloadColumn = payloadColumn;
    this.nullsLast = nullsLast;
  }

  /**
   * Builds the {@code ORDER BY} fragment (without the {@code ORDER BY} keyword itself)
   * plus its JDBC parameters, wrapped in a {@link JsonbClause}. Returns
   * {@link JsonbClause#alwaysTrue()}-equivalent (empty sql, no params) when the sort
   * is unsorted / empty. Callers prepend {@code " ORDER BY "} only when the sql is
   * non-empty.
   *
   * @param sort the parsed sort terms
   * @param fieldTypeResolver maps a sort expression (plain or the leaf of a correlated
   *     term) to the Java type of the corresponding domain-model field, used for cast
   *     selection
   */
  public JsonbClause buildOrderByClause(
      TmfSort sort, Function<String, Class<?>> fieldTypeResolver) {
    if (sort == null || sort.isEmpty()) {
      return JsonbClause.of("");
    }
    List<String> pieces = new ArrayList<>(sort.terms().size());
    List<Object> params = new ArrayList<>();
    for (TmfSortTerm term : sort.terms()) {
      switch (term.kind()) {
        case PLAIN -> pieces.add(plainTermFragment(term, fieldTypeResolver));
        case SIMPLE_RICH, JSONPATH ->
            pieces.add(correlatedTermFragment(term, fieldTypeResolver, params));
        default ->
            throw new TmfPagingException(
                "Unsupported sort term kind: " + term.kind() + " for " + term.expression());
      }
    }
    return JsonbClause.of(String.join(", ", pieces), params.toArray());
  }

  private String plainTermFragment(
      TmfSortTerm term, Function<String, Class<?>> fieldTypeResolver) {
    Class<?> fieldType = fieldTypeResolver.apply(term.expression());
    JsonbCast cast = JsonbCast.forJavaType(fieldType);
    String extraction = extractor.extractAsText(term.expression());
    String casted =
        cast == JsonbCast.TEXT ? extraction : "(" + extraction + ")" + cast.suffix();
    return casted + " " + directionKeyword(term) + nullsTail();
  }

  private String correlatedTermFragment(
      TmfSortTerm term, Function<String, Class<?>> fieldTypeResolver, List<Object> params) {
    String jsonPath = correlatedTranslator.translate(term.expression());
    // Resolve the leaf field's Java type for cast selection. The resolver may not
    // recognise the compound expression — try the leaf name (last dotted segment)
    // as a fallback, then default to TEXT.
    Class<?> fieldType = fieldTypeResolver.apply(term.expression());
    if (fieldType == null) {
      fieldType = fieldTypeResolver.apply(extractLeafFieldName(term.expression()));
    }
    JsonbCast cast = JsonbCast.forJavaType(fieldType);
    String extraction =
        "((jsonb_path_query_first(" + payloadColumn + ", ?::jsonpath)) #>> '{}')";
    String casted = cast == JsonbCast.TEXT ? extraction : extraction + cast.suffix();
    params.add(jsonPath);
    return casted + " " + directionKeyword(term) + nullsTail();
  }

  private static String extractLeafFieldName(String expression) {
    int lastDot = expression.lastIndexOf('.');
    return lastDot < 0 ? expression : expression.substring(lastDot + 1);
  }

  private static String directionKeyword(TmfSortTerm term) {
    return term.direction().isAscending() ? "ASC" : "DESC";
  }

  private String nullsTail() {
    return nullsLast ? " NULLS LAST" : "";
  }

  /**
   * Returns a {@code LIMIT ? OFFSET ?} fragment plus the two JDBC params for a paged
   * request, or a two-empty tuple for {@link Pageable#isUnpaged()}.
   */
  public JsonbClause buildPagingClause(Pageable pageable) {
    if (pageable == null || pageable.isUnpaged()) {
      return JsonbClause.of("", new Object[0]);
    }
    return JsonbClause.of(
        "LIMIT ? OFFSET ?", pageable.getPageSize(), pageable.getOffset());
  }
}
