#!/usr/bin/env python3
"""Tests for build_stops, against hand-written fixtures (no network)."""
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_stops import build, checked, collect, fetch_bus_pages, read_bus_list, required, write  # noqa: E402


def pole(sid, name="Example Road", lat=51.5, lon=-0.12, lines=(), letter="", compass="", towards="",
         cluster="", hub="", stop_type="NaptanPublicBusCoachTram", mode="bus"):
    props = []
    if compass:
        props.append({"category": "Direction", "key": "CompassPoint", "value": compass})
    if towards:
        props.append({"category": "Direction", "key": "Towards", "value": towards})
    props.append({"category": "Facility", "key": "Toilets", "value": "yes"})
    return {
        "id": sid, "commonName": name, "lat": lat, "lon": lon, "stopType": stop_type, "modes": [mode],
        "stopLetter": letter, "stationNaptan": cluster, "hubNaptanCode": hub,
        "lines": [{"id": line, "name": line.upper()} for line in lines],
        "lineModeGroups": [{"modeName": mode, "lineIdentifier": list(lines)}] if lines else [],
        "additionalProperties": props,
    }


class BuildTest(unittest.TestCase):
    def test_a_pair_s_poles_are_found_and_carry_what_the_nearby_search_reads(self):
        pair = {"id": "490G000001", "stopType": "NaptanOnstreetBusCoachStopPair", "lat": 51.5, "lon": -0.12,
                "children": [pole("490000001A", lines=["n1", "1"], letter="A", compass="N", towards="Example Town",
                                  cluster="490G000001", hub="HUBEXA")]}
        stops, lines = build(collect([pair], {}))
        self.assertEqual(["490000001A"], [s["id"] for s in stops], "the pair itself isn't a stop the search answers")
        s = stops[0]
        self.assertEqual(("B", "490G000001", "HUBEXA", "A", "N", "Example Town"),
                         (s["type"], s["cluster"], s["hub"], s["letter"], s["compass"], s["towards"]))
        self.assertEqual(["1", "n1"], [line for line, _, _ in s["lines"]], "in id order")
        self.assertEqual({"1": ("bus", "1"), "n1": ("bus", "N1")}, lines)

    def test_a_stop_listed_twice_keeps_what_either_listing_carries(self):
        bare = pole("490000001A")
        bare["lines"], bare["lineModeGroups"] = [], []
        full = pole("490000001A", lines=["1"], compass="E", towards="Example Town")
        bare_with_props = pole("490000001A", compass="", towards="")
        stops, _ = build(collect([bare, full, bare_with_props], {}))
        self.assertEqual(1, len(stops))
        self.assertEqual(("E", "Example Town"), (stops[0]["compass"], stops[0]["towards"]))
        self.assertEqual(["1"], [line for line, _, _ in stops[0]["lines"]])

    def test_a_station_s_lines_keep_their_modes_and_a_line_without_a_group_takes_the_stop_s(self):
        station = pole("940GZZLUEXA", "Example Underground Station", stop_type="NaptanMetroStation", mode="tube",
                       lines=["victoria"])
        station["lines"].append({"id": "25", "name": "25"})
        station["lineModeGroups"].append({"modeName": "bus", "lineIdentifier": ["25"]})
        station["lines"].append({"id": "orphan", "name": "Orphan"})
        stops, lines = build(collect([station], {}))
        self.assertEqual([("25", "bus", "25"), ("orphan", "tube", "Orphan"), ("victoria", "tube", "VICTORIA")],
                         stops[0]["lines"])
        self.assertEqual("M", stops[0]["type"])

    def test_stops_outside_london_or_without_a_position_are_left_out(self):
        stops, _ = build(collect([pole("far", lat=53.48, lon=-2.24), pole("nowhere", lat=0.0, lon=0.0), pole("here")], {}))
        self.assertEqual(["here"], [s["id"] for s in stops])

    def test_a_stop_on_the_greenwich_meridian_is_kept(self):
        stops, _ = build(collect([pole("meridian", lat=51.48, lon=0.0)], {}))
        self.assertEqual(["meridian"], [s["id"] for s in stops])

    def test_other_stop_types_are_left_out(self):
        entrance = pole("910GEXAENT", stop_type="NaptanRailEntrance")
        stops, _ = build(collect([entrance], {}))
        self.assertEqual([], stops)

    def test_the_file_carries_a_versioned_header_lines_then_stops_and_no_stray_tabs(self):
        stops, lines = build(collect([pole("490000001A", "Example\tRoad  / High St", lines=["1"], letter="A")], {}))
        out = io.StringIO()
        write(out, stops, lines)
        records = out.getvalue().splitlines()
        self.assertEqual("# stopdash stops v1\tbounds=51.25,-0.65,51.75,0.4", records[0])
        self.assertEqual("L\t1\tbus\t1", records[1])
        fields = records[2].split("\t")
        self.assertEqual(12, len(fields))
        self.assertEqual(["S", "490000001A", "B", "Example Road  / High St", "51.500000", "-0.120000"], fields[:6])
        self.assertEqual("1", fields[11])


