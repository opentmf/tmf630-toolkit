package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;

/**
 * Validates the PLAIN keys of a {@code sort=} request against whatever the handler method binds
 * as its resource root. Every toolkit resolver that parses {@code sort=} ({@code Pageable},
 * {@code TmfRichPageable}, {@code Sort}, {@code TmfSort}) calls it after the grammar checks —
 * the {@code allow-nested-sort-properties} switch and {@code sort-allowlist} — have passed.
 * Correlated terms ({@code field[key=value].leaf}, JsonPath) are never handed to it.
 *
 * <p>This module cannot see filter roots: they live in the filtering modules, which depend on
 * this one. The implementation is therefore contributed from above ({@code
 * FilterRootSortKeyValidator} in {@code tmf630-toolkit-attribute-filtering-core}, registered by
 * the attribute-filtering autoconfiguration). Without a contributed validator the resolvers use
 * {@link #NONE}, which accepts every key — the behaviour before 3.2.0. A consumer opts out by
 * declaring its own {@code TmfSortKeyValidator} bean, e.g. {@code NONE}.
 */
@FunctionalInterface
public interface TmfSortKeyValidator {

  /** Accepts every key — the behaviour when no validator is contributed. */
  TmfSortKeyValidator NONE = (parameter, plainKeys) -> {};

  /**
   * Validates plain sort keys for one handler parameter.
   *
   * @param parameter the handler parameter being resolved; its method identifies the handler
   * @param plainKeys the plain sort keys in request order, without direction signs
   * @throws TmfPagingException for a key the handler's root does not declare (rendered as 400)
   */
  void validate(MethodParameter parameter, List<String> plainKeys);

  /** Validates the properties of a plain Spring Data {@link Sort}. */
  default void validate(MethodParameter parameter, Sort sort) {
    validate(parameter, sort.stream().map(Sort.Order::getProperty).toList());
  }

  /** Validates the {@link TmfSort#plainKeys() plain keys} of a rich sort. */
  default void validate(MethodParameter parameter, TmfSort sort) {
    validate(parameter, sort.plainKeys());
  }
}
