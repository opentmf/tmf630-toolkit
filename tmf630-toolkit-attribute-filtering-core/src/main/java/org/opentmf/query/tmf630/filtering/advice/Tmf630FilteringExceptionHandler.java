package org.opentmf.query.tmf630.filtering.advice;

import java.util.LinkedHashMap;
import java.util.Map;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles {@link TmfFilteringException} thrown by the TMF630 attribute-filtering and
 * JsonPath-filter predicate builders, returning a {@code 400 Bad Request} response with a
 * structured JSON body.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so that it takes precedence over any
 * catch-all {@code @ExceptionHandler(Exception.class)} defined in the consuming application.
 * This prevents a common integration problem where a generic exception handler intercepts the
 * filtering error and maps it to a {@code 500 Internal Server Error}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Tmf630FilteringExceptionHandler {

  @ExceptionHandler(TmfFilteringException.class)
  public ResponseEntity<Map<String, Object>> handle(TmfFilteringException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", String.valueOf(HttpStatus.BAD_REQUEST.value()));
    body.put("status", HttpStatus.BAD_REQUEST.getReasonPhrase());
    body.put("reason", "Invalid filter parameter.");
    body.put("message", ex.getMessage());
    return ResponseEntity.badRequest().body(body);
  }
}
