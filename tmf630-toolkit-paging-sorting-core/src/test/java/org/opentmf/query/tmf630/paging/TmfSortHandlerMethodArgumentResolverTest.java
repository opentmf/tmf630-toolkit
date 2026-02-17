package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

class TmfSortHandlerMethodArgumentResolverTest {

  @Test
  void resolvesSignedSortTokensIncludingPlusDecodedAsSpace() throws Exception {
    TmfSortParser parser = new TmfSortParser(java.util.List.of(), false);
    TmfSortHandlerMethodArgumentResolver resolver = new TmfSortHandlerMethodArgumentResolver(parser);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", " transformationId", "-createdOn");

    Sort sort =
        (Sort)
            resolver.resolveArgument(
                sortParameter(),
                new ModelAndViewContainer(),
                new ServletWebRequest(request),
                null);

    assertEquals(2, sort.toList().size());
    assertEquals("transformationId", sort.toList().get(0).getProperty());
    assertEquals(Sort.Direction.ASC, sort.toList().get(0).getDirection());
    assertEquals("createdOn", sort.toList().get(1).getProperty());
    assertEquals(Sort.Direction.DESC, sort.toList().get(1).getDirection());
  }

  private MethodParameter sortParameter() throws Exception {
    Method method = ControllerStub.class.getDeclaredMethod("search", Sort.class);
    return new MethodParameter(method, 0);
  }

  private static class ControllerStub {
    @SuppressWarnings("unused")
    void search(Sort sort) {}
  }
}
