#!/usr/bin/env python3
"""
osm-to-cameras.py — Extract fixed speed-camera locations from an OSM PBF file
and load them into the map pack's SQLite search database as the `road_cameras`
table, consumed by the app's speed-camera awareness feature (owner vision:
mobile/fixed speed camera info; this extracts FIXED devices).

Two OSM sources are mined (mirrors the wiki model of enforcement relations):

  1. Standalone nodes tagged `highway=speed_camera` — the camera itself.
  2. Relations with `type=enforcement` + `enforcement=maxspeed` — the member
     with role `from` is the enforced way (gives the enforced road's name and
     a fallback position), the member with role `device` is the camera node
     (preferred position), and the relation carries the enforced `maxspeed`.

One row is emitted per camera found, with the enforced limit kept NULL when
the source carries no parseable maxspeed (missing tags handled gracefully —
a camera without a known limit is still worth showing on the map).

Table contract:

    road_cameras(
        lat          REAL NOT NULL,
        lon          REAL NOT NULL,
        kind         TEXT NOT NULL,      -- always 'fixed' for this extractor
        maxspeed_kmh INTEGER,            -- NULL when unknown/unparseable
        way_name     TEXT                -- NULL for standalone nodes w/o name
    )

Usage:
    python3 osm-to-cameras.py INPUT_PBF OUTPUT_SEARCH_DB

Arguments:
    INPUT_PBF         Path to the .osm.pbf (or .osm XML) file.
    OUTPUT_SEARCH_DB  Path to the SQLite search database. The road_cameras
                      table is created if absent (CREATE TABLE IF NOT EXISTS)
                      and augmented with the extracted rows.
"""

import sqlite3
import sys

import osmium

# Same parsing rules as osm-to-maxspeed.py (kept standalone: both scripts are
# invoked by path, and osm-to-search-csv.py already sets the duplication
# precedent). Australian implicit schemes → km/h.
AU_IMPLICIT_KMH = {
    "AU:urban": 50,
    "AU:rural": 100,
    "AU:national": 100,
    "AU:motorway": 110,
}

WORD_KMH = {
    "walk": 5,
}

SKIP_VALUES = frozenset(
    {
        "none",
        "signals",
        "variable",
        "AU:living_street",
        "AU:school",
    }
)

MPH_TO_KMH = 1.60934

MAX_PLAUSIBLE_KMH = 140


def _sane(speed):
    """Return speed if it is a plausible posted limit (0 < s <= ceiling), else None."""
    return speed if 0 < speed <= MAX_PLAUSIBLE_KMH else None


def parse_maxspeed(raw):
    """Parse an OSM maxspeed tag value into an integer km/h, or None to skip."""
    if raw is None:
        return None
    value = raw.strip()
    if not value:
        return None

    if value in SKIP_VALUES:
        return None

    if value in AU_IMPLICIT_KMH:
        return AU_IMPLICIT_KMH[value]

    lowered = value.lower()
    if lowered in WORD_KMH:
        return WORD_KMH[lowered]

    if lowered.endswith("mph"):
        num = lowered[:-3].strip()
        try:
            return _sane(round(float(num) * MPH_TO_KMH))
        except ValueError:
            return None

    candidate = value
    if lowered.endswith("km/h"):
        candidate = value[:-4].strip()
    elif lowered.endswith("kmh"):
        candidate = value[:-3].strip()
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


class CameraWayCollector(osmium.SimpleHandler):
    """Pass 1: find which way ids the enforcement relations actually reference.

    Only those ways are buffered, so pass 2 stays light even on the full-SA
    extract (we cannot know the referenced ids during pass 2's way callback,
    because relations are streamed after ways).
    """

    def __init__(self):
        super().__init__()
        self.way_ids = set()
        self.device_ids = set()

    @staticmethod
    def is_enforcement_maxspeed(tags):
        return tags.get("type") == "enforcement" and tags.get("enforcement") == "maxspeed"

    def relation(self, r):
        if not self.is_enforcement_maxspeed(r.tags):
            return
        for member in r.members:
            if member.type == "w" and member.role in ("from", "to"):
                self.way_ids.add(member.ref)
            elif member.type == "n" and member.role == "device":
                self.device_ids.add(member.ref)


