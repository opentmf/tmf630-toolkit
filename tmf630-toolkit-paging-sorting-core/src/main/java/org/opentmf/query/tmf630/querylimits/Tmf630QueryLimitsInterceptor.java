package org.opentmf.query.tmf630.querylimits;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.opentmf.query.tmf630.exception.TmfQueryLimitException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rejects requests whose query string, or one query-parameter value in it, is longer than the
 * {@link Tmf630QueryLimitSettings configured limits} — with a {@link TmfQueryLimitException}
 * from {@code preHandle}, so no argument resolver, handler or response advice runs for the
 * request.
 *
 * <p>Reads {@link HttpServletRequest#getQueryString()} only. {@code getParameterMap()} would
 * also parse a form-encoded body, which is not what this guard is about, and would decode
 * values, which is not what the container measures.
 */
public class Tmf630QueryLimitsInterceptor implements HandlerInterceptor {

  private final Tmf630QueryLimitSettings settings;

  public Tmf630QueryLimitsInterceptor(Tmf630QueryLimitSettings settings) {
    this.settings = settings;
  }

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {
    String query = request.getQueryString();
    if (query == null || query.isEmpty()) {
      return true;
    }
    if (query.length() > settings.maxQueryStringLength()) {
      throw new TmfQueryLimitException(
          HttpServletResponse.SC_REQUEST_URI_TOO_LONG,
          "Query string is "
              + query.length()
              + " characters long; the limit is "
              + settings.maxQueryStringLength()
              + ".");
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      int valueLength = eq < 0 ? 0 : pair.length() - eq - 1;
      if (valueLength > settings.maxParamValueLength()) {
        throw new TmfQueryLimitException(
            HttpServletResponse.SC_BAD_REQUEST,
            "Query parameter '"
                + pair.substring(0, eq)
                + "' is "
                + valueLength
                + " characters long; the limit is "
                + settings.maxParamValueLength()
                + ".");
      }
    }
    return true;
  }
}
