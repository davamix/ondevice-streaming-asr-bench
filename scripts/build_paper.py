#!/usr/bin/env python3
"""Build the printable paper: docs/paper/paper.html -> docs/paper/paper.pdf.

    build_paper.py                      # figures, then the PDF
    build_paper.py --browser <path>     # a specific Chrome / Edge / Chromium

The paper is HTML with print CSS (A4, running header, page numbers), printed
to PDF by a headless Chromium browser. Chromium draws the SVG figures as
vectors and honours @page margin boxes, so there is no LaTeX toolchain to
install. The figures are regenerated first (plot_tradeoffs.py writes the
print variants to docs/paper/figures/), so the PDF cannot fall behind them.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PAPER = ROOT / "docs" / "paper"

CANDIDATES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
]


def find_browser(explicit: str | None) -> str:
    if explicit:
        return explicit
    for name in ("chromium", "chromium-browser", "google-chrome", "chrome", "msedge"):
        found = shutil.which(name)
        if found:
            return found
    for path in CANDIDATES:
        if Path(path).is_file():
            return path
    raise SystemExit("no Chromium-based browser found; pass --browser <path>")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--browser", default=os.environ.get("BROWSER"))
    ap.add_argument("--no-figures", action="store_true",
                    help="skip regenerating the figures")
    args = ap.parse_args()

    if not args.no_figures:
        subprocess.run([sys.executable, str(ROOT / "scripts" / "plot_tradeoffs.py")],
                       check=True)

    browser = find_browser(args.browser)
    src = (PAPER / "paper.html").resolve()
    out = (PAPER / "paper.pdf").resolve()
    out.unlink(missing_ok=True)
    subprocess.run([
        browser, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
        "--no-first-run", "--disable-extensions",
        f"--print-to-pdf={out}", src.as_uri(),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not out.is_file():
        raise SystemExit(f"the browser wrote no PDF ({browser})")
    print(f"wrote {out.relative_to(ROOT)} ({out.stat().st_size / 1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
