#!/usr/bin/env python3
"""Tests for build_step_free.py, on a synthetic station data feed (no network)."""
import csv
import io
import os
import sys
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import build_step_free  # noqa: E402

COLUMNS = {
    "FeedInfo": ["FeedPublisherName", "FeedStartDate"],
    "Stations": ["UniqueId", "Name", "OutsideStationUniqueId"],
    "Platforms": [
        "UniqueId", "StationUniqueId", "PlatformNumber", "CardinalDirection", "IsCustomerFacing",
        "AccessibleEntranceName", "HasStepFreeRouteInformation",
    ],
    "PlatformServices": [
        "PlatformUniqueId", "StopAreaNaptanCode", "Line", "MaxGap", "MaxStep",
        "DesignatedLevelAccessPoint", "LocationOfLevelAccess", "LevelAccessByManualRamp",
    ],
    "SameLevelPaths": ["From", "To"],
    "RampRoutes": ["From", "To"],
    "Lifts": [
        "StationUniqueId", "LiftUniqueId", "FromAreas", "IntermediateAreas", "IntermediateAreas2", "ToAreas",
        "LimitedCapacityLift",
    ],
}


def zip_of(tables):
    """A station data zip holding [tables] (name -> list of row dicts) as CSVs with a BOM, as TfL's."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, columns in COLUMNS.items():
            text = io.StringIO()
            writer = csv.DictWriter(text, fieldnames=columns)
            writer.writeheader()
            for row in tables.get(name, []):
                writer.writerow({c: row.get(c, "") for c in columns})
            archive.writestr(f"{name}.csv", "﻿" + text.getvalue())
    return buffer.getvalue()


def station(sid):
    return {"UniqueId": sid, "Name": sid, "OutsideStationUniqueId": f"{sid}-Outside"}


def platform(sid, number, direction="Northbound", route_info="TRUE", entrance=""):
    return {
        "UniqueId": f"{sid}-Plat{number}", "StationUniqueId": sid, "PlatformNumber": number,
        "CardinalDirection": direction, "IsCustomerFacing": "TRUE",
        "AccessibleEntranceName": entrance, "HasStepFreeRouteInformation": route_info,
    }


def service(sid, number, line, stop=None, gap="", step="", designated="False", where="", ramp="False"):
    return {
        "PlatformUniqueId": f"{sid}-Plat{number}", "StopAreaNaptanCode": stop or sid, "Line": line,
        "MaxGap": gap, "MaxStep": step, "DesignatedLevelAccessPoint": designated,
        "LocationOfLevelAccess": where, "LevelAccessByManualRamp": ramp,
    }


def path(a, b):
    return [{"From": a, "To": b}, {"From": b, "To": a}]


def filler(count):
    """[count] plain stations, each with one level platform, so a test's table passes the
    builder's size check; and one platform for each line the builder requires."""
    tables = {"Stations": [], "Platforms": [], "PlatformServices": [], "SameLevelPaths": []}
    lines = sorted(build_step_free.REQUIRED_LINES)
    for i in range(count):
        sid = f"940GFILL{i:04d}"
        tables["Stations"].append(station(sid))
        tables["Platforms"].append(platform(sid, "1"))
        tables["PlatformServices"].append(service(sid, "1", lines[i % len(lines)], designated="TRUE"))
        tables["SameLevelPaths"] += path(f"{sid}-Outside", f"{sid}-Plat1")
    return tables


def merged(*parts):
    tables = {}
    for part in parts:
        for name, rows in part.items():
            tables.setdefault(name, []).extend(rows)
    return tables


def build(tables):
    return build_step_free.build(build_step_free.read_feed(zip_of(merged(filler(build_step_free.MIN_STOPS), tables))))


