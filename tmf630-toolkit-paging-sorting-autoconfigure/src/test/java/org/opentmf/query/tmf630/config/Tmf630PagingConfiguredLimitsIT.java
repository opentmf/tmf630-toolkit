package org.opentmf.query.tmf630.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression IT for the 3.1.1 fix: {@code default-limit} / {@code max-limit} must reach
 * the plain-{@code Pageable} resolver's Spring Data parent. Before the fix, the
 * no-offset/limit fallback served Spring's hard-coded 20 regardless of
 * {@code default-limit}, and a Spring-grammar {@code ?size=} was capped by Spring's own
 * 2000 instead of {@code max-limit}. Deliberately non-default values (7 / 9) so a pass
 * can't be satisfied by any built-in constant.
 */
@SpringBootTest(
    classes = Tmf630PagingConfiguredLimitsIT.TestApp.class,
    properties = {
      "opentmf.tmf630.paging.enabled=true",
      "opentmf.tmf630.paging.default-limit=7",
      "opentmf.tmf630.paging.max-limit=9"
    })
@AutoConfigureMockMvc
class Tmf630PagingConfiguredLimitsIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("no paging params → configured default-limit, not Spring's 20")
  void noParamsUsesConfiguredDefaultLimit() throws Exception {
    mockMvc.perform(get("/limits")).andExpect(status().isOk()).andExpect(content().string("0:7"));
  }

  @Test
  @DisplayName("Spring-grammar ?size= above max-limit is capped at max-limit")
  void springModeSizeIsCappedAtMaxLimit() throws Exception {
    mockMvc
        .perform(get("/limits").param("page", "0").param("size", "100"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:9"));
  }

  @Test
  @DisplayName("Spring-grammar ?size= below max-limit passes through")
  void springModeSizeBelowCapPassesThrough() throws Exception {
    mockMvc
        .perform(get("/limits").param("page", "1").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(content().string("5:5"));
  }

  @Test
  @DisplayName("TMF-grammar limit= above max-limit is still capped at max-limit")
  void tmfModeLimitIsCappedAtMaxLimit() throws Exception {
    mockMvc
        .perform(get("/limits").param("offset", "0").param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:9"));
  }

  @Test
  @DisplayName("TMF-grammar offset without limit → configured default-limit")
  void tmfModeOffsetWithoutLimitUsesConfiguredDefaultLimit() throws Exception {
    mockMvc
        .perform(get("/limits").param("offset", "14"))
        .andExpect(status().isOk())
        .andExpect(content().string("14:7"));
  }

  @SpringBootApplication
  @Import(LimitsController.class)
  static class TestApp {}

  @RestController
  static class LimitsController {
    @GetMapping("/limits")
    public String limits(Pageable pageable) {
      return pageable.getOffset() + ":" + pageable.getPageSize();
    }
  }
}
