#!/usr/bin/env python3
"""Build stopdash's bundled London place index (dev-docs/PLACES.md, option 4).

Writes the tab-separated file the app reads as `assets/places/place_index.tsv`: the places, streets
and postcodes a *To…* search can route to, found on the device as the rider types, with nothing sent
anywhere. Each line is one destination:

    kind  name  category  latitude  longitude  rank

- kind: `P` a place (a venue, a business, a park…), `S` a street, `C` a postcode.
- category: a place's Overture basic category (`museum`, `park`, …); empty for a street or postcode.
- latitude, longitude: degrees to five places (about a meter).
- rank: 0–9, how prominent the entry is, to order equal matches: a place Wikipedia covers in many
  languages 9 (20 or more) or 8 (5 or more), any other public place 7, a street or postcode 4, any
  other place 2. Overture's confidence isn't used: it says how sure Overture is that a place exists,
  isn't comparable across its sources, and so only filters.

The first line is a `#` header naming the format version and the sources. Lines are sorted, so a
refresh's diff shows what moved.

Sources, all open data:
- Overture Maps places (Meta, Microsoft, Foursquare, AllThePlaces; CDLA Permissive 2.0 and
  Apache 2.0), from its public Amazon S3 bucket. Kept: public places (embassies, theatres, schools,
  parks, leisure centers, places of worship, museums, hospitals, venues…) at confidence 0.7 or
  more, and any other place at 0.9 or more. Near-duplicates are merged.
- Overture Maps transportation segments, the OpenStreetMap ones only (ODbL; Overture also carries
  TomTom roads, which would need their own credit): one entry per named road per neighborhood,
  named with it ("High Street, Exampleton") from Overture's divisions (neighborhoods and
  macrohoods, also OpenStreetMap's only), so same-named streets read apart.
- OS Code-Point Open (Open Government Licence v3.0; contains Royal Mail data): the postcodes in
  the same rectangle as the rest, Greater London and the towns around it.
- Wikidata (CC0), from its public query service: the items in the same rectangle with an English
  name, a position and a Wikipedia article in at least one language, and how many languages have
  one. A public place of the same name within 300 m takes its prominence from them; Wikidata only
  ranks, never dropping or moving a listing. Only names and positions of public things are read.

Beside the index it writes Foursquare's `NOTICE.txt` (with our changes appended) and the Apache
License 2.0 text, as `FOURSQUARE_NOTICE.txt` and `FOURSQUARE_LICENSE.txt`: Overture's Foursquare
places are Apache 2.0, which requires both to ship with the data. It also writes
`OPENSTREETMAP_ODBL_NOTICE.txt`, which licenses the street entries, derived from OpenStreetMap, under
ODbL 1.0, as that license requires of a derivative database.

Every source is required: a failed or short one stops the build, so the committed index stays
rather than a partial one replacing it. Needs `pyarrow` and `pyproj`. Public data only — nothing
about any user.
"""
import argparse
import csv
import http.client
import io
import math
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile

