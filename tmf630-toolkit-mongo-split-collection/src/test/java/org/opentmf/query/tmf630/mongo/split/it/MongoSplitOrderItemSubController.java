package org.opentmf.query.tmf630.mongo.split.it;

import org.opentmf.query.tmf630.mongo.split.MongoSplitEntityRegistry;
import org.opentmf.query.tmf630.mongo.split.Tmf630MongoSubResourceController;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sub-endpoint fixture exercising {@link Tmf630MongoSubResourceController}. Mirrors
 * the JSONB {@code SplitOrderItemSubController} URL shape exactly — same base path,
 * same handler contract — so parity ITs can invoke either backend with the same test
 * requests and observe identical responses.
 */
@RestController
@RequestMapping("/split-orders/{parentId}/items")
public class MongoSplitOrderItemSubController
    extends Tmf630MongoSubResourceController<MongoSplitOrderItem, MongoSplitOrder> {

  public MongoSplitOrderItemSubController(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    super(mongoOperations, registry, MongoSplitOrderItem.class, MongoSplitOrder.class);
  }
}
