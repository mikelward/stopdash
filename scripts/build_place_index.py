#!/usr/bin/env python3
"""Build stopdash's bundled London place index (dev-docs/PLACES.md, option 4).

Writes the tab-separated file the app reads as `assets/places/place_index.tsv`: the places, streets
and postcodes a *To…* search can route to, found on the device as the rider types, with nothing sent
anywhere. Each line is one destination:

    kind  name  category  latitude  longitude  rank  district

- kind: `P` a place (a venue, a business, a park…), `S` a street, `C` a postcode.
- category: a place's type (`pub`, `museum`, `church`…, one of TYPES), from the OpenStreetMap tag
  that makes it a place, shown on its row as an icon; empty for a street or postcode (format 3; before
  it the column held the raw tag, which the app didn't read).
- latitude, longitude: degrees to five places (about a meter).
- rank: 0–9, how prominent the entry is, to order equal matches: a place Wikipedia covers in many
  languages 9 (20 or more) or 8 (5 or more), any other public place 7, a street or postcode 4, any
  other place 2.
- district: a place's or street's postcode district ("N10"), exactly as the app works out the one its
  row shows (PlaceIndex.districtNear), so a name and the district on its row find it; empty for a
  postcode, or with no postcode within about 1.1 km (format 2; format 1 had no such column).

The first line is a `#` header naming the format version and the sources. Lines are sorted, so a
refresh's diff shows what moved.

Sources, all open data:
- OpenStreetMap (ODbL), from Geofabrik's extracts of Greater London and the counties around it: the
  named public places (embassies, theatres, schools, parks, leisure centers, places of worship,
  museums, hospitals, venues…) and businesses; and one entry per named street per neighborhood,
  named with it ("High Street, Exampleton") from the neighborhoods, quarters and suburbs mapped, so
  same-named streets read apart. People map a place once, where it is, so there are no check-in
  copies to clean up (maintainer, 2026-10-13: OpenStreetMap only, no Overture or Foursquare).
- OS Code-Point Open (Open Government Licence v3.0; contains Royal Mail data): the postcodes in the
  same rectangle as the rest, Greater London and the towns around it.
- Wikidata (CC0), from its public query service: how many Wikipedia editions cover the item a place
  names in its own `wikidata` tag. Wikidata only ranks, never dropping or moving a place.

Beside the index it writes `OPENSTREETMAP_ODBL_NOTICE.txt`, which licenses the place and street
entries, derived from OpenStreetMap, under ODbL 1.0, as that license requires of a derivative database.

Every source is required: a failed or short one stops the build, so the committed index stays
rather than a partial one replacing it. Needs `osmium` (pyosmium) and `pyproj`. Public data only —
nothing about any user.
"""
import argparse
import csv
import hashlib
import http.client
import io
import math
import os
import re
import shutil
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile

FORMAT_VERSION = 3
# Greater London: the area the index covers.
WEST, EAST, SOUTH, NORTH = -0.51, 0.34, 51.28, 51.70
# Geofabrik's extracts covering that rectangle: Greater London, and the counties its fringe reaches
# (Watford, Dartford, Epsom, Thurrock, Iver, Colnbrook). They overlap at their edges; an object in two
# is read once.
GEOFABRIK = "https://download.geofabrik.de/europe/united-kingdom/england"
OSM_EXTRACTS = ("greater-london", "surrey", "kent", "essex", "hertfordshire", "buckinghamshire", "berkshire")
CODEPOINT_URL = "https://api.os.uk/downloads/v1/products/CodePointOpen/downloads?area=GB&format=CSV&redirect"
# The places and streets are a derivative of OpenStreetMap's database, so they ship under ODbL 1.0
# (section 4.2: the license URI with the database; 4.4: the same license for the derivative).
ODBL_FILE = "OPENSTREETMAP_ODBL_NOTICE.txt"
ODBL_NOTICE = """The place (kind P) and street (kind S) entries in place_index.tsv, and the neighborhood names
the streets carry, are a derivative database of OpenStreetMap, © OpenStreetMap contributors, read
from Geofabrik's extracts. A place's rank also counts the Wikipedia editions covering the Wikidata
item it names (Wikidata, CC0). The postcode (kind C) entries are OS Code-Point Open, under the Open
Government Licence v3.0, and aren't part of it.

That derivative database is made available under the Open Database License (ODbL) v1.0:
https://opendatacommons.org/licenses/odbl/1-0/

Any rights in individual contents of the database are licensed under the Database Contents License:
https://opendatacommons.org/licenses/dbcl/1-0/

The file is the database itself, and the method that builds it from the published sources is
scripts/build_place_index.py in https://github.com/mikelward/stopdash.
"""
# A source with fewer rows than this is taken to have failed, not to have shrunk.
MIN_PLACES, MIN_STREETS, MIN_POSTCODES, MIN_AREAS = 100_000, 30_000, 150_000, 500
# Items places name that Wikipedia covers at all; far fewer means a broken answer.
MIN_NOTABLE = 5_000

