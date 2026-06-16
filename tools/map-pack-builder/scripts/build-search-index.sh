#!/usr/bin/env bash
#
# build-search-index.sh
#
# Extracts named features from an OSM PBF file and builds a SQLite FTS5
# database for the aus-roads Android app's free-text search.
#
# Called from the build pipeline after the Planetiler step.
#
# Inputs:
#   - cache/south-australia-latest.osm.pbf
#
# Outputs:
#   - dist/search.db
#
# Usage:
#   tools/map-pack-builder/scripts/build-search-index.sh [BBOX]
#
# Optional BBOX filters output to west,south,east,north (e.g. Adelaide metro).
# Without a BBOX the full PBF is processed.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
CACHE="$ROOT/cache"
DIST="$ROOT/dist"
TMPDIR_SEARCH="$(mktemp -d)"

log() { printf '\033[1;34m[search]\033[0m %s\n' "$*"; }
err() { printf '\033[1;31m[search]\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() { rm -rf "$TMPDIR_SEARCH"; }
trap cleanup EXIT

# ---------------------------------------------------------------------------
# 1. Verify dependencies
# ---------------------------------------------------------------------------

for cmd in python3; do
    command -v "$cmd" >/dev/null 2>&1 || err "$cmd not found"
done

python3 -c "import osmium" 2>/dev/null || \
    err "Python 'osmium' module not found. Install with: pip install --break-system-packages osmium"

# Prefer the system sqlite3 (has FTS5); the Android SDK one may lack it.
SQLITE3=""
for candidate in /usr/bin/sqlite3 "$(command -v sqlite3 2>/dev/null || true)"; do
    if [[ -n "$candidate" ]] && "$candidate" :memory: "CREATE VIRTUAL TABLE _fts5test USING fts5(x);" 2>/dev/null; then
        SQLITE3="$candidate"
        break
    fi
done
if [[ -z "$SQLITE3" ]]; then
    err "sqlite3 with FTS5 support not found. Install the system sqlite3 package."
fi
log "using sqlite3: $SQLITE3"

# ---------------------------------------------------------------------------
# 2. Paths
# ---------------------------------------------------------------------------

PBF="$CACHE/south-australia-latest.osm.pbf"
CSV="$TMPDIR_SEARCH/features.csv"
DB="$DIST/search.db"
BARG="${1:-}"

[[ -s "$PBF" ]] || err "PBF not found: $PBF"

mkdir -p "$DIST"

# ---------------------------------------------------------------------------
# 3. Extract features → CSV
# ---------------------------------------------------------------------------

log "extracting features from $(basename "$PBF")..."

BBOX_ARG=()
if [[ -n "$BARG" ]]; then
    BBOX_ARG=("$BARG")
    log "bounding box: $BARG"
fi

python3 "$HERE/osm-to-search-csv.py" "$PBF" "$CSV" "${BBOX_ARG[@]}"

FEATURE_COUNT=$(tail -n +2 "$CSV" | wc -l)
log "extracted $FEATURE_COUNT features"

if [[ "$FEATURE_COUNT" -eq 0 ]]; then
    err "no features extracted — check PBF or bounding box"
fi

# ---------------------------------------------------------------------------
# 4. Build SQLite FTS5 database
# ---------------------------------------------------------------------------

log "building search database..."

rm -f "$DB"

"$SQLITE3" "$DB" <<'SQL'
CREATE VIRTUAL TABLE search_index USING fts5(
    name,
    kind,
    class,
    lat,
    lon,
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TABLE search_meta (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    class TEXT,
    lat REAL NOT NULL,
    lon REAL NOT NULL
);
CREATE INDEX idx_search_meta_kind ON search_meta(kind);
SQL

# Import CSV into a temporary table, then populate both FTS5 and meta.
"$SQLITE3" "$DB" <<SQL
.mode csv
.import $CSV _import

INSERT INTO search_meta (name, kind, class, lat, lon)
SELECT name, kind, class, CAST(lat AS REAL), CAST(lon AS REAL) FROM _import;

INSERT INTO search_index (name, kind, class, lat, lon)
SELECT name, kind, class, lat, lon FROM search_meta;

DROP TABLE _import;
SQL

# Verify
META_COUNT=$("$SQLITE3" "$DB" "SELECT COUNT(*) FROM search_meta;")
FTS_COUNT=$("$SQLITE3" "$DB" "SELECT COUNT(*) FROM search_index;")
log "search_meta: $META_COUNT rows, search_index: $FTS_COUNT rows"

# ---------------------------------------------------------------------------
# 5. Road speed-limit table (for the over-speed monitor)
# ---------------------------------------------------------------------------
#
# Extract a road -> maxspeed lookup from the *same* PBF and load it into the
# *same* search.db as the `road_speed` table. This table is a second component
# inside the existing `search` pack component (it ships in search.db, so the
# manifest is unchanged — the SHA-256 below covers it). The app's over-speed
# monitor queries road_speed(name, maxspeed_kmh, lat, lon) by a lat bounding box.
#
# Re-run safety: osm-to-maxspeed.py uses CREATE TABLE IF NOT EXISTS + INSERT,
# so it appends. The DB is rebuilt fresh each run (rm -f "$DB" in section 4),
# so road_speed starts empty here and this is always a clean single pass.

log "extracting road speed limits into road_speed..."

python3 "$HERE/osm-to-maxspeed.py" "$PBF" "$DB"

SPEED_COUNT=$("$SQLITE3" "$DB" "SELECT COUNT(*) FROM road_speed;")
log "road_speed: $SPEED_COUNT rows"

if [[ "$SPEED_COUNT" -eq 0 ]]; then
    err "no road_speed rows extracted — check PBF maxspeed coverage"
fi

# ---------------------------------------------------------------------------
# 6. Compute SHA-256 + file size for the manifest
# ---------------------------------------------------------------------------

SHA="$(sha256sum "$DB" | cut -d' ' -f1)"
SIZE_BYTES="$(stat -c%s "$DB")"

log "search.db: $(du -h "$DB" | cut -f1)  sha256=$SHA"

# ---------------------------------------------------------------------------
# 7. Summary
# ---------------------------------------------------------------------------

log "feature counts by kind:"
"$SQLITE3" "$DB" "SELECT kind, COUNT(*) FROM search_meta GROUP BY kind ORDER BY COUNT(*) DESC;" | \
    while IFS='|' read -r kind count; do
        printf '  %-10s %s\n' "$kind" "$count"
    done

log "top road speed limits (km/h):"
"$SQLITE3" "$DB" "SELECT maxspeed_kmh, COUNT(*) FROM road_speed GROUP BY maxspeed_kmh ORDER BY COUNT(*) DESC LIMIT 8;" | \
    while IFS='|' read -r speed count; do
        printf '  %-10s %s\n' "${speed} km/h" "$count"
    done

log "DONE. Search index at $DB"
