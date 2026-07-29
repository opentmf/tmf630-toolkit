package org.opentmf.query.tmf630.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Tmf630VersionResolverSupportTest {

  @Test
  @DisplayName("requireVersioning: returns the annotation when @Tmf630Versioned is present")
  void requireVersioningReturnsAnnotation() {
    Tmf630Versioned v = Tmf630VersionResolverSupport.requireVersioning(Annotated.class);
    assertNotNull(v);
    assertEquals("id", v.idField());
    assertEquals("version", v.versionField());
  }

  @Test
  @DisplayName("requireVersioning: throws IllegalStateException naming the type when annotation is missing")
  void requireVersioningThrowsWhenAnnotationMissing() {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> Tmf630VersionResolverSupport.requireVersioning(Plain.class));
    String message = ex.getMessage();
    assertNotNull(message);
    if (!message.contains(Plain.class.getName()) || !message.contains("@Tmf630Versioned")) {
      throw new AssertionError("unexpected message: " + message);
    }
  }

  @Test
  @DisplayName("readVersion: reads the value declared on the versionField and stringifies it")
  void readVersionReadsDeclaredField() {
    Tmf630Versioned v = Tmf630VersionResolverSupport.requireVersioning(Annotated.class);
    Annotated a = new Annotated();
    a.version = "2.0";
    assertEquals("2.0", Tmf630VersionResolverSupport.readVersion(a, v));
  }

  @Test
  @DisplayName("readVersion: walks the superclass chain to find inherited version fields")
  void readVersionWalksSuperclassChain() {
    Tmf630Versioned v = Tmf630VersionResolverSupport.requireVersioning(Subtype.class);
    Subtype s = new Subtype();
    s.version = "3.5";
    assertEquals("3.5", Tmf630VersionResolverSupport.readVersion(s, v));
  }

  @Test
  @DisplayName("readVersion: returns null when the version field is null (not the string \"null\")")
  void readVersionReturnsNullForNullField() {
    Tmf630Versioned v = Tmf630VersionResolverSupport.requireVersioning(Annotated.class);
    Annotated a = new Annotated();
    a.version = null;
    assertNull(Tmf630VersionResolverSupport.readVersion(a, v));
  }

  @Test
  @DisplayName("readVersion: throws IllegalStateException when the versionField is not declared on the class")
  void readVersionThrowsWhenFieldMissing() {
    Tmf630Versioned v = Tmf630VersionResolverSupport.requireVersioning(WrongField.class);
    WrongField w = new WrongField();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> Tmf630VersionResolverSupport.readVersion(w, v));
    String message = ex.getMessage();
    assertNotNull(message);
    if (!message.contains("does_not_exist")) {
      throw new AssertionError("unexpected message: " + message);
    }
  }

  @Tmf630Versioned(idField = "id", versionField = "version")
  static class Annotated {
    @SuppressWarnings("unused") String id = "x";
    @SuppressWarnings("unused") String version;
  }

  static class Plain {}

  static class Parent {
    @SuppressWarnings("unused") String id;
    @SuppressWarnings("unused") String version;
  }

  @Tmf630Versioned(idField = "id", versionField = "version")
  static class Subtype extends Parent {}

  @Tmf630Versioned(idField = "id", versionField = "does_not_exist")
  static class WrongField {
    @SuppressWarnings("unused") String id;
  }
}
