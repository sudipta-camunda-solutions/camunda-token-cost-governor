package io.github.camunda.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.runtime.test.outbound.OutboundConnectorContextBuilder;
import io.github.camunda.connector.metrics.TokenCostMetrics;
import io.github.camunda.connector.model.PriceSource;
import io.github.camunda.connector.model.TokenCostRequest;
import io.github.camunda.connector.model.TokenCostResult;
import io.github.camunda.connector.pricing.PriceTable;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** What the connector logs - the trace/debug output - including that it never logs secret text. */
class GovernorConnectorLoggingTest {

  private static final String PRICE_TABLE_JSON =
      "[{\"provider\":\"anthropic\",\"model\":\"claude-sonnet-5\",\"inputPricePerMillionUsd\":3.00,\"outputPricePerMillionUsd\":15.00,\"currency\":\"USD\"}]";

  private final Logger connectorPackageLogger = (Logger) LoggerFactory.getLogger("io.github.camunda.connector");
  private ListAppender<ILoggingEvent> appender;
  private GovernorConnector connector;

  @BeforeEach
  void setUp() {
    appender = new ListAppender<>();
    appender.start();
    connectorPackageLogger.addAppender(appender);
    connectorPackageLogger.setLevel(Level.DEBUG);
    connector = new GovernorConnector(PriceTable.parseSecret("[]").orElseThrow(), new TokenCostMetrics(new SimpleMeterRegistry()));
    appender.list.clear(); // drop the constructor's startup line; asserted separately below
  }

  @AfterEach
  void tearDown() {
    connectorPackageLogger.detachAppender(appender);
  }

  private static OutboundConnectorContext contextWith(String priceTableSecret) {
    return OutboundConnectorContextBuilder.create()
        .variables(
            Map.of(
                "provider", "anthropic",
                "model", "claude-sonnet-5",
                "inputTokens", 1000L,
                "outputTokens", 1000L,
                "agentName", "claims-triage",
                "priceTableSecret", priceTableSecret))
        .build();
  }

  private List<String> messages(Level level) {
    return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
  }

  private List<String> allMessages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Test
  void startupLogsDefaultTableSize() {
    appender.list.clear();
    new GovernorConnector(PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow(), new TokenCostMetrics(new SimpleMeterRegistry()));

    assertThat(messages(Level.INFO)).containsExactly("Token Cost Reporter loaded, default price table rows=1");
  }

