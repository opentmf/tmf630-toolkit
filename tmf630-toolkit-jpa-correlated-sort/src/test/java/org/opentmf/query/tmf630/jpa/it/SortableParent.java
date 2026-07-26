package org.opentmf.query.tmf630.jpa.it;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sortable_parent")
public class SortableParent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String name;

  @OneToMany(
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      mappedBy = "parent")
  private List<SortableCharacteristic> characteristics = new ArrayList<>();

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

  public List<SortableCharacteristic> getCharacteristics() {
    return characteristics;
  }

  public void setCharacteristics(List<SortableCharacteristic> characteristics) {
    this.characteristics = characteristics;
  }

  public void addCharacteristic(String name, String value) {
    SortableCharacteristic c = new SortableCharacteristic();
    c.setName(name);
    c.setValue(value);
    c.setParent(this);
    characteristics.add(c);
  }
}
