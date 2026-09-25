#!/usr/bin/env python3
"""One-page A4 version of the sequence diagram: how the connector uses the cluster secret, for the user manual.

    python docs/generate-a4-diagram.py            # writes docs/sequence-diagram-a4.svg
    python docs/generate-a4-diagram.py --png      # also renders docs/sequence-diagram-a4.png (about 300 dpi) with Edge

The full flow is in docs/sequence-diagram.md. This page keeps only the main process: setup by the admin, the runtime
connecting out, and one Token cost task reading the GOVERNOR_PRICE_TABLE cluster secret. It uses the drawing engine of
generate-sequence-diagram.py and places the result on a 210 x 297 mm page.

The SVG uses presentation attributes only (no CSS, scripts or images) so it opens in Word 2016 and later; the PNG is for
older versions. Insert either at full page width.
"""
import argparse
import html
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("seqgen", os.path.join(HERE, "generate-sequence-diagram.py"))
seq = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seq)

PAGE_W, PAGE_H = 794, 1123       # A4 at 96 dpi: 210 x 297 mm
MARGIN = 30                      # about 8 mm

PARTICIPANTS = [
    ("dev", "actor", "Developer", "or admin", "you"),
    ("rt", "participant", "GovernorConnector", "governor-connector.jar", "you"),
    ("con", "participant", "Camunda Console", "secrets, API clients", "saas"),
    ("zb", "participant", "SaaS cluster", "Zeebe", "saas"),
]
DOMAINS = [("you", "YOUR SIDE"), ("saas", "CAMUNDA SAAS")]

FLOW = [
    ("phase", "SET UP", "once, by the admin", [
        ("msg", "dev", "con", "create the cluster secret GOVERNOR_PRICE_TABLE (a JSON price table)", "s"),
        ("msg", "dev", "con", "create an API client with the scopes Orchestration Cluster API and Secrets", "s"),
        ("msg", "con", "dev", "client id and client secret", "rs"),
        ("msg", "dev", "rt", "put client id, secret, cluster id and region into docker/.env, start the runtime", "s"),
        ("msg", "dev", "zb", "deploy the BPMN: the Token cost task uses {{secrets.GOVERNOR_PRICE_TABLE}}", ""),
    ]),
    ("phase", "CONNECT", "the runtime calls out, SaaS never calls in", [
        ("msg", "rt", "con", "sign in with the API client (OAuth), get a token", "s"),
        ("msg", "rt", "zb", "open the job stream for token-cost-governor jobs", ""),
    ]),
    ("phase", "EACH TOKEN COST TASK", "at run time", [
        ("msg", "zb", "rt", "job with the placeholder {{secrets.GOVERNOR_PRICE_TABLE}}", "r"),
        ("msg", "rt", "con", "read GOVERNOR_PRICE_TABLE (audience secrets.camunda.io)", "s"),
        ("msg", "con", "rt", "the price table (JSON)", "rs"),
        ("note", "rt", "con", "401 means the API client has no Secrets scope. Not available means the secret was never "
                              "created: create it, even as [].", "plain"),
        ("self", "rt", "price the tokens: the secret's row first, else the bundled default table", ""),
        ("msg", "rt", "zb", "complete the job with tokenCostResult (cost and tokens)", ""),
    ]),
]

TITLE = "How the connector uses the cluster secret"
SUBTITLE = ""   # the manual page around the figure names the product; the figure keeps a single-line title
ARIA = ("Sequence diagram in four columns: developer or admin, GovernorConnector, Camunda Console, SaaS cluster. The admin "
        "creates the GOVERNOR_PRICE_TABLE cluster secret and an API client, the connector runtime signs in and opens a "
        "job stream to the cluster, and for each Token cost task it reads the secret from the Console, prices the "
        "tokens and completes the job.")

KEY_POINTS = [
    "Every arrow into SaaS starts on your side: the runtime calls out, SaaS never calls in.",
    "The prices you maintain live only in the Console, as the cluster secret. The BPMN holds just the placeholder.",
    "The runtime holds the API client and reads the secret. The connector code never sees Console credentials.",
]


