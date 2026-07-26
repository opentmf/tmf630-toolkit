package org.opentmf.query.tmf630.mongo.split.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.opentmf.query.tmf630.mongo.split.Tmf630MongoSplitBacked;
import org.opentmf.query.tmf630.mongo.split.Tmf630MongoSplitCollection;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mongo mirror of the JSONB {@code SplitOrderDomain} fixture. Same field shape, same
 * child collection semantics — proves the split-and-merge behavior is portable across
 * the two backends. The parent document lives in the {@code split_order} collection;
 * split children live in {@code split_order_item}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(collection = "split_order")
@Tmf630MongoSplitBacked
public class MongoSplitOrder {

  @Id private String id;
  private String status;

  @Tmf630MongoSplitCollection(
      childCollection = "split_order_item",
      childType = MongoSplitOrderItem.class,
      maxInlineItems = 100)
  private List<MongoSplitOrderItem> items;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public List<MongoSplitOrderItem> getItems() {
    return items;
  }

  public void setItems(List<MongoSplitOrderItem> items) {
    this.items = items;
  }
}
