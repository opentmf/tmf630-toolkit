package org.opentmf.query.tmf630.jsonb.it;

import org.opentmf.query.tmf630.jsonb.JsonbEntityRegistry;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbSubResourceController;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Concrete sub-endpoint controller for the split-collection IT. The developer writes
 * only this thin class — {@link Tmf630JsonbSubResourceController}'s inherited handlers
 * expose {@code GET /split-orders/{parentId}/items} (paged list) and
 * {@code GET /split-orders/{parentId}/items/{itemId}} (single item lookup).
 */
@RestController
@RequestMapping("/split-orders/{parentId}/items")
public class SplitOrderItemSubController
    extends Tmf630JsonbSubResourceController<SplitOrderItem, SplitOrderDomain> {

  public SplitOrderItemSubController(
      JdbcClient jdbcClient, ObjectMapper objectMapper, JsonbEntityRegistry registry) {
    super(jdbcClient, objectMapper, registry, SplitOrderItem.class, SplitOrderDomain.class);
  }
}
