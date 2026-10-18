#!/usr/bin/env python3
"""Tests for stop_membership, on synthetic stops and stations (no network, no real bus stop)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from stop_membership import match_name, membership, name_parts  # noqa: E402

LAT, LON = 51.5, -0.12
METERS_PER_DEGREE = 111_320.0


def at(north=0.0, east=0.0):
    """A point [north] and [east] meters from a fixed synthetic origin."""
    return LAT + north / METERS_PER_DEGREE, LON + east / (METERS_PER_DEGREE * 0.6225)


def pole(sid, name="Example Road", north=0.0, east=0.0, cluster="", hub="", ics=""):
    lat, lon = at(north, east)
    return {"id": sid, "type": "B", "name": name, "lat": lat, "lon": lon, "cluster": cluster, "hub": hub,
            "ics": ics, "lines": [("1", "bus", "1")]}


def row(sid, stop_type="R", name="Example Rail Station", north=0.0, east=0.0, hub="", ics="", lines=(("x", "national-rail", "X"),)):
    lat, lon = at(north, east)
    return {"id": sid, "type": stop_type, "name": name, "lat": lat, "lon": lon, "cluster": sid, "hub": hub,
            "ics": ics, "lines": list(lines)}


def station(sid, name="Hill Rail Station", north=0.0, east=0.0, hub="", ics="", key=None):
    lat, lon = at(north, east)
    key = key or (f"{ics}:rail" if ics else sid)
    return {"id": sid, "name": name, "lat": lat, "lon": lon, "modes": ["national-rail"],
            **({"hub": hub} if hub else {}), **({"icsCode": ics} if ics else {}),
            "station": key, "modelHub": hub or key}


def index(*stations):
    return {"version": 1, "stations": list(stations)}


class NamesTest(unittest.TestCase):
    def test_names_are_compared_as_the_app_compares_them(self):
        self.assertEqual(("hill", "high road"), name_parts("Hill Station  / High Road"))
        self.assertEqual("hill", match_name("Hill Rail Station"))
        self.assertEqual("st johns", match_name("St. John's"))
        self.assertEqual("hammersmith", match_name("Hammersmith (H&C Line) Underground Station"))


class StopsTest(unittest.TestCase):
    def stop_of(self, poles, stations=index()):
        columns = membership(poles, stations)
        return {sid: c[1] for sid, c in columns.items()}

    def test_a_clean_area_is_one_stop_named_by_the_area(self):
        stops = self.stop_of([pole("490000001A", cluster="490G000001"), pole("490000001B", north=40, cluster="490G000001")])
        self.assertEqual({"490000001A": "490G000001", "490000001B": "490G000001"}, stops)

    def test_an_area_of_two_names_is_split_into_stops_named_by_their_anchor_poles(self):
        stops = self.stop_of([
            pole("490000001A", "Example Road", cluster="490G000001"),
            pole("490000001B", "Other Lane", north=40, cluster="490G000001"),
        ])
        self.assertEqual({"490000001A": "490000001A", "490000001B": "490000001B"}, stops)

    def test_an_area_spanning_more_than_250_m_is_split_and_its_poles_regroup_by_name_and_distance(self):
        stops = self.stop_of([
            pole("490000001A", cluster="490G000001"),
            pole("490000001B", north=100, cluster="490G000001"),
            pole("490000001C", north=400, cluster="490G000001"),
        ])
        self.assertEqual("490000001A", stops["490000001A"])
        self.assertEqual("490000001A", stops["490000001B"])
        self.assertEqual("490000001C", stops["490000001C"])

    def test_a_same_named_pair_in_two_areas_or_none_is_one_stop(self):
        filed = self.stop_of([pole("490000001A", cluster="490G000001"), pole("490000002B", north=30, cluster="490G000002")])
        self.assertEqual(1, len(set(filed.values())))
        loose = self.stop_of([pole("490000001A"), pole("490000002B", north=30)])
        self.assertEqual({"490000001A"}, set(loose.values()))

    def test_two_clean_areas_meeting_end_to_end_stay_two_stops(self):
        stops = self.stop_of([
            pole("490000001A", cluster="490G000001"), pole("490000001B", north=240, cluster="490G000001"),
            pole("490000002A", north=480, cluster="490G000002"), pole("490000002B", north=720, cluster="490G000002"),
        ])
        self.assertEqual(2, len(set(stops.values())))
        self.assertEqual(stops["490000001A"], stops["490000001B"])
        self.assertEqual(stops["490000002A"], stops["490000002B"])

    def test_three_namesakes_in_a_row_give_two_stops_whichever_sorts_first(self):
        for middle in ("490000000A", "490000005A", "490000009A"):
            ids = sorted({"490000001A", "490000008A", middle} - {middle})
            poles = [pole(ids[0], north=0), pole(middle, north=200), pole(ids[1], north=400)]
            stops = self.stop_of(poles)
            self.assertEqual(2, len(set(stops.values())), middle)
            self.assertNotEqual(stops[ids[0]], stops[ids[1]], "the outer two are never one stop")

    def test_membership_is_the_same_whatever_order_the_bundle_lists_its_stops(self):
        poles = [pole("490000001A", cluster="490G000001"), pole("490000001B", north=40, cluster="490G000001"),
                 pole("490000003A", "High Road / Church Street", north=100),
                 pole("490000004A", "Church Street / High Road", north=120, east=50)]
        stations = index(station("910GHILL", north=150, ics="1009001"))
        self.assertEqual(membership(poles, stations), membership(list(reversed(poles)), stations))

    def test_a_pole_filed_under_a_station_record_is_a_stop_of_its_own_owned_by_that_station(self):
        columns = membership([pole("490000001A", "Forecourt", cluster="910GHILL", north=300)],
                             index(station("910GHILL", ics="1009001")))
        self.assertEqual(("", "490000001A", "1009001:rail", "1009001:rail"), columns["490000001A"])


class JunctionsTest(unittest.TestCase):
    def hubs(self, poles, stations=index()):
        return {sid: c[3] for sid, c in membership(poles, stations).items()}

    def test_two_stops_named_in_either_order_are_two_stops_in_one_hub(self):
        poles = [pole("490000001A", "High Road / Church Street"), pole("490000002A", "Church Street / High Road", east=60)]
        columns = membership(poles, index())
        self.assertNotEqual(columns["490000001A"][1], columns["490000002A"][1])
        self.assertEqual(columns["490000001A"][3], columns["490000002A"][3])

    def test_two_stops_sharing_only_their_road_stay_apart(self):
        hubs = self.hubs([pole("490000001A", "High Road / Church Street"), pole("490000002A", "High Road / Mill Lane", east=60)])
        self.assertNotEqual(hubs["490000001A"], hubs["490000002A"])

    def test_a_one_part_name_joins_only_the_nearer_of_two_junctions(self):
        hubs = self.hubs([
            pole("490000001A", "High Road / Church Street", north=-100),
            pole("490000002A", "High Road / Mill Lane", north=150),
            pole("490000003A", "High Road"),
        ])
        self.assertEqual(hubs["490000001A"], hubs["490000003A"])
        self.assertNotEqual(hubs["490000002A"], hubs["490000003A"])

    def test_one_part_stops_with_no_junction_in_reach_group_by_their_name(self):
        hubs = self.hubs([pole("490000001A", "High Road"), pole("490000002A", "High Road", east=150, cluster="490G000002"),
                          pole("490000003A", "High Road", east=150, north=100, cluster="490G000002")])
        self.assertEqual(1, len(set(hubs.values())))


class OwnersTest(unittest.TestCase):
    def owners(self, rows, stations):
        return {sid: c[2] for sid, c in membership(rows, stations).items()}

    def test_a_station_takes_in_its_namesake_poles_on_either_side_of_a_slash(self):
        owners = self.owners([
            pole("490000001A", "Hill Station", north=230),
            pole("490000002A", "Hill Station / High Road", north=-230),
            pole("490000003A", "Vale Road / Hill Station", east=230),
        ], index(station("910GHILL", ics="1009001")))
        self.assertEqual({"1009001:rail"}, set(owners.values()))

    def test_a_stop_of_any_name_within_200_m_is_the_nearest_station_s(self):
        owners = self.owners([pole("490000001A", "Example Road", north=190), pole("490000002A", "Far Road", north=600)],
                             index(station("910GHILL", ics="1009001")))
        self.assertEqual("1009001:rail", owners["490000001A"])
        self.assertEqual("", owners["490000002A"], "beyond the reach, no station owns it")

    def test_a_stop_within_reach_of_two_stations_is_the_nearer_one_s(self):
        owners = self.owners([pole("490000001A", "Example Road", north=120)], index(
            station("910GHILL", ics="1009001"), station("910GVALE", "Vale Rail Station", north=190, ics="1009002")))
        self.assertEqual("1009002:rail", owners["490000001A"])

    def test_a_stop_filed_under_an_interchange_is_one_of_its_stations_s_however_far(self):
        columns = membership([pole("490000001A", "Example Road", north=600, hub="HUBEXA")], index(
            station("910GHILL", hub="HUBEXA", ics="1009001"),
            station("940GZZLUHIL", "Hill Underground Station", hub="HUBEXA", north=300, ics="1009002", key="1009002:tube"),
            station("910GVALE", "Vale Rail Station", north=590, ics="1009003")))
        self.assertEqual(("1009002:tube", "HUBEXA"), columns["490000001A"][2:])

    def test_a_pole_carrying_a_station_s_ics_code_is_that_station_s(self):
        owners = self.owners([pole("490000001A", "Example Road", north=150, ics="1009002")], index(
            station("910GHILL", ics="1009001"), station("910GVALE", "Vale Rail Station", north=1000, ics="1009002")))
        self.assertEqual("1009002:rail", owners["490000001A"])

    def test_a_junction_reaching_into_a_station_s_reach_is_owned_whole(self):
        columns = membership([
            pole("490000001A", "High Road / Church Street", north=150),
            pole("490000002A", "Church Street / High Road", north=230),
        ], index(station("910GHILL", ics="1009001")))
        self.assertEqual({("1009001:rail", "1009001:rail")}, {c[2:] for c in columns.values()})

    def test_an_unowned_stop_is_its_own_hub(self):
        columns = membership([pole("490000001A", cluster="490G000001"), pole("490000001B", north=30, cluster="490G000001")], index())
        self.assertEqual({("", "490G000001")}, {c[2:] for c in columns.values()})


class StationRowsTest(unittest.TestCase):
    def test_station_rows_the_index_leaves_out_get_a_station_and_hub(self):
        columns = membership([
            row("910GHILLLL", ics="1009001", hub="HUBEXA"),
            row("910GVALE", name="Vale Rail Station", ics="1009005"),
            row("930GEXA", "F", "Example Pier", lines=()),
            row("940GZZRLEXA", "M", "Example (Miniature Railway)", ics="1009001", lines=()),
        ], index(station("910GHILL", hub="HUBEXA", ics="1009001")))
        self.assertEqual(("1009001", "1009001:rail", "", "HUBEXA"), columns["910GHILLLL"])
        self.assertEqual(("1009005", "1009005:rail", "", "1009005:rail"), columns["910GVALE"])
        self.assertEqual(("", "930GEXA", "", "930GEXA"), columns["930GEXA"])
        self.assertEqual(("1009001", "940GZZRLEXA", "", "940GZZRLEXA"), columns["940GZZRLEXA"],
                         "a miniature railway groups with nothing")

    def test_rows_in_the_index_and_all_bus_areas_typed_as_stations_carry_none(self):
        bus_area = row("490G000001", "M", "Example Station", lines=(("1", "bus", "1"),))
        columns = membership([row("910GHILL", ics="1009001"), bus_area, pole("490000001A", cluster="490G000001")],
                             index(station("910GHILL", ics="1009001")))
        self.assertNotIn("910GHILL", columns)
        self.assertNotIn("490G000001", columns)
        self.assertEqual("490G000001", columns["490000001A"][1], "the bus area still names its pole's stop")

    def test_an_omitted_station_row_of_an_unknown_prefix_stops_the_build(self):
        with self.assertRaises(SystemExit):
            membership([row("940GZZXXEXA", "M")], index())

    def test_an_index_without_membership_stops_the_build(self):
        with self.assertRaises(SystemExit):
            membership([], {"version": 1, "stations": [{"id": "910GHILL", "name": "Hill"}]})


class ChangesTest(unittest.TestCase):
    """membership_changes, which lists a refresh's regrouping in its pull request."""

    def stops_tsv(self, *rows):
        return "\n".join(["# stopdash stops v2\tbounds=51.25,-0.65,51.75,0.4\trevision=x"] + [
            "\t".join(["S", sid, kind, "Example", "51.5", "-0.12", "", "", "", "", "", "", "", level, owner, hub])
            for sid, kind, level, owner, hub in rows])

    def test_a_pole_moving_stop_or_owner_within_one_hub_is_listed(self):
        from membership_changes import changes, read_stops
        old = read_stops(self.stops_tsv(("490000001A", "B", "490G000001", "1009001:rail", "HUBEXA"),
                                        ("490000002A", "B", "490G000002", "", "490G000002")))
        new = read_stops(self.stops_tsv(("490000001A", "B", "490G000009", "1009002:tube", "HUBEXA"),
                                        ("490000002A", "B", "490G000002", "", "HUBEXA")))
        found, added, removed = changes(old, new)
        self.assertEqual([("490000001A", "490G000001", "490G000009")], found["stop"])
        self.assertEqual([("490000001A", "1009001:rail", "1009002:tube")], found["owner"])
        self.assertEqual([("490000002A", "490G000002", "HUBEXA")], found["hub"])
        self.assertEqual(([], []), (added, removed))

    def test_a_station_regrouped_in_the_index_is_listed(self):
        from membership_changes import changes, read_index
        old = read_index({"stations": [{"id": "910GHILL", "station": "910GHILL", "modelHub": "910GHILL"},
                                       {"id": "HUBEXA", "name": "Example"}]})
        new = read_index({"stations": [{"id": "910GHILL", "station": "1009001:rail", "modelHub": "HUBEXA"}]})
        found, _, _ = changes(old, new)
        self.assertEqual([("910GHILL", "910GHILL", "1009001:rail")], found["station"])
        self.assertEqual([("910GHILL", "910GHILL", "HUBEXA")], found["hub"])

    def test_the_body_caps_each_category_and_states_its_total(self):
        from membership_changes import markdown
        found = {"station": [], "stop": [(f"4900000{i:02d}A", "a", "b") for i in range(5)], "owner": [], "hub": []}
        body = markdown(found, ["490000099A"], [], cap=2)
        self.assertIn("**Stop** (5 changed)", body)
        self.assertEqual(2, body.count("`a` → `b`"))
        self.assertIn("… and 3 more", body)
        self.assertIn("**New ids** (1)", body)
        self.assertNotIn("Hub", body)

    def test_no_change_says_so(self):
        from membership_changes import markdown
        self.assertIn("No station, stop, owner or hub changed.",
                      markdown({"station": [], "stop": [], "owner": [], "hub": []}, [], []))


if __name__ == "__main__":
    unittest.main()
