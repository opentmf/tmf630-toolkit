package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * TMF-resource-style domain POJO deserialized from the JSONB payload column of
 * {@link JsonbTestRow}. Kept small — just enough fields to exercise the b.5 executor
 * across the different Postgres casts (text, bigint, timestamptz).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonbTestDomain {

  private String id;
  private String status;
  private Integer priority;
  private String modifiedBy;
  private String createdOn;

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

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public String getModifiedBy() {
    return modifiedBy;
  }

  public void setModifiedBy(String modifiedBy) {
    this.modifiedBy = modifiedBy;
  }

  public String getCreatedOn() {
    return createdOn;
  }

  public void setCreatedOn(String createdOn) {
    this.createdOn = createdOn;
  }
}
