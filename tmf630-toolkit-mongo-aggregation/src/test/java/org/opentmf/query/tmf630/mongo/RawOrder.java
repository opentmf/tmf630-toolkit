package org.opentmf.query.tmf630.mongo;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mirror of {@link Order} but with NO {@code @Field("id")} on nested classes — exercising the
 * field-resolver's ability to translate user-facing {@code id} predicates into the
 * Spring-Data-default {@code _id} BSON field name.
 */
@Document(collection = "raw_orders")
public class RawOrder {

  @Id private String id;
  private List<RawOrderItem> serviceOrderItem;

  public RawOrder() {}

  public RawOrder(String id, List<RawOrderItem> serviceOrderItem) {
    this.id = id;
    this.serviceOrderItem = serviceOrderItem;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public List<RawOrderItem> getServiceOrderItem() {
    return serviceOrderItem;
  }

  public void setServiceOrderItem(List<RawOrderItem> serviceOrderItem) {
    this.serviceOrderItem = serviceOrderItem;
  }
}
