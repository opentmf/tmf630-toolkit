package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.method.support.ModelAndViewContainer;

class TmfRichSortHandlerMethodArgumentResolverTest {

  @Test
  void resolvesPlainTermsAndDoesNotRequireAggregation() throws Exception {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfRichSortHandlerMethodArgumentResolver resolver =
        new TmfRichSortHandlerMethodArgumentResolver(parser);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", "-createdOn,+id");

    TmfSort sort =
        (TmfSort)
            resolver.resolveArgument(
                sortParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertEquals(2, sort.terms().size());
    assertFalse(sort.requiresAggregation());
  }

  @Test
  void resolvesJsonPathTermsAndFlagsRequiresAggregation() throws Exception {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfRichSortHandlerMethodArgumentResolver resolver =
        new TmfRichSortHandlerMethodArgumentResolver(parser);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", "$.arr[?(@.id == 'X')].value");

    TmfSort sort =
        (TmfSort)
            resolver.resolveArgument(
                sortParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertEquals(1, sort.terms().size());
    assertTrue(sort.requiresAggregation());
  }

  @Test
  void supportsTmfSortParameterButNotPlainSortParameter() throws Exception {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfRichSortHandlerMethodArgumentResolver resolver =
        new TmfRichSortHandlerMethodArgumentResolver(parser);

    Method tmfMethod = ControllerStub.class.getDeclaredMethod("searchRich", TmfSort.class);
    Method plainMethod =
        ControllerStub.class.getDeclaredMethod(
            "searchPlain", Sort.class);

    assertTrue(resolver.supportsParameter(new MethodParameter(tmfMethod, 0)));
    assertFalse(resolver.supportsParameter(new MethodParameter(plainMethod, 0)));
  }

  @Test
  void resolvesEmptyToEmptyTmfSort() throws Exception {
    TmfSortParser parser = new TmfSortParser(List.of(), true);
    TmfRichSortHandlerMethodArgumentResolver resolver =
        new TmfRichSortHandlerMethodArgumentResolver(parser);

    MockHttpServletRequest request = new MockHttpServletRequest();

    TmfSort sort =
        (TmfSort)
            resolver.resolveArgument(
                sortParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertTrue(sort.isEmpty());
  }

  private MethodParameter sortParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("searchRich", TmfSort.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void searchRich(TmfSort sort) { /* signature-only stub for MethodParameter reflection */ }

    @SuppressWarnings("unused")
    void searchPlain(Sort sort) { /* signature-only stub for MethodParameter reflection */ }
  }
}
