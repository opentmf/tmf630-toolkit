package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.querylimits.Tmf630QueryLimitSettings;

class Tmf630QueryLimitsPropertiesTest {

  @Test
  void defaultsMatchTheSettingsDefaults() {
    Tmf630QueryLimitsProperties properties = new Tmf630QueryLimitsProperties();

    assertTrue(properties.isEnabled());
    assertEquals(Tmf630QueryLimitSettings.DEFAULT, properties.toSettings());
    assertEquals(4096, properties.getMaxQueryStringLength());
    assertEquals(2048, properties.getMaxParamValueLength());
  }

  @Test
  void toSettingsMapsAllFields() {
    Tmf630QueryLimitsProperties properties = new Tmf630QueryLimitsProperties();
    properties.setEnabled(false);
    properties.setMaxQueryStringLength(1000);
    properties.setMaxParamValueLength(300);

    Tmf630QueryLimitSettings settings = properties.toSettings();

    assertEquals(1000, settings.maxQueryStringLength());
    assertEquals(300, settings.maxParamValueLength());
  }
}