  @Test
  void success_logsOneInfoTraceLineWithEverythingNeededToFollowTheRun() {
    TokenCostResult result = (TokenCostResult) connector.execute(contextWith(PRICE_TABLE_JSON));

    assertThat(result.priceSource()).isEqualTo(PriceSource.CLUSTER_SECRET);
    assertThat(messages(Level.INFO))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .startsWith("Token cost: ")
                    .contains("provider=anthropic", "model=claude-sonnet-5", "agent=claims-triage")
                    .contains("inputTokens=1000", "outputTokens=1000", "totalTokens=2000")
                    .contains("costMicros=18000", "costUsd=0.018000", "priceSource=CLUSTER_SECRET")
                    .containsPattern("elapsedMs=\\d+"));
  }

  @Test
  void debug_explainsThePriceDecisionWithoutEchoingTheSecret() {
    connector.execute(contextWith(PRICE_TABLE_JSON));

    assertThat(messages(Level.DEBUG))
        .anyMatch(m -> m.startsWith("Request: ") && m.contains("priceTableSecret=" + PRICE_TABLE_JSON.length() + " chars"))
        .anyMatch(m -> m.equals("Parsed GOVERNOR_PRICE_TABLE: 1 rows"))
        .anyMatch(m -> m.startsWith("Price found in CLUSTER_SECRET: inputPer1kMicros=3000 outputPer1kMicros=15000"))
        .anyMatch(m -> m.startsWith("Recorded governor_* counters:"));
    assertThat(allMessages()).noneMatch(m -> m.contains("inputPricePerMillionUsd"));
  }

  @Test
  void malformedSecret_warnsFallsBackAndNeverLogsTheSecretText() {
    String malformed = "[{\"provider\":\"anthropic\", THIS-IS-NOT-JSON secret-marker-12345";
    // The bundled default table must cover the model, or the fallback would (correctly) fail.
    GovernorConnector withDefault =
        new GovernorConnector(PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow(), new TokenCostMetrics(new SimpleMeterRegistry()));
    appender.list.clear();

    TokenCostResult result = (TokenCostResult) withDefault.execute(contextWith(malformed));

    assertThat(result.priceSource()).isEqualTo(PriceSource.DEFAULT_TABLE);
    assertThat(messages(Level.WARN))
        .singleElement()
        .satisfies(
            w ->
                assertThat(w)
                    .contains("GOVERNOR_PRICE_TABLE could not be parsed")
                    .contains(malformed.length() + " chars")
                    .contains("falling back to the bundled default table"));
    assertThat(allMessages()).noneMatch(m -> m.contains("secret-marker-12345"));
  }

  @Test
  void blankSecret_isNotAWarning() {
    GovernorConnector withDefault =
        new GovernorConnector(PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow(), new TokenCostMetrics(new SimpleMeterRegistry()));
    appender.list.clear();

    withDefault.execute(contextWith("  "));

    assertThat(messages(Level.WARN)).isEmpty();
    assertThat(messages(Level.DEBUG)).contains("GOVERNOR_PRICE_TABLE is blank - using the bundled default table");
  }

  @Test
  void priceNotFound_warnsWithTheRequestedKeyAndTableSizes() {
    String otherModelOnly =
        "[{\"provider\":\"openai\",\"model\":\"gpt-5\",\"inputPricePerMillionUsd\":1.0,\"outputPricePerMillionUsd\":1.0,\"currency\":\"USD\"}]";

    assertThatThrownBy(() -> connector.execute(contextWith(otherModelOnly))).isInstanceOf(ConnectorException.class);

    assertThat(messages(Level.WARN))
        .singleElement()
        .satisfies(w -> assertThat(w).contains("No price for anthropic:claude-sonnet-5", "secretTable=1 rows", "defaultTableRows=0"));
    assertThat(messages(Level.DEBUG)).anyMatch(m -> m.startsWith("Known price keys: secretTable=[openai:gpt-5]"));
  }

  @Test
  void emptyCompositeRegistry_warnsExactlyOnceThatMetricsWillNotBeExported() {
    GovernorConnector unbound =
        new GovernorConnector(PriceTable.parseSecret(PRICE_TABLE_JSON).orElseThrow(), new TokenCostMetrics(new CompositeMeterRegistry()));
    appender.list.clear();

    unbound.execute(contextWith(PRICE_TABLE_JSON));
    unbound.execute(contextWith(PRICE_TABLE_JSON));

    assertThat(messages(Level.WARN))
        .singleElement()
        .satisfies(w -> assertThat(w).contains("no child registries bound").contains("will NOT appear on /actuator/prometheus"));
  }

  /** Named like the runtime's real exception on purpose - the connector matches by simple class name. */
  static class SecretNotAvailableException extends RuntimeException {
    SecretNotAvailableException(String message) {
      super(message);
    }
  }

  private OutboundConnectorContext contextFailingWith(RuntimeException failure) {
    OutboundConnectorContext context = mock(OutboundConnectorContext.class);
    when(context.bindVariables(TokenCostRequest.class)).thenThrow(failure);
    return context;
  }

  @Test
  void consoleSecretAuthFailure_logsErrorWithSecretsScopeHint_andRethrowsUnchanged() {
    RuntimeException failure =
        new RuntimeException(
            "outer wrapper",
            new RuntimeException("Token retrieval failed from: https://login.cloud.camunda.io/oauth/token\nResponse code: 401\nAudience: secrets.camunda.io"));

    assertThatThrownBy(() -> connector.execute(contextFailingWith(failure))).isSameAs(failure);

    assertThat(messages(Level.ERROR))
        .singleElement()
        .satisfies(m -> assertThat(m).contains("Token cost execution failed:", "HINT:", "'Secrets' scope", "CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED"));
  }

  @Test
  void secretNotAvailable_logsErrorWithCreateTheSecretHint() {
    RuntimeException failure = new SecretNotAvailableException("Secret with name 'GOVERNOR_PRICE_TABLE' is not available");

    assertThatThrownBy(() -> connector.execute(contextFailingWith(failure))).isSameAs(failure);

    assertThat(messages(Level.ERROR))
        .singleElement()
        .satisfies(m -> assertThat(m).contains("GOVERNOR_PRICE_TABLE' is not available", "HINT:", "even as an empty array []"));
  }

  @Test
  void unrecognisedFailure_logsErrorWithoutAHint_andRethrowsUnchanged() {
    RuntimeException failure = new IllegalStateException("something else entirely");

    assertThatThrownBy(() -> connector.execute(contextFailingWith(failure))).isSameAs(failure);

    assertThat(messages(Level.ERROR)).singleElement().satisfies(m -> assertThat(m).contains("something else entirely").doesNotContain("HINT:"));
  }
}
