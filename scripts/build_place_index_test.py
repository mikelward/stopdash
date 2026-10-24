#!/usr/bin/env python3
"""Tests for build_place_index's selection, against hand-written rows (no network, no osmium).

Synthetic names and positions near central London only (AGENTS.md *Privacy*): no real address."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_place_index import (  # noqa: E402
    TYPES, place_type,
    AREA_RADIUS_METERS, DUPLICATE_METERS, ODBL_NOTICE, area_grid, cell_of, display_name, geofabrik_md5, is_street,
    district_grid, district_near, match_key, meters_apart, near_cells, truncating_division, parse_wikipedia_counts, place_item, place_of, polygon_centroid, postcode_text,
    query_service, render, select_places, select_postcodes, select_streets, wikidata_id, wikipedia_editions,
)

AT = (51.5, -0.12)
# About 0.001 degrees of latitude is 111 m.
NEAR = (51.5010, -0.12)     # ~110 m north
MID = (51.5050, -0.12)      # ~560 m north
FAR = (51.5300, -0.12)      # ~3.3 km north


def place(name, category="tourism=museum", public=True, at=AT, item=None, size=0.0):
    return (name, category, public, at[0], at[1], item, size)


def names(selected):
    return sorted(p[0] for p in selected)


class PlaceOfTest(unittest.TestCase):
    def test_public_places_and_businesses(self):
        self.assertEqual(("tourism=museum", True), place_of({"name": "Alpha", "tourism": "museum"}))
        self.assertEqual(("leisure=park", True), place_of({"name": "Alpha", "leisure": "park"}))
        self.assertEqual(("office=diplomatic", True), place_of({"name": "Alpha", "office": "diplomatic"}))
        self.assertEqual(("shop=department_store", True), place_of({"name": "Alpha", "shop": "department_store"}))
        self.assertEqual(("amenity=cafe", False), place_of({"name": "Alpha", "amenity": "cafe"}))
        self.assertEqual(("shop=bakery", False), place_of({"name": "Alpha", "shop": "bakery"}))
        self.assertEqual(("office=company", False), place_of({"name": "Alpha", "office": "company"}))

    def test_a_public_tag_wins_over_a_business_one(self):
        # A museum with a gift shop is the museum; a hotel with a restaurant is the hotel.
        self.assertEqual(("tourism=museum", True), place_of({"name": "A", "shop": "gift", "tourism": "museum"}))
        self.assertEqual(("tourism=hotel", True), place_of({"name": "A", "amenity": "restaurant", "tourism": "hotel"}))

    def test_unnamed_closed_and_non_destinations_are_not_places(self):
        self.assertIsNone(place_of({"tourism": "museum"}))
        self.assertIsNone(place_of({"name": "A", "shop": "vacant"}))
        self.assertIsNone(place_of({"name": "A", "amenity": "cafe", "disused": "yes"}))
        self.assertIsNone(place_of({"name": "A", "amenity": "parking"}))
        self.assertIsNone(place_of({"name": "A", "amenity": "bicycle_rental"}))
        self.assertIsNone(place_of({"name": "A", "highway": "residential"}))

    def test_stations_and_stops_come_from_the_station_index(self):
        self.assertIsNone(place_of({"name": "A", "railway": "station", "building": "train_station", "wikidata": "Q1"}))
        self.assertIsNone(place_of({"name": "A", "public_transport": "station", "shop": "kiosk"}))
        self.assertIsNone(place_of({"name": "A Pier", "amenity": "ferry_terminal", "man_made": "pier"}))

    def test_any_other_public_tag_says_what_an_attraction_is(self):
        # An attraction tag says a place is worth seeing, not what it is.
        cathedral = {"name": "Omega Cathedral", "tourism": "attraction", "amenity": "place_of_worship",
                     "building": "cathedral", "religion": "christian"}
        self.assertEqual(("amenity=place_of_worship", True), place_of(cathedral))
        self.assertEqual("church", place_type("amenity=place_of_worship", cathedral))
        for tags, category in (
            ({"landuse": "cemetery"}, "landuse=cemetery"),
            ({"leisure": "park"}, "leisure=park"),
            ({"place": "square"}, "place=square"),
            ({"amenity": "theatre"}, "amenity=theatre"),
            ({"leisure": "stadium"}, "leisure=stadium"),
            ({"shop": "department_store"}, "shop=department_store"),
            ({"historic": "castle"}, "historic=castle"),
        ):
            self.assertEqual((category, True), place_of({"name": "Alpha", "tourism": "attraction", **tags}), category)

    def test_an_attractions_building_names_its_type(self):
        outline = {"name": "Omega Cathedral", "tourism": "attraction", "building": "cathedral", "wikidata": "Q1"}
        self.assertEqual(("building=cathedral", True), place_of(outline))
        self.assertEqual("church", place_type("building=cathedral", outline))
        self.assertEqual(("building=stadium", True), place_of({"name": "A", "tourism": "attraction", "building": "stadium"}))
        # A shop or an office is only what it was built as.
        for building in ("yes", "retail", "office", "commercial"):
            self.assertEqual(("tourism=attraction", True),
                             place_of({"name": "A", "tourism": "attraction", "building": building}), building)

    def test_an_attraction_with_nothing_else_to_say_stays_one(self):
        self.assertEqual(("tourism=attraction", True), place_of({"name": "Alpha Wheel", "tourism": "attraction"}))
        # Over a business it shelters: a famous market hall's cafe isn't what it is.
        self.assertEqual(("tourism=attraction", True),
                         place_of({"name": "Alpha Hall", "tourism": "attraction", "amenity": "cafe"}))

    def test_a_plaque_is_no_destination_but_a_war_memorial_is(self):
        self.assertIsNone(place_of({"name": "A", "historic": "memorial", "memorial": "plaque"}))
        self.assertEqual(("historic=memorial", True), place_of({"name": "A", "historic": "memorial", "memorial": "war_memorial"}))

    def test_a_named_building_is_a_place_only_when_it_names_its_wikidata_item(self):
        self.assertIsNone(place_of({"name": "Alpha House", "building": "residential"}))
        self.assertEqual(("building=yes", True), place_of({"name": "Alpha Tower", "building": "yes", "wikidata": "Q42"}))


    def test_a_stations_structures_are_not_places(self):
        # A station's bus stand under a roof would lead a search for the station itself.
        for kind in ("roof", "bus_garage", "depot", "disused_station"):
            self.assertIsNone(place_of({"name": "Alpha Station Bus Stand", "building": kind, "wikidata": "Q1"}), kind)

    def test_a_station_building_is_a_station(self):
        self.assertIsNone(place_of({"name": "Alpha Station", "building": "train_station", "wikidata": "Q1"}))
        self.assertIsNone(place_of({"name": "Alpha Station", "building": "transportation", "wikidata": "Q1"}))


class PlaceTypeTest(unittest.TestCase):
    def test_a_places_tag_names_its_type(self):
        self.assertEqual("pub", place_type("amenity=pub", {}))
        self.assertEqual("supermarket", place_type("shop=convenience", {}))
        self.assertEqual("mall", place_type("shop=department_store", {}))
        self.assertEqual("college", place_type("amenity=university", {}))
        self.assertEqual("school", place_type("amenity=kindergarten", {}))
        self.assertEqual("park", place_type("leisure=common", {}))
        self.assertEqual("theater", place_type("amenity=music_venue", {}))

    def test_any_other_shop_or_craft_is_a_shop_and_the_rest_a_place(self):
        self.assertEqual("shop", place_type("shop=bakery", {}))
        self.assertEqual("shop", place_type("craft=tailor", {}))
        self.assertEqual("office", place_type("office=estate_agent", {}))
        # Healthcare by what it is, else a plain place: not every kind is a doctor.
        self.assertEqual("doctor", place_type("healthcare=clinic", {}))
        self.assertEqual("dentist", place_type("healthcare=dentist", {}))
        self.assertEqual("hospital", place_type("healthcare=hospital", {}))
        self.assertEqual("pharmacy", place_type("healthcare=pharmacy", {}))
        for kind in ("physiotherapist", "counselling", "laboratory", "alternative", "yes"):
            self.assertEqual("health", place_type(f"healthcare={kind}", {}), kind)
        self.assertEqual("landmark", place_type("historic=monument", {}))
        self.assertEqual("landmark", place_type("building=yes", {}))
        # A building's own value names its type where it can.
        self.assertEqual("school", place_type("building=school", {}))
        self.assertEqual("college", place_type("building=university", {}))
        self.assertEqual("church", place_type("building=church", {}))
        self.assertEqual("hospital", place_type("building=hospital", {}))
        self.assertEqual("hotel", place_type("building=hotel", {}))
        self.assertEqual("landmark", place_type("building=house", {}))
        self.assertEqual("place", place_type("amenity=studio", {}))

    def test_a_place_of_worship_is_named_for_its_religion(self):
        worship = "amenity=place_of_worship"
        self.assertEqual("church", place_type(worship, {"religion": "christian"}))
        self.assertEqual("mosque", place_type(worship, {"religion": "muslim"}))
        self.assertEqual("synagogue", place_type(worship, {"religion": "jewish"}))
        for religion in ("hindu", "sikh", "buddhist", "jain"):
            self.assertEqual("temple", place_type(worship, {"religion": religion}), religion)
        # Most of London's untagged ones are churches; a religion with no type of its own is a plain place.
        self.assertEqual("church", place_type(worship, {}))
        self.assertEqual("church", place_type(worship, {"religion": " "}))
        for religion in ("multifaith", "bahai", "spiritualist"):
            self.assertEqual("place", place_type(worship, {"religion": religion}), religion)

    def test_every_type_written_is_one_the_app_knows(self):
        # The app's PlaceType has these codes; one it doesn't know would read as a plain place.
        self.assertEqual(45, len(TYPES))
        self.assertIn("place", TYPES)


class PlaceItemTest(unittest.TestCase):
    def test_a_place_is_the_item_it_names(self):
        self.assertEqual("Q1", place_item({"wikidata": "Q1"}))
        self.assertEqual("Q1", place_item({"wikidata": "Q1", "brand:wikidata": "Q2"}))
        self.assertIsNone(place_item({"brand:wikidata": "Q2"}))

    def test_a_branch_tagged_with_its_brands_item_isnt_that_item(self):
        self.assertIsNone(place_item({"wikidata": "Q2", "brand:wikidata": "Q2"}))


class StreetTest(unittest.TestCase):
    def test_named_highways_are_streets(self):
        for kind in ("residential", "primary", "footway", "service", "pedestrian", "steps"):
            self.assertTrue(is_street({"name": "Alpha Road", "highway": kind}), kind)

    def test_not_streets(self):
        self.assertFalse(is_street({"highway": "residential"}))
        self.assertFalse(is_street({"name": "Alpha Road", "highway": "proposed"}))
        self.assertFalse(is_street({"name": "Platform 1", "highway": "platform"}))
        self.assertFalse(is_street({"name": "Alpha Corridor", "highway": "corridor"}))
        self.assertFalse(is_street({"name": "Alpha Square", "highway": "pedestrian", "area": "yes"}))
        self.assertFalse(is_street({"name": "Alpha Line", "railway": "rail"}))


class WikidataIdTest(unittest.TestCase):
    def test_reads_an_item(self):
        self.assertEqual("Q42", wikidata_id("Q42"))
        self.assertEqual("Q42", wikidata_id(" Q42;Q7 "))
        self.assertIsNone(wikidata_id(None))
        self.assertIsNone(wikidata_id("q42"))
        self.assertIsNone(wikidata_id("Q0"))
        self.assertIsNone(wikidata_id("https://www.wikidata.org/wiki/Q42"))


class CentroidTest(unittest.TestCase):
    def square(self, lat, lon, d):
        return [(lat, lon), (lat, lon + d), (lat + d, lon + d), (lat + d, lon), (lat, lon)]

    def test_a_squares_middle(self):
        lat, lon, size = polygon_centroid([self.square(51.5, -0.12, 0.002)])
        self.assertAlmostEqual(51.501, lat, places=6)
        self.assertAlmostEqual(-0.119, lon, places=6)
        self.assertAlmostEqual(0.002 * 111_320 * 0.002 * 69_400, size, delta=1)

    def test_a_hole_moves_the_middle_away_from_it(self):
        outer = self.square(51.5, -0.12, 0.004)
        hole = self.square(51.5, -0.12, 0.002)  # the south-west quarter
        lat, lon, size = polygon_centroid([outer], [hole])
        self.assertGreater(lat, 51.502)
        self.assertGreater(lon, -0.118)
        self.assertAlmostEqual(0.75 * 0.004 * 111_320 * 0.004 * 69_400, size, delta=1)

    def test_rings_with_no_area_give_their_points_mean(self):
        lat, lon, size = polygon_centroid([[(51.5, -0.12), (51.502, -0.12), (51.5, -0.12)]])
        self.assertAlmostEqual(51.500667, lat, places=5)
        self.assertEqual(0.0, size)

    def test_no_points_is_no_centroid(self):
        self.assertIsNone(polygon_centroid([]))


class SelectPlacesTest(unittest.TestCase):
    def test_the_same_place_mapped_twice_at_one_spot_is_kept_once(self):
        # A point inside a building outline, whatever each is tagged as.
        rows = [place("Alpha Hall", "amenity=theatre"), place("Alpha Hall", "amenity=arts_centre", at=(51.50005, -0.12))]
        self.assertEqual(["Alpha Hall"], names(select_places(rows)))

    def test_a_public_places_site_and_building_are_one_place(self):
        rows = [place("Gamma School", "amenity=school", size=9000.0), place("Gamma School", "amenity=school", at=NEAR)]
        selected = select_places(rows)
        self.assertEqual(["Gamma School"], names(selected))
        self.assertEqual(AT[0], selected[0][2], "the larger object is kept")

    def test_nearby_branches_of_a_business_are_all_kept(self):
        rows = [place("Delta Store", "shop=convenience", False), place("Delta Store", "shop=convenience", False, at=NEAR),
                place("Delta Store", "shop=convenience", False, at=FAR)]
        self.assertEqual(3, len(select_places(rows)))

    def test_same_named_public_places_far_apart_are_two_places(self):
        rows = [place("St Omega's Church", "amenity=place_of_worship"), place("St Omega's Church", "amenity=place_of_worship", at=MID)]
        self.assertEqual(2, len(select_places(rows)))

    def test_a_place_and_another_named_for_it_are_two_places(self):
        rows = [place("Alpha Museum"), place("Alpha Museum Cafe", "amenity=cafe", False, at=NEAR)]
        self.assertEqual(["Alpha Museum", "Alpha Museum Cafe"], names(select_places(rows)))

    def test_rank_puts_public_places_above_others(self):
        ranks = {n: r for n, _, _, _, r in select_places([place("Alpha Park", "leisure=park"), place("Beta Cafe", "amenity=cafe", False, at=FAR)])}
        self.assertEqual({"Alpha Park": 7, "Beta Cafe": 2}, ranks)

    def test_a_name_drops_where_it_says_it_is(self):
        self.assertEqual(["Omega Park"], names(select_places([place("Omega Park, London", "leisure=park")])))


class NotableTest(unittest.TestCase):
    editions = {"Q1": 70, "Q2": 6, "Q3": 2, "Q4": 40}

    def ranks(self, rows):
        return {(n, lat): r for n, _, lat, _, r in select_places(rows, self.editions)}

    def test_a_place_wikipedia_covers_widely_ranks_above_other_public_places(self):
        ranks = self.ranks([
            place("Omega Cathedral", "amenity=place_of_worship", item="Q1"),
            place("Beta Chapel", "amenity=place_of_worship", at=MID, item="Q2"),
            place("Gamma Hall", "amenity=theatre", at=NEAR, item="Q3"),
            place("Delta Club", "amenity=bar", False, at=FAR),
        ])
        self.assertEqual({("Omega Cathedral", AT[0]): 9, ("Beta Chapel", MID[0]): 8, ("Gamma Hall", NEAR[0]): 7,
                          ("Delta Club", FAR[0]): 2}, ranks)

    def test_a_famous_shop_naming_its_own_item_is_famous(self):
        self.assertEqual({("Omega Toys", AT[0]): 9}, self.ranks([place("Omega Toys", "shop=toys", False, item="Q4")]))

    def test_a_chains_item_on_its_branches_ranks_none_of_them(self):
        # Mappers sometimes tag every branch with the company's item: that's a brand, not a famous place.
        rows = [place("Delta Burger", "amenity=fast_food", False, item="Q4"),
                place("Delta Burger", "amenity=fast_food", False, at=MID, item="Q4")]
        self.assertEqual({("Delta Burger", AT[0]): 2, ("Delta Burger", MID[0]): 2}, self.ranks(rows))

    def test_a_chains_item_on_one_branch_ranks_it_no_higher_than_the_rest(self):
        rows = [place("Delta Shoes", "shop=shoes", False, item="Q4"), place("Delta Shoes", "shop=shoes", False, at=FAR)]
        self.assertEqual({("Delta Shoes", AT[0]): 2, ("Delta Shoes", FAR[0]): 2}, self.ranks(rows))

    def test_a_public_place_split_in_parts_keeps_its_items_prominence(self):
        rows = [place("Omega Common", "leisure=common", item="Q1"), place("Omega Common", "leisure=common", at=FAR, item="Q1")]
        self.assertEqual({("Omega Common", AT[0]): 9, ("Omega Common", FAR[0]): 9}, self.ranks(rows))

    def test_a_place_naming_its_item_is_kept_over_a_copy_that_doesnt(self):
        rows = [place("Alpha Hall", "amenity=theatre", size=500.0), place("Alpha Hall", "amenity=theatre", at=NEAR, item="Q1")]
        self.assertEqual({("Alpha Hall", NEAR[0]): 9}, self.ranks(rows))

    def test_a_famous_places_other_objects_nearby_are_that_place(self):
        # Its shop named for it, and a second outline tagged as an attraction.
        rows = [place("Alpha Gallery", "tourism=gallery", item="Q1"), place("Alpha Gallery", "shop=books", False, at=NEAR),
                place("Alpha Gallery", "tourism=attraction", at=NEAR), place("Alpha Gallery", "shop=books", False, at=MID)]
        self.assertEqual({("Alpha Gallery", AT[0]): 9, ("Alpha Gallery", MID[0]): 2}, self.ranks(rows))

    def test_reads_the_wikipedia_counts(self):
        text = 'item,links\nhttp://www.wikidata.org/entity/Q1,40\nnot-an-item,3\nhttp://www.wikidata.org/entity/Q2,x\n'
        self.assertEqual({"Q1": 40}, parse_wikipedia_counts(text))

    def test_items_are_asked_in_batches_and_unanswered_ones_have_none(self):
        asked = []
        def count(ids):
            asked.append(list(ids))
            return {i: 5 for i in ids if i != "Q3"}
        editions = wikipedia_editions([f"Q{i}" for i in range(1, 251)], count)
        self.assertEqual([100, 100, 50], [len(a) for a in asked])
        self.assertEqual(5, editions["Q1"])
        self.assertNotIn("Q3", editions)

    def test_the_query_service_is_retried_when_it_asks_us_to_slow_down(self):
        import io as _io, urllib.error
        calls, pauses = [], []
        def urlopen(request, timeout):
            calls.append(request)
            if len(calls) < 3:
                raise urllib.error.HTTPError(request.full_url, 429, "Too Many Requests", {"Retry-After": "7"} if len(calls) == 1 else {}, None)
            return _io.BytesIO(b"item,links\n")
        self.assertEqual("item,links\n", query_service("q", urlopen=urlopen, sleep=pauses.append))
        self.assertEqual([7, 20], pauses)

    def test_a_gateway_failure_is_asked_for_again(self):
        import io as _io, urllib.error
        calls, pauses = [], []
        def urlopen(request, timeout):
            calls.append(request)
            if len(calls) == 1:
                raise urllib.error.HTTPError(request.full_url, 502, "Bad Gateway", {}, None)
            return _io.BytesIO(b"item,links\n")
        self.assertEqual("item,links\n", query_service("q", urlopen=urlopen, sleep=pauses.append))
        self.assertEqual([10], pauses)

    def test_a_cut_off_answer_is_asked_for_again(self):
        import http.client, io as _io
        calls, pauses = [], []
        class Cut:
            def read(self):
                raise http.client.IncompleteRead(b"item,li")
        def urlopen(request, timeout):
            calls.append(request)
            return Cut() if len(calls) == 1 else _io.BytesIO(b"item,links\n")
        self.assertEqual("item,links\n", query_service("q", urlopen=urlopen, sleep=pauses.append))
        self.assertEqual([10], pauses)

    def test_any_other_query_service_failure_fails_the_build(self):
        import urllib.error
        def urlopen(request, timeout):
            raise urllib.error.HTTPError(request.full_url, 500, "Server Error", {}, None)
        with self.assertRaises(urllib.error.HTTPError):
            query_service("q", urlopen=urlopen, sleep=lambda s: self.fail("slept"))


class MatchKeyTest(unittest.TestCase):
    def test_a_trailing_city_of_london_is_noise(self):
        self.assertEqual(match_key("Omega Hall City of London"), match_key("Omega Hall"))

    def test_a_place_name_drops_where_it_says_it_is(self):
        self.assertEqual("Omega Park", display_name("Omega Park, London"))
        self.assertEqual("Omega Church, Zeta Green", display_name("Omega Church,\t   Zeta Green"))
        self.assertEqual("Omega Dental Care", display_name("Omega  Dental Care"))
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
        self.assertEqual("Omega Hall", display_name("Omega Hall – London"))
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
        self.assertEqual(match_key("Saint Omega’s Hall"), match_key("Saint Omegas Hall"))

    def test_drops_case_punctuation_a_leading_the_and_trailing_london(self):
        self.assertEqual("alpha museum", match_key("The Alpha Museum, London UK"))
        self.assertEqual("cafe eclair", match_key("Café Éclair"))
        self.assertEqual("", match_key("!!!"))

    def test_distance_is_about_right(self):
        self.assertAlmostEqual(111, meters_apart(AT, NEAR), delta=5)


class StreetsAndPostcodesTest(unittest.TestCase):
    def test_a_street_is_named_with_its_neighborhood_and_collapses_within_it(self):
        areas = area_grid([("Exampleton", "neighbourhood", 51.50, -0.12), ("Southby", "suburb", 51.53, -0.12),
                           ("London Borough of Example", "suburb", 51.515, -0.12), ("Tiny", "city_block", 51.515, -0.12)])
        rows = [("Alpha Road", 51.500, -0.12), ("Alpha Road", 51.505, -0.12),  # two cells, one area
                ("Alpha Road", 51.530, -0.12),                                  # another area
                ("Alpha Road", 51.700, -0.12)]                                  # none near
        self.assertEqual(["Alpha Road", "Alpha Road, Exampleton", "Alpha Road, Southby"],
                         sorted(name for name, _, _ in select_streets(rows, areas)))

    def test_a_street_two_old_cells_from_its_area_is_still_named_with_it(self):
        # About 2.23 km apart north-south, inside AREA_RADIUS_METERS but two of the old 2.2 km cells off.
        areas = area_grid([("Exampleton", "quarter", 51.53001, -0.12)])
        self.assertEqual(["Beta Road, Exampleton"], [n for n, _, _ in select_streets([("Beta Road", 51.50999, -0.12)], areas)])

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
        areas = area_grid([("Exampleton", "neighbourhood", 51.5, -0.12)])
        self.assertEqual(["High Street Exampleton"], [n for n, _, _ in select_streets([("High Street Exampleton", 51.5, -0.12)], areas)])

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


class DownloadTest(unittest.TestCase):
    def test_reads_geofabriks_md5_file(self):
        self.assertEqual("0123456789abcdef0123456789abcdef", geofabrik_md5("0123456789abcdef0123456789abcdef  alpha-latest.osm.pbf\n"))

    def test_a_page_that_isnt_an_md5_file_stops_the_build(self):
        with self.assertRaises(SystemExit):
            geofabrik_md5("<html>Not found</html>")


class OdblNoticeTest(unittest.TestCase):
    def test_the_notice_names_the_license_its_uri_and_what_it_covers(self):
        self.assertIn("OpenStreetMap contributors", ODBL_NOTICE)
        self.assertIn("Open Database License (ODbL) v1.0", ODBL_NOTICE)
        self.assertIn("https://opendatacommons.org/licenses/odbl/1-0/", ODBL_NOTICE)
        self.assertIn("place (kind P) and street (kind S)", ODBL_NOTICE)


class RenderTest(unittest.TestCase):
    def test_header_then_sorted_tab_separated_lines(self):
        text = render([("Beta\tHall", "tourism=museum", 51.5, -0.12, 9), ("・・・", "tourism=museum", 51.5, -0.12, 9)],
                      [("Alpha Road", 51.5, -0.12), ("ロード", 51.5, -0.12)],
                      [("XX1 1XX", 51.5, -0.12)], "test sources")
        lines = text.splitlines()
        self.assertEqual(4, len(lines))
        self.assertEqual("# stopdash place index v3\ttest sources", lines[0])
        self.assertEqual(["C\tXX1 1XX\t\t51.50000\t-0.12000\t4\t", "P\tBeta Hall\ttourism=museum\t51.50000\t-0.12000\t9\tXX1",
                          "S\tAlpha Road\t\t51.50000\t-0.12000\t4\tXX1"], lines[1:])

    def test_district_selection_follows_the_apps(self):
        self.assertEqual(-3, truncating_division(-310, 100))
        self.assertEqual(3, truncating_division(310, 100))
        # Two postcodes the same distance away: the first in the file's sorted order wins, as in the app.
        grid = district_grid([("YY2 2YY", 51.5, -0.12008), ("XX1 1XX", 51.5, -0.11992)])
        self.assertEqual("XX1", district_near(grid, 51.5, -0.12))
        # Just inside the app's limit (1000 scaled units north), and just past it.
        grid = district_grid([("XX1 1XX", 51.51, -0.12)])
        self.assertEqual("XX1", district_near(grid, 51.5, -0.12))
        self.assertEqual("", district_near(grid, 51.49999, -0.12))

    def test_a_place_takes_the_district_of_the_nearest_postcode_close_by(self):
        postcodes = [("XX1 1XX", 51.5, -0.12), ("YY2 2YY", 51.503, -0.12), ("ZZ3 3ZZ", 51.53, -0.12)]
        text = render([("Alpha Hall", "c", 51.5025, -0.12, 7), ("Beta Hall", "c", 51.515, -0.12, 7)],
                      [("Gamma Road", 51.6, -0.12)], postcodes, "test sources")
        districts = {line.split("\t")[1]: line.split("\t")[6] for line in text.splitlines()[1:]}
        # Beta Hall is 1.7 km from either postcode, and Gamma Road far from any: no district.
        self.assertEqual({"Alpha Hall": "YY2", "Beta Hall": "", "Gamma Road": ""},
                         {k: districts[k] for k in ("Alpha Hall", "Beta Hall", "Gamma Road")})

    def test_a_run_of_whitespace_reads_as_one_space(self):
        text = render([("Omega Church,\t   Zeta Green", "c", 51.5, -0.12, 9)], [("Alpha  Road", 51.5, -0.12)], [], "test sources")
        self.assertEqual(["Omega Church, Zeta Green", "Alpha Road"], [line.split("\t")[1] for line in text.splitlines()[1:]])

    def test_saint_reads_st_without_its_full_stop(self):
        text = render([("Alpha St. Omega's Church", "c", 51.5, -0.12, 9), ("Beta St.Omega Hall", "m", 51.5, -0.12, 9)],
                      [("Gamma St.", 51.5, -0.12)], [], "test sources")
        self.assertEqual(["Alpha St Omega's Church", "Beta St Omega Hall", "Gamma St"], [line.split("\t")[1] for line in text.splitlines()[1:]])

    def test_saint_in_any_case_keeps_its_own_capitals(self):
        text = render([("ST. Omega Church", "c", 51.5, -0.12, 9), ("st.omegas Hall", "m", 51.5, -0.12, 9)],
                      [("Delta ST.", 51.5, -0.12)], [], "test sources")
        self.assertEqual(["ST Omega Church", "st omegas Hall", "Delta ST"], [line.split("\t")[1] for line in text.splitlines()[1:]])

    def test_a_word_starting_st_is_left_alone(self):
        self.assertIn("P\tStamford Hall\t", render([("Stamford Hall", "m", 51.5, -0.12, 9)], [], [], "test sources"))


if __name__ == "__main__":
    unittest.main()
