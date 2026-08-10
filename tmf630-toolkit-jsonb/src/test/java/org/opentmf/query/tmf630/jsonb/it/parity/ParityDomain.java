package org.opentmf.query.tmf630.jsonb.it.parity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Domain payload POJO for the JSONB side of the URL-binding parity IT — the field-path
 * and value-coercion target of {@code @Tmf630JsonbFilter(root = ParityDomain.class)}.
 * {@code NON_NULL} keeps a null {@code modifiedBy} out of the payload entirely so
 * {@code isnull}/{@code isnotnull} (MISSING_ONLY semantics) mirror the JPA NULL column.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParityDomain {

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
