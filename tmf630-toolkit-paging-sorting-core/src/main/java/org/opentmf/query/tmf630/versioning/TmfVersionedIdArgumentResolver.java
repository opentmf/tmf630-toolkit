package org.opentmf.query.tmf630.versioning;

import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Binds {@code @PathVariable(...) TmfVersionedId} controller parameters. Reads the
 * raw path variable value and delegates to {@link TmfVersionedId#parse(String)}.
 *
 * <p>The path-variable name is taken from a co-located {@link PathVariable} annotation
 * when present; otherwise the method parameter's compiled name (available under
 * {@code -parameters}, standard for Spring Boot builds) is used.
 */
public class TmfVersionedIdArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return TmfVersionedId.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    String pathVarName = resolvePathVariableName(parameter);
    @SuppressWarnings("unchecked")
    Map<String, String> uriVars =
        (Map<String, String>)
            webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                NativeWebRequest.SCOPE_REQUEST);
    String raw = uriVars == null ? null : uriVars.get(pathVarName);
    return TmfVersionedId.parse(raw);
  }

  private static String resolvePathVariableName(MethodParameter parameter) {
    PathVariable annotation = parameter.getParameterAnnotation(PathVariable.class);
    if (annotation != null) {
      if (!annotation.name().isBlank()) return annotation.name();
      if (!annotation.value().isBlank()) return annotation.value();
    }
    return parameter.getParameterName();
  }
}
