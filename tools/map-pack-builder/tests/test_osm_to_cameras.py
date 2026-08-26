#!/usr/bin/env python3
"""Unit tests for osm-to-cameras.py.

Loads the extractor by file path (the script filename contains hyphens, so it
is not importable as a module) and exercises it end-to-end against a synthetic
OSM XML fixture containing:

  - a highway=speed_camera node WITH a maxspeed tag (node 1001, which is ALSO
    relation 4001's device member — must yield exactly ONE merged row, the
    node's explicit limit winning over the relation's conflicting one),
  - a highway=speed_camera node WITHOUT one (graceful degradation),
  - an unrelated amenity node (must be ignored),
  - an enforcement=maxspeed relation (from=way, device=node 1001, to=way),
  - an enforcement=maxspeed relation WITHOUT a device member and WITHOUT a
    maxspeed tag (falls back to the enforced way's centroid, NULL limit),
  - an unrelated relation (must be ignored).

Mirrors the shape of the osm-to-maxspeed test suite (parse-level + end-to-end
SQLite assertions).
"""

import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = TESTS_DIR.parent / "scripts"


def _load_module(file_name, module_name):
    spec = importlib.util.spec_from_file_location(module_name, SCRIPTS_DIR / file_name)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


osm_to_cameras = _load_module("osm-to-cameras.py", "osm_to_cameras_under_test")


# Synthetic OSM XML fixture. Nodes MUST precede ways, ways precede relations.
FIXTURE_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test-fixture">
  <node id="1001" version="1" lat="-34.92850" lon="138.60050">
    <tag k="highway" v="speed_camera"/>
    <tag k="maxspeed" v="80"/>
  </node>
  <node id="1002" version="1" lat="-34.93000" lon="138.61000">
    <tag k="highway" v="speed_camera"/>
  </node>
  <node id="1003" version="1" lat="-34.93500" lon="138.61500">
    <tag k="amenity" v="cafe"/>
    <tag k="name" v="Not A Camera"/>
  </node>
  <node id="2001" version="1" lat="-34.95000" lon="138.60000"/>
  <node id="2002" version="1" lat="-34.96000" lon="138.60000"/>
  <way id="3001" version="1">
    <nd ref="2001"/>
    <nd ref="2002"/>
    <tag k="highway" v="primary"/>
    <tag k="name" v="Main South Road"/>
    <tag k="maxspeed" v="100"/>
  </way>
  <way id="3002" version="1">
    <nd ref="2001"/>
    <nd ref="2002"/>
    <tag k="building" v="yes"/>
  </way>
  <relation id="4001" version="1">
    <member type="way" ref="3001" role="from"/>
    <member type="node" ref="1001" role="device"/>
    <member type="way" ref="3001" role="to"/>
    <tag k="type" v="enforcement"/>
    <tag k="enforcement" v="maxspeed"/>
    <tag k="maxspeed" v="60"/>
  </relation>
  <relation id="4002" version="1">
    <member type="way" ref="3001" role="from"/>
    <member type="way" ref="3001" role="to"/>
    <tag k="type" v="enforcement"/>
    <tag k="enforcement" v="maxspeed"/>
  </relation>
  <relation id="4003" version="1">
    <member type="way" ref="3001" role="from"/>
    <tag k="type" v="enforcement"/>
    <tag k="enforcement" v="traffic_signals"/>
  </relation>
