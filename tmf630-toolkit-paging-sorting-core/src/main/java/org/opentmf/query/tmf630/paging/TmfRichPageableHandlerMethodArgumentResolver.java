package org.opentmf.query.tmf630.paging;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class TmfRichPageableHandlerMethodArgumentResolver
    implements HandlerMethodArgumentResolver {

  private static final String OFFSET = "offset";
  private static final String LIMIT = "limit";
  private static final String SORT = "sort";

  private final Tmf630PagingSettings settings;
  private final TmfSortParser sortParser;

  public TmfRichPageableHandlerMethodArgumentResolver(Tmf630PagingSettings settings) {
    this.settings = settings;
    this.sortParser =
        new TmfSortParser(
            settings.sortAllowlist(),
            settings.allowNestedSortProperties(),
            settings.nullsLast());
  }

  @Override
  public boolean supportsParameter(@NonNull MethodParameter parameter) {
    return TmfRichPageable.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  @NonNull
  public TmfRichPageable resolveArgument(
      @NonNull MethodParameter parameter,
      @Nullable ModelAndViewContainer mavContainer,
      @NonNull NativeWebRequest webRequest,
      @Nullable WebDataBinderFactory binderFactory) {
    String offsetRaw = webRequest.getParameter(OFFSET);
    String limitRaw = webRequest.getParameter(LIMIT);
    String[] sortArray = webRequest.getParameterValues(SORT);
    List<String> sortParams = sortArray == null ? List.of() : List.of(sortArray);

    TmfSort tmfSort = sortParser.parseRich(sortParams);
    Sort plainSort =
        tmfSort.requiresAggregation()
            ? Sort.unsorted()
            : tmfSort.toPlainSort(settings.nullsLast());

    Pageable pageable;
    if (StringUtils.hasText(offsetRaw) || StringUtils.hasText(limitRaw)) {
      long offset = OffsetLimitParser.parseOffset(offsetRaw, settings);
      int limit = OffsetLimitParser.parseLimit(limitRaw, settings);
      pageable = new OffsetLimitPageRequest(offset, limit, plainSort);
    } else {
      pageable = PageRequest.of(0, settings.defaultLimit(), plainSort);
    }

    return new TmfRichPageable(pageable, tmfSort);
  }
}
