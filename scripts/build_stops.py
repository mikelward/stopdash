#!/usr/bin/env python3
"""Build stopdash's bundled list of London's stops from TfL (SPEC *Finding stops → Near me now*).

Writes the TSV the app reads as `assets/stations/stops.tsv`: every stop TfL's own nearby
search (`/StopPoint?lat&lon&radius`, with the app's stop types) can answer in and around London —
bus and tram stops, tube, DLR, Overground, Elizabeth line, tram and rail stations, and piers —
with what the near-me list reads from that search: the stop's id, name (as TfL spells it; the app
cleans it), position, cluster (`stationNaptan`), interchange (`hubNaptanCode`), pole letter,
compass point, "towards", and the lines it serves. So the list is found on the device the moment
there's a fix, rather than after a TfL search that takes 10–25 s in central London when TfL hasn't
cached it.

Built from TfL's per-mode stop lists (`/StopPoint/Mode/{mode}`, bus in 1,000-stop pages) and its
per-type listings for stations and piers, whose nested trees are walked for the stop types the
search answers with and merged by id (a stop is listed under its pair, its cluster and its
station). Every listing is required: a failed or empty one stops the build, so the committed
list stays rather than a partial one replacing it. Keyless by default (about 45 requests); set
TFL_APP_KEY to use a key. Public transport data only — nothing about any user.

Format (v1), one record per line, tab-separated:
  # stopdash stops v1<TAB>bounds=<south>,<west>,<north>,<east>
  L <id> <mode> <name>                                      a line, once
  S <id> <type> <name> <lat> <lon> <cluster> <hub> <letter> <compass> <towards> <line ids>
where <type> is M (metro station), R (rail station), B (bus/coach/tram stop) or F (pier), and
<line ids> is comma-separated in TfL's order. A field TfL leaves out is empty.
"""
import argparse
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_station_index import fetch  # noqa: E402

FORMAT_VERSION = 1
# The stop types the app's nearby search asks TfL for (StopFinder.DEFAULT_NEARBY_STOP_TYPES).
TYPE_CODES = {
    "NaptanMetroStation": "M",
    "NaptanRailStation": "R",
    "NaptanPublicBusCoachTram": "B",
    "NaptanFerryPort": "F",
}
# The bus list's page size, and the most pages it's asked for: a page short of full is the last.
BUS_PAGE_SIZE = 1000
MAX_BUS_PAGES = 60
# How many times the bus list is read. TfL's paging isn't stable from one read to the next: a stop can
# fall between two pages, leaving only a copy without its lines from another listing (six stops of one
# name lost their buses in one refresh), so the reads are merged and a stop any of them found keeps it.
BUS_READS = 2
# TfL's own network, mode by mode; National Rail's mode listing is nationwide and times out, so its
# stations come from the type listings, which also hold piers the river-bus list leaves out.
MODES = ["tube", "dlr", "overground", "elizabeth-line", "tram", "river-bus", "cable-car"]
TYPE_LISTINGS = ["NaptanRailStation", "NaptanMetroStation", "NaptanFerryPort"]
# Greater London with a margin, as the station index's (build_station_index.py): TfL's buses run
# a little past it, while the type listings' stations across the country aren't wanted.
BOUNDS = (51.25, -0.65, 51.75, 0.40)
# A near-empty result means TfL answered oddly; the committed list stays.
MIN_BUS_STOPS = 15000
MIN_STATIONS = 600
# The fewest stops that must carry each mode's lines: about half of each count in October 2026.
# A mode TfL answers without its lines would leave its stops route-less, which the near-me list
# never shows, while the bundle still claims the area; a halved count means TfL answered short.
MIN_LINED_BY_MODE = {
    "bus": 10000, "tube": 130, "dlr": 20, "overground": 50, "elizabeth-line": 15,
    "tram": 20, "river-bus": 10, "cable-car": 1, "national-rail": 150,
}


def stop_id(stop):
    return stop.get("id") or stop.get("naptanId") or ""


def direction(stop):
    """The stop's Direction properties (CompassPoint, Towards), as TfL lists them."""
    return {
        p.get("key"): p.get("value") or ""
        for p in stop.get("additionalProperties") or []
        if p.get("category") == "Direction"
    }


def collect(points, into):
    """Every stop of a searched type in [points] and their nested children, grouped by id in
    [into]: one stop is listed several times (under its pair, its cluster, its station)."""
    def walk(point):
        if point.get("stopType") in TYPE_CODES and stop_id(point):
            into.setdefault(stop_id(point), []).append(point)
        for child in point.get("children") or []:
            walk(child)

    for point in points or []:
        walk(point)
    return into


def positioned(stop):
    """Whether TfL gave [stop] a position: a listing without one carries 0, 0 or nothing, while a
    stop on the Greenwich meridian has a real longitude of 0."""
    lat, lon = stop.get("lat"), stop.get("lon")
    return lat is not None and lon is not None and (lat, lon) != (0, 0)


