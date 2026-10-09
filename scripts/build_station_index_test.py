#!/usr/bin/env python3
"""Tests for build_station_index.build_index, against a hand-written fixture (no network)."""
import os
import sys
import unittest
import urllib.error
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_station_index import (  # noqa: E402
    add_route_ends, add_terminus_ends, build_index, fetch, checked_hub_batch, modes_without_lines, required_points, route_ends, station_points, terminus_ends,
)


def stop(sid, name, modes, stop_type="NaptanMetroStation", hub="", lat=51.5, lon=-0.12):
    return {"id": sid, "commonName": name, "modes": modes, "stopType": stop_type,
            "hubNaptanCode": hub, "lat": lat, "lon": lon}


class BuildIndexTest(unittest.TestCase):
    def test_stations_carry_their_modes_and_hub_and_hubs_join_the_index(self):
        index = build_index(
            [
                stop("940GZZLUEXA", "Example Underground Station", ["tube", "bus"], hub="HUBEXA"),
                stop("910GEXAMPLE", "Example Rail Station", ["national-rail"], "NaptanRailStation", hub="HUBEXA"),
            ],
            [{"id": "HUBEXA", "commonName": "Example"}],
        )
        self.assertEqual(1, index["version"])
        by_id = {s["id"]: s for s in index["stations"]}
        self.assertEqual(["910GEXAMPLE", "940GZZLUEXA", "HUBEXA"], sorted(by_id))
        self.assertEqual(["tube"], by_id["940GZZLUEXA"]["modes"], "bus is left to the live search")
        self.assertEqual("HUBEXA", by_id["940GZZLUEXA"]["hub"])
        self.assertEqual(["national-rail", "tube"], by_id["HUBEXA"]["modes"])
        self.assertNotIn("hub", by_id["HUBEXA"])

    def test_stations_carry_their_position_and_lines_by_mode(self):
        tube = stop("940GZZLUEXA", "Example Underground Station", ["tube"], lat=51.5123456, lon=-0.1234567)
        tube["lineModeGroups"] = [
            {"modeName": "tube", "lineIdentifier": ["victoria", "northern"]},
            {"modeName": "bus", "lineIdentifier": ["1"]},
        ]
        rail = stop("910GEXAMPLE", "Example Rail Station", ["national-rail"], "NaptanRailStation")
        rail["lineModeGroups"] = [{"modeName": "national-rail", "lineIdentifier": ["thameslink"]}]
        bare = stop("910GBARE", "Bare Rail Station", ["national-rail"], "NaptanRailStation")
        by_id = {s["id"]: s for s in build_index([tube, rail, bare], [])["stations"]}
        self.assertEqual(51.51235, by_id["940GZZLUEXA"]["lat"])
        self.assertEqual(-0.12346, by_id["940GZZLUEXA"]["lon"])
        self.assertEqual({"tube": ["northern", "victoria"]}, by_id["940GZZLUEXA"]["modeLines"], "buses left out, sorted")
        self.assertEqual({"national-rail": ["thameslink"]}, by_id["910GEXAMPLE"]["modeLines"])
        self.assertNotIn("modeLines", by_id["910GBARE"])

    def test_the_index_names_its_lines_as_tfl_spells_them(self):
        tube = stop("940GZZLUEXA", "Example", ["tube", "bus"])
        tube["lineModeGroups"] = [
            {"modeName": "tube", "lineIdentifier": ["hammersmith-city"]},
            {"modeName": "bus", "lineIdentifier": ["25"]},
        ]
        tube["lines"] = [
            {"id": "hammersmith-city", "name": "Hammersmith & City"},
            {"id": "25", "name": "25"},
        ]
        index = build_index([tube], [])
        self.assertEqual({"hammersmith-city": "Hammersmith & City"}, index["lineNames"], "buses left out")

    def test_a_station_listed_under_several_modes_keeps_every_listing_s_lines(self):
        from_dlr = stop("940GZZLUEXA", "Example", ["dlr"])
        from_dlr["lineModeGroups"] = [{"modeName": "dlr", "lineIdentifier": ["dlr"]}]
        from_tube = stop("940GZZLUEXA", "Example", ["tube"])
        from_tube["lineModeGroups"] = [{"modeName": "tube", "lineIdentifier": ["jubilee"]}]
        from_dlr["lines"] = [{"id": "dlr", "name": "DLR"}]
        from_tube["lines"] = [{"id": "jubilee", "name": "Jubilee"}]
        found = station_points([from_dlr, from_tube])
        self.assertEqual(1, len(found))
        self.assertEqual({"dlr": "DLR", "jubilee": "Jubilee"}, build_index(found, [])["lineNames"])
        entry = build_index(found, [])["stations"][0]
        self.assertEqual(["dlr", "tube"], entry["modes"])
        self.assertEqual({"dlr": ["dlr"], "tube": ["jubilee"]}, entry["modeLines"])

    def test_a_mode_listing_is_checked_for_its_lines_on_its_own(self):
        def dlr_station(sid, lined):
            point = stop(sid, sid, ["dlr"])
            if lined:
                point["lineModeGroups"] = [{"modeName": "dlr", "lineIdentifier": ["dlr"]}]
            return point
        good = [dlr_station(f"940GZZDL{i}", True) for i in range(10)]
        self.assertEqual(good, required_points("dlr", good, line_mode="dlr"))
        # Stripped of its line groups but for one cross-listed interchange: refused.
        stripped = [dlr_station("940GZZDL0", True)] + [dlr_station(f"940GZZDL{i}", False) for i in range(1, 10)]
        with self.assertRaises(SystemExit):
            required_points("dlr", stripped, line_mode="dlr")
        # A mode without lines to check (river bus) only needs stations.
        self.assertEqual(stripped, required_points("river-bus", stripped, line_mode="river-bus"))

    def test_a_mode_listed_without_its_lines_is_caught(self):
        tube = stop("940GZZLUEXA", "Example", ["tube"])
        tube["lineModeGroups"] = [{"modeName": "tube", "lineIdentifier": ["northern"]}]
        dlr = stop("940GZZDLEXA", "Example DLR", ["dlr"])
        index = build_index([tube, dlr], [])
        self.assertEqual(["dlr"], modes_without_lines(index))
        dlr["lineModeGroups"] = [{"modeName": "dlr", "lineIdentifier": ["dlr"]}]
        self.assertEqual([], modes_without_lines(build_index([tube, dlr], [])))

    def test_each_rail_station_carries_the_ends_of_the_routes_it_is_on(self):
        # Thameslink from two stations: one on the Bedford route, one on the Cambridge route.
        sequences = [{"orderedLineRoutes": [
            {"name": "Brighton - Bedford", "naptanIds": ["910GSOUTH", "910GTOWN", "910GNORTHA"]},
            {"name": "Brighton - Cambridge", "naptanIds": ["910GSOUTH", "910GPARK", "910GNORTHB"]},
            {"name": "Stub", "naptanIds": ["910GALONE"]},
        ]}, None]
        ends = route_ends(sequences)
        self.assertEqual({"910GSOUTH", "910GNORTHA"}, ends["910GTOWN"])
        self.assertEqual({"910GSOUTH", "910GNORTHB"}, ends["910GPARK"])
        self.assertNotIn("910GALONE", ends, "a one-stop route has no ends")
        self.assertEqual({"910GNORTHA", "910GNORTHB"}, ends["910GSOUTH"], "a terminus reaches the far ends, not itself")

        town = stop("910GTOWN", "Town Rail Station", ["national-rail"], "NaptanRailStation")
        town["lineModeGroups"] = [{"modeName": "national-rail", "lineIdentifier": ["thameslink", "other"]}]
        index = add_route_ends(build_index([town], []), {"thameslink": ends})
        entry = index["stations"][0]
        self.assertEqual({"thameslink": ["910GNORTHA", "910GSOUTH"]}, entry["routeEnds"],
                         "a service without route data is left to count by line")

    def test_a_terminus_carries_the_far_ends_of_its_routes(self):
        # The Victoria line both ways, and the Northern line's three northern ends from Morden.
        victoria = [{"orderedLineRoutes": [
            {"name": "Walthamstow Central - Brixton", "naptanIds": ["940GZZLUWWL", "940GZZLUSVS", "940GZZLUBXN"]},
        ]}, {"orderedLineRoutes": [
            {"name": "Brixton - Walthamstow Central", "naptanIds": ["940GZZLUBXN", "940GZZLUSVS", "940GZZLUWWL"]},
        ]}]
        ends = terminus_ends(victoria)
        self.assertEqual({"940GZZLUBXN"}, ends["940GZZLUWWL"])
        self.assertEqual({"940GZZLUWWL"}, ends["940GZZLUBXN"])
        self.assertNotIn("940GZZLUSVS", ends, "a station a route runs through is no terminus")
        northern = [{"orderedLineRoutes": [
            {"name": "Morden - Edgware", "naptanIds": ["940GZZLUMDN", "940GZZLUKSX", "940GZZLUEGW"]},
            {"name": "Morden - High Barnet", "naptanIds": ["940GZZLUMDN", "940GZZLUKSX", "940GZZLUHBT"]},
            {"name": "Euston - Edgware", "naptanIds": ["940GZZLUKSX", "940GZZLUEGW"]},
            {"name": "Loop", "naptanIds": ["940GZZLUBNK", "940GZZLUMGT", "940GZZLUBNK"]},
        ]}, None]
        ends = terminus_ends(northern)
        self.assertEqual({"940GZZLUEGW", "940GZZLUHBT"}, ends["940GZZLUMDN"])
        self.assertNotIn("940GZZLUKSX", ends, "an end of one route that another runs through is no terminus")
        self.assertNotIn("940GZZLUBNK", ends, "a route ending where it starts names no far end")
        self.assertNotIn("940GZZLUMGT", ends, "a circular route's interior is run through")
        # A route ending at a stop a circular route runs through doesn't make it a terminus.
        circle = [{"orderedLineRoutes": [
            {"name": "Loop", "naptanIds": ["940GZZLUERC", "940GZZLUBST", "940GZZLUKSX", "940GZZLUERC"]},
            {"name": "Spur", "naptanIds": ["940GZZLUHSC", "940GZZLUBST"]},
        ]}]
        ends = terminus_ends(circle)
        self.assertNotIn("940GZZLUBST", ends)
        self.assertNotIn("940GZZLUERC", ends)
        self.assertEqual({"940GZZLUBST"}, ends["940GZZLUHSC"])

        wwl = stop("940GZZLUWWL", "Walthamstow Central Underground Station", ["tube"], "NaptanMetroStation")
        wwl["lineModeGroups"] = [{"modeName": "tube", "lineIdentifier": ["victoria"]}]
        index = add_terminus_ends(build_index([wwl], []), {"victoria": terminus_ends(victoria)})
        self.assertEqual({"victoria": ["940GZZLUBXN"]}, index["stations"][0]["terminusEnds"])

    def test_stations_carry_the_platforms_tfl_lists_under_them(self):
        rail = dict(stop("910GEXAMPLE", "Example Rail Station", ["national-rail"], "NaptanRailStation"), children=[
            {"naptanId": "9100EXAMPLE1"},
            {"naptanId": "9100OTHER2", "stopType": "NaptanRailAccessArea"},
            {"naptanId": "4900EXAMPLE1", "stopType": "NaptanRailEntrance"},
            {"naptanId": "490000001A", "stopType": "NaptanPublicBusCoachTram"},
        ])
        tube = dict(stop("940GZZLUEXA", "Example", ["tube"]), children=[
            stop("9400ZZLUEXA2", "Platform", ["tube"], "NaptanMetroPlatform"),
            stop("9400ZZLUEXA1", "Platform", ["tube"], "NaptanMetroPlatform"),
        ])
        bare = stop("910GBARE", "Bare Rail Station", ["national-rail"], "NaptanRailStation")
        by_id = {s["id"]: s for s in build_index([rail, tube, bare], [])["stations"]}
        # Whatever code it carries: only TfL's listing says which station a platform is under.
        self.assertEqual(["9100EXAMPLE1", "9100OTHER2"], by_id["910GEXAMPLE"]["platforms"], "entrances and bus stops left out")
        self.assertEqual(["9400ZZLUEXA1", "9400ZZLUEXA2"], by_id["940GZZLUEXA"]["platforms"])
        self.assertNotIn("platforms", by_id["910GBARE"])

    def test_a_station_listed_under_several_modes_keeps_every_listing_s_platforms(self):
        from_rail = dict(stop("910GEXAMPLE", "Example", ["national-rail"], "NaptanRailStation"),
                         children=[{"naptanId": "9100EXAMPLE0"}])
        from_elizabeth = dict(stop("910GEXAMPLE", "Example", ["elizabeth-line"], "NaptanRailStation"),
                              children=[{"naptanId": "9100EXAMPLE1"}, {"naptanId": "9100EXAMPLE0"}])
        entry = build_index(station_points([from_rail, from_elizabeth]), [])["stations"][0]
        self.assertEqual(["9100EXAMPLE0", "9100EXAMPLE1"], entry["platforms"])

    def test_a_platform_listed_under_two_stations_is_neither_s(self):
        first = dict(stop("910GFIRST", "First", ["national-rail"], "NaptanRailStation"),
                     children=[{"naptanId": "9100SHARED1"}, {"naptanId": "9100FIRST1"}])
        second = dict(stop("910GSECOND", "Second", ["national-rail"], "NaptanRailStation"),
                      children=[{"naptanId": "9100SHARED1"}])
        by_id = {s["id"]: s for s in build_index([first, second], [])["stations"]}
        self.assertEqual(["9100FIRST1"], by_id["910GFIRST"]["platforms"])
        self.assertNotIn("platforms", by_id["910GSECOND"])

    def test_far_away_platform_and_modeless_stops_are_left_out(self):
        index = build_index(
            [
                stop("910GFAR", "Far Away Rail Station", ["national-rail"], "NaptanRailStation", lat=53.5, lon=-2.2),
                stop("9400ZZLUEXA1", "Example Platform", ["tube"], "NaptanMetroPlatform"),
                stop("940GZZNOMODE", "No Mode Station", ["bus"]),
                stop("", "No Id Station", ["tube"]),
            ],
            [],
        )
        self.assertEqual([], index["stations"])

    def test_a_hub_with_no_indexed_member_is_left_out(self):
        index = build_index([], [{"id": "HUBEXA", "commonName": "Example"}])
        self.assertEqual([], index["stations"])


    def test_station_points_finds_stations_nested_under_a_hub_but_not_their_platforms(self):
        hub = {
            "id": "HUBEXA", "stopType": "TransportInterchange",
            "children": [
                dict(stop("940GZZLUEXA", "Example", ["tube"]),
                     children=[stop("9400ZZLUEXA1", "Platform", ["tube"], "NaptanMetroPlatform")]),
            ],
        }
        top = stop("910GEXAMPLE", "Example Rail", ["national-rail"], "NaptanRailStation")
        found = station_points([hub, top, top])
        self.assertEqual(["940GZZLUEXA", "910GEXAMPLE"], [p["id"] for p in found])

    def test_a_required_listing_with_no_stations_stops_the_build(self):
        for points in (None, [], [stop("9400ZZLUEXA1", "Platform", ["tube"], "NaptanMetroPlatform")]):
            with self.assertRaises(SystemExit):
                required_points("tube", points)
        points = [stop("940GZZLUEXA", "Example", ["tube"])]
        self.assertEqual(points, required_points("tube", points))

    def test_a_hub_batch_missing_a_requested_hub_stops_the_build(self):
        hub_a, hub_b = {"id": "HUBEXA"}, {"id": "HUBEXB"}
        self.assertEqual([hub_a, hub_b], checked_hub_batch(["HUBEXA", "HUBEXB"], [hub_a, hub_b]))
        self.assertEqual([hub_a], checked_hub_batch(["HUBEXA"], hub_a))
        for found in ([hub_a], [], None):
            with self.assertRaises(SystemExit):
                checked_hub_batch(["HUBEXA", "HUBEXB"], found)

