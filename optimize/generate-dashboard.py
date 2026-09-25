#!/usr/bin/env python3
"""Generate the Optimize entity-import file for the "AI cost" dashboard (a cost table, one cost tile per agent, four
summary reports, a token table and the dashboard).

    python optimize/generate-dashboard.py                      # the two demo processes and their agents -> optimize/ai-cost-dashboard.json
    python optimize/generate-dashboard.py --process my-agent-process
    python optimize/generate-dashboard.py --process order-agent="Order agent" --process claims-agent -o my-dashboard.json
    python optimize/generate-dashboard.py --process my-agent-process --agent "Triage=business_triage"

    # the sub-process demo on its own (optimize/ai-cost-dashboard -subprocess.json)
    python optimize/generate-dashboard.py --process ai-agent-subprocess-cost-tracking="AI Agent Sub-process with token/cost tracking" \\
        --agent "Research Analyst=business_researchAnalyst" --agent "Quick Answer=business_quickAnswer" \\
        --name "Subprocess AI cost" -o "optimize/ai-cost-dashboard -subprocess.json"

Every --process becomes one data source (definition) of the total, month, year, average and table reports, so those add
up over all the listed processes. Optimize has no wildcard for "all processes": each one needs its concrete process ID
(the BPMN process id), and every listed process must be a data source of the collection you import into.

--agent LABEL=PREFIX[@PROCESS_ID] describes one agent. The process has to write, for that agent, four variables named
after PREFIX: PREFIX+Cost (its cost in whole micro-USD, 1,000,000 = 1 USD), PREFIX+InputTokens, PREFIX+OutputTokens and
PREFIX+TotalTokens. The agent gets a tile that sums PREFIX+Cost, reading only PROCESS_ID (all listed processes if it is
left out), and its tokens show in the token table. Optimize's Variable view aggregates one variable per report, so an
instance with several agents needs one set of variables per agent. Without --agent and without --process the demo
agents (DEMO_AGENTS) are used; with --process and no --agent there are no agent tiles and no token table.

The JSON layout, the schema versions (reports 11, dashboard 8) and the list of valid report combinations come from
Optimize's own source at git tag 8.10.0-alpha5. If your Optimize refuses the file with a version message, put the
`sourceIndexVersion` numbers of an export from your own Optimize into REPORT_INDEX_VERSION / DASHBOARD_INDEX_VERSION.

The variables the reports read must be exported to Optimize: on new Camunda 8.10 SaaS clusters only variables named
business_* are, which is why the demo processes write business_totalCostUsd (USD), business_totalCost (whole
micro-dollars, 1,000,000 = 1 USD), business_costAgent and the per-agent variables above.
"""
import argparse
import json
import os
import sys
import uuid

REPORT_INDEX_VERSION = 11
DASHBOARD_INDEX_VERSION = 8
NS = uuid.UUID("6b1873fb-1baa-4922-8136-5bb42ddcbc4f")

DEMO_PROCESSES = [
    ("ai-agent-task-cost-tracking", "AI Agent Task with token/cost tracking"),
    ("ai-agent-subprocess-cost-tracking", "AI Agent Sub-process with token/cost tracking"),
]

# (label, variable prefix, process id): the demo processes write <prefix>Cost (whole micro-USD), <prefix>InputTokens,
# <prefix>OutputTokens and <prefix>TotalTokens for each of these agents.
DEMO_AGENTS = [
    ("Research Analyst", "business_researchAnalyst", "ai-agent-subprocess-cost-tracking"),
    ("Quick Answer", "business_quickAnswer", "ai-agent-subprocess-cost-tracking"),
    ("Task agent", "business_taskAgent", "ai-agent-task-cost-tracking"),
]
TOKEN_SUFFIXES = ("InputTokens", "OutputTokens", "TotalTokens")

COST_USD = "business_totalCostUsd"
COST_MICRO_USD = "business_totalCost"
COST_AGENT = "business_costAgent"

# Optimize's type for a whole-number variable. Not confirmed against a live Optimize: the report builder shows the type
# next to the variable name (View, Variable); if it differs, change it here and generate again.
MICRO_USD_TYPE = "Long"