def footer(width):
    """Key points as SVG elements, and their height. Coordinates start at (0, 0)."""
    pal = seq.PALETTE
    out = [f'<line x1="0" y1="0" x2="{width}" y2="0" stroke-width="1" stroke="{pal["line"]}"/>',
           f'<text x="0" y="22" font-size="11" font-weight="bold" letter-spacing="0.12em" fill="{pal["muted"]}">'
           f"KEY POINTS</text>"]
    y = 44
    for point in KEY_POINTS:
        lines = seq.wrap(point, width - 26, 12.5)
        # square bullets, so they cannot be read as the numbered circles on the arrows
        out.append(f'<rect x="3" y="{y - 9:.1f}" width="8" height="8" fill="{pal["muted"]}"/>')
        for k, line in enumerate(lines):
            out.append(f'<text x="26" y="{y + k * 17:.1f}" font-size="12.5" fill="{pal["ink"]}">'
                       f"{html.escape(line, quote=False)}</text>")
        y += len(lines) * 17 + 9
    return out, y


def build():
    seq.configure(participants=PARTICIPANTS, flow=FLOW, domains=DOMAINS, title=TITLE, subtitle=SUBTITLE, aria=ARIA,
                  legend=[("request", False, "ink"), ("reply", True, "ink"), ("credential or secret", False, "secret")],
                  col=156, left=8, compact=True, repeat_boxes=False, title_size=17, header_shift=26, gap_after=11,
                  badge_r=9, badge_font=11)
    width, height, elements = seq.Svg(inline=False).parts()
    avail_w = PAGE_W - 2 * MARGIN
    foot, foot_h = footer(avail_w)
    scale = min(avail_w / width, (PAGE_H - 2 * MARGIN - foot_h - 24) / height)
    x0 = MARGIN + (avail_w - width * scale) / 2
    drawing_bottom = MARGIN + height * scale
    foot_y = drawing_bottom + 24
    pal = seq.PALETTE
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {PAGE_W} {PAGE_H}" width="210mm" height="297mm" '
        f'role="img" aria-label="{html.escape(ARIA, quote=True)}" font-family="Segoe UI, Helvetica, Arial, sans-serif">',
        f"<title>{html.escape(TITLE, quote=False)}</title>",
        f'<rect width="{PAGE_W}" height="{PAGE_H}" fill="{pal["bg"]}"/>',
        f'<g transform="translate({x0:.2f} {MARGIN}) scale({scale:.4f})">',
        *elements,
        "</g>",
        f'<g transform="translate({MARGIN} {foot_y:.2f})">',
        *foot,
        "</g>",
        "</svg>",
    ]
    stats = {"scale": scale, "drawing_bottom": drawing_bottom, "footer_bottom": foot_y + foot_h,
             "smallest_pt": 10.5 * scale * 0.75, "labels_pt": 12 * scale * 0.75}
    return "\n".join(lines) + "\n", stats


def render_png(svg_path, png_path):
    candidates = [r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                  r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
                  shutil.which("msedge") or "", shutil.which("chromium") or "", shutil.which("google-chrome") or ""]
    browser = next((c for c in candidates if c and os.path.exists(c)), None)
    if not browser:
        print("no Edge or Chrome found: PNG not written")
        return
    with tempfile.TemporaryDirectory() as tmp:
        page = os.path.join(tmp, "a4.html")
        uri = "file:///" + os.path.abspath(svg_path).replace("\\", "/")
        with open(page, "w", encoding="utf-8") as f:
            f.write(f'<html><body style="margin:0"><img src="{uri}" style="display:block;width:{PAGE_W}px;'
                    f'height:{PAGE_H}px"></body></html>')
        subprocess.run([browser, "--headless", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=3",
                        f"--screenshot={png_path}", f"--window-size={PAGE_W},{PAGE_H}",
                        "file:///" + page.replace("\\", "/")],
                       check=False, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if os.path.exists(png_path):
        with open(png_path, "rb") as f:
            header = f.read(24)
        w, h = int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")
        print(f"wrote {png_path} ({w} x {h} px, {w / 8.27:.0f} dpi across A4)")
    else:
        print("the browser did not write the PNG")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--png", action="store_true", help="also render docs/sequence-diagram-a4.png with Edge or Chrome")
    ap.add_argument("--out-dir", default=HERE, help="directory for the output files (default: docs/)")
    args = ap.parse_args()
    document, stats = build()
    svg_path = os.path.join(args.out_dir, "sequence-diagram-a4.svg")
    with open(svg_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(document)
    print(f"wrote {svg_path}: scale {stats['scale']:.3f}, drawing ends at {stats['drawing_bottom']:.0f} of {PAGE_H}, "
          f"footer ends at {stats['footer_bottom']:.0f}; labels print at {stats['labels_pt']:.1f} pt, "
          f"smallest text {stats['smallest_pt']:.1f} pt")
    if args.png:
        render_png(svg_path, os.path.join(args.out_dir, "sequence-diagram-a4.png"))


if __name__ == "__main__":
    sys.exit(main())