# What makes a named OpenStreetMap object a destination, by key and value: a public place (rank 7,
# and Wikidata's prominence counts most for these), or any other (a shop, a cafe, an office: rank 2).
# A key mapped to True takes any value but those in NOT_A_PLACE. Public tags are tried first, so a
# museum with a gift shop is the museum and a hotel with a restaurant the hotel.
PUBLIC_PLACES = {
    "tourism": {"museum", "gallery", "attraction", "zoo", "aquarium", "theme_park", "hotel", "hostel", "guest_house",
                "apartment"},
    "amenity": {"theatre", "cinema", "arts_centre", "music_venue", "concert_hall", "events_venue", "exhibition_centre",
                "conference_centre", "library", "university", "college", "school", "kindergarten", "hospital", "clinic",
                "doctors", "community_centre", "social_centre", "townhall", "courthouse", "police", "fire_station",
                "post_office", "place_of_worship", "embassy", "marketplace", "planetarium", "public_bath",
                "research_institute", "grave_yard", "crematorium"},
    "leisure": {"park", "garden", "common", "nature_reserve", "playground", "stadium", "sports_centre", "sports_hall",
                "fitness_centre", "swimming_pool", "ice_rink", "golf_course", "water_park"},
    "landuse": {"cemetery", "recreation_ground", "village_green"},
    "office": {"diplomatic", "government"},
    "shop": {"mall", "department_store"},
    "aeroway": {"aerodrome", "terminal"},
    "place": {"square"},
    "man_made": {"bridge", "tower", "lighthouse", "observatory"},
    "historic": {"monument", "memorial", "castle", "fort", "manor", "palace", "ruins", "archaeological_site", "ship",
                 "building", "church", "city_gate", "tower"},
}
OTHER_PLACES = {
    "amenity": {"restaurant", "cafe", "pub", "bar", "fast_food", "food_court", "ice_cream", "biergarten", "nightclub",
                "casino", "gambling", "bank", "bureau_de_change", "pharmacy", "dentist", "veterinary", "childcare",
                "studio", "coworking_space", "language_school", "music_school", "dancing_school", "driving_school",
                "training", "internet_cafe", "car_rental", "nursing_home", "social_facility", "funeral_hall"},
    "tourism": {"artwork", "viewpoint", "motel", "camp_site", "caravan_site"},
    "leisure": {"bowling_alley", "escape_game", "amusement_arcade", "adult_gaming_centre", "miniature_golf", "sauna",
                "trampoline_park", "horse_riding", "dance", "hackerspace", "marina"},
    "shop": True,
    "craft": True,
    "office": True,
    "healthcare": True,
    "club": True,
}
NOT_A_PLACE = {"no", "vacant", "disused", "abandoned", "closed"}
# Buildings that are a structure rather than somewhere to go, whatever item they name: a station's bus
# stand (a roof), a garage or depot, a disused station. A search for the station must find the station.
NOT_A_DESTINATION_BUILDING = {"roof", "bus_garage", "depot", "disused_station", "garage", "garages", "carport",
                              "parking", "shed", "service", "construction", "train_station", "transportation"}
# A plaque on a wall or a stone in the pavement is a memorial, but nobody goes there.
SMALL_MEMORIALS = {"plaque", "blue_plaque", "stolperstein", "bench", "stone"}
# Stations, stops and piers come from the station index, with the ids their departures need.
TRANSIT = {
    "railway": {"station", "halt", "tram_stop", "subway_entrance"},
    "public_transport": {"station", "stop_position", "platform", "stop_area"},
    "amenity": {"bus_station", "ferry_terminal"},
    "highway": {"bus_stop"},
    "aerialway": {"station"},
    "building": {"train_station", "transportation"},
}
# Every named road and path a rider might be told to walk to is a street, but not a platform, a
# corridor inside a station, or a road not built yet. A highway area is a square, a place if anything.
NOT_A_STREET = {"platform", "corridor", "elevator", "proposed", "construction", "abandoned", "disused", "razed",
                "bus_stop", "raceway", "rest_area", "services"}
# The OpenStreetMap places a street is named with: neighborhoods, quarters and suburbs.
NEIGHBORHOODS = {"neighbourhood", "quarter", "suburb"}
WIKIDATA_ITEM = re.compile(r"\s*(Q[1-9][0-9]*)\s*(?:;.*)?")


def wikidata_id(value):
    """The Wikidata item an OpenStreetMap `wikidata` tag names ("Q42"), or None."""
    m = WIKIDATA_ITEM.fullmatch(value or "")
    return m.group(1) if m else None


