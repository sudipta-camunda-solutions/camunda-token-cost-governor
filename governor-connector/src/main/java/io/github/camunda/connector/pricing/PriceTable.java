package io.github.camunda.connector.pricing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger LOG = LoggerFactory.getLogger(PriceTable.class);
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
      PriceTable table = parse(in.readAllBytes());
      LOG.debug("Loaded bundled default price table: {} rows", table.size());
      return table;
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
   * error context of its own to throw through - it logs a WARN (never the secret's text) so
   * "misconfigured" is distinguishable from "not configured", which only logs at DEBUG.
   */
  public static Optional<PriceTable> parseSecret(String secretValue) {
    if (secretValue == null) {
      LOG.debug("GOVERNOR_PRICE_TABLE not provided (null) - using the bundled default table");
      return Optional.empty();
    }
    if (secretValue.isBlank()) {
      LOG.debug("GOVERNOR_PRICE_TABLE is blank - using the bundled default table");
      return Optional.empty();
    }
    if (secretValue.contains("{{secrets.")) {
      LOG.debug("GOVERNOR_PRICE_TABLE is still an unresolved {{secrets.*}} placeholder - using the bundled default table");
      return Optional.empty();
    }
    try {
      PriceTable table = parse(secretValue.getBytes(StandardCharsets.UTF_8));
      LOG.debug("Parsed GOVERNOR_PRICE_TABLE: {} rows", table.size());
      return Optional.of(table);
    } catch (IOException e) {
      // Deliberately never log the secret's text or Jackson's source snippet - only the parser's
      // own message (getOriginalMessage excludes the location/source excerpt).
      String reason = e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
      LOG.warn(
          "GOVERNOR_PRICE_TABLE could not be parsed as a JSON array of price rows ({} chars, {}: {}) - "
              + "falling back to the bundled default table",
          secretValue.length(),
          e.getClass().getSimpleName(),
          reason);
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

  public int size() {
    return entriesByKey.size();
  }

  /** Sorted lower-cased {@code provider:model} keys - for diagnostics only. */
  public Set<String> keys() {
    return Collections.unmodifiableSet(new TreeSet<>(entriesByKey.keySet()));
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
