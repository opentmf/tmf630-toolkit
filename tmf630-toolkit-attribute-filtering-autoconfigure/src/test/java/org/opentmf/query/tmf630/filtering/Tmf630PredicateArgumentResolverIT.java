package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = Tmf630PredicateArgumentResolverIT.TestApp.class,
    properties = {
      "opentmf.tmf630.attribute-filtering.allowlist.mode=DENY_ALL",
      "opentmf.tmf630.attribute-filtering.allowlist.entities.TestEntity=transformationId,createdOn,status",
      "opentmf.tmf630.attribute-filtering.regex.enabled=false",
      "opentmf.tmf630.attribute-filtering.onUnknownField=REJECT",
      "opentmf.tmf630.attribute-filtering.onUnknownOperator=REJECT"
    })
@AutoConfigureMockMvc
class Tmf630PredicateArgumentResolverIT {

  @org.springframework.beans.factory.annotation.Autowired
  private MockMvc mockMvc;

  @Test
  void buildsPredicateForRangeAndEqAndIgnoresPagingParams() throws Exception {
    mockMvc
        .perform(
            get("/search")
                .param("createdOn.gte", "2026-01-01T00:00:00Z")
                .param("createdOn.lt", "2026-02-01T00:00:00Z")
                .param("transformationId.eq", "abc")
                .param("page", "0")
                .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("createdOn")))
        .andExpect(content().string(Matchers.containsString("transformationId")));
  }

  @Test
  void rejectsUnknownFieldWhenAllowlistDenied() throws Exception {
    mockMvc
        .perform(get("/search").param("forbidden.eq", "x"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsUnknownOperatorWhenConfiguredToReject() throws Exception {
    mockMvc
        .perform(get("/search").param("transformationId.badop", "abc"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void supportsImplicitEq() throws Exception {
    mockMvc
        .perform(get("/search").param("transformationId", "abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("transformationId")));
  }

  @Test
  void supportsLikeiAndInOperators() throws Exception {
    mockMvc
        .perform(
            get("/search")
                .param("transformationId.likei", "%abc%")
                .param("status.in", "NEW", "DONE"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("transformationId")))
        .andExpect(content().string(Matchers.containsString("status")));
  }

  @SpringBootApplication
  @Import(TestController.class)
  static class TestApp {}

  @RestController
  static class TestController {

    @GetMapping("/search")
    public String search(@QuerydslPredicate(root = TestEntity.class) Predicate predicate) {
      return predicate == null ? "null" : predicate.toString();
    }
  }

  static class TestEntity {
    private String transformationId;
    private Instant createdOn;
    private String status;
  }
}
