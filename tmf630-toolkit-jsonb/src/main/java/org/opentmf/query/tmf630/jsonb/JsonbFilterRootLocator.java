package org.opentmf.query.tmf630.jsonb;

import java.util.Optional;
import org.opentmf.query.tmf630.filtering.Tmf630FilterRootLocator;
import org.springframework.core.MethodParameter;

/**
 * Locates the domain root of a {@code @Tmf630JsonbFilter(root = ...)} parameter, so plain sort
 * keys on the same handler are validated against the JSONB domain type — the executor would
 * otherwise sort on a missing payload key without complaint.
 */
public class JsonbFilterRootLocator implements Tmf630FilterRootLocator {

  @Override
  public Optional<Class<?>> locate(MethodParameter parameter) {
    Tmf630JsonbFilter annotation = parameter.getParameterAnnotation(Tmf630JsonbFilter.class);
    return annotation == null ? Optional.empty() : Optional.of(annotation.root());
  }
}
