#!/usr/bin/env python3
"""Draw the trade-off figures for the README (PLAN.md §10 step 21).

    plot_tradeoffs.py            # writes docs/figures/*.svg, light and dark

Each figure puts accuracy (WER) against one cost -- disk, time to final text,
peak memory -- in three panels: Spanish, clean English, noisy English. The
numbers are the README's published tables, copied into STACKS below, so a
figure can never disagree with the table beside it. The session figure reads
the Phase 5 session rows directly.

Plain SVG, no plotting library: a handful of scatter panels and lines, and
the marks follow one spec (8 px markers with a 2 px surface ring, hairline
grid, text in ink rather than series colour). Each figure is written twice,
for GitHub's light and dark themes; the README picks with <picture>.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "figures"

# ── Theme ────────────────────────────────────────────────────────────────
THEMES = {
    "light": dict(surface="#fcfcfb", ink="#0b0b0b", ink2="#52514e", muted="#898781",
                  grid="#e1e0d9", axis="#c3c2b7", parakeet="#2a78d6",
                  moonshine="#eb6834", other="#898781", ref="#d03b3b"),
    "dark": dict(surface="#1a1a19", ink="#ffffff", ink2="#c3c2b7", muted="#898781",
                 grid="#2c2c2a", axis="#383835", parakeet="#3987e5",
                 moonshine="#d95926", other="#898781", ref="#d03b3b"),
}
FONT = 'system-ui, -apple-system, "Segoe UI", sans-serif'

# ── Data: the README's tables ────────────────────────────────────────────
# Per stack and audio condition: (WER %, final text ms, clips). Final text is
# the median from the actual end of the audio, clamped at zero. Peak RSS is
# per model; Arm A's recognizer runs in Google's process, so it has none.
# English WER is level-matched FLEURS (clean) and LibriSpeech test-other
# (noisy); Whisper and Moonshine tiny were measured on the original 20 clips
# per source only.
STACKS = [
    # name, family, disk MB, peak RSS MB, {cond: (wer, final_ms, clips)}
    ("Platform (A)", "other", 0.0, None,
     {"es": (7.39, 0, 100), "en": (9.72, 69, 100), "noisy": (27.09, 81, 100)}),
    ("Moonshine tiny", "moonshine", 77.7, 475,
     {"en": (11.25, 0, 20), "noisy": (14.43, 84, 20)}),
    ("Moonshine small", "moonshine", 224.1, 760,
     {"en": (7.36, 222, 100), "noisy": (8.92, 486, 100)}),
    ("Moonshine medium", "moonshine", 416.0, 967,
     {"en": (4.71, 539, 100), "noisy": (7.02, 772, 100)}),
    ("Moonshine small-es", "moonshine", 121.8, 640,
     {"es": (7.24, 0, 100)}),
    ("base-es (C)", "other", 64.8, 872,
     {"es": (5.09, 2504, 100)}),
    ("Parakeet", "parakeet", 670.5, 1034,
     {"es": (4.47, 0, 100), "en": (4.97, 250, 100), "noisy": (9.34, 349, 100)}),
    ("Whisper base", "other", 160.6, 572,
     {"es": (14.73, 471, 20), "en": (11.88, 618, 20), "noisy": (22.82, 678, 20)}),
    ("Whisper small", "other", 375.4, 997,
     {"es": (11.24, 2750, 20), "en": (5.31, 2683, 20), "noisy": (14.43, 2424, 20)}),
]
PANELS = [("es", "Spanish"), ("en", "English, clean"), ("noisy", "English, noisy")]

# Where a label may sit relative to its marker, in order of preference:
# (dx, dy, anchor). Placement is greedy; see place_labels().
LABEL_SPOTS = [(9, 4, "start"), (0, -11, "middle"), (0, 19, "middle"),
               (-9, 4, "end"), (6, -9, "start"), (-6, -9, "end"),
               (6, 17, "start"), (-6, 17, "end"), (0, -17, "middle"),
               (0, 25, "middle"), (-3, -21, "start"), (3, -21, "end"),
               (-3, 29, "start"), (3, 29, "end")]
LABEL_CHAR_W, LABEL_H = 6.1, 11   # 11 px system sans, estimated


def place_labels(pts: list[tuple], xmin: float, xmax: float,
                 ymin: float, ymax: float) -> dict[str, tuple[int, int, str]]:
    """Put each label where it overlaps no other label or marker.

    `pts` are (name, x, y) in draw order of priority. The first spot that
    stays inside [xmin, xmax] x [ymin, ymax] and clears everything placed so
    far wins; failing that, the spot with the least overlap.
    """
    marks = [(x - 8, y - 8, x + 8, y + 8) for _, x, y in pts]
    boxes: list[tuple] = []
    out = {}

    def box(x, y, name, spot):
        dx, dy, anchor = spot
        w = len(name) * LABEL_CHAR_W
        x0 = {"start": x + dx, "middle": x + dx - w / 2, "end": x + dx - w}[anchor]
        return (x0, y + dy - LABEL_H + 2, x0 + w, y + dy + 2)

    def dist(r, x, y):
        dx = max(r[0] - x, 0, x - r[2])
        dy = max(r[1] - y, 0, y - r[3])
        return (dx * dx + dy * dy) ** 0.5

    def ambiguous(r, i):
        # A label nearer another marker than its own reads as that one's.
        own = dist(r, pts[i][1], pts[i][2])
        return sum(200 for j, (_, x, y) in enumerate(pts)
                   if j != i and dist(r, x, y) < own + 4)

    def overlap(r, others):
        return sum(max(0, min(r[2], o[2]) - max(r[0], o[0])) *
                   max(0, min(r[3], o[3]) - max(r[1], o[1])) for o in others)

    for i, (name, x, y) in enumerate(pts):
        best, best_cost = None, None
        for spot in LABEL_SPOTS:
            r = box(x, y, name, spot)
            outside = (max(0, xmin - r[0]) + max(0, r[2] - xmax) +
                       max(0, ymin - r[1]) + max(0, r[3] - ymax)) * 100
            cost = (outside + overlap(r, boxes) + ambiguous(r, i)
                    + overlap(r, marks[:i] + marks[i + 1:]))
            if best_cost is None or cost < best_cost:
                best, best_cost = spot, cost
            if cost == 0:
                break
        boxes.append(box(x, y, name, best))
        out[name] = best
    return out


# ── SVG helpers ──────────────────────────────────────────────────────────
def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


class Svg:
    def __init__(self, w: int, h: int, t: dict, title: str, desc: str):
        self.t = t
        self.parts = [
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
            f'viewBox="0 0 {w} {h}" role="img" font-family=\'{FONT}\'>',
            f"<title>{esc(title)}</title><desc>{esc(desc)}</desc>",
            f'<rect width="{w}" height="{h}" fill="{t["surface"]}"/>',
        ]

    def add(self, s: str) -> None:
        self.parts.append(s)

    def text(self, x, y, s, size=12, fill=None, anchor="start", weight="normal",
             tabular=False):
        style = ' style="font-variant-numeric: tabular-nums"' if tabular else ""
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" '
                 f'fill="{fill or self.t["ink2"]}" text-anchor="{anchor}" '
                 f'font-weight="{weight}"{style}>{esc(s)}</text>')

    def line(self, x1, y1, x2, y2, stroke, width=1, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.add(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
                 f'stroke="{stroke}" stroke-width="{width}"{d}/>')

    def dot(self, x, y, color, hollow=False, r=4.5):
        # 2 px surface ring so overlapping markers stay separable.
        self.add(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r + 2}" fill="{self.t["surface"]}"/>')
        if hollow:
            self.add(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r - 1}" fill="{self.t["surface"]}" '
                     f'stroke="{color}" stroke-width="2"/>')
        else:
            self.add(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r}" fill="{color}"/>')

    def polyline(self, pts, color, width=2):
        d = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        self.add(f'<polyline points="{d}" fill="none" stroke="{color}" '
                 f'stroke-width="{width}" stroke-linejoin="round" stroke-linecap="round"/>')

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(self.parts + ["</svg>"]) + "\n", encoding="utf-8")


def nice_ticks(hi: float, n: int = 5) -> list[float]:
    for step in (1, 2, 5, 10, 20, 50, 100, 200, 250, 500, 1000):
        if hi / step <= n:
            return [i * step for i in range(int(hi // step) + 1)]
    return [0, hi]


def legend(svg: Svg, x: float, y: float) -> None:
    t = svg.t
    items = [("Parakeet (one model, both languages)", t["parakeet"], False),
             ("Moonshine streaming (one model per language)", t["moonshine"], False),
             ("Other stacks", t["other"], False),
             ("hollow: 20 clips (the rest: 100)", t["other"], True)]
    for label, color, hollow in items:
        svg.dot(x + 5, y - 4, color, hollow=hollow)
        svg.text(x + 16, y, label, size=12)
        x += 32 + len(label) * 6.3


# ── Trade-off figures ────────────────────────────────────────────────────
def tradeoff(key: str, cost: str, xmax: float, xlabel: str, theme: str,
             title: str, xmin: float = 0.0) -> None:
    t = THEMES[theme]
    pw, ph = 290, 290                     # plot area per panel
    left, top, gap = 56, 78, 34
    w = left + 3 * pw + 2 * gap + 24
    h = top + ph + 64
    ymax = 30.0
    svg = Svg(w, h, t, title,
              f"WER against {xlabel.lower()} for every stack, in three panels: "
              "Spanish, clean English and noisy English. Lower and further left is better.")
    svg.text(left - 40, 26, title, size=16, fill=t["ink"], weight="bold")
    legend(svg, left - 40, 52)

    for i, (cond, name) in enumerate(PANELS):
        x0 = left + i * (pw + gap)
        X = lambda v: x0 + (v - xmin) / (xmax - xmin) * pw   # noqa: E731
        Y = lambda v: top + ph - v / ymax * ph    # noqa: E731
        svg.text(x0, top - 10, name, size=13, fill=t["ink"], weight="bold")
        for yt in nice_ticks(ymax, 6):
            svg.line(x0, Y(yt), x0 + pw, Y(yt), t["grid"])
            if i == 0:
                svg.text(x0 - 8, Y(yt) + 4, f"{yt:g}%", size=11, fill=t["muted"],
                         anchor="end", tabular=True)
        svg.line(x0, Y(0), x0 + pw, Y(0), t["axis"])
        for xt in nice_ticks(xmax, 4):
            if xt < xmin:
                continue
            svg.text(X(xt), top + ph + 18, f"{xt:g}", size=11, fill=t["muted"],
                     anchor="middle", tabular=True)
        svg.text(x0 + pw / 2, top + ph + 38, xlabel, size=12, anchor="middle")

        pts = []
        for sname, fam, disk, rss, conds in STACKS:
            if cond not in conds:
                continue
            wer, final_ms, clips = conds[cond]
            xv = {"disk": disk, "final": final_ms, "rss": rss}[cost]
            if xv is None:
                continue
            pts.append((sname, fam, X(xv), Y(wer), clips))
        # Gray first, accents on top; labels placed accents first.
        order = {"other": 0, "moonshine": 1, "parakeet": 2}
        pts.sort(key=lambda p: order[p[1]])
        for sname, fam, px, py, clips in pts:
            svg.dot(px, py, t[fam], hollow=clips < 100)
        spots = place_labels([(n, px, py) for n, _, px, py, _ in reversed(pts)],
                             x0 - 4, x0 + pw + gap / 2 - 2, top + 2, top + ph - 2)
        for sname, fam, px, py, clips in pts:
            dx, dy, anchor = spots[sname]
            svg.text(px + dx, py + dy, sname, size=11,
                     fill=t["ink"] if fam == "parakeet" else t["ink2"],
                     anchor=anchor, weight="bold" if fam == "parakeet" else "normal")
    svg.save(OUT / f"{key}-{theme}.svg")


# ── Session figure ───────────────────────────────────────────────────────
SESSION_SERIES = [  # variant, label, colour key
    ("parakeet-tdt-v3-int8", "Parakeet", "parakeet"),
    ("moonshine-small-en", "Moonshine small", "moonshine"),
    ("moonshine-small-es", "Moonshine small-es", "moonshine"),
    ("moonshine-medium-en", "Moonshine medium", "medium"),
]


def session_rows() -> dict:
    """{(variant, lang): timeline windows} from the phone's session runs."""
    sys.path.insert(0, str(ROOT / "scripts"))
    from summarize import RESULTS, result_paths
    out = {}
    for path in result_paths([RESULTS]):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            continue
        if (doc.get("device") or {}).get("is_emulator"):
            continue
        for r in doc.get("runs", []):
            w = (r.get("extra") or {}).get("timeline")
            if r.get("bucket") == "session" and w:
                out[(r["extra"]["variant"], r["lang"])] = w
    return out


