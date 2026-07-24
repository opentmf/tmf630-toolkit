package org.opentmf.query.tmf630.exception;

/**
 * Thrown by the TMF-630 sort/paging argument resolvers and {@code TmfSortParser} when a
 * client-supplied {@code sort=}, {@code offset=}, or {@code limit=} value fails validation.
 *
 * <p>Extends {@link IllegalArgumentException} so that legacy callers/tests that catch the
 * broader type keep working; a dedicated {@code @ControllerAdvice} handles it separately from
 * unrelated {@link IllegalArgumentException}s, producing a TMF-630 Part 1 §3.4-shaped error
 * body ({@code code}, {@code status}, {@code reason}, {@code message}) instead of falling
 * through to Spring's default 400 translator.
 */
public class TmfPagingException extends IllegalArgumentException {

  public TmfPagingException(String message) {
    super(message);
  }

  public TmfPagingException(String message, Throwable cause) {
    super(message, cause);
  }
}
