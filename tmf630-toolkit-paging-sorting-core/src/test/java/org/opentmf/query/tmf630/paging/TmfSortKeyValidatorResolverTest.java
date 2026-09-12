package org.opentmf.query.tmf630.paging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Every sort-parsing resolver hands its plain keys to the contributed {@link TmfSortKeyValidator}. */
class TmfSortKeyValidatorResolverTest {

  private static final Tmf630PagingSettings SETTINGS =
      new Tmf630PagingSettings(true, 50, 500, true, false, List.of());
  private static final TmfSortParser PARSER = new TmfSortParser(List.of(), false);
  private static final TmfSortKeyValidator REJECT_NOSUCH =
      (parameter, keys) -> {
        if (keys.contains("nosuch")) {
          throw new TmfPagingException("Unknown sort property: nosuch");
        }
      };

  private final List<List<String>> seenKeys = new ArrayList<>();
  private final List<MethodParameter> seenParameters = new ArrayList<>();
  private final TmfSortKeyValidator recording =
      (parameter, keys) -> {
        seenParameters.add(parameter);
        seenKeys.add(keys);
      };

  @Test
  void pageableResolverHandsPlainKeysAndItsHandlerParameterToTheValidator() throws Exception {
    MethodParameter parameter = parameter("pageable", Pageable.class);

    resolve(new TmfPageableHandlerMethodArgumentResolver(SETTINGS, recording), parameter, "-createdOn,+id");

    assertEquals(List.of(List.of("createdOn", "id")), seenKeys);
    assertSame(parameter, seenParameters.get(0));
  }

  @Test
  void sortResolverHandsPlainKeysToTheValidator() throws Exception {
    resolve(
        new TmfSortHandlerMethodArgumentResolver(PARSER, recording),
        parameter("sort", Sort.class),
        "-createdOn,+id");

    assertEquals(List.of(List.of("createdOn", "id")), seenKeys);
  }

  @Test
  void richResolversHandOnlyPlainTermsToTheValidator() throws Exception {
    String mixed = "-name,characteristic[name=price].value";

    resolve(
        new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS, recording),
        parameter("richPageable", TmfRichPageable.class),
        mixed);
    resolve(
        new TmfRichSortHandlerMethodArgumentResolver(new TmfSortParser(List.of(), false), recording),
        parameter("tmfSort", TmfSort.class),
        mixed);

