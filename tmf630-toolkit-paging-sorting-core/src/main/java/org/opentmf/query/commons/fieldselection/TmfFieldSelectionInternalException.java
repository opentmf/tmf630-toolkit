package org.opentmf.query.commons.fieldselection;

/**
 * Thrown by {@link FieldSelectionUtil} when a JavaBean-introspection call fails —
 * {@code Introspector.getBeanInfo} refuses a class, a {@code PropertyDescriptor} can't
 * be constructed for a record component, or a getter's {@code invoke} throws. These
 * are internal reflection catastrophes (the type is genuinely broken), not user-input
 * validation failures, so they surface as {@code 500 Internal Server Error} via
 * {@code Tmf630FieldSelectionExceptionHandler} rather than being misread as a bad
 * client request.
 *
 * <p>Bad {@code fields=} values (e.g. requesting a property the type doesn't expose)
 * are handled separately — silently skipped inside {@code parseFields}, per the
 * "unknown fields don't error" convention of TMF-630 Part 1 §4.3. This exception type
 * is reserved for reflection failures that must not be conflated with bad user input.
 */
public class TmfFieldSelectionInternalException extends RuntimeException {

  public TmfFieldSelectionInternalException(String message, Throwable cause) {
    super(message, cause);
  }
}
