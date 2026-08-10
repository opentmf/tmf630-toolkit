package org.opentmf.query.tmf630.jsonb;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.opentmf.query.tmf630.filtering.FieldAllowlistProvider;
import org.opentmf.query.tmf630.filtering.OperatorRegistry;
import org.opentmf.query.tmf630.filtering.ParamKeyParser;
import org.opentmf.query.tmf630.filtering.Tmf630FilterParser;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase (b.1) skeleton — registers the {@link JsonbEntityRegistry} bean and populates it
 * by scanning the JPA {@link EntityManagerFactory}'s metamodel for {@link Tmf630JsonbBacked}
 * annotations. Later sub-milestones (b.2 - b.7) hang the {@code JsonbPredicateFactory},
 * {@code JsonbFilterFragment}, and correlated-sort machinery off this registry.
 */
/**
 * Auto-config for the JSONB backend. Deliberately framework-neutral — depends only on
 * the JPA specification ({@code jakarta.persistence.EntityManager} /
 * {@code EntityManagerFactory} / metamodel) and never on any specific JPA
 * implementation. Zero {@code org.hibernate.*} or provider-specific imports.
 *
 * <p>The {@link EntityManagerFactory} lookup uses {@link ObjectProvider} rather than a
 * direct injection with {@link ConditionalOnBean}. {@code ObjectProvider.getIfAvailable()}
 * resolves the bean at instantiation time, by which point all auto-configs (including
 * the JPA provider's own) have already registered their bean definitions. That means
 * no {@code afterName} ordering hint is required — Spring's own dependency resolution
 * picks up the EMF whenever the JPA provider defines it, without us naming any
 * specific implementation class.
 */
@AutoConfiguration(
    afterName = "org.opentmf.query.tmf630.filtering.Tmf630AttributeFilteringAutoConfiguration")
