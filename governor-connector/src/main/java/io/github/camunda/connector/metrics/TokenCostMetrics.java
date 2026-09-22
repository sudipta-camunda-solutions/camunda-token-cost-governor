package io.github.camunda.connector.metrics;

import io.github.camunda.connector.model.TokenCostResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/**
 * Records aggregate token/cost counters on a {@link MeterRegistry}, tagged by provider/model, so
 * spend is chartable (Prometheus + Grafana) without a database. Recorded via Micrometer's
 * <b>static</b> global registry rather than dependency injection: this connector is discovered by
 * the Camunda connector runtime via the JDK {@code ServiceLoader} (SPI), which runs entirely
 * outside Spring's bean-injection graph - there is no {@code ApplicationContext}/{@code
 * MeterRegistry} bean available to inject. Spring Boot's {@code
 * management.metrics.use-global-registry} default ({@code true}) binds every auto-configured
 * registry (including a {@code PrometheusMeterRegistry}, once {@code micrometer-registry-
 * prometheus} is on the host app's classpath) into that same static instance, so metrics recorded
 * here surface on the host app's own {@code /actuator/prometheus} with no other wiring needed.
 */
public final class TokenCostMetrics {

  private final MeterRegistry registry;

  public TokenCostMetrics() {
    this(Metrics.globalRegistry);
  }

  /** For tests that want to assert against a local, non-global registry. */
  public TokenCostMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void record(TokenCostResult result) {
    Counter.builder("governor_calls_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .register(registry)
        .increment();
    Counter.builder("governor_tokens_input_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .register(registry)
        .increment(result.inputTokens());
    Counter.builder("governor_tokens_output_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .register(registry)
        .increment(result.outputTokens());
    Counter.builder("governor_cost_usd_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .register(registry)
        .increment(result.costUsd());
  }
}
