package io.github.camunda.connector;

import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.JobContext;
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
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    version = 6,
    description = "Computes the token count and USD cost of one LLM call.",
    icon = "icon.svg",
    documentationRef = "https://docs.camunda.io/docs/components/connectors/custom-built-connectors/connector-sdk/",
    inputDataClass = TokenCostRequest.class,
    defaultResultVariable = "tokenCostResult",
    propertyGroups = {
      @ElementTemplate.PropertyGroup(id = "model", label = "Model"),
      @ElementTemplate.PropertyGroup(id = "usage", label = "Token usage"),
      @ElementTemplate.PropertyGroup(id = "agent", label = "Agent (optional)")
    })
public class GovernorConnector implements OutboundConnectorFunction {

  private static final Logger LOG = LoggerFactory.getLogger(GovernorConnector.class);

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
    LOG.info("Token Cost Reporter loaded, default price table rows={}", defaultPriceTable.size());
  }

  @Override
  public Object execute(OutboundConnectorContext context) {
    long startNanos = System.nanoTime();
    try {
      return compute(context, startNanos);
    } catch (ConnectorException e) {
      throw e;
    } catch (RuntimeException e) {
      // Includes failures binding the request (e.g. an unavailable GOVERNOR_PRICE_TABLE cluster
      // secret, or a validation error) - the runtime logs these too, but without our context.
      String hint = hintFor(e);
      if (hint != null) {
        LOG.error("Token cost execution failed: {}\nHINT: {}", e.toString(), hint);
      } else {
        LOG.error("Token cost execution failed: {}", e.toString());
      }
      LOG.debug("Token cost execution failure stack trace", e);
      throw e;
    }
  }

  /**
   * Turns the two secret-resolution failures seen in practice into an actionable hint. Walks the
   * cause chain by message/class name only - the runtime's exception classes are not compile
   * dependencies of this module. Returns null for anything unrecognised.
   */
  private static String hintFor(Throwable error) {
    Throwable t = error;
    for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
      String message = String.valueOf(t.getMessage());
      if (message.contains("secrets.camunda.io")) {
        return "The runtime could not authenticate to Camunda Console to read cluster secrets (token request for audience"
            + " secrets.camunda.io was refused). The API client behind CAMUNDA_CLIENT_ID/CAMUNDA_CLIENT_SECRET most likely"
            + " lacks the 'Secrets' scope, or its credentials are wrong. Create a client with the Secrets scope in Camunda"
            + " Console, or stop using Console secrets by unsetting CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED and"
            + " providing GOVERNOR_PRICE_TABLE as an environment variable.";
      }
      if ("SecretNotAvailableException".equals(t.getClass().getSimpleName())) {
        return "A secret this element references is not available to the runtime. Create it in Camunda Console (for"
            + " GOVERNOR_PRICE_TABLE even as an empty array []), and make sure a runtime you host yourself can read"
            + " Console secrets: API client with the 'Secrets' scope and CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED=true.";
      }
    }
    return null;
  }

  private Object compute(OutboundConnectorContext context, long startNanos) {
    TokenCostRequest request = context.bindVariables(TokenCostRequest.class);
    LOG.debug(
        "Request: provider={} model={} agent='{}' inputTokens={} outputTokens={} priceTableSecret={}",
        request.provider(),
        request.model(),
        request.agentName(),
        request.inputTokens(),
        request.outputTokens(),
        describeSecret(request.priceTableSecret()));

    Optional<PriceTable> secretTable = PriceTable.parseSecret(request.priceTableSecret());

    PriceEntry price = null;
    PriceSource source = null;
    if (secretTable.isPresent()) {
      price = secretTable.get().lookup(request.provider(), request.model()).orElse(null);
      if (price != null) {
        source = PriceSource.CLUSTER_SECRET;
      } else {
        LOG.debug(
            "{}:{} is not in the GOVERNOR_PRICE_TABLE secret ({} rows) - trying the bundled default table",
            request.provider(),
            request.model(),
            secretTable.get().size());
      }
    }
    if (price == null) {
      price = defaultPriceTable.lookup(request.provider(), request.model()).orElse(null);
      if (price != null) {
        source = PriceSource.DEFAULT_TABLE;
      }
    }
    if (price == null) {
      LOG.warn(
          "No price for {}:{} - secretTable={} defaultTableRows={}",
          request.provider(),
          request.model(),
          secretTable.map(t -> t.size() + " rows").orElse("absent"),
          defaultPriceTable.size());
      LOG.debug(
          "Known price keys: secretTable={} defaultTable={}",
          secretTable.map(PriceTable::keys).orElse(null),
          defaultPriceTable.keys());
      throw new ConnectorException(
          TokenCostErrorCodes.PRICE_NOT_FOUND,
          "No price for \"" + request.provider() + ":" + request.model()
              + "\" in the GOVERNOR_PRICE_TABLE cluster secret or the bundled default table.");
    }
    LOG.debug(
        "Price found in {}: inputPer1kMicros={} outputPer1kMicros={} currency={}",
        source,
        price.inputPer1kMicros(),
        price.outputPer1kMicros(),
        price.currency());

    long costMicros = Pricer.priceMicros(request.inputTokens(), request.outputTokens(), price);
    TokenCostResult result =
        new TokenCostResult(
            request.provider(),
            request.model(),
            resolveAgent(request.agentName(), context),
            request.inputTokens(),
            request.outputTokens(),
            request.inputTokens() + request.outputTokens(),
            costMicros,
            costMicros / 1_000_000.0,
            price.currency(),
            source);

    metrics.record(result);

    LOG.info(
        "Token cost: provider={} model={} agent={} inputTokens={} outputTokens={} totalTokens={} costMicros={} costUsd={} priceSource={} elapsedMs={}",
        result.provider(),
        result.model(),
        result.agent(),
        result.inputTokens(),
        result.outputTokens(),
        result.totalTokens(),
        result.costMicros(),
        BigDecimal.valueOf(result.costMicros(), 6).toPlainString(),
        result.priceSource(),
        (System.nanoTime() - startNanos) / 1_000_000);
    return result;
  }

  /** Describes the secret's state for logs without ever including its content. */
  private static String describeSecret(String secret) {
    if (secret == null) {
      return "null";
    }
    if (secret.isBlank()) {
      return "blank";
    }
    if (secret.contains("{{secrets.")) {
      return "unresolved-placeholder";
    }
    return secret.length() + " chars";
  }

  /** Never null - Prometheus needs the same tag keys on every sample of a meter. */
  private static String resolveAgent(String agent, OutboundConnectorContext context) {
    if (agent != null && !agent.isBlank()) {
      return agent.trim();
    }
    JobContext job = context.getJobContext();
    if (job != null && job.getBpmnProcessId() != null && job.getElementId() != null) {
      LOG.debug("No agent name set - defaulting to {}:{}", job.getBpmnProcessId(), job.getElementId());
      return job.getBpmnProcessId() + ":" + job.getElementId();
    }
    LOG.debug("No agent name set and no job context ids available - defaulting to 'unspecified'");
    return "unspecified";
  }
}
