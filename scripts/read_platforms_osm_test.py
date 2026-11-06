#!/usr/bin/env python3
"""Tests for read_platforms_osm's geometry and tag reading, on hand-written data (no network, no osmium)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from read_platforms_osm import axis, connector_kind, fraction, is_platform, meters, nearest, rows  # noqa: E402

# Synthetic points along a meridian near (51.5, -0.12): 0.001 degrees of latitude is about 110 m.
A, B = (51.5, -0.12), (51.501, -0.12)


class Geometry(unittest.TestCase):
    def test_meters(self):
        self.assertAlmostEqual(meters(A, B), 110.5, delta=0.5)

    def test_axis_is_the_farthest_pair_of_an_area(self):
        # A platform mapped as an area: a long thin rectangle, its ends the farthest corners.
        ring = [A, (51.5, -0.11995), (51.501, -0.11995), B, A]
        ends = axis(ring)
        self.assertGreater(meters(*ends), 110)

    def test_fraction_along(self):
        self.assertAlmostEqual(fraction((51.5003, -0.12), (A, B)), 0.3, places=2)
        # Off the end, clamped.
        self.assertEqual(fraction((51.502, -0.12), (A, B)), 1.0)


class Tags(unittest.TestCase):
    def test_platforms(self):
        self.assertTrue(is_platform({"railway": "platform"}))
        self.assertTrue(is_platform({"public_transport": "platform", "subway": "yes"}))
        self.assertFalse(is_platform({"public_transport": "platform", "bus": "yes"}))

    def test_connectors(self):
        self.assertEqual(connector_kind({"highway": "steps"}), "steps")
        self.assertEqual(connector_kind({"highway": "steps", "conveying": "yes"}), "escalator")
        self.assertEqual(connector_kind({"highway": "footway"}), "footway")
        self.assertIsNone(connector_kind({"highway": "residential"}))


class Rows(unittest.TestCase):
    def test_a_platform_with_stairs_a_third_along(self):
        stations = [("940GZZLUXXX", "Example", (51.5005, -0.12))]
        station = nearest(stations, A, 300)
        nodes = [(1, A), (2, (51.50033, -0.12)), (3, B)]
        routes = {42: {"Example line: A → B", "Example line: B → A"}}
        out = rows([(station, 42, {"ref": "1"}, nodes)], {2: {"steps"}}, set(), routes)
        cols = out[0].split("\t")
        self.assertEqual(cols[0], "940GZZLUXXX")
        self.assertEqual(cols[3], "1")
        self.assertEqual(cols[8], "steps@0.33")
        self.assertEqual(cols[9], "Example line: A → B|Example line: B → A")


if __name__ == "__main__":
    unittest.main()
