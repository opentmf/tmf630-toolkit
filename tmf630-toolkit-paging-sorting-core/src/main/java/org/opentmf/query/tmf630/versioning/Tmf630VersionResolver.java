package org.opentmf.query.tmf630.versioning;

import java.util.Optional;

/**
 * TMF-630 Part 4 §2 version resolution — hands the caller the row/document matching a
 * {@code (logicalId, version?)} pair.
 *
 * <p>Implementations are per-backend: {@code Tmf630JpaVersionResolver} in
 * {@code tmf630-toolkit-jpa-correlated-sort}, {@code Tmf630MongoVersionResolver} in
 * {@code tmf630-toolkit-mongo-aggregation}, {@code Tmf630JsonbVersionResolver} in
 * {@code tmf630-toolkit-jsonb}. Each is auto-configured when its backend is on the
 * classpath. Consumers autowire the interface; only one implementation is active per
 * service.
 *
 * <p>Typical usage in a controller handler for GET/PATCH/DELETE on a versioned
 * resource:
 *
 * <pre>{@code
 * @GetMapping("/{ref}")
 * @Tmf630Response
 * Product get(@PathVariable("ref") TmfVersionedId ref) {
 *   return resolver.resolveOrLatest(Product.class, ref)
 *       .orElseThrow(() -> new NotFoundException(ref.id()));
 * }
 * }</pre>
 */
public interface Tmf630VersionResolver {

  /**
   * Returns the row/document with the highest {@code version} for the given logical
   * id per the entity's {@link Tmf630Versioned#versionOrder()}. Empty if no rows
   * match the logical id at all.
   */
  <T> Optional<T> resolveLatest(Class<T> type, String logicalId);

  /**
   * Returns the row/document with the exact {@code (logicalId, version)} pair, or
   * empty if none matches.
   */
  <T> Optional<T> resolveSpecific(Class<T> type, String logicalId, String version);

  /**
   * Convenience: dispatches to {@link #resolveSpecific} when {@code ref.version()}
   * is present, {@link #resolveLatest} otherwise.
   */
  default <T> Optional<T> resolveOrLatest(Class<T> type, TmfVersionedId ref) {
    return ref.version().isPresent()
        ? resolveSpecific(type, ref.id(), ref.version().get())
        : resolveLatest(type, ref.id());
  }
}