class CameraExtractor(osmium.SimpleHandler):
    """Pass 2: emit camera rows.

    OSM streams objects node -> way -> relation, so by the time a relation is
    handed to us every referenced way has been seen and buffered.
    """

    def __init__(self, rows):
        super().__init__()
        self.rows = rows
        self.ways = {}  # way_id -> (name, centroid_lat, centroid_lon) for referenced ways
        # device node id -> (lat, lon), buffered in pass 2's node callback. Set by
        # the orchestrator from CameraWayCollector.device_ids. Buffered explicitly
        # because RelationMember.location is NOT reliably populated across pyosmium
        # versions even with locations=True — reading it live made the device
        # position silently degrade to the way centroid.
        self.device_coords = {}
        self.matched_node_cameras = 0
        self.matched_relations = 0
        self.emitted = 0
        self.skipped_no_location = 0  # camera node / relation with no usable position
        self.skipped_unparseable_speed = 0  # kept as NULL, only counted here

    # -- helpers ------------------------------------------------------------

    def _emit(self, lat, lon, speed, way_name):
        self.rows.append((lat, lon, "fixed", speed, way_name))
        self.emitted += 1

    def _relation_parts(self, r):
        """Return (speed, device_loc, from_names, from_centroids) for a relation."""
        speed_raw = r.tags.get("maxspeed")
        speed = parse_maxspeed(speed_raw)
        if speed_raw is not None and speed is None:
            self.skipped_unparseable_speed += 1

        device_loc = (None, None)
        names = []
        centroids = []
        for member in r.members:
            if member.type == "n" and member.role == "device":
                coords = self.device_coords.get(member.ref)
                if coords is not None:
                    device_loc = coords
            elif member.type == "w" and member.role in ("from", "to"):
                info = self.ways.get(member.ref)
                if info is None:
                    continue
                name, clat, clon = info
                if name:
                    names.append(name)
                if clat is not None:
                    centroids.append((clat, clon))
        return speed, device_loc, names, centroids

    # -- osmium callbacks ---------------------------------------------------

    def node(self, n):
        # Buffer coords for device nodes referenced by enforcement relations
        # (ids come from pass 1, which fully completes before pass 2 starts).
        if n.id in self.referenced_device_ids:
            loc = n.location
            if loc is not None and loc.valid():
                self.device_coords[n.id] = (loc.lat, loc.lon)
        if n.tags.get("highway") != "speed_camera":
            return
        self.matched_node_cameras += 1
        loc = n.location
        if loc is None or not loc.valid():
            self.skipped_no_location += 1
            return
        speed = parse_maxspeed(n.tags.get("maxspeed"))
        self._emit(loc.lat, loc.lon, speed, n.tags.get("name") or None)

    def way(self, w):
        # Buffer ONLY the ways enforcement relations reference (pass 1 told us).
        if w.id not in self.referenced_way_ids:
            return
        lat, lon = way_centroid(w)
        self.ways[w.id] = (w.tags.get("name") or "", lat, lon)

    def relation(self, r):
        if not CameraWayCollector.is_enforcement_maxspeed(r.tags):
            return
        self.matched_relations += 1
        speed, (dlat, dlon), names, centroids = self._relation_parts(r)

        # Position: prefer the device node, fall back to the enforced way's centroid.
        lat, lon = dlat, dlon
        if lat is None and centroids:
            lat, lon = centroids[0]
        if lat is None:
            self.skipped_no_location += 1
            return

        way_name = names[0] if names else None
        self._emit(lat, lon, speed, way_name)


def create_schema(conn):
    """Create the road_cameras table + lat index if they do not already exist."""
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS road_cameras (
            lat          REAL NOT NULL,
            lon          REAL NOT NULL,
            kind         TEXT NOT NULL,
            maxspeed_kmh INTEGER,
            way_name     TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_road_cameras_lat ON road_cameras(lat);
        """
    )


def extract_osm_to_db(pbf_path, db_path):
    """Run the two-pass extraction and append rows to the search database.

    Returns the handler (for its counters) so callers/tests can inspect stats.
    """
    collector = CameraWayCollector()
    collector.apply_file(pbf_path, locations=True, idx="flex_mem")

    rows = []
    handler = CameraExtractor(rows)
    handler.referenced_way_ids = collector.way_ids
    handler.referenced_device_ids = collector.device_ids
    # locations=True gives way nodes their coordinates (centroids); device-node
    # positions are buffered explicitly in node() because relation-member
    # locations are version-dependent (see device_coords comment above).
    handler.apply_file(pbf_path, locations=True, idx="flex_mem")

    conn = sqlite3.connect(db_path)
    try:
        create_schema(conn)
        # Idempotent: clear any prior rows so a standalone re-run doesn't duplicate
        # (the orchestrator rebuilds the DB fresh, but the tool must be safe alone).
        conn.execute("DELETE FROM road_cameras")
        conn.executemany(
            "INSERT INTO road_cameras (lat, lon, kind, maxspeed_kmh, way_name) "
            "VALUES (?, ?, ?, ?, ?)",
            rows,
        )
        conn.commit()
        total = conn.execute("SELECT COUNT(*) FROM road_cameras").fetchone()[0]
    finally:
        conn.close()

    print(
        f"road_cameras: matched {handler.matched_node_cameras} speed_camera nodes "
        f"and {handler.matched_relations} enforcement=maxspeed relations, "
        f"emitted {handler.emitted} rows "
        f"(skipped {handler.skipped_no_location} without any usable position; "
        f"{handler.skipped_unparseable_speed} unparseable limits kept as NULL).",
        file=sys.stderr,
    )
    print(f"road_cameras now holds {total} rows in {db_path}", file=sys.stderr)
    return handler


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} INPUT_PBF OUTPUT_SEARCH_DB", file=sys.stderr)
        sys.exit(1)

    handler = extract_osm_to_db(sys.argv[1], sys.argv[2])
    # DELETE+INSERT makes the DB row count equal this run's emitted count.
    print(f"done ({handler.emitted} rows)", file=sys.stderr)


if __name__ == "__main__":
    main()