# The types the app shows as an icon (PlaceType's codes), from a place's category ([place_of]): a
# "key=value" here, else a whole key ("shop": any shop), else "place".
TYPE_OF = {
    "amenity=restaurant": "restaurant", "amenity=food_court": "restaurant", "amenity=cafe": "cafe",
    "amenity=fast_food": "takeaway", "amenity=pub": "pub", "amenity=bar": "bar", "amenity=biergarten": "bar",
    "amenity=nightclub": "bar", "shop=supermarket": "supermarket", "shop=convenience": "supermarket",
    "shop=mall": "mall", "shop=department_store": "mall", "shop=hairdresser": "hairdresser", "shop=beauty": "hairdresser",
    "amenity=pharmacy": "pharmacy", "amenity=doctors": "doctor", "amenity=clinic": "doctor", "amenity=dentist": "dentist",
    "amenity=hospital": "hospital", "amenity=bank": "bank", "amenity=bureau_de_change": "bank",
    "tourism=hotel": "hotel", "tourism=hostel": "hotel", "tourism=guest_house": "hotel", "tourism=apartment": "hotel",
    "tourism=motel": "hotel", "amenity=school": "school", "amenity=kindergarten": "school",
    "amenity=university": "college", "amenity=college": "college", "amenity=library": "library",
    "leisure=park": "park", "leisure=garden": "park", "leisure=common": "park", "leisure=nature_reserve": "park",
    "landuse=village_green": "park", "landuse=recreation_ground": "park", "leisure=playground": "playground",
    "leisure=fitness_centre": "gym", "leisure=sports_centre": "gym", "leisure=sports_hall": "gym", "leisure=ice_rink": "gym",
    "leisure=swimming_pool": "pool", "leisure=water_park": "pool", "leisure=stadium": "stadium",
    "leisure=golf_course": "golf", "tourism=museum": "museum", "tourism=gallery": "gallery", "amenity=arts_centre": "gallery",
    "amenity=theatre": "theater", "amenity=music_venue": "theater", "amenity=concert_hall": "theater",
    "amenity=cinema": "cinema", "tourism=attraction": "attraction", "tourism=theme_park": "attraction",
    "tourism=zoo": "attraction", "tourism=aquarium": "attraction", "amenity=events_venue": "venue",
    "amenity=exhibition_centre": "venue", "amenity=conference_centre": "venue", "amenity=marketplace": "market",
    "amenity=community_centre": "community", "amenity=social_centre": "community", "amenity=police": "police",
    "amenity=post_office": "post_office", "landuse=cemetery": "cemetery", "amenity=grave_yard": "cemetery",
    "amenity=crematorium": "cemetery", "place=square": "square", "amenity=embassy": "office",
    "amenity=townhall": "office", "amenity=courthouse": "office",
    # Healthcare by what it is: a physiotherapist, a counselor or a lab isn't a doctor, so any other
    # healthcare value is "health" (TYPE_OF_KEY).
    "healthcare=doctor": "doctor", "healthcare=clinic": "doctor", "healthcare=centre": "doctor",
    "healthcare=dentist": "dentist", "healthcare=hospital": "hospital", "healthcare=pharmacy": "pharmacy",
    # A building admitted for its Wikidata item: its type when the building says it, else a landmark.
    "building=school": "school", "building=university": "college", "building=college": "college",
    "building=church": "church", "building=chapel": "church", "building=cathedral": "church",
    "building=mosque": "mosque", "building=synagogue": "synagogue", "building=temple": "temple",
    "building=hospital": "hospital", "building=hotel": "hotel", "building=stadium": "stadium",
    "building=museum": "museum", "building=library": "library", "building=supermarket": "supermarket",
    "building=retail": "shop", "building=office": "office", "building=commercial": "office",
}
TYPE_OF_KEY = {"shop": "shop", "craft": "shop", "office": "office", "healthcare": "health", "historic": "landmark",
               "man_made": "landmark", "building": "landmark", "aeroway": "airport"}
# A place of worship's type, by its religion. Most of London's with none tagged are churches; one
# tagged with a religion not here (multifaith, Bahá'í…) is a plain place, never mislabeled a church.
WORSHIP = {"christian": "church", "muslim": "mosque", "jewish": "synagogue", "hindu": "temple", "sikh": "temple",
           "buddhist": "temple", "jain": "temple"}
TYPES = sorted(set(TYPE_OF.values()) | set(TYPE_OF_KEY.values()) | set(WORSHIP.values()) | {"place"})


def place_type(category, tags):
    """The type a place with [category] ("amenity=pub") and [tags] shows as: one of TYPES."""
    if category == "amenity=place_of_worship":
        religion = (tags.get("religion") or "").strip()
        return WORSHIP.get(religion, "place") if religion else "church"
    return TYPE_OF.get(category) or TYPE_OF_KEY.get(category.split("=")[0], "place")


def place_item(tags):
    """The Wikidata item a place's tags say it is, or None. A branch tagged with its brand's item
    (`wikidata` the same as `brand:wikidata`) is not that item: the brand's fame isn't the branch's."""
    item = wikidata_id(tags.get("wikidata"))
    return None if item and item == wikidata_id(tags.get("brand:wikidata")) else item


def place_of(tags):
    """(category, public) for an OpenStreetMap object's tags (a dict) when it's a destination, else None."""
    if not tags.get("name") or tags.get("disused") == "yes" or tags.get("abandoned") == "yes":
        return None
    if any(tags.get(key) in values for key, values in TRANSIT.items()):
        return None
    for table, public in ((PUBLIC_PLACES, True), (OTHER_PLACES, False)):
        for key, values in table.items():
            value = tags.get(key)
            if value is None or value in NOT_A_PLACE or (values is not True and value not in values):
                continue
            if key == "historic" and value == "memorial" and tags.get("memorial") in SMALL_MEMORIALS:
                continue
            return f"{key}={value}", public
    # A named building is a place only when it names its Wikidata item (a famous tower, a landmark
    # hall): the rest are mostly blocks of flats.
    building = tags.get("building")
    if (building and building not in NOT_A_PLACE and building not in NOT_A_DESTINATION_BUILDING
            and wikidata_id(tags.get("wikidata"))):
        return f"building={building}", True
    return None


def is_street(tags):
    highway = tags.get("highway")
    return bool(tags.get("name")) and highway is not None and highway not in NOT_A_STREET and tags.get("area") != "yes"


