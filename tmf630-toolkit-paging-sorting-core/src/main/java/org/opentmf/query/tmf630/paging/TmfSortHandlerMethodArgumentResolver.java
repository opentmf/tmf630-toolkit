package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class TmfSortHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String SORT = "sort";

  private final TmfSortParser sortParser;

  public TmfSortHandlerMethodArgumentResolver(TmfSortParser sortParser) {
    this.sortParser = sortParser;
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
    return sortParser.parse(sortParams);
  }
}
