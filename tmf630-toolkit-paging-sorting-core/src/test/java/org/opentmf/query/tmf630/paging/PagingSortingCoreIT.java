package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class PagingSortingCoreIT {

  @Test
  void resolverBuildsOffsetLimitPageableFromTmfParameters() throws Exception {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 50, 100, true, false, null);
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(settings);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("offset", "15");
    request.setParameter("limit", "10");
    request.setParameter("sort", "-createdOn");

    OffsetLimitPageRequest pageRequest =
        (OffsetLimitPageRequest)
            resolver.resolveArgument(
                TestFixtures.pageableParameter(),
                null,
                new ServletWebRequest(request),
                null);

    assertEquals(15, pageRequest.getOffset());
    assertEquals(10, pageRequest.getPageSize());
    assertEquals("createdOn", pageRequest.getSort().toList().get(0).getProperty());
  }
}
