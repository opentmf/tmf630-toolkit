package org.opentmf.query.tmf630.jpa.it;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.opentmf.query.tmf630.versioning.Tmf630Versioned;
import org.opentmf.query.tmf630.versioning.VersionOrder;

/**
 * Versioned JPA entity fixture. Surrogate primary key {@code row_id}; logical id
 * lives in {@code id} column; version in {@code version}. Multiple rows may share
 * the same logical id — the resolver picks the max version via LEX ordering in this
 * fixture (simplest case; other orderings covered by unit tests on VersionComparators).
 */
@Entity
@Table(name = "versioned_spec")
@Tmf630Versioned(versionOrder = VersionOrder.LEX)
public class VersionedSpec {

  @Id
  @Column(name = "row_id")
  private String rowId;

  private String id;
  private String version;
  private String name;

  public String getRowId() {
    return rowId;
  }

  public void setRowId(String rowId) {
    this.rowId = rowId;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
