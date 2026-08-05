package org.opentmf.query.tmf630.filtering.it.mongo;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Regression fixture for {@code PolymorphicRegexMongoIT}. {@code value} is declared as
 * {@code Object} so the same document collection can hold string, numeric, boolean, and nested
 * values — the shape TMF-620's {@code productSpecCharacteristicValue.value} takes when carrying
 * polymorphic characteristic values.
 */
@Document(collection = "polymorphic_regex_entity")
public class PolymorphicMongoEntity {

  @Id public String id;
  public List<PolymorphicCharacteristic> characteristic;

  public static class PolymorphicCharacteristic {
    public String name;
    public Object value;
  }
}