FORMAT_VERSION = 1
# Greater London: the area the index covers.
WEST, EAST, SOUTH, NORTH = -0.51, 0.34, 51.28, 51.70
BUCKET = "overturemaps-us-west-2"
CODEPOINT_URL = "https://api.os.uk/downloads/v1/products/CodePointOpen/downloads?area=GB&format=CSV&redirect"
# Overture's Foursquare places are Apache 2.0, which requires shipping the license and Foursquare's
# NOTICE beside the data. Both sit at the top of Foursquare's own public release bucket.
FOURSQUARE_NOTICE_URL = "https://fsq-os-places-us-east-1.s3.amazonaws.com/NOTICE.txt"
FOURSQUARE_LICENSE_URL = "https://fsq-os-places-us-east-1.s3.amazonaws.com/LICENSE.txt"
NOTICE_FILE = "FOURSQUARE_NOTICE.txt"
LICENSE_FILE = "FOURSQUARE_LICENSE.txt"
# The streets and neighborhoods are a derivative of OpenStreetMap's database, so they ship under
# ODbL 1.0 (section 4.2: the license URI with the database; 4.4: the same license for the derivative).
ODBL_FILE = "OPENSTREETMAP_ODBL_NOTICE.txt"
ODBL_NOTICE = """The street (kind S) entries in place_index.tsv, and the neighborhood names they carry, are a
derivative database of OpenStreetMap, © OpenStreetMap contributors, taken through the Overture Maps
Foundation's transportation and divisions releases.

That derivative database is made available under the Open Database License (ODbL) v1.0:
https://opendatacommons.org/licenses/odbl/1-0/

Any rights in individual contents of the database are licensed under the Database Contents License:
https://opendatacommons.org/licenses/dbcl/1-0/

The file is the database itself, and the method that builds it from the published sources is
scripts/build_place_index.py in https://github.com/mikelward/stopdash.
"""
FOURSQUARE_CHANGES = (
    "\n\nChanges by StopDash: this app ships a subset of the Data, taken through the Overture Maps "
    "Foundation's places release: places in and around London, with near-duplicates merged and names, "
    "categories and positions reduced to a search list. The index file beside this notice holds it.\n"
)
# A source with fewer rows than this is taken to have failed, not to have shrunk.
MIN_PLACES, MIN_STREETS, MIN_POSTCODES, MIN_AREAS = 100_000, 30_000, 150_000, 500
# London has some 23,000 Wikidata items with an article (2026-10); far fewer means a broken answer.
MIN_NOTABLE = 10_000

PUBLIC_CATEGORIES = {
    "embassy", "theatre_venue", "performing_arts_venue", "music_venue", "movie_theater", "museum",
    "art_gallery", "park", "playground", "stadium_arena", "library", "college_university",
    "elementary_school", "high_school", "preschool", "place_of_learning", "education", "specialty_school",
    "hospital", "community_center", "government_office", "police_station", "gym",
    "sport_or_fitness_facility", "swimming_pool", "golf_course", "historic_site", "monument", "hotel",
    "shopping_mall", "department_store", "event_venue", "arts_and_entertainment", "sports_and_recreation",
    "zoo", "aquarium", "landmark_and_historical_building", "fire_station", "post_office", "town_hall",
    "courthouse", "cemetery", "sport_court", "fitness_studio", "airport", "campus_building",
    "research_institute", "primary_care_or_general_clinic", "lodging",
}
# Never a destination worth listing: stations come from the station index (with the ids their
# departures need), and these are rarely where a trip ends.
DROPPED_CATEGORIES = {
    "train_station", "public_transit_facility_or_service", "bus_station", "travel_and_transportation",
    "parking", "ev_charging_station", "atm", "gas_station",
}
PUBLIC_MIN_CONFIDENCE = 0.7
OTHER_MIN_CONFIDENCE = 0.9
# Two listings of one place are within this many meters of each other.
DUPLICATE_METERS = 250
# Listings this close with the same name are one place whatever their categories (Overture files
# some under two).
SAME_SPOT_METERS = 25
# Places of which London has one: a far-off copy of the name is a mis-placed listing (people check
# in to "British Museum" from home), not a second branch.
UNIQUE_CATEGORIES = {
    "museum", "art_gallery", "theatre_venue", "performing_arts_venue", "music_venue", "stadium_arena",
    "embassy", "zoo", "aquarium", "landmark_and_historical_building", "historic_site", "monument",
}
# A landmark's own listing is at least this confident before its stray copies are dropped.
LANDMARK_MIN_CONFIDENCE = 0.99
LANDMARK_RADIUS_METERS = 1000
# A Wikidata item lends its prominence to a same-named place within this many meters.
NOTABLE_METERS = 300
# How many Wikipedia languages cover a place, for its rank: (at least, rank), best first.
NOTABLE_RANKS = ((20, 9), (5, 8))
# The datasets Overture lists for itself alongside a listing's real source.
OVERTURE_OWN = {"Overture", "Overture-signals"}
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


AREA_SUFFIX = area_suffix(r"(?:\s*,|\s+[\-\u2013\u2014])\s*")
# A dash with no space before it ("Omega Church-London") is a clause too, but only after a name of
# several words: after one it is as likely the name itself ("Hi-London", "VA-UK").
GLUED_AREA_SUFFIX = area_suffix(r"[\-\u2013\u2014]\s*")
# The same said first, as a clause of its own ("City of Westminster, Omega Gallery").
AREA_PREFIX = re.compile(r"^\s*(?:city of london|city of westminster)\s*,\s*", re.IGNORECASE)


