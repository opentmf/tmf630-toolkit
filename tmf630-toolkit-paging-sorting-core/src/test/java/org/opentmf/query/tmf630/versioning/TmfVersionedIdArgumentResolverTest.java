package org.opentmf.query.tmf630.versioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Unit tests for {@link TmfVersionedIdArgumentResolver} that don't need a live
 * DispatcherServlet — {@link NativeWebRequest} is mocked and the URI-template
 * variables map is stubbed directly.
 */
class TmfVersionedIdArgumentResolverTest {

  private final TmfVersionedIdArgumentResolver resolver = new TmfVersionedIdArgumentResolver();

  @Test
  @DisplayName("supportsParameter returns true only for TmfVersionedId typed parameters")
  void supportsOnlyTmfVersionedId() throws Exception {
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter versioned = new MethodParameter(sample, 0);
    MethodParameter other = new MethodParameter(sample, 1);
    assertTrue(resolver.supportsParameter(versioned));
    assertFalse(resolver.supportsParameter(other));
  }

  @Test
  @DisplayName("resolveArgument reads path variable value and parses to TmfVersionedId")
  void resolveArgumentBareId() throws Exception {
    NativeWebRequest request = requestWithPathVariable("ref", "Prod");
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    Object result = resolver.resolveArgument(parameter, null, request, null);
    assertEquals(new TmfVersionedId("Prod", java.util.Optional.empty()), result);
  }

  @Test
  @DisplayName("resolveArgument parses the version-directed form")
  void resolveArgumentWithVersion() throws Exception {
    NativeWebRequest request = requestWithPathVariable("ref", "Prod:(version=1.0)");
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    TmfVersionedId parsed = (TmfVersionedId) resolver.resolveArgument(parameter, null, request, null);
    assertEquals("Prod", parsed.id());
    assertEquals(java.util.Optional.of("1.0"), parsed.version());
  }

  @Test
  @DisplayName("resolveArgument surfaces TmfPagingException for malformed values")
  void resolveArgumentMalformed() throws Exception {
    NativeWebRequest request = requestWithPathVariable("ref", "Prod(Version=1.0)"); // typo form
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    assertThrows(
        TmfPagingException.class,
        () -> resolver.resolveArgument(parameter, null, request, null));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "'query fills in when path is bare',           Prod,                2.0, 2.0",
      "'path wins over conflicting query',           Prod:(version=1.0),  2.0, 1.0",
      "'path wins even when query agrees',           Prod:(version=1.0),  1.0, 1.0",
  })
  @DisplayName("path :(version=N) is authoritative; query ?version=N fills in only when path is bare")
  void pathPrecedesOrFallsBackToQuery(String label, String pathValue,
      String queryVersion, String expectedVersion) throws Exception {
    NativeWebRequest request =
        requestWithPathVariable("ref", pathValue, Map.of("version", queryVersion));
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    TmfVersionedId parsed =
        (TmfVersionedId) resolver.resolveArgument(parameter, null, request, null);
    assertEquals("Prod", parsed.id());
    assertEquals(java.util.Optional.of(expectedVersion), parsed.version());
  }

  @Test
  @DisplayName("bare path with no ?version=N stays empty (latest-version semantics)")
  void barePathNoQueryStaysEmpty() throws Exception {
    NativeWebRequest request = requestWithPathVariable("ref", "Prod");
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    TmfVersionedId parsed =
        (TmfVersionedId) resolver.resolveArgument(parameter, null, request, null);
    assertEquals("Prod", parsed.id());
    assertTrue(parsed.version().isEmpty());
  }

  @Test
  @DisplayName("blank ?version= is treated as absent (path result stands)")
  void blankQueryVersionIgnored() throws Exception {
    NativeWebRequest request = requestWithPathVariable("ref", "Prod", Map.of("version", "  "));
    Method sample = Sample.class.getDeclaredMethod("handler", TmfVersionedId.class, String.class);
    MethodParameter parameter = new MethodParameter(sample, 0);
    TmfVersionedId parsed =
        (TmfVersionedId) resolver.resolveArgument(parameter, null, request, null);
    assertEquals("Prod", parsed.id());
    assertTrue(parsed.version().isEmpty());
  }

  private static NativeWebRequest requestWithPathVariable(String name, String value) {
    return requestWithPathVariable(name, value, Map.of());
  }

  private static NativeWebRequest requestWithPathVariable(
      String name, String value, Map<String, String> queryParams) {
    MockHttpServletRequest raw = new MockHttpServletRequest();
    raw.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(name, value));
    queryParams.forEach(raw::setParameter);
    return new ServletWebRequest(raw);
  }

  static class Sample {
    @SuppressWarnings("java:S1186") // reflective MethodParameter fixture; body is intentionally empty
    void handler(@PathVariable("ref") TmfVersionedId versioned, @PathVariable String other) {}
  }
}
