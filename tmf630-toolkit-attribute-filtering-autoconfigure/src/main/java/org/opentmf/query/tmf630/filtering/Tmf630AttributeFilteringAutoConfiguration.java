package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.types.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.filtering.advice.Tmf630FilteringExceptionHandler;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@AutoConfiguration
@ConditionalOnClass({Predicate.class, HandlerMethodArgumentResolver.class})
@EnableConfigurationProperties(Tmf630AttributeFilteringProperties.class)
@ConditionalOnProperty(
    prefix = "opentmf.tmf630.attribute-filtering",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Tmf630AttributeFilteringAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Tmf630FilteringExceptionHandler tmf630FilteringExceptionHandler() {
    return new Tmf630FilteringExceptionHandler();
  }

  @Bean
  @ConditionalOnMissingBean
  public OperatorRegistry tmf630OperatorRegistry() {
    return new OperatorRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  public ParamKeyParser tmf630ParamKeyParser(
      OperatorRegistry registry, Tmf630AttributeFilteringProperties properties) {
    return new ParamKeyParser(registry, properties.isImplicitEqEnabled());
  }

  @Bean
  @ConditionalOnMissingBean
  public FieldPathResolver tmf630FieldPathResolver() {
    return new FieldPathResolver();
  }

  @Bean
  @ConditionalOnMissingBean
  public ValueConverter tmf630ValueConverter() {
    return new ValueConverter(new DefaultFormattingConversionService());
  }

  @Bean
  @ConditionalOnMissingBean
  public PredicateFactory tmf630PredicateFactory(Tmf630AttributeFilteringProperties properties) {
    return new PredicateFactory(
        properties.getRegex().isEnabled(), properties.getRegex().getMaxLength());
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonPathFilterPredicateBuilder tmf630JsonPathFilterPredicateBuilder(
      FieldPathResolver pathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory) {
    return new JsonPathFilterPredicateBuilder(pathResolver, valueConverter, predicateFactory);
  }

  @Bean
  @ConditionalOnMissingBean
  public FieldAllowlistProvider tmf630FieldAllowlistProvider(
      Tmf630AttributeFilteringProperties properties) {
    return new PropertyFieldAllowlistProvider(properties.getAllowlist().getEntities());
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630PredicateArgumentResolver tmf630PredicateArgumentResolver(
      ParamKeyParser keyParser,
      Tmf630AttributeFilteringProperties properties,
      FieldAllowlistProvider allowlistProvider,
      FieldPathResolver pathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory,
      JsonPathFilterPredicateBuilder jsonPathFilterPredicateBuilder) {
    return new Tmf630PredicateArgumentResolver(
        keyParser,
        properties.toSettings(),
        allowlistProvider,
        pathResolver,
        valueConverter,
        predicateFactory,
        jsonPathFilterPredicateBuilder);
  }

  @Bean
  public BeanPostProcessor tmf630ResolverOrderingPostProcessor(
      Tmf630PredicateArgumentResolver resolver) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof RequestMappingHandlerAdapter adapter)) {
          return bean;
        }

        List<HandlerMethodArgumentResolver> current = adapter.getArgumentResolvers();
        List<HandlerMethodArgumentResolver> reordered = new ArrayList<>();
        reordered.add(resolver);
        if (current != null) {
          for (HandlerMethodArgumentResolver existing : current) {
            if (!(existing instanceof Tmf630PredicateArgumentResolver)) {
              reordered.add(existing);
            }
          }
          adapter.setArgumentResolvers(reordered);
        } else {
          adapter.setCustomArgumentResolvers(List.of(resolver));
        }
        return bean;
      }
    };
  }
}
