package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfUndeclaredQueryParameterException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.springframework.http.ResponseEntity;

class Tmf630UndeclaredQueryParameterExceptionHandlerTest {

  private final TmfUndeclaredQueryParameterException refusal =
      new TmfUndeclaredQueryParameterException(
          "GET /templates/content", Set.of("variantKey"), Set.of("variant", "docId"));

  @Test
  void answers400InTheToolkitsErrorShape() {
    ResponseEntity<ErrorMessage> response =
        new Tmf630UndeclaredQueryParameterExceptionHandler().handle(refusal);

    assertEquals(400, response.getStatusCode().value());
    ErrorMessage body = response.getBody();
    assertEquals("400", body.code());
    assertEquals("Bad Request", body.status());
    assertEquals("Unknown query parameter.", body.reason());
    assertEquals(
        "unknown query parameter 'variantKey' — GET /templates/content takes only docId, variant",
        body.message());
  }

  @Test
  void theProblemDetailNamesTheParameter_forAServicesOwnRenderer() {
    assertEquals(400, refusal.getStatusCode().value());
    assertEquals(refusal.getMessage(), refusal.getBody().getDetail());
    assertEquals(
        "problemDetail." + TmfUndeclaredQueryParameterException.class.getName(),
        refusal.getDetailMessageCode());
    assertArrayEquals(
        new Object[] {"variantKey", "GET /templates/content", "docId, variant"},
        refusal.getDetailMessageArguments());
  }
}
