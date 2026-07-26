package org.opentmf.query.tmf630.jsonb;

/**
 * Thrown when a candidate row entity annotated with {@link Tmf630JsonbBacked} fails
 * runtime validation — the payload field is missing, the domain type is null, or other
 * configuration-time inconsistencies. Not a user-facing error (never surfaces on a
 * request), so remains a {@link RuntimeException} rather than a subclass of any
 * request-scoped exception hierarchy.
 */
public class Tmf630JsonbConfigurationException extends RuntimeException {

  public Tmf630JsonbConfigurationException(String message) {
    super(message);
  }

  public Tmf630JsonbConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