def merged(listings):
    """One stop from its [listings]: the fullest listing's identity and position, with every
    listing's lines and Direction properties (one copy of a pole can lack what another carries)."""
    best = max(listings, key=lambda p: (positioned(p), len(p.get("lines") or []), len(direction(p))))
    first_mode = next((m for p in listings for m in p.get("modes") or []), "")
    mode_of = {}
    names = {}
    for p in listings:
        for group in p.get("lineModeGroups") or []:
            for line in group.get("lineIdentifier") or []:
                mode_of.setdefault(line, group.get("modeName") or "")
        for line in p.get("lines") or []:
            if line.get("id"):
                names.setdefault(line["id"], line.get("name") or line["id"])
    props = {}
    for p in listings:
        for key, value in direction(p).items():
            if value:
                props.setdefault(key, value)
    return {
        "id": stop_id(best),
        "type": TYPE_CODES[best["stopType"]],
        "name": best.get("commonName") or "",
        "lat": best.get("lat"),
        "lon": best.get("lon"),
        "cluster": next((p["stationNaptan"] for p in listings if p.get("stationNaptan")), ""),
        "hub": next((p["hubNaptanCode"] for p in listings if p.get("hubNaptanCode")), ""),
        "letter": next((p["stopLetter"] for p in listings if p.get("stopLetter")), ""),
        "compass": props.get("CompassPoint", ""),
        "towards": props.get("Towards", ""),
        # TfL's search lists a stop's lines by id; a line with no mode group takes the stop's mode,
        # as the app does with TfL's own answer.
        "lines": [(line, mode_of.get(line) or first_mode, names[line]) for line in sorted(names)],
    }


def in_bounds(stop, bounds=BOUNDS):
    if not positioned(stop):
        return False
    lat, lon = stop["lat"], stop["lon"]
    return bounds[0] <= lat <= bounds[2] and bounds[1] <= lon <= bounds[3]


def build(listings_by_id, bounds=BOUNDS):
    """The stops and the line table for the file, from the merged listings (pure, for tests).
    A line's name and mode are the first seen; a line TfL files under two modes keeps the first."""
    stops = [merged(v) for v in listings_by_id.values()]
    stops = sorted((s for s in stops if in_bounds(s, bounds)), key=lambda s: s["id"])
    lines = {}
    for s in stops:
        for line, mode, name in s["lines"]:
            lines.setdefault(line, (mode, name))
    return stops, lines


def clean(text):
    """[text] with any tab or line break, which would break the record, as a space; otherwise as
    TfL spells it (its names carry runs of spaces, which the app's name cleaning handles)."""
    return re.sub(r"[\t\r\n]", " ", str(text))


def write(out, stops, lines, bounds=BOUNDS):
    out.write(f"# stopdash stops v{FORMAT_VERSION}\tbounds={','.join(str(b) for b in bounds)}\n")
    for line in sorted(lines):
        mode, name = lines[line]
        out.write("\t".join(["L", clean(line), clean(mode), clean(name)]) + "\n")
    for s in stops:
        out.write("\t".join([
            "S", clean(s["id"]), s["type"], clean(s["name"]),
            f"{s['lat']:.6f}", f"{s['lon']:.6f}",
            clean(s["cluster"]), clean(s["hub"]), clean(s["letter"]),
            clean(s["compass"]), clean(s["towards"]),
            ",".join(clean(line) for line, _, _ in s["lines"]),
        ]) + "\n")


def checked(stops):
    """Refuses a list TfL answered short: the committed one stays."""
    buses = sum(1 for s in stops if s["type"] == "B")
    stations = sum(1 for s in stops if s["type"] in "MRF")
    if buses < MIN_BUS_STOPS:
        sys.exit(f"only {buses} bus stops; refusing to write a partial list")
    if stations < MIN_STATIONS:
        sys.exit(f"only {stations} stations; refusing to write a partial list")
    for mode, floor in MIN_LINED_BY_MODE.items():
        lined = sum(1 for s in stops if any(m == mode for _, m, _ in s["lines"]))
        if lined < floor:
            sys.exit(f"only {lined} stops carry {mode} lines (under {floor}); refusing to write the list")
    return stops


def required(label, points):
    """[points] when they hold a positioned stop of a searched type in the area, checked on this
    listing alone: a listing with only parent records (or only far-off stops) would otherwise drop
    a whole mode while the other listings keep the totals up, and the app would then take the area
    as covered and never ask TfL."""
    stops, _ = build(collect(points or [], {}))
    if not stops:
        sys.exit(f"TfL listed no {label} stops in the area; refusing to write a list without them")
    return points


def fetch_bus_pages(fetch_json=fetch):
    points = []
    for page in range(1, MAX_BUS_PAGES + 1):
        found = (fetch_json("/StopPoint/Mode/bus", {"page": page}) or {}).get("stopPoints") or []
        points.extend(found)
        if len(found) < BUS_PAGE_SIZE:
            return required("bus", points)
    sys.exit(f"the bus list ran past {MAX_BUS_PAGES} pages; refusing to guess where it ends")


def read_bus_list(fetch_json=fetch, reads=BUS_READS):
    """Every bus stop TfL's paged list gives in [reads] reads of it, for [collect] to merge."""
    points = []
    for _ in range(reads):
        points.extend(fetch_bus_pages(fetch_json))
    return points


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="where to write stops.tsv")
    args = parser.parse_args(argv)
    found = {}
    collect(read_bus_list(), found)
    for mode in MODES:
        collect(required(mode, (fetch(f"/StopPoint/Mode/{mode}") or {}).get("stopPoints")), found)
    for stop_type in TYPE_LISTINGS:
        collect(required(stop_type, fetch(f"/StopPoint/Type/{stop_type}")), found)
    stops, lines = build(found)
    checked(stops)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        write(out, stops, lines)
    print(f"wrote {len(stops)} stops and {len(lines)} lines to {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
