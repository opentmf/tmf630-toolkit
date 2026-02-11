package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.annotations.QueryEntity;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@QueryEntity
@Document("mongo_search_entity")
class MongoSearchEntity {

  @Id
  private String id;
  private String href;
  private String category;
  private String externalId;
  private String requestedStartDate;
  private String state;
  private List<ExternalReference> externalReference;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getHref() {
    return href;
  }

  public void setHref(String href) {
    this.href = href;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getRequestedStartDate() {
    return requestedStartDate;
  }

  public void setRequestedStartDate(String requestedStartDate) {
    this.requestedStartDate = requestedStartDate;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public List<ExternalReference> getExternalReference() {
    return externalReference;
  }

  public void setExternalReference(List<ExternalReference> externalReference) {
    this.externalReference = externalReference;
  }

  @QueryEntity
  static class ExternalReference {
    private String id;
    private String name;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }
}
