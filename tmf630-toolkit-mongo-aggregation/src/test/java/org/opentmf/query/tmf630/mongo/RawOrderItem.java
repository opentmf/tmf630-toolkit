package org.opentmf.query.tmf630.mongo;

public class RawOrderItem {

  // No @Field annotation here — Spring Data's default behavior writes this as `_id`
  // in BSON. PR 6's MongoFieldResolver discovers this at translation time and
  // rewrites user-facing `@.id == 'X'` / `[id=X]` predicates to use `_id` accordingly.
  private String id;

  private Service service;

  public RawOrderItem() {}

  public RawOrderItem(String id, Service service) {
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
