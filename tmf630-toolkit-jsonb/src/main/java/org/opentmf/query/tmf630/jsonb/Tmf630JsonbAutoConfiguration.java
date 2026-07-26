package org.opentmf.query.tmf630.jsonb;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Phase (b.1) skeleton — registers the {@link JsonbEntityRegistry} bean and populates it
 * by scanning the JPA {@link EntityManagerFactory}'s metamodel for {@link Tmf630JsonbBacked}
 * annotations. Later sub-milestones (b.2 - b.7) hang the {@code JsonbPredicateFactory},
 * {@code JsonbFilterFragment}, and correlated-sort machinery off this registry.
 */
@AutoConfiguration
@ConditionalOnClass({EntityManager.class, Tmf630JsonbBacked.class})
@ConditionalOnBean(EntityManagerFactory.class)
public class Tmf630JsonbAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(Tmf630JsonbAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public JsonbEntityRegistry tmf630JsonbEntityRegistry(EntityManagerFactory entityManagerFactory) {
    JsonbEntityRegistry registry = new JsonbEntityRegistry();
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
}
