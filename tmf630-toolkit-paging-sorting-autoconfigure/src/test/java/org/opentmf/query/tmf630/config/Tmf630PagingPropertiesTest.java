package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;

class Tmf630PagingPropertiesTest {

  @Test
  void toSettingsMapsAllFields() {
    Tmf630PagingProperties properties = new Tmf630PagingProperties();
    properties.setEnabled(true);
    properties.setDefaultLimit(25);
    properties.setMaxLimit(300);
    properties.setStrictMode(false);
    properties.setAllowNestedSortProperties(true);
    properties.setSortAllowlist(List.of("id", "createdOn"));

    Tmf630PagingSettings settings = properties.toSettings();

    assertTrue(settings.enabled());
    assertEquals(25, settings.defaultLimit());
    assertEquals(300, settings.maxLimit());
    assertFalse(settings.strictMode());
    assertTrue(settings.allowNestedSortProperties());
    assertEquals(List.of("id", "createdOn"), settings.sortAllowlist());
  }
}
