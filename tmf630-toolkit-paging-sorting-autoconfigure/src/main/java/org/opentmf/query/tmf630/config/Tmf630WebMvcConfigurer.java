package org.opentmf.query.tmf630.config;

import java.util.List;
import org.opentmf.query.tmf630.paging.TmfPageableHandlerMethodArgumentResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@EnableConfigurationProperties(Tmf630PagingProperties.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
    prefix = "opentmf.tmf630.paging",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class Tmf630WebMvcConfigurer implements WebMvcConfigurer {

  private final Tmf630PagingProperties properties;

  public Tmf630WebMvcConfigurer(Tmf630PagingProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(0, new TmfPageableHandlerMethodArgumentResolver(properties.toSettings()));
  }
}
