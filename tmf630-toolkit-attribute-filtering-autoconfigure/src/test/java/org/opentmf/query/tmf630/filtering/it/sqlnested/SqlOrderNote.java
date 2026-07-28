package org.opentmf.query.tmf630.filtering.it.sqlnested;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Ordered child of {@link SqlOrderEntity} — the parent side declares
 * {@code @OrderColumn} so that positional {@code [N]} filter can address individual
 * elements by their persisted position. Used by
 * {@code Tmf630PredicateSqlJpaPositionalIT}.
 */
@Entity
@Table(name = "sql_order_note")
public class SqlOrderNote {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String author;
  @Column private String text;

  @ManyToOne
  @JoinColumn(name = "order_id")
  @JsonIgnore
  private SqlOrderEntity order;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getText() {
    return text;
  }

  public void setText(String text) {
    this.text = text;
  }

  public SqlOrderEntity getOrder() {
    return order;
  }

  public void setOrder(SqlOrderEntity order) {
    this.order = order;
  }
}