# A place's same-named copies are one place within this many meters, when it's a public place: a
# school's site and its building, a museum's point and its outline.
DUPLICATE_METERS = 250
# The same name this close is one place whatever its categories. Businesses merge only this close:
# two branches of a chain a street apart are two places.
SAME_SPOT_METERS = 25
# How many Wikipedia languages cover a place, for its rank: (at least, rank), best first.
NOTABLE_RANKS = ((20, 9), (5, 8))
# Where a listing says it is, as a clause of its own after its name ("Holland Park, London", "Omega
# Hall - City of Westminster"): the postcode district the app shows says that, so the name drops it.
# Run on without a comma or dash it stays, being part of many a name ("Cancer Research UK", "Guild of
# Freemen of the City of London") (maintainer, 2026-10-12).
def area_suffix(separator):
    return re.compile(
        rf"(?:{separator}(?:london|uk|united kingdom|england|great britain"
        r"|(?:the\s+)?(?:royal\s+|london\s+)?borough\s+of\s+[a-z&' ]+)"
        rf"|{separator}(?:city of london|city of westminster))+\s*$",
        re.IGNORECASE,
    )


AREA_SUFFIX = area_suffix(r"(?:\s*,|\s+[\-–—])\s*")
# A dash with no space before it ("Omega Church-London") is a clause too, but only after a name of
# several words: after one it is as likely the name itself ("Hi-London", "VA-UK").
GLUED_AREA_SUFFIX = area_suffix(r"[\-–—]\s*")
# The same said first, as a clause of its own ("City of Westminster, Omega Gallery").
AREA_PREFIX = re.compile(r"^\s*(?:city of london|city of westminster)\s*,\s*", re.IGNORECASE)


def display_name(name):
    """[name] less an [AREA_PREFIX] and [AREA_SUFFIX], unless that would leave nothing that names the place.

    Runs of spaces and tabs are one space first: a listing padded inside its name ("St Paul,  New
    Southgate") would otherwise show the gap on the row.
    """
    name = " ".join(name.split())
    trimmed = AREA_SUFFIX.sub("", AREA_PREFIX.sub("", name)).strip()
    unglued = GLUED_AREA_SUFFIX.sub("", trimmed).strip()
    if len(unglued.split()) > 1:
        trimmed = unglued
    # An area the rest leads into is part of the name: joined on by "&" or "and" ("Omega Care
    # Westminster & City of London"), or the object of "of", "in" and the like ("Guild of Freemen of the
    # City of London").
    if re.search(r"(?:&|\b(?:and|of|the|in|at|for|to|on|by))$", trimmed, re.IGNORECASE):
        return name
    return trimmed if match_key(trimmed) not in ("", "london") else name


# Words a listing tacks on that don't name the place ("British Museum London UK").
TRAILING_NOISE = re.compile(r"(?:\s+(?:in\s+)?(?:city of london|city of westminster|london|uk|united kingdom|england))+$")
APOSTROPHES = re.compile("[‘’ʼʹ`´]")


def match_key(name):
    """A name as compared for duplicates: accents folded, case and punctuation dropped, an apostrophe
    joining its word (so "St Paul's" and "St Pauls" are one name), a leading "the" and trailing
    "London", "UK" and the like removed."""
    # Every apostrophe form is an apostrophe (curly, reversed, the modifier letter, a backtick or acute
    # accent typed for one): made straight before the ASCII fold, which would drop it.
    name = APOSTROPHES.sub("'", name)
    folded = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower().replace("'", "")
    words = re.sub(r"[^a-z0-9 ]+", " ", folded).split()
    key = " ".join(words)
    if key.startswith("the "):
        key = key[4:]
    return TRAILING_NOISE.sub("", key)


def meters_apart(a, b):
    """Rough distance in meters between two (lat, lon) points, good to a few percent in London."""
    dy = (a[0] - b[0]) * 111_320
    dx = (a[1] - b[1]) * 69_400
    return (dx * dx + dy * dy) ** 0.5


def cell_of(lat, lon, meters):
    """(lat, lon)'s cell in a grid of [meters]-wide cells, measured as [meters_apart] measures. Two
    points no more than [meters] apart are always in the same cell or adjacent ones, so a search of a
    cell and its eight neighbors (see [near_cells]) finds everything within [meters]."""
    return (math.floor(lat * 111_320 / meters), math.floor(lon * 69_400 / meters))


def near_cells(cell):
    """[cell] and its eight neighbors."""
    return [(cell[0] + dy, cell[1] + dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)]


def polygon_centroid(outers, inners=()):
    """(lat, lon, square meters) of the area whose outer rings are [outers] and holes [inners], each a
    list of (lat, lon), measured as [meters_apart] measures; the mean of the outer rings' points when
    they enclose no area, and None when there are none."""
    points = [p for ring in outers for p in ring]
    if not points:
        return None
    lat0, lon0 = points[0]
    total = sy = sx = 0.0
    for rings, sign in ((outers, 1), (inners, -1)):
        for ring in rings:
            # Shoelace, in meters from the first point: small numbers keep a small building's area exact.
            xy = [((lon - lon0) * 69_400, (lat - lat0) * 111_320) for lat, lon in ring]
            a = cx = cy = 0.0
            for (x0, y0), (x1, y1) in zip(xy, xy[1:] + xy[:1]):
                cross = x0 * y1 - x1 * y0
                a += cross
                cx += (x0 + x1) * cross
                cy += (y0 + y1) * cross
            if a == 0:
                continue
            area = abs(a) / 2
            total += sign * area
            sx += sign * area * cx / (3 * a)
            sy += sign * area * cy / (3 * a)
    if total <= 0:
        return (sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points), 0.0)
    return (lat0 + sy / total / 111_320, lon0 + sx / total / 69_400, total)


