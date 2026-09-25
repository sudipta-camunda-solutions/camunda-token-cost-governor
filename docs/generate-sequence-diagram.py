#!/usr/bin/env python3
"""Generate the sequence diagram of the whole flow (connector, Camunda SaaS and secrets) from one description.

    python docs/generate-sequence-diagram.py                    # writes docs/sequence-diagram.md and docs/sequence-diagram.svg
    python docs/generate-sequence-diagram.py --inline out.svg   # also writes a theme-aware fragment for embedding in a web page

The flow is data (FLOW below); the Mermaid text and the SVG are both produced from it, so they cannot drift. Edit FLOW,
run the script, commit the three outputs. Facts come from docker/docker-compose.yml, docker/.env.example, README.md
("Docker deployment") and GovernorConnector.java; nothing here is measured on a live cluster beyond what the README says.

The SVG file uses presentation attributes only (no CSS, no scripts, no markers) and a white background, so it opens in
Word and in any browser. The --inline fragment takes its colours from page variables (--dg-*), so a page can theme it.
"""
import argparse
import html
import os
import re
import sys

# id, kind, two label lines, domain. Order groups the domains: the arrows into SaaS all start on the left.
PARTICIPANTS = [
    ("dev", "actor", "Developer", "or admin", "you"),
    ("prom", "participant", "Prometheus", "and Grafana", "you"),
    ("gov", "participant", "GovernorConnector", "governor-connector.jar", "you"),
    ("rt", "participant", "connectors-bundle", "self-hosted runtime", "you"),
    ("con", "participant", "Camunda Console", "OAuth and Secrets API", "saas"),
    ("zb", "participant", "SaaS cluster", "Zeebe", "saas"),
    ("optimize", "participant", "Optimize", "and Operate", "saas"),
    ("saas", "participant", "SaaS-hosted runtime", "AI Agent job worker", "saas"),
    ("llm", "participant", "LLM provider", "", "third"),
]
DOMAINS = [("you", "YOUR SIDE"), ("saas", "CAMUNDA SAAS"), ("third", "THIRD PARTY")]

