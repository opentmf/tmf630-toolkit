package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;

class Tmf630AttributeFilteringPropertiesTest {

  @Test
  void mapsNestedPropertiesToSettings() {
    Tmf630AttributeFilteringProperties properties = new Tmf630AttributeFilteringProperties();
    properties.setEnabled(true);
    properties.setImplicitEqEnabled(false);
    properties.setCombineRepeatedValues(CombineMode.AND);
    properties.setAllowNestedPaths(true);
    properties.setOnUnknownField(UnknownParamBehavior.IGNORE);
    properties.setOnUnknownOperator(UnknownParamBehavior.IGNORE);

    Tmf630AttributeFilteringProperties.Regex regex = new Tmf630AttributeFilteringProperties.Regex();
    regex.setEnabled(true);
    regex.setMaxLength(99);
    properties.setRegex(regex);

    Tmf630AttributeFilteringProperties.Limits limits = new Tmf630AttributeFilteringProperties.Limits();
    limits.setMaxClauses(12);
    limits.setMaxValuesPerKey(4);
    properties.setLimits(limits);

    Tmf630AttributeFilteringProperties.JsonPathFilter jsonPathFilter =
        new Tmf630AttributeFilteringProperties.JsonPathFilter();
    jsonPathFilter.setEnabled(true);
    jsonPathFilter.setMaxLength(999);
    properties.setJsonPathFilter(jsonPathFilter);

    Tmf630AttributeFilteringProperties.Allowlist allowlist =
        new Tmf630AttributeFilteringProperties.Allowlist();
    allowlist.setMode(AllowlistMode.ALLOW_ALL);
    allowlist.setEntities(Map.of("Entity", List.of("name")));
    properties.setAllowlist(allowlist);

    var settings = properties.toSettings();

    assertTrue(properties.isEnabled());
    assertFalse(properties.isImplicitEqEnabled());
    assertEquals(CombineMode.AND, properties.getCombineRepeatedValues());
    assertTrue(properties.isAllowNestedPaths());
    assertEquals(UnknownParamBehavior.IGNORE, properties.getOnUnknownField());
    assertEquals(UnknownParamBehavior.IGNORE, properties.getOnUnknownOperator());
    assertTrue(properties.getRegex().isEnabled());
    assertEquals(99, properties.getRegex().getMaxLength());
    assertEquals(12, properties.getLimits().getMaxClauses());
    assertEquals(4, properties.getLimits().getMaxValuesPerKey());
    assertTrue(properties.getJsonPathFilter().isEnabled());
    assertEquals(999, properties.getJsonPathFilter().getMaxLength());
    assertEquals(AllowlistMode.ALLOW_ALL, properties.getAllowlist().getMode());
    assertEquals(Map.of("Entity", List.of("name")), properties.getAllowlist().getEntities());
    assertFalse(settings.implicitEqEnabled());
    assertEquals(CombineMode.AND, settings.combineRepeatedValues());
    assertTrue(settings.allowNestedPaths());
    assertTrue(settings.regexEnabled());
    assertEquals(99, settings.limits().maxRegexLength());
    assertEquals(12, settings.limits().maxClauses());
    assertEquals(4, settings.limits().maxValuesPerKey());
    assertEquals(AllowlistMode.ALLOW_ALL, settings.allowlistMode());
    assertEquals(UnknownParamBehavior.IGNORE, settings.onUnknownField());
    assertEquals(UnknownParamBehavior.IGNORE, settings.onUnknownOperator());
    assertTrue(settings.jsonPathFilterEnabled());
    assertEquals(999, settings.jsonPathMaxLength());
  }
}
