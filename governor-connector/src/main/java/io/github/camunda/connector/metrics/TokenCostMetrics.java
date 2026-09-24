package io.github.camunda.connector.metrics;

import io.github.camunda.connector.model.TokenCostResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records aggregate token/cost counters on a {@link MeterRegistry}, tagged by provider/model/agent, so
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

  private static final Logger LOG = LoggerFactory.getLogger(TokenCostMetrics.class);

  private final MeterRegistry registry;
  private final AtomicBoolean registryChecked = new AtomicBoolean(false);

  public TokenCostMetrics() {
    this(Metrics.globalRegistry);
  }

  /** For tests that want to assert against a local, non-global registry. */
  public TokenCostMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void record(TokenCostResult result) {
    checkRegistryOnce();

    Counter.builder("governor_calls_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .tag("agent", result.agent())
        .register(registry)
        .increment();
    Counter.builder("governor_tokens_input_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .tag("agent", result.agent())
        .register(registry)
        .increment(result.inputTokens());
    Counter.builder("governor_tokens_output_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .tag("agent", result.agent())
        .register(registry)
        .increment(result.outputTokens());
    Counter.builder("governor_cost_usd_total")
        .tag("provider", result.provider())
        .tag("model", result.model())
        .tag("agent", result.agent())
        .register(registry)
        .increment(result.costUsd());

    LOG.debug(
        "Recorded governor_* counters: provider={} model={} agent={} +calls=1 +inputTokens={} +outputTokens={} +costUsd={}",
        result.provider(),
        result.model(),
        result.agent(),
        result.inputTokens(),
        result.outputTokens(),
        result.costUsd());
  }

  /**
   * Once, on the first record: the global registry is a composite that starts empty and only
   * exports anything if the host app binds a real registry (e.g. Prometheus) into it. If none is
   * bound, the counters are recorded but never reach /actuator/prometheus - the single most
   * confusing "where are my metrics" failure, so say so loudly.
   */
  private void checkRegistryOnce() {
    if (!registryChecked.compareAndSet(false, true)) {
      return;
    }
    if (registry instanceof CompositeMeterRegistry composite) {
      int bound = composite.getRegistries().size();
      if (bound == 0) {
        LOG.warn(
            "Micrometer global registry has no child registries bound: governor_* counters are being recorded "
                + "but will NOT appear on /actuator/prometheus. Check the host exposes a Prometheus registry "
                + "(micrometer-registry-prometheus on the classpath) and management.metrics.use-global-registry is true.");
      } else {
        LOG.info("Micrometer global registry has {} child registr{} bound - governor_* counters are exportable", bound, bound == 1 ? "y" : "ies");
      }
    } else {
      LOG.debug("Recording governor_* counters on a non-composite registry: {}", registry.getClass().getSimpleName());
    }
  }
}
