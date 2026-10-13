package org.opentmf.query.tmf630.config;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.advice.Tmf630UndeclaredQueryParameterExceptionHandler;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.queryparams.Tmf630AcceptsAnyQueryParameter;
import org.opentmf.query.tmf630.queryparams.Tmf630AcceptsQueryParameters;
import org.opentmf.query.tmf630.queryparams.Tmf630DeclaredQueryParameters;
import org.opentmf.query.tmf630.queryparams.Tmf630QueryArgument;
import org.opentmf.query.tmf630.queryparams.Tmf630QueryArgumentClassifier;
import org.opentmf.query.tmf630.queryparams.Tmf630QueryParameterContract;
import org.opentmf.query.tmf630.queryparams.Tmf630UndeclaredQueryParameterListener;
import org.opentmf.query.tmf630.queryparams.UndeclaredQueryParamBehavior;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The undeclared-query-parameter guard end to end: IGNORE by default (a no-op bump), REJECT with
 * the toolkit's error body or the service's own ProblemDetail, WARN served and counted, the
 * extension points, and the handler ↔ OAS parity a service's contract test runs.
 */
class Tmf630UndeclaredQueryParamsIT {

  @Nested
  @SpringBootTest(classes = TestApp.class)
  @AutoConfigureMockMvc
  class DefaultIgnore {

    @Autowired private MockMvc mockMvc;
    @Autowired private Counter counter;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void reset() {
      counter.calls.set(0);
    }

    @Test
    @DisplayName("default IGNORE: an undeclared parameter reaches the handler, as before 3.5.0")
    void undeclaredIsIgnored() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&variantKey=winback"))
          .andExpect(status().isOk())
          .andExpect(content().string("1:v:null"));
      assertEquals(1, counter.calls.get());
    }

