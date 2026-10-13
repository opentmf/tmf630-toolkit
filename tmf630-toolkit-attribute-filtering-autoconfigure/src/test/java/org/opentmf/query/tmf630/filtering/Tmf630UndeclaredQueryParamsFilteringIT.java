package org.opentmf.query.tmf630.filtering;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.querydsl.core.types.Predicate;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The undeclared-query-parameter guard beside the filter grammar, under REJECT on both sides: a
 * TMF-630 list is left to the grammar (which refuses its own unknown names in its own words),
 * except for Spring's {@code page}/{@code size}, which the grammar skips as reserved and the guard
 * refuses (3.5.1); a single read beside it is judged by the guard — {@code @Tmf630PassThrough}
 * included.
 *
 * <p>{@code Item}'s fields are bound reflectively by the predicate resolver (Sonar S1068 does not
 * model that).
 */
@SpringBootTest(
    classes = Tmf630UndeclaredQueryParamsFilteringIT.TestApp.class,
    properties = {
      "opentmf.tmf630.undeclared-query-params.on-undeclared=REJECT",
      "opentmf.tmf630.attribute-filtering.on-unknown-field=REJECT",
      "opentmf.tmf630.attribute-filtering.on-unknown-operator=REJECT"
    })
@AutoConfigureMockMvc
@SuppressWarnings("java:S1068")
class Tmf630UndeclaredQueryParamsFilteringIT {

  @Autowired private MockMvc mockMvc;

  @Test
  void aListTakesItsFiltersAndTheTmfNames_theGuardDoesNotJudgeIt() throws Exception {
    mockMvc
        .perform(
            get("/items")
                .param("status.eq", "active")
                .param("offset", "0")
                .param("limit", "2")
                .param("sort", "-name")
                .param("fields", "name"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("status")));
  }

  @Test
  void aListsUnknownFilterIsRefusedByTheGrammar_notTheGuard() throws Exception {
    mockMvc
        .perform(get("/items").param("stauts.eq", "active"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value(not("Unknown query parameter.")));
  }

  @Test
  void aListRefusesSpringsPageAndSize_whichTheGrammarWouldLetPass() throws Exception {
    mockMvc
        .perform(get("/items").param("status.eq", "active").param("page", "0").param("size", "10"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("Unknown query parameter."))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "unknown query parameters 'page', 'size' — GET /items is a TMF-630 list,"
                        + " which pages with offset and limit"));
  }

  @Test
  void aFieldNamedSizeIsStillAFilterWithAnExplicitOperator() throws Exception {
    mockMvc
        .perform(get("/items").param("size.eq", "M"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("size")));
  }

  @Test
  void aListThatBindsSizeItselfKeepsIt_andStillRefusesPage() throws Exception {
    mockMvc.perform(get("/sized-items").param("size", "M")).andExpect(status().isOk());
    mockMvc
        .perform(get("/sized-items").param("size", "M").param("page", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(startsWith("unknown query parameter 'page' —")));
  }

  @Test
  void aSingleReadTakesItsParamsAndPassThroughNames_andRefusesAnythingElse() throws Exception {
    mockMvc
        .perform(get("/items/1").param("lang", "de").param("version", "main"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/items/1").param("status.eq", "active"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("Unknown query parameter."))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "unknown query parameter 'status.eq' — GET /items/{id} takes only fields,"
                        + " lang, version"));
  }

  @SpringBootApplication
  @Import(ItemController.class)
  static class TestApp {}

  @RestController
  static class ItemController {

    @GetMapping("/items")
    public String list(@QuerydslPredicate(root = Item.class) Predicate predicate, Pageable p) {
      return String.valueOf(predicate);
    }

    @GetMapping("/sized-items")
    public String sized(
        @RequestParam(name = "size", required = false) String size,
        @QuerydslPredicate(root = Item.class) Predicate predicate) {
      return size + "|" + predicate;
    }

    @GetMapping("/items/{id}")
    @Tmf630Response
    @Tmf630PassThrough({"version"})
    public String read(@PathVariable("id") String id, @RequestParam("lang") String lang) {
      return id + lang;
    }
  }

  static class Item {
    private String name;
    private String status;
    private String size;
  }
}
