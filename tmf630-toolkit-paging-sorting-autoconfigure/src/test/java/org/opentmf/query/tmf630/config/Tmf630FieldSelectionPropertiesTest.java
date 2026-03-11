package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Tmf630FieldSelectionPropertiesTest {

  @Test
  void defaultValuesAreCorrect() {
    Tmf630FieldSelectionProperties props = new Tmf630FieldSelectionProperties();
    assertTrue(props.isEnabled());
    assertEquals(1, props.getDefaultDepth());
  }

  @Test
  void settersAndGettersWork() {
    Tmf630FieldSelectionProperties props = new Tmf630FieldSelectionProperties();
    props.setEnabled(false);
    props.setDefaultDepth(5);
    assertFalse(props.isEnabled());
    assertEquals(5, props.getDefaultDepth());
  }
}
