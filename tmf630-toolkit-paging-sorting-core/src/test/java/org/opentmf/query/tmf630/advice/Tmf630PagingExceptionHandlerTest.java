package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630PagingExceptionHandlerTest {

  private final Tmf630PagingExceptionHandler handler = new Tmf630PagingExceptionHandler();

  @Test
  void returns400WithTmfErrorBodyForSortException() {
    TmfPagingException ex = new TmfPagingException("Sort property is not allowed: secret");
    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    ErrorMessage body = response.getBody();
    assertNotNull(body);
    assertEquals("400", body.code());
    assertEquals("Bad Request", body.status());
    assertEquals("Invalid sort or paging parameter.", body.reason());
    assertEquals("Sort property is not allowed: secret", body.message());
    assertNull(body.referenceError());
    assertNull(body.type());
    assertNull(body.schemaLocation());
  }

  @Test
  void returns400ForLimitValidationFailure() {
    TmfPagingException ex = new TmfPagingException("limit must be > 0");
    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("limit must be > 0", response.getBody().message());
  }
}
