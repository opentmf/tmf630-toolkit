package org.opentmf.query.tmf630.advice;

import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles {@link TmfPagingException} — thrown by the TMF-630 sort/paging argument resolvers and
 * {@code TmfSortParser} when a client-supplied {@code sort=}, {@code offset=}, or {@code limit=}
 * value fails validation — returning a {@code 400 Bad Request} response with the same TMF-630
 * Part 1 §3.4 error body shape as {@code Tmf630FilteringExceptionHandler}. Without this handler
 * the exception would fall through to Spring's default 400 translator, producing a non-TMF body
 * that consumers cannot deserialize uniformly with the filtering / range error bodies.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} for the same reason as the filtering
 * handler: to win over any catch-all {@code @ExceptionHandler(Exception.class)} in the consuming
 * application.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Tmf630PagingExceptionHandler {

  @ExceptionHandler(TmfPagingException.class)
  public ResponseEntity<ErrorMessage> handle(TmfPagingException ex) {
    ErrorMessage body =
        new ErrorMessage(
            String.valueOf(HttpStatus.BAD_REQUEST.value()),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            "Invalid sort or paging parameter.",
            ex.getMessage());
    return ResponseEntity.badRequest().body(body);
  }
}
