package org.opentmf.query.tmf630.queryparams;

import java.util.Set;

/**
 * Told about every request {@link Tmf630UndeclaredQueryParameterInterceptor} finds carrying an
 * undeclared query parameter, before it is warned about or refused — the counting half of {@link
 * UndeclaredQueryParamBehavior#WARN}. The auto-configuration contributes a Micrometer counter when
 * a {@code MeterRegistry} is present; an application may add its own beans.
 */
@FunctionalInterface
public interface Tmf630UndeclaredQueryParameterListener {

  /** The listener that does nothing. */
  Tmf630UndeclaredQueryParameterListener NONE = (method, route, undeclared, behavior) -> {};

  /**
   * @param method the HTTP method, {@code GET} or {@code HEAD}
   * @param route the matched route pattern, e.g. {@code /templates/{id}} — bounded by the
   *     application's mappings, so safe as a metric tag
   * @param undeclared the undeclared names — chosen by the caller, so NOT safe as a metric tag
   * @param behavior {@link UndeclaredQueryParamBehavior#WARN} or {@link
   *     UndeclaredQueryParamBehavior#REJECT}
   */
  void undeclared(
      String method, String route, Set<String> undeclared, UndeclaredQueryParamBehavior behavior);
}
