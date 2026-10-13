package org.opentmf.query.tmf630.jsonb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.queryparams.Tmf630DeclaredQueryParameters;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;

/**
 * A {@code @Tmf630JsonbFilter JsonbClause} list is a TMF-630 list to the undeclared-query-parameter
 * guard, which recognises it by class name — core does not depend on this module.
 */
class JsonbUndeclaredQueryParametersTest {

  static class Payload {}

  /** Signature-only handlers: the guard reads their parameters, never the values bound to them. */
  @SuppressWarnings({"unused", "java:S1172"})
  static class OrderController {

    @Tmf630Response
    public String list(@Tmf630JsonbFilter(root = Payload.class) JsonbClause filter, Pageable p) {
      return "";
    }

    public String bareClause(JsonbClause filter) {
      return "";
    }

    @Tmf630Response
    public String retrieve(@PathVariable("id") String id) {
      return id;
    }
  }

  private static Optional<Set<String>> declared(String name) throws NoSuchMethodException {
    for (var method : OrderController.class.getMethods()) {
      if (method.getName().equals(name)) {
        return Tmf630DeclaredQueryParameters.of(new HandlerMethod(new OrderController(), method));
      }
    }
    throw new NoSuchMethodException(name);
  }

  @Test
  void aJsonbFilterListIsOpen() throws Exception {
    assertTrue(declared("list").isEmpty());
    assertTrue(declared("bareClause").isEmpty());
  }

  @Test
  void theSingleReadBesideItTakesOnlyFields() throws Exception {
    assertEquals(Optional.of(Set.of("fields")), declared("retrieve"));
  }
}
