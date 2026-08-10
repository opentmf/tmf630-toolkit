package org.opentmf.query.tmf630.jsonb.it.parity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Column-per-field JPA entity for the QueryDSL side of the URL-binding parity IT. Field
 * names and types mirror {@link ParityDomain} exactly so the same URL grammar resolves
 * identically on both backends. {@code createdOn} is an ISO-8601 string on purpose —
 * lexical comparison is chronologically correct and behaves identically on a varchar
 * column and a JSONB text extraction.
 */
@Entity
@Table(name = "parity_entity")
public class ParityEntity {

  @Id private String id;
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
