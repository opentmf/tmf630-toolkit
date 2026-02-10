package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.core.MethodParameter;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

class Tmf630PredicateArgumentResolverTest {

  @Test
  void buildsPredicateWhenParametersAreValid() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");
    request.setParameter("page", "0");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  @Test
  void rejectsUnsupportedParameterWithoutQuerydslRoot() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "abc");

    assertThrows(
        ResponseStatusException.class,
        () ->
            resolver.resolveArgument(
                invalidPredicateParameter(),
                null,
                new ServletWebRequest(request),
                null));
  }

  @Test
  void rejectsDisallowedFieldWhenAllowlistIsStrict() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.DENY_ALL);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("forbidden.eq", "abc");

    assertThrows(
        ResponseStatusException.class,
        () ->
            resolver.resolveArgument(
                predicateParameter(),
                null,
                new ServletWebRequest(request),
                null));
  }

  @Test
  void supportsParameterChecksPredicateType() throws Exception {
    Tmf630PredicateArgumentResolver resolver = newResolver(AllowlistMode.ALLOW_ALL);
    Method method = StubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    assertTrue(resolver.supportsParameter(new MethodParameter(method, 0)));
  }

  @Test
  void supportsNoValueAndMultiValueOperators() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            CombineMode.OR,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT);
    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name", "age"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.isnull", "");
    request.setParameter("name.in", "a", "b");
    request.setParameter("age.between", "18", "65");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  @Test
  void ignoresUnknownOperatorAndFieldWhenConfiguredToIgnore() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            CombineMode.OR,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.IGNORE,
            UnknownParamBehavior.IGNORE);

    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), false),
            settings,
            rootEntity -> Set.of("name"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.badop", "x");
    request.setParameter("forbidden.eq", "x");

    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);
    assertNotNull(predicate);
  }

  @Test
  void rejectsWhenPredicateLimitsExceeded() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            CombineMode.AND,
            false,
            false,
            new PredicateLimits(1, 1, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT);

    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            rootEntity -> Set.of("name"),
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "a", "b");

    assertThrows(
        ResponseStatusException.class,
        () ->
            resolver.resolveArgument(
                predicateParameter(),
                null,
                new ServletWebRequest(request),
                null));
  }

  private static Tmf630PredicateArgumentResolver newResolver(AllowlistMode mode) {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            CombineMode.OR,
            false,
            false,
            new PredicateLimits(20, 5, 128),
            mode,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT);

    FieldAllowlistProvider allowlistProvider =
        rootEntity -> mode == AllowlistMode.ALLOW_ALL ? Set.of() : Set.of("name");

    return new Tmf630PredicateArgumentResolver(
        new ParamKeyParser(new OperatorRegistry(), true),
        settings,
        allowlistProvider,
        new FieldPathResolver(),
        new ValueConverter(new DefaultFormattingConversionService()),
        new PredicateFactory(false, 128));
  }

  private static MethodParameter predicateParameter() throws Exception {
    Method method = StubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static MethodParameter invalidPredicateParameter() throws Exception {
    Method method = InvalidStubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static class StubController {
    @SuppressWarnings("unused")
    void search(@QuerydslPredicate(root = Entity.class) com.querydsl.core.types.Predicate predicate) {}
  }

  private static class InvalidStubController {
    @SuppressWarnings("unused")
    void search(com.querydsl.core.types.Predicate predicate) {}
  }

  private static class Entity {
    @SuppressWarnings("unused")
    private String name;
    @SuppressWarnings("unused")
    private Integer age;
  }
}
