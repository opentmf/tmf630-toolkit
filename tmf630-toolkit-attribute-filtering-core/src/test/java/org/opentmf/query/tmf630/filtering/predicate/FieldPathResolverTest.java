package org.opentmf.query.tmf630.filtering.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;

/**
 * Nested {@code SampleEntity}/{@code NestedEntity}/{@code CollectionEntity}/… test DTOs declare
 * private fields that are never read from Java code — {@link FieldPathResolver} exercises them via
 * reflection (walking declared fields by name/generic type). Sonar S1068 flags them as "unused"
 * because it does not model that reflective read path; deleting the fields would silently break
 * these tests. Suppress at class level with intent.
 */
@SuppressWarnings("java:S1068")
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

  @Test
  void resolvesNestedCollectionElementFieldType() {
    FieldPathResolver resolver = new FieldPathResolver();
    ResolvedField field = resolver.resolve(CollectionEntity.class, "refs.name", true);
    assertEquals(String.class, field.javaType());
  }

  @Test
  void fallsBackToObjectForRawCollectionElementType() {
    FieldPathResolver resolver = new FieldPathResolver();
    ResolvedField field = resolver.resolve(RawCollectionEntity.class, "refs", true);
    assertEquals(Object.class, field.javaType());
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

  static class CollectionEntity {
    private List<Ref> refs;
  }

  static class Ref {
    private String name;
  }

  @SuppressWarnings("rawtypes")
  static class RawCollectionEntity {
    private List refs;
  }
}
