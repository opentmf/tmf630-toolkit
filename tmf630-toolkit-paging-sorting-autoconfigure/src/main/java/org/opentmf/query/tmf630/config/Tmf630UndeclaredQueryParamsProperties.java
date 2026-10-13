package org.opentmf.query.tmf630.config;

import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.queryparams.UndeclaredQueryParamBehavior;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code opentmf.tmf630.undeclared-query-params.*} — the guard against query parameters a
 * handler does not declare. Its own prefix, like {@code query-limits.*}: it protects every
 * mapping, not paged ones.
 */
@ConfigurationProperties(prefix = "opentmf.tmf630.undeclared-query-params")
public class Tmf630UndeclaredQueryParamsProperties {

  /**
   * What a read does with a query parameter its handler does not declare: IGNORE (Spring's own
   * behaviour, the guard is not registered), WARN (served, logged and counted) or REJECT (400).
   */
  private UndeclaredQueryParamBehavior onUndeclared = UndeclaredQueryParamBehavior.IGNORE;

  /**
   * Handler-argument types — or annotations — that read nothing from the query string, beyond
   * the built-in ones (path variables, headers, body, cookies, attributes, principal, response,
   * locale). An argument the guard does not recognise leaves its whole handler unjudged.
   */
  private List<Class<?>> nonQueryArguments = new ArrayList<>();

  private final ExceptionHandlerSettings exceptionHandler = new ExceptionHandlerSettings();

  public UndeclaredQueryParamBehavior getOnUndeclared() {
    return onUndeclared;
  }

  public void setOnUndeclared(UndeclaredQueryParamBehavior onUndeclared) {
    this.onUndeclared = onUndeclared;
  }

  public List<Class<?>> getNonQueryArguments() {
    return nonQueryArguments;
  }

  public void setNonQueryArguments(List<Class<?>> nonQueryArguments) {
    this.nonQueryArguments = nonQueryArguments;
  }

  public ExceptionHandlerSettings getExceptionHandler() {
    return exceptionHandler;
  }

  /** {@code opentmf.tmf630.undeclared-query-params.exception-handler.*}. */
  public static class ExceptionHandlerSettings {

    /**
     * Whether the toolkit answers a refusal with its TMF-630 error body. Switch off in a service
     * that renders request-binding errors as ProblemDetail: the refusal is a
     * ServletRequestBindingException whose detail names the parameter(s).
     */
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }
}
