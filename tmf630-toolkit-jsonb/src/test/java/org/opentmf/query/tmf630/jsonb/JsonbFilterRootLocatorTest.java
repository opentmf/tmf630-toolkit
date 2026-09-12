package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.core.MethodParameter;

class JsonbFilterRootLocatorTest {

  private final JsonbFilterRootLocator locator = new JsonbFilterRootLocator();

  @Test
  void locatesTheDomainRootOfAJsonbFilterParameter() throws Exception {
    assertThat(locator.locate(new MethodParameter(searchMethod(), 0))).contains(Domain.class);
  }

  @Test
  void ignoresEveryOtherParameter() throws Exception {
    assertThat(locator.locate(new MethodParameter(searchMethod(), 1))).isEmpty();
  }

  private static Method searchMethod() throws Exception {
    return Handlers.class.getDeclaredMethod("search", JsonbClause.class, TmfSort.class);
  }

  @SuppressWarnings("unused")
  private static class Handlers {
    void search(@Tmf630JsonbFilter(root = Domain.class) JsonbClause clause, TmfSort sort) {
      // signature-only stub for MethodParameter reflection
    }
  }

  private static class Domain {}
}