PUBLIC_RANK, STREET_RANK, POSTCODE_RANK, OTHER_RANK = 7, 4, 4, 2


def select_places(rows, editions=None):
    """The places to bundle from OpenStreetMap rows (name, category, public, lat, lon, wikidata item,
    square meters), as (name, category, lat, lon, rank), one object of a place kept.

    People map a place once, but a building and its grounds, or a point inside an outline, sometimes
    both carry it: the same name within SAME_SPOT_METERS is one place whatever its categories, and a
    public place's same name and category within DUPLICATE_METERS one place too. So is the same name
    within DUPLICATE_METERS of a place naming its Wikidata item, whatever it's tagged: a famous
    gallery's shop named for the gallery, a theater's second outline tagged as an attraction. The one
    kept names a Wikidata item, then is the larger. [editions] ({item: Wikipedia editions}) ranks the famous above
    the rest. A business with branches is a chain: mappers sometimes tag a branch with the company's
    item, so an item on a business whose name or item recurs more than DUPLICATE_METERS away is a
    brand's, not one famous place's, and ranks none."""
    editions = editions or {}
    kept = [(display_name(name.strip()), category, public, lat, lon, item, size)
            for name, category, public, lat, lon, item, size in rows if name]
    first, chains = {}, set()
    for name, category, public, lat, lon, item, size in kept:
        if public:
            continue
        for mark in (match_key(name), item):
            if mark:
                at = first.setdefault(mark, (lat, lon))
                if meters_apart(at, (lat, lon)) > DUPLICATE_METERS:
                    chains.add(mark)
    kept = [(n, c, p, lat, lon, None if not p and (i in chains or match_key(n) in chains) else i, s)
            for n, c, p, lat, lon, i, s in kept]
    # The best object first, so a duplicate always meets the one it merges into already placed.
    kept.sort(key=lambda r: (r[5] is None, -r[6], len(r[0]), r[0]))
    cells = {}
    out = []
    for name, category, public, lat, lon, item, _ in kept:
        key = match_key(name)
        if not key:
            continue
        cell = cell_of(lat, lon, DUPLICATE_METERS)
        duplicate = False
        for near in near_cells(cell):
            for other_key, other_category, other_at, other_famous in cells.get(near, ()):
                if key != other_key:
                    continue
                apart = meters_apart(other_at, (lat, lon))
                same_place = other_famous or (public and category == other_category)
                if apart <= SAME_SPOT_METERS or (same_place and apart <= DUPLICATE_METERS):
                    duplicate = True
                    break
            if duplicate:
                break
        if duplicate:
            continue
        cells.setdefault(cell, []).append((key, category, (lat, lon), item is not None))
        rank = PUBLIC_RANK if public else OTHER_RANK
        links = editions.get(item, 0) if item else 0
        for at_least, notable in NOTABLE_RANKS:
            if links >= at_least:
                rank = max(rank, notable)
                break
        out.append((name, category, lat, lon, rank))
    return out


# A street is named with the nearest neighborhood within this distance; past it, it stands alone.
AREA_RADIUS_METERS = 2500
# Places too broad to tell two streets apart.
BROAD_AREA = re.compile(r"\b(?:borough|city of|london)\b", re.IGNORECASE)


def nearest_area(areas, lat, lon):
    """The name of the nearest of [areas] (a grid: cell -> [(name, lat, lon)]) within
    AREA_RADIUS_METERS of (lat, lon), or None."""
    best, best_meters = None, AREA_RADIUS_METERS
    for near in near_cells(cell_of(lat, lon, AREA_RADIUS_METERS)):
        for name, a_lat, a_lon in areas.get(near, ()):
            meters = meters_apart((lat, lon), (a_lat, a_lon))
            if meters <= best_meters:
                best, best_meters = name, meters
    return best


def area_grid(rows):
    """Neighborhood rows (name, OpenStreetMap `place` value, lat, lon) as a grid for [nearest_area]:
    neighborhoods, quarters and suburbs only, less any as broad as a borough."""
    grid = {}
    for name, kind, lat, lon in rows:
        if not name or kind not in NEIGHBORHOODS or BROAD_AREA.search(name):
            continue
        grid.setdefault(cell_of(lat, lon, AREA_RADIUS_METERS), []).append((name.strip(), lat, lon))
    return grid


def street_cell(name, lat, lon):
    """The cell a street's point is kept in: one point per street name per cell of about a kilometer."""
    return (name.strip(), round(lat * 90), round(lon * 55))


def select_streets(rows, areas):
    """One (name, lat, lon) per named street per neighborhood, from points along the streets (name,
    lat, lon), named with its nearest neighborhood ("High Street, Exampleton") so two streets of one
    name read apart; a street's points in one neighborhood collapse to one entry, at their middle."""
    cells = {}
    for name, lat, lon in rows:
        cells.setdefault(street_cell(name, lat, lon), (lat, lon))
    grouped = {}
    for (name, _, _), (lat, lon) in cells.items():
        area = nearest_area(areas, lat, lon)
        label = f"{name}, {area}" if area and area.lower() not in name.lower() else name
        grouped.setdefault(label, []).append((lat, lon))
    out = []
    for label, points in grouped.items():
        lat = sum(p[0] for p in points) / len(points)
        lon = sum(p[1] for p in points) / len(points)
        # The middle of the cells, then the cell nearest it: a curving road's middle can be off it.
        lat, lon = min(points, key=lambda p: meters_apart(p, (lat, lon)))
        out.append((label, lat, lon))
    return out


