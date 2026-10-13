package org.opentmf.query.tmf630.exception;

import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.web.bind.ServletRequestBindingException;

/**
 * Thrown by {@code Tmf630UndeclaredQueryParameterInterceptor} before a handler runs when the
 * request carries a query parameter the handler does not declare, under {@code
 * opentmf.tmf630.undeclared-query-params.on-undeclared: REJECT}.
 *
 * <p>A {@link ServletRequestBindingException} — the {@code 400} every other unbindable request
 * parameter gets — so a service that renders request-binding errors as {@code ProblemDetail}
 * (its {@code ResponseEntityExceptionHandler}, or Spring's default resolver) keeps its own shape,
 * with this message as the problem's {@code detail}. The message names the undeclared
 * parameter(s), the route and what the route takes:
 *
 * <pre>
 * unknown query parameter 'variantKey' — GET /templates/content takes only docId, lang, variant
 * </pre>
 *
 * <p>The three parts are also the problem detail's message arguments ({@code {0}} the
 * undeclared names, {@code {1}} the route, {@code {2}} the declared names), for a service that
 * localises the detail under the default code — {@code problemDetail.} followed by this class's
 * fully qualified name.
 * {@code Tmf630UndeclaredQueryParameterExceptionHandler} answers it in the toolkit's TMF-630
 * error shape instead, unless switched off.
 */
public final class TmfUndeclaredQueryParameterException extends ServletRequestBindingException {

  private final String route;
  private final SortedSet<String> undeclared;
  private final SortedSet<String> declared;

  /**
   * @param route the HTTP method and route pattern, e.g. {@code GET /templates/content}
   * @param undeclared the names the request carries and the handler does not declare
   * @param declared the names the handler declares
   */
  public TmfUndeclaredQueryParameterException(
      String route, Set<String> undeclared, Set<String> declared) {
    super(
        describe(route, undeclared, declared),
        null,
        new Object[] {
          String.join(", ", new TreeSet<>(undeclared)),
          route,
          String.join(", ", new TreeSet<>(declared))
        });
    this.route = route;
    this.undeclared = Collections.unmodifiableSortedSet(new TreeSet<>(undeclared));
    this.declared = Collections.unmodifiableSortedSet(new TreeSet<>(declared));
    getBody().setDetail(getMessage());
  }

  /** The HTTP method and route pattern of the refused request. */
  public String getRoute() {
    return route;
  }

  /** The names the request carried and the handler does not declare, sorted. */
  public SortedSet<String> getUndeclared() {
    return undeclared;
  }

  /** The names the handler declares, sorted. */
  public SortedSet<String> getDeclared() {
    return declared;
  }

  /**
   * The refusal's message — also what {@code WARN} mode logs, without building the exception.
   */
  public static String describe(String route, Set<String> undeclared, Set<String> declared) {
    String unknown =
        undeclared.size() == 1
            ? "unknown query parameter '" + undeclared.iterator().next() + "'"
            : "unknown query parameters "
                + new TreeSet<>(undeclared).stream()
                    .map(name -> "'" + name + "'")
                    .collect(Collectors.joining(", "));
    String takes =
        declared.isEmpty()
            ? "takes no query parameters"
            : "takes only " + String.join(", ", new TreeSet<>(declared));
    return unknown + " — " + route + " " + takes;
  }
}
