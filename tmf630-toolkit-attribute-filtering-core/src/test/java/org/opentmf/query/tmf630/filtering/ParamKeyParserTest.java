package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParamKeyParserTest {

  @Test
  void parsesKnownOperatorSuffix() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("createdOn.gte").orElseThrow();

    assertEquals("createdOn", parsed.fieldPath());
    assertEquals(TmfOperator.GTE, parsed.operator());
  }

  @Test
  void parsesCaseInsensitiveOperatorSuffix() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("transformationId.likei").orElseThrow();
    assertEquals("transformationId", parsed.fieldPath());
    assertEquals(TmfOperator.LIKEI, parsed.operator());
  }

  @Test
  void appliesImplicitEqWhenEnabled() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("transformationId").orElseThrow();

    assertEquals("transformationId", parsed.fieldPath());
    assertEquals(TmfOperator.EQ, parsed.operator());
  }

  @Test
  void returnsEmptyWhenUnknownSuffixAndImplicitDisabled() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), false);
    assertTrue(parser.parse("createdOn.unknown").isEmpty());
    assertTrue(parser.parse("transformationId").isEmpty());
  }

  @Test
  void returnsEmptyForNullBlankAndMalformedSuffix() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    assertTrue(parser.parse(null).isEmpty());
    assertTrue(parser.parse(" ").isEmpty());
    assertTrue(parser.parse("createdOn.unknown").isEmpty());
  }
}
