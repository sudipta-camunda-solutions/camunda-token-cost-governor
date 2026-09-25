# Token Cost Reporter: connector, Camunda SaaS and secrets, end to end

How the self-hosted connector runtime, Camunda SaaS and the secrets fit together, from the one-time setup to the
Optimize dashboard. Read it left to right: your side on the left, Camunda SaaS in the middle, the LLM provider on the
right. Every connection is opened from your side. The runtime polls Zeebe and asks the Console for secrets; SaaS never
calls in.

The same diagram as an image, for documents: [sequence-diagram.svg](sequence-diagram.svg). Arrows 1-4, 7-8, 12, 18-20 carry a
credential or a secret.

For a user manual there is a one-page A4 version of the main process, the cluster secret from setup to one Token cost
task: [sequence-diagram-a4.svg](sequence-diagram-a4.svg) (sharp, for Word 2016 and later) and
[sequence-diagram-a4.png](sequence-diagram-a4.png) (about 300 dpi, for any Word version).

```mermaid
sequenceDiagram
    autonumber
    box Your side
        actor dev as Developer<br/>or admin
        participant prom as Prometheus<br/>and Grafana
        participant gov as GovernorConnector<br/>governor-connector.jar
        participant rt as connectors-bundle<br/>self-hosted runtime
    end
    box Camunda SaaS
        participant con as Camunda Console<br/>OAuth and Secrets API
        participant zb as SaaS cluster<br/>Zeebe
        participant optimize as Optimize<br/>and Operate
        participant saas as SaaS-hosted runtime<br/>AI Agent job worker
    end
    participant llm as LLM provider
    rect rgba(148, 163, 184, 0.16)
        Note over dev,llm: 1. SETUP - credentials and secrets, done once
        dev->>con: create the cluster secret GOVERNOR_PRICE_TABLE (a JSON array, [] at the least)
        dev->>con: create an API client with the scopes Orchestration Cluster API and Secrets
        con-->>dev: client id and client secret
        dev->>rt: write cluster id, region, client id and client secret into docker/.env
        Note over rt: Environment variables only. They never appear in the BPMN or the repo.
        dev->>zb: deploy the BPMN from Web Modeler (Token Cost Reporter template v6)
        Note over zb: The BPMN holds only the placeholder {{secrets.GOVERNOR_PRICE_TABLE}}, never the value.
    end
    rect rgba(148, 163, 184, 0.16)
        Note over dev,llm: 2. START-UP - the runtime connects out, SaaS never calls in
        rt->>gov: load governor-connector.jar from LOADER_PATH (SPI)
        rt->>con: OAuth client-credentials token request for the cluster API
        con-->>rt: access token (JWT)
        rt->>zb: open the job stream for io.github.camunda:token-cost-governor:1, fetch process definitions over REST on port 443
        Note over rt,zb: REST address: REGION.zeebe.camunda.io/CLUSTER_ID, or REGION.api.camunda.io/CLUSTER_ID on newer clusters. A 404 means the wrong one: set CAMUNDA_CLIENT_REST_ADDRESS.
        Note over rt: AI Agent jobs stay with the SaaS runtime: CAMUNDA_CONNECTOR_AGENTICAI_ENABLED=false.
    end
    rect rgba(148, 163, 184, 0.16)
        Note over dev,llm: 3. PROCESS INSTANCE - one run of the process
        zb->>zb: the instance starts, the Start event sets the totals to 0 and costAgent to empty
        loop for each AI agent: Research Analyst, then Quick Answer
            Note over zb,saas: AI AGENT STEP - runs on the SaaS-hosted runtime
            zb-->>saas: job io.camunda.agenticai:aiagent-job-worker:1 (pushed down the stream)
            saas->>saas: resolve the SaaS-only secrets CAMUNDA_PROVIDED_LLM_API_ENDPOINT and CAMUNDA_PROVIDED_LLM_API_KEY
            saas->>llm: model calls and tool calls (ad-hoc loop)
            llm-->>saas: answers and token usage
            saas->>zb: complete the job: agent.context.metrics.tokenUsage, totals over all model calls
            Note over zb,saas: TOKEN COST STEP - runs on your self-hosted runtime
            zb-->>rt: job io.github.camunda:token-cost-governor:1 with provider, model, agentName, tokens and priceTableSecret = {{secrets.GOVERNOR_PRICE_TABLE}}, still a placeholder
            rt->>gov: execute(context)
            gov->>rt: bindVariables(TokenCostRequest): the runtime resolves the secret now
            alt the Secrets scope and the secret are in place
                rt->>con: token request for the audience secrets.camunda.io, then read GOVERNOR_PRICE_TABLE
                con-->>rt: the secret value: the JSON price table
                Note over rt,con: Not yet observed on a live cluster with a correctly scoped client (README, Docker deployment).
            else the API client has no Secrets scope
                con-->>rt: HTTP 401 for the audience secrets.camunda.io: the job fails, the connector logs a HINT
            else the secret was never created
                con-->>rt: secret not available: the job fails (create it, even as [])
            end
            rt-->>gov: the request, with the secret substituted
            gov->>gov: look up provider:model in the secret's table, else in the bundled default table, price it in whole micro-USD, count it in Micrometer, log it
            alt a price was found
                gov-->>rt: TokenCostResult
            else no price in either table
                gov-->>rt: BPMN error PRICE_NOT_FOUND (a boundary event in the process)
            end
            rt->>zb: complete the job with tokenCostResult (or throw the BPMN error)
            zb->>zb: output mappings: the totals, costAgent, this agent's cost, and its input, output and total tokens
        end
    end
    rect rgba(148, 163, 184, 0.16)
        Note over dev,llm: 4. REPORTING - after the instance completes
        zb->>optimize: records are exported, Optimize imports only the business_ variables (8.10 SaaS)
        dev->>optimize: import the generated dashboard JSON into a collection
        prom->>rt: scrape :8080/actuator/prometheus (the governor_ counters)
        rt-->>prom: counters per provider, model and agent
    end
```

