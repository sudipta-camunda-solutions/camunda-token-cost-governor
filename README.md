# Token Cost Reporter — Camunda 8 Agentic AI

Computes the token count and USD cost of every LLM call Camunda 8.10+'s native **AI Agent Task**
(`io.camunda.agenticai:aiagent:task:2`) and **AI Agent Sub-process**
(`io.camunda.agenticai:aiagent:subprocess:2`) elements make — without a database, without
intercepting or proxying anything. A single, self-contained BPMN connector: no network calls of
its own, no server to run, no secrets required beyond an optional price table.

It makes cost transparent so a process owner can compare it against whatever they already know a
manual/human version of the same task costs — that comparison itself is intentionally outside
this connector's scope; it reports numbers, not conclusions.

## Contents

- [How it works](#how-it-works)
- [Quick start](#quick-start)
- [Configuring the price table (GOVERNOR_PRICE_TABLE secret)](#configuring-the-price-table-governor_price_table-secret)
- [Configuring the connector in Modeler](#configuring-the-connector-in-modeler)
- [BPMN error codes](#bpmn-error-codes)
- [Prometheus metrics](#prometheus-metrics)
- [Docker deployment](#docker-deployment)
- [Testing](#testing)
- [Version compatibility](#version-compatibility)
- [Honest limitations](#honest-limitations)

## How it works

Camunda's native AI Agent Task/Sub-process elements already report token usage as part of their
own result (see [Honest limitations](#honest-limitations) for the exact shape and its gaps). This
connector reads whatever input/output token counts the process already has — most naturally via
FEEL from that result — looks up a price for the model, and returns the computed cost:

```
AI Agent Task/Sub-process  ──►  process variable (agentResult.tokenUsage...)  ──►  Token Cost Reporter  ──►  tokenCostResult
```

No interception layer, no reverse proxy, no gateway sitting between the AI Agent element and the
real provider — the connector runs as an ordinary BPMN service task wherever you place it,
typically right after the AI Agent element.

Price data (what a provider charges per model) is the one piece of information the connector can't
derive on its own. It comes from, in priority order:

1. The `GOVERNOR_PRICE_TABLE` Camunda cluster secret — a JSON array of price rows, configured
   once for your whole cluster, so every developer's element just works without typing prices
   into Modeler. See [Configuring the price table](#configuring-the-price-table-governor_price_table-secret).
2. A small bundled default table (a handful of illustrative, **unverified** prices for common
   models) used for any model the secret's table doesn't cover, or when the secret is left blank.

If neither has a price for the requested `provider:model`, the connector raises a BPMN error
(`PRICE_NOT_FOUND`) rather than silently reporting a wrong or zero cost.

## Quick start

```bash
mvn package   # builds governor-connector/target/governor-connector-<version>-with-dependencies.jar
```

Mount that jar into `camunda/connectors-bundle` via `LOADER_PATH` (see
[Docker deployment](#docker-deployment)), or reference it from your own connector runtime. Import
[`element-templates/token-cost-governor.json`](element-templates/token-cost-governor.json) into
Modeler, drag the "Token Cost Reporter" element into a process after your AI Agent Task/Sub-process,
and wire its four visible fields (see
[Configuring the connector in Modeler](#configuring-the-connector-in-modeler)).

The connector works out of the box against the bundled default price table with **no secret
configured at all** for a handful of well-known models — but create the `GOVERNOR_PRICE_TABLE`
cluster secret before relying on this for real cost tracking; the bundled table's prices are
illustrative placeholders, not verified against live provider pricing.

## Configuring the price table (`GOVERNOR_PRICE_TABLE` secret)

In Camunda Console (SaaS) or your self-managed cluster's secret store, create a cluster secret
named `GOVERNOR_PRICE_TABLE` with a JSON array value, one row per model:

```json
[
  { "provider": "anthropic", "model": "claude-sonnet-4-5", "inputPricePerMillionUsd": 3.00, "outputPricePerMillionUsd": 15.00, "currency": "USD" },
  { "provider": "openai", "model": "gpt-5", "inputPricePerMillionUsd": 1.25, "outputPricePerMillionUsd": 10.00, "currency": "USD" }
]
```

Prices are **USD per 1,000,000 tokens** — copy them straight off a provider's pricing page. Update
this one secret whenever pricing changes or a new model needs tracking; no connector redeploy, no
per-element edits.

Two behaviors worth knowing:

- A secret that's never been **created** at all makes the job fail outright (this is how Camunda's
  own secret resolution works, not a bug in this connector) — create it, even with an empty JSON
  array `[]`, so the connector can fall back to the bundled default table gracefully.
- A secret that exists but is left blank, or whose table doesn't cover a particular model, falls
  back to the bundled default table per-model, not all-or-nothing.

## Configuring the connector in Modeler

One operation, four visible fields (`element-templates/token-cost-governor.json` — copy it into
your Modeler's element template directory, or upload it via Web Modeler):

| Field | Group | Typical value |
|---|---|---|
| Provider | Model | `=agentProvider`, or a literal like `anthropic` |
| Model | Model | `=agentModel` |
| Input tokens | Token usage | `=agentResult.tokenUsage.inputTokens` |
| Output tokens | Token usage | `=agentResult.tokenUsage.outputTokens` |

A fifth field (the price table) is present in the generated template but never shown in Modeler —
it's a `Hidden` property always resolved from the `GOVERNOR_PRICE_TABLE` cluster secret, exactly
like the field types above it are the only ones you'll ever see or edit.

Result variable defaults to `tokenCostResult`:

```json
{
  "provider": "anthropic", "model": "claude-sonnet-4-5",
  "inputTokens": 1200, "outputTokens": 340, "totalTokens": 1540,
  "costMicros": 8700, "costUsd": 0.0087, "currency": "USD",
  "priceSource": "CLUSTER_SECRET"
}
```

See [`bpmn/ai-agent-task-cost-tracking.bpmn`](bpmn/ai-agent-task-cost-tracking.bpmn) for a
complete, tested example wiring the connector into an explicit AI Agent Task loop with an
accumulated `totalCostMicros`, and
[`bpmn/ai-agent-subprocess-cost-tracking.bpmn`](bpmn/ai-agent-subprocess-cost-tracking.bpmn) for
the AI Agent Sub-process pattern — one connector call after the sub-process's own completion,
reading its terminal aggregate result (no per-call interception needed, since this connector only
reports, it doesn't gate).

## BPMN error codes

| Code | Retryable | Meaning |
|---|---|---|
| `PRICE_NOT_FOUND` | no | No price for the requested `provider:model` in the cluster secret or the bundled default table |

Non-retryable because this connector does no I/O and holds no state — every failure it can raise
is a deterministic configuration problem (an unpriced model) that would fail identically on retry.
Attach a boundary error event if you want a fallback path (see the demo BPMN's optional
illustrative branch); otherwise it surfaces as an ordinary Zeebe incident.

## Prometheus metrics

Every execution records four Micrometer counters, tagged by `provider`/`model`:

| Metric | What |
|---|---|
| `governor_calls_total` | Number of token-cost calls |
| `governor_tokens_input_total` | Cumulative input tokens |
| `governor_tokens_output_total` | Cumulative output tokens |
| `governor_cost_usd_total` | Cumulative USD cost |

These surface automatically on whatever Spring Boot Actuator endpoint the hosting
`connectors-bundle` already exposes — `/actuator/prometheus` — with no configuration beyond
mounting the connector's jar (it shades in `micrometer-registry-prometheus`, which is what makes
Spring Boot auto-configure that endpoint; see the module's `pom.xml` comments for why
`micrometer-core` itself is deliberately *not* shaded in, to avoid a duplicate copy on
`connectors-bundle`'s own classpath). Point Prometheus at that endpoint, and Grafana at Prometheus,
for spend charts/trends across every process instance and model.

`tokenCostResult` is also an ordinary process variable, so Camunda Operate can show it per
instance, and Optimize can report on it, without any of the above.

## Docker deployment

```bash
cp docker/.env.example docker/.env   # fill in your Camunda cluster connection, never commit
mvn package
docker compose -f docker/docker-compose.yml up --build
```

Brings up a single `connectors-bundle` instance with `governor-connector` mounted via
`LOADER_PATH` (the documented custom-connector hosting pattern — see
`docker/Dockerfile.connector`). The connector needs no secrets of its own to run beyond the
optional `GOVERNOR_PRICE_TABLE` cluster secret, which is configured in Camunda, not in `.env`. See
`docker/docker-compose.yml`'s own comments for the Camunda SaaS-vs-self-managed connection
options.

## Testing

| Layer | What |
|---|---|
| Unit | `PricerTest` (fixed-point rounding math), `GovernorConnectorTest` (cluster-secret vs. default-table priority, `PRICE_NOT_FOUND`, Prometheus counters) |
| Full-process (Testcontainers Zeebe, `camunda-process-test-java`) | `GovernorProcessTest` — deploys `ai-agent-task-cost-tracking.bpmn`, mocks the connector's own job type, asserts the loop-back gateway, iteration counter, accumulated cost, and the `PRICE_NOT_FOUND` boundary-event branch all fire correctly |

```bash
mvn test                                        # unit tests only
mvn failsafe:integration-test failsafe:verify   # full-process test (needs Docker, for Testcontainers Zeebe)
```

## Version compatibility

Pinned to `io.camunda.connector:connector-core:8.9.12` (the latest GA co-published across
`connector-core`/`connector-validation`/`element-template-generator`/`connector-runtime-test` on
Maven Central as of 2026-09-22 — no stable `8.10.0` exists yet, only `8.10.0-alphaN`). This does
**not** block targeting an 8.10+ cluster: `governor-connector` never compiles against the
agentic-ai connector's own classes, so the SDK version it builds with and the Camunda platform
version it's deployed against are independent. The job type strings
(`io.camunda.agenticai:aiagent:task:2` / `:subprocess:2`) have changed every Camunda release so
far — verify them against your own cluster's actual deployed element template before relying on
this project's demo BPMN files unmodified.

## Honest limitations

- **Every provider works identically now.** Unlike an interception-based design, there is no
  provider-specific gap here (the previous gateway-based design of this project couldn't intercept
  Gemini at all, since its element template has no custom-endpoint option) — this connector never
  needs to intercept anything, so that limitation no longer applies to anyone.
- **Token accounting is exact for what Camunda forwards, not necessarily for what the provider
  bills — and this connector has no fallback for the gap.** Camunda's agentic-ai connector
  currently forwards only input/output token counts, not reasoning-tokens or
  cache-creation/read-tokens (tracked upstream as `camunda/connectors#8605`). A previous version of
  this project ran its own reverse-proxy that read token counts straight from the real provider
  response, sidestepping this gap; without that proxy, this connector is now fully dependent on
  whatever `agentResult.tokenUsage` (or equivalent) the AI Agent element itself reports, with
  nothing to cross-check it against.
- **The bundled default price table is illustrative, not verified against live provider pricing.**
  It exists for a zero-config quick start with a handful of well-known models — see
  [`governor-connector/src/main/resources/default-price-table.json`](governor-connector/src/main/resources/default-price-table.json).
  Configure the `GOVERNOR_PRICE_TABLE` cluster secret with real, current prices before relying on
  this for actual cost tracking.
- **No multi-currency support in the price-override path.** Every price table row (secret or
  bundled) carries its own `currency` field and that value passes straight through to
  `tokenCostResult.currency`, but the connector does no currency conversion — mixing currencies
  across models in the same process leaves any aggregation across them up to whoever consumes
  `tokenCostResult`.
- **No human-baseline/savings comparison.** This connector computes cost; it does not know what a
  manual/human version of the same task would have cost, and doesn't try to guess. Compare
  `tokenCostResult.costUsd` against whatever baseline you already know, downstream, in your own
  FEEL expression or reporting.
