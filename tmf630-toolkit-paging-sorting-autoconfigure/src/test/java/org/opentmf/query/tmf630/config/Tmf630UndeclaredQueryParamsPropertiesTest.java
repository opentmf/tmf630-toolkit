package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.queryparams.UndeclaredQueryParamBehavior;

class Tmf630UndeclaredQueryParamsPropertiesTest {

  @Test
  void defaultsAreANoOp() {
    Tmf630UndeclaredQueryParamsProperties properties = new Tmf630UndeclaredQueryParamsProperties();

    assertEquals(UndeclaredQueryParamBehavior.IGNORE, properties.getOnUndeclared());
    assertTrue(properties.getNonQueryArguments().isEmpty());
    assertTrue(properties.getExceptionHandler().isEnabled());
  }

  @Test
  void settersBind() {
    Tmf630UndeclaredQueryParamsProperties properties = new Tmf630UndeclaredQueryParamsProperties();
    properties.setOnUndeclared(UndeclaredQueryParamBehavior.WARN);
    properties.setNonQueryArguments(List.of(String.class));
    properties.getExceptionHandler().setEnabled(false);

    assertEquals(UndeclaredQueryParamBehavior.WARN, properties.getOnUndeclared());
    assertEquals(List.of(String.class), properties.getNonQueryArguments());
    assertFalse(properties.getExceptionHandler().isEnabled());
  }
}
