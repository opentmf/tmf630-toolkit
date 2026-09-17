package org.opentmf.query.tmf630.advice;

import org.opentmf.query.tmf630.exception.TmfQueryLimitException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles {@link TmfQueryLimitException} — thrown by {@code Tmf630QueryLimitsInterceptor} before
 * a handler runs — returning the status the exception carries ({@code 414 URI Too Long} for the
 * whole query string, {@code 400 Bad Request} for one parameter value) with the same TMF-630
 * Part 1 §3.4 error body shape as the other toolkit handlers.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} for the same reason as the paging and
 * filtering handlers: to win over any catch-all {@code @ExceptionHandler(Exception.class)} in
 * the consuming application.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Tmf630QueryLimitExceptionHandler {

  @ExceptionHandler(TmfQueryLimitException.class)
  public ResponseEntity<ErrorMessage> handle(TmfQueryLimitException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatus());
    boolean wholeQuery = status == HttpStatus.URI_TOO_LONG;
    ErrorMessage body =
        new ErrorMessage(
            String.valueOf(status.value()),
            status.getReasonPhrase(),
            wholeQuery ? "Query string too long." : "Query parameter too long.",
            ex.getMessage());
    return ResponseEntity.status(status).body(body);
  }
}
