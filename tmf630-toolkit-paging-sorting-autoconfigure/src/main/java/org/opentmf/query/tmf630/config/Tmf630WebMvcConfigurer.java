package org.opentmf.query.tmf630.config;

import java.util.List;
import java.util.function.Supplier;
import org.opentmf.query.tmf630.paging.TmfPageableHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfRichPageableHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfRichSortHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfSortHandlerMethodArgumentResolver;
import org.opentmf.query.tmf630.paging.TmfSortKeyValidator;
import org.opentmf.query.tmf630.paging.TmfSortParser;
import org.opentmf.query.tmf630.versioning.TmfVersionedIdArgumentResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final Supplier<TmfSortKeyValidator> sortKeyValidator;

  public Tmf630WebMvcConfigurer(Tmf630PagingProperties properties) {
    this.properties = properties;
    this.sortKeyValidator = () -> TmfSortKeyValidator.NONE;
  }

  /**
   * @param sortKeyValidator the plain-sort-key validator handed to every sort-parsing resolver —
   *     contributed by the attribute-filtering autoconfiguration, which checks keys against the
   *     handler's filter root. Absent, the resolvers accept every key ({@link
   *     TmfSortKeyValidator#NONE}).
   */
  @Autowired
  public Tmf630WebMvcConfigurer(
      Tmf630PagingProperties properties, ObjectProvider<TmfSortKeyValidator> sortKeyValidator) {
    this.properties = properties;
    this.sortKeyValidator = () -> sortKeyValidator.getIfAvailable(() -> TmfSortKeyValidator.NONE);
  }

  @Override
  public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
    TmfSortParser sortParser =
        new TmfSortParser(
            properties.getSortAllowlist(),
            properties.isAllowNestedSortProperties(),
            properties.isNullsLast());
    TmfSortKeyValidator validator = sortKeyValidator.get();
    resolvers.add(0, new TmfSortHandlerMethodArgumentResolver(sortParser, validator));
    resolvers.add(1, new TmfRichSortHandlerMethodArgumentResolver(sortParser, validator));
    // Resolution order matters: TmfRichPageableHandlerMethodArgumentResolver claims
    // TmfRichPageable parameters first; the remaining (plain Pageable) parameters
    // fall through to TmfPageableHandlerMethodArgumentResolver.
    resolvers.add(
        2, new TmfRichPageableHandlerMethodArgumentResolver(properties.toSettings(), validator));
    resolvers.add(
        3, new TmfPageableHandlerMethodArgumentResolver(properties.toSettings(), validator));
    // TMF-630 Part 4 §2.5 — binds @PathVariable(...) TmfVersionedId parameters.
    // Only claims parameters typed exactly TmfVersionedId, so Spring's own
    // @PathVariable resolver keeps handling all other path variables.
    resolvers.add(4, new TmfVersionedIdArgumentResolver());
  }
}
