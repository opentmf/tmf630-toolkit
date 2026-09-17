package org.opentmf.query.tmf630.querylimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfQueryLimitException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Tmf630QueryLimitsInterceptorTest {

  private final Tmf630QueryLimitsInterceptor interceptor =
      new Tmf630QueryLimitsInterceptor(new Tmf630QueryLimitSettings(100, 20));
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final Object handler = new Object();

  private static MockHttpServletRequest get(String queryString) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/items");
    request.setQueryString(queryString);
    return request;
  }

  @Test
  void noQueryStringPasses() {
    assertTrue(interceptor.preHandle(get(null), response, handler));
    assertTrue(interceptor.preHandle(get(""), response, handler));
  }

  @Test
  void valuesAtTheLimitPass() {
    assertTrue(interceptor.preHandle(get("fields=" + "a".repeat(20)), response, handler));
    String exactly100 = "fields=" + "a".repeat(20) + "&" + "x".repeat(71) + "=";
    assertEquals(100, exactly100.length());
    assertTrue(interceptor.preHandle(get(exactly100), response, handler));
  }

  @Test
  void oneCharOverTheValueLimitIs400NamingTheParameter() {
    TmfQueryLimitException ex =
        assertThrows(
            TmfQueryLimitException.class,
            () -> interceptor.preHandle(get("fields=" + "a".repeat(21)), response, handler));

    assertEquals(400, ex.getStatus());
    assertEquals("Query parameter 'fields' is 21 characters long; the limit is 20.", ex.getMessage());
  }

  @Test
  void oneCharOverTheQueryStringLimitIs414() {
    String query = "a=1&" + "b=2&".repeat(24) + "c";
    assertEquals(101, query.length());

    TmfQueryLimitException ex =
        assertThrows(
            TmfQueryLimitException.class,
            () -> interceptor.preHandle(get(query), response, handler));

    assertEquals(414, ex.getStatus());
    assertEquals("Query string is 101 characters long; the limit is 100.", ex.getMessage());
  }

  @Test
  void valuelessAndEmptyValuedParametersAreNeverOversize() {
    assertTrue(interceptor.preHandle(get("flag&empty=&x=1"), response, handler));
  }

  @Test
  void rawEncodedLengthIsWhatCounts() {
    // 7 encoded chars ("%20" x 7 = 21) for 7 decoded spaces: the encoded form is measured.
    TmfQueryLimitException ex =
        assertThrows(
            TmfQueryLimitException.class,
            () -> interceptor.preHandle(get("q=" + "%20".repeat(7)), response, handler));
    assertEquals(400, ex.getStatus());
  }

  @Test
  void settingsRejectNonPositiveLimits() {
    assertThrows(IllegalArgumentException.class, () -> new Tmf630QueryLimitSettings(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new Tmf630QueryLimitSettings(1, 0));
  }
}
