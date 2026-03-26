package org.opentmf.query.tmf630.filtering;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class Tmf630AttributeFilteringAutoConfigurationTest {

  @Test
  void doesNotCreateResolverWhenFeatureDisabled() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                WebMvcAutoConfiguration.class, Tmf630AttributeFilteringAutoConfiguration.class))
        .withPropertyValues("opentmf.tmf630.attribute-filtering.enabled=false")
        .run(
            context ->
                org.assertj.core.api.Assertions.assertThat(context)
                    .doesNotHaveBean(Tmf630PredicateArgumentResolver.class));
  }
}
