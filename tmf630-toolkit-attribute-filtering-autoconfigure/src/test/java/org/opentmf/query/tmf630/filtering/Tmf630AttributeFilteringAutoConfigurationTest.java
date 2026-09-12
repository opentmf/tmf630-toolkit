package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.paging.TmfSortKeyValidator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class Tmf630AttributeFilteringAutoConfigurationTest {

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, Tmf630AttributeFilteringAutoConfiguration.class));

  @Test
  void doesNotCreateResolverWhenFeatureDisabled() {
    runner
        .withPropertyValues("opentmf.tmf630.attribute-filtering.enabled=false")
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(Tmf630PredicateArgumentResolver.class)
                    .doesNotHaveBean(TmfSortKeyValidator.class));
  }

  @Test
  void contributesTheFilterRootSortKeyValidatorAndTheQuerydslRootLocator() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(TmfSortKeyValidator.class);
          assertThat(context.getBean(TmfSortKeyValidator.class))
              .isInstanceOf(FilterRootSortKeyValidator.class);
          assertThat(context).hasBean("tmf630QuerydslPredicateRootLocator");
        });
  }

  @Test
  void aConsumerSortKeyValidatorReplacesTheToolkitOne() {
    runner
        .withBean(TmfSortKeyValidator.class, () -> TmfSortKeyValidator.NONE)
        .run(
            context ->
                assertThat(context.getBean(TmfSortKeyValidator.class))
                    .isSameAs(TmfSortKeyValidator.NONE));
  }
}