# Items: ("phase", title, description, items) - ("sub", text) - ("loop", label, items) - ("alt", [(condition, items)])
#        ("msg", from, to, label, flags) - ("self", who, label, flags) - ("note", from, to, text, tone)
# flags: "r" = dashed (a reply, or a job pushed down a connection the runtime opened), "s" = carries a credential or
# secret. tone: "plain" or "secret".
FLOW = [
    ("phase", "SETUP", "credentials and secrets, done once", [
        ("msg", "dev", "con", "create the cluster secret GOVERNOR_PRICE_TABLE (a JSON array, [] at the least)", "s"),
        ("msg", "dev", "con", "create an API client with the scopes Orchestration Cluster API and Secrets", "s"),
        ("msg", "con", "dev", "client id and client secret", "rs"),
        ("msg", "dev", "rt", "write cluster id, region, client id and client secret into docker/.env", "s"),
        ("note", "rt", "rt", "Environment variables only. They never appear in the BPMN or the repo.", "secret"),
        ("msg", "dev", "zb", "deploy the BPMN from Web Modeler (Token Cost Reporter template v6)", ""),
        ("note", "zb", "zb", "The BPMN holds only the placeholder {{secrets.GOVERNOR_PRICE_TABLE}}, never the value.",
         "secret"),
    ]),
    ("phase", "START-UP", "the runtime connects out, SaaS never calls in", [
        ("msg", "rt", "gov", "load governor-connector.jar from LOADER_PATH (SPI)", ""),
        ("msg", "rt", "con", "OAuth client-credentials token request for the cluster API", "s"),
        ("msg", "con", "rt", "access token (JWT)", "rs"),
        ("msg", "rt", "zb", "open the job stream for io.github.camunda:token-cost-governor:1, "
                            "fetch process definitions over REST on port 443", ""),
        ("note", "rt", "zb", "REST address: REGION.zeebe.camunda.io/CLUSTER_ID, or REGION.api.camunda.io/CLUSTER_ID on "
                             "newer clusters. A 404 means the wrong one: set CAMUNDA_CLIENT_REST_ADDRESS.", "plain"),
        ("note", "rt", "rt", "AI Agent jobs stay with the SaaS runtime: CAMUNDA_CONNECTOR_AGENTICAI_ENABLED=false.",
         "plain"),
    ]),
    ("phase", "PROCESS INSTANCE", "one run of the process", [
        ("self", "zb", "the instance starts, the Start event sets the totals to 0 and costAgent to empty", ""),
        ("loop", "for each AI agent: Research Analyst, then Quick Answer", [
            ("sub", "AI AGENT STEP", "runs on the SaaS-hosted runtime"),
            ("msg", "zb", "saas", "job io.camunda.agenticai:aiagent-job-worker:1 (pushed down the stream)", "r"),
            ("self", "saas", "resolve the SaaS-only secrets CAMUNDA_PROVIDED_LLM_API_ENDPOINT and "
                             "CAMUNDA_PROVIDED_LLM_API_KEY", "s"),
            ("msg", "saas", "llm", "model calls and tool calls (ad-hoc loop)", ""),
            ("msg", "llm", "saas", "answers and token usage", "r"),
            ("msg", "saas", "zb", "complete the job: agent.context.metrics.tokenUsage, totals over all model calls", ""),
            ("sub", "TOKEN COST STEP", "runs on your self-hosted runtime"),
            ("msg", "zb", "rt", "job io.github.camunda:token-cost-governor:1 with provider, model, agentName, tokens "
                                "and priceTableSecret = {{secrets.GOVERNOR_PRICE_TABLE}}, still a placeholder", "r"),
            ("msg", "rt", "gov", "execute(context)", ""),
            ("msg", "gov", "rt", "bindVariables(TokenCostRequest): the runtime resolves the secret now", "s"),
            ("alt", [
                ("the Secrets scope and the secret are in place", [
                    ("msg", "rt", "con", "token request for the audience secrets.camunda.io, then read "
                                         "GOVERNOR_PRICE_TABLE", "s"),
                    ("msg", "con", "rt", "the secret value: the JSON price table", "rs"),
                    ("note", "rt", "con", "Not yet observed on a live cluster with a correctly scoped client "
                                          "(README, Docker deployment).", "plain"),
                ]),
                ("the API client has no Secrets scope", [
                    ("msg", "con", "rt", "HTTP 401 for the audience secrets.camunda.io: the job fails, the connector "
                                         "logs a HINT", "r"),
                ]),
                ("the secret was never created", [
                    ("msg", "con", "rt", "secret not available: the job fails (create it, even as [])", "r"),
                ]),
            ]),
            ("msg", "rt", "gov", "the request, with the secret substituted", "r"),
            ("self", "gov", "look up provider:model in the secret's table, else in the bundled default table, price "
                            "it in whole micro-USD, count it in Micrometer, log it", ""),
            ("alt", [
                ("a price was found", [
                    ("msg", "gov", "rt", "TokenCostResult", "r"),
                ]),
                ("no price in either table", [
                    ("msg", "gov", "rt", "BPMN error PRICE_NOT_FOUND (a boundary event in the process)", "r"),
                ]),
            ]),
            ("msg", "rt", "zb", "complete the job with tokenCostResult (or throw the BPMN error)", ""),
            ("self", "zb", "output mappings: the totals, costAgent, this agent's cost, and its input, output and total "
                           "tokens", ""),
        ]),
    ]),
    ("phase", "REPORTING", "after the instance completes", [
        ("msg", "zb", "optimize", "records are exported, Optimize imports only the business_ variables (8.10 SaaS)", ""),
        ("msg", "dev", "optimize", "import the generated dashboard JSON into a collection", ""),
        ("msg", "prom", "rt", "scrape :8080/actuator/prometheus (the governor_ counters)", ""),
        ("msg", "rt", "prom", "counters per provider, model and agent", "r"),
    ]),
]

CREDENTIALS = [
    ("API client id and secret",
     "docker/.env, passed to the runtime as CAMUNDA_CLIENT_ID and CAMUNDA_CLIENT_SECRET",
     "the self-hosted runtime only",
     "OAuth tokens: one for the cluster API (jobs), and with the Secrets scope one for secrets.camunda.io"),
    ("GOVERNOR_PRICE_TABLE cluster secret",
     "Camunda Console, referenced in the BPMN as {{secrets.GOVERNOR_PRICE_TABLE}}",
     "the runtime, when the connector binds its variables. The connector never sees Console credentials",
     "the price table (a JSON array). A row here beats the bundled default table for that model"),
    ("CAMUNDA_PROVIDED_LLM_API_ENDPOINT and CAMUNDA_PROVIDED_LLM_API_KEY",
     "SaaS-only cluster secrets, referenced by the AI Agent elements",
     "the SaaS-hosted runtime only",
     "the AI Agent's LLM calls. Your runtime cannot resolve them, so it does not take AI Agent jobs"),
]

