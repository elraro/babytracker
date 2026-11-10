# /// script
# requires-python = ">=3.10"
# dependencies = ["matplotlib>=3.8"]
# ///
"""Draw one row per day of sleep, awake time and bottles from a lactAPP export.

    uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sleep.png

The CSV comes from tools/sleep-chart/export.sh (see README.md next to this file).
Awake time is the gap between recorded sleeps; nothing in the app records crying.
Rows are calendar days in the local time the events were recorded in, so a night
is split at midnight. Hours before the first and after the last record are "no data".
"""

from __future__ import annotations

import argparse
import csv
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

DAY = 1440  # minutes
EPOCH = datetime(1970, 1, 1)

THEMES = {
    # Validated pair (sleep/awake) for each surface; see README.md.
    "dark": {
        "bg": "#111110", "panel": "#1a1a19", "text": "#f0efec", "text2": "#c3c2b7", "muted": "#8f8e86",
        "sleep": "#3987e5", "awake": "#c98500", "nodata": "#262624", "tick": "#f0efec", "grid": "#111110",
    },
    "light": {
        "bg": "#f4f3f0", "panel": "#fcfcfb", "text": "#0b0b0b", "text2": "#52514e", "muted": "#7a7974",
        "sleep": "#2a78d6", "awake": "#eda100", "nodata": "#e9e8e4", "tick": "#0b0b0b", "grid": "#fcfcfb",
    },
}


def minutes(value: str) -> float:
    """Local wall-clock timestamp from the export -> minutes since 1970 on a naive scale."""
    return (datetime.fromisoformat(value) - EPOCH).total_seconds() / 60


def date_of(day: int) -> datetime:
    return EPOCH + timedelta(days=day)


@dataclass
class Day:
    day: int
    sleep: list[tuple[float, float, str]] = field(default_factory=list)  # (from, to, kind) in minutes of the day
    awake: list[tuple[float, float]] = field(default_factory=list)
    bottles: list[tuple[float, int]] = field(default_factory=list)  # (minute of the day, ml)
    covered: tuple[float, float] = (0, DAY)
    naps: int = 0
    longest: float = 0

    @property
    def sleep_min(self) -> float:
        return sum(b - a for a, b, _ in self.sleep)

    @property
    def complete(self) -> bool:
        return self.covered == (0, DAY)

    @property
    def ml(self) -> int:
        return sum(ml for _, ml in self.bottles)


