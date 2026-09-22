package io.github.camunda.connector.model;

/** Where the price used for one {@code TokenCostResult} came from - makes the result self-auditing. */
public enum PriceSource {
  CLUSTER_SECRET,
  DEFAULT_TABLE
}
