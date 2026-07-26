package org.opentmf.query.tmf630.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;

class TmfVersionedIdTest {

  @Test
  @DisplayName("bare id parses with empty version")
  void bareId() {
    TmfVersionedId parsed = TmfVersionedId.parse("VirtualStorage");
    assertEquals("VirtualStorage", parsed.id());
    assertTrue(parsed.version().isEmpty());
  }

  @Test
  @DisplayName("id:(version=X) parses with the version present")
  void withVersion() {
    TmfVersionedId parsed = TmfVersionedId.parse("VirtualStorage:(version=1.0)");
    assertEquals("VirtualStorage", parsed.id());
    assertEquals(Optional.of("1.0"), parsed.version());
  }

  @Test
  @DisplayName("numeric-string version parses")
  void numericStringVersion() {
    TmfVersionedId parsed = TmfVersionedId.parse("Prod:(version=13)");
    assertEquals(Optional.of("13"), parsed.version());
  }

  @Test
  @DisplayName("blank input rejected")
  void blankRejected() {
    assertThrows(TmfPagingException.class, () -> TmfVersionedId.parse(""));
    assertThrows(TmfPagingException.class, () -> TmfVersionedId.parse(null));
    assertThrows(TmfPagingException.class, () -> TmfVersionedId.parse("   "));
  }

  @Test
  @DisplayName("typo spelling /X(Version=1.0) rejected — not spec-authorized")
  void typoSpellingRejected() {
    TmfPagingException ex =
        assertThrows(
            TmfPagingException.class, () -> TmfVersionedId.parse("Prod(Version=1.0)"));
    assertTrue(ex.getMessage().contains("Malformed"), ex.getMessage());
  }

  @Test
  @DisplayName("uppercase 'Version' rejected — spec uses lowercase")
  void uppercaseVersionKeywordRejected() {
    assertThrows(
        TmfPagingException.class, () -> TmfVersionedId.parse("Prod:(Version=1.0)"));
  }

  @Test
  @DisplayName("missing closing paren rejected")
  void missingClosingParenRejected() {
    assertThrows(
        TmfPagingException.class, () -> TmfVersionedId.parse("Prod:(version=1.0"));
  }

  @Test
  @DisplayName("empty version value rejected")
  void emptyVersionValueRejected() {
    assertThrows(TmfPagingException.class, () -> TmfVersionedId.parse("Prod:(version=)"));
  }

  @Test
  @DisplayName("record constructor rejects blank id")
  void recordRejectsBlankId() {
    assertThrows(IllegalArgumentException.class, () -> new TmfVersionedId("", Optional.empty()));
    assertThrows(IllegalArgumentException.class, () -> new TmfVersionedId(null, Optional.empty()));
  }

  @Test
  @DisplayName("record constructor normalises null version to empty Optional")
  void recordNormalisesNullVersion() {
    TmfVersionedId built = new TmfVersionedId("X", null);
    assertTrue(built.version().isEmpty());
  }
}
