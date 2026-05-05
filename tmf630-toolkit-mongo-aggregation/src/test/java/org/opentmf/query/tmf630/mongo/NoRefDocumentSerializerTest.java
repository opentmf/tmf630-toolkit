package org.opentmf.query.tmf630.mongo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.querydsl.core.types.dsl.PathBuilder;
import org.junit.jupiter.api.Test;

class NoRefDocumentSerializerTest {

  private final NoRefDocumentSerializer serializer = new NoRefDocumentSerializer();

  @Test
  void asReferenceAlwaysThrows() {
    assertThrows(
        UnsupportedOperationException.class, () -> serializer.asReference("anything"));
  }

  @Test
  void isReferenceAlwaysReturnsFalse() {
    PathBuilder<Object> path = new PathBuilder<>(Object.class, "anyVar");
    assertFalse(serializer.isReference(path));
  }
}
