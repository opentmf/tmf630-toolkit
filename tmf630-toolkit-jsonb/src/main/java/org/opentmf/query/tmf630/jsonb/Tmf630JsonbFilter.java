package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller parameter of type {@link JsonbClause} to the request's TMF-630
 * filter surface — the JSONB counterpart of Spring Data's
 * {@code @QuerydslPredicate(root = ...)}. The full URL grammar applies: dot-suffix
 * operators, §4.4 encoded operator literals, comma/semicolon OR lists with escapes,
 * allowlists, clause/value limits, {@code filter=} JsonPath and
 * {@code filter.combineWithAttributes}.
 *
 * <pre>{@code
 * @GetMapping
 * @Tmf630Response
 * Page<CommunicationMessage> search(
 *     @Tmf630JsonbFilter(root = CommunicationMessage.class) JsonbClause clause,
 *     TmfSort sort,
 *     Pageable pageable) {
 *   return executor.findAll(CommunicationMessage.class, clause, sort, pageable, typeResolver);
 * }
 * }</pre>
 *
 * <p>{@code root} is the <em>domain</em> type (the payload POJO registered via
 * {@code @Tmf630JsonbBacked(domainType = ...)}), not the row entity — field paths and
 * value coercion resolve against it.
 *
 * <p>Resolved by {@code Tmf630JsonbClauseArgumentResolver}, registered automatically by
 * {@code Tmf630JsonbAutoConfiguration} when Spring MVC and a
 * {@code Tmf630FilterSettings} bean are present.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Tmf630JsonbFilter {

  /** The domain (payload POJO) type the filter grammar resolves field paths against. */
  Class<?> root();
}
