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
    assertEquals(20, pageable.getPageSize());
    assertEquals("transformationId", pageable.getSort().toList().get(0).getProperty());
  }

  @Test
  void strictModeRejectsInvalidNumericInput() {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("limit", "abc");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            resolver.resolveArgument(
                pageableParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null));
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
  void rejectsNegativeOffsetAndNonPositiveLimit() {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 500, true, false, java.util.List.of());
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request1 = new MockHttpServletRequest();
    request1.setParameter("offset", "-1");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            resolver.resolveArgument(
                pageableParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request1),
                null));

    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setParameter("limit", "0");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            resolver.resolveArgument(
                pageableParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request2),
                null));
  }

  private MethodParameter pageableParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("search", Pageable.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void search(Pageable pageable) {}
  }
}
