#!/usr/bin/env python3
"""
osm-to-maxspeed.py — Extract a road → speed-limit lookup from an OSM PBF file
and load it into the map pack's SQLite search database as the `road_speed`
table, consumed by the app's over-speed monitor.

One row is emitted per highway way that carries a *parseable* maxspeed tag,
with (lat, lon) set to the way's node centroid (mean of its nodes' positions).

Table contract (FIXED — the over-speed monitor queries this shape):

    road_speed(
        id           INTEGER PRIMARY KEY AUTOINCREMENT,
        name         TEXT,
        maxspeed_kmh INTEGER NOT NULL,
        lat          REAL NOT NULL,
        lon          REAL NOT NULL
    )

A `CREATE INDEX idx_road_speed_lat ON road_speed(lat)` supports the bounding-box
prefilter the consumer uses to find nearby roads.

Usage:
    python3 osm-to-maxspeed.py INPUT_PBF OUTPUT_SEARCH_DB

Arguments:
    INPUT_PBF         Path to the .osm.pbf file.
    OUTPUT_SEARCH_DB  Path to the SQLite search database. The road_speed table
                      is created if absent (CREATE TABLE IF NOT EXISTS) and
                      augmented with the extracted rows.
"""

import sqlite3
import sys

import osmium

# Australian implicit maxspeed schemes → km/h. These appear as the literal
# tag value when a road inherits a default rather than a posted number.
# Refs: https://wiki.openstreetmap.org/wiki/OSM_tags_for_routing/Maxspeed#Australia
AU_IMPLICIT_KMH = {
    "AU:urban": 50,
    "AU:rural": 100,
    "AU:national": 100,
    "AU:motorway": 110,
}

# Word-valued schemes.
WORD_KMH = {
    "walk": 5,
}

# Values that carry no usable numeric limit for the monitor → skipped.
SKIP_VALUES = frozenset(
    {
        "none",
        "signals",
        "variable",
        # Living-street / school-zone implicit schemes: no fixed posted limit
        # we can rely on for the monitor, so skip them.
        "AU:living_street",
        "AU:school",
    }
)

MPH_TO_KMH = 1.60934

# AU's highest posted limit is 130 km/h; anything above this is vandalism or a
# bad tag and must never become a "limit a road can never exceed" for the monitor.
MAX_PLAUSIBLE_KMH = 140


def _sane(speed):
    """Return speed if it is a plausible posted limit (0 < s <= ceiling), else None."""
    return speed if 0 < speed <= MAX_PLAUSIBLE_KMH else None


def parse_maxspeed(raw):
    """Parse an OSM maxspeed tag value into an integer km/h, or None to skip.

    Handles:
      - bare number          "100"        -> 100
      - mph                  "60 mph"     -> round(60 * 1.60934)
      - AU implicit schemes  "AU:urban"   -> 50, "AU:rural"/"AU:national" -> 100,
                             "AU:motorway"-> 110
      - "walk"               -> 5
      - "none"/"signals"/"variable"/"AU:living_street"/"AU:school"/unparseable
                             -> None (skipped)
    """
    if raw is None:
        return None
    value = raw.strip()
    if not value:
        return None

    # Explicit skips first (covers living_street / school implicit schemes).
    if value in SKIP_VALUES:
        return None

    # AU implicit schemes.
    if value in AU_IMPLICIT_KMH:
        return AU_IMPLICIT_KMH[value]

    # Word values (e.g. "walk").
    lowered = value.lower()
    if lowered in WORD_KMH:
        return WORD_KMH[lowered]

    # mph suffix, e.g. "60 mph" / "60mph".
    if lowered.endswith("mph"):
        num = lowered[:-3].strip()
        try:
            return _sane(round(float(num) * MPH_TO_KMH))
        except ValueError:
            return None

    # Bare number (km/h is the OSM default unit). Some values carry an
    # explicit "km/h" suffix; strip it. Accept ints; reject anything else.
    candidate = value
    if lowered.endswith("km/h"):
        candidate = value[:-4].strip()
    elif lowered.endswith("kmh"):
        candidate = value[:-3].strip()
    # Accept integer or float km/h (OSM occasionally carries "50.0").
    try:
        speed = round(float(candidate))
    except ValueError:
        return None
    return _sane(speed)


def way_centroid(way):
    """Mean lat/lon of the way's nodes that have valid locations.

    Returns (None, None) when no node has a usable location.
    """
    lats = []
    lons = []
    for node_ref in way.nodes:
        loc = node_ref.location
        if loc.valid():
            lats.append(loc.lat)
            lons.append(loc.lon)
    if not lats:
        return None, None
    return sum(lats) / len(lats), sum(lons) / len(lons)


class MaxspeedExtractor(osmium.SimpleHandler):
    def __init__(self, rows):
        super().__init__()
        self.rows = rows
        self.matched = 0  # highway ways with a maxspeed tag (parseable or not)
        self.emitted = 0  # rows actually emitted
        self.skipped_unparseable = 0  # maxspeed present but not parseable
        self.skipped_no_location = 0  # parseable but no node centroid

    def way(self, w):
        if w.tags.get("highway") is None:
            return
        raw = w.tags.get("maxspeed")
        if raw is None:
            return
        self.matched += 1

        speed = parse_maxspeed(raw)
        if speed is None:
            self.skipped_unparseable += 1
            return

        lat, lon = way_centroid(w)
        if lat is None:
            self.skipped_no_location += 1
            return

        name = w.tags.get("name") or ""
        self.rows.append((name, speed, lat, lon))
        self.emitted += 1


def create_schema(conn):
    """Create the road_speed table + lat index if they do not already exist."""
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS road_speed (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            name         TEXT,
            maxspeed_kmh INTEGER NOT NULL,
            lat          REAL NOT NULL,
            lon          REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_road_speed_lat ON road_speed(lat);
        """
    )


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} INPUT_PBF OUTPUT_SEARCH_DB", file=sys.stderr)
        sys.exit(1)

    pbf_path = sys.argv[1]
    db_path = sys.argv[2]

    rows = []
    handler = MaxspeedExtractor(rows)
    # locations=True with the flex_mem index gives way nodes their coordinates
    # so we can compute centroids (mirrors osm-to-search-csv.py).
    handler.apply_file(pbf_path, locations=True, idx="flex_mem")

    conn = sqlite3.connect(db_path)
    try:
        create_schema(conn)
        # Idempotent: clear any prior rows so a standalone re-run doesn't duplicate
        # (the orchestrator rebuilds the DB fresh, but the tool must be safe alone).
        conn.execute("DELETE FROM road_speed")
        conn.executemany(
            "INSERT INTO road_speed (name, maxspeed_kmh, lat, lon) "
            "VALUES (?, ?, ?, ?)",
            rows,
        )
        conn.commit()
        total = conn.execute("SELECT COUNT(*) FROM road_speed").fetchone()[0]
    finally:
        conn.close()

    print(
        f"road_speed: matched {handler.matched} highway ways with maxspeed, "
        f"emitted {handler.emitted} rows "
        f"(skipped {handler.skipped_unparseable} unparseable, "
        f"{handler.skipped_no_location} without node locations).",
        file=sys.stderr,
    )
    print(f"road_speed now holds {total} rows in {db_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
