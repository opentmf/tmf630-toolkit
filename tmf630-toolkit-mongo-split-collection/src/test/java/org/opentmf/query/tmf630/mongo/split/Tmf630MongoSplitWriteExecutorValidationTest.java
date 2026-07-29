package org.opentmf.query.tmf630.mongo.split;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Argument-validation tests for {@link Tmf630MongoSplitWriteExecutor}. Exercise the
 * cheap "reject bad input at the boundary" branches without needing a live Mongo —
 * the throws happen before any {@code MongoOperations} call, so a mocked instance
 * suffices.
 */
class Tmf630MongoSplitWriteExecutorValidationTest {

  private final Tmf630MongoSplitWriteExecutor executor =
      new Tmf630MongoSplitWriteExecutor(
          mock(MongoOperations.class), new MongoSplitEntityRegistry());

  @Test
  @DisplayName("saveWithSplits rejects null parent with IllegalStateException on metadata lookup")
  void saveWithSplitsRejectsUnregisteredType() {
    Object unregistered = new Object();
    assertThatThrownBy(() -> executor.saveWithSplits(unregistered))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("appendChild on an unregistered type rejects with IllegalStateException")
  void appendChildRejectsUnregisteredType() {
    Object child = new Object();
    assertThatThrownBy(() -> executor.appendChild(Object.class, "p", child))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("updateChild rejects null parentId")
  void updateChildRejectsNullParentId() {
    Object child = new Object();
    assertThatThrownBy(() -> executor.updateChild(Object.class, null, "item", child))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("updateChild on an unregistered type rejects (metadata lookup fails)")
  void updateChildOnUnregisteredTypeRejects() {
    Object child = new Object();
    assertThatThrownBy(() -> executor.updateChild(Object.class, "p", "i", child))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("updateChild rejects null itemId")
  void updateChildRejectsNullItemId() {
    Object child = new Object();
    assertThatThrownBy(() -> executor.updateChild(Object.class, "p", null, child))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("updateChild rejects null child")
  void updateChildRejectsNullChild() {
    assertThatThrownBy(() -> executor.updateChild(Object.class, "p", "i", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("removeChild rejects null parentId")
  void removeChildRejectsNullParentId() {
    assertThatThrownBy(() -> executor.removeChild(Object.class, null, "i", Object.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("removeChild rejects null itemId")
  void removeChildRejectsNullItemId() {
    assertThatThrownBy(() -> executor.removeChild(Object.class, "p", null, Object.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("reindexChildren rejects null parentId")
  void reindexChildrenRejectsNullParentId() {
    assertThatThrownBy(() -> executor.reindexChildren(Object.class, null, Object.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("saveWithSplitsReconciled rejects unregistered type with IllegalStateException")
  void reconciledRejectsUnregisteredType() {
    Object unregistered = new Object();
    assertThatThrownBy(() -> executor.saveWithSplitsReconciled(unregistered))
        .isInstanceOf(IllegalStateException.class);
  }
}