def display_name(name):
    """[name] less an [AREA_PREFIX] and [AREA_SUFFIX], unless that would leave nothing that names the place."""
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


def is_public(category):
    return category in PUBLIC_CATEGORIES or category.endswith("_place_of_worship") or category.endswith("_school")


APOSTROPHES = re.compile("[\u2018\u2019\u02bc\u02b9\u0060\u00b4]")


def match_key(name, join_apostrophes=True):
    """A name as compared for duplicates: accents folded, case and punctuation dropped, a leading
    "the" and trailing "London", "UK" and the like removed. [join_apostrophes]: an apostrophe joins
    its word, so "St Paul's" and "St Pauls" are one name; without it, it splits one, as for the
    [drop_misplaced_landmarks], where joining would make "St Pauls" start "St Paul's …" and a store
    "For Art's Sake" the same place as a gallery "For Arts Sake"."""
    # Every apostrophe form is an apostrophe (curly, reversed, the modifier letter, a backtick or acute
    # accent typed for one): made straight before the ASCII fold, which would drop it.
    name = APOSTROPHES.sub("'", name)
    folded = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    if join_apostrophes:
        folded = folded.replace("'", "")
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


PUBLIC_RANK, STREET_RANK, POSTCODE_RANK, OTHER_RANK = 7, 4, 4, 2


def rank_of(category):
    return PUBLIC_RANK if is_public(category) else OTHER_RANK


def drop_misplaced_landmarks(places):
    """[places] (name, category, confidence, lat, lon, meta_only) less a landmark's stray copies.

    A landmark is the most confident listing of its name, when it's in UNIQUE_CATEGORIES at
    LANDMARK_MIN_CONFIDENCE. Its strays are the pages people make for a famous place, in whatever
    category, wherever they happen to be: "Royal Albert Hall" has some forty listings across London,
    as a music venue, a bar, a cinema, all from Meta pages alone. So another listing named the same,
    in any category, goes when Meta is its only source and it's less confident than the landmark;
    one named starting with it ("Tate Modern Museum") goes only past LANDMARK_RADIUS_METERS, since a
    big site's own listings (a gallery, a room, an entrance) sit within that. A copy any other source
    vouches for is kept, as a chain's real branches are."""
    # Each landmark by its name with apostrophes splitting words, for both tests: "St Pauls" doesn't
    # start "St Paul's Church", and "For Arts Sake" isn't "For Art's Sake" -- a store isn't the gallery
    # across London that happens to spell it without one.
    landmarks = {}
    for p in places:
        if p[1] in UNIQUE_CATEGORIES and p[2] >= LANDMARK_MIN_CONFIDENCE:
            key = match_key(p[0], join_apostrophes=False)
            if key not in landmarks or p[2] > landmarks[key][2]:
                landmarks[key] = p
    dropped = set()
    for p in places:
        if not p[5]:
            continue
        key = match_key(p[0], join_apostrophes=False)
        same = landmarks.get(key)
        if same is not None and same is not p and p[2] < same[2]:
            dropped.add(id(p))
            continue
        words = key.split()
        for n in range(1, len(words)):
            landmark = landmarks.get(" ".join(words[:n]))
            if landmark is None or landmark is p or p[2] >= landmark[2]:
                continue
            if meters_apart((p[3], p[4]), (landmark[3], landmark[4])) > LANDMARK_RADIUS_METERS:
                dropped.add(id(p))
                break
    return [p for p in places if id(p) not in dropped]


class Notable:
    """Wikidata's items (name, lat, lon, languages), by match key, for [select_places]."""

    def __init__(self, items=()):
        self.by_key = {}
        for name, lat, lon, links in items:
            key = match_key(name)
            if key:
                self.by_key.setdefault(key, []).append((lat, lon, links))

    def rank_near(self, name, lat, lon):
        """The rank Wikidata's coverage gives a place named [name] at [lat], [lon], or None: the most
        languages among items of its very name within NOTABLE_METERS. Only its very name: a school or
        a cafe named for a cathedral isn't the cathedral."""
        links = 0
        for item_lat, item_lon, item_links in self.by_key.get(match_key(name), ()):
            if item_links > links and meters_apart((lat, lon), (item_lat, item_lon)) <= NOTABLE_METERS:
                links = item_links
        for at_least, rank in NOTABLE_RANKS:
            if links >= at_least:
                return rank
        return None


