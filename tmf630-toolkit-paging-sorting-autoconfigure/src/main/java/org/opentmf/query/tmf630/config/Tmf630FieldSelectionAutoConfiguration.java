package org.opentmf.query.tmf630.config;

import org.opentmf.query.tmf630.advice.Tmf630ResponseBodyAdvice;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(Tmf630FieldSelectionProperties.class)
@ConditionalOnProperty(
    prefix = "opentmf.tmf630.field-selection",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Tmf630FieldSelectionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Tmf630ResponseBodyAdvice tmf630ResponseBodyAdvice(
      Tmf630FieldSelectionProperties properties) {
    return new Tmf630ResponseBodyAdvice(properties.getDefaultDepth());
  }
}
