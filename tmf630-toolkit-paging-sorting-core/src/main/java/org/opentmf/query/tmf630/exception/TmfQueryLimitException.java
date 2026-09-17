package org.opentmf.query.tmf630.exception;

/**
 * Thrown by {@code Tmf630QueryLimitsInterceptor} before a handler runs when the request's query
 * string, or one query-parameter value in it, is longer than the configured limit. Carries the
 * HTTP status the rejection maps to: {@code 414} for the whole query string, {@code 400} for a
 * single parameter. {@code Tmf630QueryLimitExceptionHandler} turns it into a TMF-630 Part 1 §3.4
 * error body.
 */
public class TmfQueryLimitException extends RuntimeException {

  private final int status;

  public TmfQueryLimitException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int getStatus() {
    return status;
  }
}
