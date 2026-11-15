package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** The JSON reading of a filter value on a field of an open JSON type (ledger #4246, (a′)). */
class JsonTypedValueTest {

  @Test
  void anIntegerReadsAsTheTextAndALong() {
    JsonTypedValue value = JsonTypedValue.read("42");

    assertEquals(List.of("42", 42L), value.readings());
    assertTrue(value.isNumber());
    assertEquals(List.of("\"42\"", "42"), value.jsonLiterals());
  }

  @Test
  void anIntegralDecimalReadsAsALongForTheDocumentStore() {
    assertEquals(List.of("42.0", 42L), JsonTypedValue.read("42.0").readings());
    assertEquals(List.of("1e3", 1000L), JsonTypedValue.read("1e3").readings());
  }

  @Test
  void aFractionReadsAsADouble() {
    assertEquals(List.of("-2.5", -2.5d), JsonTypedValue.read("-2.5").readings());
    assertEquals(List.of("\"-2.5\"", "-2.5"), JsonTypedValue.read("-2.5").jsonLiterals());
  }

  @Test
  void aBooleanReadsAsTheTextAndTheBoolean() {
    assertEquals(List.of("true", true), JsonTypedValue.read("true").readings());
    assertEquals(List.of("\"false\"", "false"), JsonTypedValue.read("false").jsonLiterals());
    assertFalse(JsonTypedValue.read("true").isNumber());
  }

  @ParameterizedTest
  @ValueSource(strings = {"alpha", "01", "+7", ".5", "1.", "NaN", "TRUE", "", " 42", "0x1A", "1e"})
  void anythingElseIsTheTextOnly(String text) {
    JsonTypedValue value = JsonTypedValue.read(text);

    assertEquals(List.of(text), value.readings());
    assertFalse(value.isNumber());
  }

  @Test
  void aStringLiteralIsEscapedForJson() {
    assertEquals(
        List.of("\"a\\\"b\\\\c\\u000a\""),
        JsonTypedValue.read("a\"b\\c\n").jsonLiterals());
  }

  @Test
  void aNumberTooLongForTheDocumentStoreIsTheTextOnly() {
    String long1 = "1" + "0".repeat(400);

    assertEquals(List.of(long1), JsonTypedValue.read(long1).readings());
  }

  @Test
  void mapsAndJacksonNodesAreJsonTyped() {
    assertTrue(JsonTypedFields.isJsonTyped(Map.class));
    assertTrue(JsonTypedFields.isJsonTyped(HashMap.class));
    assertTrue(JsonTypedFields.isJsonTyped(TreeMap.class));
    assertTrue(JsonTypedFields.isJsonTyped(JsonNode.class));
    assertTrue(JsonTypedFields.isJsonTyped(ObjectNode.class));
    assertTrue(JsonTypedFields.isJsonTyped(StringNode.class));
  }

  @Test
  void objectAndTypedFieldsAreNotJsonTyped() {
    for (Class<?> type : List.of(Object.class, String.class, Integer.class, List.class)) {
      assertFalse(JsonTypedFields.isJsonTyped(type), type.getName());
    }
    assertFalse(JsonTypedFields.isJsonTyped(null));
  }
}
