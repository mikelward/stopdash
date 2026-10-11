#!/usr/bin/env python3
"""Tests for build_place_index's selection, against hand-written rows (no network).

Synthetic names and positions near central London only (AGENTS.md *Privacy*): no real address."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_place_index import (  # noqa: E402
    AREA_RADIUS_METERS, DUPLICATE_METERS, area_grid, cell_of, match_key, meters_apart, near_cells, postcode_text, rank_of, render,
    ODBL_NOTICE, foursquare_license, foursquare_notice, from_osm_only, select_places, select_postcodes, select_streets,
)

AT = (51.5, -0.12)
# About 0.001 degrees of latitude is 111 m.
NEAR = (51.5010, -0.12)     # ~110 m north
MID = (51.5050, -0.12)      # ~560 m north
FAR = (51.5300, -0.12)      # ~3.3 km north


def place(name, category="museum", confidence=1.0, at=AT, status=None, meta_only=True):
    return (name, category, confidence, status, at[0], at[1], meta_only)


def names(selected):
    return sorted(p[0] for p in selected)


class SelectPlacesTest(unittest.TestCase):
    def test_public_places_need_less_confidence_than_other_places(self):
        rows = [place("Alpha Park", "park", 0.75), place("Beta Cafe", "cafe", 0.75), place("Gamma Cafe", "cafe", 0.95)]
        self.assertEqual(["Alpha Park", "Gamma Cafe"], names(select_places(rows)))

    def test_closed_unnamed_and_non_destination_places_are_left_out(self):
        rows = [place("Alpha Hall", status="permanently_closed"), place("", "museum"), place("Beta Car Park", "parking"),
                place("Gamma Station", "train_station")]
        self.assertEqual([], select_places(rows))

    def test_a_nearby_listing_of_the_same_place_merges_into_the_more_confident_one(self):
        rows = [place("Alpha Gallery", confidence=0.95, at=NEAR), place("The Alpha Gallery", confidence=1.0),
                place("Alpha Gallery London UK", confidence=0.9, at=NEAR)]
        self.assertEqual(["The Alpha Gallery"], names(select_places(rows)))

    def test_a_longer_name_nearby_merges_only_within_its_own_category(self):
        rows = [place("Alpha Gallery"), place("Alpha Gallery Cafe", "cafe", 0.95, at=NEAR),
                place("Alpha Gallery Museum", "museum", 0.95, at=NEAR)]
        self.assertEqual(["Alpha Gallery", "Alpha Gallery Cafe"], names(select_places(rows)))

    def test_one_listing_under_two_categories_is_kept_once(self):
        rows = [place("Alpha Hall", "music_venue", 1.0), place("Alpha Hall", "theatre_venue", 0.95, at=(51.50005, -0.12)),
                place("Alpha Hall", "cafe", 0.95, at=MID, meta_only=False)]
        self.assertEqual(["Alpha Hall", "Alpha Hall"], names(select_places(rows)), "the one 560 m off is another place")

    def test_duplicates_two_small_cells_apart_still_merge(self):
        # About 240 m apart north-south: over a 159 m cell boundary twice, under DUPLICATE_METERS.
        rows = [place("Gamma School", "specialty_school", 0.95), place("Gamma School", "specialty_school", 0.95, at=(51.50216, -0.12))]
        self.assertEqual(["Gamma School"], names(select_places(rows)))

    def test_far_apart_branches_of_a_business_are_all_kept(self):
        rows = [place("Delta Store", "convenience_store", 0.95), place("Delta Store", "convenience_store", 0.95, at=MID),
                place("Delta Store", "convenience_store", 0.95, at=FAR)]
        self.assertEqual(3, len(select_places(rows)))

    def test_a_landmarks_same_named_copy_goes_wherever_it_is(self):
        rows = [place("The Alpha Museum", confidence=1.0), place("Alpha Museum", confidence=0.95, at=MID),
                place("Alpha Museum", confidence=0.95, at=FAR)]
        self.assertEqual(["The Alpha Museum"], names(select_places(rows)))

    def test_a_landmarks_longer_named_listing_stays_nearby_and_goes_far_away(self):
        rows = [place("Alpha Museum", confidence=1.0), place("Alpha Museum Archives", confidence=0.9, at=MID),
                place("Alpha Museum Of Art", confidence=0.9, at=FAR)]
        self.assertEqual(["Alpha Museum", "Alpha Museum Archives"], names(select_places(rows)))

    def test_copies_another_source_vouches_for_are_branches_and_stay(self):
        spots = [(51.5 + i * 0.02, -0.12) for i in range(8)]
        rows = [place("Zeta Gallery", "art_gallery", 1.0 if i == 0 else 0.95, at=s, meta_only=False) for i, s in enumerate(spots)]
        self.assertEqual(8, len(select_places(rows)))

    def test_a_landmarks_many_stray_pages_go_whatever_their_category(self):
        # A famous hall with a dozen Meta pages across London, filed under any category.
        spots = [(51.40 + i * 0.02, -0.12) for i in range(12)]
        rows = [place("Omega Hall", "music_venue", 0.996, at=(51.62, -0.12))]
        rows += [place("Omega Hall", category, 0.9, at=s) for s, category in
                 zip(spots, ["arts_and_entertainment", "bar", "movie_theater", "music_venue"] * 3)]
        self.assertEqual([(51.62, -0.12)], [(p[2], p[3]) for p in select_places(rows)])

    def test_a_more_confident_listing_than_the_landmark_isnt_dropped(self):
        rows = [place("Omega Hall", "music_venue", 0.99), place("Omega Hall", "bar", 0.995, at=FAR)]
        self.assertEqual(2, len(select_places(rows)))

    def test_a_far_copy_stays_when_no_listing_is_confident_enough_to_be_the_landmark(self):
        rows = [place("Alpha Museum", confidence=0.95), place("Alpha Museum", confidence=0.95, at=FAR)]
        self.assertEqual(2, len(select_places(rows)))

    def test_rank_puts_public_places_above_others_whatever_the_confidence(self):
        self.assertEqual(rank_of("museum"), rank_of("park"))
        self.assertGreater(rank_of("christian_place_of_worship"), rank_of("cafe"))
        ranks = {name: rank for name, _, _, _, rank in select_places([place("Alpha Park", "park", 0.7), place("Beta Cafe", "cafe", 1.0, at=FAR)])}
        self.assertGreater(ranks["Alpha Park"], ranks["Beta Cafe"])


class MatchKeyTest(unittest.TestCase):
    def test_drops_case_punctuation_a_leading_the_and_trailing_london(self):
        self.assertEqual("alpha museum", match_key("The Alpha Museum, London UK"))
        self.assertEqual("cafe eclair", match_key("Café Éclair"))
        self.assertEqual("", match_key("!!!"))

    def test_distance_is_about_right(self):
        self.assertAlmostEqual(111, meters_apart(AT, NEAR), delta=5)


class StreetsAndPostcodesTest(unittest.TestCase):
    def test_a_street_is_named_with_its_neighborhood_and_collapses_within_it(self):
        areas = area_grid([("Exampleton", "neighborhood", 51.50, -0.12), ("Southby", "macrohood", 51.53, -0.12),
                           ("London Borough of Example", "neighborhood", 51.515, -0.12), ("Tiny", "microhood", 51.515, -0.12)])
        rows = [("Alpha Road", "road", 51.500, -0.12), ("Alpha Road", "road", 51.505, -0.12),  # two cells, one area
                ("Alpha Road", "road", 51.530, -0.12),                                          # another area
                ("Alpha Road", "road", 51.700, -0.12),                                          # none near
                ("Alpha Line", "rail", 51.5, -0.12), (None, "road", 51.5, -0.12)]
        self.assertEqual(["Alpha Road", "Alpha Road, Exampleton", "Alpha Road, Southby"],
                         sorted(name for name, _, _ in select_streets(rows, areas)))

    def test_a_street_two_old_cells_from_its_area_is_still_named_with_it(self):
        # About 2.23 km apart north-south, inside AREA_RADIUS_METERS but two of the old 2.2 km cells off.
        areas = area_grid([("Exampleton", "neighborhood", 51.53001, -0.12)])
        self.assertEqual(["Beta Road, Exampleton"],
                         [n for n, _, _ in select_streets([("Beta Road", "road", 51.50999, -0.12)], areas)])

    def test_points_within_a_cells_width_are_in_neighboring_cells(self):
        import random
        rng = random.Random(7)
        for meters in (DUPLICATE_METERS, AREA_RADIUS_METERS):
            for _ in range(2000):
                a = (51.3 + rng.random() * 0.4, -0.5 + rng.random() * 0.8)
                b = (a[0] + (rng.random() - 0.5) * meters / 111_320 * 1.4, a[1] + (rng.random() - 0.5) * meters / 69_400 * 1.4)
                if meters_apart(a, b) <= meters:
                    self.assertIn(cell_of(*b, meters), near_cells(cell_of(*a, meters)))

    def test_a_street_already_naming_its_area_isnt_given_it_again(self):
        areas = area_grid([("Exampleton", "neighborhood", 51.5, -0.12)])
        self.assertEqual(["High Street Exampleton"],
                         [n for n, _, _ in select_streets([("High Street Exampleton", "road", 51.5, -0.12)], areas)])

    def test_postcodes_are_written_with_one_space(self):
        self.assertEqual("E1 6AN", postcode_text("E1  6AN"))
        self.assertEqual("SW1A 1AA", postcode_text("SW1A1AA"))

    def test_postcodes_in_the_rectangle_are_kept_whichever_district(self):
        # A London borough's and a neighboring town's are both kept; one far off isn't read at all.
        rows = [["XX1 1XX", "10", "530000", "180000", "E92000001", "", "", "", "E09000001", ""],
                ["WW1 1WW", "10", "510000", "196000", "E92000001", "", "", "", "E07000103", ""],
                ["YY1 1YY", "10", "430000", "280000", "E92000001", "", "", "", "E07000001", ""],
                ["short"]]
        read = []
        def to_wgs84(e, n):
            read.append(e)
            return (51.5, -0.12)
        self.assertEqual(["XX1 1XX", "WW1 1WW"], [p for p, _, _ in select_postcodes(rows, to_wgs84)])
        self.assertEqual([530000.0, 510000.0], read)

    def test_a_postcode_converted_outside_the_rectangle_is_dropped(self):
        rows = [["XX1 1XX", "10", "500000", "150000", "", "", "", "", "E07000001", ""]]
        self.assertEqual([], select_postcodes(rows, lambda e, n: (51.2, -0.6)))


class SourcesTest(unittest.TestCase):
    def test_only_openstreetmaps_own_features_are_credited_by_its_line(self):
        self.assertTrue(from_osm_only([{"dataset": "OpenStreetMap"}, {"dataset": "Overture"}]))
        self.assertFalse(from_osm_only([{"dataset": "TomTom"}]))
        self.assertFalse(from_osm_only([{"dataset": "OpenStreetMap"}, {"dataset": "Esri Community Maps"}]))
        self.assertFalse(from_osm_only([]))


class FoursquareNoticeTest(unittest.TestCase):
    def test_the_notice_is_kept_whole_with_our_changes_after_it(self):
        original = "(c) Foursquare Labs, Inc.\nLicensed under the Apache License, Version 2.0.\n\n"
        shipped = foursquare_notice(original)
        self.assertTrue(shipped.startswith(original.rstrip()))
        self.assertIn("Changes by StopDash", shipped)

    def test_a_page_that_is_not_the_notice_stops_the_build(self):
        with self.assertRaises(SystemExit):
            foursquare_notice("<html>Not found</html>")

    def test_only_the_apache_license_is_shipped_as_the_license(self):
        text = "Apache License\nVersion 2.0, January 2004\n"
        self.assertEqual(text, foursquare_license(text))
        with self.assertRaises(SystemExit):
            foursquare_license("<html>Access denied</html>")


class OdblNoticeTest(unittest.TestCase):
    def test_the_street_notice_names_the_license_and_its_uri(self):
        self.assertIn("OpenStreetMap contributors", ODBL_NOTICE)
        self.assertIn("Open Database License (ODbL) v1.0", ODBL_NOTICE)
        self.assertIn("https://opendatacommons.org/licenses/odbl/1-0/", ODBL_NOTICE)


class RenderTest(unittest.TestCase):
    def test_header_then_sorted_tab_separated_lines(self):
        text = render([("Beta\tHall", "museum", 51.5, -0.12, 9), ("・・・", "museum", 51.5, -0.12, 9)],
                      [("Alpha Road", 51.5, -0.12), ("ロード", 51.5, -0.12)],
                      [("XX1 1XX", 51.5, -0.12)], "test sources")
        lines = text.splitlines()
        self.assertEqual(4, len(lines))
        self.assertEqual("# stopdash place index v1\ttest sources", lines[0])
        self.assertEqual(["C\tXX1 1XX\t\t51.50000\t-0.12000\t4", "P\tBeta Hall\tmuseum\t51.50000\t-0.12000\t9",
                          "S\tAlpha Road\t\t51.50000\t-0.12000\t4"], lines[1:])


if __name__ == "__main__":
    unittest.main()
