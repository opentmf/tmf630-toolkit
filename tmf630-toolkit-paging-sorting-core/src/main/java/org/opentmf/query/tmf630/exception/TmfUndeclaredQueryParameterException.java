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
 * <p>On a TMF-630 list — judged for Spring's {@code page} and {@code size} only, which its filter
 * grammar would let pass — the refusal comes from {@link #onList} and says how the list pages:
 *
 * <pre>
 * unknown query parameter 'page' — GET /sampleRecord is a TMF-630 list,
 *     which pages with offset and limit
 * </pre>
 *
 * <p>The three parts are also the problem detail's message arguments ({@code {0}} the
 * undeclared names, {@code {1}} the route, {@code {2}} the declared names — empty on a list),
 * for a service that localises the detail under the default code — {@code problemDetail.}
 * followed by this class's fully qualified name. {@code
 * Tmf630UndeclaredQueryParameterExceptionHandler} answers it in the toolkit's TMF-630
 * error shape instead, unless switched off.
 */
public final class TmfUndeclaredQueryParameterException extends ServletRequestBindingException {

  private static final String LIST_PAGING = "is a TMF-630 list, which pages with offset and limit";

  private final String route;
  private final SortedSet<String> undeclared;
  private final SortedSet<String> declared;
  private final boolean listRoute;

  /**
   * @param route the HTTP method and route pattern, e.g. {@code GET /templates/content}
   * @param undeclared the names the request carries and the handler does not declare
   * @param declared the names the handler declares
   */
  public TmfUndeclaredQueryParameterException(
      String route, Set<String> undeclared, Set<String> declared) {
    this(describe(route, undeclared, declared), route, undeclared, declared, false);
  }

  private TmfUndeclaredQueryParameterException(
      String message,
      String route,
      Set<String> undeclared,
      Set<String> declared,
      boolean listRoute) {
    super(
        message,
        null,
        new Object[] {
          String.join(", ", new TreeSet<>(undeclared)),
          route,
          String.join(", ", new TreeSet<>(declared))
        });
    this.route = route;
    this.undeclared = Collections.unmodifiableSortedSet(new TreeSet<>(undeclared));
    this.declared = Collections.unmodifiableSortedSet(new TreeSet<>(declared));
    this.listRoute = listRoute;
    getBody().setDetail(getMessage());
  }

  /**
   * The refusal on a TMF-630 list: Spring's {@code page}/{@code size}, which its filter grammar
   * would let pass (3.5.1).
   *
   * @param route the HTTP method and route pattern, e.g. {@code GET /sampleRecord}
   * @param undeclared the names refused
   */
  public static TmfUndeclaredQueryParameterException onList(String route, Set<String> undeclared) {
    return new TmfUndeclaredQueryParameterException(
        describeOnList(route, undeclared), route, undeclared, Set.of(), true);
  }

  /** The HTTP method and route pattern of the refused request. */
  public String getRoute() {
    return route;
  }

  /** The names the request carried and the handler does not declare, sorted. */
  public SortedSet<String> getUndeclared() {
    return undeclared;
  }

  /** The names the handler declares, sorted; empty on a TMF-630 list. */
  public SortedSet<String> getDeclared() {
    return declared;
  }

  /** Whether the refused route is a TMF-630 list — see {@link #onList}. */
  public boolean isListRoute() {
    return listRoute;
  }

  /**
   * The refusal's message — also what {@code WARN} mode logs, without building the exception.
   */
  public static String describe(String route, Set<String> undeclared, Set<String> declared) {
    String takes =
        declared.isEmpty()
            ? "takes no query parameters"
            : "takes only " + String.join(", ", new TreeSet<>(declared));
    return unknown(undeclared) + " — " + route + " " + takes;
  }

  /** The list refusal's message — also what {@code WARN} mode logs for a list. */
  public static String describeOnList(String route, Set<String> undeclared) {
    return unknown(undeclared) + " — " + route + " " + LIST_PAGING;
  }

  private static String unknown(Set<String> undeclared) {
    return undeclared.size() == 1
        ? "unknown query parameter '" + undeclared.iterator().next() + "'"
        : "unknown query parameters "
            + new TreeSet<>(undeclared).stream()
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(", "));
  }
}