# Variable names the demo processes used in earlier versions. Instances that ran before a rename still carry them, and
# the table (includeNewVariables) would show them as extra columns, some of them printing 7.77E-4 (Zeebe exports a
# decimal below 0.001 in scientific notation). Hiding a name that no instance has is harmless.
LEGACY_VARIABLES = [
    "business_totalCostMicros",
    "business_researchAnalystCostMicros",
    "business_quickAnswerCostMicros",
    "business_researchAnalystCostUsd",
    "business_quickAnswerCostUsd",
]

MAX_AGENT_TILES = 9

# Zeebe's default tenant. Optimize stores every Camunda 8 (SaaS and self-managed) definition under it, so a report
# definition with tenantIds [null] is rejected on import with "definitions that don't exist"
# (DefinitionService.getDefinitionVersions filters on the listed tenants plus null, never on "<default>").
DEFAULT_TENANT = "<default>"


def rid(name):
    """Stable id per report/dashboard name, independent of the process list."""
    return str(uuid.uuid5(NS, name))


def definitions(processes, versions, tenant):
    return [
        {
            "identifier": f"definition-{i}",
            "key": key,
            "name": name,
            "displayName": name,
            "versions": list(versions),
            "tenantIds": [tenant],
        }
        for i, (key, name) in enumerate(processes, start=1)
    ]


def completed_only():
    return {"type": "completedInstancesOnly", "data": None, "filterLevel": "instance", "appliedTo": ["all"]}


def started_in_current(unit):
    # relative filter, start.value 0 = "this <unit>" (the current calendar unit up to now)
    return {
        "type": "instanceStartDate",
        "data": {
            "type": "relative",
            "start": {"value": 0, "unit": unit},
            "end": None,
            "includeUndefined": False,
            "excludeUndefined": False,
        },
        "filterLevel": "instance",
        "appliedTo": ["all"],
    }


def variable_view(name, var_type):
    return {"entity": "variable", "properties": [{"name": name, "type": var_type}]}