def postcode_text(raw):
    """Code-Point's fixed-width postcode ("E1  6AN", "SW1A1AA") as written ("E1 6AN", "SW1A 1AA")."""
    compact = raw.replace(" ", "").upper()
    return f"{compact[:-3]} {compact[-3:]}"


# Code-Point Open's postcodes read, in British National Grid meters: a margin around the WGS84
# rectangle the rest of the index covers, so converting only these is enough.
BNG_WEST, BNG_EAST, BNG_SOUTH, BNG_NORTH = 490_000, 575_000, 145_000, 210_000


def select_postcodes(rows, to_wgs84):
    """The postcodes in the index's rectangle (WEST to EAST, SOUTH to NORTH) from Code-Point Open
    rows, as (postcode, lat, lon): Greater London and the towns around it TfL reaches, as the places
    and streets are (maintainer, 2026-10-11). [to_wgs84] turns an easting and northing into (lat, lon)."""
    out = []
    for r in rows:
        if len(r) < 3 or not r[2] or not r[3]:
            continue
        easting, northing = float(r[2]), float(r[3])
        if not (BNG_WEST <= easting <= BNG_EAST and BNG_SOUTH <= northing <= BNG_NORTH):
            continue
        lat, lon = to_wgs84(easting, northing)
        if WEST <= lon <= EAST and SOUTH <= lat <= NORTH:
            out.append((postcode_text(r[0]), lat, lon))
    return out


# "St." for Saint (or Street) reads "St", as the app cleans TfL's stop names, so "St. Paul's Cathedral"
# lists beside the "St Paul's" stops: the full stop dropped, and a space kept before a word it ran into.
# Whatever its case ("ST. John", "st.georges"), keeping the name's own capitals.
SAINT_DOT = re.compile(r"\b(St)\.(?:(?=\w)|\s*)", re.IGNORECASE)


def without_saint_dot(name):
    """[name] with each "St." read "St" ("St.Mary's" → "St Mary's", "Wardour St." → "Wardour St")."""
    def replace(m):
        return m.group(1) + (" " if m.end() < len(name) and name[m.end()].isalnum() else "")
    return SAINT_DOT.sub(replace, name).strip()


# A place's district is the one its row shows, so search finds what the rider reads: worked out exactly
# as PlaceIndex.districtNear does, on the same rounded coordinates, cells, order and tie-break, and
# PlaceIndexTest checks every bundled entry against it.
COORDINATE_SCALE = 100_000
LAT_CELL, LON_CELL = 1000, 1700
MAX_DISTRICT_DISTANCE = 1000


def scaled(degrees):
    """[degrees] as the app holds it: written to five places, read back, then Math.round(x * 1e5)."""
    return math.floor(float(f"{degrees:.5f}") * COORDINATE_SCALE + 0.5)


