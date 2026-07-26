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
 * a JSONB payload column, plus a {@code LIMIT ? OFFSET ?} pagination tail. Ships plain
 * dotted terms in this Phase (b.4) sub-milestone; JsonPath and simple-rich grammars
 * flow through separate translators (b.6, deferred).
 *
 * <p>Each term is rendered as:
 *
 * <pre>{@code
 * (payload->>'field')::cast ASC|DESC [NULLS LAST]
 * }</pre>
 *
 * <p>Cast is picked per {@link JsonbCast#forJavaType(Class)}. Nulls-last decoration is
 * driven by the toolkit-wide {@code opentmf.tmf630.paging.nulls-last} property (Phase
 * a.1) supplied at construction. On Postgres, {@code NULLS LAST} is a native SQL
 * modifier so the semantic outcome and the emitted text always agree — no dialect-based
 * elision like Hibernate does for {@code Sort.Order.nullsLast()}.
 */
public class JsonbSortBuilder {

  private final JsonbPathExtractor extractor;
  private final boolean nullsLast;

  public JsonbSortBuilder(JsonbPathExtractor extractor, boolean nullsLast) {
    this.extractor = extractor;
    this.nullsLast = nullsLast;
  }

  /**
   * Builds the {@code ORDER BY} fragment (without the {@code ORDER BY} keyword itself),
   * or an empty string when the sort is unsorted / empty. Callers prepend
   * {@code " ORDER BY "} only when this returns non-empty.
   *
   * @param sort the parsed sort terms; only PLAIN terms are accepted here — rich/JsonPath
   *     terms throw with a clear message pointing at the JsonPath-sort executor (b.6)
   * @param fieldTypeResolver maps a plain sort expression to the Java type of the
   *     corresponding domain-model field, used for cast selection
   */
  public String buildOrderByClause(TmfSort sort, Function<String, Class<?>> fieldTypeResolver) {
    if (sort == null || sort.isEmpty()) {
      return "";
    }
    List<String> pieces = new ArrayList<>(sort.terms().size());
    for (TmfSortTerm term : sort.terms()) {
      if (term.kind() != TmfSortTerm.Kind.PLAIN) {
        throw new TmfPagingException(
            "Non-plain sort terms on JSONB backend require the JsonPath-sort executor"
                + " (Phase b.6, not yet available). Term: "
                + term.expression());
      }
      Class<?> fieldType = fieldTypeResolver.apply(term.expression());
      JsonbCast cast = JsonbCast.forJavaType(fieldType);
      String extraction = extractor.extractAsText(term.expression());
      String casted =
          cast == JsonbCast.TEXT ? extraction : "(" + extraction + ")" + cast.suffix();
      String direction = term.direction().isAscending() ? "ASC" : "DESC";
      String nullsTail = nullsLast ? " NULLS LAST" : "";
      pieces.add(casted + " " + direction + nullsTail);
    }
    return String.join(", ", pieces);
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