class BuildTest(unittest.TestCase):
    def test_a_platform_the_walking_map_reaches_from_the_street_is_step_free(self):
        a = "940GZZEXMPA"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1"), platform(a, "2", "Southbound")],
            "PlatformServices": [service(a, "1", "victoria", gap="85", step="50"), service(a, "2", "victoria", gap="86", step="50")],
            # Street, ticket hall, a lift down to the platform level; platform 2 by stairs only.
            "SameLevelPaths": path(f"{a}-Outside", "hall") + path("low", f"{a}-Plat1"),
            "Lifts": [{"StationUniqueId": a, "LiftUniqueId": f"{a}-Lift-1", "FromAreas": "hall", "ToAreas": "low",
                       "LimitedCapacityLift": "False"}],
        })
        self.assertEqual(
            {"victoria": [
                {"platform": "1", "direction": "Northbound", "level": "level", "station": a, "node": 1},
                {"platform": "2", "direction": "Southbound", "level": "none"},
            ]},
            table["stops"][a],
        )

    def test_a_platform_only_lifts_reach_is_cut_off_while_every_lift_that_gets_there_is_out(self):
        a = "940GZZEXMPF"
        lift = lambda n, frm, to: {"StationUniqueId": a, "LiftUniqueId": f"{a}-Lift-{n}", "FromAreas": frm,
                                   "ToAreas": to, "LimitedCapacityLift": "False"}
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, n) for n in ("1", "2", "3")],
            "PlatformServices": [service(a, n, "district", designated="TRUE") for n in ("1", "2", "3")],
            # The street and its ticket hall; lifts A and B down to platform 1's level, C to platform
            # 2's; platform 3 on the street's own level.
            "SameLevelPaths": (path(f"{a}-Outside", "hall") + path("deep", f"{a}-Plat1") + path("mid", f"{a}-Plat2")
                               + path("hall", f"{a}-Plat3")),
            "Lifts": [lift("A", "hall", "deep"), lift("B", "hall", "deep"), lift("C", "hall", "mid")],
        })
        entries = {e["platform"]: e for e in table["stops"][a]["district"]}
        graph = table["stations"][a]
        self.assertNotIn("node", entries["3"])
        self.assertEqual({f"{a}-Lift-A", f"{a}-Lift-B", f"{a}-Lift-C"}, set(graph["lifts"]))
        reaches = build_step_free.reaches
        self.assertTrue(reaches(graph, entries["1"]["node"], {f"{a}-Lift-A"}))
        self.assertFalse(reaches(graph, entries["1"]["node"], {f"{a}-Lift-A", f"{a}-Lift-B"}))
        self.assertFalse(reaches(graph, entries["2"]["node"], {f"{a}-Lift-C"}))
        self.assertTrue(reaches(graph, entries["2"]["node"], {f"{a}-Lift-A"}))
        # The street and its hall walk to each other, so they're the one node, and every lift leaves it.
        self.assertTrue(all(0 in nodes for nodes in graph["lifts"].values()))

    def test_a_lift_map_keeps_one_way_paths_one_way(self):
        a = "940GZZEXMPG"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1")],
            "PlatformServices": [service(a, "1", "jubilee", designated="TRUE")],
            # A way down by lift, and a one-way exit from the platform's level back to the street.
            "SameLevelPaths": [{"From": "low", "To": f"{a}-Outside"}] + path("low", f"{a}-Plat1"),
            "Lifts": [{"StationUniqueId": a, "LiftUniqueId": f"{a}-Lift-1", "FromAreas": f"{a}-Outside",
                       "ToAreas": "low", "LimitedCapacityLift": "False"}],
        })
        entry = table["stops"][a]["jubilee"][0]
        graph = table["stations"][a]
        self.assertEqual([[entry["node"], 0]], graph["walks"])
        self.assertFalse(build_step_free.reaches(graph, entry["node"], {f"{a}-Lift-1"}))

    def test_a_lift_tfl_gives_no_id_is_never_out(self):
        a = "940GZZEXMPH"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1")],
            "PlatformServices": [service(a, "1", "central", designated="TRUE")],
            "SameLevelPaths": path("low", f"{a}-Plat1"),
            "Lifts": [{"StationUniqueId": a, "FromAreas": f"{a}-Outside", "ToAreas": "low", "LimitedCapacityLift": "False"}],
        })
        # The lift joins the street and the platform as a walk would: nothing can cut it off.
        self.assertNotIn("node", table["stops"][a]["central"][0])
        self.assertNotIn(a, table["stations"])

    def test_onto_the_train_level_at_a_marked_spot_by_ramp_or_by_a_step(self):
        a = "940GZZEXMPB"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, n) for n in ("1", "2", "3", "4")],
            "PlatformServices": [
                service(a, "1", "jubilee", designated="TRUE", where="Car 4, first door."),
                service(a, "2", "jubilee", ramp="TRUE"),
                service(a, "3", "jubilee", gap="120", step="200"),
                # Level by its gap and step, though not marked: no marked spot to name.
                service(a, "4", "jubilee", gap="0", step="0"),
            ],
            "SameLevelPaths": [p for n in ("1", "2", "3", "4") for p in path(f"{a}-Outside", f"{a}-Plat{n}")],
        })
        self.assertEqual(
            [
                {"platform": "1", "direction": "Northbound", "level": "level", "where": "Car 4, first door"},
                {"platform": "2", "direction": "Northbound", "level": "ramp"},
                {"platform": "3", "direction": "Northbound", "level": "platform"},
                {"platform": "4", "direction": "Northbound", "level": "level"},
            ],
            table["stops"][a]["jubilee"],
        )

    def test_a_route_through_a_limited_capacity_lift_is_marked_and_an_entrance_named(self):
        a = "910GEXAMPLE"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1", entrance="Example Road")],
            "PlatformServices": [service(a, "1", "national-rail", ramp="TRUE")],
            "SameLevelPaths": path(f"{a}-Outside", "street") + path("deck", f"{a}-Plat1"),
            "Lifts": [{"StationUniqueId": a, "LiftUniqueId": f"{a}-Lift-1", "FromAreas": "street",
                       "IntermediateAreas": "mid", "ToAreas": "deck", "LimitedCapacityLift": "TRUE"}],
        })
        self.assertEqual(
            [{"platform": "1", "direction": "Northbound", "level": "ramp", "limitedLift": True, "entrance": "Example Road",
              "station": a, "node": 1}],
            table["stops"][a]["national-rail"],
        )

    def test_a_ramp_route_joins_areas_one_way_as_tfl_lists_it(self):
        a = "940GZZEXMPC"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1")],
            "PlatformServices": [service(a, "1", "district", ramp="TRUE")],
            "RampRoutes": [{"From": f"{a}-Outside", "To": "ramped"}],
            "SameLevelPaths": path("ramped", f"{a}-Plat1"),
        })
        self.assertEqual("ramp", table["stops"][a]["district"][0]["level"])

    def test_a_platform_without_route_information_is_left_out_not_called_none(self):
        a = "940GZZEXMPD"
        table = build({
            "Stations": [station(a)],
            "Platforms": [platform(a, "1", route_info="FALSE "), platform(a, "2")],
            "PlatformServices": [service(a, "1", "central"), service(a, "2", "central")],
        })
        self.assertEqual([{"platform": "2", "direction": "Northbound", "level": "none"}], table["stops"][a]["central"])

    def test_by_stop_area_and_line_with_platforms_in_number_order(self):
        hub = "HUBEXM"
        table = build({
            "Stations": [station(hub)],
            "Platforms": [platform(hub, n, direction="") for n in ("10", "3A", "2", "3")],
            "PlatformServices": [service(hub, n, line, stop="940GZZEXMPE")
                                 for n in ("10", "3A", "2", "3") for line in ("district", "circle")],
        })
        lines = table["stops"]["940GZZEXMPE"]
        self.assertEqual(["circle", "district"], list(lines))
        self.assertEqual(["2", "3", "3A", "10"], [e["platform"] for e in lines["district"]])
        self.assertNotIn(hub, table["stops"])

    def test_the_table_is_versioned_and_dated(self):
        table = build({"FeedInfo": [{"FeedPublisherName": "Transport for London", "FeedStartDate": "2026-08-03T09:14+00:00"}]})
        self.assertEqual(1, table["version"])
        self.assertEqual("2026-08-03", table["published"])
        self.assertIn("TfL", table["source"])
        self.assertEqual(sorted(table["stops"]), list(table["stops"]))

    def test_a_short_table_or_a_missing_line_is_refused(self):
        with self.assertRaises(SystemExit):
            build_step_free.build(build_step_free.read_feed(zip_of(filler(build_step_free.MIN_STOPS - 1))))
        without_dlr = filler(build_step_free.MIN_STOPS + 10)
        without_dlr["PlatformServices"] = [s for s in without_dlr["PlatformServices"] if s["Line"] != "dlr"]
        with self.assertRaises(SystemExit):
            build_step_free.build(build_step_free.read_feed(zip_of(without_dlr)))


if __name__ == "__main__":
    unittest.main()