    assertEquals(List.of(List.of("name"), List.of("name")), seenKeys);
  }

  @Test
  void pageableBesideATmfSortValidatesThePlainKeysItCarries() throws Exception {
    resolve(
        new TmfPageableHandlerMethodArgumentResolver(SETTINGS, recording),
        new MethodParameter(
            Handlers.class.getDeclaredMethod("pageableBesideTmfSort", Pageable.class, TmfSort.class),
            0),
        "-name");

    assertEquals(List.of(List.of("name")), seenKeys);
  }

  @Test
  void aValidatorRejectionSurfacesFromEveryResolver() throws Exception {
    List<ResolverCase> cases =
        List.of(
            new ResolverCase(
                new TmfPageableHandlerMethodArgumentResolver(SETTINGS, REJECT_NOSUCH),
                parameter("pageable", Pageable.class)),
            new ResolverCase(
                new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS, REJECT_NOSUCH),
                parameter("richPageable", TmfRichPageable.class)),
            new ResolverCase(
                new TmfSortHandlerMethodArgumentResolver(PARSER, REJECT_NOSUCH),
                parameter("sort", Sort.class)),
            new ResolverCase(
                new TmfRichSortHandlerMethodArgumentResolver(PARSER, REJECT_NOSUCH),
                parameter("tmfSort", TmfSort.class)));

    for (ResolverCase resolverCase : cases) {
      HandlerMethodArgumentResolver resolver = resolverCase.resolver();
      MethodParameter parameter = resolverCase.parameter();
      TmfPagingException ex =
          assertThrows(
              TmfPagingException.class, () -> resolve(resolver, parameter, "-nosuch"));
      assertEquals("Unknown sort property: nosuch", ex.getMessage());
    }
  }

  @Test
  void nestingAndAllowlistStillRejectBeforeTheValidatorIsAsked() throws Exception {
    Tmf630PagingSettings allowlisted =
        new Tmf630PagingSettings(true, 50, 500, true, false, List.of("createdOn"));
    TmfPageableHandlerMethodArgumentResolver resolver =
        new TmfPageableHandlerMethodArgumentResolver(allowlisted, recording);
    MethodParameter parameter = parameter("pageable", Pageable.class);

    TmfPagingException notAllowed =
        assertThrows(TmfPagingException.class, () -> resolve(resolver, parameter, "nosuch"));
    TmfPagingException nested =
        assertThrows(TmfPagingException.class, () -> resolve(resolver, parameter, "createdOn.x"));

    assertEquals("Sort property is not allowed: nosuch", notAllowed.getMessage());
    assertEquals("Nested sort properties are not allowed: createdOn.x", nested.getMessage());
    assertTrue(seenKeys.isEmpty());
  }

  @Test
  void legacyConstructorsKeepAcceptingEveryKey() {
    assertDoesNotThrow(
        () ->
            resolve(
                new TmfPageableHandlerMethodArgumentResolver(SETTINGS),
                parameter("pageable", Pageable.class),
                "-nosuch"));
    assertDoesNotThrow(
        () ->
            resolve(
                new TmfRichPageableHandlerMethodArgumentResolver(SETTINGS),
                parameter("richPageable", TmfRichPageable.class),
                "-nosuch"));
    assertDoesNotThrow(
        () ->
            resolve(
                new TmfSortHandlerMethodArgumentResolver(PARSER),
                parameter("sort", Sort.class),
                "-nosuch"));
    assertDoesNotThrow(
        () ->
            resolve(
                new TmfRichSortHandlerMethodArgumentResolver(PARSER),
                parameter("tmfSort", TmfSort.class),
                "-nosuch"));
  }

  @Test
  void noneAcceptsEveryKeyInEveryForm() throws Exception {
    MethodParameter parameter = parameter("sort", Sort.class);
    TmfSort rich = PARSER.parseRich(List.of("-nosuch"));

    assertDoesNotThrow(() -> TmfSortKeyValidator.NONE.validate(parameter, List.of("nosuch")));
    assertDoesNotThrow(() -> TmfSortKeyValidator.NONE.validate(parameter, Sort.by("nosuch")));
    assertDoesNotThrow(() -> TmfSortKeyValidator.NONE.validate(parameter, rich));
  }

  @Test
  void plainKeysSkipCorrelatedTermsAndKeepRequestOrder() {
    TmfSort sort =
        new TmfSortParser(List.of(), true)
            .parseRich(List.of("-b,characteristic[name=price].value,+a,$.x[?(@.k=='v')].y"));

    assertEquals(List.of("b", "a"), sort.plainKeys());
    assertEquals(List.of(), TmfSort.empty().plainKeys());
  }

  private static Object resolve(
      HandlerMethodArgumentResolver resolver, MethodParameter parameter, String sort)
      throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("sort", sort);
    request.setParameter("offset", "0");
    request.setParameter("limit", "10");
    return resolver.resolveArgument(
        parameter, new ModelAndViewContainer(), new ServletWebRequest(request), null);
  }

  private static MethodParameter parameter(String name, Class<?> type) throws Exception {
    return new MethodParameter(Handlers.class.getDeclaredMethod(name, type), 0);
  }

  private record ResolverCase(HandlerMethodArgumentResolver resolver, MethodParameter parameter) {}

  @SuppressWarnings("unused")
  private static class Handlers {
    void pageable(Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }

    void sort(Sort sort) {
      // signature-only stub for MethodParameter reflection
    }

    void tmfSort(TmfSort sort) {
      // signature-only stub for MethodParameter reflection
    }

    void richPageable(TmfRichPageable pageable) {
      // signature-only stub for MethodParameter reflection
    }

    void pageableBesideTmfSort(Pageable pageable, TmfSort sort) {
      // signature-only stub for MethodParameter reflection
    }
  }
}
