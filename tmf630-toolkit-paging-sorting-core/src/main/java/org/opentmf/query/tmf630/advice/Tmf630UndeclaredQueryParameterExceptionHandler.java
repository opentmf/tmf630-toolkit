package org.opentmf.query.tmf630.advice;

import org.opentmf.query.tmf630.exception.TmfUndeclaredQueryParameterException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers {@link TmfUndeclaredQueryParameterException} — thrown by {@code
 * Tmf630UndeclaredQueryParameterInterceptor} before a handler runs — with {@code 400 Bad Request}
 * and the same TMF-630 Part 1 §3.4 error body as the toolkit's other request rejections.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} like the other toolkit handlers, so it wins
 * over a catch-all in the consuming application. A service that renders request-binding errors as
 * {@code ProblemDetail} switches it off with {@code
 * opentmf.tmf630.undeclared-query-params.exception-handler.enabled=false}: the exception is a
 * {@code ServletRequestBindingException} whose problem detail already names the parameter(s).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Tmf630UndeclaredQueryParameterExceptionHandler {

  @ExceptionHandler(TmfUndeclaredQueryParameterException.class)
  public ResponseEntity<ErrorMessage> handle(TmfUndeclaredQueryParameterException ex) {
    HttpStatus status = HttpStatus.BAD_REQUEST;
    ErrorMessage body =
        new ErrorMessage(
            String.valueOf(status.value()),
            status.getReasonPhrase(),
            "Unknown query parameter.",
            ex.getMessage());
    return ResponseEntity.status(status).body(body);
  }
}
