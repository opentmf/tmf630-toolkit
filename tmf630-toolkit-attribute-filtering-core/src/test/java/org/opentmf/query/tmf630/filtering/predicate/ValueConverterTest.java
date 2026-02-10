package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.format.support.DefaultFormattingConversionService;

class ValueConverterTest {

  @Test
  void convertsCommonTypes() {
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());

    assertEquals(42, converter.convert("42", Integer.class));
    assertEquals(true, converter.convert("true", Boolean.class));
    assertEquals(
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
        converter.convert("123e4567-e89b-12d3-a456-426614174000", UUID.class));
    assertEquals(
        Instant.parse("2026-01-01T00:00:00Z"),
        converter.convert("2026-01-01T00:00:00Z", Instant.class));
    assertEquals(42, converter.convert("42", int.class));
  }

  @Test
  void rejectsUnsupportedOrInvalidConversions() {
    ValueConverter converter = new ValueConverter(new DefaultFormattingConversionService());
    assertThrows(TmfFilteringException.class, () -> converter.convert("x", NoConverter.class));
    assertThrows(TmfFilteringException.class, () -> converter.convert("x", Integer.class));
  }

  static class NoConverter {}
}
