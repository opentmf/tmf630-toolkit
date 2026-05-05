package org.opentmf.query.tmf630.mongo;

import org.springframework.data.mongodb.core.mapping.Field;

public class OrderItem {

  @Field("id")
  private String id;

  private Service service;

  public OrderItem() {}

  public OrderItem(String id, Service service) {
    this.id = id;
    this.service = service;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Service getService() {
    return service;
  }

  public void setService(Service service) {
    this.service = service;
  }
}