def select_places(rows, notable=None):
    """The places to bundle from Overture rows (name, category, confidence, status, lat, lon[,
    meta_only]: whether Meta is the listing's only source, taken as true when absent), as
    (name, category, lat, lon, rank), near-duplicates merged.

    Two listings are one place when they share a category, sit within DUPLICATE_METERS, and one's
    match key equals the other's or starts it ("Tate Modern" and "Tate Modern Museum"), or when they
    have the same name within SAME_SPOT_METERS whatever their categories; the more confident listing,
    then the shorter name, is kept. [notable] (Wikidata's items) ranks the famous above the rest; it
    never drops or moves a listing, since a name alone can't say a Wikidata item and a listing far from
    it are one place (maintainer, 2026-10-12)."""
    notable = notable or Notable()
    kept = []
    for name, category, confidence, status, lat, lon, *rest in rows:
        meta_only = rest[0] if rest else True
        if not name or status == "permanently_closed":
            continue
        category = category or ""
        if category in DROPPED_CATEGORIES:
            continue
        need = PUBLIC_MIN_CONFIDENCE if is_public(category) else OTHER_MIN_CONFIDENCE
        if (confidence or 0.0) < need:
            continue
        kept.append((display_name(name.strip()), category, confidence, lat, lon, meta_only))
    kept = drop_misplaced_landmarks(kept)
    # Best listing first, so a duplicate always meets the one it merges into already placed.
    kept.sort(key=lambda r: (-r[2], len(r[0]), r[0]))
    cells = {}
    out = []
    for name, category, confidence, lat, lon, _ in kept:
        key = match_key(name)
        if not key:
            continue
        cell = cell_of(lat, lon, DUPLICATE_METERS)
        duplicate = False
        for near in near_cells(cell):
            for other_key, other_category, other_at in cells.get(near, ()):
                apart = meters_apart(other_at, (lat, lon))
                # One listing filed under two categories: the same name at the same spot.
                if key == other_key and apart <= SAME_SPOT_METERS:
                    duplicate = True
                    break
                if other_category != category or apart > DUPLICATE_METERS:
                    continue
                if key == other_key or key.startswith(other_key + " ") or other_key.startswith(key + " "):
                    duplicate = True
                    break
            if duplicate:
                break
        if duplicate:
            continue
        cells.setdefault(cell, []).append((key, category, (lat, lon)))
        # Only a public place takes Wikidata's prominence: a shop or a cafe named like an area or a
        # company ("Chiswick", a takeaway) isn't the famous thing.
        boost = notable.rank_near(name, lat, lon) if is_public(category) else None
        out.append((name, category, lat, lon, max(rank_of(category), boost or 0)))
    return out


# A street is named with the nearest neighborhood within this distance; past it, it stands alone.
AREA_RADIUS_METERS = 2500
# Divisions too broad to tell two streets apart.
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
    """Neighborhood rows (name, subtype, lat, lon) as a grid for [nearest_area]: neighborhoods and
    macrohoods only, less any as broad as a borough."""
    grid = {}
    for name, subtype, lat, lon in rows:
        if not name or subtype not in ("neighborhood", "macrohood") or BROAD_AREA.search(name):
            continue
        grid.setdefault(cell_of(lat, lon, AREA_RADIUS_METERS), []).append((name.strip(), lat, lon))
    return grid


def select_streets(rows, areas):
    """One (name, lat, lon) per named road per neighborhood, from segment rows (name, subtype, lat,
    lon), named with its nearest neighborhood ("High Street, Exampleton") so two streets of one
    name read apart; a road's segments in one neighborhood collapse to one entry, at their middle."""
    cells = {}
    for name, subtype, lat, lon in rows:
        if not name or subtype != "road":
            continue
        cells.setdefault((name.strip(), round(lat * 90), round(lon * 55)), (lat, lon))
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


