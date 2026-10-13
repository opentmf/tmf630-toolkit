package org.opentmf.query.tmf630.queryparams;

/**
 * What {@link Tmf630UndeclaredQueryParameterInterceptor} does with a query parameter the handler
 * does not declare — {@code opentmf.tmf630.undeclared-query-params.on-undeclared}.
 */
public enum UndeclaredQueryParamBehavior {

  /**
   * Spring MVC's own behaviour: the parameter is silently ignored. The interceptor is not even
   * registered.
   */
  IGNORE,

  /**
   * The request is served, and the undeclared names are logged at {@code WARN} and counted — the
   * caller audit that precedes {@link #REJECT}.
   */
  WARN,

  /** The request is refused with a {@code 400} naming the parameter(s) before the handler runs. */
  REJECT
}