    @Test
    @DisplayName("default IGNORE registers no refusal handler, but the contract bean exists")
    void onlyTheDeclaredSetBeanIsRegistered() {
      assertTrue(
          context
              .getBeansOfType(Tmf630UndeclaredQueryParameterExceptionHandler.class)
              .isEmpty());
      assertEquals(1, context.getBeansOfType(Tmf630DeclaredQueryParameters.class).size());
    }
  }

  @Nested
  @SpringBootTest(
      classes = TestApp.class,
      properties = {
        // relaxed binding: the enum is matched case-insensitively, by the guard's condition too
        "opentmf.tmf630.undeclared-query-params.on-undeclared=reject",
        "opentmf.tmf630.undeclared-query-params.non-query-arguments="
            + "org.opentmf.query.tmf630.config.Tmf630UndeclaredQueryParamsIT.AppBody",
        "opentmf.tmf630.query-limits.max-param-value-length=40"
      })
  @AutoConfigureMockMvc
  class Reject {

    @Autowired private MockMvc mockMvc;
    @Autowired private Counter counter;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void reset() {
      counter.calls.set(0);
    }

    @Test
    @DisplayName("a declared parameter reaches the handler")
    void declaredPasses() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&variant=winback"))
          .andExpect(status().isOk())
          .andExpect(content().string("1:v:winback"));
      assertEquals(1, counter.calls.get());
    }

    @Test
    @DisplayName("a misspelt parameter → 400 TMF body naming it, the route and what it takes")
    void misspeltIs400() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&variantKey=winback"))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.code").value("400"))
          .andExpect(jsonPath("$.status").value("Bad Request"))
          .andExpect(jsonPath("$.reason").value("Unknown query parameter."))
          .andExpect(
              jsonPath("$.message")
                  .value(
                      "unknown query parameter 'variantKey' — GET /single/{id} takes only"
                          + " variant, version"));
      assertEquals(0, counter.calls.get(), "the handler must not run");
    }

    @Test
    @DisplayName("HEAD is judged like GET; POST is not judged")
    void onlyReadsAreJudged() throws Exception {
      mockMvc.perform(head("/single/1?version=v&x=1")).andExpect(status().isBadRequest());
      mockMvc.perform(post("/single/1?anything=1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("fields is declared where @Tmf630Response projects, and only there")
    void fieldsWhereTheProjectionRuns() throws Exception {
      mockMvc.perform(get("/projected/1?fields=name")).andExpect(status().isOk());
      mockMvc
          .perform(get("/single/1?version=v&fields=name"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value(startsWith("unknown query parameter 'fields'")));
    }

    @Test
    @DisplayName("a Pageable declares offset, limit and sort; Spring's page/size are refused")
    void pagingNames() throws Exception {
      mockMvc.perform(get("/history?offset=0&limit=5&sort=-id")).andExpect(status().isOk());
      mockMvc
          .perform(get("/history?page=1&size=5"))
          .andExpect(status().isBadRequest())
          .andExpect(
              jsonPath("$.message")
                  .value(
                      "unknown query parameters 'page', 'size' — GET /history takes only"
                          + " limit, offset, sort"));
    }

    @Test
    @DisplayName("a catch-all states its vocabulary; without it, or with accepts-any, it is open")
    void catchAllsAndOptOuts() throws Exception {
      mockMvc.perform(get("/forward?incidentType=failedJob")).andExpect(status().isOk());
      mockMvc.perform(get("/forward?incidentTyp=failedJob")).andExpect(status().isBadRequest());
      mockMvc.perform(get("/catch-all?anything=1")).andExpect(status().isOk());
      mockMvc.perform(get("/callback?Recipient=a&BounceCode=5")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("non-query-arguments closes a handler with an application-resolved body")
    void nonQueryArgumentsProperty() throws Exception {
      mockMvc.perform(get("/body-like/1")).andExpect(status().isOk());
      mockMvc.perform(get("/body-like/1?x=1")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an application classifier declares what its own argument reads")
    void classifierBean() throws Exception {
      mockMvc
          .perform(get("/tenant?tenant=acme"))
          .andExpect(status().isOk())
          .andExpect(content().string("acme"));
      mockMvc.perform(get("/tenant?tenantId=acme")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the refusal handler wins over the application's catch-all")
    void winsOverCatchAll() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&nope=1"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.reason").value("Unknown query parameter."));
    }

    @Test
    @DisplayName("the query-limits guard runs first: an oversize undeclared value is 'too long'")
    void queryLimitsFirst() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&nope=" + "a".repeat(41)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.reason").value("Query parameter too long."));
    }

    @Test
    @DisplayName("every closed probe route declares exactly what the OAS declares")
    void parityWithTheContract() throws IOException {
      Tmf630QueryParameterContract contract =
          Tmf630QueryParameterContract.of(context)
              .handlers(handler -> handler.getBeanType() == ProbeController.class);
      try (InputStream oas =
          getClass().getResourceAsStream("/openapi/undeclared-probe.oas.yaml")) {
        assertEquals(Map.of(), contract.drift(Tmf630QueryParameterContract.readYaml(oas)));
      }
      assertEquals(
          Set.of(
              "GET /callback → ProbeController#callback",
              "GET /catch-all → ProbeController#catchAll"),
          contract.openRoutes());
    }
  }

  @Nested
  @SpringBootTest(
      classes = ProblemDetailApp.class,
      properties = {
        "opentmf.tmf630.undeclared-query-params.on-undeclared=REJECT",
        "opentmf.tmf630.undeclared-query-params.exception-handler.enabled=false",
        "spring.mvc.problemdetails.enabled=true"
      })
  @AutoConfigureMockMvc
  class ServiceProblemDetail {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("toolkit handler off: the service's ProblemDetail renders the binding error")
    void problemDetail() throws Exception {
      mockMvc
          .perform(get("/single/1?version=v&variantKey=winback"))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(
              jsonPath("$.detail")
                  .value(
                      "unknown query parameter 'variantKey' — GET /single/{id} takes only"
                          + " variant, version"));
      assertTrue(
          context
              .getBeansOfType(Tmf630UndeclaredQueryParameterExceptionHandler.class)
              .isEmpty());
    }
  }

  @Nested
  @SpringBootTest(
      classes = {TestApp.class, Metrics.class},
      properties = "opentmf.tmf630.undeclared-query-params.on-undeclared=WARN")
  @AutoConfigureMockMvc
  class Warn {

    @Autowired private MockMvc mockMvc;
    @Autowired private Counter counter;
    @Autowired private MeterRegistry meters;
    @Autowired private HeardListener heard;

    @Test
    @DisplayName("WARN: the request is served, reported to listeners and counted by route")
    void servedReportedAndCounted() throws Exception {
      counter.calls.set(0);
      mockMvc
          .perform(get("/single/1?version=v&variantKey=winback"))
          .andExpect(status().isOk())
          .andExpect(content().string("1:v:null"));
      assertEquals(1, counter.calls.get());
      assertTrue(heard.events.contains("GET /single/{id} [variantKey] WARN"));
      assertEquals(
          1.0,
          meters
              .get("opentmf.tmf630.undeclared.query.params")
              .tag("method", "GET")
              .tag("uri", "/single/{id}")
              .tag("outcome", "warned")
              .counter()
              .count());
      assertFalse(
          meters.getMeters().stream()
              .anyMatch(meter -> meter.getId().getTags().toString().contains("variantKey")),
          "a caller-chosen name must never become a tag");
    }
  }

  // ---------------------------------------------------------------------------------------------

  @SpringBootApplication
  @Import({ProbeController.class, Counter.class, CatchAll.class, AppResolvers.class})
  static class TestApp {}

  /** A service with its own ProblemDetail rendering and no catch-all of its own. */
  @SpringBootApplication
  @Import({ProbeController.class, Counter.class, AppResolvers.class})
  static class ProblemDetailApp {}

  @Configuration
  static class Metrics {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    HeardListener heardListener() {
      return new HeardListener();
    }
  }

  static class HeardListener implements Tmf630UndeclaredQueryParameterListener {
    final List<String> events = new ArrayList<>();

    @Override
    public void undeclared(
        String method,
        String route,
        Set<String> undeclared,
        UndeclaredQueryParamBehavior behavior) {
      events.add(method + " " + route + " " + undeclared + " " + behavior);
    }
  }

  static class Counter {
    final AtomicInteger calls = new AtomicInteger();
  }

  /** Marks an argument an application resolver fills from the body — like catalog's own. */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  @interface AppBody {}

  /** An argument type only an application resolver knows. */
  record TenantScope(String tenant) {}

  /** A projected body. */
  public static class Item {
    private final String id;
    private final String name;

    Item(String id) {
      this.id = id;
      this.name = "item-" + id;
    }

    public String getId() {
      return id;
    }

    public String getName() {
      return name;
    }
  }

  @RestController
  static class ProbeController {

    private final Counter counter;

    ProbeController(Counter counter) {
      this.counter = counter;
    }

    @GetMapping("/single/{id}")
    public String single(
        @PathVariable("id") String id,
        @RequestParam(name = "version") String version,
        @RequestParam(name = "variant", required = false) String variant) {
      counter.calls.incrementAndGet();
      return id + ":" + version + ":" + variant;
    }

    @PostMapping("/single/{id}")
    public String update(@PathVariable("id") String id) {
      return id;
    }

    @GetMapping("/projected/{id}")
    @Tmf630Response
    public Item projected(@PathVariable("id") String id) {
      return new Item(id);
    }

    @GetMapping("/history")
    public String history(Pageable pageable) {
      return String.valueOf(pageable.getPageSize());
    }

    @GetMapping("/forward")
    @Tmf630AcceptsQueryParameters({"incidentType"})
    public String forward(@RequestParam Map<String, String> query) {
      return String.valueOf(query);
    }

    @GetMapping("/catch-all")
    public String catchAll(@RequestParam Map<String, String> query) {
      return String.valueOf(query);
    }

    @GetMapping("/callback")
    @Tmf630AcceptsAnyQueryParameter
    public String callback(@RequestParam("Recipient") String recipient) {
      return recipient;
    }

    @GetMapping("/body-like/{id}")
    public String bodyLike(@PathVariable("id") String id, @AppBody String body) {
      return id + body;
    }

    @GetMapping("/tenant")
    public String tenant(TenantScope scope) {
      return scope.tenant();
    }
  }

  @Configuration
  static class AppResolvers implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
      resolvers.add(0, new AppResolver());
    }

    /** What {@code TenantScope} reads, told to the guard. */
    @Bean
    Tmf630QueryArgumentClassifier tenantClassifier() {
      return parameter ->
          parameter.getParameterType() == TenantScope.class
              ? Tmf630QueryArgument.named("tenant")
              : null;
    }
  }

  static class AppResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
      return parameter.hasParameterAnnotation(AppBody.class)
          || parameter.getParameterType() == TenantScope.class;
    }

    @Override
    public Object resolveArgument(
        MethodParameter parameter,
        ModelAndViewContainer mavContainer,
        NativeWebRequest webRequest,
        WebDataBinderFactory binderFactory) {
      return parameter.getParameterType() == TenantScope.class
          ? new TenantScope(webRequest.getParameter("tenant"))
          : "";
    }
  }

  @RestControllerAdvice(assignableTypes = ProbeController.class)
  static class CatchAll {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
      return ResponseEntity.status(500).body("catch-all");
    }
  }
}
