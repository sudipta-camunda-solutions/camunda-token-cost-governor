#!/usr/bin/env python3
"""Generate the Optimize entity-import file for the "AI cost" dashboard (5 reports + 1 dashboard).

    python optimize/generate-dashboard.py                      # the two demo processes -> optimize/ai-cost-dashboard.json
    python optimize/generate-dashboard.py --process my-agent-process
    python optimize/generate-dashboard.py --process order-agent="Order agent" --process claims-agent -o my-dashboard.json

Every --process becomes one data source (definition) of every report, so the reports add up over all the listed
processes. Optimize has no wildcard for "all processes": each one needs its concrete process ID (the BPMN process id),
and every listed process must be a data source of the collection you import into.

The JSON layout, the schema versions (reports 11, dashboard 8) and the list of valid report combinations come from
Optimize's own source at git tag 8.10.0-alpha5. If your Optimize refuses the file with a version message, put the
`sourceIndexVersion` numbers of an export from your own Optimize into REPORT_INDEX_VERSION / DASHBOARD_INDEX_VERSION.

The variables the reports read must be exported to Optimize: on new Camunda 8.10 SaaS clusters only variables named
business_* are, which is why the demo processes write business_totalCostUsd, business_totalCostMicros and
business_costAgent.
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

COST_USD = "business_totalCostUsd"
COST_MICROS = "business_totalCostMicros"
COST_AGENT = "business_costAgent"

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


def build(processes, versions=("all",), tenant=DEFAULT_TENANT):
    defs = definitions(processes, versions, tenant)
    several = len(processes) > 1

    def report(name, description, view, visualization, filters, configuration):
        return {
            "id": rid(name),
            "exportEntityType": "single_process_report",
            "name": name,
            "description": description,
            "sourceIndexVersion": REPORT_INDEX_VERSION,
            "collectionId": None,
            "data": {
                # a fresh copy per report: the reports must not share one mutable list
                "definitions": json.loads(json.dumps(defs)),
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

    def number_report(name, description, aggregation, filters):
        return report(
            name,
            description,
            variable_view(COST_USD, "Double"),
            "number",
            filters,
            {"aggregationTypes": [{"type": aggregation, "value": None}], "showInstanceCount": True},
        )

    excluded = ["processDefinitionId", "businessKey", "duration", "engineName", "tenantId"]
    order = ["processInstanceId", "startDate", "endDate"]
    if several:
        # tell the processes apart in the table
        order.insert(0, "processDefinitionKey")
    else:
        excluded.insert(0, "processDefinitionKey")
    order += ["variable:" + COST_USD, "variable:" + COST_MICROS, "variable:" + COST_AGENT]

    r1 = report(
        "1 - Cost of each process instance",
        "One row per completed instance: cost in USD and micro-dollars, and the agent name.",
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
    r2 = number_report(
        "2 - Agent-wise cost (USD)",
        "Total cost of all completed instances. Add an agent filter on the dashboard (Edit, Filters, Variable, "
        "business_costAgent) to see one agent.",
        "sum",
        [completed_only()],
    )
    r3 = number_report(
        "3 - Total spend this month (USD)",
        "Total cost of completed instances started in the current calendar month.",
        "sum",
        [completed_only(), started_in_current("months")],
    )
    r4 = number_report(
        "4 - Total spend this year (USD)",
        "Total cost of completed instances started in the current calendar year.",
        "sum",
        [completed_only(), started_in_current("years")],
    )
    r5 = number_report(
        "5 - Average cost per case (USD)",
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

    dashboard = {
        "id": rid("dashboard AI cost"),
        "exportEntityType": "dashboard",
        "name": "AI cost",
        "description": "Cost of the AI agent flow: per instance, per agent, this month, this year and the average "
        "per case.",
        "sourceIndexVersion": DASHBOARD_INDEX_VERSION,
        "collectionId": None,
        "tiles": [
            tile(r2, 0, 0, 5, 3),
            tile(r3, 5, 0, 4, 3),
            tile(r4, 9, 0, 4, 3),
            tile(r5, 13, 0, 5, 3),
            tile(r1, 0, 3, 18, 7),
        ],
        # No variable filter on purpose: Optimize validates a dashboard variable filter against variables that already
        # exist in its data (DashboardService.validateVariableFiltersExistInReports), so importing one before the
        # first instance with business_costAgent has been imported fails. Add it in the dashboard editor once data
        # exists.
        "availableFilters": [],
    }
    return [r1, r2, r3, r4, r5, dashboard]


def check(entities, process_count):
    assert process_count >= 1, "at least one process is needed"
    ids = [e["id"] for e in entities]
    assert len(ids) == len(set(ids)), "duplicate ids"
    reports = {e["id"] for e in entities if e["exportEntityType"] == "single_process_report"}
    for e in entities:
        if e["exportEntityType"] == "dashboard":
            for t in e["tiles"]:
                assert t["id"] in reports, "tile without report: " + t["id"]
        else:
            defs = e["data"]["definitions"]
            assert len(defs) == process_count, "definitions count"
            assert len({d["identifier"] for d in defs}) == len(defs), "duplicate definition identifiers"
            assert len({d["key"] for d in defs}) == len(defs), "the same process listed twice"
            assert all(d["tenantIds"] != [None] for d in defs), "a null tenant is refused by Optimize"
    for e in entities:
        assert e["name"] and len(e["name"]) <= 80
        assert len(e.get("description") or "") <= 300
        want = REPORT_INDEX_VERSION if e["exportEntityType"] == "single_process_report" else DASHBOARD_INDEX_VERSION
        assert e["sourceIndexVersion"] == want
    text = json.dumps(entities)
    assert '"totalCostUsd"' not in text and '"totalCostMicros"' not in text, "unprefixed variable name"


def parse_process(value):
    key, _, name = value.partition("=")
    key = key.strip()
    if not key:
        raise argparse.ArgumentTypeError("empty process id")
    return key, (name.strip() or key)


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--process", action="append", type=parse_process, metavar="ID[=NAME]",
                    help="BPMN process id (and optional display name) to use as a data source; repeat for several. "
                         "Default: the two demo processes.")
    ap.add_argument("--versions", choices=["all", "latest"], default="all", help="process versions (default: all)")
    ap.add_argument("--tenant", default=DEFAULT_TENANT, help="tenant id (default: %(default)s, Camunda 8's default)")
    ap.add_argument("-o", "--out", default=os.path.join(here, "ai-cost-dashboard.json"), help="output file")
    args = ap.parse_args(argv)

    processes = args.process or DEMO_PROCESSES
    entities = build(processes, versions=(args.versions,), tenant=args.tenant)
    check(entities, len(processes))
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(entities, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"wrote {args.out} - {len(entities)} entities, {len(processes)} process(es): "
          + ", ".join(k for k, _ in processes))


if __name__ == "__main__":
    sys.exit(main())
