package org.opentmf.query.tmf630.filtering;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names query parameters that the TMF-630 attribute-filter grammar must NOT read as filters on
 * this one handler, so the handler can bind them itself — typically a mandatory selector that is
 * not an entity property (a branch/tag {@code version}, a derived {@code state}):
 *
 * <pre>{@code
 * @GetMapping("/use-cases")
 * @Tmf630PassThrough({"version"})
 * Page<UseCase> list(
 *     @RequestParam(name = "version") String version,
 *     @QuerydslPredicate(root = UseCase.class) Predicate predicate,
 *     Pageable pageable);
 * }</pre>
 *
 * <p>Honoured by both filter terminals — {@code @QuerydslPredicate} and
 * {@code @Tmf630JsonbFilter} — and found on an API interface method as well as on the
 * implementing class. The allowance is local to the annotated handler and deliberately narrow:
 *
 * <ul>
 *   <li>Exact names only. {@code version} is passed through; {@code version.eq} is still parsed
 *       as a filter key (and rejected under {@code on-unknown-field: REJECT} when no such field
 *       exists).
 *   <li>It loosens nothing else. Every other unknown name on the same request is still rejected
 *       under {@code REJECT}.
 *   <li>A passed-through name that matches an entity property is no longer filterable on this
 *       handler — naming it is an explicit choice.
 * </ul>
 *
 * <p>There is intentionally no global or property-based equivalent: the reserved-name set stays
 * the TMF-630 vocabulary, and a service-wide exemption would re-open the silent-typo hole that
 * {@code on-unknown-field: REJECT} exists to close.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tmf630PassThrough {

  /** Exact query-parameter names the filter grammar leaves to the handler's own bindings. */
  String[] value();
}
