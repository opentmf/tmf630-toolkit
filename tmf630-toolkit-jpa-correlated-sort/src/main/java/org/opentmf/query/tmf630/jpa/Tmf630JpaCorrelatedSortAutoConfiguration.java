package org.opentmf.query.tmf630.jpa;

import jakarta.persistence.EntityManager;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-wires {@link Tmf630JpaCorrelatedSortExecutor} when JPA is on the classpath and an
 * {@link EntityManager} bean exists. The executor honors the toolkit-wide
 * {@code opentmf.tmf630.paging.nulls-last} property when {@link Tmf630PagingSettings}
 * is available (via the paging-sorting-autoconfigure module); otherwise it defaults to
 * {@code false} preserving the underlying dialect's null-ordering behavior.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
public class Tmf630JpaCorrelatedSortAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JpaCorrelatedSortExecutor tmf630JpaCorrelatedSortExecutor(
      EntityManager entityManager, ObjectProvider<Tmf630PagingSettings> pagingSettings) {
    Tmf630PagingSettings settings = pagingSettings.getIfAvailable();
    boolean nullsLast = settings != null && settings.nullsLast();
    return new Tmf630JpaCorrelatedSortExecutor(entityManager, nullsLast);
  }

  @Bean
  @ConditionalOnMissingBean
  public Tmf630JpaVersionResolver tmf630JpaVersionResolver(EntityManager entityManager) {
    return new Tmf630JpaVersionResolver(entityManager);
  }
}
