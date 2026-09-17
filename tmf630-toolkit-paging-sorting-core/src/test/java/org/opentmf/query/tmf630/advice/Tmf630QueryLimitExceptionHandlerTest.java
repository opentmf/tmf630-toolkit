package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfQueryLimitException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630QueryLimitExceptionHandlerTest {

  private final Tmf630QueryLimitExceptionHandler handler = new Tmf630QueryLimitExceptionHandler();

  @Test
  void oversizeParameterIs400WithTmfBody() {
    ResponseEntity<ErrorMessage> response =
        handler.handle(
            new TmfQueryLimitException(
                400, "Query parameter 'fields' is 2100 characters long; the limit is 2048."));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    ErrorMessage body = response.getBody();
    assertNotNull(body);
    assertEquals("400", body.code());
    assertEquals("Bad Request", body.status());
    assertEquals("Query parameter too long.", body.reason());
    assertEquals(
        "Query parameter 'fields' is 2100 characters long; the limit is 2048.", body.message());
    assertNull(body.referenceError());
    assertNull(body.type());
    assertNull(body.schemaLocation());
  }

  @Test
  void oversizeQueryStringIs414WithTmfBody() {
    ResponseEntity<ErrorMessage> response =
        handler.handle(
            new TmfQueryLimitException(
                414, "Query string is 5000 characters long; the limit is 4096."));

    assertEquals(HttpStatus.URI_TOO_LONG, response.getStatusCode());
    ErrorMessage body = response.getBody();
    assertNotNull(body);
    assertEquals("414", body.code());
    assertEquals("URI Too Long", body.status());
    assertEquals("Query string too long.", body.reason());
    assertEquals("Query string is 5000 characters long; the limit is 4096.", body.message());
  }
}