@ConditionalOnClass({EntityManager.class, Tmf630JsonbBacked.class})
public class Tmf630JsonbAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(Tmf630JsonbAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public JsonbEntityRegistry tmf630JsonbEntityRegistry(
      ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider) {
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
    EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
    if (entityManagerFactory == null) {
      log.debug(
          "tmf630-jsonb: no EntityManagerFactory bean available; registry stays empty. "
              + "This is expected for services that add the module to the classpath "
              + "without yet configuring JPA.");
      return registry;
    }
    for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
      Class<?> javaType = entityType.getJavaType();
      if (javaType.isAnnotationPresent(Tmf630JsonbBacked.class)) {
        JsonbEntityMetadata metadata = JsonbEntityMetadata.of(javaType);
        registry.register(metadata);
        if (log.isInfoEnabled()) {
          JsonbAuditColumns audit = metadata.auditColumns();
          log.info(
              "tmf630-jsonb: registered {} (domain={}, payload={}, audit=[createdDate={}, "
                  + "lastModifiedDate={}, createdBy={}, lastModifiedBy={}, version={}])",
              javaType.getSimpleName(),
              metadata.domainType().getSimpleName(),
              metadata.payloadField(),
              audit.createdDateField().orElse("-"),
              audit.lastModifiedDateField().orElse("-"),
              audit.createdByField().orElse("-"),
              audit.lastModifiedByField().orElse("-"),
              audit.versionField().orElse("-"));
        }
      }
    }
    if (registry.all().isEmpty()) {
      log.debug(
          "tmf630-jsonb: no @Tmf630JsonbBacked entities found; module is inert until "
              + "an entity opts in.");
    }
    return registry;
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonbPathExtractor tmf630JsonbPathExtractor() {
    return new JsonbPathExtractor("payload");
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonbPredicateFactory tmf630JsonbPredicateFactory(
      JsonbPathExtractor extractor, ObjectProvider<Tmf630FilterSettings> filterSettings) {
    Tmf630FilterSettings settings = filterSettings.getIfAvailable();
    boolean regexEnabled = settings != null && settings.regexEnabled();
    return new JsonbPredicateFactory(
        extractor,
        settings != null ? settings.isnullSemantics() : null,
        regexEnabled);
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonbJsonPathTranslator tmf630JsonbJsonPathTranslator(JsonbPathExtractor extractor) {
    return new JsonbJsonPathTranslator(extractor, "payload");
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonbSplitAwareFilterTranslator tmf630JsonbSplitAwareFilterTranslator(
      JsonbJsonPathTranslator delegate, JsonbEntityRegistry registry) {
    return new JsonbSplitAwareFilterTranslator(delegate, registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public JsonbSortBuilder tmf630JsonbSortBuilder(
      JsonbPathExtractor extractor, ObjectProvider<Tmf630PagingSettings> pagingSettings) {
    Tmf630PagingSettings settings = pagingSettings.getIfAvailable();
    boolean nullsLast = settings != null && settings.nullsLast();
    return new JsonbSortBuilder(extractor, nullsLast);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DataSource.class)
  public JdbcClient tmf630JsonbJdbcClient(DataSource dataSource) {
    return JdbcClient.create(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JsonbFilterExecutor tmf630JsonbFilterExecutor(
      JdbcClient jdbcClient,
      ObjectProvider<ObjectMapper> objectMapper,
      JsonbEntityRegistry registry,
      JsonbSortBuilder sortBuilder) {
    return new Tmf630JsonbFilterExecutor(
        jdbcClient, requireObjectMapper(objectMapper), registry, sortBuilder);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JsonbWriteExecutor tmf630JsonbWriteExecutor(
      JdbcClient jdbcClient, ObjectProvider<ObjectMapper> objectMapper, JsonbEntityRegistry registry) {
    return new Tmf630JsonbWriteExecutor(jdbcClient, requireObjectMapper(objectMapper), registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JsonbSplitChildCounter tmf630JsonbSplitChildCounter(
      JdbcClient jdbcClient, JsonbEntityRegistry registry) {
    return new Tmf630JsonbSplitChildCounter(jdbcClient, registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JsonbVersionResolver tmf630JsonbVersionResolver(
      JdbcClient jdbcClient, ObjectProvider<ObjectMapper> objectMapper, JsonbEntityRegistry registry) {
    return new Tmf630JsonbVersionResolver(jdbcClient, requireObjectMapper(objectMapper), registry);
  }

  /**
   * The {@code @Tmf630JsonbFilter} URL-binding layer — active only when Spring MVC is on
   * the classpath AND a {@link Tmf630FilterSettings} bean exists. The settings-bean gate
   * is deliberate: the URL grammar the binding implements MUST run on the same settings
   * the rest of the application's TMF-630 filtering uses, and
   * {@code tmf630-toolkit-attribute-filtering-autoconfigure} exposes that bean out of the
   * box. Grammar collaborators ({@code ParamKeyParser}, {@code FieldPathResolver},
   * {@code ValueConverter}, allowlist) are reused from the application context when
   * present and constructed with identical defaults otherwise.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(WebMvcConfigurer.class)
  @ConditionalOnBean(Tmf630FilterSettings.class)
  static class Tmf630JsonbUrlBindingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Tmf630JsonbClauseBuilder tmf630JsonbClauseBuilder(
        Tmf630FilterSettings settings,
        ObjectProvider<ParamKeyParser> keyParser,
        ObjectProvider<FieldAllowlistProvider> allowlistProvider,
        ObjectProvider<FieldPathResolver> fieldPathResolver,
        ObjectProvider<ValueConverter> valueConverter,
        JsonbPredicateFactory predicateFactory,
        JsonbSplitAwareFilterTranslator filterTranslator) {
      Tmf630FilterParser parser =
          new Tmf630FilterParser(
              keyParser.getIfAvailable(
                  () -> new ParamKeyParser(new OperatorRegistry(), settings.implicitEqEnabled())),
              settings,
              allowlistProvider.getIfAvailable(() -> rootEntity -> Set.of()));
      return new Tmf630JsonbClauseBuilder(
          parser,
          settings,
          fieldPathResolver.getIfAvailable(FieldPathResolver::new),
          valueConverter.getIfAvailable(
              () -> new ValueConverter(new DefaultFormattingConversionService())),
          predicateFactory,
          filterTranslator);
    }

    @Bean
    @ConditionalOnMissingBean
    Tmf630JsonbClauseArgumentResolver tmf630JsonbClauseArgumentResolver(
        Tmf630JsonbClauseBuilder clauseBuilder) {
      return new Tmf630JsonbClauseArgumentResolver(clauseBuilder);
    }

    @Bean
    WebMvcConfigurer tmf630JsonbUrlBindingWebMvcConfigurer(
        Tmf630JsonbClauseArgumentResolver resolver) {
      return new WebMvcConfigurer() {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
          resolvers.add(resolver);
        }
      };
    }
  }

  /**
   * Resolves the Jackson 3 ({@code tools.jackson}) mapper the executors serialize payloads
   * with, failing the boot with an actionable message when none is defined. Spring Boot 4
   * auto-configures a {@code JsonMapper} bean (an {@link ObjectMapper} subtype) whenever
   * {@code spring-boot-jackson} is on the classpath, so most services get this for free.
   */
  private static ObjectMapper requireObjectMapper(ObjectProvider<ObjectMapper> provider) {
    ObjectMapper mapper = provider.getIfAvailable();
    if (mapper == null) {
      throw new Tmf630JsonbConfigurationException(
          "No tools.jackson.databind.ObjectMapper bean available — the tmf630-toolkit-jsonb "
              + "executors need one to (de)serialize JSONB payloads. Spring Boot 4 "
              + "auto-configures a JsonMapper out of the box (spring-boot-jackson); on "
              + "Spring Boot 3, add tools.jackson.core:jackson-databind and declare a "
              + "JsonMapper bean explicitly.");
    }
    return mapper;
  }
}
