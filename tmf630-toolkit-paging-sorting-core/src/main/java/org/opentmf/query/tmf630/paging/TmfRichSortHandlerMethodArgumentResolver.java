package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class TmfRichSortHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String SORT = "sort";

  private final TmfSortParser sortParser;

  public TmfRichSortHandlerMethodArgumentResolver(TmfSortParser sortParser) {
    this.sortParser = sortParser;
  }

  @Override
  public boolean supportsParameter(@NonNull MethodParameter parameter) {
    return TmfSort.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      @NonNull MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      @NonNull NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    String[] sortArray = webRequest.getParameterValues(SORT);
    List<String> sortParams = sortArray == null ? List.of() : List.of(sortArray);
    return sortParser.parseRich(sortParams);
  }
}
