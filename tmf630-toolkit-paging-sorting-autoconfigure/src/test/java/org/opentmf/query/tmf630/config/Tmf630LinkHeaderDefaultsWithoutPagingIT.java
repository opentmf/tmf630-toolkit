package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * With the paging auto-configuration switched off there are no {@code Tmf630PagingProperties};
 * the response advice must still be created and must apply the default {@code Link} budget.
 */
@SpringBootTest(
    classes = Tmf630LinkHeaderDefaultsWithoutPagingIT.TestApp.class,
    properties = "opentmf.tmf630.paging.enabled=false")
@AutoConfigureMockMvc
class Tmf630LinkHeaderDefaultsWithoutPagingIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private ApplicationContext context;

  @Test
  void defaultBudgetAppliesWhenPagingPropertiesAreAbsent() throws Exception {
    assertFalse(context.containsBean("tmf630WebMvcConfigurer"));

    mockMvc
        .perform(get("/no-paging/items?status=" + "a".repeat(256) + "&offset=10&limit=10"))
        .andExpect(status().isPartialContent())
        .andExpect(header().exists("Link"));
    mockMvc
        .perform(get("/no-paging/items?status=" + "a".repeat(257) + "&offset=10&limit=10"))
        .andExpect(status().isPartialContent())
        .andExpect(header().doesNotExist("Link"));
  }

  @SpringBootApplication
  @Import(NoPagingController.class)
  static class TestApp {}

  @RestController
  static class NoPagingController {
    @GetMapping("/no-paging/items")
    @Tmf630Response
    public Page<String> items() {
      return new PageImpl<>(List.of("x"), new OffsetLimitPageRequest(10, 10, Sort.unsorted()), 50);
    }
  }
}
