package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

class TmfRichPageableHandlerMethodArgumentResolverTest {

  private static final Tmf630PagingSettings SETTINGS =
      new Tmf630PagingSettings(true, 50, 500, true, false, List.of());

  @Test
  void resolvesTmfRichPageableWithPlainSort() throws Exception {
    TmfRichPageableHandlerMethodArgumentResolver resolver =
        new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", "-createdOn,+id");
    request.setParameter("offset", "10");
    request.setParameter("limit", "5");

    TmfRichPageable pageable =
        resolver.resolveArgument(
            tmfPageableParameter(),
            new ModelAndViewContainer(),
            new ServletWebRequest(request),
            null);

    assertEquals(10, pageable.getOffset());
    assertEquals(5, pageable.getPageSize());
    assertFalse(pageable.tmfSort().requiresAggregation());
    assertEquals(2, pageable.tmfSort().terms().size());
    assertEquals(Sort.Direction.DESC, pageable.getSort().toList().get(0).getDirection());
  }

  @Test
  void resolvesTmfRichPageableWithCorrelatedSortReturnsUnsortedPlainSort() throws Exception {
    TmfRichPageableHandlerMethodArgumentResolver resolver =
        new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", "$.arr[?(@.id == 'X')].value");

    TmfRichPageable pageable =
        resolver.resolveArgument(
            tmfPageableParameter(),
            new ModelAndViewContainer(),
            new ServletWebRequest(request),
            null);

    assertTrue(pageable.tmfSort().requiresAggregation());
    assertTrue(pageable.getSort().isUnsorted());
  }

  @Test
  void supportsTmfRichPageableButNotPlainPageable() throws Exception {
    TmfRichPageableHandlerMethodArgumentResolver resolver =
        new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS);

    Method tmfMethod = ControllerStub.class.getDeclaredMethod("rich", TmfRichPageable.class);
    Method plainMethod = ControllerStub.class.getDeclaredMethod("plain", Pageable.class);

    assertTrue(resolver.supportsParameter(new MethodParameter(tmfMethod, 0)));
    assertFalse(resolver.supportsParameter(new MethodParameter(plainMethod, 0)));
  }

  @Test
  void usesDefaultLimitWhenNoOffsetOrLimitParam() throws Exception {
    TmfRichPageableHandlerMethodArgumentResolver resolver =
        new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS);
    MockHttpServletRequest request = new MockHttpServletRequest();

    TmfRichPageable pageable =
        resolver.resolveArgument(
            tmfPageableParameter(),
            new ModelAndViewContainer(),
            new ServletWebRequest(request),
            null);

    assertEquals(50, pageable.getPageSize());
    assertEquals(0, pageable.getOffset());
  }

  private MethodParameter tmfPageableParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("rich", TmfRichPageable.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void rich(TmfRichPageable pageable) { /* signature-only stub for MethodParameter reflection */ }

    @SuppressWarnings("unused")
    void plain(Pageable pageable) { /* signature-only stub for MethodParameter reflection */ }
  }
}
