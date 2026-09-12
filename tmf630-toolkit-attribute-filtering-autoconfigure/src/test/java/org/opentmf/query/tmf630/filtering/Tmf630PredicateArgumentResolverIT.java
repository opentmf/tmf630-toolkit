package org.opentmf.query.tmf630.filtering;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.querydsl.core.types.Predicate;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
/**
 * {@code TestEntity} declares JavaBean-style fields ({@code transformationId}, {@code createdOn},
 * {@code status}) that are never read from Java code — Spring's {@link QuerydslPredicate} argument
 * resolver binds request parameters to them reflectively through the entity's declared fields.
 * Sonar S1068 flags the fields as "unused" because it does not model that reflective binding;
 * deleting them would silently break the resolver contract these tests pin. Suppress at class
 * level with intent.
 */
@SuppressWarnings("java:S1068")
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
  void ignoresOffsetLimitAndFieldsAlongsideAttributeFiltering() throws Exception {
    mockMvc
        .perform(
            get("/search")
                .param("transformationId.eq", "abc")
                .param("offset", "0")
                .param("limit", "2")
                .param("fields", "transformationId,status")
                .param("sort", "-createdOn"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("transformationId")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("offset"))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("limit"))))
        .andExpect(content().string(Matchers.not(Matchers.containsString("fields"))));
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

  @Test
  void supportsRestrictedJsonPathFilterAndCombinesWithAttributeFilters() throws Exception {
    mockMvc
        .perform(
            get("/search")
                .param("transformationId.eq", "abc")
                .param("filter", "$[?(@.status == 'NEW')]"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("transformationId")))
        .andExpect(content().string(Matchers.containsString("status")));
  }

  @Test
  void rejectsInvalidJsonPathFilterExpression() throws Exception {
    mockMvc
        .perform(get("/search").param("filter", "$.status"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void filteringExceptionHandlerReturns400WithStructuredBodyEvenWhenAppHasCatchAllHandler()
      throws Exception {
    // Simulates a consuming service that has @ExceptionHandler(Exception.class) returning 500.
    // Our Tmf630FilteringExceptionHandler at HIGHEST_PRECEDENCE must win and return 400.
    mockMvc
        .perform(get("/search").param("forbidden.eq", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("400"))
        .andExpect(jsonPath("$.status").value("Bad Request"))
        .andExpect(jsonPath("$.reason").value("Invalid filter parameter."))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  @Test
  void filteringExceptionBodyContainsFieldNameAndFormatHintForTemporalType() throws Exception {
    // Verify rich error message: field name + ISO format hint
    mockMvc
        .perform(get("/search").param("createdOn.eq", "2025-01-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("createdOn")))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Instant")));
  }

  @Test
  void passThroughDeclaredOnApiInterfaceReachesHandlerNotFilterGrammar() throws Exception {
    mockMvc
        .perform(
            get("/scoped-search").param("version", "v1").param("transformationId.eq", "abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.startsWith("v1|")))
        .andExpect(content().string(Matchers.containsString("transformationId")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("version"))));
  }

  @Test
  void passThroughDoesNotLoosenTheAllowlistForOtherNames() throws Exception {
    mockMvc
        .perform(get("/scoped-search").param("version", "v1").param("forbidden.eq", "x"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(Matchers.containsString("forbidden")));
  }

  @Test
  void passThroughNameIsStillAFilterOnHandlersThatDoNotDeclareIt() throws Exception {
    mockMvc
        .perform(get("/search").param("version", "v1"))
        .andExpect(status().isBadRequest());
  }

  @SpringBootApplication
  @Import({TestController.class, ScopedSearchController.class, CatchAllExceptionHandler.class})
  static class TestApp {}

  /** API-interface form (mapping, bindings and pass-through declared here), as consumers use. */
  interface ScopedSearchApi {

    @GetMapping("/scoped-search")
    @Tmf630PassThrough({"version"})
    String search(
        @RequestParam(name = "version") String version,
        @QuerydslPredicate(root = TestEntity.class) Predicate predicate);
  }

  @RestController
  static class ScopedSearchController implements ScopedSearchApi {

    @Override
    public String search(String version, Predicate predicate) {
      return version + "|" + predicate;
    }
  }

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

  /** Simulates a consuming application's generic exception handler that returns 500. */
  @RestControllerAdvice
  static class CatchAllExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAll(Exception ex) {
      return ResponseEntity.internalServerError().body("Internal error: " + ex.getMessage());
    }
  }
}
