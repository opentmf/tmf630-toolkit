package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA row entity as PostgreSQL-with-JSONB-backed for the purposes of the tmf630
 * toolkit's attribute-filtering and sort pipelines. When present, the toolkit routes
 * URL-parameter predicates and sort terms targeting this entity's <em>domain-model</em>
 * fields into JSONB SQL (e.g. {@code payload->>'field'}, {@code jsonb_path_exists},
 * {@code jsonb_array_length}) against the annotated row's {@code payload} column, rather
 * than through the standard {@code querydsl-jpa} path against normalized entity columns.
 *
 * <p>Semantics and design rationale — see
 * {@code docs/JSONB_BACKEND_DESIGN.md} §0.1 and §3.4. Key points:
 *
 * <ul>
 *   <li><strong>Per-entity opt-in.</strong> The default (no annotation) is pure JPA, so
 *       adding the {@code tmf630-toolkit-jsonb} module to a classpath does not change the
 *       behavior of any entity that doesn't opt in. Existing services keep working.
 *   <li><strong>Mixed-mode services supported.</strong> A single service (and single
 *       Postgres database) may host some entities as pure-JPA (normalized tables) and
 *       others as JSONB-backed. Per-entity routing is decided by the presence of this
 *       annotation on the row entity class.
 *   <li><strong>Payload column convention.</strong> The row entity must declare a field
 *       named by {@link #payloadField()} (default {@code "payload"}) of type {@code JsonNode}
 *       (or another type Jackson can read/write to a JSONB column via
 *       {@code @JdbcTypeCode(SqlTypes.JSON)}). The toolkit does not enforce the column
 *       name or type here — those are Hibernate's concern via the field's own annotations.
 *   <li><strong>Domain type.</strong> The {@link #domainType()} attribute identifies the
 *       TMF resource POJO whose fields the toolkit resolves against for URL-parameter
 *       parsing and allowlist checks. The row entity is a persistence-layer detail; the
 *       domain type is what clients see and what {@code @QuerydslPredicate(root = ...)}
 *       is expected to bind against.
 * </ul>
 *
 * <p>Phase (b.1) of the v3.0.0 roadmap ships this annotation and the entity-detection
 * plumbing. The predicate/sort translators land in b.2 – b.7; see
 * {@code docs/V3_ROADMAP.md} §3.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tmf630JsonbBacked {

  /**
   * The TMF resource POJO whose fields the toolkit resolves against for URL-parameter
   * parsing. Filter and sort URLs use the domain-model field names (e.g. {@code
   * ?status.eq=Pending}, {@code sort=name}); the toolkit translates each to the
   * corresponding JSONB path expression (e.g. {@code payload->>'status'}) against the
   * annotated row's {@link #payloadField()} column.
   */
  Class<?> domainType();

  /**
   * The name of the field on the row entity that holds the JSONB payload. Defaults to
   * {@code "payload"}. Change this only if the row entity uses a different naming
   * convention.
   */
  String payloadField() default "payload";
}
