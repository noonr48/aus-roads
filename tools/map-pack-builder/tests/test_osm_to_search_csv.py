#!/usr/bin/env python3
"""Focused tests for relation-area search extraction."""

import csv
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "osm-to-search-csv.py"
SPEC = importlib.util.spec_from_file_location("osm_to_search_csv", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class FakeLocation:
    def __init__(self, latitude, longitude):
        self.lat = latitude
        self.lon = longitude

    def valid(self):
        return True


class FakeNodeRef:
    def __init__(self, latitude, longitude):
        self.location = FakeLocation(latitude, longitude)


class FakeArea:
    def __init__(self, outer, inner=()):
        self.outer = outer
        self.inner = inner

    def outer_rings(self):
        return iter((self.outer,))

    def inner_rings(self, outer):
        assert outer is self.outer
        return iter((self.inner,)) if self.inner else iter(())


def ring(*coordinates):
    return [FakeNodeRef(latitude, longitude) for latitude, longitude in coordinates]


class AreaCentroidTest(unittest.TestCase):
    def test_centroid_accounts_for_inner_ring(self):
        outer = ring((20, 10), (20, 14), (24, 14), (24, 10), (20, 10))
        inner = ring((21, 12), (21, 13), (22, 13), (22, 12), (21, 12))

        latitude, longitude = MODULE.area_centroid(FakeArea(outer, inner))

        self.assertAlmostEqual(latitude, 22.0333333, places=6)
        self.assertAlmostEqual(longitude, 11.9666667, places=6)

    def test_centroid_stays_precise_for_small_real_world_footprint(self):
        outer = ring(
            (-34.92000, 138.62000),
            (-34.92000, 138.62002),
            (-34.91998, 138.62002),
            (-34.91998, 138.62000),
            (-34.92000, 138.62000),
        )

        latitude, longitude = MODULE.area_centroid(FakeArea(outer))

        self.assertAlmostEqual(latitude, -34.91999, places=9)
        self.assertAlmostEqual(longitude, 138.62001, places=9)


class RelationAreaCliTest(unittest.TestCase):
    def test_relation_pois_are_emitted_without_duplicating_closed_ways(self):
        osm = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6">
  <node id="1" lat="20.1" lon="10.1"/>
  <node id="2" lat="20.1" lon="10.3"/>
  <node id="3" lat="20.3" lon="10.3"/>
  <node id="4" lat="20.3" lon="10.1"/>
  <node id="5" lat="20.4" lon="10.4"/>
  <node id="6" lat="20.4" lon="10.6"/>
  <node id="7" lat="20.6" lon="10.6"/>
  <node id="8" lat="20.6" lon="10.4"/>
  <node id="9" lat="20.7" lon="10.7"/>
  <node id="10" lat="20.7" lon="10.9"/>
  <node id="11" lat="20.9" lon="10.9"/>
  <node id="12" lat="20.9" lon="10.7"/>
  <node id="13" lat="22.0" lon="12.0"/>
  <node id="14" lat="22.0" lon="12.2"/>
  <node id="15" lat="22.2" lon="12.2"/>
  <node id="16" lat="22.2" lon="12.0"/>
  <way id="101">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/><nd ref="4"/><nd ref="1"/>
    <tag k="name" v="Way Market"/><tag k="shop" v="supermarket"/>
  </way>
  <way id="102"><nd ref="5"/><nd ref="6"/><nd ref="7"/><nd ref="8"/><nd ref="5"/></way>
  <way id="103"><nd ref="9"/><nd ref="10"/><nd ref="11"/><nd ref="12"/><nd ref="9"/></way>
  <way id="104"><nd ref="13"/><nd ref="14"/><nd ref="15"/><nd ref="16"/><nd ref="13"/></way>
  <relation id="201">
    <member type="way" ref="102" role="outer"/>
    <tag k="type" v="multipolygon"/><tag k="name" v="Relation Market"/>
    <tag k="shop" v="supermarket"/>
  </relation>
  <relation id="202">
    <member type="way" ref="103" role="outer"/>
    <tag k="type" v="multipolygon"/><tag k="name" v="Relation Museum"/>
    <tag k="tourism" v="museum"/>
  </relation>
  <relation id="203">
    <member type="way" ref="104" role="outer"/>
    <tag k="type" v="multipolygon"/><tag k="name" v="Outside Hospital"/>
    <tag k="amenity" v="hospital"/>
  </relation>
</osm>
"""
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "fixture.osm"
            output = Path(temp) / "features.csv"
            source.write_text(osm, encoding="utf-8")
            result = subprocess.run(
                [sys.executable, SCRIPT, source, output, "10,20,11,21"],
                check=True,
                capture_output=True,
                text=True,
            )
            with output.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))

        by_name = {row["name"]: row for row in rows}
        self.assertEqual(set(by_name), {"Way Market", "Relation Market", "Relation Museum"})
        self.assertEqual(sum(row["name"] == "Way Market" for row in rows), 1)
        self.assertEqual(by_name["Relation Market"]["class"], "shop=supermarket")
        self.assertEqual(by_name["Relation Museum"]["class"], "tourism=museum")
        self.assertAlmostEqual(float(by_name["Relation Market"]["lat"]), 20.5)
        self.assertAlmostEqual(float(by_name["Relation Market"]["lon"]), 10.5)
        self.assertNotIn("Outside Hospital", by_name)
        self.assertIn("poi: 3", result.stderr)


if __name__ == "__main__":
    unittest.main()
