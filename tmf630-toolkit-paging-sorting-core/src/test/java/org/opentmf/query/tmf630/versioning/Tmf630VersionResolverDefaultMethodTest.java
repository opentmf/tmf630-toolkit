package org.opentmf.query.tmf630.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link Tmf630VersionResolver#resolveOrLatest} default method's dispatch
 * logic in isolation — real backend resolvers exercise this in their own ITs, but
 * this unit test guarantees the interface default is correct without needing any
 * backend infrastructure to boot.
 */
class Tmf630VersionResolverDefaultMethodTest {

  @Test
  @DisplayName("resolveOrLatest with empty version dispatches to resolveLatest")
  void dispatchesToLatest() {
    RecordingResolver r = new RecordingResolver();
    r.resolveOrLatest(String.class, new TmfVersionedId("X", Optional.empty()));
    assertEquals("latest:X", r.lastCall);
  }

  @Test
  @DisplayName("resolveOrLatest with version dispatches to resolveSpecific")
  void dispatchesToSpecific() {
    RecordingResolver r = new RecordingResolver();
    r.resolveOrLatest(String.class, new TmfVersionedId("X", Optional.of("1.0")));
    assertEquals("specific:X:1.0", r.lastCall);
  }

  @Test
  @DisplayName("default method threads returned Optional through unchanged")
  void returnValuePassthrough() {
    RecordingResolver r = new RecordingResolver();
    r.returnValue = Optional.of("hit");
    Optional<String> result =
        r.resolveOrLatest(String.class, new TmfVersionedId("X", Optional.empty()));
    assertTrue(result.isPresent());
    assertEquals("hit", result.get());
  }

  static final class RecordingResolver implements Tmf630VersionResolver {
    String lastCall;
    Optional<String> returnValue = Optional.empty();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolveLatest(Class<T> type, String logicalId) {
      lastCall = "latest:" + logicalId;
      return (Optional<T>) returnValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version) {
      lastCall = "specific:" + logicalId + ":" + version;
      return (Optional<T>) returnValue;
    }
  }
}
