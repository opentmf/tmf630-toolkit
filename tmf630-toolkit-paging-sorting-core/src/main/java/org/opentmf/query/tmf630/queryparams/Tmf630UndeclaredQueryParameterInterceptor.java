package org.opentmf.query.tmf630.queryparams;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.opentmf.query.tmf630.exception.TmfUndeclaredQueryParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * A read refuses — or reports — a query parameter its handler does not declare.
 *
 * <p>Spring MVC ignores a query parameter no handler argument binds. On a hand-written read that
 * turns a misspelt parameter into a confident wrong answer: {@code ?variantKey=winback} on a
 * route that takes {@code variant} answers {@code 200} with the default variant; {@code
 * ?rendtion=customer} serves the masked rendition. The TMF-630 filter grammar already refuses
 * unknown names on list handlers; this interceptor covers every other handler, judging the
 * request against {@link Tmf630DeclaredQueryParameters} — the handler's own signature.
 *
 * <p>Only {@code GET} and {@code HEAD} are judged, and only {@link HandlerMethod} handlers (not
 * static resources, not functional endpoints). Open handlers — TMF-630 lists, catch-alls without
 * a stated vocabulary, {@link Tmf630AcceptsAnyQueryParameter} — pass untouched.
 *
 * <p>Under {@link UndeclaredQueryParamBehavior#WARN} the request is served and the undeclared
 * names are logged and reported to the {@link Tmf630UndeclaredQueryParameterListener}; under
 * {@link UndeclaredQueryParamBehavior#REJECT} it is refused from {@code preHandle} with a {@link
 * TmfUndeclaredQueryParameterException}, so no argument resolver or handler runs.
 */
public class Tmf630UndeclaredQueryParameterInterceptor implements HandlerInterceptor {

  private static final Logger log =
      LoggerFactory.getLogger(Tmf630UndeclaredQueryParameterInterceptor.class);

  /** Caller-chosen names are logged; control characters must not forge log lines. */
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

  private final Tmf630DeclaredQueryParameters declaredQueryParameters;
  private final UndeclaredQueryParamBehavior behavior;
  private final Tmf630UndeclaredQueryParameterListener listener;

  public Tmf630UndeclaredQueryParameterInterceptor(
      Tmf630DeclaredQueryParameters declaredQueryParameters,
      UndeclaredQueryParamBehavior behavior,
      Tmf630UndeclaredQueryParameterListener listener) {
    this.declaredQueryParameters = Objects.requireNonNull(declaredQueryParameters);
    this.behavior = Objects.requireNonNull(behavior);
    this.listener = Objects.requireNonNull(listener);
  }

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler)
      throws TmfUndeclaredQueryParameterException {
    if (behavior == UndeclaredQueryParamBehavior.IGNORE
        || !(handler instanceof HandlerMethod handlerMethod)
        || !isRead(request.getMethod())) {
      return true;
    }
    Optional<Set<String>> declared = declaredQueryParameters.declaredBy(handlerMethod);
    if (declared.isEmpty()) {
      return true;
    }
    Set<String> undeclared = new TreeSet<>(request.getParameterMap().keySet());
    undeclared.removeAll(declared.get());
    if (undeclared.isEmpty()) {
      return true;
    }
    String pattern = pattern(request);
    listener.undeclared(request.getMethod(), pattern, undeclared, behavior);
    String route = request.getMethod() + " " + pattern;
    if (behavior == UndeclaredQueryParamBehavior.REJECT) {
      throw new TmfUndeclaredQueryParameterException(route, undeclared, declared.get());
    }
    if (log.isWarnEnabled()) {
      String message =
          TmfUndeclaredQueryParameterException.describe(route, undeclared, declared.get());
      log.warn("{}", CONTROL.matcher(message).replaceAll("?"));
    }
    return true;
  }

  private static boolean isRead(String httpMethod) {
    return "GET".equals(httpMethod) || "HEAD".equals(httpMethod);
  }

  /** The matched route pattern, {@code /templates/{id}}, falling back to the request URI. */
  private static String pattern(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? request.getRequestURI() : pattern.toString();
  }
}
