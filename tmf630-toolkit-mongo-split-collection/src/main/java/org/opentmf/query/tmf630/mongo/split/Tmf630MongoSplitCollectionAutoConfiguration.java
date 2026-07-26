package org.opentmf.query.tmf630.mongo.split;

import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

/**
 * Auto-config for the Mongo split-collection module. Depends only on
 * {@link MongoOperations} and the Mongo mapping context — no coupling to any specific
 * app framework beyond spring-data-mongodb itself.
 *
 * <p>The registry is populated by walking the
 * {@link MongoMappingContext#getPersistentEntities() mapping context} at startup and
 * asking each entity type to opt in via {@link Tmf630MongoSplitBacked}. Types without
 * the annotation are ignored, so adding this module to a service that doesn't declare
 * any split-backed types is inert.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
      "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration"
    })
@ConditionalOnClass({MongoOperations.class, Tmf630MongoSplitBacked.class})
public class Tmf630MongoSplitCollectionAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(Tmf630MongoSplitCollectionAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public MongoSplitEntityRegistry tmf630MongoSplitEntityRegistry(
      ObjectProvider<MongoMappingContext> mongoMappingContextProvider) {
    MongoSplitEntityRegistry registry = new MongoSplitEntityRegistry();
    MongoMappingContext ctx = mongoMappingContextProvider.getIfAvailable();
    if (ctx == null) {
      log.debug(
          "tmf630-mongo-split: no MongoMappingContext available; registry stays empty. "
              + "Expected on services that add the module to the classpath before "
              + "configuring spring-data-mongodb.");
      return registry;
    }
    Set<Class<?>> candidates = new HashSet<>();
    for (MongoPersistentEntity<?> entity : ctx.getPersistentEntities()) {
      candidates.add(entity.getType());
      // Also crawl properties for split candidates whose parent type didn't map yet.
      for (MongoPersistentProperty prop : (Iterable<MongoPersistentProperty>) entity) {
        PersistentEntity<?, ?> owning = prop.getOwner();
        if (owning != null) candidates.add(owning.getType());
      }
    }
    registry.registerAll(candidates);
    if (registry.all().isEmpty()) {
      log.debug(
          "tmf630-mongo-split: no @Tmf630MongoSplitBacked entities found; module is "
              + "inert until an entity opts in.");
    }
    return registry;
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630MongoSplitWriteExecutor tmf630MongoSplitWriteExecutor(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    return new Tmf630MongoSplitWriteExecutor(mongoOperations, registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630MongoSplitReadMerger tmf630MongoSplitReadMerger(
      MongoOperations mongoOperations, MongoSplitEntityRegistry registry) {
    return new Tmf630MongoSplitReadMerger(mongoOperations, registry);
  }

  @Bean
  @ConditionalOnMissingBean
  public MongoInnerPredicateTranslator tmf630MongoInnerPredicateTranslator() {
    return new SimpleMongoInnerPredicateTranslator();
  }

  @Bean
  @ConditionalOnMissingBean
  public MongoSplitAwareFilterTranslator tmf630MongoSplitAwareFilterTranslator(
      MongoSplitEntityRegistry registry,
      MongoOperations mongoOperations,
      MongoInnerPredicateTranslator innerPredicateTranslator) {
    return new MongoSplitAwareFilterTranslator(
        registry, mongoOperations, innerPredicateTranslator);
  }
}
