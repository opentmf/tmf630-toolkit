package org.opentmf.query.tmf630.jsonb;

import org.opentmf.query.tmf630.filtering.Tmf630PassThroughNames;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @Tmf630JsonbFilter(root = Domain.class) JsonbClause} controller
 * parameters by handing the request's parameter map to {@link Tmf630JsonbClauseBuilder}.
 * The JSONB counterpart of {@code Tmf630PredicateArgumentResolver}.
 */
public class Tmf630JsonbClauseArgumentResolver implements HandlerMethodArgumentResolver {

  private final Tmf630JsonbClauseBuilder clauseBuilder;

  public Tmf630JsonbClauseArgumentResolver(Tmf630JsonbClauseBuilder clauseBuilder) {
    this.clauseBuilder = clauseBuilder;
  }

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(Tmf630JsonbFilter.class)
        && JsonbClause.class.isAssignableFrom(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Tmf630JsonbFilter annotation = parameter.getParameterAnnotation(Tmf630JsonbFilter.class);
    if (annotation == null) {
      // supportsParameter gates on the annotation; reaching here without it is a wiring bug.
      throw new IllegalStateException(
          "@Tmf630JsonbFilter annotation missing on parameter: " + parameter);
    }
    return clauseBuilder.build(
        annotation.root(), webRequest.getParameterMap(), Tmf630PassThroughNames.of(parameter));
  }
}