def district_grid(postcodes):
    """[postcodes] (postcode, lat, lon), in the order the app reads them (the file's sorted order), by
    the app's grid cell, for [district_near]."""
    grid = {}
    for postcode, lat, lon in sorted(postcodes, key=lambda p: f"C\t{p[0]}\t\t{p[1]:.5f}\t{p[2]:.5f}"):
        y, x = scaled(lat), scaled(lon)
        grid.setdefault((y // LAT_CELL, x // LON_CELL), []).append((postcode.split(" ")[0], y, x))
    return grid


def truncating_division(a, b):
    """[a] / [b] for a positive [b], rounded toward zero as Kotlin's Long division does (Python's // floors)."""
    return a // b if a >= 0 else -(-a // b)


def district_near(grid, lat, lon):
    """The district PlaceIndex.districtNear gives (lat, lon): the nearest postcode's, the first in its
    order on a tie, within about 1.1 km; "" with none that near."""
    y, x = scaled(lat), scaled(lon)
    best, best_distance = "", None
    for d_lat in (-1, 0, 1):
        for d_lon in (-1, 0, 1):
            for district, p_y, p_x in grid.get((y // LAT_CELL + d_lat, x // LON_CELL + d_lon), ()):
                north = p_y - y
                east = truncating_division((p_x - x) * 62, 100)
                distance = north * north + east * east
                if best_distance is None or distance < best_distance:
                    best, best_distance = district, distance
    if best_distance is None or best_distance > MAX_DISTRICT_DISTANCE * MAX_DISTRICT_DISTANCE:
        return ""
    return best


def render(places, streets, postcodes, sources):
    """The index file's text: the header, then every entry, sorted."""
    def clean(text):
        # Tabs and newlines would break the line; any run of whitespace reads as one space on a row.
        return without_saint_dot(" ".join(text.split()))
    # A name with no letter or digit the app can match ("ピカデリー・サーカス") can't be found by typing.
    places = [p for p in places if match_key(p[0])]
    streets = [s for s in streets if match_key(s[0])]
    grid = district_grid(postcodes)
    lines = [f"P\t{clean(n)}\t{c}\t{lat:.5f}\t{lon:.5f}\t{r}\t{district_near(grid, lat, lon)}" for n, c, lat, lon, r in places]
    lines += [f"S\t{clean(n)}\t\t{lat:.5f}\t{lon:.5f}\t{STREET_RANK}\t{district_near(grid, lat, lon)}" for n, lat, lon in streets]
    lines += [f"C\t{p}\t\t{lat:.5f}\t{lon:.5f}\t{POSTCODE_RANK}\t" for p, lat, lon in postcodes]
    lines.sort()
    header = f"# stopdash place index v{FORMAT_VERSION}\t{sources}"
    return header + "\n" + "\n".join(lines) + "\n"


def require(label, rows, minimum):
    if len(rows) < minimum:
        sys.exit(f"{label}: only {len(rows)} rows (expected at least {minimum}); not writing a partial index")
    return rows


# --- Network and files: run by hand or in the refresh workflow, not in tests. ---

def geofabrik_md5(text):
    """The checksum in Geofabrik's `.md5` file ("<hex>  <name>")."""
    m = re.match(r"([0-9a-f]{32})\s", text)
    if not m:
        raise SystemExit("Geofabrik's checksum file doesn't look like one; not trusting the download")
    return m.group(1)


def fetch_osm(directory):
    """Geofabrik's extracts, downloaded into [directory] and checked against Geofabrik's MD5 sums."""
    paths = []
    for name in OSM_EXTRACTS:
        url = f"{GEOFABRIK}/{name}-latest.osm.pbf"
        expected = geofabrik_md5(urllib.request.urlopen(url + ".md5", timeout=60).read().decode("ascii", "replace"))
        path = os.path.join(directory, f"{name}.osm.pbf")
        digest = hashlib.md5()
        with urllib.request.urlopen(url, timeout=300) as response, open(path, "wb") as f:
            while chunk := response.read(1 << 20):
                digest.update(chunk)
                f.write(chunk)
        if digest.hexdigest() != expected:
            raise SystemExit(f"{name}: the download doesn't match Geofabrik's checksum; not reading it")
        paths.append(path)
    return paths


def inside(lat, lon):
    return WEST <= lon <= EAST and SOUTH <= lat <= NORTH


def ring_points(ring):
    return [(n.location.lat, n.location.lon) for n in ring if n.location.valid()]


def read_osm(paths):
    """(places, street points, neighborhoods) from the OpenStreetMap files at [paths], each object
    once however many of the overlapping extracts hold it: places as [select_places] takes them,
    street points as (name, lat, lon), one per street per [street_cell], and neighborhoods as (name,
    `place` value, lat, lon). Only what lies inside the rectangle."""
    import osmium
    places, areas, streets = {}, {}, {}

    def add(key, tags, point):
        lat, lon, size = point
        if not inside(lat, lon):
            return
        name = tags.get("name")
        kind = place_of(tags)
        if kind and (key not in places or places[key][6] < size):
            places[key] = (name, place_type(kind[0], tags), kind[1], lat, lon, place_item(tags), size)
        if tags.get("place") in NEIGHBORHOODS:
            areas[key] = (name, tags["place"], lat, lon)

    for path in paths:
        processor = (osmium.FileProcessor(path).with_areas(osmium.filter.KeyFilter("name"))
                     .with_filter(osmium.filter.KeyFilter("name")))
        for obj in processor:
            tags = dict(obj.tags)
            if obj.is_node():
                if obj.location.valid():
                    add(("n", obj.id), tags, (obj.location.lat, obj.location.lon, 0.0))
            elif obj.is_area():
                outers = list(obj.outer_rings())
                point = polygon_centroid([ring_points(o) for o in outers],
                                         [ring_points(i) for o in outers for i in obj.inner_rings(o)])
                if point:
                    add(("w" if obj.from_way() else "r", obj.orig_id()), tags, point)
            elif obj.is_way():
                points = ring_points(obj.nodes)
                if is_street(tags):
                    for lat, lon in points:
                        if inside(lat, lon):
                            streets.setdefault(street_cell(tags["name"], lat, lon), (tags["name"].strip(), lat, lon))
                # A closed way's place, should its outline not make an area; the area replaces it.
                elif obj.is_closed() and points and ("w", obj.id) not in places:
                    add(("w", obj.id), tags, polygon_centroid([points]))
    return list(places.values()), list(streets.values()), list(areas.values())


def osm_date(paths):
    """The oldest of the extracts' dates (their replication timestamps), for the header."""
    import osmium
    dates = []
    for path in paths:
        stamp = osmium.io.Reader(path, osmium.osm.NOTHING).header().get("osmosis_replication_timestamp")
        if stamp:
            dates.append(stamp[:10])
    if len(dates) != len(paths):
        raise SystemExit("an OpenStreetMap extract has no date in its header; not dating the index")
    return min(dates)


WIKIDATA_URL = "https://query.wikidata.org/sparql"
# The Wikipedia editions alone of the given items: sitelinks also count Commons, Wikivoyage and the
# other Wikimedia projects, which say nothing about how widely a place is written up. Asked in batches:
# the query service times out on many at once, and so does its optimizer's own plan for a batch: the
# hint keeps the VALUES first, which answers a batch in under a second.
WIKIPEDIA_QUERY = """SELECT ?item (COUNT(DISTINCT ?article) AS ?links) WHERE {
  hint:Query hint:optimizer "None" .
  VALUES ?item { %(items)s }
  ?article schema:about ?item ; schema:isPartOf ?wiki .
  ?wiki wikibase:wikiGroup "wikipedia" .
} GROUP BY ?item"""
WIKIPEDIA_BATCH = 100
ENTITY = re.compile(r"^http://www\.wikidata\.org/entity/(Q[0-9]+)$")


def parse_wikipedia_counts(text):
    """The Wikipedia-count answer as {item id ("Q42"): editions}; a row it can't read is left out."""
    counts = {}
    for row in csv.DictReader(io.StringIO(text)):
        entity = ENTITY.match(row.get("item") or "")
        try:
            links = int(row.get("links") or "")
        except ValueError:
            continue
        if entity:
            counts[entity.group(1)] = links
    return counts


def wikipedia_editions(items, count):
    """{item: Wikipedia editions} for [items], asked of [count] (a list of item ids to {id: editions})
    WIKIPEDIA_BATCH at a time; an item it doesn't answer for has none and is left out."""
    asked = sorted(set(items))
    editions = {}
    for start in range(0, len(asked), WIKIPEDIA_BATCH):
        editions.update(count(asked[start:start + WIKIPEDIA_BATCH]))
    return editions


# The query service limits a client's rate, now and then cuts a long answer short, and its gateway
# sometimes fails a batch it answers on a second try; told to slow down (429), cut off, or failed by
# the gateway (502, 503, 504), the builder waits (Retry-After, else a growing pause) and tries again,
# up to this many times before failing the build.
QUERY_ATTEMPTS = 5
RETRIED_STATUSES = {429, 502, 503, 504}


def query_service(query, urlopen=urllib.request.urlopen, sleep=time.sleep):
    request = urllib.request.Request(
        WIKIDATA_URL, data=urllib.parse.urlencode({"query": query}).encode(),
        headers={"Accept": "text/csv", "User-Agent": "stopdash-place-index-builder (https://github.com/mikelward/stopdash)"},
    )
    for attempt in range(QUERY_ATTEMPTS):
        try:
            return urlopen(request, timeout=300).read().decode("utf-8")
        except http.client.IncompleteRead as e:
            if attempt == QUERY_ATTEMPTS - 1:
                raise
            pause = 10 * 2 ** attempt
            print(f"Wikidata's answer was cut off after {len(e.partial)} bytes; waiting {pause} s", file=sys.stderr)
            sleep(pause)
        except urllib.error.HTTPError as e:
            if e.code not in RETRIED_STATUSES or attempt == QUERY_ATTEMPTS - 1:
                raise
            wait = e.headers.get("Retry-After") if e.headers else None
            pause = int(wait) if wait and wait.isdigit() else 10 * 2 ** attempt
            print(f"Wikidata answered {e.code}; waiting {pause} s", file=sys.stderr)
            sleep(pause)


def fetch_editions(items):
    count = lambda ids: parse_wikipedia_counts(query_service(WIKIPEDIA_QUERY % {"items": " ".join("wd:" + i for i in ids)}))
    return wikipedia_editions(items, count)


def fetch_postcode_rows(zip_path=None):
    data = open(zip_path, "rb").read() if zip_path else urllib.request.urlopen(CODEPOINT_URL, timeout=300).read()
    z = zipfile.ZipFile(io.BytesIO(data))
    for name in z.namelist():
        if name.startswith("Data/CSV/") and name.endswith(".csv"):
            yield from csv.reader(io.TextIOWrapper(z.open(name), "utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--codepoint-zip", help="read Code-Point Open from this zip instead of downloading it")
    parser.add_argument("--osm", nargs="+", help="read OpenStreetMap from these .osm.pbf files instead of downloading Geofabrik's")
    args = parser.parse_args()
    from pyproj import Transformer
    bng = Transformer.from_crs("EPSG:27700", "EPSG:4326", always_xy=True)

    def to_wgs84(easting, northing):
        lon, lat = bng.transform(easting, northing)
        return lat, lon

    download = None if args.osm else tempfile.mkdtemp(prefix="osm-")
    try:
        paths = args.osm or fetch_osm(download)
        rows, street_points, neighborhoods = read_osm(paths)
        dated = osm_date(paths)
    finally:
        if download:
            shutil.rmtree(download)
    editions = fetch_editions([r[5] for r in rows if r[5]])
    require("Wikidata items with an article", [i for i, n in editions.items() if n > 0], MIN_NOTABLE)
    places = require("places", select_places(rows, editions), MIN_PLACES)
    areas = area_grid(require("neighborhoods", neighborhoods, MIN_AREAS))
    streets = require("streets", select_streets(street_points, areas), MIN_STREETS)
    postcodes = require("postcodes", select_postcodes(fetch_postcode_rows(args.codepoint_zip), to_wgs84), MIN_POSTCODES)
    sources = f"OpenStreetMap {dated} (ODbL); OS Code-Point Open (OGL v3.0); Wikidata (CC0)"
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(render(places, streets, postcodes, sources))
    with open(os.path.join(out_dir, ODBL_FILE), "w", encoding="utf-8") as f:
        f.write(ODBL_NOTICE)
    print(f"{len(places)} places, {len(streets)} streets, {len(postcodes)} postcodes from OpenStreetMap {dated}")


if __name__ == "__main__":
    main()
