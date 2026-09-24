# Token Cost Reporter — Camunda 8 Agentic AI

Computes the token count and USD cost of every LLM call Camunda's native **AI Agent Task** and
**AI Agent Sub-process** elements make — without a database, without intercepting or proxying
anything. A single, self-contained BPMN connector: no network calls of its own, no server to run,
no secrets required beyond an optional price table.

(Job type strings for these native elements change between Camunda releases, including the naming
pattern itself — `io.camunda.agenticai:aiagent:1` on a live Camunda 8.9 SaaS cluster as of this
writing, not `io.camunda.agenticai:aiagent:task:2` as earlier drafts of this project assumed
without checking; see [Version compatibility](#version-compatibility).)

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
- [Reporting in Camunda Optimize](#reporting-in-camunda-optimize)
- [Logging](#logging)
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
AI Agent Task  ──►  process variable agent.context.metrics.tokenUsage.*  ──►  Token Cost Reporter  ──►  tokenCostResult
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
and wire its four required fields plus the optional agent name (see
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

One operation, four required fields plus one optional (`element-templates/token-cost-governor.json`
— copy it into your Modeler's element template directory, or upload it via Web Modeler):

| Field | Group | Typical value |
|---|---|---|
| Provider | Model | `=agentProvider`, or a literal like `anthropic` |
| Model | Model | `=agentModel` |
| Input tokens | Token usage | `=agent.context.metrics.tokenUsage.inputTokenCount` |
| Output tokens | Token usage | `=agent.context.metrics.tokenUsage.outputTokenCount` |
| Agent name (optional) | Agent | A literal like `claims-triage` — see below (the underlying input is called `agentName`) |

**Agent name** identifies which agent a call belongs to, so token count and cost can be broken
down per agent (chargeback/showback by agent role). Leave it blank and it defaults to
`<processId>:<elementId>` of the Token cost task, or `unspecified` if that isn't available. It
is used as a Prometheus label, so keep it to a **small, stable set of names** — never bind it to
something per-instance or per-customer (e.g. `=customerId`), or every distinct value becomes a
permanent new time series and Prometheus's storage grows without bound.

The Agent name field arrived in template version 3 and its input was renamed `agent` → `agentName`
in version 5 (see the note below on why). Existing elements built from an older version need
the template updated/re-applied in Modeler to show it — and re-applying regenerates the task's
input/output mapping, which deletes the demo BPMN's hand-added `business_totalCostMicros`/`business_totalCostUsd`
accumulator outputs (see that file's top comment), so re-add those afterward.

The FEEL paths above are the real AI Agent Task result shape (`agent` being whatever you named the
result variable), read from a real process instance on a live cluster (AI Agent element template
v7): token usage sits at `agent.context.metrics.tokenUsage`, next to `modelCalls`. It does **not**
need "Include assistant message". Earlier versions of this README documented
`agent.responseMessage.metadata.framework.tokenUsage` — that path is not present on this result, and
using it makes `inputTokens`/`outputTokens` resolve to null, failing validation with
`inputTokens: must not be null`. If your AI Agent template version exposes usage somewhere else,
check the `agent` variable in Operate and adjust the two paths.

**Don't name any other input `agent`.** The AI Agent Task's default result variable is `agent`, and
Zeebe applies input mappings in order, each visible to the next — an input called `agent` shadows the
result variable, so the token paths that follow it resolve to null. That is why the connector's field
is `agentName`, and it applies equally if you rename your own variables. This is exactly what
[`bpmn/ai-agent-task-cost-tracking.bpmn`](bpmn/ai-agent-task-cost-tracking.bpmn)
wires up against a real AI Agent Task element (not a placeholder) — see its own top comment for
the full detail, including an operational gotcha around re-applying this connector's element
template in Modeler.

One more field (the price table) is present in the generated template but never shown in Modeler —
it's a `Hidden` property always resolved from the `GOVERNOR_PRICE_TABLE` cluster secret, exactly
like the field types above it are the only ones you'll ever see or edit.

Result variable defaults to `tokenCostResult`:

```json
{
  "provider": "anthropic", "model": "claude-sonnet-4-5", "agent": "claims-triage",
  "inputTokens": 1200, "outputTokens": 340, "totalTokens": 1540,
  "costMicros": 8700, "costUsd": 0.0087, "currency": "USD",
  "priceSource": "CLUSTER_SECRET"
}
```

`bpmn/ai-agent-task-cost-tracking.bpmn` wires a **real** AI Agent Task element end to end
(tested against a real Testcontainers-backed Zeebe engine, with the AI Agent Task's job mocked
rather than calling a real LLM). `bpmn/ai-agent-subprocess-cost-tracking.bpmn` uses two **real** AI
Agent Sub-process elements (ad-hoc sub-processes) that behave differently on purpose: **Research Analyst (big
context)** has a long prompt and brief, a context window of 100 and makes several model calls, so it uses many
tokens; **Quick Answer (small context)** has a one-line prompt, a window of 4 and one model call. Each is followed by
its own Token cost task (agent names `research-analyst` and `quick-answer`). It is tested the same way (both job types
mocked, the token counts are fixtures: 12,850/940 against 96/42; the test also runs the ad-hoc loop and checks what
each call receives). Its token usage
is at `agent.context.metrics.tokenUsage`, which only exists because the sub-processes set **Include agent
context**; each contains a tool script task because Zeebe refuses an ad-hoc sub-process with no activity. Neither
demo has been run against a live cluster. Both demo files accumulate the same flat, top-level
process variables — see [Reporting in Camunda Optimize](#reporting-in-camunda-optimize)
for why that shape matters.

## BPMN error codes

| Code | Retryable | Meaning |
|---|---|---|
| `PRICE_NOT_FOUND` | no | No price for the requested `provider:model` in the cluster secret or the bundled default table |

Non-retryable because this connector does no I/O and holds no state — every failure it can raise
is a deterministic configuration problem (an unpriced model) that would fail identically on retry.
Attach a boundary error event if you want a fallback path (see the demo BPMN's optional
illustrative branch); otherwise it surfaces as an ordinary Zeebe incident.

## Prometheus metrics

Every execution records four Micrometer counters, tagged by `provider`/`model`/`agent` (the
`agent` tag is always present — Prometheus requires the same tag keys on every sample of a metric —
falling back to `<processId>:<elementId>`, then `unspecified`):

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
for spend charts/trends across every process instance, model and agent — for example spend per
agent is `sum by (agent) (governor_cost_usd_total)`, and average input tokens per call for one
agent is `governor_tokens_input_total{agent="claims-triage"} / governor_calls_total{agent="claims-triage"}`.

`tokenCostResult` is also an ordinary process variable, so Camunda Operate can show it per
instance, and Optimize can report on it, without any of the above.

## Reporting in Camunda Optimize

Prometheus/Grafana (above) is for real-time charts and for cost over time. For business-facing reports
— "what did this instance cost," "how much this month" — Optimize is the better tool, fed by the demo BPMN
files' `business_totalCostMicros`, `business_totalCostUsd` and `business_costAgent` variables. The Word manual
(Section 9) has the full walkthrough; this is the short version.

**Two things decide whether Optimize can see the cost at all**

1. **The variable names must start with `business_`.** New Camunda 8.10 SaaS clusters export only
   `business_`-prefixed variables to Optimize (8.10 release notes); everything else is permanently excluded and
   never back-filled. Symptom: the **Variable** option in the report builder is blocked ("Not seeing a process or
   variable?"). The alternative is to change the variable filter in the cluster's settings in Console. Only
   instances started afterwards carry the variables.
2. **The Token cost task keeps these output mappings** (re-applying the element template in Web Modeler removes
   them, so re-add them): `tokenCostResult` = `=tokenCostResult`; `business_totalCostMicros` =
   `=business_totalCostMicros + tokenCostResult.costMicros`; `business_totalCostUsd` =
   `=business_totalCostUsd + tokenCostResult.costUsd`; `business_costAgent` = `=tokenCostResult.agent`. The Start
   event sets the two totals to 0. `business_costAgent` is a plain string because Optimize does not flatten object
   variables such as `tokenCostResult` on SaaS.

**What Optimize can and cannot do.** Its Variable view returns one number (sum, average, minimum or maximum of a
numeric variable over the filtered instances); its Group by can only be None, per Optimize's own list of valid
report combinations. So there is no bar chart of cost per month or per agent in Optimize — use Grafana on
`governor_cost_usd_total` for that. Optimize does well at totals and averages for a slice (this month, this year,
one agent) and a per-instance table that exports to CSV.

**Import the ready-made dashboard.** [`optimize/ai-cost-dashboard.json`](optimize/ai-cost-dashboard.json) holds five
reports and a dashboard, `AI cost`:

| # | Report | Shows |
|---|---|---|
| 1 | Cost of each process instance | Raw data table of completed instances: ID, start, end, `business_totalCostUsd`, `business_totalCostMicros`, `business_costAgent` |
| 2 | Agent-wise cost (USD) | Number, sum of `business_totalCostUsd`; add an agent filter to the dashboard once data exists to select one agent |
| 3 | Total spend this month (USD) | Number, sum, instances started this calendar month |
| 4 | Total spend this year (USD) | Number, sum, instances started this calendar year |
| 5 | Average cost per case (USD) | Number, average; the human-flow target is off until you set it |

1. In Optimize create a collection (`AI cost`) and add both demo processes (`ai-agent-task-cost-tracking` and
   `ai-agent-subprocess-cost-tracking`) as data sources, all versions. The first process in the file needs at least
   one instance in Optimize, and every process in the file must be a data source of the collection.
2. Open the collection, **Create New** → **Import JSON**, pick the file. Camunda's documentation says only Optimize
   superusers can import and export.
3. Open the dashboard. To compare with a human-handled case, open Report 5, switch on the progress bar in its
   configuration and set the target to your cost per human-handled case.
4. The file has no agent filter: Optimize only accepts a dashboard variable filter for a variable that already exists in
   its data. After the first instance with `business_costAgent` arrives, open the dashboard, **Edit**, add a **Variable**
   filter on `business_costAgent`, and save.
5. If the import is refused, the message names the cause. "Requires definitions that don't exist" means no definition
   matched: the file uses tenant `<default>` (where Optimize stores every Camunda 8 definition) and the process key
   `ai-agent-task-cost-tracking`, so the process must have at least one instance imported into Optimize and its BPMN
   process ID must equal that key (otherwise change the key and name in the file). Other causes: a schema version mismatch (the file uses reports 11 and dashboard 8, the values of
   8.10.0-alpha5; read `sourceIndexVersion` from an export of your own Optimize), or permissions. The same file can
   be posted to `/api/public/import?collectionId=<id>` on `https://<region>.optimize.camunda.io/<cluster-id>`.

**Which processes it covers.** Every report in the file lists both demo processes as data sources, so the numbers add
up over both, and the table shows the process ID per row. For your own processes run
`python optimize/generate-dashboard.py --process my-agent-process` (repeat `--process` for several; `--process
ID="Display name"`, `--versions latest`, `-o file` are also available), or add a process to an already-imported report
in the report builder (**Add** next to the data source). Optimize has no "all processes" data source, and a process
instance cannot be a data source: a report reads all instances of the listed processes, and you narrow it with
filters (state, dates, `business_costAgent`). Importing the same file twice creates a second copy.

The file was built from Optimize's own source at 8.10.0-alpha5. It has not yet been imported into a live Optimize,
and no cost data exists yet on the test cluster.

## Logging

The connector logs under `io.github.camunda.connector` and never logs the price-table secret's text
(only its state — blank, unresolved placeholder, or a character count — and row counts).

- **INFO** (default): one startup line, and one trace line per call:
  `Token cost: provider=anthropic model=claude-haiku-4-5 agent=task-agent inputTokens=100 outputTokens=50 totalTokens=150 costMicros=350 costUsd=0.000350 priceSource=DEFAULT_TABLE elapsedMs=3`
- **DEBUG**: also the inputs received, whether the price-table secret was blank/unresolved/parsed,
  which table matched and the rates used, and the counters recorded.
- **WARN**: a `GOVERNOR_PRICE_TABLE` that isn't valid JSON (previously a silent fall-back to the
  default table), a model with no price (with both tables' row counts), and a Micrometer registry
  that isn't bound (so `governor_*` would never reach `/actuator/prometheus`).
- **ERROR**: any unexpected failure, including a Console secret that can't be read.

Set the level with `GOVERNOR_LOG_LEVEL=DEBUG` in `docker/.env`, or the environment variable
`LOGGING_LEVEL_IO_GITHUB_CAMUNDA_CONNECTOR`. It can also be switched live, without a restart:
`curl -X POST -H "Content-Type: application/json" -d '{"configuredLevel":"DEBUG"}' http://localhost:8080/actuator/loggers/io.github.camunda.connector`.
See `DEPLOYMENT-GUIDE.docx`, Section 12.4, for reading the lines.

## Docker deployment

```bash
cp docker/.env.example docker/.env   # fill in your Camunda cluster connection, never commit
mvn package
docker compose -f docker/docker-compose.yml up --build
```

Brings up a single `connectors-bundle` instance with `governor-connector` mounted via
`LOADER_PATH` (the documented custom-connector hosting pattern — see
`docker/Dockerfile.connector`). The runtime connects **out** to your Camunda SaaS cluster and polls
for jobs — SaaS never calls in, and custom connectors cannot be uploaded to SaaS's own runtime.

The connector needs no secrets of its own beyond the `GOVERNOR_PRICE_TABLE` cluster secret. A
runtime you host yourself only sees Console cluster secrets if `docker/docker-compose.yml`'s
`CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED` is `true` (it is) **and** the API client in
`.env` was created with the **Secrets** scope, in addition to the scope that lets it read and
complete jobs. This comes from Camunda's hybrid-mode documentation and was confirmed on a real
cluster: with a client lacking the Secrets scope, the runtime's token request for audience
`secrets.camunda.io` is refused with HTTP 401 and the job fails (the connector's ERROR log then
carries a `HINT:` line saying so). Resolving a secret with a correctly scoped client has not been
observed yet.

If the runtime logs `Failed with code 404` when fetching a process definition (health shows
`processDefinitionImport` DOWN), your cluster serves its REST API at a different address than the
runtime's built-in default — observed on a cluster reporting 8.10.0-alpha5, where
`https://<region>.api.camunda.io/<cluster-id>` worked and the default `.zeebe.camunda.io` address
returned 404. Set `CAMUNDA_CLIENT_REST_ADDRESS` in `docker/.env` (see `docker/.env.example`).

For step-by-step instructions for every platform — local without Docker, Docker Desktop,
Kubernetes, AWS (ECS Fargate, EKS) and Azure (Container Apps, AKS) — see `DEPLOYMENT-GUIDE.docx`.

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
version it's deployed against are independent. The job type strings change between Camunda
releases — not just their version suffix, but the naming pattern itself: the real AI Agent Task
job type observed against a live Camunda 8.9 SaaS cluster (element template version 7) was
`io.camunda.agenticai:aiagent:1`, not `io.camunda.agenticai:aiagent:task:2` as earlier drafts of
this project assumed without checking a real deployment (`ai-agent-task-cost-tracking.bpmn` now
uses the verified real job type). Always verify against your own cluster's actual deployed element
template rather than trusting any job type string written down here.

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
  whatever the AI Agent Task's own result reports, with nothing to cross-check it against.
- **Token usage location depends on the AI Agent element's template version.** On the version this
  project was verified against (v7, openaiCompatible provider) it is at
  `<resultVariable>.context.metrics.tokenUsage.{inputTokenCount, outputTokenCount}`. Other template
  versions or providers may report it elsewhere, and a wrong path shows up as `inputTokens: must not
  be null` on the Token cost job — look at the `agent` variable in Operate to find the real path.
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
