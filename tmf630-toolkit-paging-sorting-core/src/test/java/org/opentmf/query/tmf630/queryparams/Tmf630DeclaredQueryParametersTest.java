package org.opentmf.query.tmf630.queryparams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.types.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.paging.TmfRichPageable;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.versioning.TmfVersionedId;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * The declared set derived from a handler signature — what the guard judges a request against.
 * Fixtures are compiled with {@code -parameters}, as a Spring Boot application is.
 */
class Tmf630DeclaredQueryParametersTest {

  @Retention(RetentionPolicy.RUNTIME)
  @interface AppResolved {}

  /** A type only an application resolver knows. */
  static class TenantScope {}

  /** A model attribute: Spring binds any query parameter onto its properties. */
  static class Criteria {}

  /** Signature-only handlers: the guard reads their parameters, never their (empty) bodies. */
  @SuppressWarnings({"unused", "java:S1186"})
  static class Probe {
    public void named(
        @RequestParam(name = "version") String version,
        @RequestParam(value = "variant", required = false) String variant,
        @RequestParam(required = false) String lang) {}

    public void bare() {}

    public void nonQuery(
        @PathVariable("id") String id,
        @RequestHeader("If-None-Match") String etag,
        @RequestBody String body,
        @CookieValue("session") String cookie,
        @RequestAttribute("tenant") String tenant,
        Principal principal,
        HttpServletResponse response,
        Locale locale) {}

    public void paged(Pageable pageable) {}

    public void richPaged(TmfRichPageable pageable) {}

    public void sorted(Sort sort, TmfSort tmfSort) {}

    public void versioned(@PathVariable("id") TmfVersionedId id) {}

    public void list(@RequestParam("version") String version, Predicate predicate) {}

    public void annotatedList(@QuerydslPredicate Object predicate) {}

    @Tmf630AcceptsQueryParameters({"x"})
    public void annotatedListAccepting(Predicate predicate) {}

    public void catchAll(@RequestParam Map<String, String> query) {}

    public void catchAllMulti(@RequestParam MultiValueMap<String, String> query) {}

    @Tmf630AcceptsQueryParameters({"processInstanceId", "incidentType"})
    public void forwarded(@RequestParam Map<String, String> query, Pageable pageable) {}

    public void namedMap(@RequestParam("filter") Map<String, String> filter) {}

    public void raw(HttpServletRequest request) {}

    @Tmf630AcceptsQueryParameters({"q"})
    public void rawAccepting(HttpServletRequest request) {}

    public void model(Criteria criteria) {}

    public void defaultBinding(String q, Optional<Integer> page, Locale locale) {}

    public void customAnnotated(@AppResolved String token) {}

    public void appType(TenantScope scope) {}

    @Tmf630AcceptsAnyQueryParameter
    public void anything(@RequestParam("a") String a) {}

    @Tmf630Response
    public String projected(@PathVariable("id") String id) {
      return id;
    }
  }

  @Tmf630Response
  @Tmf630AcceptsQueryParameters({"cacheBuster"})
  @SuppressWarnings("unused")
  static class ProjectedType {
    public String read(@RequestParam("lang") String lang) {
      return lang;
    }
  }

  @Tmf630AcceptsAnyQueryParameter
  @SuppressWarnings({"unused", "java:S1186"})
  static class CallbackType {
    public void report(@RequestParam("Recipient") String recipient) {}
  }

  /** The api interface carries the annotations; the implementation repeats none of them. */
  interface DetailApi {
    @Tmf630Response
    String view(@PathVariable("id") String id, @RequestParam(name = "rendition") String rendition);

    @Tmf630AcceptsQueryParameters({"incidentType"})
    String forward(@RequestParam Map<String, String> query);
  }

  static class DetailController implements DetailApi {
    @Override
    public String view(String id, String rendition) {
      return id + rendition;
    }

    @Override
    public String forward(Map<String, String> query) {
      return String.valueOf(query);
    }
  }