def sessions(theme: str) -> None:
    t = dict(THEMES[theme])
    t["medium"] = "#1baf7a" if theme == "light" else "#199e70"
    rows = session_rows()
    pw, ph, left, top, gap = 420, 250, 56, 84, 30
    w, h = left + 2 * pw + gap + 16, top + ph + 64
    # x runs past the session's end to leave room for the end labels.
    ymax, xmax = 1.2, 8.4
    svg = Svg(w, h, t, "Six-minute sessions: compute per 30 seconds",
              "Share of real time each stack spent computing, per 30 s of audio, "
              "across one continuous 6-minute session per language. Above 1.0 a "
              "stack falls behind the microphone. No stack came near it.")
    svg.text(left - 40, 26, "Six minutes of continuous speech: share of real time spent computing",
             size=16, fill=t["ink"], weight="bold")
    svg.text(left - 40, 46, "Per 30 s of audio, one session per language. Above 1.0 the "
             "stack falls behind the microphone for good.", size=12)
    for i, (lang, name) in enumerate([("en", "English session"), ("es", "Spanish session")]):
        x0 = left + i * (pw + gap)
        X = lambda v: x0 + v / xmax * pw          # noqa: E731
        Y = lambda v: top + ph - v / ymax * ph    # noqa: E731
        svg.text(x0, top - 10, name, size=13, fill=t["ink"], weight="bold")
        plot_w = X(6.2) - x0
        for yt in (0, 0.2, 0.4, 0.6, 0.8, 1.0, 1.2):
            svg.line(x0, Y(yt), x0 + plot_w, Y(yt), t["grid"])
            if i == 0:
                svg.text(x0 - 8, Y(yt) + 4, f"{yt:.1f}", size=11, fill=t["muted"],
                         anchor="end", tabular=True)
        svg.line(x0, Y(0), x0 + plot_w, Y(0), t["axis"])
        svg.line(x0, Y(1.0), x0 + plot_w, Y(1.0), t["ink2"], width=1, dash="4 3")
        svg.text(x0 + plot_w, Y(1.0) - 6, "real time", size=11, anchor="end")
        for m in range(0, 7):
            svg.text(X(m), top + ph + 18, f"{m}", size=11, fill=t["muted"],
                     anchor="middle", tabular=True)
        svg.text(x0 + plot_w / 2, top + ph + 38, "Minutes into the session", size=12,
                 anchor="middle")
        ends = []
        for variant, label, key in SESSION_SERIES:
            win = rows.get((variant, lang))
            if not win:
                continue
            # The last window is partial (3-11 s): its rate is noise.
            full = [x for j, x in enumerate(win)
                    if j == 0 or x["audio_ms"] - win[j - 1]["audio_ms"] >= 30_000]
            pts = [(X(x["audio_ms"] / 60000), Y(x["rtf"])) for x in full]
            svg.polyline(pts, t[key])
            ends.append((pts[-1][1], label, t[key], pts[-1][0]))
        # Direct labels at the right end, nudged apart vertically.
        ends.sort()
        last = -99.0
        for y, label, color, x in ends:
            y = max(y, last + 14)
            last = y
            svg.text(x + 6, y + 4, label, size=11, fill=t["ink2"])
    svg.save(OUT / f"sessions-{theme}.svg")


def main() -> int:
    for theme in THEMES:
        sessions(theme)
        tradeoff("wer-disk", "disk", 700, "Disk, MB", theme,
                 "Accuracy against size on disk")
        tradeoff("wer-final", "final", 2800, "Time to final text after speech ends, ms",
                 theme, "Accuracy against time to final text")
        # A scatter needs no zero on x; from 300 MB the 1 GB cluster spreads.
        tradeoff("wer-rss", "rss", 1100, "Peak memory (RSS), MB", theme,
                 "Accuracy against peak memory", xmin=300)
    print(f"wrote {len(list(OUT.glob('*.svg')))} figures -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
