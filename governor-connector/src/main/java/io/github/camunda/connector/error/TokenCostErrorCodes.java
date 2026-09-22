package io.github.camunda.connector.error;

/**
 * BPMN error code(s) this connector raises via {@code ConnectorException}. Non-retryable: this
 * connector does no I/O and holds no state - every failure it can raise is a deterministic
 * input/configuration problem that would fail identically on retry.
 */
public final class TokenCostErrorCodes {

  /** No price found for the requested provider:model in either the {@code GOVERNOR_PRICE_TABLE}
   *  cluster secret or the bundled default table - "fail loud rather than silently report $0." */
  public static final String PRICE_NOT_FOUND = "PRICE_NOT_FOUND";

  private TokenCostErrorCodes() {}
}
