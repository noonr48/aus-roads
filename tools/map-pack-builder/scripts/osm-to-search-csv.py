#!/usr/bin/env python3
"""
osm-to-search-csv.py — Extract named features from an OSM PBF file and
write them as CSV for import into a SQLite FTS5 search index.

Usage:
    python3 osm-to-search-csv.py INPUT_PBF OUTPUT_CSV [BBOX]

Arguments:
    INPUT_PBF   Path to the .osm.pbf file
    OUTPUT_CSV  Path to the output CSV file
    BBOX        Optional bounding box: west,south,east,north
"""

import csv
import sys

import osmium

PLACE_VALUES = frozenset(
    {
        "suburb",
        "city",
        "town",
        "village",
        "locality",
        "neighbourhood",
        "hamlet",
        "quarter",
    }
)
# Priority is deliberate and deterministic for multi-tagged features. A set made
# the emitted category vary with Python hash order, which made pack builds differ.
POI_KEYS = ("amenity", "tourism", "shop", "leisure")
RELATION_AREA_KINDS = frozenset({"poi", "water", "park"})
HIGHWAY_SKIP = frozenset(
    {
        "motorway_junction",
        "services",
        "rest_area",
        "turning_circle",
        "bus_stop",
        "traffic_signals",
        "stop",
        "give_way",
    }
)


def in_bbox(lat, lon, bbox):
    if bbox is None:
        return True
    west, south, east, north = bbox
    return south <= lat <= north and west <= lon <= east


def way_centroid(way):
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


def ring_centroid(ring):
    """Return (lat, lon, absolute area) for a valid polygon ring."""
    coordinates = []
    for node_ref in ring:
        location = node_ref.location
        if location.valid():
            coordinates.append((location.lon, location.lat))
    if len(coordinates) < 3:
        return None
    if coordinates[0] != coordinates[-1]:
        coordinates.append(coordinates[0])

    # Shift to a local origin before the shoelace calculation. OSM building
    # footprints are tiny relative to global longitude/latitude values; using
    # raw coordinates needlessly loses precision when large products cancel.
    origin_lon, origin_lat = coordinates[0]
    local_coordinates = [
        (lon - origin_lon, lat - origin_lat) for lon, lat in coordinates
    ]
    doubled_area = 0.0
    weighted_lon = 0.0
    weighted_lat = 0.0
    for (lon_a, lat_a), (lon_b, lat_b) in zip(
        local_coordinates, local_coordinates[1:]
    ):
        cross = lon_a * lat_b - lon_b * lat_a
        doubled_area += cross
        weighted_lon += (lon_a + lon_b) * cross
        weighted_lat += (lat_a + lat_b) * cross
    if abs(doubled_area) < 1e-15:
        return None

    return (
        origin_lat + weighted_lat / (3.0 * doubled_area),
        origin_lon + weighted_lon / (3.0 * doubled_area),
        abs(doubled_area) / 2.0,
    )


def area_centroid(area):
    """Return an area-weighted centroid for a relation multipolygon."""
    weighted_lat = 0.0
    weighted_lon = 0.0
    total_area = 0.0
    fallback_locations = []

    for outer_ring in area.outer_rings():
        for node_ref in outer_ring:
            location = node_ref.location
            if location.valid():
                fallback_locations.append((location.lat, location.lon))

        outer = ring_centroid(outer_ring)
        if outer is not None:
            lat, lon, ring_area = outer
            weighted_lat += lat * ring_area
            weighted_lon += lon * ring_area
            total_area += ring_area

        for inner_ring in area.inner_rings(outer_ring):
            inner = ring_centroid(inner_ring)
            if inner is not None:
                lat, lon, ring_area = inner
                weighted_lat -= lat * ring_area
                weighted_lon -= lon * ring_area
                total_area -= ring_area

    if total_area > 1e-15:
        return weighted_lat / total_area, weighted_lon / total_area
    if fallback_locations:
        return (
            sum(lat for lat, _ in fallback_locations) / len(fallback_locations),
            sum(lon for _, lon in fallback_locations) / len(fallback_locations),
        )
    return None, None


def classify(tags):
    """Yield (kind, class) tuples for matching features."""
    name = tags.get("name", "").strip()
    if not name:
        ref = tags.get("ref", "").strip()
        if ref and "highway" in tags:
            yield "road", tags["highway"]
        return

    place = tags.get("place", "")
    if place in PLACE_VALUES:
        yield "suburb", place
        return

    for key in POI_KEYS:
        if key in tags:
            value = tags[key]
            if key == "leisure" and value == "park":
                yield "park", value
            else:
                yield "poi", f"{key}={value}"
            return

    if "highway" in tags:
        hw = tags["highway"]
        if hw not in HIGHWAY_SKIP:
            yield "road", hw
        return

    if tags.get("natural") == "water":
        yield "water", "water"
        return

    if tags.get("leisure") == "park":
        yield "park", "park"
        return


class FeatureExtractor(osmium.SimpleHandler):
    def __init__(self, writer, bbox=None):
        super().__init__()
        self.writer = writer
        self.bbox = bbox
        self.counts = {"suburb": 0, "poi": 0, "road": 0, "water": 0, "park": 0}

    def node(self, n):
        tags = dict(n.tags)
        for kind, klass in classify(tags):
            lat, lon = n.location.lat, n.location.lon
            if not in_bbox(lat, lon, self.bbox):
                continue
            self.writer.writerow(
                [
                    tags.get("name", tags.get("ref", "")),
                    kind,
                    klass,
                    f"{lat:.7f}",
                    f"{lon:.7f}",
                ]
            )
            self.counts[kind] += 1

    def way(self, w):
        tags = dict(w.tags)
        for kind, klass in classify(tags):
            lat, lon = way_centroid(w)
            if lat is None:
                continue
            if not in_bbox(lat, lon, self.bbox):
                continue
            self.writer.writerow(
                [
                    tags.get("name", tags.get("ref", "")),
                    kind,
                    klass,
                    f"{lat:.7f}",
                    f"{lon:.7f}",
                ]
            )
            self.counts[kind] += 1

    def area(self, area):
        # Closed ways are already emitted by way(). Restrict this callback to
        # relation-derived areas so adding multipolygon POIs cannot duplicate them.
        if area.from_way():
            return
        tags = dict(area.tags)
        for kind, klass in classify(tags):
            if kind not in RELATION_AREA_KINDS:
                continue
            lat, lon = area_centroid(area)
            if lat is None or not in_bbox(lat, lon, self.bbox):
                continue
            self.writer.writerow(
                [
                    tags.get("name", ""),
                    kind,
                    klass,
                    f"{lat:.7f}",
                    f"{lon:.7f}",
                ]
            )
            self.counts[kind] += 1


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} INPUT_PBF OUTPUT_CSV [BBOX]", file=sys.stderr)
        sys.exit(1)

    pbf_path = sys.argv[1]
    csv_path = sys.argv[2]
    bbox = None
    if len(sys.argv) > 3:
        bbox = tuple(float(x) for x in sys.argv[3].split(","))

    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["name", "kind", "class", "lat", "lon"])

        handler = FeatureExtractor(writer, bbox)
        handler.apply_file(pbf_path, locations=True, idx="flex_mem")

    total = sum(handler.counts.values())
    print(f"Extracted {total} features:", file=sys.stderr)
    for kind, count in sorted(handler.counts.items()):
        print(f"  {kind}: {count}", file=sys.stderr)


if __name__ == "__main__":
    main()
