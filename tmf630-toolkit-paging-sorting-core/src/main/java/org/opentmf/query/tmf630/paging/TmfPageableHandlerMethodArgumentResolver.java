package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.lang.Nullable;
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

  public TmfPageableHandlerMethodArgumentResolver(Tmf630PagingSettings settings) {
    this.settings = settings;
    this.sortParser =
        new TmfSortParser(
            settings.sortAllowlist(), settings.allowNestedSortProperties());
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Pageable.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Pageable resolveArgument(
      MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    String offsetRaw = webRequest.getParameter(OFFSET);
    String limitRaw = webRequest.getParameter(LIMIT);
    String[] sortArray = webRequest.getParameterValues(SORT);
    List<String> sortParams = sortArray == null ? List.of() : List.of(sortArray);
    Sort parsedSort = sortParser.parse(sortParams);

    if (!isTmfMode(offsetRaw, limitRaw)) {
      Pageable fallback = super.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
      if (parsedSort.isUnsorted()) {
        return fallback;
      }
      return PageRequest.of(fallback.getPageNumber(), fallback.getPageSize(), parsedSort);
    }

    long offset = parseLong(offsetRaw, 0L, OFFSET);
    int limit = parseInt(limitRaw, settings.defaultLimit(), LIMIT);
    limit = Math.min(limit, settings.maxLimit());
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0");
    }

    return new OffsetLimitPageRequest(offset, limit, parsedSort);
  }

  private boolean isTmfMode(String offsetRaw, String limitRaw) {
    return StringUtils.hasText(offsetRaw) || StringUtils.hasText(limitRaw);
  }

  private long parseLong(String raw, long defaultValue, String fieldName) {
    if (!StringUtils.hasText(raw)) {
      return defaultValue;
    }
    try {
      long value = Long.parseLong(raw);
      if (value < 0) {
        throw new IllegalArgumentException(fieldName + " must be >= 0");
      }
      return value;
    } catch (NumberFormatException e) {
      if (settings.strictMode()) {
        throw new IllegalArgumentException(fieldName + " must be numeric", e);
      }
      return defaultValue;
    }
  }

  private int parseInt(String raw, int defaultValue, String fieldName) {
    if (!StringUtils.hasText(raw)) {
      return defaultValue;
    }
    try {
      int value = Integer.parseInt(raw);
      if (value <= 0) {
        throw new IllegalArgumentException(fieldName + " must be > 0");
      }
      return value;
    } catch (NumberFormatException e) {
      if (settings.strictMode()) {
        throw new IllegalArgumentException(fieldName + " must be numeric", e);
      }
      return defaultValue;
    }
  }
}