def build(processes, agents=(), versions=("all",), tenant=DEFAULT_TENANT, name="AI cost"):
    if len(agents) > MAX_AGENT_TILES:
        raise ValueError(f"at most {MAX_AGENT_TILES} agent tiles fit in one row")
    defs = definitions(processes, versions, tenant)
    by_key = {key: (key, process_name) for key, process_name in processes}
    several = len(processes) > 1

    def report(report_name, description, view, visualization, filters, configuration, report_defs=None):
        return {
            "id": rid(report_name),
            "exportEntityType": "single_process_report",
            "name": report_name,
            "description": description,
            "sourceIndexVersion": REPORT_INDEX_VERSION,
            "collectionId": None,
            "data": {
                # a fresh copy per report: the reports must not share one mutable list
                "definitions": json.loads(json.dumps(report_defs or defs)),
                "configuration": configuration,
                "filter": filters,
                "view": view,
                "groupBy": {"type": "none", "value": None},
                "distributedBy": {"type": "none", "value": None},
                "visualization": visualization,
                "managementReport": False,
                "instantPreviewReport": False,
            },
        }

    def number_report(report_name, description, aggregation, filters, variable=COST_USD, var_type="Double",
                      report_defs=None):
        return report(
            report_name,
            description,
            variable_view(variable, var_type),
            "number",
            filters,
            {"aggregationTypes": [{"type": aggregation, "value": None}], "showInstanceCount": True},
            report_defs,
        )

    def agent_report(number, label, prefix, process_key):
        variable = prefix + "Cost"
        if process_key is None:
            agent_defs = defs
        elif process_key in by_key:
            agent_defs = definitions([by_key[process_key]], versions, tenant)
        else:
            raise ValueError(f"agent '{label}' reads process '{process_key}', which is not one of the listed processes: "
                             + ", ".join(by_key))
        return number_report(
            f"{number} - {label} cost (micro-USD)",
            f"Total cost of {label} over all completed instances, in micro-USD (1,000,000 = 1 USD; variable "
            f"{variable}).",
            "sum",
            [completed_only()],
            variable,
            MICRO_USD_TYPE,
            agent_defs,
        )

    cost_variables = [prefix + "Cost" for _, prefix, _ in agents]
    token_variables = [prefix + suffix for _, prefix, _ in agents for suffix in TOKEN_SUFFIXES]

    def table_report(report_name, description, shown_variables, hidden_variables):
        """Raw data table. The columns are the process ones plus the process variables in `shown_variables`, in that
        order; every other variable Optimize knows is appended by includeNewVariables unless it is hidden. The
        per-agent variables are not listed in `shown_variables` on purpose: Optimize may refuse a column for a variable
        that no instance has written yet, and includeNewVariables appends them anyway."""
        excluded = ["processDefinitionId", "businessKey", "duration", "engineName", "tenantId"]
        order = ["processInstanceId", "startDate", "endDate"]
        if several:
            # tell the processes apart in the table
            order.insert(0, "processDefinitionKey")
        else:
            excluded.insert(0, "processDefinitionKey")
        order += ["variable:" + name for name in shown_variables]
        excluded += ["variable:" + name for name in [*LEGACY_VARIABLES, *hidden_variables]]
        return report(
            report_name,
            description,
            {"entity": None, "properties": ["rawData"]},
            "table",
            [completed_only()],
            {
                "tableColumns": {
                    "includeNewVariables": True,
                    "excludedColumns": excluded,
                    "includedColumns": [],
                    "columnOrder": order,
                }
            },
        )

    r1 = table_report(
        "1 - Cost of each process instance",
        "One row per completed instance: total cost in USD and in micro-USD (1,000,000 = 1 USD), the agent name(s) "
        "and the cost of each agent in micro-USD.",
        [COST_USD, COST_MICRO_USD, COST_AGENT],
        token_variables,
    )
    agent_reports = [
        agent_report(number, label, prefix, process_key)
        for number, (label, prefix, process_key) in enumerate(agents, start=2)
    ]
    first = 2 + len(agent_reports)
    r_total = number_report(
        f"{first} - Total cost, all agents (USD)",
        "Total cost of all completed instances, all agents together.",
        "sum",
        [completed_only()],
    )
    r_month = number_report(
        f"{first + 1} - Total spend this month (USD)",
        "Total cost of completed instances started in the current calendar month.",
        "sum",
        [completed_only(), started_in_current("months")],
    )
    r_year = number_report(
        f"{first + 2} - Total spend this year (USD)",
        "Total cost of completed instances started in the current calendar year.",
        "sum",
        [completed_only(), started_in_current("years")],
    )
    r_average = number_report(
        f"{first + 3} - Average cost per case (USD)",
        "Average cost of one completed instance. Turn on the target in the report configuration to compare it with "
        "a human-handled case.",
        "avg",
        [completed_only()],
    )

    def tile(report_dto, x, y, w, h):
        return {
            "id": report_dto["id"],
            "position": {"x": x, "y": y},
            "dimensions": {"width": w, "height": h},
            "type": "optimize_report",
            "configuration": None,
        }

    tiles = []
    y = 0
    if agent_reports:
        # one row of agent tiles across the 18 columns
        width, extra = divmod(18, len(agent_reports))
        x = 0
        for i, agent_rep in enumerate(agent_reports):
            w = width + (1 if i < extra else 0)
            tiles.append(tile(agent_rep, x, 0, w, 3))
            x += w
        y = 3
    tiles += [
        tile(r_total, 0, y, 5, 3),
        tile(r_month, 5, y, 4, 3),
        tile(r_year, 9, y, 4, 3),
        tile(r_average, 13, y, 5, 3),
        tile(r1, 0, y + 3, 18, 7),
    ]
    token_reports = []
    if agents:
        r_tokens = table_report(
            f"{first + 4} - Tokens of each process instance",
            "One row per completed instance: the input, output and total tokens of each agent (its total over all "
            "model calls), next to the agent name(s).",
            [COST_AGENT],
            [COST_USD, COST_MICRO_USD, *cost_variables],
        )
        token_reports.append(r_tokens)
        tiles.append(tile(r_tokens, 0, y + 10, 18, 7))

    dashboard = {
        "id": rid("dashboard " + name),
        "exportEntityType": "dashboard",
        "name": name,
        "description": "Cost of the AI agent flow: per instance, per agent, this month, this year and the average "
        "per case.",
        "sourceIndexVersion": DASHBOARD_INDEX_VERSION,
        "collectionId": None,
        "tiles": tiles,
        # No variable filter on purpose: Optimize validates a dashboard variable filter against variables that already
        # exist in its data (DashboardService.validateVariableFiltersExistInReports), so importing one before the
        # first instance with business_costAgent has been imported fails. Add it in the dashboard editor once data
        # exists.
        "availableFilters": [],
    }
    return [r1, *agent_reports, r_total, r_month, r_year, r_average, *token_reports, dashboard]


