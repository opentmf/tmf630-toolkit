package org.opentmf.query.tmf630.queryparams;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;

/**
 * SPI: tells the undeclared-query-parameter guard what an argument of the application's own
 * kind reads from the query string — typically the argument of a custom {@code
 * HandlerMethodArgumentResolver}, which the built-in rules see only as an unknown type and
 * therefore leave the whole handler unjudged.
 *
 * <p>Every bean of this type is consulted before the built-in rules, in bean order; the first
 * non-null answer wins. An argument that merely reads nothing from the query string (a custom
 * body or header binding) can be declared without code through {@code
 * opentmf.tmf630.undeclared-query-params.non-query-arguments}.
 *
 * <pre>{@code
 * @Bean
 * Tmf630QueryArgumentClassifier tenantClassifier() {
 *   return parameter ->
 *       parameter.getParameterType() == TenantScope.class
 *           ? Tmf630QueryArgument.named("tenant")
 *           : null;
 * }
 * }</pre>
 */
@FunctionalInterface
public interface Tmf630QueryArgumentClassifier {

  /**
   * @param parameter one argument of a handler method, with parameter-name discovery initialised
   * @return what the argument reads from the query string, or {@code null} when this classifier
   *     does not recognise it
   */
  @Nullable Tmf630QueryArgument classify(MethodParameter parameter);

  /**
   * A classifier answering {@link Tmf630QueryArgument#none()} for every argument of one of {@code
   * types} — or annotated with one of them, for an annotation type — and {@code null} otherwise.
   * What {@code opentmf.tmf630.undeclared-query-params.non-query-arguments} configures.
   */
  static Tmf630QueryArgumentClassifier nonQuery(Collection<Class<?>> types) {
    List<Class<?>> nonQuery = List.copyOf(types);
    return parameter ->
        nonQuery.stream().anyMatch(type -> isOrCarries(parameter, type))
            ? Tmf630QueryArgument.none()
            : null;
  }

  private static boolean isOrCarries(MethodParameter parameter, Class<?> type) {
    return type.isAnnotation()
        ? parameter.hasParameterAnnotation(type.asSubclass(Annotation.class))
        : type.isAssignableFrom(parameter.getParameterType());
  }
}
