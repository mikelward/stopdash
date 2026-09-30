#!/usr/bin/env python3
"""Tests for build_crs_index.py, on a synthetic NaPTAN fragment (no network)."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import build_crs_index  # noqa: E402

NS = "http://www.naptan.org.uk/"


def stop(atco, crs=None, status="active"):
    rail = (
        f"<StopClassification><OffStreet><Rail><AnnotatedRailRef>"
        f"<TiplocRef>{atco[4:]}</TiplocRef>{f'<CrsRef>{crs}</CrsRef>' if crs else ''}"
        f"</AnnotatedRailRef></Rail></OffStreet></StopClassification>"
    )
    return f'<StopPoint Status="{status}"><AtcoCode>{atco}</AtcoCode>{rail}</StopPoint>'


def xml(*stops):
    return f'<NaPTAN xmlns="{NS}"><StopPoints>{"".join(stops)}</StopPoints></NaPTAN>'.encode()


class OnePerCrsTest(unittest.TestCase):
    def test_a_station_under_several_ids_keeps_the_national_rail_one(self):
        codes = {
            "EXAMPLE1": "EXA", "EXAMPLEC": "EXA",  # an Overground-only id beside the National Rail one
            "TWINA": "TWN", "TWINB": "TWN",  # two National Rail ids, both kept
            "OTHERA": "OTH", "OTHERB": "OTH",  # neither listed by TfL: one kept
            "ONLY": "ONL",
        }
        stations = {
            "910GEXAMPLE1": ["overground"], "910GEXAMPLEC": ["national-rail", "overground"],
            "910GTWINA": ["national-rail"], "910GTWINB": ["national-rail"],
        }
        self.assertEqual(
            {"EXAMPLEC": "EXA", "TWINA": "TWN", "TWINB": "TWN", "OTHERA": "OTH", "ONLY": "ONL"},
            build_crs_index.one_per_crs(codes, stations),
        )


class ParseTest(unittest.TestCase):
    def test_active_stations_with_a_crs_map_their_tiploc_to_it(self):
        codes = build_crs_index.parse(xml(
            stop("9100EXAMPLE", "EXA"),
            stop("9100NOCODE"),
            stop("9100CLOSED", "CLO", status="inactive"),
            stop("9100LOWER", "low"),
        ))
        self.assertEqual({"EXAMPLE": "EXA", "LOWER": "LOW"}, codes)

    def test_a_short_list_is_refused(self):
        with self.assertRaises(SystemExit):
            build_crs_index.build(xml(stop("9100EXAMPLE", "EXA")))

    def test_a_code_the_board_service_refuses_is_left_out(self):
        stops = [stop(f"9100S{i:04d}", f"{chr(65 + i % 26)}{chr(65 + i // 26 % 26)}{chr(65 + i // 676 % 26)}") for i in range(2100)]
        table = build_crs_index.build(xml(*stops, stop("9100STPXBOX", "SPL"), stop("9100STPX", "STP")))
        self.assertNotIn("STPXBOX", table["codes"])
        self.assertEqual("STP", table["codes"]["STPX"])

    def test_the_table_is_sorted_and_versioned(self):
        stops = [stop(f"9100S{i:04d}", f"{chr(65 + i % 26)}{chr(65 + i // 26 % 26)}{chr(65 + i // 676 % 26)}") for i in range(2100)]
        table = build_crs_index.build(xml(*reversed(stops)))
        self.assertEqual(1, table["version"])
        self.assertEqual(sorted(table["codes"]), list(table["codes"]))
        self.assertIn("Open Government Licence", table["source"])


if __name__ == "__main__":
    unittest.main()