  @Tmf630Response
  interface ProjectedApi {
    String read(@RequestParam("lang") String lang);
  }

  static class ProjectedController implements ProjectedApi {
    @Override
    public String read(String lang) {
      return lang;
    }
  }

  static HandlerMethod handler(Object bean, String name) {
    for (Method method : bean.getClass().getMethods()) {
      if (method.getName().equals(name)) {
        return new HandlerMethod(bean, method);
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static Optional<Set<String>> declared(String name) {
    return Tmf630DeclaredQueryParameters.of(handler(new Probe(), name));
  }

  private static void assertDeclares(Optional<Set<String>> declared, String... names) {
    assertEquals(Optional.of(Set.of(names)), declared);
  }

  @Nested
  class Declared {

    @Test
    void everyRequestParamByNameValueOrCompiledName() {
      assertDeclares(declared("named"), "version", "variant", "lang");
    }

    @Test
    void aHandlerWithoutArgumentsDeclaresNothing_andIsJudged() {
      assertDeclares(declared("bare"));
    }

    @Test
    void pathHeaderBodyCookieAttributePrincipalResponseAndLocaleReadNoQuery() {
      assertDeclares(declared("nonQuery"));
    }

    @Test
    void aPageableDeclaresWhatThePagingResolversRead() {
      assertDeclares(declared("paged"), "offset", "limit", "sort");
      assertDeclares(declared("richPaged"), "offset", "limit", "sort");
    }

    @Test
    void aSortDeclaresSort() {
      assertDeclares(declared("sorted"), "sort");
    }

    @Test
    void aVersionedIdDeclaresItsQueryFallback() {
      assertDeclares(declared("versioned"), "version");
    }

    @Test
    void aNamedRequestParamOnAMapIsOneParameter_notACatchAll() {
      assertDeclares(declared("namedMap"), "filter");
    }

    @Test
    void aSimpleTypedArgumentWithoutAnnotationIsSpringsDefaultRequestParam() {
      assertDeclares(declared("defaultBinding"), "q", "page");
    }

    @Test
    void theDeclaredSetIsSorted() {
      assertEquals(
          List.of("lang", "variant", "version"), List.copyOf(declared("named").orElseThrow()));
    }
  }

  @Nested
  class Fields {

    @Test
    void aTmf630ResponseMethodTakesFields_aPlainOneDoesNot() {
      assertDeclares(declared("projected"), "fields");
      assertDeclares(declared("bare"));
    }

    @Test
    void aTmf630ResponseTypeTakesFieldsOnEveryMethod() {
      assertDeclares(
          Tmf630DeclaredQueryParameters.of(handler(new ProjectedType(), "read")),
          "lang",
          "fields",
          "cacheBuster");
    }

    @Test
    void theApiInterfaceCountsForMethodAndTypeAnnotations() {
      assertDeclares(
          Tmf630DeclaredQueryParameters.of(handler(new DetailController(), "view")),
          "rendition",
          "fields");
      assertDeclares(
          Tmf630DeclaredQueryParameters.of(handler(new ProjectedController(), "read")),
          "lang",
          "fields");
    }
  }

  @Nested
  class Open {

    @Test
    void aTmf630ListIsOpen_evenWhenItNamesExtraParameters() {
      assertTrue(declared("list").isEmpty());
      assertTrue(declared("annotatedList").isEmpty());
      assertTrue(declared("annotatedListAccepting").isEmpty());
    }

    @Test
    void anUnnamedCatchAllMapIsOpen_theJavaIdentifierIsNotAName() {
      assertTrue(declared("catchAll").isEmpty());
      assertTrue(declared("catchAllMulti").isEmpty());
    }

    @Test
    void aCatchAllThatStatesItsVocabularyIsJudgedAgainstIt() {
      assertDeclares(
          declared("forwarded"), "processInstanceId", "incidentType", "offset", "limit", "sort");
      assertDeclares(
          Tmf630DeclaredQueryParameters.of(handler(new DetailController(), "forward")),
          "incidentType");
    }

    @Test
    void theRawRequestAModelAttributeAndUnknownArgumentsAreOpen_unlessTheVocabularyIsStated() {
      assertTrue(declared("raw").isEmpty());
      assertTrue(declared("model").isEmpty());
      assertTrue(declared("customAnnotated").isEmpty());
      assertTrue(declared("appType").isEmpty());
      assertDeclares(declared("rawAccepting"), "q");
    }

    @Test
    void acceptsAnyOnTheMethodOrTheTypeOpensTheHandler() {
      assertTrue(declared("anything").isEmpty());
      assertTrue(
          Tmf630DeclaredQueryParameters.of(handler(new CallbackType(), "report")).isEmpty());
    }
  }

  @Nested
  class Classifiers {

    private final Tmf630QueryArgumentClassifier tenant =
        parameter ->
            parameter.getParameterType() == TenantScope.class
                ? Tmf630QueryArgument.named("tenant")
                : null;

    @Test
    void anApplicationClassifierClosesAHandlerTheBuiltInRulesLeaveOpen() {
      Tmf630DeclaredQueryParameters declared = new Tmf630DeclaredQueryParameters(List.of(tenant));
      assertDeclares(declared.declaredBy(handler(new Probe(), "appType")), "tenant");
    }

    @Test
    void nonQueryTypesAndAnnotationsReadNothing() {
      Tmf630DeclaredQueryParameters declared =
          new Tmf630DeclaredQueryParameters(
              List.of(
                  Tmf630QueryArgumentClassifier.nonQuery(
                      List.of(TenantScope.class, AppResolved.class))));
      assertDeclares(declared.declaredBy(handler(new Probe(), "appType")));
      assertDeclares(declared.declaredBy(handler(new Probe(), "customAnnotated")));
      assertDeclares(
          declared.declaredBy(handler(new Probe(), "named")), "version", "variant", "lang");
    }

    @Test
    void anApplicationClassifierMayDeclareItsOwnFilterTerminal() {
      Tmf630DeclaredQueryParameters declared =
          new Tmf630DeclaredQueryParameters(
              List.of(
                  parameter ->
                      parameter.getParameterType() == String.class
                          ? Tmf630QueryArgument.filter()
                          : null));
      assertTrue(declared.declaredBy(handler(new Probe(), "named")).isEmpty());
    }

    @Test
    void theDerivationIsCachedPerHandler() {
      Tmf630DeclaredQueryParameters declared = new Tmf630DeclaredQueryParameters(List.of());
      HandlerMethod named = handler(new Probe(), "named");
      assertSame(declared.declaredBy(named), declared.declaredBy(named));
    }
  }

  @Nested
  class Names {

    @Test
    void aBindingWithoutANameToReadIsAnErrorNotTheLiteralNull() throws Exception {
      Method method = Probe.class.getMethod("named", String.class, String.class, String.class);
      MethodParameter unnamed =
          new MethodParameter(method, 2) {
            @Override
            public String getParameterName() {
              return null;
            }
          };
      IllegalStateException ex =
          assertThrows(
              IllegalStateException.class, () -> Tmf630DeclaredQueryParameters.builtIn(unnamed));
      assertTrue(ex.getMessage().contains("name it explicitly or compile with -parameters"));
    }

    @Test
    void assignabilityByNameWalksSuperclassesAndInterfaces() {
      assertTrue(
          Tmf630DeclaredQueryParameters.isAssignableTo(ArrayList.class, "java.util.Collection"));
      assertTrue(
          Tmf630DeclaredQueryParameters.isAssignableTo(
              ArrayList.class, "java.util.AbstractCollection"));
      assertFalse(
          Tmf630DeclaredQueryParameters.isAssignableTo(String.class, "java.util.Collection"));
    }
  }
}