BREAKS = [
    ("Job fails, the log has a HINT about the audience secrets.camunda.io, HTTP 401",
     "the API client has no Secrets scope",
     "create the client with the Orchestration Cluster API and Secrets scopes"),
    ("Job fails with SecretNotAvailableException",
     "GOVERNOR_PRICE_TABLE was never created, or the runtime cannot read Console secrets",
     "create it, even as [], and keep CAMUNDA_CONNECTOR_SECRETPROVIDER_CONSOLE_ENABLED=true"),
    ("Failed with code 404 while fetching a process definition, health shows processDefinitionImport DOWN",
     "the cluster serves its REST API at another address",
     "set CAMUNDA_CLIENT_REST_ADDRESS to https://REGION.api.camunda.io/CLUSTER_ID"),
    ("BPMN error PRICE_NOT_FOUND",
     "no row for provider:model in the secret or in the bundled table",
     "add the model to the GOVERNOR_PRICE_TABLE secret"),
    ("An AI Agent job fails with \"Must be an HTTP or HTTPS URL\" and leaves an incident",
     "the self-hosted runtime took the job and cannot resolve CAMUNDA_PROVIDED_LLM_*",
     "keep CAMUNDA_CONNECTOR_AGENTICAI_ENABLED=false"),
]

TITLE = "Token Cost Reporter: connector, Camunda SaaS and secrets, end to end"
SUBTITLE = "Every arrow into SaaS starts on your side. The runtime polls out; SaaS never calls in."
ARIA = ("Sequence diagram. A self-hosted connector runtime opens every connection to Camunda SaaS, resolves the "
        "GOVERNOR_PRICE_TABLE secret through the Console at the moment the connector binds its request, prices each "
        "AI agent's tokens, and reports through Optimize and Prometheus.")

# legend samples: (label, dashed, colour role)
LEGEND = [
    ("request", False, "ink"),
    ("reply, or a job pushed down a connection the runtime opened", True, "ink"),
    ("carries a credential or a secret", False, "secret"),
]

# ---------------------------------------------------------------- layout constants
COL = 160          # column width
LEFT = 30
FS = 12            # label font size
LH = 15            # label line height
TITLE_SIZE = 18
COMPACT = False    # phase title and description on one line
REPEAT_BOXES = True  # repeat the participant boxes at the bottom
HEADER_SHIFT = 0   # pixels the title block is pulled up (a single-line title, no subtitle, when above 0)
GAP_AFTER = 16     # space below each arrow
BADGE_R, BADGE_FONT = 8, 10   # numbered circle at the tail of each arrow
IDX = {p[0]: i for i, p in enumerate(PARTICIPANTS)}
N = len(PARTICIPANTS)
W = 2 * LEFT + N * COL


def configure(participants=None, flow=None, domains=None, title=None, subtitle=None, aria=None, legend=None,
              col=None, left=None, compact=None, repeat_boxes=None, title_size=None, header_shift=None,
              gap_after=None, badge_r=None, badge_font=None):
    """Replace the content and layout constants, so another script can draw a different diagram with this engine."""
    g = globals()
    for name, value in (("PARTICIPANTS", participants), ("FLOW", flow), ("DOMAINS", domains), ("TITLE", title),
                        ("SUBTITLE", subtitle), ("ARIA", aria), ("LEGEND", legend), ("COL", col), ("LEFT", left),
                        ("COMPACT", compact), ("REPEAT_BOXES", repeat_boxes), ("TITLE_SIZE", title_size),
                        ("HEADER_SHIFT", header_shift), ("GAP_AFTER", gap_after), ("BADGE_R", badge_r),
                        ("BADGE_FONT", badge_font)):
        if value is not None:
            g[name] = value
    g["IDX"] = {p[0]: i for i, p in enumerate(g["PARTICIPANTS"])}
    g["N"] = len(g["PARTICIPANTS"])
    g["W"] = 2 * g["LEFT"] + g["N"] * g["COL"]


def cx(i):
    return LEFT + COL / 2 + i * COL


# ---------------------------------------------------------------- colours
PALETTE = {
    "bg": "#ffffff", "ink": "#111827", "muted": "#4b5563", "line": "#a9b1bd",
    "band-a": "#f6f7f9", "band-b": "#eceff4", "note": "#ffffff", "note-line": "#9ca3af",
    "secret": "#b45309", "secret-fill": "#fef3c7", "frame": "#6b7280",
    "you": "#047857", "saas": "#1d4ed8", "third": "#6b7280", "box": "#ffffff", "box-line": "#374151",
}


