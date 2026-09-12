package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class TmfSortHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String SORT = "sort";

  private final TmfSortParser sortParser;
  private final TmfSortKeyValidator sortKeyValidator;

  public TmfSortHandlerMethodArgumentResolver(TmfSortParser sortParser) {
    this(sortParser, TmfSortKeyValidator.NONE);
  }

  public TmfSortHandlerMethodArgumentResolver(
      TmfSortParser sortParser, TmfSortKeyValidator sortKeyValidator) {
    this.sortParser = sortParser;
    this.sortKeyValidator = sortKeyValidator;
  }

  @Override
  public boolean supportsParameter(@NonNull MethodParameter parameter) {
    return Sort.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      @NonNull MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      @NonNull NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    String[] sortArray = webRequest.getParameterValues(SORT);
    List<String> sortParams = sortArray == null ? List.of() : List.of(sortArray);
    Sort sort = sortParser.parse(sortParams);
    sortKeyValidator.validate(parameter, sort);
    return sort;
  }
}