def render(places, streets, postcodes, sources):
    """The index file's text: the header, then every entry, sorted."""
    def clean(text):
        return text.replace("\t", " ").replace("\n", " ")
    # A name with no letter or digit the app can match ("ピカデリー・サーカス") can't be found by typing.
    places = [p for p in places if match_key(p[0])]
    streets = [s for s in streets if match_key(s[0])]
    lines = [f"P\t{clean(n)}\t{c}\t{lat:.5f}\t{lon:.5f}\t{r}" for n, c, lat, lon, r in places]
    lines += [f"S\t{clean(n)}\t\t{lat:.5f}\t{lon:.5f}\t{STREET_RANK}" for n, lat, lon in streets]
    lines += [f"C\t{p}\t\t{lat:.5f}\t{lon:.5f}\t{POSTCODE_RANK}" for p, lat, lon in postcodes]
    lines.sort()
    header = f"# stopdash place index v{FORMAT_VERSION}\t{sources}"
    return header + "\n" + "\n".join(lines) + "\n"


def require(label, rows, minimum):
    if len(rows) < minimum:
        sys.exit(f"{label}: only {len(rows)} rows (expected at least {minimum}); not writing a partial index")
    return rows


# --- Network: run in the refresh workflow, not in tests. ---

def s3():
    import pyarrow.fs as fs
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    options = {}
    if proxy:
        p = urllib.parse.urlparse(proxy)
        options["proxy_options"] = {"scheme": "http", "host": p.hostname, "port": p.port}
    return fs.S3FileSystem(anonymous=True, region="us-west-2", **options)


def latest_release(filesystem):
    import pyarrow.fs as fs
    releases = [i.base_name for i in filesystem.get_file_info(fs.FileSelector(f"{BUCKET}/release")) if i.type == fs.FileType.Directory]
    if not releases:
        sys.exit("Overture: no releases listed")
    return sorted(releases)[-1]


def overture_rows(filesystem, release, theme, kind, columns):
    """Overture rows inside London, reading only the row groups whose bounding boxes reach it."""
    import pyarrow.compute as pc
    import pyarrow.fs as fs
    import pyarrow.parquet as pq
    for info in filesystem.get_file_info(fs.FileSelector(f"{BUCKET}/release/{release}/theme={theme}/type={kind}")):
        f = pq.ParquetFile(info.path, filesystem=filesystem)
        md = f.metadata
        paths = [md.schema.column(i).path for i in range(md.num_columns)]
        ix = {k: paths.index(f"bbox.{k}") for k in ("xmin", "xmax", "ymin", "ymax")}
        groups = []
        for g in range(md.num_row_groups):
            st = {k: md.row_group(g).column(i).statistics for k, i in ix.items()}
            if st["xmax"].min <= EAST and st["xmin"].max >= WEST and st["ymax"].min <= NORTH and st["ymin"].max >= SOUTH:
                groups.append(g)
        if not groups:
            continue
        t = f.read_row_groups(groups, columns=columns + ["bbox"])
        b = t.column("bbox")
        lat = pc.divide(pc.add(pc.struct_field(b, "ymin"), pc.struct_field(b, "ymax")), 2)
        lon = pc.divide(pc.add(pc.struct_field(b, "xmin"), pc.struct_field(b, "xmax")), 2)
        inside = pc.and_(pc.and_(pc.greater_equal(lon, WEST), pc.less_equal(lon, EAST)),
                         pc.and_(pc.greater_equal(lat, SOUTH), pc.less_equal(lat, NORTH)))
        t = t.append_column("lat", lat).append_column("lon", lon).filter(inside)
        yield t


def fetch_places(filesystem, release):
    import pyarrow.compute as pc
    rows = []
    for t in overture_rows(filesystem, release, "places", "place", ["names", "basic_category", "confidence", "operating_status", "sources"]):
        # Every listing also names Overture itself as a source, so it's the others that count.
        meta_only = [{s.get("dataset") for s in sources or ()} - OVERTURE_OWN == {"meta"} for sources in t.column("sources").to_pylist()]
        rows += zip(pc.struct_field(t.column("names"), "primary").to_pylist(), t.column("basic_category").to_pylist(),
                    t.column("confidence").to_pylist(), t.column("operating_status").to_pylist(),
                    t.column("lat").to_pylist(), t.column("lon").to_pylist(), meta_only)
    return rows


