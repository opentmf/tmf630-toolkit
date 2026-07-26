package org.opentmf.query.tmf630.versioning;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a domain/entity type as versioned per TMF-630 Part 4 §2 semantics: the same
 * logical id can appear in multiple rows/documents that differ only in
 * {@link #versionField()}, and the "latest" one is chosen by
 * {@link #versionOrder()} at read time.
 *
 * <p>Discovered at bootstrap by the per-backend {@code Tmf630VersionResolver}
 * implementations, which use the resolved metadata (identifier field name, version
 * field name, comparison policy) to build their fetch queries.
 *
 * <p>Example — DNext-convention numeric-string version:
 *
 * <pre>{@code
 * @Document("productOffering")
 * @Tmf630Versioned(versionOrder = VersionOrder.NUMERIC_STRING)
 * public class ProductOffering {
 *   @Id String id;
 *   String version;
 *   // ...
 * }
 * }</pre>
 *
 * <p>Example — semver-shaped version, non-default field names:
 *
 * <pre>{@code
 * @Entity
 * @Tmf630Versioned(idField = "logicalKey", versionField = "revision",
 *                  versionOrder = VersionOrder.SEMVER)
 * public class SpecEntity { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tmf630Versioned {

  /**
   * The Java field that carries the logical (across-versions) id. TMF convention is
   * a plain {@code id} column reused across every version of the same logical
   * entity, so {@code id} is the default.
   */
  String idField() default "id";

  /**
   * The Java field that carries the version discriminator. TMF convention is a
   * {@code version} column, so {@code version} is the default.
   */
  String versionField() default "version";

  /** How the version values are ordered — {@link VersionOrder} javadoc has the details. */
  VersionOrder versionOrder() default VersionOrder.LEX;
}
