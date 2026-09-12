package org.opentmf.query.tmf630.filtering;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.paging.TmfSortKeyValidator;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedMethod;

/**
 * Validates plain sort keys against the handler's filter root — the type named by a sibling
 * {@code @QuerydslPredicate(root = ...)} or {@code @Tmf630JsonbFilter(root = ...)} parameter of
 * the same handler method. A key must resolve through the same {@link FieldPathResolver} the
 * filter grammar uses — if you can filter on it, you can sort on it — or the request is rejected
 * with a {@link TmfPagingException}: a 400 with the same body as the nesting and allowlist
 * rejections. Nesting stays governed by {@code allow-nested-sort-properties}, which the sort
 * parser enforces before this validator is asked.
 *
 * <p>A handler without a filter-root parameter is NOT validated: no root is known there, and the
 * request behaves exactly as before 3.2.0 (an unknown key still fails inside Spring Data on JPA,
 * and is still ignored by Mongo and by the JSONB executor).
 *
 * <p>Parameters are read through Spring's {@link AnnotatedMethod}, so a root declared on an API
 * interface parameter counts. The root is resolved once per handler method.
 */
public class FilterRootSortKeyValidator implements TmfSortKeyValidator {

  private final FieldPathResolver fieldPathResolver;
  private final List<Tmf630FilterRootLocator> rootLocators;
  private final Map<Method, Optional<Class<?>>> rootsByHandler = new ConcurrentHashMap<>();

  public FilterRootSortKeyValidator(
      FieldPathResolver fieldPathResolver, List<Tmf630FilterRootLocator> rootLocators) {
    this.fieldPathResolver = fieldPathResolver;
    this.rootLocators = List.copyOf(rootLocators);
  }

  @Override
  public void validate(MethodParameter parameter, List<String> plainKeys) {
    Method handler = parameter.getMethod();
    if (plainKeys.isEmpty() || handler == null) {
      return;
    }
    rootsByHandler
        .computeIfAbsent(handler, this::locateRoot)
        .ifPresent(root -> plainKeys.forEach(key -> requireDeclared(root, key)));
  }

  private Optional<Class<?>> locateRoot(Method handler) {
    return Arrays.stream(new AnnotatedMethod(handler).getMethodParameters())
        .map(this::rootOf)
        .flatMap(Optional::stream)
        .findFirst();
  }

  private Optional<Class<?>> rootOf(MethodParameter candidate) {
    return rootLocators.stream()
        .map(locator -> locator.locate(candidate))
        .flatMap(Optional::stream)
        .findFirst();
  }

  private void requireDeclared(Class<?> root, String key) {
    try {
      fieldPathResolver.resolve(root, key, true);
    } catch (TmfFilteringException ex) {
      throw new TmfPagingException("Unknown sort property: " + key, ex);
    }
  }
}
