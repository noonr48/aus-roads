#!/usr/bin/env python3
"""Focused tests for the road-speed bbox contract."""

import importlib.util
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "osm-to-maxspeed.py"
SPEC = importlib.util.spec_from_file_location("osm_to_maxspeed", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BboxTest(unittest.TestCase):
    def test_parses_ordered_bounds(self):
        self.assertEqual(
            MODULE.parse_bbox("138.4,-35.2,139,-34.6"),
            (138.4, -35.2, 139.0, -34.6),
        )

    def test_includes_edges_and_rejects_outside_points(self):
        bbox = MODULE.parse_bbox("138.4,-35.2,139,-34.6")
        self.assertTrue(MODULE.in_bbox(-35.2, 138.4, bbox))
        self.assertTrue(MODULE.in_bbox(-34.6, 139.0, bbox))
        self.assertFalse(MODULE.in_bbox(-34.5, 138.7, bbox))
        self.assertFalse(MODULE.in_bbox(-34.9, 139.1, bbox))

    def test_rejects_malformed_or_unordered_bounds(self):
        invalid = (
            "138.4,-35.2,139",
            "west,-35.2,139,-34.6",
            "139,-35.2,138.4,-34.6",
            "138.4,-34.6,139,-35.2",
            "nan,-35.2,139,-34.6",
        )
        for raw in invalid:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                MODULE.parse_bbox(raw)

    def test_cli_clips_road_rows_to_inclusive_bounds(self):
        osm = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6">
  <node id="1" lat="20.5" lon="10.5"/>
  <node id="2" lat="20.5" lon="10.6"/>
  <node id="3" lat="22" lon="12"/>
  <node id="4" lat="22" lon="12.1"/>
  <node id="5" lat="20" lon="10"/>
  <way id="11"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="maxspeed" v="50"/><tag k="name" v="Inside"/></way>
  <way id="12"><nd ref="3"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="maxspeed" v="80"/><tag k="name" v="Outside"/></way>
  <way id="13"><nd ref="5"/><tag k="highway" v="primary"/><tag k="maxspeed" v="60"/><tag k="name" v="Boundary"/></way>
</osm>
"""
        with tempfile.TemporaryDirectory() as temp:
            pbf = Path(temp) / "fixture.osm"
            database = Path(temp) / "search.db"
            pbf.write_text(osm, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, SCRIPT, pbf, database, "10,20,11,21"],
                check=True,
                capture_output=True,
                text=True,
            )
            with sqlite3.connect(database) as connection:
                rows = connection.execute(
                    "SELECT name, maxspeed_kmh FROM road_speed ORDER BY name"
                ).fetchall()
        self.assertEqual(rows, [("Boundary", 60), ("Inside", 50)])
        self.assertIn("1 outside bbox", result.stderr)


if __name__ == "__main__":
    unittest.main()
