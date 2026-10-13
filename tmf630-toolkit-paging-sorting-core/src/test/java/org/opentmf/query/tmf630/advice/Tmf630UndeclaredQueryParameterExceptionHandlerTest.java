package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
  void aListRefusalAnswersInTheSameShape() {
    TmfUndeclaredQueryParameterException onList =
        TmfUndeclaredQueryParameterException.onList("GET /sampleRecord", Set.of("page"));

    ErrorMessage body =
        new Tmf630UndeclaredQueryParameterExceptionHandler().handle(onList).getBody();

    assertEquals("Unknown query parameter.", body.reason());
    assertEquals(
        "unknown query parameter 'page' — GET /sampleRecord is a TMF-630 list, which pages with"
            + " offset and limit",
        body.message());
    assertArrayEquals(
        new Object[] {"page", "GET /sampleRecord", ""}, onList.getDetailMessageArguments());
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
    assertFalse(refusal.isListRoute());
  }
}
