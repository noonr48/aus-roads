#!/usr/bin/env bash
# Build the portable search database shipped beside the Adelaide demo tiles.
# The MBTiles metadata bounds are the clipping source of truth.
#
# Usage: build-bundled-search-asset.sh [SOURCE_DB [OUTPUT_DB [BOUNDS_MBTILES]]]
# Writes OUTPUT_DB and a raw-SHA-256 OUTPUT_DB.sha256 sidecar via atomic renames.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PROJECT_ROOT="$(cd "$ROOT/../.." && pwd)"
SOURCE="${1:-$ROOT/dist/search.db}"
OUTPUT="${2:-$PROJECT_ROOT/android/app/src/main/assets/maptile/adelaide-test-search-v1.db}"
TILES="${3:-$PROJECT_ROOT/android/app/src/main/assets/maptile/adelaide-test-tiles.mbtiles}"

[[ -s "$SOURCE" ]] || { echo "missing source search database: $SOURCE" >&2; exit 1; }
[[ -s "$TILES" ]] || { echo "missing bounds source MBTiles: $TILES" >&2; exit 1; }
command -v sqlite3 >/dev/null || { echo "sqlite3 is required" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }

BOUNDS_RAW="$(sqlite3 "$TILES" "SELECT value FROM metadata WHERE name='bounds' LIMIT 1;")"
[[ -n "$BOUNDS_RAW" ]] || { echo "MBTiles metadata has no bounds: $TILES" >&2; exit 1; }

BOUNDS="$(python3 - "$BOUNDS_RAW" <<'PY'
import math
import sys

raw = sys.argv[1]
try:
    values = tuple(float(value) for value in raw.split(","))
except ValueError as error:
    raise SystemExit(f"invalid MBTiles bounds {raw!r}: {error}")
if len(values) != 4 or not all(math.isfinite(value) for value in values):
    raise SystemExit(f"invalid MBTiles bounds {raw!r}: expected four finite numbers")
west, south, east, north = values
if not (-180 <= west <= east <= 180 and -90 <= south <= north <= 90):
    raise SystemExit(f"invalid MBTiles bounds {raw!r}: coordinates are out of range or unordered")
print(" ".join(format(value, ".15g") for value in values))
PY
)"
read -r WEST SOUTH EAST NORTH <<< "$BOUNDS"

OUTPUT_DIR="$(dirname "$OUTPUT")"
OUTPUT_NAME="$(basename "$OUTPUT")"
mkdir -p "$OUTPUT_DIR"
TEMP_DB="$(mktemp "$OUTPUT_DIR/.${OUTPUT_NAME}.tmp.XXXXXX")"
TEMP_HASH="${TEMP_DB}.sha256"
cleanup() { rm -f "$TEMP_DB" "$TEMP_HASH"; }
trap cleanup EXIT

# ATTACH takes a SQL string literal, so double any apostrophes in the path.
SOURCE_SQL="${SOURCE//\'/\'\'}"

sqlite3 "$TEMP_DB" >/dev/null <<SQL
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
ATTACH DATABASE '$SOURCE_SQL' AS source;

CREATE TABLE search_meta (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    class TEXT,
    lat REAL NOT NULL,
    lon REAL NOT NULL
);
INSERT INTO search_meta
SELECT id, name, kind, class, lat, lon
FROM source.search_meta
WHERE lon BETWEEN $WEST AND $EAST AND lat BETWEEN $SOUTH AND $NORTH;
CREATE INDEX idx_search_meta_kind ON search_meta(kind);
CREATE INDEX idx_search_meta_lat_lon ON search_meta(lat, lon);

CREATE TABLE road_speed (
    id INTEGER PRIMARY KEY,
    name TEXT,
    maxspeed_kmh INTEGER NOT NULL,
    lat REAL NOT NULL,
    lon REAL NOT NULL
);
INSERT INTO road_speed
SELECT id, name, maxspeed_kmh, lat, lon
FROM source.road_speed
WHERE lon BETWEEN $WEST AND $EAST AND lat BETWEEN $SOUTH AND $NORTH;
CREATE INDEX idx_road_speed_lat ON road_speed(lat);

DETACH DATABASE source;
VACUUM;
SQL

TOTAL="$(sqlite3 "$TEMP_DB" 'SELECT count(*) FROM search_meta')"
POIS="$(sqlite3 "$TEMP_DB" "SELECT count(*) FROM search_meta WHERE kind='poi'")"
SUPERMARKETS="$(sqlite3 "$TEMP_DB" "SELECT count(*) FROM search_meta WHERE class='shop=supermarket'")"
ADELAIDE="$(sqlite3 "$TEMP_DB" "SELECT count(*) FROM search_meta WHERE lower(name) LIKE '%adelaide%'")"
OUTSIDE="$(sqlite3 "$TEMP_DB" "
    SELECT
        (SELECT count(*) FROM search_meta
         WHERE NOT (lon BETWEEN $WEST AND $EAST AND lat BETWEEN $SOUTH AND $NORTH)) +
        (SELECT count(*) FROM road_speed
         WHERE NOT (lon BETWEEN $WEST AND $EAST AND lat BETWEEN $SOUTH AND $NORTH));
")"
INTEGRITY="$(sqlite3 "$TEMP_DB" 'PRAGMA integrity_check;')"

(( TOTAL >= 1000 )) || { echo "bundled index too small: $TOTAL rows" >&2; exit 1; }
(( POIS >= 100 )) || { echo "bundled index has too few POIs: $POIS" >&2; exit 1; }
(( SUPERMARKETS >= 1 )) || { echo "bundled index has no supermarkets" >&2; exit 1; }
(( ADELAIDE >= 1 )) || { echo "bundled index has no Adelaide match" >&2; exit 1; }
(( OUTSIDE == 0 )) || { echo "bundled index has $OUTSIDE rows outside tile bounds $BOUNDS_RAW" >&2; exit 1; }
[[ "$INTEGRITY" == "ok" ]] || { echo "bundled index failed integrity check: $INTEGRITY" >&2; exit 1; }

HASH="$(sha256sum "$TEMP_DB" | cut -d' ' -f1)"
printf '%s\n' "$HASH" > "$TEMP_HASH"
chmod 0644 "$TEMP_DB" "$TEMP_HASH"
mv -f "$TEMP_DB" "$OUTPUT"
mv -f "$TEMP_HASH" "${OUTPUT}.sha256"
TEMP_DB=""
TEMP_HASH=""

echo "bundled search: $OUTPUT"
echo "tile bounds: $BOUNDS_RAW ($TILES)"
echo "rows=$TOTAL pois=$POIS supermarkets=$SUPERMARKETS size=$(stat -c%s "$OUTPUT")"
echo "sha256=$HASH (${OUTPUT}.sha256)"
