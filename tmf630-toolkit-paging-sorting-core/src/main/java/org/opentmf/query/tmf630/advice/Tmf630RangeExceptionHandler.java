package org.opentmf.query.tmf630.advice;

import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.opentmf.query.tmf630.util.Tmf630Util;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Tmf630RangeExceptionHandler {

  @ExceptionHandler(RequestedRangeNotSatisfiableException.class)
  public ResponseEntity<ErrorMessage> handle(RequestedRangeNotSatisfiableException e) {
    long total = e.getTotalElements();

    ErrorMessage error = getRangeNotSatisfiableError(e);
    HttpHeaders headers = new HttpHeaders();
    Tmf630Util.applyRangeHeaders(headers, total, e.getRequestedOffset(), 0, false);

    return new ResponseEntity<>(error, headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
  }

  private static ErrorMessage getRangeNotSatisfiableError(
      RequestedRangeNotSatisfiableException exception) {
    long offset = exception.getRequestedOffset();
    long total = exception.getTotalElements();

    ErrorMessage error = new ErrorMessage();
    error.setCode(String.valueOf(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value()));
    error.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.getReasonPhrase());
    error.setReason("Requested offset is outside the available range.");
    error.setMessage(
        "Requested offset "
            + offset
            + " does not overlap with existing items. Valid offsets are between 0 and "
            + (total == 0 ? 0 : total - 1)
            + ".");
    return error;
  }
}
