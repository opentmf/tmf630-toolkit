package org.opentmf.query.tmf630.queryparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.types.Predicate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Handler ↔ OAS parity over a real {@code RequestMappingHandlerMapping}: the check the template's
 * contract tests run, ported from dnms-catalog's {@code ParityWithTheContract} — without its
 * {@code fields} allowance: the OAS declares {@code fields} wherever the projection runs.
 */
class Tmf630QueryParameterContractTest {

  @RestController
  @SuppressWarnings("unused")
  static class TemplateController {

    @GetMapping("/templates/content")
    public String content(
        @RequestParam("docId") String docId,
        @RequestParam(name = "lang", required = false) String lang,
        @RequestHeader(name = "If-None-Match", required = false) String etag) {
      return docId;
    }

    @GetMapping("/templates/{id:[a-z0-9-]+}")
    @Tmf630Response
    public String template(
        @PathVariable("id") String id, @RequestParam(name = "version") String version) {
      return id;
    }

    @GetMapping("/templates")
    public String list(Predicate predicate, Pageable pageable) {
      return "";
    }

    @PostMapping("/templates")
    public String create(@RequestParam("dryRun") boolean dryRun) {
      return "";
    }

    @GetMapping("/history")
    public String history(Pageable pageable) {
      return "";
    }

    @GetMapping("/undocumented")
    public String undocumented(@RequestParam("q") String q) {
      return q;
    }

    @GetMapping("/openapi")
    public String openApi() {
      return "";
    }
  }

  /** A library's controller the service does not own. */
  @RestController
  @SuppressWarnings("unused")
  static class LibraryController {

    @GetMapping("/ops/outbox")
    public String outbox(@RequestParam("state") String state) {
      return state;
    }
  }

  @RestController
  @RequestMapping("/v4")
  @SuppressWarnings("unused")
  static class PrefixedController {

    @GetMapping("/templates/content")
    public String content(
        @RequestParam("docId") String docId, @RequestParam("lang") String lang) {
      return docId;
    }
  }

  @Configuration
  @EnableWebMvc
  @Import({TemplateController.class, LibraryController.class, PrefixedController.class})
  static class WebConfig {}

  private AnnotationConfigWebApplicationContext context;

  @BeforeEach
  void start() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(WebConfig.class);
    context.refresh();
  }

  @AfterEach
  void stop() {
    context.close();
  }

  private static Map<String, Object> oas() throws IOException {
    try (InputStream in =
        Tmf630QueryParameterContractTest.class.getResourceAsStream(
            "/openapi/contract-probe.oas.yaml")) {
      return Tmf630QueryParameterContract.readYaml(in);
    }
  }

  private Tmf630QueryParameterContract serviceOwned() {
    return Tmf630QueryParameterContract.of(context)
        .handlers(handler -> handler.getBeanType() == TemplateController.class);
  }

  @Nested
  class Routes {

    @Test
    void everyServedGetIsListed_aPostIsNot() {
      List<String> routes =
          serviceOwned().routes().stream()
              .map(Tmf630QueryParameterContract.Route::toString)
              .toList();
      assertEquals(
          List.of(
              "GET /history → TemplateController#history",
              "GET /openapi → TemplateController#openApi",
              "GET /templates → TemplateController#list",
              "GET /templates/content → TemplateController#content",
              "GET /templates/{id:[a-z0-9-]+} → TemplateController#template",
              "GET /undocumented → TemplateController#undocumented"),
          routes);
    }

    @Test
    void theOnlyOpenReadIsTheTmf630List() {
      assertEquals(Set.of("GET /templates → TemplateController#list"), serviceOwned().openRoutes());
    }

    @Test
    void aClosedRouteCarriesItsDeclaredSet() {
      Tmf630QueryParameterContract.Route content =
          serviceOwned().routes().stream()
              .filter(route -> route.pattern().equals("/templates/content"))
              .findFirst()
              .orElseThrow();
      assertFalse(content.open());
      assertEquals(Set.of("docId", "lang"), content.declared());
    }
  }

  @Nested
  class Drift {

    @Test
    void everyDisagreementIsNamed_agreementIsSilent() throws IOException {
      SortedMap<String, String> drift = serviceOwned().drift(oas());

      assertEquals(
          Map.of(
              "GET /history → TemplateController#history",
              "handler [limit, offset, sort] vs contract [limit, offset]",
              "GET /undocumented → TemplateController#undocumented",
              "handler [q] vs contract []"),
          drift);
    }

    @Test
    void aClassLevelPrefixIsStrippedBeforeMatching() throws IOException {
      Tmf630QueryParameterContract prefixed =
          Tmf630QueryParameterContract.of(context)
              .handlers(handler -> handler.getBeanType() == PrefixedController.class);

      assertEquals(1, prefixed.drift(oas()).size());
      assertTrue(prefixed.withPathPrefix("/v4").drift(oas()).isEmpty());
    }

    @Test
    void withoutAHandlerFilterTheLibrarysRoutesAreJudgedToo() throws IOException {
      assertTrue(
          Tmf630QueryParameterContract.of(context)
              .drift(oas())
              .containsKey("GET /ops/outbox → LibraryController#outbox"));
    }
  }

  @Nested
  class Document {

    @Test
    void queryParametersOfEveryGet_pathItemLevelIncluded_refsResolved() throws IOException {
      assertEquals(
          Map.of(
              "/templates/content", Set.of("docId", "lang"),
              "/templates/{templateId}", Set.of("fields", "version"),
              "/history", Set.of("offset", "limit"),
              "/templates", Set.of("name")),
          Tmf630QueryParameterContract.documentedQueryParameters(oas()));
    }

    @Test
    void anExternalUnresolvableOrCyclicRefIsAnError() {
      assertRefFails(Map.of("$ref", "other.yaml#/components/parameters/X"), Map.of());
      assertRefFails(Map.of("$ref", "#/components/parameters/Missing"), Map.of());
      assertRefFails(
          Map.of("$ref", "#/components/parameters/A"),
          Map.of("A", Map.of("$ref", "#/components/parameters/A")));
    }

    private void assertRefFails(Map<String, Object> parameter, Map<String, Object> parameters) {
      Map<String, Object> document =
          Map.of(
              "paths", Map.of("/x", Map.of("get", Map.of("parameters", List.of(parameter)))),
              "components", Map.of("parameters", parameters));
      assertThrows(
          IllegalArgumentException.class,
          () -> Tmf630QueryParameterContract.documentedQueryParameters(document));
    }

    @Test
    void anEmptyDocumentDocumentsNothing() {
      Map<String, Object> empty =
          Tmf630QueryParameterContract.readYaml(new ByteArrayInputStream(new byte[0]));
      assertTrue(empty.isEmpty());
      assertTrue(Tmf630QueryParameterContract.documentedQueryParameters(empty).isEmpty());
    }
  }
}