WIKIDATA_URL = "https://query.wikidata.org/sparql"
# Every named, placed item in the box, with its sitelinks across all Wikimedia projects.
WIKIDATA_QUERY = """SELECT ?item ?name ?loc ?links WHERE {
  SERVICE wikibase:box {
    ?item wdt:P625 ?loc .
    bd:serviceParam wikibase:cornerSouthWest "Point(%(west)s %(south)s)"^^geo:wktLiteral ;
                    wikibase:cornerNorthEast "Point(%(east)s %(north)s)"^^geo:wktLiteral .
  }
  ?item wikibase:sitelinks ?links .
  FILTER(?links > 0)
  ?item rdfs:label ?name . FILTER(LANG(?name) = "en")
}"""
# The Wikipedia editions alone of the given items: sitelinks also count Commons, Wikivoyage and the
# other Wikimedia projects, which say nothing about how widely a place is written up. Asked only for
# items whose sitelinks could reach a threshold, in batches: counting them for the whole box at once
# times the query service out, and so does its optimizer's own plan for a batch: the hint keeps the
# VALUES first, which answers a batch in under a second.
WIKIPEDIA_QUERY = """SELECT ?item (COUNT(DISTINCT ?article) AS ?links) WHERE {
  hint:Query hint:optimizer "None" .
  VALUES ?item { %(items)s }
  ?article schema:about ?item ; schema:isPartOf ?wiki .
  ?wiki wikibase:wikiGroup "wikipedia" .
} GROUP BY ?item"""
WIKIPEDIA_BATCH = 100
POINT = re.compile(r"Point\(\s*(-?[0-9.]+)\s+(-?[0-9.]+)\s*\)")
ENTITY = re.compile(r"^http://www\.wikidata\.org/entity/(Q[0-9]+)$")


def parse_wikidata(text):
    """The query service's CSV answer as (name, lat, lon, links, item) rows; a row it can't read is left out."""
    items = []
    for row in csv.DictReader(io.StringIO(text)):
        point = POINT.search(row.get("loc") or "")
        try:
            links = int(row.get("links") or "")
        except ValueError:
            continue
        if point and row.get("name"):
            items.append((row["name"], float(point.group(2)), float(point.group(1)), links, row.get("item") or ""))
    return items


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


def with_wikipedia_counts(rows, count):
    """[rows] (parse_wikidata's) as (name, lat, lon, Wikipedia editions): an item whose sitelinks reach
    no threshold keeps 0, since its Wikipedia editions can't either; the rest are asked of [count] (a
    list of item ids to {id: editions}), and one it doesn't answer for has none."""
    floor = min(at_least for at_least, _ in NOTABLE_RANKS)
    asked = sorted({m.group(1) for *_, links, item in rows if links >= floor for m in [ENTITY.match(item)] if m})
    editions = {}
    for start in range(0, len(asked), WIKIPEDIA_BATCH):
        editions.update(count(asked[start:start + WIKIPEDIA_BATCH]))
    out = []
    for name, lat, lon, _, item in rows:
        entity = ENTITY.match(item)
        out.append((name, lat, lon, editions.get(entity.group(1), 0) if entity else 0))
    return out


# The query service limits a client's rate, and now and then cuts a long answer short; told to slow
# down (429) or cut off, the builder waits (Retry-After, else a growing pause) and tries again, up to
# this many times before failing the build.
QUERY_ATTEMPTS = 5


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
            if e.code != 429 or attempt == QUERY_ATTEMPTS - 1:
                raise
            wait = e.headers.get("Retry-After") if e.headers else None
            pause = int(wait) if wait and wait.isdigit() else 10 * 2 ** attempt
            print(f"Wikidata asked us to slow down (429); waiting {pause} s", file=sys.stderr)
            sleep(pause)


def fetch_wikidata():
    rows = parse_wikidata(query_service(WIKIDATA_QUERY % {"west": WEST, "south": SOUTH, "east": EAST, "north": NORTH}))
    count = lambda ids: parse_wikipedia_counts(query_service(WIKIPEDIA_QUERY % {"items": " ".join("wd:" + i for i in ids)}))
    return with_wikipedia_counts(rows, count)


