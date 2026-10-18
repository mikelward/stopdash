#!/usr/bin/env python3
"""Tests for build_stops, against hand-written fixtures (no network)."""
import datetime
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_stops import (  # noqa: E402
    KEEP_DAYS, build, checked, collect, fetch_bus_pages, keep, kept_line_table, line_table, read_bus_list,
    read_kept, read_previous, required, write, write_kept,
)


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
        write(out, stops, lines, {"490000001A": ("1009001", "490000001A", "", "490000001A")}, "abc123")
        records = out.getvalue().splitlines()
        self.assertEqual("# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=abc123", records[0])
        self.assertEqual("L\t1\tbus\t1", records[1])
        fields = records[2].split("\t")
        self.assertEqual(16, len(fields))
        self.assertEqual(["S", "490000001A", "B", "Example Road  / High St", "51.500000", "-0.120000"], fields[:6])
        self.assertEqual("1", fields[11])
        self.assertEqual(["1009001", "490000001A", "", "490000001A"], fields[12:])

    def test_a_stop_without_membership_writes_its_four_columns_empty(self):
        stops, lines = build(collect([pole("490000001A", lines=["1"])], {}))
        out = io.StringIO()
        write(out, stops, lines)
        self.assertEqual(["", "", "", ""], out.getvalue().splitlines()[2].split("\t")[12:])

    def test_a_stop_carries_its_ics_code_from_whichever_listing_has_it(self):
        bare = pole("490000001A")
        coded = pole("490000001A")
        coded["icsCode"] = "1009001"
        for listings in ([bare, coded], [coded, bare]):
            stops, _ = build(collect(listings, {}))
            self.assertEqual("1009001", stops[0]["ics"])

    def test_the_revision_changes_with_lines_or_membership_and_nothing_else(self):
        from build_stops import revision
        stops, lines = build(collect([pole("490000001A", lines=["1"], towards="Example Town")], {}))
        index = {"version": 1, "stations": [{"id": "910GEXAMPL", "station": "1009001:rail", "modelHub": "HUBEXA"}]}
        columns = {"490000001A": ("1009001", "490000001A", "", "490000001A")}
        base = revision(stops, lines, columns, index)
        self.assertEqual(16, len(base))
        moved, _ = build(collect([pole("490000001A", lines=["1"], towards="Elsewhere", lat=51.6)], {}))
        self.assertEqual(base, revision(moved, lines, columns, index), "a stop's position or towards isn't membership")
        self.assertNotEqual(base, revision(stops, lines, {"490000001A": ("1009001", "490G000001", "", "490G000001")}, index))
        self.assertNotEqual(base, revision(stops, {"1": ("bus", "One")}, columns, index))
        regrouped = {"version": 1, "stations": [{"id": "910GEXAMPL", "station": "910GEXAMPL", "modelHub": "910GEXAMPL"}]}
        self.assertNotEqual(base, revision(stops, lines, columns, regrouped))
        relined = {"version": 1, "stations": [{**index["stations"][0], "modeLines": {"national-rail": ["southern"]}}]}
        self.assertNotEqual(base, revision(stops, lines, columns, relined), "an indexed station's lines count")
        renamed = {**index, "lineNames": {"southern": "Southern"}}
        self.assertNotEqual(base, revision(stops, lines, columns, renamed), "so do the index's line names")

    def test_the_revision_is_stamped_into_the_station_index_after_its_version(self):
        from build_stops import with_revision
        index = {"version": 1, "stations": [], "lineNames": {}}
        self.assertEqual(["version", "revision", "stations", "lineNames"], list(with_revision(index, "abc")))
        self.assertEqual("abc", with_revision(with_revision(index, "old"), "abc")["revision"])

    def test_a_bus_pole_without_membership_stops_the_build(self):
        from build_stops import checked_membership
        stops, _ = build(collect([pole("490000001A", lines=["1"])], {}))
        with self.assertRaises(SystemExit):
            checked_membership(stops, {})


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


TODAY = datetime.date(2026, 10, 18)


def built(*poles):
    stops, _ = build(collect(list(poles), {}))
    return stops


def written(stops):
    out = io.StringIO()
    write(out, stops, line_table(stops))
    return out.getvalue()


def kept_text(kept):
    out = io.StringIO()
    write_kept(out, kept)
    return out.getvalue()


