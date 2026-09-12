package org.opentmf.query.tmf630.paging;

import java.lang.reflect.Method;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

public class TmfPageableHandlerMethodArgumentResolver extends PageableHandlerMethodArgumentResolver {

  private static final String OFFSET = "offset";
  private static final String LIMIT = "limit";
  private static final String SORT = "sort";

  private final Tmf630PagingSettings settings;
  private final TmfSortParser sortParser;
  private final TmfSortKeyValidator sortKeyValidator;

  public TmfPageableHandlerMethodArgumentResolver(Tmf630PagingSettings settings) {
    this(settings, TmfSortKeyValidator.NONE);
  }

  public TmfPageableHandlerMethodArgumentResolver(
      Tmf630PagingSettings settings, TmfSortKeyValidator sortKeyValidator) {
    this.settings = settings;
    this.sortKeyValidator = sortKeyValidator;
    // Propagate the toolkit settings into the Spring Data parent, which owns the
    // non-TMF path (no offset/limit params). Without these, the no-params fallback is
    // Spring's hard-coded PageRequest.of(0, 20) — not the configured default-limit —
    // and a Spring-grammar ?size= is capped by Spring's own 2000, not max-limit.
    setFallbackPageable(PageRequest.of(0, settings.defaultLimit()));
    setMaxPageSize(settings.maxLimit());
    this.sortParser =
        new TmfSortParser(
            settings.sortAllowlist(),
            settings.allowNestedSortProperties(),
            settings.nullsLast());
  }

  @Override
  public boolean supportsParameter(@NonNull MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    // Defer TmfRichPageable resolution to TmfRichPageableHandlerMethodArgumentResolver.
    return Pageable.class.isAssignableFrom(type) && !TmfRichPageable.class.isAssignableFrom(type);
  }

  @Override
  @NonNull
  public Pageable resolveArgument(
      @NonNull MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      @NonNull NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    String offsetRaw = webRequest.getParameter(OFFSET);
    String limitRaw = webRequest.getParameter(LIMIT);
    String[] sortArray = webRequest.getParameterValues(SORT);
    List<String> sortParams = sortArray == null ? List.of() : List.of(sortArray);
    Sort parsedSort;
    if (methodAlsoBindsTmfSort(parameter)) {
      // The handler method has a separate TmfSort parameter that owns correlated-sort
      // term handling. Pageable should not 400 on those terms — instead, expose only
      // the plain subset (or unsorted when every term is correlated). The controller
      // is expected to drive the actual sort via its TmfSort parameter.
      // (For methods using TmfRichPageable, this branch is irrelevant — that resolver
      // takes precedence via supportsParameter above.)
      TmfSort rich = sortParser.parseRich(sortParams);
      parsedSort =
          rich.requiresAggregation() ? Sort.unsorted() : rich.toPlainSort(settings.nullsLast());
    } else {
      parsedSort = sortParser.parse(sortParams);
    }

    sortKeyValidator.validate(parameter, parsedSort);

    if (!isTmfMode(offsetRaw, limitRaw)) {
      Pageable fallback = super.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
      if (parsedSort.isUnsorted()) {
        return fallback;
      }
      return PageRequest.of(fallback.getPageNumber(), fallback.getPageSize(), parsedSort);
    }

    long offset = OffsetLimitParser.parseOffset(offsetRaw, settings);
    int limit = OffsetLimitParser.parseLimit(limitRaw, settings);

    return new OffsetLimitPageRequest(offset, limit, parsedSort);
  }

  private boolean isTmfMode(String offsetRaw, String limitRaw) {
    return StringUtils.hasText(offsetRaw) || StringUtils.hasText(limitRaw);
  }

  private static boolean methodAlsoBindsTmfSort(MethodParameter parameter) {
    Method method = parameter.getMethod();
    if (method == null) {
      return false;
    }
    for (Class<?> paramType : method.getParameterTypes()) {
      if (TmfSort.class.isAssignableFrom(paramType)) {
        return true;
      }
    }
    return false;
  }

}
