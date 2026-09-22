package io.github.camunda.connector.pricing;

/**
 * Prices token counts against a {@link PriceEntry}. Deliberately pure integer arithmetic
 * (round-half-up, long-typed) - never {@code double}/{@code float} - fixed-point (micro-dollars)
 * is exact regardless of magnitude. Token counts and per-1k prices are small enough (well under
 * 2^31) that the intermediate product never approaches {@code long} overflow.
 */
public final class Pricer {

  private Pricer() {}

  public static long priceMicros(long inputTokens, long outputTokens, PriceEntry price) {
    return roundedCost(inputTokens, price.inputPer1kMicros()) + roundedCost(outputTokens, price.outputPer1kMicros());
  }

  private static long roundedCost(long tokens, long per1kMicros) {
    // round-half-up: (tokens * per1kMicros) / 1000, with +500 before truncating division.
    return (tokens * per1kMicros + 500) / 1000;
  }
}
