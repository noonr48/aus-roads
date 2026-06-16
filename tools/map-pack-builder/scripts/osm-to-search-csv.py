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
POI_KEYS = frozenset({"amenity", "tourism", "shop", "leisure"})
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

    def relation(self, r):
        tags = dict(r.tags)
        if tags.get("natural") == "water" or tags.get("leisure") == "park":
            name = tags.get("name", "").strip()
            if not name:
                return
            kind = "water" if tags.get("natural") == "water" else "park"
            cls = "water" if kind == "water" else "park"
            lat, lon = self._relation_centroid(r)
            if lat is None:
                return
            if not in_bbox(lat, lon, self.bbox):
                return
            self.writer.writerow([name, kind, cls, f"{lat:.7f}", f"{lon:.7f}"])
            self.counts[kind] += 1

    def _relation_centroid(self, rel):
        lats, lons = [], []
        for member in rel.members:
            if member.type == "w":
                try:
                    loc = member.location
                    if loc.valid():
                        lats.append(loc.lat)
                        lons.append(loc.lon)
                except Exception:
                    pass
        if not lats:
            return None, None
        return sum(lats) / len(lats), sum(lons) / len(lons)


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
