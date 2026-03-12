package org.opentmf.query.tmf630.filtering.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630FilteringExceptionHandlerTest {

  private final Tmf630FilteringExceptionHandler handler = new Tmf630FilteringExceptionHandler();

  @Test
  void returns400WithStructuredBody() {
    TmfFilteringException ex = new TmfFilteringException("Field \"birthdate\" could not be parsed.");
    ResponseEntity<Map<String, Object>> response = handler.handle(ex);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals("400", response.getBody().get("code"));
    assertEquals("Bad Request", response.getBody().get("status"));
    assertEquals("Invalid filter parameter.", response.getBody().get("reason"));
    assertEquals("Field \"birthdate\" could not be parsed.", response.getBody().get("message"));
  }

  @Test
  void includesExceptionMessageInResponseBody() {
    String detail = "Field \"createdOn\" (Instant) could not be parsed from value \"2025-01-01\". "
        + "Expected format: yyyy-MM-dd'T'HH:mm:ssX (ISO-8601 UTC), example: 1990-06-15T11:30:00Z";
    TmfFilteringException ex = new TmfFilteringException(detail);

    ResponseEntity<Map<String, Object>> response = handler.handle(ex);

    String message = (String) response.getBody().get("message");
    assertTrue(message.contains("createdOn"));
    assertTrue(message.contains("Instant"));
    assertTrue(message.contains("yyyy-MM-dd"));
  }
}
