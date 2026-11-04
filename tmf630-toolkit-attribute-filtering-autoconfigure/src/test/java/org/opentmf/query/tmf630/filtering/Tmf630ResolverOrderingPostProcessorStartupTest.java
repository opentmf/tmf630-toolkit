package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;

/**
 * The ordering post-processor is a {@code static} {@code @Bean}: registering it must not create
 * the auto-configuration early, which Spring reports at startup as a {@code
 * BeanPostProcessorChecker} warning naming the configuration class.
 */
@ExtendWith(OutputCaptureExtension.class)
class Tmf630ResolverOrderingPostProcessorStartupTest {

  @Test
  void theContextStartsWithoutTheBeanPostProcessorCheckerWarning(CapturedOutput output) {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                WebMvcAutoConfiguration.class, Tmf630AttributeFilteringAutoConfiguration.class))
        .run(context -> assertThat(context).hasNotFailed());

    assertThat(output.getAll())
        .doesNotContain("not eligible for getting processed by all BeanPostProcessors");
  }
}
