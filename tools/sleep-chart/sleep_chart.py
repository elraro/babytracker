# /// script
# requires-python = ">=3.10"
# dependencies = ["matplotlib>=3.8"]
# ///
"""Draw one row per day of sleep, awake time and bottles from a lactAPP export,
with a panel of trends underneath.

    uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sleep.png
    uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sueno.png --lang es

The CSV comes from tools/sleep-chart/export.sh (see README.md next to this file).
Awake time is the gap between recorded sleeps; nothing in the app records crying.
Rows are calendar days in the local time the events were recorded in, so a night
is split at midnight. Hours before the first and after the last record are "no data".
Every number in the trends panel is computed here from the export; the wording only
reports it (see README.md for the definitions and thresholds).
"""

from __future__ import annotations

import argparse
import calendar
import csv
import random
import statistics as st
import sys
import textwrap
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
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

TEXT = {
    "en": {
        "title": "Sleep and wake, one day at a time",
        "days": "Days {a}–{b} of life",
        "local": "local time ({zones})",
        "average": "Average over {n} complete days: {sleep} of sleep, longest stretch {longest}, {ml} ml of milk",
        "sleep": "Sleep", "awake": "Awake (between sleeps)", "bottle": "Bottle", "nodata": "No data",
        "time_of_day": "Time of day",
        "day": "Day {n}",
        "month": ("{n} MONTH", "{n} MONTHS"),
        "months": "Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec",
        "footer": "Crying is not recorded. Nights are split at midnight. "
                  "Partial days (first and last) are in italics and left out of the averages.",
        "trends": "Trends: {a} vs {b}",
        "col_first": "First half", "col_second": "Second half", "col_verdict": "Change",
        "wake_window": "Wake window (median)",
        "naps": "Naps per day",
        "nap_time": "Nap time per day",
        "bottles": "Bottles per day",
        "milk_day": "Milk per day",
        "milk_bottle": "Milk per bottle",
        "sleep_day": "Sleep per day",
        "bedtime": "Bedtime",
        "wake_up": "Morning wake-up",
        "longest": "Longest night stretch",
        "wakings": "Night wakings",
        "clear_up": "clear increase", "clear_down": "clear decrease",
        "likely_up": "likely increase", "likely_down": "likely decrease",
        "possible_up": "possible increase", "possible_down": "possible decrease",
        "later": "later", "earlier": "earlier", "clear_later": "clearly later", "clear_earlier": "clearly earlier",
        "likely_later": "likely later", "likely_earlier": "likely earlier",
        "possible_later": "possibly later", "possible_earlier": "possibly earlier",
        "none": "no clear change",
        "late_naps": "After days with {min} min or more of naps starting from {hour}:00 ({n} days), the longest "
                     "night stretch was {a} (median), against {b} after the other {m} days. "
                     "This is an association, not proof of cause.",
        "caveat": "{n} days of records. Medians of each half; the change column comes from a rank correlation "
                  "with the date. With this many measures, one or two can look like a change by chance. "
                  "Patterns in your records, not a medical assessment.",
    },
    "es": {
        "title": "Sueño y vigilia, día a día",
        "days": "Días {a}–{b} de vida",
        "local": "hora local ({zones})",
        "average": "Media de {n} días completos: {sleep} de sueño, tramo más largo {longest}, {ml} ml de leche",
        "sleep": "Sueño", "awake": "Vigilia (entre sueños)", "bottle": "Biberón", "nodata": "Sin datos",
        "time_of_day": "Hora del día",
        "day": "Día {n}",
        "month": ("{n} MES", "{n} MESES"),
        "months": "ene feb mar abr may jun jul ago sep oct nov dic",
        "footer": "El llanto no se registra. Las noches se cortan a medianoche. "
                  "Los días incompletos (primero y último) van en cursiva y no cuentan en las medias.",
        "trends": "Tendencias: {a} frente a {b}",
        "col_first": "Primera mitad", "col_second": "Segunda mitad", "col_verdict": "Cambio",
        "wake_window": "Ventana de vigilia (mediana)",
        "naps": "Siestas al día",
        "nap_time": "Tiempo de siesta al día",
        "bottles": "Biberones al día",
        "milk_day": "Leche al día",
        "milk_bottle": "Leche por biberón",
        "sleep_day": "Sueño al día",
        "bedtime": "Hora de acostarse",
        "wake_up": "Hora de despertarse",
        "longest": "Tramo nocturno más largo",
        "wakings": "Despertares nocturnos",
        "clear_up": "aumento claro", "clear_down": "descenso claro",
        "likely_up": "aumento probable", "likely_down": "descenso probable",
        "possible_up": "posible aumento", "possible_down": "posible descenso",
        "later": "más tarde", "earlier": "más temprano", "clear_later": "claramente más tarde",
        "clear_earlier": "claramente más temprano", "likely_later": "probablemente más tarde",
        "likely_earlier": "probablemente más temprano", "possible_later": "quizá más tarde",
        "possible_earlier": "quizá más temprano",
        "none": "sin cambio claro",
        "late_naps": "Tras los días con {min} min o más de siesta empezada a partir de las {hour}:00 ({n} días), "
                     "el tramo nocturno más largo fue de {a} (mediana), frente a {b} tras los otros {m} días. "
                     "Es una asociación, no prueba que una cosa cause la otra.",
        "caveat": "{n} días de registros. Medianas de cada mitad; la columna de cambio sale de una correlación "
                  "de rangos con la fecha. Con tantas medidas, una o dos pueden parecer un cambio por azar. "
                  "Son patrones de tus registros, no una valoración médica.",
    },
}

