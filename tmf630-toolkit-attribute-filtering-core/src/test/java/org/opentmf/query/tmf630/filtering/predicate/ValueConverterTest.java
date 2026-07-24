package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

  @Test
  void includesFieldNameAndFormatHintInErrorMessageForTemporalTypes() {
    TmfFilteringException ex1 = assertThrows(TmfFilteringException.class,
        () -> converter.convert("blabla", LocalDate.class, "birthdate"));
    assertTrue(ex1.getMessage().contains("\"birthdate\""));
    assertTrue(ex1.getMessage().contains("LocalDate"));
    assertTrue(ex1.getMessage().contains("yyyy-MM-dd"));
    assertTrue(ex1.getMessage().contains("1990-06-15"));

    TmfFilteringException ex2 = assertThrows(TmfFilteringException.class,
        () -> converter.convert("blabla", LocalDateTime.class, "createdAt"));
    assertTrue(ex2.getMessage().contains("\"createdAt\""));
    assertTrue(ex2.getMessage().contains("LocalDateTime"));
    assertTrue(ex2.getMessage().contains("yyyy-MM-dd'T'HH:mm:ss"));

    TmfFilteringException ex3 = assertThrows(TmfFilteringException.class,
        () -> converter.convert("blabla", OffsetDateTime.class, "updatedAt"));
    assertTrue(ex3.getMessage().contains("\"updatedAt\""));
    assertTrue(ex3.getMessage().contains("OffsetDateTime"));
    assertTrue(ex3.getMessage().contains("XXX"));
  }

  @Test
  void includesFieldNameInEnumErrorMessage() {
    TmfFilteringException ex = assertThrows(TmfFilteringException.class,
        () -> converter.convert("bogus", PlainStatus.class, "jobStatus"));
    assertTrue(ex.getMessage().contains("\"jobStatus\""));
    assertTrue(ex.getMessage().contains("PlainStatus"));
    assertTrue(ex.getMessage().contains("bogus"));
  }

  @Test
  void fallsBackToGenericMessageWhenFieldNameIsNull() {
    TmfFilteringException ex = assertThrows(TmfFilteringException.class,
        () -> converter.convert("blabla", LocalDate.class, null));
    // still contains format hint even without field name
    assertTrue(ex.getMessage().contains("yyyy-MM-dd"));
    assertTrue(ex.getMessage().contains("1990-06-15"));
  }

  @Test
  void errorMessageIncludesFieldNameEvenWhenTypeHasNoFormatHint() {
    // Covers ValueConverter.buildCannotConvertMessage's fieldName-set-but-no-hint
    // branch: Integer has no entry in TYPE_FORMAT_HINTS, but fieldName is passed
    // through, so the message should quote the field name without a hint suffix.
    TmfFilteringException ex =
        assertThrows(
            TmfFilteringException.class,
            () -> converter.convert("x", NoConverter.class, "someBusinessField"));
    assertTrue(ex.getMessage().contains("\"someBusinessField\""));
    assertTrue(ex.getMessage().contains("NoConverter"));
  }

  @Test
  void allKnownTemporalTypesHaveFormatHints() {
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(LocalDate.class));
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(LocalDateTime.class));
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(java.time.LocalTime.class));
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(OffsetDateTime.class));
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(java.time.ZonedDateTime.class));
    assertTrue(ValueConverter.TYPE_FORMAT_HINTS.containsKey(Instant.class));
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

    // Factory shape must accept a single String even though this variant always
    // returns null; ValueConverter's factory-method discovery filters by signature.
    @SuppressWarnings("java:S1172")
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
