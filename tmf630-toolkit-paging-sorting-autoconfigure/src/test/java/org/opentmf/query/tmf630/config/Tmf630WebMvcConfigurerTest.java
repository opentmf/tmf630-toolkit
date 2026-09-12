package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class Tmf630WebMvcConfigurerTest {

  @Test
  void legacyConstructorRegistersTheResolversWithoutASortKeyValidator() throws Exception {
    List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
    new Tmf630WebMvcConfigurer(new Tmf630PagingProperties()).addArgumentResolvers(resolvers);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", "-nosuch");
    MethodParameter sortParameter =
        new MethodParameter(Handlers.class.getDeclaredMethod("search", Sort.class), 0);
    Sort sort =
        (Sort)
            resolvers
                .get(0)
                .resolveArgument(
                    sortParameter,
                    new ModelAndViewContainer(),
                    new ServletWebRequest(request),
                    null);

    assertEquals(5, resolvers.size());
    assertEquals("nosuch", sort.toList().get(0).getProperty());
  }

  @SuppressWarnings("unused")
  private static class Handlers {
    void search(Sort sort) {
      // signature-only stub for MethodParameter reflection
    }
  }
}
