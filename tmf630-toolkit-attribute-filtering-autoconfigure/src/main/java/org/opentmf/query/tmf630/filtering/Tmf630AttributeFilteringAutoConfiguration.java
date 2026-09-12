package org.opentmf.query.tmf630.filtering;

import com.querydsl.core.types.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.opentmf.query.tmf630.filtering.advice.Tmf630FilteringExceptionHandler;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.opentmf.query.tmf630.paging.TmfSortKeyValidator;
import org.springframework.beans.factory.ObjectProvider;
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

  /**
   * The resolved filter settings, exposed as a bean so backend modules outside this one
   * (e.g. tmf630-toolkit-jsonb) consume the SAME configuration the QueryDSL resolver
   * runs on — one settings source per application, no per-backend divergence.
   */
  @Bean
  @ConditionalOnMissingBean
  public Tmf630FilterSettings tmf630FilterSettings(
      Tmf630AttributeFilteringProperties properties) {
    return properties.toSettings();
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
        properties.getRegex().isEnabled(),
        properties.getRegex().getMaxLength(),
        properties.getIsnullSemantics(),
        properties.getRegex().isAllowJpaLikeSemantics());
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
      Tmf630FilterSettings settings,
      FieldAllowlistProvider allowlistProvider,
      FieldPathResolver pathResolver,
      ValueConverter valueConverter,
      PredicateFactory predicateFactory,
      JsonPathFilterPredicateBuilder jsonPathFilterPredicateBuilder) {
    return new Tmf630PredicateArgumentResolver(
        keyParser,
        settings,
        allowlistProvider,
        pathResolver,
        valueConverter,
        predicateFactory,
        jsonPathFilterPredicateBuilder);
  }

  @Bean
  @ConditionalOnMissingBean(name = "tmf630QuerydslPredicateRootLocator")
  public Tmf630FilterRootLocator tmf630QuerydslPredicateRootLocator() {
    return new QuerydslPredicateRootLocator();
  }

  /**
   * Validates plain sort keys against the handler's filter root ({@link
   * FilterRootSortKeyValidator}); the paging autoconfiguration hands it to every sort-parsing
   * resolver. Every {@link Tmf630FilterRootLocator} bean takes part. Declare a {@code
   * TmfSortKeyValidator} bean of your own (e.g. {@code TmfSortKeyValidator.NONE}) to opt out.
   */
  @Bean
  @ConditionalOnMissingBean
  public TmfSortKeyValidator tmf630SortKeyValidator(
      FieldPathResolver pathResolver, ObjectProvider<Tmf630FilterRootLocator> rootLocators) {
    return new FilterRootSortKeyValidator(pathResolver, rootLocators.orderedStream().toList());
  }

  @Bean
  public BeanPostProcessor tmf630ResolverOrderingPostProcessor(
      ObjectProvider<Tmf630PredicateArgumentResolver> resolverProvider) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof RequestMappingHandlerAdapter adapter)) {
          return bean;
        }

        Tmf630PredicateArgumentResolver resolver = resolverProvider.getIfAvailable();
        if (resolver == null) {
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
