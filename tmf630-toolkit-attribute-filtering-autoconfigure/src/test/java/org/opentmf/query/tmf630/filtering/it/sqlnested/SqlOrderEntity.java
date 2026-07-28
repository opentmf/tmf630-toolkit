package org.opentmf.query.tmf630.filtering.it.sqlnested;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * Test entity with a JOIN-mapped {@code @OneToMany} collection, used by
 * {@code Tmf630PredicateSqlJpaArrayCorrelationIT} to exercise Phase (a.3) array
 * correlation ({@code filter=$[?(@.items[?(@.state=='X')])]}) on JPA. Not part of
 * production code.
 */
@Entity
@Table(name = "sql_order_entity")
public class SqlOrderEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String reference;
  @Column private String status;

  @OneToMany(
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      mappedBy = "order")
  private List<SqlOrderItem> items = new ArrayList<>();

  @OneToMany(
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      mappedBy = "order")
  @OrderColumn(name = "note_order")
  private List<SqlOrderNote> notes = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public List<SqlOrderItem> getItems() {
    return items;
  }

  public void setItems(List<SqlOrderItem> items) {
    this.items = items;
  }

  public void addItem(SqlOrderItem item) {
    item.setOrder(this);
    items.add(item);
  }

  public List<SqlOrderNote> getNotes() {
    return notes;
  }

  public void setNotes(List<SqlOrderNote> notes) {
    this.notes = notes;
  }

  public void addNote(SqlOrderNote note) {
    note.setOrder(this);
    notes.add(note);
  }
}
