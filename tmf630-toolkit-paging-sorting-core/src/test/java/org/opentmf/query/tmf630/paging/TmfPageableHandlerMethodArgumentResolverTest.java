package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

class TmfPageableHandlerMethodArgumentResolverTest {

  @Test
  void resolvesTmfParametersWithLimitCapping() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(
            true,
            50,
            100,
            true,
            false,
            java.util.List.of());

    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("offset", "10");
    request.setParameter("limit", "150");
    request.setParameter("sort", "-createdOn,+id");

    Pageable pageable =
        (Pageable)
            resolver.resolveArgument(
                pageableParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertEquals(10, pageable.getOffset());
    assertEquals(100, pageable.getPageSize());
    assertEquals("createdOn", pageable.getSort().toList().get(0).getProperty());
    assertEquals("id", pageable.getSort().toList().get(1).getProperty());
  }

  @Test
  void fallsBackToSpringPageAndSizeWhenTmfParamsAbsent() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("page", "2");
    request.setParameter("size", "5");

    Pageable pageable =
        (Pageable)
            resolver.resolveArgument(
                pageableParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertEquals(2, pageable.getPageNumber());
    assertEquals(5, pageable.getPageSize());
    assertEquals(10, pageable.getOffset());
  }

  @Test
  void appliesCustomSortParsingWhenOnlySortIsProvided() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", " transformationId");

    Pageable pageable =
        resolver.resolveArgument(
            pageableParameter(),
            new ModelAndViewContainer(),
            new ServletWebRequest(request),
            null);

    assertEquals(0, pageable.getPageNumber());
    // The settings' defaultLimit (50) — not Spring Data's hard-coded 20 — must drive
    // the fallback page size when the request carries no offset/limit params.
    assertEquals(50, pageable.getPageSize());
    assertEquals("transformationId", pageable.getSort().toList().get(0).getProperty());
  }

  @Test
  void strictModeRejectsInvalidNumericInput() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("limit", "abc");
    MethodParameter parameter = pageableParameter();
    ModelAndViewContainer mavc = new ModelAndViewContainer();
    ServletWebRequest webRequest = new ServletWebRequest(request);

    assertThrows(
        IllegalArgumentException.class,
        () -> resolver.resolveArgument(parameter, mavc, webRequest, null));
  }

  @Test
  void lenientModeFallsBackToDefaultOnInvalidNumericInput() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 25, 500, false, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("offset", "bad");
    request.setParameter("limit", "bad");

    Pageable pageable =
        resolver.resolveArgument(
            pageableParameter(),
            new ModelAndViewContainer(),
            new ServletWebRequest(request),
            null);

    assertEquals(0, pageable.getOffset());
    assertEquals(25, pageable.getPageSize());
  }

  @Test
  void rejectsNegativeOffsetAndNonPositiveLimit() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);
    MethodParameter parameter = pageableParameter();

    MockHttpServletRequest request1 = new MockHttpServletRequest();
    request1.setParameter("offset", "-1");
    ModelAndViewContainer mavc1 = new ModelAndViewContainer();
    ServletWebRequest webRequest1 = new ServletWebRequest(request1);
    assertThrows(
        IllegalArgumentException.class,
        () -> resolver.resolveArgument(parameter, mavc1, webRequest1, null));

    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setParameter("limit", "0");
    ModelAndViewContainer mavc2 = new ModelAndViewContainer();
    ServletWebRequest webRequest2 = new ServletWebRequest(request2);
    assertThrows(
        IllegalArgumentException.class,
        () -> resolver.resolveArgument(parameter, mavc2, webRequest2, null));
  }

  private MethodParameter pageableParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("search", Pageable.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void search(Pageable pageable) { /* signature-only stub for MethodParameter reflection */ }
  }
}
