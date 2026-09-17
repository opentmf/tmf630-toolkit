package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UnsupportedEncodingException;
import org.apache.coyote.http11.HeadersTooLargeException;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

class Tmf630HeadersTooLargeRecoveryResolverTest {

  private final Tmf630HeadersTooLargeRecoveryResolver resolver =
      new Tmf630HeadersTooLargeRecoveryResolver();
  private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/items");

  @Test
  void runsFirst() {
    assertEquals(Ordered.HIGHEST_PRECEDENCE, resolver.getOrder());
  }

  @Test
  void unrelatedExceptionsAreLeftToOtherResolversUntouched() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.addHeader("Link", "<x>; rel=\"next\"");

    ModelAndView result =
        resolver.resolveException(request, response, null, new IllegalStateException("other"));

    assertNull(result);
    assertEquals("<x>; rel=\"next\"", response.getHeader("Link"));
    assertEquals(200, response.getStatus());
  }

  @Test
  void headersTooLargeIsRecognisedAnywhereInTheCauseChain() {
    assertTrue(
        Tmf630HeadersTooLargeRecoveryResolver.isHeadersTooLarge(
            new RuntimeException("wrapped", new HeadersTooLargeException("too large"))));
    assertFalse(
        Tmf630HeadersTooLargeRecoveryResolver.isHeadersTooLarge(
            new RuntimeException("plain", new IllegalArgumentException())));
  }

  @Test
  void uncommittedResponseIsResetAndAnsweredWithAChunkedTmfBody()
      throws UnsupportedEncodingException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.addHeader("Link", "<x>; rel=\"next\"");
    response.addHeader("X-Huge", "h".repeat(9000));
    response.setStatus(206);

    ModelAndView result =
        resolver.resolveException(
            request, response, null, new HeadersTooLargeException("too large"));

    assertNotNull(result, "handled: nothing else may write");
    assertTrue(result.isEmpty(), "an empty ModelAndView — the response is complete");
    assertEquals(500, response.getStatus());
    assertNull(response.getHeader("Link"));
    assertNull(response.getHeader("X-Huge"));
    assertEquals("application/json", response.getContentType());
    assertNull(response.getHeader("Content-Length"), "no length: the body must be chunked");
    assertEquals(Tmf630HeadersTooLargeRecoveryResolver.ERROR_BODY, response.getContentAsString());
    assertTrue(response.isCommitted(), "flushed before the container could compute a length");
  }

  @Test
  void previouslyDelimitedResponseGetsAStatusOnly500() throws UnsupportedEncodingException {
    // The failed attempt had a Content-Length: Tomcat's leftover identity filter would truncate
    // any body written now, so none is.
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.addHeader("X-Huge", "h".repeat(9000));
    response.setContentLength(35);

    ModelAndView result =
        resolver.resolveException(
            request, response, null, new HeadersTooLargeException("too large"));

    assertNotNull(result);
    assertEquals(500, response.getStatus());
    assertEquals(0, response.getContentLength());
    assertEquals("", response.getContentAsString());
    assertNull(response.getHeader("X-Huge"));
  }

  @Test
  void committedResponseIsNotTouched() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.addHeader("X-Huge", "h".repeat(9000));
    response.setStatus(206);
    response.setCommitted(true);

    ModelAndView result =
        resolver.resolveException(
            request, response, null, new HeadersTooLargeException("too large"));

    assertNull(result);
    assertEquals(206, response.getStatus());
    assertNotNull(response.getHeader("X-Huge"));
  }
}
