package org.opentmf.query.tmf630.jsonb;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import javax.sql.DataSource;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

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
 * implementation. The {@code afterName} entries below are ordering hints ONLY (they
 * do not create compile-time or runtime dependencies on those classes); Spring Boot
 * silently ignores names that are absent from the classpath, so services running on
 * EclipseLink or any other JPA provider load this auto-config in a valid order too.
 *
 * <p>The {@link EntityManagerFactory} lookup uses {@link ObjectProvider} rather than a
 * direct injection with {@link ConditionalOnBean} — this avoids the auto-config
 * ordering trap where {@code @ConditionalOnBean(EntityManagerFactory.class)} at the
 * method level can be evaluated before the JPA provider's own auto-config has run.
 */
@AutoConfiguration(
    afterName = {
      // Spring Boot 4.1 (Hibernate).
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      // Spring Boot 3.x (Hibernate) — older tenants of the toolkit.
      "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
      // Hypothetical EclipseLink autoconfig — kept as an ordering hint so that a
      // downstream service running on EclipseLink with a community autoconfig class
      // also gets us ordered correctly. Non-fatal if absent.
      "org.springframework.boot.autoconfigure.orm.jpa.EclipseLinkJpaAutoConfiguration",
      "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"
    })
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
        log.info(
            "tmf630-jsonb: registered {} (domain={}, payload={}, audit=[createdDate={}, "
                + "lastModifiedDate={}, createdBy={}, lastModifiedBy={}, version={}])",
            javaType.getSimpleName(),
            metadata.domainType().getSimpleName(),
            metadata.payloadField(),
            metadata.auditColumns().createdDateField().orElse("-"),
            metadata.auditColumns().lastModifiedDateField().orElse("-"),
            metadata.auditColumns().createdByField().orElse("-"),
            metadata.auditColumns().lastModifiedByField().orElse("-"),
            metadata.auditColumns().versionField().orElse("-"));
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
  public JsonbSortBuilder tmf630JsonbSortBuilder(
      JsonbPathExtractor extractor, ObjectProvider<Tmf630PagingSettings> pagingSettings) {
    boolean nullsLast =
        pagingSettings.getIfAvailable() != null && pagingSettings.getIfAvailable().nullsLast();
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
      ObjectMapper objectMapper,
      JsonbEntityRegistry registry,
      JsonbSortBuilder sortBuilder) {
    return new Tmf630JsonbFilterExecutor(jdbcClient, objectMapper, registry, sortBuilder);
  }
}
