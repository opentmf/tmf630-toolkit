package org.opentmf.query.tmf630.advice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Keeps a {@code HeadersTooLargeException} from failing twice.
 *
 * <p>When the response headers do not fit the container's buffer, Tomcat throws {@code
 * org.apache.coyote.http11.HeadersTooLargeException} at commit time — from inside the message
 * converter when the body is large enough to flush mid-write. The response is <em>not</em>
 * committed at that point, but the oversized headers are still set on it, so whichever
 * {@code @ExceptionHandler} runs next writes its error entity into the same header set and
 * fails identically; Boot's {@code /error} dispatch then fails a third time and the client gets
 * an empty body and a closed connection.
 *
 * <p>This resolver runs first ({@link Ordered#HIGHEST_PRECEDENCE}), recognises the exception by
 * class name — the toolkit does not depend on Tomcat — and, if the response is not committed,
 * logs which header overflowed, calls {@link HttpServletResponse#reset()} so the header set is
 * empty again, and answers a {@code 500} with a TMF-630 Part 1 §3.4 error body itself.
 *
 * <p>It answers itself, rather than stepping aside for the application's handlers, because of a
 * second Tomcat behaviour: the failed commit leaves the output filter it had chosen (chunked,
 * for a body without a known length) active, and {@code reset()} does not remove it. A second
 * response written with a {@code Content-Length} — which Spring sets for every small error
 * entity — is then framed as chunks under a {@code Content-Length} header and reaches the
 * client corrupted. Writing the body with no length and flushing makes Tomcat pick chunked
 * again, which is idempotent, so the framing is right. If the failed attempt had a {@code
 * Content-Length} or {@code Content-Encoding} of its own, no body can be framed reliably and
 * the {@code 500} is sent without one. If the response is already committed nothing can be
 * written any more; the resolver logs and steps aside.
 */
public class Tmf630HeadersTooLargeRecoveryResolver implements HandlerExceptionResolver, Ordered {

  static final String HEADERS_TOO_LARGE_EXCEPTION =
      "org.apache.coyote.http11.HeadersTooLargeException";

  static final String ERROR_BODY =
      "{\"code\":\"500\",\"status\":\"Internal Server Error\","
          + "\"reason\":\"Response headers too large.\","
          + "\"message\":\"The response headers exceeded the server's header buffer; "
          + "the server log names the header that overflowed.\"}";

  private static final Logger log =
      LoggerFactory.getLogger(Tmf630HeadersTooLargeRecoveryResolver.class);

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  @Nullable
  public ModelAndView resolveException(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @Nullable Object handler,
      @NonNull Exception ex) {
    if (!isHeadersTooLarge(ex)) {
      return null;
    }
    String where = request.getMethod() + " " + request.getRequestURI();
    if (response.isCommitted()) {
      log.warn(
          "Response headers for {} exceeded the container's buffer and the response is already"
              + " committed; nothing can be written",
          where);
      return null;
    }
    log.warn(
        "Response headers for {} exceeded the container's buffer (largest: {}); answering 500",
        where,
        largestHeader(response));
    boolean bodyCanBeFramed =
        response.getHeader(HttpHeaders.CONTENT_LENGTH) == null
            && response.getHeader(HttpHeaders.CONTENT_ENCODING) == null;
    response.reset();
    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    try {
      if (bodyCanBeFramed) {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(ERROR_BODY.getBytes(StandardCharsets.UTF_8));
      } else {
        response.setContentLength(0);
      }
      // Commit now, before the container computes a Content-Length for the small body on
      // close — that is the identity-over-chunked framing this resolver exists to avoid.
      response.flushBuffer();
    } catch (IOException e) {
      log.warn("Could not write the 500 body for {}: {}", where, e.toString());
    }
    return new ModelAndView();
  }

  static boolean isHeadersTooLarge(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (HEADERS_TOO_LARGE_EXCEPTION.equals(t.getClass().getName())) {
        return true;
      }
    }
    return false;
  }

  private static String largestHeader(HttpServletResponse response) {
    return response.getHeaderNames().stream()
        .filter(Objects::nonNull)
        .max(Comparator.comparingInt(name -> headerLength(response, name)))
        .map(name -> name + " (" + headerLength(response, name) + " chars)")
        .orElse("no headers set");
  }

  private static int headerLength(HttpServletResponse response, String name) {
    return response.getHeaders(name).stream().mapToInt(String::length).sum();
  }
}
