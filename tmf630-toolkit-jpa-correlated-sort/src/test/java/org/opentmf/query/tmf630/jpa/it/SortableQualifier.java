package org.opentmf.query.tmf630.jpa.it;

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
@Table(name = "sortable_qualifier")
public class SortableQualifier {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String name;
  @Column private String value;

  @ManyToOne
  @JoinColumn(name = "characteristic_id")
  @JsonIgnore
  private SortableCharacteristic characteristic;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public SortableCharacteristic getCharacteristic() {
    return characteristic;
  }

  public void setCharacteristic(SortableCharacteristic characteristic) {
    this.characteristic = characteristic;
  }
}
