package org.opentmf.query.tmf630.filtering;

import java.util.Optional;
import org.springframework.core.MethodParameter;

/**
 * Tells {@link FilterRootSortKeyValidator} which resource type a handler parameter filters on.
 * One implementation per filter binding: {@link QuerydslPredicateRootLocator} for
 * {@code @QuerydslPredicate(root = ...)}; {@code tmf630-toolkit-jsonb} contributes one for
 * {@code @Tmf630JsonbFilter(root = ...)}. Register further ones as beans.
 */
@FunctionalInterface
public interface Tmf630FilterRootLocator {

  /** The root type {@code parameter} filters on, or empty when it is not a filter parameter. */
  Optional<Class<?>> locate(MethodParameter parameter);
}
