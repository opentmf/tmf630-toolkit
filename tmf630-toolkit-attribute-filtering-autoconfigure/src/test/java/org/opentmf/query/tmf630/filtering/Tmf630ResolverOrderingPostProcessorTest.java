package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.jspecify.annotations.Nullable;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

class Tmf630ResolverOrderingPostProcessorTest {

  private static ObjectProvider<Tmf630PredicateArgumentResolver> providerOf(
      Tmf630PredicateArgumentResolver resolver) {
    return new ObjectProvider<>() {
      @Override
      public Tmf630PredicateArgumentResolver getObject() { return resolver; }
      @Override
      public Tmf630PredicateArgumentResolver getIfAvailable() { return resolver; }
      @Override
      public Tmf630PredicateArgumentResolver getIfUnique() { return resolver; }
    };
  }

  @Test
  void returnsSameBeanForNonAdapterTypes() {
    Tmf630AttributeFilteringAutoConfiguration config = new Tmf630AttributeFilteringAutoConfiguration();
    BeanPostProcessor bpp = config.tmf630ResolverOrderingPostProcessor(providerOf(newResolver()));
    Object bean = new Object();
    assertSame(bean, bpp.postProcessAfterInitialization(bean, "x"));
  }

  @Test
  void setsCustomResolversWhenAdapterHasNoResolvers() {
    Tmf630AttributeFilteringAutoConfiguration config = new Tmf630AttributeFilteringAutoConfiguration();
    Tmf630PredicateArgumentResolver resolver = newResolver();
    BeanPostProcessor bpp = config.tmf630ResolverOrderingPostProcessor(providerOf(resolver));
    RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();

    bpp.postProcessAfterInitialization(adapter, "adapter");

    assertEquals(1, adapter.getCustomArgumentResolvers().size());
    assertSame(resolver, adapter.getCustomArgumentResolvers().get(0));
  }

  @Test
  void prependsResolverAndRemovesDuplicatesWhenResolverListExists() {
    Tmf630AttributeFilteringAutoConfiguration config = new Tmf630AttributeFilteringAutoConfiguration();
    Tmf630PredicateArgumentResolver resolver = newResolver();
    BeanPostProcessor bpp = config.tmf630ResolverOrderingPostProcessor(providerOf(resolver));
    RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
    HandlerMethodArgumentResolver other =
        new HandlerMethodArgumentResolver() {
          @Override
          public boolean supportsParameter(MethodParameter parameter) {
            return false;
          }

          @Override
          public Object resolveArgument(
              MethodParameter parameter,
              @Nullable ModelAndViewContainer mavContainer,
              NativeWebRequest webRequest,
              @Nullable WebDataBinderFactory binderFactory) {
            return null;
          }
        };
    adapter.setArgumentResolvers(List.of(other, resolver));

    bpp.postProcessAfterInitialization(adapter, "adapter");

    assertInstanceOf(Tmf630PredicateArgumentResolver.class, adapter.getArgumentResolvers().get(0));
    assertEquals(2, adapter.getArgumentResolvers().size());
  }

  private static Tmf630PredicateArgumentResolver newResolver() {
    Tmf630FilterSettings settings =
        new Tmf630FilterSettings(
            true,
            CombineMode.OR,
            false,
            false,
            false,
            new PredicateLimits(10, 3, 64),
            AllowlistMode.ALLOW_ALL,
            UnknownParamBehavior.IGNORE,
            UnknownParamBehavior.IGNORE,
            true,
            2048,
            UnknownParamBehavior.IGNORE);
    return new Tmf630PredicateArgumentResolver(
        new ParamKeyParser(new OperatorRegistry(), true),
        settings,
        rootEntity -> Set.of(),
        new FieldPathResolver(),
        new ValueConverter(new DefaultFormattingConversionService()),
        new PredicateFactory(false, 64),
        new JsonPathFilterPredicateBuilder(
            new FieldPathResolver(),
            new ValueConverter(new DefaultFormattingConversionService()),
            new PredicateFactory(false, 64)));
  }
}
