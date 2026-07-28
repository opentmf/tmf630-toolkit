package org.opentmf.query.tmf630.jpa.it;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sortable_characteristic")
public class SortableCharacteristic {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column private String name;
  @Column private String value;

  @ManyToOne
  @JoinColumn(name = "parent_id")
  @JsonIgnore
  private SortableParent parent;

  @OneToMany(
      cascade = CascadeType.ALL,
      orphanRemoval = true,
      mappedBy = "characteristic")
  private List<SortableQualifier> qualifiers = new ArrayList<>();

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

  public SortableParent getParent() {
    return parent;
  }

  public void setParent(SortableParent parent) {
    this.parent = parent;
  }

  public List<SortableQualifier> getQualifiers() {
    return qualifiers;
  }

  public void setQualifiers(List<SortableQualifier> qualifiers) {
    this.qualifiers = qualifiers;
  }

  public void addQualifier(String name, String value) {
    SortableQualifier q = new SortableQualifier();
    q.setName(name);
    q.setValue(value);
    q.setCharacteristic(this);
    qualifiers.add(q);
  }
}
