package org.opentmf.query.tmf630.filtering.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630FilteringExceptionHandlerTest {

  private final Tmf630FilteringExceptionHandler handler = new Tmf630FilteringExceptionHandler();

  @Test
  void returns400WithStructuredBody() {
    TmfFilteringException ex = new TmfFilteringException("Field \"birthdate\" could not be parsed.");
    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    ErrorMessage body = response.getBody();
    assertNotNull(body);
    assertEquals("400", body.code());
    assertEquals("Bad Request", body.status());
    assertEquals("Invalid filter parameter.", body.reason());
    assertEquals("Field \"birthdate\" could not be parsed.", body.message());
    // TMF-630 Part 1 §3.4 optional fields — omitted from the body when not populated
    // (record component `null` + @JsonInclude(NON_NULL) on ErrorMessage).
    assertNull(body.referenceError());
    assertNull(body.type());
    assertNull(body.schemaLocation());
  }

  @Test
  void includesExceptionMessageInResponseBody() {
    String detail = "Field \"createdOn\" (Instant) could not be parsed from value \"2025-01-01\". "
        + "Expected format: yyyy-MM-dd'T'HH:mm:ssX (ISO-8601 UTC), example: 1990-06-15T11:30:00Z";
    TmfFilteringException ex = new TmfFilteringException(detail);

    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    String message = response.getBody().message();
    assertTrue(message.contains("createdOn"));
    assertTrue(message.contains("Instant"));
    assertTrue(message.contains("yyyy-MM-dd"));
  }
}
