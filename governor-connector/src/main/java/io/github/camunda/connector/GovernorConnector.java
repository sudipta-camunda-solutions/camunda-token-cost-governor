package io.github.camunda.connector;

import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorContext;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.github.camunda.connector.error.TokenCostErrorCodes;
import io.github.camunda.connector.metrics.TokenCostMetrics;
import io.github.camunda.connector.model.PriceSource;
import io.github.camunda.connector.model.TokenCostRequest;
import io.github.camunda.connector.model.TokenCostResult;
import io.github.camunda.connector.pricing.PriceEntry;
import io.github.camunda.connector.pricing.PriceTable;
import io.github.camunda.connector.pricing.Pricer;
import java.util.Optional;

/**
 * Entry point for the "Token Cost Reporter" outbound connector: computes the token count and USD
 * cost of one LLM call from token counts the process already has (most naturally the AI Agent
 * Task/Sub-process element's own result), and a price table.
 *
 * <p>Fully self-contained: no HTTP calls, no database, no budget/policy enforcement. Price data
 * comes from the {@code GOVERNOR_PRICE_TABLE} cluster secret (a JSON array of price rows,
 * centrally maintained - see the README), falling back to a small bundled illustrative default
 * table when that secret is unset. Every execution also records Micrometer counters (see
 * {@link TokenCostMetrics}) so spend is chartable via Prometheus/Grafana, in addition to
 * returning {@code tokenCostResult} as an ordinary process variable for Operate/Optimize.
 */
@OutboundConnector(name = "Token Cost Reporter", type = "io.github.camunda:token-cost-governor:1")
@ElementTemplate(
    id = "io.github.camunda.TokenCostGovernor.v2",
    name = "Token Cost Reporter",
    version = 2,
    description = "Computes the token count and USD cost of one LLM call.",
    icon = "icon.svg",
    documentationRef = "https://docs.camunda.io/docs/components/connectors/custom-built-connectors/connector-sdk/",
    inputDataClass = TokenCostRequest.class,
    defaultResultVariable = "tokenCostResult",
    propertyGroups = {
      @ElementTemplate.PropertyGroup(id = "model", label = "Model"),
      @ElementTemplate.PropertyGroup(id = "usage", label = "Token usage")
    })
public class GovernorConnector implements OutboundConnectorFunction {

  private final PriceTable defaultPriceTable;
  private final TokenCostMetrics metrics;

  /** Used by the connector runtime, which discovers this class via SPI and requires a no-arg
   *  constructor. */
  public GovernorConnector() {
    this(PriceTable.loadDefault(), new TokenCostMetrics());
  }

  /** Package-visible for tests that want to substitute a synthetic default table or metrics
   *  registry. */
  GovernorConnector(PriceTable defaultPriceTable, TokenCostMetrics metrics) {
    this.defaultPriceTable = defaultPriceTable;
    this.metrics = metrics;
  }

  @Override
  public Object execute(OutboundConnectorContext context) {
    TokenCostRequest request = context.bindVariables(TokenCostRequest.class);

    Optional<PriceTable> secretTable = PriceTable.parseSecret(request.priceTableSecret());

    PriceEntry price = null;
    PriceSource source = null;
    if (secretTable.isPresent()) {
      price = secretTable.get().lookup(request.provider(), request.model()).orElse(null);
      if (price != null) {
        source = PriceSource.CLUSTER_SECRET;
      }
    }
    if (price == null) {
      price = defaultPriceTable.lookup(request.provider(), request.model()).orElse(null);
      if (price != null) {
        source = PriceSource.DEFAULT_TABLE;
      }
    }
    if (price == null) {
      throw new ConnectorException(
          TokenCostErrorCodes.PRICE_NOT_FOUND,
          "No price for \"" + request.provider() + ":" + request.model()
              + "\" in the GOVERNOR_PRICE_TABLE cluster secret or the bundled default table.");
    }

    long costMicros = Pricer.priceMicros(request.inputTokens(), request.outputTokens(), price);
    TokenCostResult result =
        new TokenCostResult(
            request.provider(),
            request.model(),
            request.inputTokens(),
            request.outputTokens(),
            request.inputTokens() + request.outputTokens(),
            costMicros,
            costMicros / 1_000_000.0,
            price.currency(),
            source);

    metrics.record(result);
    return result;
  }
}
