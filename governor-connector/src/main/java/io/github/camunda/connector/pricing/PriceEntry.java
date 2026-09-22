package io.github.camunda.connector.pricing;

import java.time.Instant;

/**
 * One row of the price table: what a provider charges for one model, per 1,000 tokens. Prices
 * are stored as integer micro-dollars (1 USD = 1,000,000 micros) per 1k tokens, never as a
 * float - see {@link Pricer} for why.
 */
public record PriceEntry(
    String provider,
    String model,
    long inputPer1kMicros,
    long outputPer1kMicros,
    String currency,
    Instant updatedAt) {

  public static String key(String provider, String model) {
    return provider.toLowerCase() + ":" + model.toLowerCase();
  }

  public String key() {
    return key(provider, model);
  }
}
