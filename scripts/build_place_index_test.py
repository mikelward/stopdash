#!/usr/bin/env python3
"""Tests for build_place_index's selection, against hand-written rows (no network).

Synthetic names and positions near central London only (AGENTS.md *Privacy*): no real address."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_place_index import (  # noqa: E402
    AREA_RADIUS_METERS, DUPLICATE_METERS, area_grid, cell_of, match_key, meters_apart, near_cells, postcode_text, rank_of, render,
    ODBL_NOTICE, Notable, display_name, foursquare_license, foursquare_notice, from_osm_only, parse_wikidata, parse_wikipedia_counts, query_service, select_places, with_wikipedia_counts, select_postcodes,
    select_streets,
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

    def test_a_landmark_without_its_apostrophe_is_neither_named_nor_started_by_one_with_it(self):
        # Across London, "Omega Pauls" isn't "Omega Paul's" (a store spelled one way, a gallery the
        # other), and doesn't start "Omega Paul's Church": each is another place.
        rows = [place("Omega Pauls", confidence=1.0), place("Omega Paul's", "clothing_store", 0.9, at=FAR),
                place("Omega Paul's Church", "christian_place_of_worship", 0.9, at=FAR)]
        self.assertEqual(["Omega Paul's", "Omega Paul's Church", "Omega Pauls"], names(select_places(rows)))
        # The same with a curly apostrophe, as many listings write it.
        curly = [place("Omega Pauls", confidence=1.0), place("Omega Paul\u2019s Church", "christian_place_of_worship", 0.9, at=FAR)]
        self.assertEqual(["Omega Pauls", "Omega Paul\u2019s Church"], names(select_places(curly)))
        for mark in "\u2018\u02bc`":
            other = [place("Omega Pauls", confidence=1.0), place(f"Omega Paul{mark}s Church", "christian_place_of_worship", 0.9, at=FAR)]
            self.assertEqual(2, len(select_places(other)), mark)

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


class NotableTest(unittest.TestCase):
    # Synthetic Wikidata items: (name, lat, lon, languages with an article).
    notable = Notable([("Omega Cathedral", AT[0], AT[1], 70), ("Beta Chapel", AT[0], AT[1], 6), ("Gamma Hall", AT[0], AT[1], 2)])

    def ranks(self, rows):
        return {name: rank for name, _, _, _, rank in select_places(rows, self.notable)}

    def test_a_place_wikipedia_covers_widely_ranks_above_other_public_places(self):
        ranks = self.ranks([
            place("Omega Cathedral", "christian_place_of_worship", 0.95),
            place("Beta Chapel", "christian_place_of_worship", 0.95, at=MID),
            place("Gamma Hall", "music_venue", 0.95, at=NEAR),
            place("Delta Club", "bar", 0.95, at=(51.45, -0.12)),
        ])
        self.assertEqual({"Omega Cathedral": 9, "Beta Chapel": 7, "Gamma Hall": 7, "Delta Club": 2}, ranks)

    def test_a_shop_named_like_a_famous_thing_isnt_it(self):
        self.assertEqual({"Omega Cathedral": 2}, self.ranks([place("Omega Cathedral", "fast_food_restaurant", 0.95)]))

    def test_prominence_needs_the_place_near_the_item(self):
        near = self.ranks([place("Beta Chapel", "christian_place_of_worship", 0.95, at=NEAR)])
        self.assertEqual({"Beta Chapel": 8}, near)

    def test_a_place_named_for_a_famous_one_doesnt_share_its_prominence(self):
        self.assertEqual({"Omega Cathedral School": 7}, self.ranks([place("Omega Cathedral School", "place_of_learning", 0.95, at=NEAR)]))

    def test_a_shops_far_branches_stay_however_famous_the_name(self):
        # A chain's head office is a Wikidata item too: its shops elsewhere aren't copies of it.
        notable = Notable([("Delta Store", AT[0], AT[1], 40)])
        rows = [place("Delta Store", "grocery_store", 0.95), place("Delta Store", "grocery_store", 0.95, at=FAR)]
        self.assertEqual(2, len(select_places(rows, notable)))

    def test_wikidata_never_drops_or_moves_a_listing(self):
        # A name alone can't say a far listing is the famous item's stray rather than another place, so
        # every copy stays where Overture puts it; only the one beside the item takes its prominence.
        notable = Notable([("Omega Cathedral", AT[0], AT[1], 70), ("Omega Park", AT[0], AT[1], 21)])
        rows = [place("Omega Cathedral", "christian_place_of_worship", 0.95),
                place("Omega Cathedral", "christian_place_of_worship", 0.95, at=FAR),
                place("Omega Park", "park", 0.9, at=FAR)]
        self.assertEqual(
            sorted([("Omega Cathedral", AT[0], 9), ("Omega Cathedral", FAR[0], 7), ("Omega Park", FAR[0], 7)]),
            sorted((p[0], p[2], p[4]) for p in select_places(rows, notable)),
        )

    def test_reads_the_query_services_csv(self):
        text = ('item,name,loc,links\nhttp://www.wikidata.org/entity/Q1,Omega Cathedral,Point(-0.12 51.5),70\n'
                'http://www.wikidata.org/entity/Q2,No Place,,3\nhttp://www.wikidata.org/entity/Q3,Bad Links,Point(-0.12 51.5),x\n')
        self.assertEqual([("Omega Cathedral", 51.5, -0.12, 70, "http://www.wikidata.org/entity/Q1")], parse_wikidata(text))

    def test_the_query_service_is_retried_when_it_asks_us_to_slow_down(self):
        import io as _io, urllib.error
        calls, pauses = [], []
        def urlopen(request, timeout):
            calls.append(request)
            if len(calls) < 3:
                raise urllib.error.HTTPError(request.full_url, 429, "Too Many Requests", {"Retry-After": "7"} if len(calls) == 1 else {}, None)
            return _io.BytesIO(b"name,loc,links\n")
        self.assertEqual("name,loc,links\n", query_service("q", urlopen=urlopen, sleep=pauses.append))
        self.assertEqual([7, 20], pauses)

    def test_a_cut_off_answer_is_asked_for_again(self):
        import http.client, io as _io
        calls, pauses = [], []
        class Cut:
            def read(self):
                raise http.client.IncompleteRead(b"name,lo")
        def urlopen(request, timeout):
            calls.append(request)
            return Cut() if len(calls) == 1 else _io.BytesIO(b"name,loc,links\n")
        self.assertEqual("name,loc,links\n", query_service("q", urlopen=urlopen, sleep=pauses.append))
        self.assertEqual([10], pauses)

    def test_any_other_query_service_failure_fails_the_build(self):
        import urllib.error
        def urlopen(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 500, "Server Error", {}, None)
        with self.assertRaises(urllib.error.HTTPError):
            query_service("q", urlopen=urlopen, sleep=lambda s: self.fail("slept"))

    def test_reads_the_wikipedia_counts(self):
        text = 'item,links\nhttp://www.wikidata.org/entity/Q1,40\nnot-an-item,3\nhttp://www.wikidata.org/entity/Q2,x\n'
        self.assertEqual({"Q1": 40}, parse_wikipedia_counts(text))

    def test_prominence_counts_wikipedia_editions_not_every_sitelink(self):
        # Q1 has 30 sitelinks but 12 Wikipedia editions; Q2's 4 sitelinks can't reach a threshold, so
        # it isn't asked about; Q3 isn't answered for, so it has none.
        rows = [("Omega Hall", 51.5, -0.12, 30, "http://www.wikidata.org/entity/Q1"),
                ("Omega Shed", 51.5, -0.12, 4, "http://www.wikidata.org/entity/Q2"),
                ("Omega Arch", 51.5, -0.12, 9, "http://www.wikidata.org/entity/Q3")]
        asked = []
        def count(ids):
            asked.extend(ids)
            return {"Q1": 12}
        self.assertEqual(
            [("Omega Hall", 51.5, -0.12, 12), ("Omega Shed", 51.5, -0.12, 0), ("Omega Arch", 51.5, -0.12, 0)],
            with_wikipedia_counts(rows, count),
        )
        self.assertEqual(["Q1", "Q3"], asked)


class MatchKeyTest(unittest.TestCase):
    def test_a_trailing_city_of_london_is_noise(self):
        self.assertEqual(match_key("Omega Hall City of London"), match_key("Omega Hall"))

    def test_a_place_name_drops_where_it_says_it_is(self):
        self.assertEqual("Omega Park", display_name("Omega Park, London"))
        self.assertEqual("Omega Wall", display_name("Omega Wall, City of London"))
        # Only as a clause of its own (maintainer, 2026-10-12): run on, it may be part of the name.
        self.assertEqual("Omega Wall City of London", display_name("Omega Wall City of London"))
        self.assertEqual("Omega Trust", display_name("Omega Trust, London, UK"))
        self.assertEqual("Omega Hall", display_name("Omega Hall - City of Westminster"))
        self.assertEqual("Omega Park", display_name("Omega Park, London Borough of Omegaton"))
        self.assertEqual("Omega Park", display_name("Omega Park, Royal Borough of Omega and Zeta"))
        self.assertEqual("Omega Gallery", display_name("City of Westminster, Omega Gallery"))

    def test_a_name_keeps_an_area_it_cant_do_without(self):
        self.assertEqual("Omega Research UK", display_name("Omega Research UK"))
        # A dash with no space before it is a clause only after a name of several words.
        self.assertEqual("Hi-London", display_name("Hi-London"))
        self.assertEqual("Omega-Link-UK", display_name("Omega-Link-UK"))
        self.assertEqual("Omega Church", display_name("Omega Church-London"))
        self.assertEqual("Omega Church", display_name("Omega Church- UK"))
        self.assertEqual("Omega Hall", display_name("Omega Hall \u2013 London"))
        self.assertEqual("City of Westminster", display_name("City of Westminster"))
        self.assertEqual("London, UK", display_name("London, UK"))
        self.assertEqual("London Borough of Omega", display_name("London Borough of Omega"))
        # An area joined on by "&" or "and" is part of the name: no dangling "&".
        self.assertEqual("Omega Care Westminster & City of London", display_name("Omega Care Westminster & City of London"))
        self.assertEqual("Omega Care Soho and City of Westminster", display_name("Omega Care Soho and City of Westminster"))
        # Nor one the name leads into.
        self.assertEqual("Guild of Omegas of the City of London", display_name("Guild of Omegas of the City of London"))
        self.assertEqual("Omega Gallery in The City of London", display_name("Omega Gallery in The City of London"))

    def test_an_apostrophe_joins_its_word(self):
        self.assertEqual(match_key("Saint Omega's Hall"), match_key("Saint Omegas Hall"))

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

    def test_saint_reads_st_without_its_full_stop(self):
        text = render([("Alpha St. Omega's Church", "church", 51.5, -0.12, 9), ("Beta St.Omega Hall", "museum", 51.5, -0.12, 9)],
                      [("Gamma St.", 51.5, -0.12)], [], "test sources")
        names = [line.split("\t")[1] for line in text.splitlines()[1:]]
        self.assertEqual(["Alpha St Omega's Church", "Beta St Omega Hall", "Gamma St"], names)

    def test_saint_in_any_case_keeps_its_own_capitals(self):
        text = render([("ST. Omega Church", "church", 51.5, -0.12, 9), ("st.omegas Hall", "museum", 51.5, -0.12, 9)],
                      [("Delta ST.", 51.5, -0.12)], [], "test sources")
        names = [line.split("\t")[1] for line in text.splitlines()[1:]]
        self.assertEqual(["ST Omega Church", "st omegas Hall", "Delta ST"], names)

    def test_a_word_starting_st_is_left_alone(self):
        text = render([("Stamford Hall", "museum", 51.5, -0.12, 9)], [], [], "test sources")
        self.assertIn("P\tStamford Hall\t", text)


if __name__ == "__main__":
    unittest.main()