def full_set(skip_lines_for=""):
    """A list big enough to pass, each mode at its floor; [skip_lines_for]'s stops carry no lines."""
    from build_stops import MIN_LINED_BY_MODE
    points = []
    types = {"bus": "NaptanPublicBusCoachTram", "national-rail": "NaptanRailStation", "river-bus": "NaptanFerryPort"}
    for mode, floor in MIN_LINED_BY_MODE.items():
        count = 16000 if mode == "bus" else max(floor, 100)
        for i in range(count):
            lines = [] if mode == skip_lines_for else [f"{mode}-line"]
            points.append(pole(f"{mode}-{i}", stop_type=types.get(mode, "NaptanMetroStation"), mode=mode, lines=lines))
    stops, _ = build(collect(points, {}))
    return stops


class CheckedTest(unittest.TestCase):
    def test_a_full_list_passes(self):
        stops = full_set()
        self.assertIs(stops, checked(stops))

    def test_a_short_list_is_refused(self):
        stops, _ = build(collect([pole(f"490{i:07d}", lines=["1"]) for i in range(10)], {}))
        with self.assertRaises(SystemExit):
            checked(stops)

    def test_a_mode_answered_without_its_lines_is_refused(self):
        for mode in ("bus", "tram", "cable-car"):
            with self.subTest(mode=mode), self.assertRaises(SystemExit):
                checked(full_set(skip_lines_for=mode))


class RequiredTest(unittest.TestCase):
    def test_a_listing_with_a_usable_stop_passes(self):
        points = [pole("940GZZCREXA", stop_type="NaptanMetroStation", mode="tram")]
        self.assertEqual(points, required("tram", points))

    def test_a_listing_of_parents_alone_or_far_off_stops_is_refused(self):
        parent = {"id": "HUBEXA", "stopType": "TransportInterchange", "lat": 51.5, "lon": -0.12, "children": [
            {"id": "940GEXAENT", "stopType": "NaptanMetroEntrance", "lat": 51.5, "lon": -0.12}]}
        with self.assertRaises(SystemExit):
            required("tram", [parent])
        with self.assertRaises(SystemExit):
            required("tram", [pole("far", lat=53.48, lon=-2.24, stop_type="NaptanMetroStation")])
        with self.assertRaises(SystemExit):
            required("tram", [])


class BusPagesTest(unittest.TestCase):
    def test_pages_are_read_until_one_comes_back_short(self):
        asked = []

        def fake(path, params=None):
            asked.append(params["page"])
            size = 1000 if params["page"] < 3 else 5
            return {"stopPoints": [pole(f"{params['page']}-{i}") for i in range(size)]}

        self.assertEqual(2005, len(fetch_bus_pages(fake)))
        self.assertEqual([1, 2, 3], asked)

    def test_a_stop_one_read_of_the_list_skips_keeps_its_lines_from_another(self):
        # Paging drifts between reads: the first read misses a stop, leaving only a copy without
        # lines; the second read has it with them.
        reads = iter([[pole("a", lines=("1",))], [pole("a", lines=("1",)), pole("b", lines=("2",))]])
        def fake(path, params=None):
            return {"stopPoints": next(reads)}
        found = collect(read_bus_list(fake), {})
        collect([pole("b")], found)
        stops, _ = build(found)
        self.assertEqual({"a": ["1"], "b": ["2"]}, {s["id"]: [line for line, _, _ in s["lines"]] for s in stops})

    def test_an_empty_list_is_refused(self):
        with self.assertRaises(SystemExit):
            fetch_bus_pages(lambda path, params=None: {"stopPoints": []})


if __name__ == "__main__":
    unittest.main()
