package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.opentmf.query.commons.fieldselection.TmfFieldSelectionInternalException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630FieldSelectionExceptionHandlerTest {

  private final Tmf630FieldSelectionExceptionHandler handler =
      new Tmf630FieldSelectionExceptionHandler();

  @Test
  void reflectionCatastropheReturns500WithTmfErrorBody() {
    // Verifies the semantic split: this exception is NEVER 400. Bad `fields=` values
    // are silently skipped upstream; only reflection failures reach this handler,
    // and those are always server-side.
    TmfFieldSelectionInternalException ex =
        new TmfFieldSelectionInternalException(
            "Error getting properties of class com.example.Broken",
            new IllegalStateException("beanInfo blew up"));

    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    ErrorMessage body = response.getBody();
    assertNotNull(body);
    assertEquals("500", body.code());
    assertEquals("Internal Server Error", body.status());
    assertEquals(
        "Field selection failed due to an internal reflection error.", body.reason());
    assertEquals(
        "Error getting properties of class com.example.Broken", body.message());
    // 2.1.5 optional fields — omitted from the JSON body when unset.
    assertNull(body.referenceError());
    assertNull(body.type());
    assertNull(body.schemaLocation());
  }
}
