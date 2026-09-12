package org.opentmf.query.tmf630.jsonb.it.parity;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Simulates a consuming service's catch-all {@code @ExceptionHandler(Exception.class)} (the
 * estate's services answer 500 through one): pins what an exception the toolkit does NOT map
 * turns into, and that the toolkit's own HIGHEST_PRECEDENCE handlers still win over it. Scoped
 * to {@link SortParityController} because the other JSONB ITs component-scan this package too
 * and must keep Spring's default exception handling.
 */
@RestControllerAdvice(assignableTypes = SortParityController.class)
public class ParityCatchAllAdvice {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<String> handleUnmapped(Exception ex) {
    return ResponseEntity.internalServerError().body(ex.getClass().getSimpleName());
  }
}