</osm>
"""

# Multi-relation dedupe fixture: UNTAGGED device node 5001 is the device member
# of TWO enforcement=maxspeed relations (different enforced roads and limits).
# Exactly one row must be emitted for it (first relation's values win).
MULTI_RELATION_FIXTURE_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test-fixture">
  <node id="5001" version="1" lat="-34.97000" lon="138.62000"/>
  <node id="6001" version="1" lat="-34.98000" lon="138.62000"/>
  <node id="6002" version="1" lat="-34.99000" lon="138.62000"/>
  <way id="7001" version="1">
    <nd ref="6001"/>
    <nd ref="6002"/>
    <tag k="highway" v="primary"/>
    <tag k="name" v="South Eastern Freeway"/>
  </way>
  <way id="7002" version="1">
    <nd ref="6001"/>
    <nd ref="6002"/>
    <tag k="highway" v="secondary"/>
    <tag k="name" v="Crafers Road"/>
  </way>
  <relation id="8001" version="1">
    <member type="way" ref="7001" role="from"/>
    <member type="node" ref="5001" role="device"/>
    <member type="way" ref="7001" role="to"/>
    <tag k="type" v="enforcement"/>
    <tag k="enforcement" v="maxspeed"/>
    <tag k="maxspeed" v="60"/>
  </relation>
  <relation id="8002" version="1">
    <member type="way" ref="7002" role="from"/>
    <member type="node" ref="5001" role="device"/>
    <member type="way" ref="7002" role="to"/>
    <tag k="type" v="enforcement"/>
    <tag k="enforcement" v="maxspeed"/>
    <tag k="maxspeed" v="80"/>
  </relation>
</osm>
"""


class ParseMaxspeedTest(unittest.TestCase):
    """Direct tests of the shared maxspeed parsing rules."""

    def test_bare_number(self):
        self.assertEqual(osm_to_cameras.parse_maxspeed("80"), 80)

    def test_kmh_suffix(self):
        self.assertEqual(osm_to_cameras.parse_maxspeed("60 km/h"), 60)

    def test_mph(self):
        self.assertEqual(osm_to_cameras.parse_maxspeed("60 mph"), round(60 * 1.60934))

    def test_au_implicit(self):
        self.assertEqual(osm_to_cameras.parse_maxspeed("AU:urban"), 50)

    def test_none_and_empty(self):
        self.assertIsNone(osm_to_cameras.parse_maxspeed(None))
        self.assertIsNone(osm_to_cameras.parse_maxspeed(""))

    def test_skip_values(self):
        for raw in ("none", "signals", "variable"):
            self.assertIsNone(osm_to_cameras.parse_maxspeed(raw))

    def test_unparseable(self):
        self.assertIsNone(osm_to_cameras.parse_maxspeed("fast"))

    def test_implausible_rejected(self):
        self.assertIsNone(osm_to_cameras.parse_maxspeed("999"))


