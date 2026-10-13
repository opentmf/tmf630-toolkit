package org.opentmf.query.tmf630.queryparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentmf.query.tmf630.queryparams.Tmf630DeclaredQueryParametersTest.handler;

import com.querydsl.core.types.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.exception.TmfUndeclaredQueryParameterException;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The guard on probe handlers — the cases of dnms-catalog's {@code UndeclaredQueryParameterGuard}
 * (CAT-UNKNOWN-QUERY-PARAM-400-1) and dnms-journal's {@code UnknownQueryParameterConfiguration},
 * ported, with the toolkit's rulings where the two differed: {@code sort} is declared wherever a
 * {@code Pageable} reads it, {@code fields} only where the projection runs, nothing is always
 * allowed, and the refusal is a {@link ServletRequestBindingException}.
 */
class Tmf630UndeclaredQueryParameterInterceptorTest {

  private final List<String> heard = new ArrayList<>();
  private final Tmf630UndeclaredQueryParameterListener listener =
      (method, route, undeclared, behavior) ->
          heard.add(method + " " + route + " " + undeclared + " " + behavior);

  private Tmf630UndeclaredQueryParameterInterceptor guard(UndeclaredQueryParamBehavior behavior) {
    return new Tmf630UndeclaredQueryParameterInterceptor(
        new Tmf630DeclaredQueryParameters(List.of()), behavior, listener);
  }

  private final Tmf630UndeclaredQueryParameterInterceptor reject =
      guard(UndeclaredQueryParamBehavior.REJECT);

  /**
   * Handlers shaped like a service's own: a single read, a TMF-630 list, a paged history read.
   * Signature-only: the guard reads their parameters, never the values bound to them.
   */
  @SuppressWarnings({"unused", "java:S1172"})
  static class Probe {
    public String single(
        @PathVariable("id") String id,
        @RequestParam(name = "version") String version,
        @RequestParam(name = "variant", required = false) String variant,
        @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
      return id;
    }

    @Tmf630Response
    public String projected(@PathVariable("id") String id) {
      return id;
    }

    public String bare() {
      return "";
    }

    public String list(@RequestParam("version") String version, Predicate predicate, Pageable p) {
      return version;
    }

    public String history(@RequestParam("type") String type, Pageable pageable) {
      return type;
    }

    public String map(@RequestParam Map<String, String> all) {
      return "";
    }

    public String raw(HttpServletRequest request) {
      return "";
    }
  }

  /** Stand-in for the api interfaces: the annotations live on the interface. */
  interface DetailApi {
    String view(@RequestParam(name = "lang", required = false) String lang);

    String role(@RequestParam(value = "role", required = false) String role);
  }

  static class DetailController implements DetailApi {
    @Override
    public String view(String lang) {
      return lang;
    }

    @Override
    public String role(String role) {
      return role;
    }
  }

  /** The pass-through shape: {@code @RequestParam Map} binds the WHOLE query string. */
  interface PassThroughApi {
    @Tmf630AcceptsQueryParameters({
      "processInstanceId",
      "processDefinitionKeyIn",
      "incidentType",
      "limit"
    })
    String forward(@RequestParam Map<String, String> query);
  }

  static class PassThroughController implements PassThroughApi {
    @Override
    public String forward(Map<String, String> query) {
      return String.valueOf(query);
    }
  }

  private static HandlerMethod probe(String name) {
    return handler(new Probe(), name);
  }

