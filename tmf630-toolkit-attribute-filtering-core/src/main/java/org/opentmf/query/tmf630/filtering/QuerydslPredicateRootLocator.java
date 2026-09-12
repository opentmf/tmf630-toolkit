package org.opentmf.query.tmf630.filtering;

import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.data.querydsl.binding.QuerydslPredicate;

/** Locates the root of a {@code @QuerydslPredicate(root = ...)} parameter. */
public class QuerydslPredicateRootLocator implements Tmf630FilterRootLocator {

  @Override
  public Optional<Class<?>> locate(MethodParameter parameter) {
    QuerydslPredicate annotation = parameter.getParameterAnnotation(QuerydslPredicate.class);
    if (annotation == null || annotation.root() == Object.class) {
      return Optional.empty();
    }
    return Optional.of(annotation.root());
  }
}
