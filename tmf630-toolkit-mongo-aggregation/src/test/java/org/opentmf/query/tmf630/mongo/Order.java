package org.opentmf.query.tmf630.mongo;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "orders")
public class Order {

  @Id private String id;
  private List<OrderItem> serviceOrderItem;

  public Order() {}

  public Order(String id, List<OrderItem> serviceOrderItem) {
    this.id = id;
    this.serviceOrderItem = serviceOrderItem;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public List<OrderItem> getServiceOrderItem() {
    return serviceOrderItem;
  }

  public void setServiceOrderItem(List<OrderItem> serviceOrderItem) {
    this.serviceOrderItem = serviceOrderItem;
  }
}
