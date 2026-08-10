package org.opentmf.query.tmf630.jsonb;

/**
 * Thrown when a JSONB payload cannot be (de)serialized to/from the domain type — a
 * malformed payload row, a domain-model/payload mismatch, or a Jackson mapping failure.
 * Wraps the unchecked Jackson 3 {@code tools.jackson.core.JacksonException} with the
 * domain-type context the raw exception lacks. A server-side data problem, never a
 * client-input error, so it is deliberately NOT a
 * {@link org.opentmf.query.tmf630.filtering.TmfFilteringException} (which maps to
 * HTTP 400 in downstream handlers).
 */
public class Tmf630JsonbSerializationException extends RuntimeException {

  public Tmf630JsonbSerializationException(String message, Throwable cause) {
    super(message, cause);
  }
}
