package org.opentmf.query.tmf630.config;

import org.jspecify.annotations.NonNull;
import org.opentmf.query.tmf630.advice.Tmf630QueryLimitExceptionHandler;
import org.opentmf.query.tmf630.querylimits.Tmf630QueryLimitsInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the query-parameter guard on every mapping — {@link Tmf630QueryLimitsInterceptor}
 * plus the handler that answers its rejections as TMF error bodies. Independent of the paging
 * auto-configuration on purpose: see {@link Tmf630QueryLimitsProperties}.
 */
@AutoConfiguration
@EnableConfigurationProperties(Tmf630QueryLimitsProperties.class)
@ConditionalOnProperty(
    prefix = "opentmf.tmf630.query-limits",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Tmf630QueryLimitsAutoConfiguration implements WebMvcConfigurer {

  private final Tmf630QueryLimitsProperties properties;

  public Tmf630QueryLimitsAutoConfiguration(Tmf630QueryLimitsProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addInterceptors(@NonNull InterceptorRegistry registry) {
    registry.addInterceptor(new Tmf630QueryLimitsInterceptor(properties.toSettings()));
  }

  @Bean
  public Tmf630QueryLimitExceptionHandler tmf630QueryLimitExceptionHandler() {
    return new Tmf630QueryLimitExceptionHandler();
  }
}