  private static MockHttpServletRequest request(
      String method, String pattern, String... nameValues) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, pattern);
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
    for (int i = 0; i < nameValues.length; i += 2) {
      request.addParameter(nameValues[i], nameValues[i + 1]);
    }
    return request;
  }

  private boolean pass(HandlerMethod handler, MockHttpServletRequest request) throws Exception {
    return reject.preHandle(request, new MockHttpServletResponse(), handler);
  }

  private TmfUndeclaredQueryParameterException refused(
      HandlerMethod handler, MockHttpServletRequest request) {
    return assertThrows(TmfUndeclaredQueryParameterException.class, () -> pass(handler, request));
  }

  @Nested
  class Reject {

    @Test
    void aDeclaredParameterPasses_aMisspeltOneIsRefusedByNameWithWhatTheReadTakes()
        throws Exception {
      HandlerMethod single = probe("single");

      assertTrue(
          pass(single, request("GET", "/single/{id}", "version", "v", "variant", "winback")));
      TmfUndeclaredQueryParameterException ex =
          refused(single, request("GET", "/single/{id}", "version", "v", "variantKey", "winback"));
      assertEquals(
          "unknown query parameter 'variantKey' — GET /single/{id} takes only variant, version",
          ex.getMessage());
      assertEquals(
          ex.getMessage(),
          ex.getBody().getDetail(),
          "the problem detail the 400 carries names the parameter too");
      assertEquals(Set.of("variantKey"), ex.getUndeclared());
      assertEquals(Set.of("variant", "version"), ex.getDeclared());
      assertEquals("GET /single/{id}", ex.getRoute());
      assertEquals(List.of("GET /single/{id} [variantKey] REJECT"), heard);
    }

    @Test
    void everyUnknownNameIsListed_aReadWithoutParametersSaysSo() {
      assertTrue(
          refused(probe("single"), request("GET", "/single/{id}", "z", "1", "a", "2"))
              .getMessage()
              .startsWith("unknown query parameters 'a', 'z' — "));
      assertEquals(
          "unknown query parameter 'version' — HEAD /bare takes no query parameters",
          refused(probe("bare"), request("HEAD", "/bare", "version", "v")).getMessage());
    }

    @Test
    void aTmf630ProjectionTakesFields_aPlainReadDoesNot() throws Exception {
      assertTrue(pass(probe("projected"), request("GET", "/projected/{id}", "fields", "id")));
      assertTrue(
          refused(probe("bare"), request("GET", "/bare", "fields", "id"))
              .getMessage()
              .contains("'fields'"));
    }

    @Test
    void aPageableDeclaresOffsetLimitAndSort_notSpringsPageAndSize() throws Exception {
      HandlerMethod history = probe("history");

      assertTrue(
          pass(
              history,
              request(
                  "GET", "/history", "type", "t", "offset", "0", "limit", "5", "sort", "-id")));
      assertEquals(
          Set.of("page", "size"),
          refused(history, request("GET", "/history", "type", "t", "page", "1", "size", "5"))
              .getUndeclared());
    }

    @Test
    void aListTakesItsFiltersAndTheTmfNames_aMapAndARawRequestAnything_aPostIsNotJudged()
        throws Exception {
      assertTrue(
          pass(
              probe("list"),
              request(
                  "GET", "/list", "version", "v", "name", "x", "offset", "0", "limit", "5",
                  "sort", "-id", "fields", "name", "page.eq", "2")));
      assertTrue(pass(probe("map"), request("GET", "/map", "anything", "x", "page", "1")));
      assertTrue(pass(probe("raw"), request("GET", "/raw", "anything", "x", "size", "5")));
      assertTrue(pass(probe("single"), request("POST", "/single/{id}", "anything", "x")));
      assertTrue(
          reject.preHandle(
              request("GET", "/static", "x", "1"), new MockHttpServletResponse(), new Object()));
      assertTrue(heard.isEmpty());
    }

    /** 3.5.1: the filter grammar skips page/size as reserved, so the list would serve them. */
    @Test
    void aListRefusesSpringsPageAndSize_namingHowItPages() {
      TmfUndeclaredQueryParameterException ex =
          refused(probe("list"), request("GET", "/list", "name", "x", "page", "0", "size", "10"));

      assertEquals(
          "unknown query parameters 'page', 'size' — GET /list is a TMF-630 list, which pages"
              + " with offset and limit",
          ex.getMessage());
      assertEquals(ex.getMessage(), ex.getBody().getDetail());
      assertTrue(ex.isListRoute());
      assertEquals(Set.of("page", "size"), ex.getUndeclared());
      assertTrue(ex.getDeclared().isEmpty());
      assertEquals(List.of("GET /list [page, size] REJECT"), heard);
      assertEquals(
          "unknown query parameter 'size' — GET /list is a TMF-630 list, which pages with offset"
              + " and limit",
          refused(probe("list"), request("GET", "/list", "size", "10")).getMessage());
    }

    @Test
    void theApiInterfaceDeclares_whetherSpelledWithNameOrValue() throws Exception {
      HandlerMethod view = handler(new DetailController(), "view");
      HandlerMethod role = handler(new DetailController(), "role");

      assertTrue(pass(view, request("GET", "/view", "lang", "de")));
      assertTrue(pass(role, request("GET", "/role", "role", "attachment")));
      TmfUndeclaredQueryParameterException ex =
          refused(view, request("GET", "/view", "rendtion", "customer"));
      assertTrue(ex.getMessage().contains("'rendtion'"));
      assertTrue(ex.getMessage().contains("takes only lang"));
    }

    @Test
    void aCatchAllMapBinding_acceptsEveryNameItStates_andRefusesAnyOther() throws Exception {
      HandlerMethod forward = handler(new PassThroughController(), "forward");

      assertTrue(
          pass(
              forward,
              request(
                  "GET",
                  "/ops/engine/incidents",
                  "processInstanceId",
                  "abc",
                  "processDefinitionKeyIn",
                  "a,b",
                  "incidentType",
                  "failedJob",
                  "limit",
                  "20")));
      TmfUndeclaredQueryParameterException ex =
          refused(forward, request("GET", "/ops/engine/incidents", "processInstanceld", "abc"));
      assertEquals(Set.of("processInstanceld"), ex.getUndeclared());
      assertTrue(ex.getMessage().contains("processInstanceId"));
    }

    @Test
    void aGuardBuiltWithoutListenersRefusesAlike() {
      Tmf630UndeclaredQueryParameterInterceptor quiet =
          new Tmf630UndeclaredQueryParameterInterceptor(
              new Tmf630DeclaredQueryParameters(List.of()),
              UndeclaredQueryParamBehavior.REJECT,
              Tmf630UndeclaredQueryParameterListener.NONE);
      MockHttpServletRequest request = request("GET", "/bare", "x", "1");
      MockHttpServletResponse response = new MockHttpServletResponse();
      HandlerMethod bare = probe("bare");

      assertThrows(
          TmfUndeclaredQueryParameterException.class,
          () -> quiet.preHandle(request, response, bare));
    }

    @Test
    void theRouteFallsBackToTheRequestUriWithoutAMatchedPattern() {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/bare");
      request.addParameter("x", "1");
      assertEquals(
          "unknown query parameter 'x' — GET /bare takes no query parameters",
          refused(probe("bare"), request).getMessage());
    }
  }

  @Nested
  class Warn {

    @Test
    void anUndeclaredParameterIsServedAndReported() throws Exception {
      Tmf630UndeclaredQueryParameterInterceptor warn = guard(UndeclaredQueryParamBehavior.WARN);

      assertTrue(
          warn.preHandle(
              request("GET", "/single/{id}", "version", "v", "variantKey", "w\r\nforged"),
              new MockHttpServletResponse(),
              probe("single")));
      assertEquals(List.of("GET /single/{id} [variantKey] WARN"), heard);
    }

    @Test
    void aListServesPageAndSizeButReportsThem() throws Exception {
      Tmf630UndeclaredQueryParameterInterceptor warn = guard(UndeclaredQueryParamBehavior.WARN);

      assertTrue(
          warn.preHandle(
              request("GET", "/list", "name", "x", "size", "10"),
              new MockHttpServletResponse(),
              probe("list")));
      assertEquals(List.of("GET /list [size] WARN"), heard);
    }

    @Test
    void aDeclaredRequestIsNotReported() throws Exception {
      Tmf630UndeclaredQueryParameterInterceptor warn = guard(UndeclaredQueryParamBehavior.WARN);

      assertTrue(
          warn.preHandle(
              request("GET", "/single/{id}", "version", "v"),
              new MockHttpServletResponse(),
              probe("single")));
      assertTrue(heard.isEmpty());
    }
  }

  @Nested
  class Ignore {

    @Test
    void nothingIsJudged() throws Exception {
      Tmf630UndeclaredQueryParameterInterceptor ignore =
          guard(UndeclaredQueryParamBehavior.IGNORE);

      assertTrue(
          ignore.preHandle(
              request("GET", "/bare", "anything", "x"),
              new MockHttpServletResponse(),
              probe("bare")));
      assertTrue(
          ignore.preHandle(
              request("GET", "/list", "page", "1"), new MockHttpServletResponse(), probe("list")));
      assertTrue(heard.isEmpty());
    }
  }
}
