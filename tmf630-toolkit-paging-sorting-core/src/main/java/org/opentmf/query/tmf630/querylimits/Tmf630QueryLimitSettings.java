package org.opentmf.query.tmf630.querylimits;

/**
 * Request-side limits enforced by {@link Tmf630QueryLimitsInterceptor} before any handler runs.
 *
 * <p>Nothing else bounds a query-parameter value: the filtering module caps {@code filter=} and
 * regexes, but {@code fields=}, {@code sort=} and any pass-through or unknown parameter reach
 * the handler — and everything that echoes the request afterwards (the pagination {@code Link}
 * header first of all) — at whatever length the container accepted. A typed rejection here keeps
 * the value out of the response entirely.
 *
 * <p>Lengths are measured on the raw query string as received (encoded form), never on a parsed
 * form body.
 *
 * @param maxQueryStringLength longest raw query string accepted; longer answers {@code 414}
 * @param maxParamValueLength longest single raw parameter value accepted; longer answers {@code
 *     400}
 */
public record Tmf630QueryLimitSettings(int maxQueryStringLength, int maxParamValueLength) {

  /**
   * {@code 4096} chars per query string — half of Tomcat's 8 KB request-line ceiling, so the
   * typed error is reached before the container's untyped one — and {@code 2048} chars per value,
   * matching the filtering module's {@code json-path-filter.max-length} default so no {@code
   * filter=} accepted today is newly rejected.
   */
  public static final Tmf630QueryLimitSettings DEFAULT = new Tmf630QueryLimitSettings(4096, 2048);

  public Tmf630QueryLimitSettings {
    if (maxQueryStringLength <= 0) {
      throw new IllegalArgumentException("maxQueryStringLength must be > 0");
    }
    if (maxParamValueLength <= 0) {
      throw new IllegalArgumentException("maxParamValueLength must be > 0");
    }
  }
}
