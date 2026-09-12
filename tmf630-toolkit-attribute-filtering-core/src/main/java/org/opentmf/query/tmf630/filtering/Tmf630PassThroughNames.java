package org.opentmf.query.tmf630.filtering;

import java.util.Arrays;
import java.util.Set;
import org.springframework.core.MethodParameter;

/** Reads a handler's {@link Tmf630PassThrough} names for the filter argument resolvers. */
public final class Tmf630PassThroughNames {

  private Tmf630PassThroughNames() {}

  /**
   * The exact query-parameter names the handler owning {@code parameter} passes through, or an
   * empty set when it declares none. On a Spring MVC handler parameter,
   * {@code getMethodAnnotation} is the handler method's merged lookup, so an annotation declared
   * on the API interface method is found as well as one on the implementing method.
   */
  public static Set<String> of(MethodParameter parameter) {
    Tmf630PassThrough annotation = parameter.getMethodAnnotation(Tmf630PassThrough.class);
    return annotation == null ? Set.of() : Set.copyOf(Arrays.asList(annotation.value()));
  }
}
