package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

class FieldPathResolverTest {

  @Test
  void resolvesFlatFieldType() {
    FieldPathResolver resolver = new FieldPathResolver();
    ResolvedField field = resolver.resolve(SampleEntity.class, "createdOn", false);
    assertEquals(Instant.class, field.javaType());
  }

  @Test
  void rejectsUnknownField() {
    FieldPathResolver resolver = new FieldPathResolver();
    assertThrows(
        TmfFilteringException.class, () -> resolver.resolve(SampleEntity.class, "missing", false));
  }

  @Test
  void rejectsBlankAndNestedWhenDisabled() {
    FieldPathResolver resolver = new FieldPathResolver();
    assertThrows(TmfFilteringException.class, () -> resolver.resolve(SampleEntity.class, " ", false));
    assertThrows(TmfFilteringException.class, () -> resolver.resolve(NestedEntity.class, "inner.name", false));
  }

  @Test
  void resolvesNestedAndInheritedFields() {
    FieldPathResolver resolver = new FieldPathResolver();
    ResolvedField nested = resolver.resolve(NestedEntity.class, "inner.name", true);
    assertEquals(String.class, nested.javaType());

    ResolvedField inherited = resolver.resolve(ChildEntity.class, "createdOn", false);
    assertEquals(Instant.class, inherited.javaType());
  }

  static class SampleEntity {
    private Instant createdOn;
  }

  static class NestedEntity {
    private Inner inner;
  }

  static class Inner {
    private String name;
  }

  static class BaseEntity {
    private Instant createdOn;
  }

  static class ChildEntity extends BaseEntity {}
}