# Trend thresholds on the permutation p-value of Spearman's rho against the date.
CLEAR, LIKELY, POSSIBLE = 0.02, 0.06, 0.15
LATE_NAP_HOUR, LATE_NAP_MIN = 15, 90
MIN_GROUP = 5
PERMUTATIONS = 4000


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


def read(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit(f"{path}: no rows")
    missing = {"type", "sleep_kind", "amount_ml", "start_local", "end_local", "day_of_life"} - set(rows[0])
    if missing:
        sys.exit(f"{path}: missing columns {sorted(missing)}; export it with export.sh")
    return rows


def load(rows: list[dict]):
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


# ---- trends ----------------------------------------------------------------------


def _mins(d: datetime) -> float:
    return d.hour * 60 + d.minute + d.second / 60


def _evening(d: datetime) -> float:
    """Minutes after noon, so a bedtime after midnight sorts after one before it."""
    m = _mins(d)
    return m - 720 if m >= 720 else m + 720


def _rank(v: list[float]) -> list[float]:
    order = sorted(range(len(v)), key=lambda i: v[i])
    r = [0.0] * len(v)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and v[order[j + 1]] == v[order[i]]:
            j += 1
        for k in range(i, j + 1):
            r[order[k]] = (i + j) / 2
        i = j + 1
    return r


def _pearson(x: list[float], y: list[float]) -> float:
    mx, my = st.mean(x), st.mean(y)
    sx = sum((a - mx) ** 2 for a in x) ** 0.5
    sy = sum((b - my) ** 2 for b in y) ** 0.5
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / (sx * sy) if sx and sy else 0.0


def spearman(x: list[float], y: list[float]) -> tuple[float, float]:
    """Spearman's rho and a two-sided permutation p-value (fixed seed, so reruns agree)."""
    rx, ry = _rank(x), _rank(y)
    rho = _pearson(rx, ry)
    rng = random.Random(1)
    hits = 0
    for _ in range(PERMUTATIONS):
        rng.shuffle(ry)
        if abs(_pearson(rx, ry)) >= abs(rho) - 1e-12:
            hits += 1
    return rho, (hits + 1) / (PERMUTATIONS + 1)


@dataclass
class Trend:
    key: str
    first: float
    second: float
    rho: float
    p: float
    kind: str  # "duration", "count", "ml", "clock", "evening"


@dataclass
class Trends:
    first_range: tuple[date, date]
    second_range: tuple[date, date]
    days: int
    rows: list[Trend]
    late_naps: tuple[int, float, int, float] | None  # (n late days, median, n other days, median)


def trends(rows: list[dict]) -> Trends | None:
    """Daily and nightly measures, each compared between the two halves of the period."""
    parse = datetime.fromisoformat
    sleeps = sorted(
        (parse(r["start_local"]), parse(r["end_local"]), r["sleep_kind"])
        for r in rows if r["type"] == "sleep" and r["end_local"]
    )
    bottles = sorted((parse(r["start_local"]), int(r["amount_ml"])) for r in rows if r["type"] == "bottle")
    if len(sleeps) < 10:
        return None
    span_end = max([e for _, e, _ in sleeps] + [b for b, _ in bottles])

    # Nights from clock times, not labels: bedtime is the first sleep starting 18:00-03:00
    # that is labelled night or lasts 4 h or more; the night then holds every sleep that
    # starts before 07:00 next morning, plus any sleep labelled night starting before 09:00.
    nights, in_night, i = {}, set(), 0
    while i < len(sleeps):
        s, e, k = sleeps[i]
        if not ((s.hour >= 18 or s.hour < 3) and (k == "night" or e - s >= timedelta(hours=4))):
            i += 1
            continue
        evening = (s - timedelta(hours=12)).date()
        morning = datetime.combine(evening + timedelta(days=1), datetime.min.time())
        part, j = [(s, e)], i + 1
        in_night.add(i)
        while j < len(sleeps):
            s2, e2, k2 = sleeps[j]
            if s2 < morning + timedelta(hours=7) or (k2 == "night" and s2 < morning + timedelta(hours=9)):
                part.append((s2, e2))
                in_night.add(j)
                j += 1
            else:
                break
        bed, wake = part[0][0], part[-1][1]
        if wake < span_end - timedelta(hours=1) and (wake - bed) > timedelta(hours=6):  # finished nights only
            nights[evening] = {
                "bedtime": _evening(bed), "wake_up": _mins(wake), "wakings": len(part) - 1,
                "longest": max((b - a).total_seconds() / 60 for a, b in part),
            }
        i = j
    naps = [(s, e) for idx, (s, e, _) in enumerate(sleeps) if idx not in in_night]

    # Complete calendar days only: drop the first and the last day of the export.
    first_day = min(s for s, _, _ in sleeps).date() + timedelta(days=1)
    last_day = span_end.date() - timedelta(days=1)
    days = {}
    d = first_day
    while d <= last_day:
        lo = datetime.combine(d, datetime.min.time())
        hi = lo + timedelta(days=1)
        total = sum(max((min(e, hi) - max(s, lo)).total_seconds() / 60, 0) for s, e, _ in sleeps)
        day_naps = [(s, e) for s, e in naps if s.date() == d]
        windows = [
            (s2 - e1).total_seconds() / 60
            for (_, e1, _), (s2, _, _) in zip(sleeps, sleeps[1:])
            if e1.date() == d and 5 <= e1.hour < 21 and s2 > e1
        ]
        ml = [m for b, m in bottles if b.date() == d]
        days[d] = {
            "sleep_day": total,
            "naps": len(day_naps),
            "nap_time": sum((e - s).total_seconds() / 60 for s, e in day_naps),
            "late_nap": sum((e - s).total_seconds() / 60 for s, e in day_naps if s.hour >= LATE_NAP_HOUR),
            "wake_window": st.median(windows) if windows else None,
            "bottles": len(ml),
            "milk_day": sum(ml),
            "milk_bottle": st.mean(ml) if ml else None,
        }
        d += timedelta(days=1)
    if len(days) < 2 * MIN_GROUP:
        return None

    ordered = sorted(days)
    half = len(ordered) // 2
    first_half, second_half = set(ordered[:half]), set(ordered[-half:])

    def trend(key: str, source: dict, kind: str) -> Trend | None:
        pts = [(d, v[key]) for d, v in sorted(source.items()) if v[key] is not None]
        if len(pts) < 2 * MIN_GROUP:
            return None
        a = [v for d, v in pts if d in first_half]
        b = [v for d, v in pts if d in second_half]
        if len(a) < MIN_GROUP or len(b) < MIN_GROUP:
            return None
        rho, p = spearman([d.toordinal() for d, _ in pts], [v for _, v in pts])
        return Trend(key, st.median(a), st.median(b), rho, p, kind)

    rows_out = [
        trend("wake_window", days, "duration"),
        trend("naps", days, "count"),
        trend("nap_time", days, "duration"),
        trend("bottles", days, "count"),
        trend("milk_day", days, "ml"),
        trend("milk_bottle", days, "ml"),
        trend("sleep_day", days, "duration"),
        trend("bedtime", nights, "evening"),
        trend("wake_up", nights, "clock"),
        trend("longest", nights, "duration"),
        trend("wakings", nights, "count"),
    ]

    late = None
    late_days = [nights[d]["longest"] for d in ordered if d in nights and days[d]["late_nap"] >= LATE_NAP_MIN]
    other_days = [nights[d]["longest"] for d in ordered if d in nights and days[d]["late_nap"] < LATE_NAP_MIN]
    if len(late_days) >= MIN_GROUP and len(other_days) >= MIN_GROUP:
        late = (len(late_days), st.median(late_days), len(other_days), st.median(other_days))

    return Trends(
        (ordered[0], ordered[half - 1]), (ordered[-half], ordered[-1]), len(days),
        [r for r in rows_out if r is not None], late,
    )


# ---- formatting ------------------------------------------------------------------


def hm(m: float) -> str:
    m = round(m)
    return f"{m // 60}:{m % 60:02d}"


def duration(m: float) -> str:
    m = round(m)
    h, r = divmod(m, 60)
    return f"{h} h {r:02d} min" if h else f"{r} min"


def age_in_months(birth: date, day: date) -> int:
    """Completed calendar months; a birthday on the 31st falls on the last day of shorter months."""
    months = (day.year - birth.year) * 12 + day.month - birth.month
    anniversary = min(birth.day, calendar.monthrange(day.year, day.month)[1])
    return months - (day.day < anniversary)


class Fmt:
    def __init__(self, lang: str):
        self.t = TEXT[lang]
        self.lang = lang
        self.month_names = self.t["months"].split()

    def date(self, d: date | datetime, year: bool = False) -> str:
        s = f"{d.day} {self.month_names[d.month - 1]}"
        return f"{s} {d.year}" if year else s

    def number(self, v: float) -> str:
        s = f"{v:.0f}" if abs(v - round(v)) < 0.05 else f"{v:.1f}"
        return s.replace(".", ",") if self.lang == "es" else s

    def value(self, v: float, kind: str) -> str:
        if kind == "duration":
            return duration(v)
        if kind == "ml":
            return f"{v:.0f} ml"
        if kind == "clock":
            return hm(v)
        if kind == "evening":
            return hm((v + 720) % DAY)
        return self.number(v)

    def verdict(self, tr: Trend) -> str:
        if tr.p >= POSSIBLE:
            return self.t["none"]
        level = "clear" if tr.p < CLEAR else "likely" if tr.p < LIKELY else "possible"
        if tr.kind in ("clock", "evening"):
            return self.t[f"{level}_{'later' if tr.rho > 0 else 'earlier'}"]
        return self.t[f"{level}_{'up' if tr.rho > 0 else 'down'}"]

    def range(self, a: date, b: date) -> str:
        return f"{self.date(a)} – {self.date(b)}"


# ---- drawing ---------------------------------------------------------------------


def draw(days: list[Day], birth_day: int, zones: list[str], tr: Trends | None, out: Path, theme: str,
         bottles: bool, dpi: int, lang: str) -> None:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.lines import Line2D
    from matplotlib.patches import FancyBboxPatch, Patch, Rectangle

    t = THEMES[theme]
    f = Fmt(lang)
    tx = f.t
    plt.rcParams["font.family"] = "DejaVu Sans"
    n = len(days)
    row_h = 0.24  # inches per day
    line = 0.25  # inches per line of the trends panel
    panel = 0.0
    if tr:
        panel = 0.55 + line * (len(tr.rows) + 1) + 0.25 + (0.62 if tr.late_naps else 0) + 0.62
    top, bottom = 1.55, 0.75 + panel
    height = top + bottom + n * row_h
    fig = plt.figure(figsize=(13, height), dpi=dpi, facecolor=t["bg"])
    left, width = 0.155, 0.775
    ax = fig.add_axes((left, bottom / height, width, (n * row_h) / height), facecolor=t["bg"])
    totals_x = 1 + 0.6 / (13 * width)  # right edge of the daily totals, 0.6 in past the plot
    text_x = left - 0.115
    y_in = lambda inches_from_top: 1 - inches_from_top / height  # noqa: E731

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
    ax.set_xlabel(tx["time_of_day"], color=t["muted"], fontsize=9)
    labels = [f"{tx['day'].format(n=d.day - birth_day)}  ·  {f.date(date_of(d.day))}" for d in days]
    ax.set_yticks(range(n), labels)
    ax.tick_params(axis="y", colors=t["text2"], labelsize=8, length=0)
    for s in ax.spines.values():
        s.set_visible(False)

    # Age in completed calendar months, marked on the row where it changes (the monthly birthday).
    birth = date_of(birth_day).date()
    last_month = None
    for i, d in enumerate(days):
        month = age_in_months(birth, date_of(d.day).date())
        if month != last_month:
            label = (tx["month"][0] if month == 1 else tx["month"][1]).format(n=month)
            ax.text(-0.118, i, label, transform=ax.get_yaxis_transform(), ha="right",
                    va="center", fontsize=7.5, fontweight="bold", color=t["text"])
            if last_month is not None:
                ax.axhline(i - 0.5, color=t["muted"], lw=0.6, ls=(0, (2, 3)), xmax=1, zorder=4)
            last_month = month

    ax.text(totals_x, -1.6, tx["sleep"], transform=ax.get_yaxis_transform(), va="bottom", ha="right", fontsize=8,
            color=t["muted"])

    first_age, last_age = days[0].day - birth_day, days[-1].day - birth_day
    full = [d for d in days if d.complete]
    fig.text(text_x, y_in(0.35), tx["title"], fontsize=17, fontweight="bold", color=t["text"], va="top")
    sub = (f"{tx['days'].format(a=first_age, b=last_age)}  ·  "
           f"{f.date(date_of(days[0].day), True)} – {f.date(date_of(days[-1].day), True)}")
    if zones:
        sub += f"  ·  {tx['local'].format(zones=', '.join(zones))}"
    if full:
        sub += "\n" + tx["average"].format(
            n=len(full),
            sleep=duration(sum(d.sleep_min for d in full) / len(full)),
            longest=duration(sum(d.longest for d in full) / len(full)),
            ml=f"{sum(d.ml for d in full) / len(full):.0f}",
        )
    fig.text(text_x, y_in(0.72), sub, fontsize=9, color=t["text2"], va="top", linespacing=1.5)

    handles = [Patch(color=t["sleep"], label=tx["sleep"]), Patch(color=t["awake"], label=tx["awake"])]
    if bottles:
        handles.append(Line2D([], [], color=t["tick"], lw=1.6, label=tx["bottle"]))
    handles.append(Patch(color=t["nodata"], label=tx["nodata"]))
    fig.legend(handles=handles, loc="upper right", bbox_to_anchor=(left + width + 0.04, y_in(0.3)),
               ncol=len(handles), frameon=False, fontsize=8.5, labelcolor=t["text2"], handlelength=1.2,
               columnspacing=1.4)

    chart_bottom = top + n * row_h  # inches from the top to the bottom of the plot
    fig.text(text_x, y_in(chart_bottom + 0.55), tx["footer"], fontsize=7.5, color=t["muted"], va="top")

    if tr:
        y0 = chart_bottom + 0.95
        box_top, box_bottom = y_in(y0 - 0.15), y_in(height - 0.2)
        fig.patches.append(FancyBboxPatch(
            (text_x - 0.01, box_bottom), left + width + 0.065 - text_x, box_top - box_bottom,
            boxstyle="round,pad=0,rounding_size=0.006", transform=fig.transFigure, facecolor=t["panel"],
            edgecolor="none", zorder=-1))
        fig.text(text_x + 0.01, y_in(y0), tx["trends"].format(a=f.range(*tr.first_range), b=f.range(*tr.second_range)),
                 fontsize=11, fontweight="bold", color=t["text"], va="top")
        cols = (text_x + 0.01, text_x + 0.32, text_x + 0.47, text_x + 0.62)
        y = y0 + 0.4
        for x, label in zip(cols, (None, tx["col_first"], tx["col_second"], tx["col_verdict"])):
            if label:
                fig.text(x, y_in(y), label, fontsize=8, color=t["muted"], va="top")
        for r in tr.rows:
            y += line
            verdict = f.verdict(r)
            strong = r.p < LIKELY
            fig.text(cols[0], y_in(y), tx[r.key], fontsize=9, color=t["text2"], va="top")
            fig.text(cols[1], y_in(y), f.value(r.first, r.kind), fontsize=9, color=t["text"], va="top")
            fig.text(cols[2], y_in(y), f.value(r.second, r.kind), fontsize=9, color=t["text"], va="top")
            fig.text(cols[3], y_in(y), verdict, fontsize=9, va="top",
                     color=t["text"] if strong else t["muted"], fontweight="bold" if strong else "normal")
        y += line + 0.2
        if tr.late_naps:
            nl, ml_, no, mo = tr.late_naps
            note = tx["late_naps"].format(
                min=LATE_NAP_MIN, hour=LATE_NAP_HOUR, n=nl, a=duration(ml_), b=duration(mo), m=no)
            # matplotlib's own wrapping uses the figure edge, not the panel, so wrap by hand.
            fig.text(cols[0], y_in(y), textwrap.fill(note, 150), fontsize=9, color=t["text2"], va="top",
                     linespacing=1.4)
            y += 0.62
        fig.text(cols[0], y_in(y), textwrap.fill(tx["caveat"].format(n=tr.days), 165), fontsize=8,
                 color=t["muted"], va="top", linespacing=1.4)

    fig.savefig(out, facecolor=t["bg"])
    plt.close(fig)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("csv", type=Path, help="export from export.sh")
    p.add_argument("-o", "--output", type=Path, default=Path("sleep-chart.png"), help="PNG to write")
    p.add_argument("--lang", choices=sorted(TEXT), default="en")
    p.add_argument("--theme", choices=sorted(THEMES), default="dark")
    p.add_argument("--no-bottles", action="store_true", help="leave out the bottle marks")
    p.add_argument("--no-trends", action="store_true", help="leave out the trends panel")
    p.add_argument("--dpi", type=int, default=200)
    args = p.parse_args()
    rows = read(args.csv)
    days, birth_day, zones = load(rows)
    tr = None if args.no_trends else trends(rows)
    draw(days, birth_day, zones, tr, args.output, args.theme, not args.no_bottles, args.dpi, args.lang)
    print(f"wrote {args.output} ({len(days)} days{'' if tr or args.no_trends else '; too few days for trends'})")


if __name__ == "__main__":
    main()
