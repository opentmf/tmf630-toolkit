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

@Entity
@Table(name = "sql_order_item")
public class SqlOrderItem {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String state;
  @Column private String sku;

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

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public SqlOrderEntity getOrder() {
    return order;
  }

  public void setOrder(SqlOrderEntity order) {
    this.order = order;
  }
}
