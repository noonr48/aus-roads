#!/usr/bin/env bash
# Integration test for tile-derived bounds, replacement, and checksum output.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BUILDER="$HERE/../scripts/build-bundled-search-asset.sh"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

SOURCE="$WORK/source.db"
TILES="$WORK/tiles.mbtiles"
OUTPUT="$WORK/bundled.db"

sqlite3 "$SOURCE" <<'SQL'
CREATE TABLE search_meta (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
    class TEXT, lat REAL NOT NULL, lon REAL NOT NULL
);
WITH RECURSIVE numbers(value) AS (
    VALUES(1) UNION ALL SELECT value + 1 FROM numbers WHERE value < 1000
)
INSERT INTO search_meta
SELECT value,
       CASE WHEN value = 1 THEN 'Adelaide Market' ELSE 'Feature ' || value END,
       CASE WHEN value <= 100 THEN 'poi' ELSE 'road' END,
       CASE WHEN value = 1 THEN 'shop=supermarket' ELSE 'road' END,
       20.5, 10.5
FROM numbers;
INSERT INTO search_meta VALUES (1001, 'Outside', 'poi', 'shop=supermarket', 22, 12);

CREATE TABLE road_speed (
    id INTEGER PRIMARY KEY, name TEXT, maxspeed_kmh INTEGER NOT NULL,
    lat REAL NOT NULL, lon REAL NOT NULL
);
INSERT INTO road_speed VALUES
    (1, 'Inside Road', 50, 20.5, 10.5),
    (2, 'Boundary Road', 60, 20, 10),
    (3, 'Outside Road', 80, 22, 12);
SQL

sqlite3 "$TILES" <<'SQL'
CREATE TABLE metadata (name TEXT, value TEXT);
INSERT INTO metadata VALUES ('bounds', '10,20,11,21');
SQL

bash "$BUILDER" "$SOURCE" "$OUTPUT" "$TILES"
[[ "$(sqlite3 "$OUTPUT" 'SELECT count(*) FROM search_meta;')" -eq 1000 ]]
[[ "$(sqlite3 "$OUTPUT" 'SELECT count(*) FROM road_speed;')" -eq 2 ]]
[[ "$(sqlite3 "$OUTPUT" "SELECT count(*) FROM road_speed WHERE name='Boundary Road';")" -eq 1 ]]
[[ "$(<"${OUTPUT}.sha256")" == "$(sha256sum "$OUTPUT" | cut -d' ' -f1)" ]]

# A second build replaces both artifacts and regenerates the digest.
sqlite3 "$SOURCE" "UPDATE search_meta SET name='Adelaide Rebuilt' WHERE id=1;"
bash "$BUILDER" "$SOURCE" "$OUTPUT" "$TILES"
[[ "$(sqlite3 "$OUTPUT" "SELECT count(*) FROM search_meta WHERE name='Adelaide Rebuilt';")" -eq 1 ]]
[[ "$(<"${OUTPUT}.sha256")" == "$(sha256sum "$OUTPUT" | cut -d' ' -f1)" ]]

# Invalid tile metadata must fail without publishing an output.
sqlite3 "$TILES" "UPDATE metadata SET value='11,20,10,21' WHERE name='bounds';"
if bash "$BUILDER" "$SOURCE" "$WORK/invalid.db" "$TILES" >/dev/null 2>&1; then
    echo "expected invalid MBTiles bounds to fail" >&2
    exit 1
fi
[[ ! -e "$WORK/invalid.db" ]]

echo "bundled search asset integration test: PASS"
