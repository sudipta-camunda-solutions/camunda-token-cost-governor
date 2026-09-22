package io.github.camunda.connector.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PricerTest {

  private static final PriceEntry CLAUDE_SONNET =
      new PriceEntry("anthropic", "claude-sonnet-5", 3_000_000, 15_000_000, "USD", Instant.EPOCH);

  @Test
  void pricesInputAndOutputTokensSeparately() {
    long costMicros = Pricer.priceMicros(1000, 1000, CLAUDE_SONNET);

    assertThat(costMicros).isEqualTo(3_000_000 + 15_000_000);
  }

  @Test
  void roundsHalfUpRatherThanTruncating() {
    // 1 token at $3.00/1k = 3000 micros/1000 = 3.0 exactly -> no rounding needed here;
    // use a price that doesn't divide evenly to exercise the +500 rounding.
    PriceEntry oddPrice = new PriceEntry("openai", "gpt-5", 1, 0, "USD", Instant.EPOCH);

    // 1 token * 1 micro / 1000 = 0.001 -> rounds down to 0.
    assertThat(Pricer.priceMicros(1, 0, oddPrice)).isZero();
    // 500 tokens * 1 micro = 500; (500 + 500) / 1000 = 1 -> rounds up.
    assertThat(Pricer.priceMicros(500, 0, oddPrice)).isEqualTo(1);
  }

  @Test
  void zeroTokensCostNothing() {
    assertThat(Pricer.priceMicros(0, 0, CLAUDE_SONNET)).isZero();
  }
}