def check(entities, process_count):
    assert process_count >= 1, "at least one process is needed"
    ids = [e["id"] for e in entities]
    assert len(ids) == len(set(ids)), "duplicate ids"
    reports = {e["id"] for e in entities if e["exportEntityType"] == "single_process_report"}
    keys = set()
    for e in entities:
        if e["exportEntityType"] == "dashboard":
            assert {t["id"] for t in e["tiles"]} == reports, "every report needs a tile and every tile a report"
        else:
            defs = e["data"]["definitions"]
            assert 1 <= len(defs) <= process_count, "definitions count"
            assert len({d["identifier"] for d in defs}) == len(defs), "duplicate definition identifiers"
            assert len({d["key"] for d in defs}) == len(defs), "the same process listed twice"
            assert all(d["tenantIds"] != [None] for d in defs), "a null tenant is refused by Optimize"
            keys |= {d["key"] for d in defs}
    assert len(keys) == process_count, "every listed process must be a data source of some report"
    for e in entities:
        assert e["name"] and len(e["name"]) <= 80
        assert len(e.get("description") or "") <= 300
        want = REPORT_INDEX_VERSION if e["exportEntityType"] == "single_process_report" else DASHBOARD_INDEX_VERSION
        assert e["sourceIndexVersion"] == want
    text = json.dumps(entities)
    assert '"totalCostUsd"' not in text and '"totalCost"' not in text, "unprefixed variable name"


def parse_process(value):
    key, _, name = value.partition("=")
    key = key.strip()
    if not key:
        raise argparse.ArgumentTypeError("empty process id")
    return key, (name.strip() or key)


def parse_agent(value):
    label, sep, rest = value.partition("=")
    prefix, _, process = rest.partition("@")
    label, prefix, process = label.strip(), prefix.strip(), process.strip()
    if not sep or not label or not prefix:
        raise argparse.ArgumentTypeError("expected LABEL=PREFIX[@PROCESS_ID]")
    return label, prefix, (process or None)


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--process", action="append", type=parse_process, metavar="ID[=NAME]",
                    help="BPMN process id (and optional display name) to use as a data source; repeat for several. "
                         "Default: the two demo processes.")
    ap.add_argument("--agent", action="append", type=parse_agent, metavar="LABEL=PREFIX[@PROCESS_ID]",
                    help="add one agent: a cost tile on PREFIX+Cost (whole micro-USD), read from PROCESS_ID only "
                         "(default: all listed processes), and its PREFIX+InputTokens/OutputTokens/TotalTokens in the "
                         "token table. Repeat for several. Default: the demo agents when no --process is given, "
                         "otherwise none.")
    ap.add_argument("--name", default="AI cost", help="dashboard name (default: %(default)s)")
    ap.add_argument("--versions", choices=["all", "latest"], default="all", help="process versions (default: all)")
    ap.add_argument("--tenant", default=DEFAULT_TENANT, help="tenant id (default: %(default)s, Camunda 8's default)")
    ap.add_argument("-o", "--out", default=os.path.join(here, "ai-cost-dashboard.json"), help="output file")
    args = ap.parse_args(argv)

    processes = args.process or DEMO_PROCESSES
    if args.agent is not None:
        agents = args.agent
    else:
        agents = [] if args.process else DEMO_AGENTS
    try:
        entities = build(processes, agents, versions=(args.versions,), tenant=args.tenant, name=args.name)
    except ValueError as e:
        ap.error(str(e))
    check(entities, len(processes))
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(entities, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {args.out} - {len(entities)} entities, {len(processes)} process(es): "
          + ", ".join(k for k, _ in processes) + f"; {len(agents)} agent tile(s)")


if __name__ == "__main__":
    sys.exit(main())