## Which credential goes where

| Credential | Where it lives | Who reads it | What it is for |
|---|---|---|---|
| API client id and secret | `docker/.env`, passed to the runtime as `CAMUNDA_CLIENT_ID` and `CAMUNDA_CLIENT_SECRET` | the self-hosted runtime only | OAuth tokens: one for the cluster API (jobs), and with the Secrets scope one for `secrets.camunda.io` |
| `GOVERNOR_PRICE_TABLE` cluster secret | Camunda Console, referenced in the BPMN as `{{secrets.GOVERNOR_PRICE_TABLE}}` | the runtime, when the connector binds its variables. The connector never sees Console credentials | the price table (a JSON array). A row here beats the bundled default table for that model |
| `CAMUNDA_PROVIDED_LLM_API_ENDPOINT` and `CAMUNDA_PROVIDED_LLM_API_KEY` | SaaS-only cluster secrets, referenced by the AI Agent elements | the SaaS-hosted runtime only | the AI Agent's LLM calls. Your runtime cannot resolve them, so it does not take AI Agent jobs |

## When it breaks

| What you see | Cause | Fix |
|---|---|---|
| Job fails, the log has a HINT about the audience `secrets.camunda.io`, HTTP 401 | the API client has no Secrets scope | create the client with the Orchestration Cluster API and Secrets scopes |
| Job fails with `SecretNotAvailableException` | `GOVERNOR_PRICE_TABLE` was never created, or the runtime cannot read Console secrets | create it, even as `[]`, and keep `CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED`=true |
| Failed with code 404 while fetching a process definition, health shows `processDefinitionImport` DOWN | the cluster serves its REST API at another address | set `CAMUNDA_CLIENT_REST_ADDRESS` to `https://REGION.api.camunda.io/CLUSTER_ID` |
| BPMN error `PRICE_NOT_FOUND` | no row for `provider:model` in the secret or in the bundled table | add the model to the `GOVERNOR_PRICE_TABLE` secret |
| An AI Agent job fails with "Must be an HTTP or HTTPS URL" and leaves an incident | the self-hosted runtime took the job and cannot resolve `CAMUNDA_PROVIDED_LLM`_* | keep `CAMUNDA_CONNECTOR_AGENTICAI_ENABLED`=false |

## Where this comes from

`docker/docker-compose.yml`, `docker/.env.example`, the Docker deployment section of the README, the two demo BPMN
files and `GovernorConnector.java`. The success path of a secret read with a correctly scoped client is marked in the
diagram because the README says it has not been observed on a live cluster yet.

Generated by `docs/generate-sequence-diagram.py`. Edit the flow there and run the script; do not edit this file or the
SVG by hand. The A4 page comes from `docs/generate-a4-diagram.py` (`--png` also renders the PNG).
