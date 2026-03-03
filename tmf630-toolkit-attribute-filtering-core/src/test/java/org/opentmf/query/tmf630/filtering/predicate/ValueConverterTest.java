package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.format.support.DefaultFormattingConversionService;

class ValueConverterTest {

  private final ValueConverter converter =
      new ValueConverter(new DefaultFormattingConversionService());

  @Test
  void convertsCommonTypes() {
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
    assertThrows(TmfFilteringException.class, () -> converter.convert("x", NoConverter.class));
    assertThrows(TmfFilteringException.class, () -> converter.convert("x", Integer.class));
    assertThrows(TmfFilteringException.class, () -> converter.convert("x", void.class));
  }

  @Test
  void convertsEnumUsingValueOfWhenNoFactoryMethodExists() {
    assertEquals(PlainStatus.NEW, converter.convert("NEW", PlainStatus.class));
    assertEquals(PlainStatus.DONE, converter.convert("DONE", PlainStatus.class));
  }

  @Test
  void convertsEnumUsingFactoryMethodBeforeValueOf() {
    assertEquals(StatusWithFactory.NEW, converter.convert("new", StatusWithFactory.class));
    assertEquals(StatusWithFactory.DONE, converter.convert("Done", StatusWithFactory.class));
    assertEquals(StatusWithFactory.FAILED, converter.convert("FAILED", StatusWithFactory.class));
  }

  @Test
  void fallsBackToValueOfWhenFactoryMethodReturnsNull() {
    assertEquals(StatusWithNullFactory.ACTIVE, converter.convert("ACTIVE", StatusWithNullFactory.class));
  }

  @Test
  void triesMultipleFactoryMethodsUntilOneSucceeds() {
    assertEquals(StatusWithMultiFactory.NEW, converter.convert("nuevo", StatusWithMultiFactory.class));
    assertEquals(StatusWithMultiFactory.DONE, converter.convert("done", StatusWithMultiFactory.class));
    assertEquals(StatusWithMultiFactory.FAILED, converter.convert("FAILED", StatusWithMultiFactory.class));
  }

  @Test
  void throwsWhenAllFactoryMethodsAndValueOfFail() {
    assertThrows(TmfFilteringException.class, () -> converter.convert("bogus", PlainStatus.class));
  }

  static class NoConverter {}

  enum PlainStatus {
    NEW, DONE, FAILED
  }

  enum StatusWithFactory {
    NEW, DONE, FAILED;

    public static StatusWithFactory initFrom(String value) {
      return valueOf(value.toUpperCase());
    }
  }

  enum StatusWithNullFactory {
    ACTIVE, INACTIVE;

    public static StatusWithNullFactory tryParse(String value) {
      return null;
    }
  }

  enum StatusWithMultiFactory {
    NEW, DONE, FAILED;

    public static StatusWithMultiFactory fromSpanish(String value) {
      if ("nuevo".equalsIgnoreCase(value)) {
        return NEW;
      }
      throw new IllegalArgumentException("Unknown Spanish value: " + value);
    }

    public static StatusWithMultiFactory fromLower(String value) {
      return valueOf(value.toUpperCase());
    }
  }
}
