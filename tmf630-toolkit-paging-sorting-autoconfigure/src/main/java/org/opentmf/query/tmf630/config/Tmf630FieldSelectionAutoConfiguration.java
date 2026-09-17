package org.opentmf.query.tmf630.config;

import org.opentmf.query.tmf630.advice.Tmf630ResponseBodyAdvice;
import org.opentmf.query.tmf630.paging.config.Tmf630LinkHeaderSettings;
import org.springframework.beans.factory.ObjectProvider;
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

  /**
   * @param pagingProperties the paging properties, present when the paging auto-configuration
   *     is active — they carry the {@code Link} header budget. Absent, the budget defaults.
   */
  @Bean
  @ConditionalOnMissingBean
  public Tmf630ResponseBodyAdvice tmf630ResponseBodyAdvice(
      Tmf630FieldSelectionProperties properties,
      ObjectProvider<Tmf630PagingProperties> pagingProperties) {
    Tmf630PagingProperties paging = pagingProperties.getIfAvailable();
    Tmf630LinkHeaderSettings linkHeaderSettings =
        paging == null ? Tmf630LinkHeaderSettings.DEFAULT : paging.toLinkHeaderSettings();
    return new Tmf630ResponseBodyAdvice(properties.getDefaultDepth(), linkHeaderSettings);
  }
}
