package io.github.camunda.connector.model;

/**
 * Result of one token-cost computation. {@code costMicros} is the authoritative fixed-point
 * figure (integer micro-dollars); {@code costUsd} is a derived, display-only convenience for
 * Tasklist/logs - never accumulate or compare on it instead of {@code costMicros}.
 */
public record TokenCostResult(
    String provider,
    String model,
    long inputTokens,
    long outputTokens,
    long totalTokens,
    long costMicros,
    double costUsd,
    String currency,
    PriceSource priceSource) {}