def from_osm_only(sources):
    """Whether OpenStreetMap is a listing's only real source, so its one credit covers it."""
    return {s.get("dataset") for s in sources or ()} - OVERTURE_OWN == {"OpenStreetMap"}


def fetch_osm_rows(filesystem, release, theme, kind):
    """(name, subtype, lat, lon) of a theme's OpenStreetMap features: no other source's credit is
    in the app, so a TomTom road or a geoBoundaries county is left out."""
    import pyarrow.compute as pc
    rows = []
    for t in overture_rows(filesystem, release, theme, kind, ["names", "subtype", "sources"]):
        rows += [(name, subtype, lat, lon) for name, subtype, lat, lon, sources in zip(
            pc.struct_field(t.column("names"), "primary").to_pylist(), t.column("subtype").to_pylist(),
            t.column("lat").to_pylist(), t.column("lon").to_pylist(), t.column("sources").to_pylist())
            if from_osm_only(sources)]
    return rows


def fetch_streets(filesystem, release):
    return fetch_osm_rows(filesystem, release, "transportation", "segment")


def fetch_areas(filesystem, release):
    return fetch_osm_rows(filesystem, release, "divisions", "division")


def fetch_postcode_rows(zip_path=None):
    data = open(zip_path, "rb").read() if zip_path else urllib.request.urlopen(CODEPOINT_URL, timeout=300).read()
    z = zipfile.ZipFile(io.BytesIO(data))
    for name in z.namelist():
        if name.startswith("Data/CSV/") and name.endswith(".csv"):
            yield from csv.reader(io.TextIOWrapper(z.open(name), "utf-8"))


def foursquare_notice(notice):
    """Foursquare's NOTICE as shipped: kept whole (the license requires it) with our changes appended."""
    if "Foursquare" not in notice or "Apache License" not in notice:
        raise SystemExit("Foursquare's NOTICE.txt doesn't look like the notice; not shipping it")
    return notice.rstrip() + FOURSQUARE_CHANGES


def foursquare_license(text):
    if "Apache License" not in text or "Version 2.0" not in text:
        raise SystemExit("Foursquare's LICENSE.txt isn't the Apache License 2.0; not shipping it")
    return text


def fetch_text(url):
    return urllib.request.urlopen(url, timeout=60).read().decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--codepoint-zip", help="read Code-Point Open from this zip instead of downloading it")
    args = parser.parse_args()
    from pyproj import Transformer
    bng = Transformer.from_crs("EPSG:27700", "EPSG:4326", always_xy=True)

    def to_wgs84(easting, northing):
        lon, lat = bng.transform(easting, northing)
        return lat, lon

    filesystem = s3()
    release = latest_release(filesystem)
    notable = Notable(require("Wikidata items", fetch_wikidata(), MIN_NOTABLE))
    places = require("places", select_places(fetch_places(filesystem, release), notable), MIN_PLACES)
    areas = area_grid(require("neighborhoods", fetch_areas(filesystem, release), MIN_AREAS))
    streets = require("streets", select_streets(fetch_streets(filesystem, release), areas), MIN_STREETS)
    postcodes = require("postcodes", select_postcodes(fetch_postcode_rows(args.codepoint_zip), to_wgs84), MIN_POSTCODES)
    sources = f"Overture Maps {release} (places: CDLA-Permissive-2.0, Apache-2.0; streets: ODbL); OS Code-Point Open (OGL v3.0); Wikidata (CC0)"
    notice = foursquare_notice(fetch_text(FOURSQUARE_NOTICE_URL))
    license_text = foursquare_license(fetch_text(FOURSQUARE_LICENSE_URL))
    out_dir = os.path.dirname(os.path.abspath(args.out))
    os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(render(places, streets, postcodes, sources))
    for name, text in ((NOTICE_FILE, notice), (LICENSE_FILE, license_text), (ODBL_FILE, ODBL_NOTICE)):
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as f:
            f.write(text)
    print(f"{len(places)} places, {len(streets)} streets, {len(postcodes)} postcodes from Overture {release}")


if __name__ == "__main__":
    main()
