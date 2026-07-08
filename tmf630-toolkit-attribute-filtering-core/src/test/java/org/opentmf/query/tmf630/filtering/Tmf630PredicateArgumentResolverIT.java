package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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

class Tmf630PredicateArgumentResolverIT {

  @Test
  void resolvesComplexPredicateWithMultipleOperators() throws Exception {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            true,
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(50, 10, 128),
            AllowlistMode.DENY_ALL,
            UnknownParamBehavior.REJECT,
            UnknownParamBehavior.REJECT,
            true,
            2048,
            UnknownParamBehavior.REJECT);

    FieldAllowlistProvider allowlist = rootEntity -> Set.of("name", "age");

    Tmf630PredicateArgumentResolver resolver =
        new Tmf630PredicateArgumentResolver(
            new ParamKeyParser(new OperatorRegistry(), true),
            settings,
            allowlist,
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 128),
            new JsonPathFilterPredicateBuilder(
                new FieldPathResolver(),
                new ValueConverter(new DefaultFormattingConversionService()),
                new PredicateFactory(false, 128)));

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("name.eq", "john");
    request.setParameter("age.gt", "18");
    Object predicate =
        resolver.resolveArgument(
            predicateParameter(),
            null,
            new ServletWebRequest(request),
            null);

    assertNotNull(predicate);
  }

  private static MethodParameter predicateParameter() throws Exception {
    Method method = StubController.class.getDeclaredMethod("search", com.querydsl.core.types.Predicate.class);
    return new MethodParameter(method, 0);
  }

  private static class StubController {
    @SuppressWarnings("unused")
    void search(@QuerydslPredicate(root = Entity.class) com.querydsl.core.types.Predicate predicate) {}
  }

  private static class Entity {
    @SuppressWarnings("unused")
    private String name;
    @SuppressWarnings("unused")
    private Integer age;
  }
}
