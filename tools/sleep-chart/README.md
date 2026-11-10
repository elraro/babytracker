# Sleep chart

A PNG with one row per day: sleep, awake time between sleeps, and bottles, from
00:00 to 24:00 in the local time the events were recorded in, with a panel of trends
underneath. English or Spanish.

## 1. Export (on the server)

From the directory with `docker-compose.yml`:

```sh
/path/to/export.sh > babytracker-export.csv            # one baby
/path/to/export.sh <baby uuid> > babytracker-export.csv   # several babies
```

The CSV has no names, notes or IDs, but it does contain real dates and times. Keep it
out of Git (the repository root ignores `*.csv` and `*.png`) and out of shared places.

## 2. Draw (anywhere with [uv](https://docs.astral.sh/uv/))

```sh
uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sleep-chart.png
uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sueno.png --lang es
```

Options: `--lang en|es`, `--theme dark|light`, `--no-bottles`, `--no-trends`, `--dpi N`. uv installs matplotlib in a
throw-away environment; with plain Python, `pip install matplotlib` and run it with `python3`.

## What it shows, and what it does not

- **Awake** is the gap between two recorded sleeps. The app does not record crying.
- Rows are calendar days, so a night is split at midnight. The longest stretch is
  counted on the merged night, not on the two halves.
- "Day N" is days since birth (the birth date is day 0); "3 MONTHS" marks the row of the
  monthly birthday on which the baby turns 3 months old (calendar months).
- Grey ("no data") is the time before the first and after the last record. A sleep
  that was never ended counts as lasting until the last record.
- The averages in the subtitle use complete days only; the first and last days are
  partial and their totals are shown in italics.
- Colours: sleep/awake are the validated blue/yellow pair for each theme.

## The trends panel

Every number is computed by the script from the export; the text only reports it.

- **Period:** complete calendar days (the first and last day of the export are left out),
  split into two equal halves; with an odd count the middle day is in neither. Each row
  shows the median of each half. At least 10 days and 5 values per half are needed.
- **Change:** Spearman's rank correlation of the measure with the date, over all days,
  with a two-sided permutation p-value (4000 shuffles, fixed seed). p < 0.02 "clear",
  < 0.06 "likely", < 0.15 "possible", otherwise "no clear change". About ten measures are
  tested at once, so a "possible" one can be chance.
- **Nights come from clock times, not labels:** bedtime is the first sleep starting
  18:00–03:00 that is labelled night or lasts 4 h or more; the night then holds every
  sleep that starts before 07:00, plus sleeps labelled night that start before 09:00.
  Night wakings are the gaps inside it. Everything else is a nap.
- **Wake window:** the median gap between sleeps that end 05:00–21:00 on that day.
- **Late naps:** days with 90 min or more of naps starting from 15:00, against the rest,
  compared on the following night's longest stretch. Shown only when both groups have at
  least 5 days. It is an association, not a cause.
