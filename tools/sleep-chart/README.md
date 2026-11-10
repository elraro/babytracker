# Sleep chart

A PNG with one row per day: sleep, awake time between sleeps, and bottles, from
00:00 to 24:00 in the local time the events were recorded in.

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
uv run tools/sleep-chart/sleep_chart.py babytracker-export.csv -o sleep-light.png --theme light
```

Options: `--theme dark|light`, `--no-bottles`, `--dpi N`. uv installs matplotlib in a
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
