package org.opentmf.query.tmf630.mongo;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "products")
public class Product {

  @Id private String id;
  private List<Characteristic> characteristic;
  private String description;

  public Product() {}

  public Product(String id, List<Characteristic> characteristic) {
    this.id = id;
    this.characteristic = characteristic;
  }

  public Product(String id, List<Characteristic> characteristic, String description) {
    this.id = id;
    this.characteristic = characteristic;
    this.description = description;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public List<Characteristic> getCharacteristic() {
    return characteristic;
  }

  public void setCharacteristic(List<Characteristic> characteristic) {
    this.characteristic = characteristic;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }
}
