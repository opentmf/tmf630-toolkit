package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.format.support.DefaultFormattingConversionService;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

class JacksonEnumTextReaderTest {

  private enum Stage {
    IN_PROGRESS("inProgress"),
    DONE("done");

    private final String wire;

    Stage(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  private enum Phase {
    @JsonProperty("phase-one")
    ONE
  }

  private enum Labelled {
    ONE;

    @Override
    public String toString() {
      return "label " + name().toLowerCase(Locale.ROOT);
    }
  }

  private enum Clash {
    LEFT("RIGHT"),
    RIGHT("LEFT");

    private final String wire;

    Clash(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  private enum WithDefault {
    KNOWN("known"),
    @JsonEnumDefaultValue
    OTHER("other");

    private final String wire;

    WithDefault(String wire) {
      this.wire = wire;
    }

    @JsonValue
    public String wire() {
      return wire;
    }
  }

  private final JacksonEnumTextReader reader = new JacksonEnumTextReader(null);

  @Test
  @DisplayName("reads a @JsonValue, a @JsonProperty and a toString() wire value")
  void readsWireValues() {
    assertEquals(Optional.of(Stage.IN_PROGRESS), reader.read(Stage.class, "inProgress"));
    assertEquals(Optional.of(Phase.ONE), reader.read(Phase.class, "phase-one"));
    // Jackson 3 reads and writes an enum by toString() by default
    assertEquals(Optional.of(Labelled.ONE), reader.read(Labelled.class, "label one"));
  }

  @Test
  @DisplayName("reads nothing for an unknown text, a non-enum type or a null text")
  void readsNothingElse() {
    assertTrue(reader.read(Stage.class, "bogus").isEmpty());
    assertTrue(reader.read(Stage.class, "INPROGRESS").isEmpty());
    assertTrue(reader.read(String.class, "inProgress").isEmpty());
    assertTrue(reader.read(Stage.class, null).isEmpty());
  }

  @Test
  @DisplayName("an unknown text stays unread when the mapper maps it to a default or to null")
  void roundTripRefusesTheMappersFallbacks() {
    ObjectMapper toDefault =
        JsonMapper.builder()
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .build();
    JacksonEnumTextReader defaulting = new JacksonEnumTextReader(toDefault);
    assertTrue(defaulting.read(WithDefault.class, "typo").isEmpty());
    assertEquals(Optional.of(WithDefault.KNOWN), defaulting.read(WithDefault.class, "known"));
    assertEquals(Optional.of(WithDefault.OTHER), defaulting.read(WithDefault.class, "other"));

    ObjectMapper toNull =
        JsonMapper.builder().enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).build();
    assertTrue(new JacksonEnumTextReader(toNull).read(Stage.class, "typo").isEmpty());
  }

  @Test
  @DisplayName("under a case-insensitive mapper only the spelling the payloads carry is read")
  void caseInsensitiveMapperReadsOnlyTheWrittenSpelling() {
    ObjectMapper insensitive =
        JsonMapper.builder().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
    JacksonEnumTextReader lenient = new JacksonEnumTextReader(insensitive);
    assertTrue(lenient.read(Stage.class, "INPROGRESS").isEmpty());
    assertEquals(Optional.of(Stage.IN_PROGRESS), lenient.read(Stage.class, "inProgress"));
  }

  @Test
  @DisplayName("ValueConverter: factory, then the constant name, then the wire value, then 400")
  void converterOrder() {
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService(), reader);

    assertEquals(Stage.IN_PROGRESS, converter.convert("IN_PROGRESS", Stage.class, "stage"));
    assertEquals(Stage.IN_PROGRESS, converter.convert("inProgress", Stage.class, "stage"));
    assertEquals(Phase.ONE, converter.convert("phase-one", Phase.class, "phase"));
    // "LEFT" is LEFT's name and RIGHT's wire value: the name wins, as before
    assertEquals(Clash.LEFT, converter.convert("LEFT", Clash.class, "clash"));
    assertEquals(Optional.of(Clash.RIGHT), reader.read(Clash.class, "LEFT"));
    TmfFilteringException refused =
        assertThrows(
            TmfFilteringException.class, () -> converter.convert("bogus", Stage.class, "stage"));
    assertEquals(
        "Field \"stage\" (Stage) could not be parsed from value \"bogus\".", refused.getMessage());
  }

  @Test
  @DisplayName("ValueConverter without a reader accepts only the constant name, as before")
  void converterWithoutReader() {
    ValueConverter byName = new ValueConverter(new DefaultFormattingConversionService());
    assertEquals(Stage.IN_PROGRESS, byName.convert("IN_PROGRESS", Stage.class, "stage"));
    assertThrows(
        TmfFilteringException.class, () -> byName.convert("inProgress", Stage.class, "stage"));
    ValueConverter nullReader = new ValueConverter(new DefaultFormattingConversionService(), null);
    assertThrows(
        TmfFilteringException.class, () -> nullReader.convert("inProgress", Stage.class, "stage"));
  }
}
