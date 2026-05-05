package org.opentmf.query.tmf630.mongo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Test-only advice that maps {@link IllegalArgumentException} (the toolkit's standard
 * sort-parsing rejection) to HTTP 400. Real consumer applications typically have an
 * equivalent handler, often part of a broader error-mapping framework.
 */
@RestControllerAdvice
class HttpItExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ex.getMessage());
  }
}