class Ink:
    """Colours as literal hex (file mode) or as page variables in style attributes (inline mode)."""

    def __init__(self, inline):
        self.inline = inline

    def c(self, role):
        return f"var(--dg-{role})" if self.inline else PALETTE[role]

    def paint(self, fill=None, stroke=None):
        pairs = []
        if fill is not None:
            pairs.append(("fill", self.c(fill) if fill != "none" else "none"))
        if stroke is not None:
            pairs.append(("stroke", self.c(stroke) if stroke != "none" else "none"))
        if self.inline:
            return 'style="' + ";".join(f"{k}:{v}" for k, v in pairs) + '"'
        return " ".join(f'{k}="{v}"' for k, v in pairs)


def esc(text):
    return html.escape(text, quote=False)


def tw(text, size=None):
    """Estimated rendered width of text: glyph widths by class, in em, times the font size."""
    size = FS if size is None else size
    total = 0.0
    for ch in text:
        if ch in "il.,:;'|!()[]{}` ":
            total += 0.30
        elif ch in "-/\\tfjr":
            total += 0.40
        elif ch in "mwMW":
            total += 0.85
        elif ch.isupper() or ch == "_":
            total += 0.66
        elif ch.isdigit():
            total += 0.56
        else:
            total += 0.52
    return total * size


def wrap(text, max_px, size=None):
    """Greedy word wrap on the estimated width; a token that is too long for a line breaks after punctuation."""
    size = FS if size is None else size
    lines, cur = [], ""

    def fits(s):
        return tw(s, size) <= max_px

    for word in text.split(" "):
        while not fits(word):
            k = len(word)
            while k > 1 and not fits(word[:k]):
                k -= 1
            cut = max((word.rfind(ch, 0, k) for ch in ":/.-_,"), default=-1)
            end = cut + 1 if cut >= k // 2 else k
            if cur:
                lines.append(cur)
                cur = ""
            lines.append(word[:end])
            word = word[end:]
        if not cur:
            cur = word
        elif fits(cur + " " + word):
            cur += " " + word
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def used(items):
    """Participant indexes touched by the items (for frame extents)."""
    found = set()
    for it in items:
        kind = it[0]
        if kind == "msg":
            found |= {IDX[it[1]], IDX[it[2]]}
        elif kind == "self":
            found.add(IDX[it[1]])
        elif kind == "note":
            found |= {IDX[it[1]], IDX[it[2]]}
        elif kind == "phase":
            found |= used(it[3])
        elif kind == "loop":
            found |= used(it[2])
        elif kind == "alt":
            for _, branch in it[1]:
                found |= used(branch)
    return found


def secret_arrows(items, counter):
    """Numbers (1-based, in drawing order) of the arrows that carry a credential or secret."""
    out = []
    for it in items:
        kind = it[0]
        if kind in ("msg", "self"):
            counter[0] += 1
            if "s" in it[-1]:
                out.append(counter[0])
        elif kind == "phase":
            out += secret_arrows(it[3], counter)
        elif kind == "loop":
            out += secret_arrows(it[2], counter)
        elif kind == "alt":
            for _, branch in it[1]:
                out += secret_arrows(branch, counter)
    return out


