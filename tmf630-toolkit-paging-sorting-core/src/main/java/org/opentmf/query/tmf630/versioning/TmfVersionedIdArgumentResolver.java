package org.opentmf.query.tmf630.versioning;

import java.util.Map;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
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
 *
 * <p><b>Query-parameter fallback for {@code version}.</b> If the parsed path segment
 * does not carry a {@code :(version=X)} suffix, this resolver falls back to the
 * {@code version} query parameter (e.g. {@code /orders/42?version=1}). Precedence rule:
 * the path form wins whenever it carries a version. Callers using
 * {@code /orders/42:(version=1)?version=2} get id=42, version=1 — the query is ignored
 * silently. This is deliberate: declaring {@code TmfVersionedId} on the handler signals
 * "I accept either shape"; a mismatched query is treated as noise, not conflict, so
 * clients can safely include a default {@code version=…} on every call regardless of
 * whether the path pins one.
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
                RequestAttributes.SCOPE_REQUEST);
    String raw = uriVars == null ? null : uriVars.get(pathVarName);
    TmfVersionedId parsed = TmfVersionedId.parse(raw);
    if (parsed.version().isPresent()) {
      return parsed;
    }
    String queryVersion = webRequest.getParameter("version");
    if (queryVersion == null || queryVersion.isBlank()) {
      return parsed;
    }
    return new TmfVersionedId(parsed.id(), Optional.of(queryVersion));
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
