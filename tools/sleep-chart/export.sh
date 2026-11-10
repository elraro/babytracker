#!/bin/sh
# Export sleeps and bottles for sleep_chart.py. Run on the server, in the directory
# that holds docker-compose.yml (or pass it as BT_COMPOSE_DIR):
#
#   ./export.sh > babytracker-export.csv
#   ./export.sh <baby id> > babytracker-export.csv     # when the family has several babies
#
# The CSV has no names, notes or IDs, but it does hold real dates and times:
# keep it out of Git and out of shared places.
set -eu

baby="${1:-}"
case "$baby" in
  "" | [0-9a-fA-F-]*) ;;
  *) echo "usage: $0 [baby uuid]" >&2; exit 2 ;;
esac
case "$baby" in *[!0-9a-fA-F-]*) echo "not a UUID: $baby" >&2; exit 2 ;; esac

cd "${BT_COMPOSE_DIR:-.}"
docker compose exec -T db sh -c 'psql -X -q -v ON_ERROR_STOP=1 -v baby="$0" -U "$POSTGRES_USER" -d "$POSTGRES_DB"' "$baby" <<'SQL'
COPY (
  SELECT
    e.type,
    e.sleep_kind,
    e.amount_ml,
    (e.started_at AT TIME ZONE e.timezone)                         AS start_local,
    (e.ended_at   AT TIME ZONE e.timezone)                         AS end_local,
    e.timezone,
    (e.started_at AT TIME ZONE e.timezone)::date - b.date_of_birth AS day_of_life
  FROM events e
  JOIN babies b ON b.id = e.baby_id AND b.family_id = e.family_id
  WHERE e.deleted_at IS NULL
    AND b.deleted_at IS NULL
    AND e.type IN ('sleep', 'bottle')
    AND (:'baby' = '' OR b.id::text = :'baby')
  ORDER BY e.started_at
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL
