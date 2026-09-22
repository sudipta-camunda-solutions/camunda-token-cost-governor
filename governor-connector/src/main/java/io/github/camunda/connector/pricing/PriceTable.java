package io.github.camunda.connector.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A provider:model -&gt; price lookup, built from a plain JSON array of rows (see {@link Row}).
 * Used both for the bundled classpath fallback table and for whatever a caller parses out of the
 * {@code GOVERNOR_PRICE_TABLE} cluster secret - same shape either way, so one parser suffices.
 *
 * <p>Prices in the JSON are USD per 1,000,000 tokens (matching how providers publish pricing
 * today), converted here to {@link PriceEntry}'s internal per-1,000-tokens-in-micros convention
 * with a single multiply - {@code pricePerMillionUsd * 1000} micros per 1k tokens.
 */
public final class PriceTable {

  private static final String DEFAULT_TABLE_RESOURCE = "/default-price-table.json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, PriceEntry> entriesByKey;

  private PriceTable(Map<String, PriceEntry> entriesByKey) {
    this.entriesByKey = entriesByKey;
  }

  /**
   * Loads the small, illustrative, bundled default table shipped on the classpath. Its prices
   * are placeholders, not verified against live provider pricing - see the README for how to
   * override them via the {@code GOVERNOR_PRICE_TABLE} cluster secret.
   */
  public static PriceTable loadDefault() {
    try (InputStream in = PriceTable.class.getResourceAsStream(DEFAULT_TABLE_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Bundled default price table resource not found: " + DEFAULT_TABLE_RESOURCE);
      }
      return parse(in.readAllBytes());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load bundled default price table", e);
    }
  }

  /**
   * Parses the {@code GOVERNOR_PRICE_TABLE} cluster secret's resolved value. Returns empty (not
   * an error) when the secret is unset - Camunda leaves an unresolved {@code {{secrets.NAME}}}
   * placeholder literal rather than substituting an empty string, so that pattern (as well as a
   * genuinely blank value) is treated as "not configured," letting the caller fall back to the
   * bundled default table. A malformed value that isn't blank and isn't an unresolved placeholder
   * is a real configuration error and is returned as empty too, since this method has no BPMN
   * error context of its own to throw through - callers that want to distinguish "not configured"
   * from "misconfigured" should log accordingly.
   */
  public static Optional<PriceTable> parseSecret(String secretValue) {
    if (secretValue == null || secretValue.isBlank() || secretValue.contains("{{secrets.")) {
      return Optional.empty();
    }
    try {
      return Optional.of(parse(secretValue.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static PriceTable parse(byte[] json) throws IOException {
    Row[] rows = MAPPER.readValue(json, Row[].class);
    Map<String, PriceEntry> byKey = new ConcurrentHashMap<>();
    for (Row row : rows) {
      PriceEntry entry = row.toPriceEntry();
      byKey.put(entry.key(), entry);
    }
    return new PriceTable(byKey);
  }

  public Optional<PriceEntry> lookup(String provider, String model) {
    return Optional.ofNullable(entriesByKey.get(PriceEntry.key(provider, model)));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Row(
      String provider, String model, double inputPricePerMillionUsd, double outputPricePerMillionUsd, String currency) {

    PriceEntry toPriceEntry() {
      return new PriceEntry(
          provider,
          model,
          Math.round(inputPricePerMillionUsd * 1000),
          Math.round(outputPricePerMillionUsd * 1000),
          currency == null ? "USD" : currency,
          Instant.now());
    }
  }
}