class KeepTest(unittest.TestCase):
    """TfL's reads come and go a little; what the last build had is kept for KEEP_DAYS."""

    def setUp(self):
        self.full = pole("490000001A", lines=["1", "2"], letter="A", compass="N", towards="Example Town")
        self.previous = read_previous(written(built(self.full, pole("490000002B", lines=["3"]))))

    def test_the_last_build_reads_back_as_build_gives_it(self):
        self.assertEqual(built(self.full, pole("490000002B", lines=["3"])),
                         sorted(self.previous.values(), key=lambda s: s["id"]))

    def test_a_read_with_everything_keeps_nothing(self):
        stops, kept = keep(built(self.full, pole("490000002B", lines=["3"])), self.previous, {}, TODAY)
        self.assertEqual(built(self.full, pole("490000002B", lines=["3"])), stops)
        self.assertEqual({}, kept)

    def test_a_stop_a_line_and_a_blank_field_the_read_missed_are_kept_from_today(self):
        thin = pole("490000001A", lines=["1"], letter="A")
        stops, kept = keep(built(thin), self.previous, {}, TODAY)
        self.assertEqual(built(self.full, pole("490000002B", lines=["3"])), stops)
        self.assertEqual({("490000001A", "line:2"): TODAY, ("490000001A", "compass"): TODAY,
                          ("490000001A", "towards"): TODAY, ("490000002B", "*"): TODAY}, kept)

    def test_what_went_missing_keeps_its_first_date(self):
        earlier = TODAY - datetime.timedelta(days=10)
        _, kept = keep(built(pole("490000001A", lines=["1"], letter="A", compass="N", towards="Example Town"),
                             pole("490000002B", lines=["3"])),
                       self.previous, {("490000001A", "line:2"): earlier}, TODAY)
        self.assertEqual({("490000001A", "line:2"): earlier}, kept)

    def test_what_has_been_missing_longer_than_the_window_goes(self):
        long_ago = TODAY - datetime.timedelta(days=KEEP_DAYS + 1)
        just = TODAY - datetime.timedelta(days=KEEP_DAYS)
        thin = pole("490000001A", lines=["1"], letter="A", towards="Example Town")
        stops, kept = keep(built(thin), self.previous,
                           {("490000001A", "line:2"): long_ago, ("490000001A", "compass"): just,
                            ("490000002B", "*"): long_ago}, TODAY)
        self.assertEqual(["490000001A"], [s["id"] for s in stops])
        self.assertEqual(["1"], [line for line, _, _ in stops[0]["lines"]])
        self.assertEqual("N", stops[0]["compass"], "a field missing exactly the window is still kept")
        self.assertEqual({("490000001A", "compass"): just}, kept)

    def test_a_kept_stop_drops_its_own_kept_items_as_they_run_out(self):
        long_ago = TODAY - datetime.timedelta(days=KEEP_DAYS + 1)
        recent = TODAY - datetime.timedelta(days=3)
        stops, kept = keep([], self.previous,
                           {("490000001A", "*"): recent, ("490000001A", "line:2"): long_ago,
                            ("490000001A", "towards"): long_ago, ("490000001A", "compass"): recent}, TODAY)
        first = next(s for s in stops if s["id"] == "490000001A")
        self.assertEqual((["1"], "", "N"), ([l for l, _, _ in first["lines"]], first["towards"], first["compass"]))
        self.assertEqual({("490000001A", "*"): recent, ("490000001A", "compass"): recent,
                          ("490000002B", "*"): TODAY}, kept)

    def test_a_kept_stop_that_returns_without_something_counts_it_missing_since_the_stop_was(self):
        gone = TODAY - datetime.timedelta(days=20)
        stops, kept = keep(built(pole("490000001A", lines=["1"], letter="A", compass="N"),
                                 pole("490000002B", lines=["3"])),
                           self.previous, {("490000001A", "*"): gone}, TODAY)
        self.assertEqual(["1", "2"], [l for l, _, _ in stops[0]["lines"]])
        self.assertEqual({("490000001A", "line:2"): gone, ("490000001A", "towards"): gone}, kept)
        long_gone = TODAY - datetime.timedelta(days=KEEP_DAYS + 1)
        stops, kept = keep(built(pole("490000001A", lines=["1"], letter="A", compass="N"),
                                 pole("490000002B", lines=["3"])),
                           self.previous, {("490000001A", "*"): long_gone}, TODAY)
        self.assertEqual((["1"], ""), ([l for l, _, _ in stops[0]["lines"]], stops[0]["towards"]))
        self.assertEqual({}, kept)

    def test_a_station_the_read_misses_isn_t_kept_whole(self):
        station = pole("940GZZLUEXA", name="Example Underground Station", stop_type="NaptanMetroStation",
                       mode="tube", lines=["victoria"])
        previous = read_previous(written(built(self.full, station)))
        stops, kept = keep(built(self.full), previous, {}, TODAY)
        self.assertEqual(["490000001A"], [s["id"] for s in stops])
        self.assertEqual({}, kept)

    def test_what_comes_back_leaves_the_record_and_a_new_value_wins(self):
        moved = pole("490000001A", lines=["1", "2"], letter="A", compass="S", towards="Example Town")
        stops, kept = keep(built(moved, pole("490000002B", lines=["3"])), self.previous,
                           {("490000001A", "compass"): TODAY - datetime.timedelta(days=2)}, TODAY)
        self.assertEqual("S", stops[0]["compass"])
        self.assertEqual({}, kept)

    def test_a_kept_line_brings_its_mode_and_name_into_the_line_table(self):
        stops, _ = keep(built(pole("490000001A", lines=["1"], letter="A")), self.previous, {}, TODAY)
        self.assertEqual(("bus", "3"), line_table(stops)["3"])

    def test_a_line_s_current_name_wins_over_a_kept_copy(self):
        # Stop A misses line 3 this read and keeps the last build's copy, named "3"; stop B still
        # lists it, now under a new name. A sorts first, so the kept copy is seen first.
        previous = read_previous(written(built(pole("490000001A", lines=["3"]), pole("490000002B", lines=["3"]))))
        renamed = pole("490000002B", lines=["3"])
        renamed["lines"] = [{"id": "3", "name": "Three"}]
        read = built(pole("490000001A", lines=["1"]), renamed)
        stops, _ = keep([dict(s, lines=list(s["lines"])) for s in read], previous, {}, TODAY)
        self.assertEqual(("bus", "Three"), kept_line_table(read, stops)["3"])
        self.assertEqual(("bus", "1"), kept_line_table(read, stops)["1"])

    def test_the_record_round_trips(self):
        kept = {("490000001A", "line:2"): TODAY, ("490000002B", "*"): TODAY - datetime.timedelta(days=4)}
        self.assertEqual(kept, read_kept(kept_text(kept)))

    def test_a_file_of_another_format_keeps_nothing(self):
        self.assertEqual({}, read_previous("# stopdash stops v1\tbounds=1,2,3,4\n"))
        self.assertEqual({}, read_previous(""))
        self.assertEqual({}, read_kept(""))


if __name__ == "__main__":
    unittest.main()
