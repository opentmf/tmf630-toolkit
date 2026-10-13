package org.opentmf.query.tmf630.queryparams;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * static resources, not functional endpoints). A TMF-630 list is judged for Spring's {@code
 * page}/{@code size} alone, which its filter grammar would let pass ({@link
 * Tmf630DeclaredQueryParameters#listRefusals}, 3.5.1). Other open handlers — catch-alls without a
 * stated vocabulary, {@link Tmf630AcceptsAnyQueryParameter} — pass untouched.
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
    Set<String> sent = request.getParameterMap().keySet();
    Optional<Set<String>> declared = declaredQueryParameters.declaredBy(handlerMethod);
    if (declared.isPresent()) {
      Set<String> undeclared = new TreeSet<>(sent);
      undeclared.removeAll(declared.get());
      judge(request, undeclared, declared.get());
    } else {
      Set<String> refused = new TreeSet<>(sent);
      refused.retainAll(declaredQueryParameters.listRefusals(handlerMethod));
      judge(request, refused, null);
    }
    return true;
  }

  /**
   * Reports, then warns about or refuses, the undeclared names of one request.
   *
   * @param declared the judged handler's declared names, or {@code null} for a TMF-630 list
   */
  private void judge(
      HttpServletRequest request, Set<String> undeclared, @Nullable Set<String> declared)
      throws TmfUndeclaredQueryParameterException {
    if (undeclared.isEmpty()) {
      return;
    }
    String pattern = pattern(request);
    listener.undeclared(request.getMethod(), pattern, undeclared, behavior);
    String route = request.getMethod() + " " + pattern;
    if (behavior == UndeclaredQueryParamBehavior.REJECT) {
      throw declared == null
          ? TmfUndeclaredQueryParameterException.onList(route, undeclared)
          : new TmfUndeclaredQueryParameterException(route, undeclared, declared);
    }
    if (log.isWarnEnabled()) {
      String message =
          declared == null
              ? TmfUndeclaredQueryParameterException.describeOnList(route, undeclared)
              : TmfUndeclaredQueryParameterException.describe(route, undeclared, declared);
      log.warn("{}", CONTROL.matcher(message).replaceAll("?"));
    }
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