def split(start: float, end: float):
    """Yield (day, from, to) pieces of an interval, cut at local midnights."""
    d = int(start // DAY)
    while d * DAY < end:
        a, b = max(start, d * DAY), min(end, (d + 1) * DAY)
        if b > a:
            yield d, a - d * DAY, b - d * DAY
        d += 1


def load(path: Path):
    with path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit(f"{path}: no rows")
    missing = {"type", "sleep_kind", "amount_ml", "start_local", "end_local", "day_of_life"} - set(rows[0])
    if missing:
        sys.exit(f"{path}: missing columns {sorted(missing)}; export it with export.sh")

    events = []
    for r in rows:
        start = minutes(r["start_local"])
        end = minutes(r["end_local"]) if r["end_local"] else None
        events.append((r, start, end))
    span_start = min(s for _, s, _ in events)
    span_end = max(e if e is not None else s for _, s, e in events)
    first_row, first_start, _ = min(events, key=lambda x: x[1])
    birth_day = int(first_start // DAY) - int(first_row["day_of_life"])
    zones = sorted({r.get("timezone", "") for r in rows} - {""})

    sleeps = sorted(
        (s, e if e is not None else span_end, r["sleep_kind"]) for r, s, e in events if r["type"] == "sleep"
    )
    bottles = [(s, int(r["amount_ml"])) for r, s, _ in events if r["type"] == "bottle"]

    # Overlapping sleeps are merged before the gaps between them become awake time.
    merged: list[list[float]] = []
    for s, e, _ in sleeps:
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    awake, cur = [], span_start
    for s, e in merged:
        if s > cur:
            awake.append((cur, s))
        cur = max(cur, e)
    if span_end > cur:
        awake.append((cur, span_end))

    first, last = int(span_start // DAY), int(span_end // DAY)
    days = {d: Day(d) for d in range(first, last + 1)}
    for s, e, kind in sleeps:
        for d, a, b in split(s, e):
            days[d].sleep.append((a, b, kind))
        if kind == "nap":
            days[int(s // DAY)].naps += 1
    for s, e in awake:
        for d, a, b in split(s, e):
            days[d].awake.append((a, b))
    for s, ml in bottles:
        days[int(s // DAY)].bottles.append((s - int(s // DAY) * DAY, ml))
    for s, e in merged:  # a night cut by midnight still counts as one stretch
        d = days[int(s // DAY)]
        d.longest = max(d.longest, e - s)
    for d in days.values():
        d.covered = (max(span_start - d.day * DAY, 0), min(span_end - d.day * DAY, DAY))
    return [days[d] for d in sorted(days)], birth_day, zones


def hm(m: float) -> str:
    m = round(m)
    return f"{m // 60}:{m % 60:02d}"


def duration(m: float) -> str:
    m = round(m)
    h, r = divmod(m, 60)
    return f"{h} h {r:02d} min" if h else f"{r} min"


def draw(days: list[Day], birth_day: int, zones: list[str], out: Path, theme: str, bottles: bool, dpi: int) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch, Rectangle
    from matplotlib.lines import Line2D

    t = THEMES[theme]
    n = len(days)
    row_h = 0.24  # inches per day
    top, bottom = 1.55, 0.75
    height = top + bottom + n * row_h
    fig = plt.figure(figsize=(13, height), dpi=dpi, facecolor=t["bg"])
    left, width = 0.155, 0.775
    ax = fig.add_axes((left, bottom / height, width, (n * row_h) / height), facecolor=t["bg"])
    totals_x = 1 + 0.6 / (13 * width)  # right edge of the daily totals, 0.6 in past the plot
    plt.rcParams["font.family"] = "DejaVu Sans"

    bar = 0.78
    for i, d in enumerate(days):
        y = i - bar / 2
        ax.add_patch(Rectangle((0, y), DAY, bar, color=t["nodata"], lw=0))
        for a, b in d.awake:
            ax.add_patch(Rectangle((a, y), b - a, bar, color=t["awake"], lw=0))
        for a, b, _ in d.sleep:
            ax.add_patch(Rectangle((a, y), b - a, bar, color=t["sleep"], lw=0))
        if bottles:
            for m, _ in d.bottles:
                ax.plot([m, m], [y + 0.12, y + bar - 0.12], color=t["panel"], lw=3.2, solid_capstyle="butt")
                ax.plot([m, m], [y + 0.12, y + bar - 0.12], color=t["tick"], lw=1.6, solid_capstyle="butt")
        ax.text(totals_x, i, hm(d.sleep_min), transform=ax.get_yaxis_transform(), va="center", ha="right", fontsize=8,
                color=t["text2"] if d.complete else t["muted"], style="normal" if d.complete else "italic")

    for h in range(2, 24, 2):  # thin hour lines through the bars, on the labelled hours
        ax.axvline(h * 60, color=t["grid"], lw=0.8, alpha=0.5, zorder=3)

    ax.set_xlim(0, DAY)
    ax.set_ylim(n - 0.5, -0.5)
    ticks = list(range(0, DAY + 1, 120))
    ax.set_xticks(ticks, [f"{m // 60:02d}:00" for m in ticks])
    ax.tick_params(axis="x", colors=t["muted"], labelsize=8, length=0, labeltop=True, top=False)
    ax.set_xlabel("Time of day", color=t["muted"], fontsize=9)
    labels = [f"Day {d.day - birth_day}  ·  {date_of(d.day):%d %b}" for d in days]
    ax.set_yticks(range(n), labels)
    ax.tick_params(axis="y", colors=t["text2"], labelsize=8, length=0)
    for s in ax.spines.values():
        s.set_visible(False)

    # Month markers (age in months, 30.44-day months) on the left of the first row of each month.
    last_month = None
    for i, d in enumerate(days):
        month = int((d.day - birth_day) // 30.4375) + 1
        if month != last_month:
            # In its own column left of the day labels, level with the month's first day.
            ax.text(-0.118, i, f"MONTH {month}", transform=ax.get_yaxis_transform(), ha="right",
                    va="center", fontsize=7.5, fontweight="bold", color=t["text"])
            if last_month is not None:
                ax.axhline(i - 0.5, color=t["muted"], lw=0.6, ls=(0, (2, 3)), xmax=1, zorder=4)
            last_month = month

    ax.text(totals_x, -1.6, "Sleep", transform=ax.get_yaxis_transform(), va="bottom", ha="right", fontsize=8,
            color=t["muted"])

    first_age, last_age = days[0].day - birth_day, days[-1].day - birth_day
    full = [d for d in days if d.complete]
    fig.text(left - 0.115, 1 - 0.35 / height, "Sleep and wake, one day at a time", fontsize=17, fontweight="bold",
             color=t["text"], va="top")
    sub = f"Days {first_age}–{last_age} of life  ·  {date_of(days[0].day):%d %b %Y} – {date_of(days[-1].day):%d %b %Y}"
    if zones:
        sub += f"  ·  local time ({', '.join(zones)})"
    if full:
        avg_sleep = sum(d.sleep_min for d in full) / len(full)
        avg_long = sum(d.longest for d in full) / len(full)
        avg_ml = sum(d.ml for d in full) / len(full)
        sub += (f"\nAverage over {len(full)} complete days: {duration(avg_sleep)} of sleep, "
                f"longest stretch {duration(avg_long)}, {avg_ml:.0f} ml of milk")
    fig.text(left - 0.115, 1 - 0.72 / height, sub, fontsize=9, color=t["text2"], va="top", linespacing=1.5)

    handles = [Patch(color=t["sleep"], label="Sleep"), Patch(color=t["awake"], label="Awake (between sleeps)")]
    if bottles:
        handles.append(Line2D([], [], color=t["tick"], lw=1.6, label="Bottle"))
    handles.append(Patch(color=t["nodata"], label="No data"))
    fig.legend(handles=handles, loc="upper right", bbox_to_anchor=(left + width + 0.04, 1 - 0.3 / height), ncol=len(handles),
               frameon=False, fontsize=8.5, labelcolor=t["text2"], handlelength=1.2, columnspacing=1.4)

    fig.text(left - 0.115, 0.18 / height, "Crying is not recorded. Nights are split at midnight. "
             "Partial days (first and last) are in italics and left out of the averages.",
             fontsize=7.5, color=t["muted"])
    fig.savefig(out, facecolor=t["bg"])
    plt.close(fig)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("csv", type=Path, help="export from export.sh")
    p.add_argument("-o", "--output", type=Path, default=Path("sleep-chart.png"), help="PNG to write")
    p.add_argument("--theme", choices=sorted(THEMES), default="dark")
    p.add_argument("--no-bottles", action="store_true", help="leave out the bottle marks")
    p.add_argument("--dpi", type=int, default=200)
    args = p.parse_args()
    days, birth_day, zones = load(args.csv)
    draw(days, birth_day, zones, args.output, args.theme, not args.no_bottles, args.dpi)
    print(f"wrote {args.output} ({len(days)} days)")


if __name__ == "__main__":
    main()
