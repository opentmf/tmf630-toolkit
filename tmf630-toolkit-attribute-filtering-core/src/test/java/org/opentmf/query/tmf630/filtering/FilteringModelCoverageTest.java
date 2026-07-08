package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;

class FilteringModelCoverageTest {

  @Test
  void operatorRegistryAndOperatorHelpersWork() {
    OperatorRegistry registry = new OperatorRegistry();
    Optional<TmfOperator> eq = registry.resolveSuffix("eq");
    assertTrue(eq.isPresent());
    assertTrue(registry.isRegistered("eq"));
    assertFalse(registry.isRegistered("missing"));
    assertEquals("eq", eq.orElseThrow().suffix());
    assertTrue(TmfOperator.IS_NULL.isNoValueOperator());
    assertTrue(TmfOperator.IN.isMultiValueOperator());
  }

  @Test
  void parsedParamAndSettingsRecordsExposeValues() {
    ParsedParamKey key = new ParsedParamKey("name", TmfOperator.EQ);
    assertEquals("name", key.fieldPath());
    assertEquals(TmfOperator.EQ, key.operator());
    assertFalse(key.implicitEq());
    assertTrue(new ParsedParamKey("name", TmfOperator.EQ, true).implicitEq());

    PredicateLimits limits = new PredicateLimits(10, 3, 64);
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.AND,
            true,
            true,
            true,
            limits,
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.IGNORE,
            true,
            1234,
            UnknownParamBehavior.REJECT);

    assertTrue(settings.implicitEqCsvOr());
    assertEquals(10, settings.limits().maxClauses());
    assertEquals(3, settings.limits().maxValuesPerKey());
    assertEquals(64, settings.limits().maxRegexLength());
    assertEquals(AllowlistMode.DENY_ALL, settings.allowlistMode());
    assertTrue(settings.allowNestedPathsJpa());
    assertTrue(settings.allowNestedPathsDocdb());
    assertTrue(settings.jsonPathFilterEnabled());
    assertEquals(1234, settings.jsonPathMaxLength());
  }

  @Test
  void filteringExceptionSupportsCauseConstructor() {
    RuntimeException cause = new RuntimeException("x");
    TmfFilteringException ex = new TmfFilteringException("failure", cause);
    assertEquals("failure", ex.getMessage());
    assertNotNull(ex.getCause());
  }
}
