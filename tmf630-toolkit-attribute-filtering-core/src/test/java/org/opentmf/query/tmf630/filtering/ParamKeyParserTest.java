package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParamKeyParserTest {

  @Test
  void parsesKnownOperatorSuffix() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("createdOn.gte").orElseThrow();

    assertEquals("createdOn", parsed.fieldPath());
    assertEquals(TmfOperator.GTE, parsed.operator());
    assertFalse(parsed.implicitEq());
  }

  @Test
  void marksExplicitEqSuffixAsNotImplicit() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("name.eq").orElseThrow();

    assertEquals(TmfOperator.EQ, parsed.operator());
    assertFalse(parsed.implicitEq());
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
    assertTrue(parsed.implicitEq());
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
  }

  @Test
  void fallsBackToImplicitEqForUnknownSuffixWhenEnabled() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed = parser.parse("createdOn.unknown").orElseThrow();

    assertEquals("createdOn.unknown", parsed.fieldPath());
    assertEquals(TmfOperator.EQ, parsed.operator());
    assertTrue(parsed.implicitEq());
  }

  @Test
  void fallsBackToImplicitEqForDeepDottedFieldPath() {
    ParamKeyParser parser = new ParamKeyParser(new OperatorRegistry(), true);
    ParsedParamKey parsed =
        parser
            .parse("relatedPartyValue.partyRole.engagedParty.organization.organizationIdentification.identificationId")
            .orElseThrow();

    assertEquals(
        "relatedPartyValue.partyRole.engagedParty.organization.organizationIdentification.identificationId",
        parsed.fieldPath());
    assertEquals(TmfOperator.EQ, parsed.operator());
  }
}