class CameraExtractionTest(unittest.TestCase):
    """End-to-end extraction of the synthetic fixture into a temp search.db."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="osm-cameras-test-")
        base = Path(cls.tmp.name)
        cls.fixture = base / "fixture.osm"
        cls.fixture.write_text(FIXTURE_OSM, encoding="utf-8")
        cls.db = base / "search.db"
        cls.handler = osm_to_cameras.extract_osm_to_db(str(cls.fixture), str(cls.db))
        cls.conn = sqlite3.connect(str(cls.db))

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()
        cls.tmp.cleanup()

    def _all_rows(self):
        cur = self.conn.execute(
            "SELECT lat, lon, kind, maxspeed_kmh, way_name FROM road_cameras "
            "ORDER BY lat DESC, lon DESC"
        )
        return cur.fetchall()

    def test_schema_shape(self):
        cols = self.conn.execute("PRAGMA table_info(road_cameras)").fetchall()
        names_types = [(c[1], c[2], c[3]) for c in cols]
        self.assertEqual(
            names_types,
            [
                ("lat", "REAL", 1),
                ("lon", "REAL", 1),
                ("kind", "TEXT", 1),
                ("maxspeed_kmh", "INTEGER", 0),
                ("way_name", "TEXT", 0),
            ],
        )

    def test_row_count(self):
        # Node 1002 + relation 4002's centroid fallback + ONE merged row for
        # node 1001 (speed_camera node AND relation 4001's device member —
        # counted once; it was previously duplicated with limits 80 and 60).
        # Relation 4003 is not enforcement=maxspeed and must be ignored.
        self.assertEqual(len(self._all_rows()), 3)

    def test_camera_node_also_device_member_is_deduped(self):
        # Node 1001 is BOTH a highway=speed_camera node (maxspeed=80) and
        # relation 4001's device member (maxspeed=60): it must produce exactly
        # ONE row at the device-node position (not the from-way centroid) with
        # the explicit device-node limit winning — 80, never a conflicting
        # 80/60 pair for the same node.
        matches = [
            row
            for row in self._all_rows()
            if row[0] == -34.9285 and row[1] == 138.6005
        ]
        self.assertEqual(
            matches, [(-34.9285, 138.6005, "fixed", 80, "Main South Road")]
        )
        self.assertFalse(any(row[3] == 60 for row in self._all_rows()))

    def test_standalone_node_without_limit_is_graceful(self):
        self.assertIn((-34.93, 138.61, "fixed", None, None), self._all_rows())

    def test_relation_falls_back_to_way_centroid_without_device(self):
        # Relation 4002 has no device member and no maxspeed: position comes
        # from the 'from' way centroid ((-34.95 + -34.96)/2), limit stays NULL.
        self.assertIn((-34.955, 138.6, "fixed", None, "Main South Road"), self._all_rows())

    def test_non_camera_features_ignored(self):
        kinds = {row[2] for row in self._all_rows()}
        self.assertEqual(kinds, {"fixed"})
        self.assertNotIn("Not A Camera", [r[4] for r in self._all_rows()])

    def test_non_maxspeed_enforcement_ignored(self):
        # Relation 4003 (enforcement=traffic_signals) must contribute nothing:
        # assert the full expected row count here so this test stands alone
        # instead of leaning on the sibling row-count test.
        self.assertEqual(len(self._all_rows()), 3)

    def test_handler_counters(self):
        h = self.handler
        self.assertEqual(h.matched_node_cameras, 2)
        self.assertEqual(h.matched_relations, 2)  # 4003 filtered out
        self.assertEqual(h.emitted, 3)  # node 1001 counted once, not twice
        self.assertEqual(h.deduped_device_nodes, 1)
        self.assertEqual(h.skipped_no_location, 0)

    def test_idempotent_rerun(self):
        # A second standalone run must not duplicate rows (DELETE + INSERT).
        osm_to_cameras.extract_osm_to_db(str(self.fixture), str(self.db))
        self.assertEqual(len(self._all_rows()), 3)

    def test_index_created(self):
        idx = self.conn.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='road_cameras'"
        ).fetchall()
        self.assertTrue(any("idx_road_cameras_lat" == i[0] for i in idx))


class FixtureSanityTest(unittest.TestCase):
    """Guard the fixture itself against silent corruption."""

    def test_fixture_parses_as_json_free_xml_with_expected_ids(self):
        text = FIXTURE_OSM
        for marker in ('id="1001"', 'id="4001"', 'id="4002"', 'id="4003"',
                       'k="highway" v="speed_camera"', 'k="enforcement" v="maxspeed"'):
            self.assertIn(marker, text)


class MultiRelationDedupeTest(unittest.TestCase):
    """Two enforcement relations sharing an UNtagged device member -> single row."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="osm-cameras-multi-rel-")
        base = Path(cls.tmp.name)
        cls.fixture = base / "fixture.osm"
        cls.fixture.write_text(MULTI_RELATION_FIXTURE_OSM, encoding="utf-8")
        cls.db = base / "search.db"
        cls.handler = osm_to_cameras.extract_osm_to_db(str(cls.fixture), str(cls.db))
        cls.conn = sqlite3.connect(str(cls.db))

    @classmethod
    def tearDownClass(cls):
        cls.conn.close()
        cls.tmp.cleanup()

    def test_two_relations_sharing_untagged_device_emit_single_row(self):
        # Node 5001 carries no highway=speed_camera tag yet is the device member
        # of relations 8001 (maxspeed=60) and 8002 (maxspeed=80): without the
        # multi-relation dedupe this emitted TWO rows for one physical camera.
        # First emitting relation wins on limit and road name.
        rows = self.conn.execute(
            "SELECT lat, lon, kind, maxspeed_kmh, way_name FROM road_cameras"
        ).fetchall()
        self.assertEqual(
            rows,
            [(-34.97, 138.62, "fixed", 60, "South Eastern Freeway")],
        )
        h = self.handler
        self.assertEqual(h.matched_relations, 2)
        self.assertEqual(h.emitted, 1)
        self.assertEqual(h.deduped_relation_devices, 1)
        self.assertEqual(h.deduped_device_nodes, 0)


if __name__ == "__main__":
    unittest.main()