class FetchTest(unittest.TestCase):
    def http_error(self, code, retry_after=None):
        headers = {"Retry-After": retry_after} if retry_after else {}
        return urllib.error.HTTPError("https://api.tfl.gov.uk/x", code, "error", headers, None)

    def test_a_rate_limit_waits_and_retries(self):
        body = mock.MagicMock()
        body.__enter__.return_value.read.return_value = b'{"ok": true}'
        waits = []
        with mock.patch("urllib.request.urlopen",
                        side_effect=[self.http_error(429, "90"), self.http_error(429), body]):
            self.assertEqual({"ok": True}, fetch("/x", retry_delays=(5, 15), sleep=waits.append))
        self.assertEqual([90, 60], waits, "TfL's Retry-After, else a minute")

    def test_a_client_error_is_not_retried(self):
        waits = []
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(404)]):
            with self.assertRaises(urllib.error.HTTPError):
                fetch("/x", retry_delays=(5,), sleep=waits.append)
        self.assertEqual([], waits)

    def test_a_rate_limit_that_persists_fails_the_build(self):
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(429)] * 2):
            with self.assertRaises(urllib.error.HTTPError):
                fetch("/x", retry_delays=(5,), sleep=lambda _: None)


if __name__ == "__main__":
    unittest.main()
