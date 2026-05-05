package org.opentmf.query.tmf630.config;

import java.util.List;
import org.opentmf.query.tmf630.paging.TmfPageableHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfRichPageableHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfRichSortHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfSortHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfSortParser;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.jspecify.annotations.NonNull;
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
  public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
    TmfSortParser sortParser =
        new TmfSortParser(
            properties.getSortAllowlist(), properties.isAllowNestedSortProperties());
    resolvers.add(0, new TmfSortHandlerMethodArgumentResolver(sortParser));
    resolvers.add(1, new TmfRichSortHandlerMethodArgumentResolver(sortParser));
    // Resolution order matters: TmfRichPageableHandlerMethodArgumentResolver claims
    // TmfRichPageable parameters first; the remaining (plain Pageable) parameters
    // fall through to TmfPageableHandlerMethodArgumentResolver.
    resolvers.add(2, new TmfRichPageableHandlerMethodArgumentResolver(properties.toSettings()));
    resolvers.add(3, new TmfPageableHandlerMethodArgumentResolver(properties.toSettings()));
  }
}
