package org.opentmf.query.tmf630.jpa.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionOrder;

/**
 * Versioned JPA entity fixture with non-default field names and a NON-String version
 * ({@code Integer revision}). Pins how the resolver binds the caller's String version to a
 * typed attribute, so a change in how the query is built cannot silently change it.
 */
@Entity
@Table(name = "versioned_revision_spec")
@Tmf630Versioned(idField = "logicalKey", versionField = "revision", versionOrder = VersionOrder.LEX)
public class VersionedRevisionSpec {

  @Id
  @Column(name = "row_id")
  private String rowId;

  private String logicalKey;
  private Integer revision;
  private String name;

  public String getRowId() {
    return rowId;
  }

  public void setRowId(String rowId) {
    this.rowId = rowId;
  }

  public String getLogicalKey() {
    return logicalKey;
  }

  public void setLogicalKey(String logicalKey) {
    this.logicalKey = logicalKey;
  }

  public Integer getRevision() {
    return revision;
  }

  public void setRevision(Integer revision) {
    this.revision = revision;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