# ---------------------------------------------------------------- SVG
class Svg:
    def __init__(self, inline):
        self.ink = Ink(inline)
        self.inline = inline
        self.bands, self.lifelines, self.frames, self.fg = [], [], [], []
        self.n = 0
        self.band_index = 0
        self.halo = "band-a"

    # primitives ---------------------------------------------------------
    def text(self, x, y, s, size=None, weight=None, anchor="start", fill="ink", italic=False, spacing=None, halo=False):
        size = FS if size is None else size
        common = f'x="{x:.1f}" y="{y:.1f}" font-size="{size}" text-anchor="{anchor}"'
        if weight:
            common += f' font-weight="{weight}"'
        if italic:
            common += ' font-style="italic"'
        if spacing:
            common += f' letter-spacing="{spacing}"'
        if halo:
            # the band colour behind the glyphs keeps the dotted lifelines from running through the words
            self.fg.append(f'<text {common} stroke-width="4" stroke-linejoin="round" '
                           f'{self.ink.paint(fill=self.halo, stroke=self.halo)}>{esc(s)}</text>')
        self.fg.append(f"<text {common} {self.ink.paint(fill=fill)}>{esc(s)}</text>")

    def head(self, x, y, d, color):
        pts = f"{x:.1f},{y:.1f} {x - d * 9:.1f},{y - 4.5:.1f} {x - d * 9:.1f},{y + 4.5:.1f}"
        self.fg.append(f'<polygon points="{pts}" {self.ink.paint(fill=color, stroke="none")}/>')

    def badge(self, x, y):
        self.n += 1
        self.fg.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{BADGE_R}" '
                       f'{self.ink.paint(fill="ink", stroke="none")}/>')
        self.text(x, y + BADGE_FONT * 0.35, str(self.n), size=BADGE_FONT, weight="bold", anchor="middle", fill="bg")

    # items ---------------------------------------------------------------
    def place(self, items, y):
        for it in items:
            kind = it[0]
            y = getattr(self, "do_" + kind)(it, y)
        return y

    def do_phase(self, it, y):
        _, title, desc, items = it
        self.band_index += 1
        self.halo = "band-a" if self.band_index % 2 else "band-b"
        top = y
        y += 8
        heading = f"{self.band_index}  {title}"
        if COMPACT:
            # the heading shares the row with the lifelines, so it gets the band colour behind it
            self.text(14, y + 12, heading, size=12, weight="bold", spacing="0.06em", halo=True)
            self.text(14 + tw(heading, 12) * 1.1 + len(heading) * 0.72 + 14, y + 12, desc, size=11, fill="muted",
                      halo=True)
            y += 28
        else:
            self.text(14, y + 10, heading, size=12, weight="bold", spacing="0.06em")
            self.text(14, y + 26, desc, size=11, fill="muted")
            y += 38
        y = self.place(items, y)
        y += 10
        self.bands.append((top, y, self.band_index))
        return y

    def do_sub(self, it, y):
        _, title, desc = it
        y += 6
        self.text(14, y + 10, title, size=11, weight="bold", fill="muted", spacing="0.06em")
        self.text(14, y + 25, desc, size=11, fill="muted")
        return y + 34

    def do_msg(self, it, y):
        _, src, dst, label, flags = it
        x1, x2 = cx(IDX[src]), cx(IDX[dst])
        d = 1 if x2 > x1 else -1
        dx = abs(x2 - x1)
        secret, dashed = "s" in flags, "r" in flags
        color = "secret" if secret else "ink"
        mid = (x1 + x2) / 2
        # the label is centred on the arrow, so it must not run past either edge of the page
        lines = wrap(label, min(max(dx + 120, 170), 560, 2 * min(mid - 8, W - 8 - mid)))
        for k, ln in enumerate(lines):
            self.text(mid, y + 12 + k * LH, ln, anchor="middle", fill=color, halo=True)
        ay = y + len(lines) * LH + 9
        dash = ' stroke-dasharray="5 4"' if dashed else ""
        self.fg.append(f'<line x1="{x1:.1f}" y1="{ay:.1f}" x2="{x2 - d * 8:.1f}" y2="{ay:.1f}" '
                       f'stroke-width="1.5"{dash} {self.ink.paint(stroke=color)}/>')
        self.head(x2, ay, d, color)
        self.badge(x1 + d * 13, ay)
        return ay + GAP_AFTER

    def do_self(self, it, y):
        _, who, label, flags = it
        x = cx(IDX[who])
        color = "secret" if "s" in flags else "ink"
        right = W - (x + 50) - 30
        side = 1 if right >= 260 else -1
        width = min(400, right if side == 1 else x - 50 - 12)
        lines = wrap(label, width)
        top = y + 6
        for k, ln in enumerate(lines):
            self.text(x + side * 50, top + 9 + k * LH, ln, anchor="start" if side == 1 else "end", fill=color,
                      halo=True)
        loop_h = 20
        ly = top + 2
        self.fg.append(f'<polyline points="{x:.1f},{ly:.1f} {x + side * 36:.1f},{ly:.1f} {x + side * 36:.1f},'
                       f'{ly + loop_h:.1f} {x + side * 8:.1f},{ly + loop_h:.1f}" fill="none" stroke-width="1.5" '
                       f'{self.ink.paint(stroke=color)}/>')
        self.head(x, ly + loop_h, -side, color)
        self.badge(x + side * 13, ly)
        return top + max(len(lines) * LH + 8, loop_h + 12) + 8

    def do_note(self, it, y):
        _, a, b, text, tone = it
        left, right = cx(IDX[a]), cx(IDX[b])
        x1, x2 = min(left, right) - 78, max(left, right) + 78
        if x2 - x1 < 300:
            mid = (x1 + x2) / 2
            x1, x2 = mid - 150, mid + 150
        if x1 < 8:
            x1, x2 = 8, x2 + (8 - x1)
        if x2 > W - 8:
            x1, x2 = max(8, x1 - (x2 - (W - 8))), W - 8
        lines = wrap(text, x2 - x1 - 22, 11.5)
        h = len(lines) * LH + 12
        secret = tone == "secret"
        self.fg.append(f'<rect x="{x1:.1f}" y="{y + 4:.1f}" width="{x2 - x1:.1f}" height="{h}" rx="3" '
                       f'stroke-width="1" {self.ink.paint(fill="secret-fill" if secret else "note", stroke="secret" if secret else "note-line")}/>')
        for k, ln in enumerate(lines):
            self.text(x1 + 10, y + 4 + 15 + k * LH - 3, ln, size=11.5, fill="ink")
        return y + 4 + h + 10

    def frame_extents(self, items, pad):
        cols = sorted(used(items))
        lo, hi = cols[0], cols[-1]
        while hi - lo + 1 < 3:
            if hi < N - 1:
                hi += 1
            elif lo > 0:
                lo -= 1
            else:
                break
            if hi - lo + 1 < 3 and lo > 0:
                lo -= 1
        return cx(lo) - COL / 2 + pad, cx(hi) + COL / 2 - pad

    def tab(self, x, y, label):
        wtab = 8 + len(label) * 7
        self.fg.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{wtab}" height="17" rx="2" '
                       f'{self.ink.paint(fill="frame", stroke="none")}/>')
        self.text(x + 5, y + 12.5, label, size=11, weight="bold", fill="bg")
        return wtab

    def do_loop(self, it, y):
        _, label, items = it
        x1, x2 = self.frame_extents(items, 12)
        top = y + 4
        wtab = self.tab(x1 + 6, top + 5, "loop")
        self.text(x1 + 6 + wtab + 8, top + 18, label, size=11.5, weight="bold", halo=True)
        y = self.place(items, top + 30)
        y += 4
        self.frames.append(f'<rect x="{x1:.1f}" y="{top:.1f}" width="{x2 - x1:.1f}" height="{y - top:.1f}" rx="4" '
                           f'fill="none" stroke-width="1.5" stroke-dasharray="7 5" {self.ink.paint(stroke="frame")}/>')
        return y + 6

    def do_alt(self, it, y):
        _, branches = it
        everything = [i for _, branch in branches for i in branch]
        x1, x2 = self.frame_extents(everything, 4)
        top = y + 4
        y = top + 4
        for k, (cond, branch) in enumerate(branches):
            if k:
                self.frames.append(f'<line x1="{x1:.1f}" y1="{y:.1f}" x2="{x2:.1f}" y2="{y:.1f}" stroke-width="1" '
                                   f'stroke-dasharray="4 4" {self.ink.paint(stroke="frame")}/>')
                y += 3
            tx = x1 + 6
            if k == 0:
                tx += self.tab(x1 + 6, y + 2, "alt") + 8
            self.text(tx, y + 15, f"[{cond}]", size=11.5, weight="bold", italic=True, fill="muted", halo=True)
            y = self.place(branch, y + 24)
        y += 2
        self.frames.append(f'<rect x="{x1:.1f}" y="{top:.1f}" width="{x2 - x1:.1f}" height="{y - top:.1f}" rx="3" '
                           f'fill="none" stroke-width="1.25" {self.ink.paint(stroke="frame")}/>')
        return y + 8

    # page ----------------------------------------------------------------
    def boxes(self, y):
        for i, (pid, kind, l1, l2, dom) in enumerate(PARTICIPANTS):
            x = cx(i) - COL / 2 + 10
            self.fg.append(f'<rect x="{x:.1f}" y="{y}" width="{COL - 20}" height="46" rx="4" stroke-width="1.5" '
                           f'{self.ink.paint(fill="box", stroke="box-line")}/>')
            self.fg.append(f'<rect x="{x:.1f}" y="{y}" width="{COL - 20}" height="4" rx="2" '
                           f'{self.ink.paint(fill=dom, stroke="none")}/>')
            self.text(cx(i), y + 22, l1, size=12, weight="bold", anchor="middle")
            if l2:
                self.text(cx(i), y + 37, l2, size=10.5, anchor="middle", fill="muted")

    def render(self):
        # A page that embeds the fragment has its own heading, so the fragment drops the title block.
        shift = 56 if self.inline else HEADER_SHIFT
        top = 116 - shift
        y = self.place(FLOW, top + 62)
        bottom_boxes = y + 8
        # domain strips, title, legend, boxes (top and bottom)
        if not self.inline:
            self.text(LEFT, 30 if not HEADER_SHIFT else 24, TITLE, size=TITLE_SIZE, weight="bold")
            if SUBTITLE:
                self.text(LEFT, 50, SUBTITLE, size=12, fill="muted")
        lx = LEFT
        ly = 72 - shift
        for label, dashed, color in LEGEND:
            dash = ' stroke-dasharray="5 4"' if dashed else ""
            self.fg.append(f'<line x1="{lx}" y1="{ly}" x2="{lx + 34}" y2="{ly}" stroke-width="1.5"{dash} '
                           f'{self.ink.paint(stroke=color)}/>')
            self.head(lx + 42, ly, 1, color)
            self.text(lx + 52, ly + 4, label, size=11.5, fill=color if color == "secret" else "muted")
            lx += 52 + int(len(label) * 6.1) + 34
        for dom, label in DOMAINS:
            cols = [i for i, p in enumerate(PARTICIPANTS) if p[4] == dom]
            x1, x2 = cx(cols[0]) - COL / 2 + 10, cx(cols[-1]) + COL / 2 - 10
            self.text((x1 + x2) / 2, top - 4, label, size=10.5, weight="bold", anchor="middle", fill=dom,
                      spacing="0.12em")
            self.fg.append(f'<rect x="{x1:.1f}" y="{top + 2}" width="{x2 - x1:.1f}" height="3" '
                           f'{self.ink.paint(fill=dom, stroke="none")}/>')
        self.boxes(top + 8)
        if REPEAT_BOXES:
            self.boxes(bottom_boxes)
        total_h = bottom_boxes + 46 + 24 if REPEAT_BOXES else bottom_boxes + 16
        # boundary between your side and SaaS: before the first participant that is not on your side
        first_saas = next((i for i, p in enumerate(PARTICIPANTS) if p[4] != "you"), N)
        edge = LEFT + first_saas * COL
        edge_end = bottom_boxes + 46 if REPEAT_BOXES else bottom_boxes
        self.frames.append(f'<line x1="{edge}" y1="{top + 2}" x2="{edge}" y2="{edge_end}" stroke-width="1.5" '
                           f'stroke-dasharray="2 6" {self.ink.paint(stroke="frame")}/>')
        # lifelines
        for i in range(N):
            self.lifelines.append(f'<line x1="{cx(i):.1f}" y1="{top + 54}" x2="{cx(i):.1f}" y2="{bottom_boxes}" '
                                  f'stroke-width="1" stroke-dasharray="3 4" {self.ink.paint(stroke="line")}/>')
        bands = []
        for b_top, b_bot, idx in self.bands:
            bands.append(f'<rect x="0" y="{b_top:.1f}" width="{W}" height="{b_bot - b_top:.1f}" '
                         f'{self.ink.paint(fill="band-a" if idx % 2 else "band-b", stroke="none")}/>')
        return total_h, bands

    def parts(self):
        """(width, height, drawing elements in paint order), for a script that places the drawing on a page."""
        total_h, bands = self.render()
        return W, total_h, bands + self.frames + self.lifelines + self.fg

    def document(self):
        total_h, bands = self.render()
        if self.inline:
            root = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {total_h}" role="img" '
                    f'aria-label="{html.escape(ARIA, quote=True)}" class="seq-diagram" font-family="inherit">')
            background = ""
        else:
            root = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {total_h}" width="{W}" '
                    f'height="{total_h}" role="img" aria-label="{html.escape(ARIA, quote=True)}" '
                    f'font-family="Segoe UI, Helvetica, Arial, sans-serif">')
            background = f'<rect width="{W}" height="{total_h}" fill="{PALETTE["bg"]}"/>'
        parts = [root, f"<title>{esc(TITLE)}</title>", f"<desc>{esc(ARIA)}</desc>", background]
        parts += bands + self.frames + self.lifelines + self.fg + ["</svg>"]
        return "\n".join(p for p in parts if p) + "\n"


