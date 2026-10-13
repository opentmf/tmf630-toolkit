package org.opentmf.query.tmf630.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.opentmf.query.tmf630.advice.Tmf630UndeclaredQueryParameterExceptionHandler;
import org.opentmf.query.tmf630.queryparams.Tmf630DeclaredQueryParameters;
import org.opentmf.query.tmf630.queryparams.Tmf630QueryArgumentClassifier;
import org.opentmf.query.tmf630.queryparams.Tmf630UndeclaredQueryParameterInterceptor;
import org.opentmf.query.tmf630.queryparams.Tmf630UndeclaredQueryParameterListener;
import org.opentmf.query.tmf630.queryparams.UndeclaredQueryParamBehavior;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the undeclared-query-parameter guard — {@link
 * Tmf630UndeclaredQueryParameterInterceptor} on every mapping, plus the handler that answers its
 * refusals as TMF error bodies — when {@code opentmf.tmf630.undeclared-query-params.on-undeclared}
 * is {@code WARN} or {@code REJECT}. Under the default {@code IGNORE} nothing touches a request.
 *
 * <p>The {@link Tmf630DeclaredQueryParameters} bean exists in every mode: it is what {@code
 * Tmf630QueryParameterContract} compares with the OpenAPI document, whether or not the guard is
 * switched on yet.
 */
@AutoConfiguration(after = Tmf630QueryLimitsAutoConfiguration.class)
@EnableConfigurationProperties(Tmf630UndeclaredQueryParamsProperties.class)
public class Tmf630UndeclaredQueryParamsAutoConfiguration {

  private static final String PREFIX = "opentmf.tmf630.undeclared-query-params";

  /**
   * @param classifiers the application's own classifiers, consulted first, in bean order
   */
  @Bean
  @ConditionalOnMissingBean
  public Tmf630DeclaredQueryParameters tmf630DeclaredQueryParameters(
      Tmf630UndeclaredQueryParamsProperties properties,
      ObjectProvider<Tmf630QueryArgumentClassifier> classifiers) {
    List<Tmf630QueryArgumentClassifier> all =
        new ArrayList<>(classifiers.orderedStream().toList());
    all.add(Tmf630QueryArgumentClassifier.nonQuery(properties.getNonQueryArguments()));
    return new Tmf630DeclaredQueryParameters(all);
  }

  /** The guard itself, registered only under {@code WARN} or {@code REJECT}. */
  @Configuration(proxyBeanMethods = false)
  @Conditional(OnGuardActive.class)
  static class Guard implements WebMvcConfigurer {

    private final Tmf630DeclaredQueryParameters declaredQueryParameters;
    private final Tmf630UndeclaredQueryParamsProperties properties;
    private final ObjectProvider<Tmf630UndeclaredQueryParameterListener> listeners;

    Guard(
        Tmf630DeclaredQueryParameters declaredQueryParameters,
        Tmf630UndeclaredQueryParamsProperties properties,
        ObjectProvider<Tmf630UndeclaredQueryParameterListener> listeners) {
      this.declaredQueryParameters = declaredQueryParameters;
      this.properties = properties;
      this.listeners = listeners;
    }

    /**
     * Registered last, so every interceptor at the default order runs first — the query-limits
     * guard above all: an oversize value is refused as too long before its name is judged.
     */
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
      List<Tmf630UndeclaredQueryParameterListener> all = listeners.orderedStream().toList();
      Tmf630UndeclaredQueryParameterListener listener =
          (method, route, undeclared, behavior) ->
              all.forEach(each -> each.undeclared(method, route, undeclared, behavior));
      registry
          .addInterceptor(
              new Tmf630UndeclaredQueryParameterInterceptor(
                  declaredQueryParameters, properties.getOnUndeclared(), listener))
          .order(Ordered.LOWEST_PRECEDENCE);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = PREFIX,
        name = "exception-handler.enabled",
        havingValue = "true",
        matchIfMissing = true)
    Tmf630UndeclaredQueryParameterExceptionHandler tmf630UndeclaredQueryParamExceptionHandler() {
      return new Tmf630UndeclaredQueryParameterExceptionHandler();
    }
  }

  /**
   * Counts every warned or refused request as {@code opentmf.tmf630.undeclared.query.params},
   * tagged by {@code method}, {@code uri} (the route pattern) and {@code outcome} ({@code warned}
   * / {@code rejected}). The parameter names are logged, never tagged: the caller chooses them.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  @Conditional(OnGuardActive.class)
  static class Metrics {

    static final String METER = "opentmf.tmf630.undeclared.query.params";
    private static final String DESCRIPTION =
        "Reads carrying a query parameter their handler does not declare";

    @Bean
    Tmf630UndeclaredQueryParameterListener tmf630UndeclaredQueryParameterMetrics(
        ObjectProvider<MeterRegistry> registry) {
      return (method, route, undeclared, behavior) ->
          registry.ifAvailable(
              meters ->
                  Counter.builder(METER)
                      .description(DESCRIPTION)
                      .tag("method", method)
                      .tag("uri", route)
                      .tag(
                          "outcome",
                          behavior == UndeclaredQueryParamBehavior.REJECT ? "rejected" : "warned")
                      .register(meters)
                      .increment());
    }
  }

  /** {@code on-undeclared} is {@code WARN} or {@code REJECT}. */
  static class OnGuardActive extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      UndeclaredQueryParamBehavior behavior =
          Binder.get(context.getEnvironment())
              .bind(PREFIX + ".on-undeclared", UndeclaredQueryParamBehavior.class)
              .orElse(UndeclaredQueryParamBehavior.IGNORE);
      return behavior == UndeclaredQueryParamBehavior.IGNORE
          ? ConditionOutcome.noMatch(PREFIX + ".on-undeclared is IGNORE")
          : ConditionOutcome.match(PREFIX + ".on-undeclared is " + behavior);
    }
  }
}
