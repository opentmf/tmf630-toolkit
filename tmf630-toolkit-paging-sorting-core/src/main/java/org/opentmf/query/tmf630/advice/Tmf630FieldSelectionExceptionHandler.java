package org.opentmf.query.tmf630.advice;

import org.opentmf.query.commons.fieldselection.TmfFieldSelectionInternalException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Handles {@link TmfFieldSelectionInternalException} — a reflection catastrophe raised
 * from {@code FieldSelectionUtil} (bad JavaBean class, broken property descriptor,
 * getter throwing) — returning {@code 500 Internal Server Error} with the same TMF-630
 * Part 1 §3.4 error body shape as the other query-parameter handlers. This is
 * deliberately NOT mapped to 400: the exception represents an internal type mismatch
 * on the server side, not bad client input. Bad {@code fields=} values (unknown
 * property names) are silently skipped in {@code FieldSelectionUtil.parseFields} and
 * never reach this handler.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so a consuming application's
 * generic {@code @ExceptionHandler(Exception.class)} does not intercept and dilute
 * the shape.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class Tmf630FieldSelectionExceptionHandler {

  @ExceptionHandler(TmfFieldSelectionInternalException.class)
  public ResponseEntity<ErrorMessage> handle(TmfFieldSelectionInternalException ex) {
    ErrorMessage body =
        new ErrorMessage(
            String.valueOf(HttpStatus.INTERNAL_SERVER_ERROR.value()),
            HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            "Field selection failed due to an internal reflection error.",
            ex.getMessage());
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
