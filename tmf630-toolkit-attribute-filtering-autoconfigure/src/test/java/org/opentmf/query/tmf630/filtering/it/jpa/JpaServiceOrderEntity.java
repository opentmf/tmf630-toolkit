package org.opentmf.query.tmf630.filtering.it.jpa;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "jpa_service_order")
public class JpaServiceOrderEntity {

  @Id
  private String id;

  private String href;
  private String category;
  private String externalId;

  @Column(length = 64)
  private String requestedStartDate;

  private String state;

  @ElementCollection
  @CollectionTable(
      name = "jpa_service_order_tags",
      joinColumns = @JoinColumn(name = "order_id"))
  @Column(name = "tag")
  private List<String> tags = new ArrayList<>();

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

  public List<String> getTags() {
    return tags;
  }

  public void setTags(List<String> tags) {
    this.tags = tags;
  }
}