# ---------------------------------------------------------------- Mermaid
BAND = "rgba(148, 163, 184, 0.16)"


def mermaid():
    first, last = PARTICIPANTS[0][0], PARTICIPANTS[-1][0]
    lines = ["sequenceDiagram", "    autonumber"]
    boxes = {"you": "Your side", "saas": "Camunda SaaS"}
    for dom in ("you", "saas", "third"):
        members = [p for p in PARTICIPANTS if p[4] == dom]
        if dom in boxes:
            lines.append(f"    box {boxes[dom]}")
        pad = "        " if dom in boxes else "    "
        for pid, kind, l1, l2, _ in members:
            name = l1 + (f"<br/>{l2}" if l2 else "")
            lines.append(f"{pad}{kind} {pid} as {name}")
        if dom in boxes:
            lines.append("    end")

    def walk(items, ind, phase_no):
        for it in items:
            kind = it[0]
            if kind == "phase":
                phase_no[0] += 1
                lines.append(f"{ind}rect {BAND}")
                lines.append(f"{ind}    Note over {first},{last}: {phase_no[0]}. {it[1]} - {it[2]}")
                walk(it[3], ind + "    ", phase_no)
                lines.append(f"{ind}end")
            elif kind == "sub":
                lines.append(f"{ind}Note over zb,saas: {it[1]} - {it[2]}")
            elif kind == "loop":
                lines.append(f"{ind}loop {it[1]}")
                walk(it[2], ind + "    ", phase_no)
                lines.append(f"{ind}end")
            elif kind == "alt":
                for k, (cond, branch) in enumerate(it[1]):
                    lines.append(f"{ind}{'alt' if k == 0 else 'else'} {cond}")
                    walk(branch, ind + "    ", phase_no)
                lines.append(f"{ind}end")
            elif kind == "msg":
                arrow = "-->>" if "r" in it[4] else "->>"
                lines.append(f"{ind}{it[1]}{arrow}{it[2]}: {it[3]}")
            elif kind == "self":
                lines.append(f"{ind}{it[1]}->>{it[1]}: {it[2]}")
            elif kind == "note":
                where = it[1] if it[1] == it[2] else f"{it[1]},{it[2]}"
                lines.append(f"{ind}Note over {where}: {it[3]}")

    walk(FLOW, "    ", [0])
    return "\n".join(lines)


