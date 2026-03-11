package org.opentmf.query.tmf630.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or entire controller) for automatic TMF630 response handling.
 *
 * <p>When placed on a method that returns {@link org.springframework.data.domain.Page Page&lt;T&gt;},
 * the library transparently:
 * <ul>
 *   <li>Resolves the HTTP status ({@code 200}, {@code 206}, or {@code 416}).</li>
 *   <li>Adds {@code Content-Range}, {@code X-Total-Count}, and {@code X-Result-Count} headers.</li>
 *   <li>Extracts the page content as a JSON array.</li>
 *   <li>Applies {@code fields} query-parameter-based field selection if present.</li>
 * </ul>
 *
 * <p>When the method instead returns {@code ResponseEntity<List<T>>} (e.g. via
 * {@link org.opentmf.query.tmf630.util.Tmf630Util#tmfPage Tmf630Util.tmfPage(Page)}), only
 * field selection is applied — status and headers are already set.
 *
 * <p>May be placed at the type level to apply to every handler method in the controller.
 *
 * <h3>Controlling expansion depth</h3>
 * <p>The {@link #depth()} attribute controls how deep nested objects are auto-expanded when a
 * {@code fields=} query parameter names a complex field (e.g. {@code fields=address} expands
 * {@code Address} into its sub-fields up to the given depth). When not set (or explicitly set to
 * {@code -1}), the global default configured via
 * {@code opentmf.tmf630.field-selection.default-depth} is used.
 *
 * <pre>{@code
 * @GetMapping("/persons")
 * @Tmf630Response(depth = 2)
 * Page<Person> list(Pageable pageable) { ... }
 *
 * @GetMapping("/orders")
 * @Tmf630Response(depth = 1)
 * Page<Order> orders(Pageable pageable) { ... }
 *
 * @GetMapping("/simple")
 * @Tmf630Response   // uses opentmf.tmf630.field-selection.default-depth
 * Page<Simple> simple(Pageable pageable) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tmf630Response {

  /**
   * Maximum nesting depth used when auto-expanding a complex field selected by name via the
   * {@code fields=} query parameter.
   *
   * <p>A value of {@code -1} (the default) means "inherit the global default configured via
   * {@code opentmf.tmf630.field-selection.default-depth}".
   *
   * <p>When {@code @Tmf630Response} is placed at the class level and a method also carries its own
   * {@code @Tmf630Response}, the method-level annotation takes precedence.
   */
  int depth() default -1;
}
