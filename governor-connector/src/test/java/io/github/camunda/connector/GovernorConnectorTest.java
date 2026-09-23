package io.github.camunda.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.JobContext;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.github.camunda.connector.error.TokenCostErrorCodes;
import io.github.camunda.connector.metrics.TokenCostMetrics;
import io.github.camunda.connector.model.PriceSource;
import io.github.camunda.connector.model.TokenCostRequest;
import io.github.camunda.connector.model.TokenCostResult;
import io.github.camunda.connector.pricing.PriceTable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GovernorConnectorTest {

  private static final String PRICE_TABLE_JSON =
      "[{\"provider\":\"anthropic\",\"model\":\"claude-sonnet-5\",\"inputPricePerMillionUsd\":3.00,\"outputPricePerMillionUsd\":15.00,\"currency\":\"USD\"}]";

  private SimpleMeterRegistry meterRegistry;
  private GovernorConnector connector;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    PriceTable emptyDefaultTable = PriceTable.parseSecret("[]").orElseThrow();
    connector = new GovernorConnector(emptyDefaultTable, new TokenCostMetrics(meterRegistry));
  }

  private static OutboundConnectorContext contextWith(Map<String, Object> variables) {
    return OutboundConnectorContextBuilder.create().variables(variables).build();
  }

  private static Map<String, Object> baseVariables(String priceTableSecret) {
    return Map.of(
        "provider", "anthropic",
        "model", "claude-sonnet-5",
        "inputTokens", 1000L,
        "outputTokens", 1000L,
        "priceTableSecret", priceTableSecret);
  }

  @Test
  void computesCostFromClusterSecretTable() {
    TokenCostResult result = (TokenCostResult) connector.execute(contextWith(baseVariables(PRICE_TABLE_JSON)));

    assertThat(result.priceSource()).isEqualTo(PriceSource.CLUSTER_SECRET);
    assertThat(result.inputTokens()).isEqualTo(1000);
    assertThat(result.outputTokens()).isEqualTo(1000);
    assertThat(result.totalTokens()).isEqualTo(2000);
    // $3.00/1M input * 1000 tokens = $0.003 = 3000 micros; $15.00/1M output * 1000 tokens = $0.015 = 15000 micros.
    assertThat(result.costMicros()).isEqualTo(3_000 + 15_000);
    assertThat(result.costUsd()).isEqualTo(0.018);
    assertThat(result.currency()).isEqualTo("USD");
  }

  @Test
  void fallsBackToDefaultTableWhenSecretIsBlank() {
    PriceTable defaultTable = PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow();
    GovernorConnector connectorWithDefault = new GovernorConnector(defaultTable, new TokenCostMetrics(meterRegistry));

    // A cluster secret that was never CREATED at all makes bindVariables() throw outright
    // (SecretNotAvailableException, verified against the SDK's own secret-resolution code) -
    // there is no way to "gracefully" reference a nonexistent secret. Only a secret that exists
    // but is left blank resolves successfully to an empty string, which this connector then
    // treats as "not configured" and falls back to the bundled default table - exercised here by
    // actually registering the secret (via .secret(...)) with an empty value, matching how a
    // real Camunda cluster secret set up but left blank would behave.
    OutboundConnectorContext context =
        OutboundConnectorContextBuilder.create()
            .secret("GOVERNOR_PRICE_TABLE", "")
            .variables(baseVariables("{{secrets.GOVERNOR_PRICE_TABLE}}"))
            .build();

    TokenCostResult result = (TokenCostResult) connectorWithDefault.execute(context);

    assertThat(result.priceSource()).isEqualTo(PriceSource.DEFAULT_TABLE);
  }

  @Test
  void clusterSecretTableTakesPriorityOverDefaultTable() {
    PriceTable defaultTable = PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow();
    GovernorConnector connectorWithDefault = new GovernorConnector(defaultTable, new TokenCostMetrics(meterRegistry));
    String cheaperOverride =
        "[{\"provider\":\"anthropic\",\"model\":\"claude-sonnet-5\",\"inputPricePerMillionUsd\":1.00,\"outputPricePerMillionUsd\":1.00,\"currency\":\"USD\"}]";

    TokenCostResult result = (TokenCostResult) connectorWithDefault.execute(contextWith(baseVariables(cheaperOverride)));

    assertThat(result.priceSource()).isEqualTo(PriceSource.CLUSTER_SECRET);
    assertThat(result.costMicros()).isEqualTo(1_000 + 1_000);
  }

  @Test
  void throwsPriceNotFoundWhenModelUnknownEverywhere() {
    Map<String, Object> vars =
        Map.of("provider", "unknown", "model", "unknown-model", "inputTokens", 10L, "outputTokens", 10L, "priceTableSecret", "");

    assertThatThrownBy(() -> connector.execute(contextWith(vars)))
        .isInstanceOf(ConnectorException.class)
        .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode()).isEqualTo(TokenCostErrorCodes.PRICE_NOT_FOUND));
  }

  @Test
  void roundsHalfUpConsistentlyWithPricer() {
    // 0.001 USD/1M tokens = 1 micro/1k tokens - matches PricerTest's "oddPrice" case exactly:
    // 500 tokens * 1 micro = 500; (500 + 500) / 1000 = 1 -> rounds up.
    String oddPriceJson =
        "[{\"provider\":\"openai\",\"model\":\"gpt-5\",\"inputPricePerMillionUsd\":0.001,\"outputPricePerMillionUsd\":0,\"currency\":\"USD\"}]";
    Map<String, Object> vars =
        Map.of("provider", "openai", "model", "gpt-5", "inputTokens", 500L, "outputTokens", 0L, "priceTableSecret", oddPriceJson);

    TokenCostResult result = (TokenCostResult) connector.execute(contextWith(vars));

    assertThat(result.costMicros()).isEqualTo(1);
  }

  @Test
  void recordsMetricsOnSuccess() {
    connector.execute(contextWith(baseVariables(PRICE_TABLE_JSON)));

    assertThat(meterRegistry.get("governor_calls_total").counter().count()).isEqualTo(1.0);
    assertThat(meterRegistry.get("governor_tokens_input_total").counter().count()).isEqualTo(1000.0);
    assertThat(meterRegistry.get("governor_tokens_output_total").counter().count()).isEqualTo(1000.0);
    assertThat(meterRegistry.get("governor_cost_usd_total").counter().count()).isEqualTo(0.018);
  }

  @Test
  void explicitAgentName_appearsInResultAndAsMetricTag() {
    Map<String, Object> vars = new HashMap<>(baseVariables(PRICE_TABLE_JSON));
    vars.put("agent", "  claims-triage ");

    TokenCostResult result = (TokenCostResult) connector.execute(contextWith(vars));

    assertThat(result.agent()).isEqualTo("claims-triage");
    assertThat(meterRegistry.get("governor_cost_usd_total").tag("agent", "claims-triage").counter().count())
        .isEqualTo(0.018);
    assertThat(meterRegistry.get("governor_calls_total").tag("agent", "claims-triage").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void blankAgent_fallsBackToProcessIdAndElementIdFromJobContext() {
    JobContext job = mock(JobContext.class);
    when(job.getBpmnProcessId()).thenReturn("claims-process");
    when(job.getElementId()).thenReturn("Activity_TokenCost");
    OutboundConnectorContext context = mock(OutboundConnectorContext.class);
    when(context.getJobContext()).thenReturn(job);
    when(context.bindVariables(TokenCostRequest.class))
        .thenReturn(new TokenCostRequest("anthropic", "claude-sonnet-5", 1000L, 1000L, "  ", PRICE_TABLE_JSON));

    TokenCostResult result = (TokenCostResult) connector.execute(context);

    assertThat(result.agent()).isEqualTo("claims-process:Activity_TokenCost");
    assertThat(meterRegistry.get("governor_calls_total").tag("agent", "claims-process:Activity_TokenCost").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void blankAgent_withNoJobContextIds_isUnspecifiedNeverNull() {
    // The SDK's test context carries no process/element ids, so this exercises the last fallback.
    TokenCostResult result = (TokenCostResult) connector.execute(contextWith(baseVariables(PRICE_TABLE_JSON)));

    assertThat(result.agent()).isEqualTo("unspecified");
    assertThat(meterRegistry.get("governor_calls_total").tag("agent", "unspecified").counter().count()).isEqualTo(1.0);
  }
}