# identifiers, placeholders and addresses that should read as code in the tables
CODE = re.compile(r"(\{\{[^}]+\}\}|https://\S+|docker/\.env|[A-Za-z]+\.camunda\.io|\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\*?|"
                  r"\bSecretNotAvailableException\b|processDefinitionImport|\[\]|provider:model)")


def cell(text):
    return CODE.sub(r"`\1`", text).replace("|", "\\|")


def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(cell(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def ranges(numbers):
    """1, 2, 3, 5 -> '1-3, 5'"""
    out, start = [], None
    for i, n in enumerate(numbers):
        if start is None:
            start = prev = n
        elif n == prev + 1:
            prev = n
        else:
            out.append(f"{start}-{prev}" if prev > start else str(start))
            start = prev = n
    if start is not None:
        out.append(f"{start}-{prev}" if prev > start else str(start))
    return ", ".join(out)


def markdown():
    secret = secret_arrows(FLOW, [0])
    return f"""# {TITLE}

How the self-hosted connector runtime, Camunda SaaS and the secrets fit together, from the one-time setup to the
Optimize dashboard. Read it left to right: your side on the left, Camunda SaaS in the middle, the LLM provider on the
right. Every connection is opened from your side. The runtime polls Zeebe and asks the Console for secrets; SaaS never
calls in.

The same diagram as an image, for documents: [sequence-diagram.svg](sequence-diagram.svg). Arrows {ranges(secret)} carry a
credential or a secret.

For a user manual there is a one-page A4 version of the main process, the cluster secret from setup to one Token cost
task: [sequence-diagram-a4.svg](sequence-diagram-a4.svg) (sharp, for Word 2016 and later) and
[sequence-diagram-a4.png](sequence-diagram-a4.png) (about 300 dpi, for any Word version).

```mermaid
{mermaid()}
```

## Which credential goes where

{table(["Credential", "Where it lives", "Who reads it", "What it is for"], CREDENTIALS)}

## When it breaks

{table(["What you see", "Cause", "Fix"], BREAKS)}

## Where this comes from

`docker/docker-compose.yml`, `docker/.env.example`, the Docker deployment section of the README, the two demo BPMN
files and `GovernorConnector.java`. The success path of a secret read with a correctly scoped client is marked in the
diagram because the README says it has not been observed on a live cluster yet.

Generated by `docs/generate-sequence-diagram.py`. Edit the flow there and run the script; do not edit this file or the
SVG by hand. The A4 page comes from `docs/generate-a4-diagram.py` (`--png` also renders the PNG).
"""


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--inline", metavar="FILE", help="also write the theme-aware inline SVG fragment to FILE")
    ap.add_argument("--out-dir", default=here, help="directory for sequence-diagram.md and .svg (default: docs/)")
    args = ap.parse_args()
    with open(os.path.join(args.out_dir, "sequence-diagram.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(markdown())
    with open(os.path.join(args.out_dir, "sequence-diagram.svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(Svg(inline=False).document())
    if args.inline:
        with open(args.inline, "w", encoding="utf-8", newline="\n") as f:
            f.write(Svg(inline=True).document())
    print(f"wrote {args.out_dir}: sequence-diagram.md, sequence-diagram.svg" + (f", {args.inline}" if args.inline else ""))


if __name__ == "__main__":
    sys.exit(main())
