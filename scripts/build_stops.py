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

It also writes the station model's membership (dev-docs/station-model.md), worked out by
stop_membership.py from these stops and the station index (--stations): each bus pole's ICS code,
stop, owning station and hub, and the station and hub of each station record the index leaves
out. A content revision over the lines and the membership is stamped into this file's header
and into the station index, so the app can tell when a refresh changed either.

TfL's lists aren't stable from one read to the next: a bus stop, one of its lines, or its compass
point or "towards" can be missing from one read and back on the next. So, given the last build
(--previous, the directory holding the committed stops.tsv), whatever that build had and this
read lacks is kept for up to KEEP_DAYS days, and the date it first went missing is recorded in
stops_kept.tsv beside the output. A rebuild then changes only what TfL really changed, and what it
really removed goes once it has been missing for KEEP_DAYS. The app doesn't read stops_kept.tsv.

Format (v2), one record per line, tab-separated:
  # stopdash stops v2<TAB>bounds=<south>,<west>,<north>,<east><TAB>revision=<hex>
  L <id> <mode> <name>                                      a line, once
  S <id> <type> <name> <lat> <lon> <cluster> <hub> <letter> <compass> <towards> <line ids>
    <ics> <stop> <owner> <model hub>
where <type> is M (metro station), R (rail station), B (bus/coach/tram stop) or F (pier),
<line ids> is comma-separated in TfL's order, and <hub> is TfL's interchange. On a B row <stop>
is its stop, <owner> the station that owns it (empty for none) and <model hub> its hub; on a
station record the index leaves out, <stop> is its station and <owner> is empty; every other row
leaves all four empty. A field TfL leaves out is empty.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_station_index import fetch  # noqa: E402
from stop_membership import membership  # noqa: E402

FORMAT_VERSION = 2
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
# How long a stop, line or field the last build had is kept after TfL stops listing it, and the
# file recording since when (beside stops.tsv; a builder file the app never reads).
KEEP_DAYS = 30
KEPT_FILE = "stops_kept.tsv"
KEPT_HEADER = "# stopdash kept stops v1"
# The fields kept when a read leaves them blank; the id, type and position are the stop itself.
KEPT_FIELDS = ("name", "cluster", "hub", "ics", "letter", "compass", "towards")
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
        "ics": next((str(p["icsCode"]).strip() for p in listings if p.get("icsCode")), ""),
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
    return stops, line_table(stops)


def clean(text):
    """[text] with any tab or line break, which would break the record, as a space; otherwise as
    TfL spells it (its names carry runs of spaces, which the app's name cleaning handles)."""
    return re.sub(r"[\t\r\n]", " ", str(text))


NO_MEMBERSHIP = ("", "", "", "")


def write(out, stops, lines, columns=None, revision="", bounds=BOUNDS):
    """The file, each stop with its [columns] (membership, by id) and the header's [revision]."""
    columns = columns or {}
    out.write(f"# stopdash stops v{FORMAT_VERSION}\tbounds={','.join(str(b) for b in bounds)}\trevision={revision}\n")
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
            *(clean(c) for c in columns.get(s["id"], NO_MEMBERSHIP)),
        ]) + "\n")


def read_previous(text):
    """id -> stop, as build() gives it, from a stops.tsv this builder wrote; empty for one of
    another format version, which is then rebuilt from TfL's read alone."""
    rows = text.splitlines()
    if not rows or not rows[0].startswith(f"# stopdash stops v{FORMAT_VERSION}\t"):
        return {}
    lines = {}
    stops = {}
    for row in rows[1:]:
        fields = row.split("\t")
        if fields[0] == "L" and len(fields) == 4:
            lines[fields[1]] = (fields[2], fields[3])
        elif fields[0] == "S":
            fields += [""] * (16 - len(fields))
            stops[fields[1]] = {
                "id": fields[1], "type": fields[2], "name": fields[3],
                "lat": float(fields[4]), "lon": float(fields[5]),
                "cluster": fields[6], "hub": fields[7], "letter": fields[8],
                "compass": fields[9], "towards": fields[10], "ics": fields[12],
                "lines": [(line, *lines.get(line, ("", line))) for line in fields[11].split(",") if line],
            }
    return stops


def read_kept(text):
    """(stop id, what) -> the date it went missing, from a stops_kept.tsv; what is "*" for the
    whole stop, "line:<id>" for one of its lines, or a field's name."""
    rows = text.splitlines()
    if not rows or rows[0] != KEPT_HEADER:
        return {}
    kept = {}
    for row in rows[1:]:
        fields = row.split("\t")
        if len(fields) == 3:
            kept[(fields[1], fields[2])] = datetime.date.fromisoformat(fields[0])
    return kept


def write_kept(out, kept):
    out.write(KEPT_HEADER + "\n")
    for (sid, what), since in sorted(kept.items()):
        out.write(f"{since.isoformat()}\t{sid}\t{what}\n")


def keep(stops, previous, kept, today, days=KEEP_DAYS):
    """[stops] with what [previous] had and they lack, while it has been missing for at most [days]:
    a bus stop, a stop's line, or a field this read left blank. Returns the stops, in id order, and the
    record of since when each kept item has been missing (what came back drops out of it)."""
    found = {}

    def held(sid, what, since=None):
        since = kept.get((sid, what), since or today)
        if (today - since).days > days:
            return False
        found[(sid, what)] = since
        return True

    def fill(stop, old, sid):
        # A stop kept whole that comes back without some of what it had: those have been
        # missing since the stop was, not since today.
        whole = kept.get((sid, "*"))
        for field in KEPT_FIELDS:
            if not stop[field] and old[field] and held(sid, field, whole):
                stop[field] = old[field]
        have = {line for line, _, _ in stop["lines"]}
        stop["lines"] = sorted(
            stop["lines"] + [l for l in old["lines"] if l[0] not in have and held(sid, "line:" + l[0], whole)])

    out = []
    for stop in stops:
        old = previous.get(stop["id"])
        if old:
            fill(stop, old, stop["id"])
        out.append(stop)
    fresh = {s["id"] for s in stops}
    for sid, old in previous.items():
        # Only a bus stop is kept whole: the paged bus list is what drifts, while a station comes
        # from listings checked on their own and is placed by the station index, which a row
        # rebuilt from the last list can't stand in for.
        if sid in fresh or old["type"] != "B" or not held(sid, "*"):
            continue
        # A whole stop is kept as it was, less what was already kept and has now run out.
        stop = dict(old)
        for field in KEPT_FIELDS:
            if (sid, field) in kept and not held(sid, field):
                stop[field] = ""
        stop["lines"] = [l for l in old["lines"] if (sid, "line:" + l[0]) not in kept or held(sid, "line:" + l[0])]
        out.append(stop)
    return sorted(out, key=lambda s: s["id"]), found


def line_table(stops):
    """The line table from the stops' lines: a line's name and mode are the first seen."""
    lines = {}
    for s in stops:
        for line, mode, name in s["lines"]:
            lines.setdefault(line, (mode, name))
    return lines


def kept_line_table(read, stops):
    """The line table for [stops], what TfL's [read] says about a line first: a line's name and mode
    come from the last build only where this read no longer lists it at all."""
    return {**line_table(stops), **line_table(read)}


def revision(stops, lines, columns, index):
    """A hash over the lines (the line table, each stop's, and each indexed station's and line
    name) and the membership of both files:
    the same in stops.tsv and station_index.json, and changed by a refresh that changes either."""
    digest = hashlib.sha256()
    for line in sorted(lines):
        digest.update("\t".join(["L", line, *lines[line]]).encode() + b"\n")
    for s in stops:
        record = [s["id"], ",".join(line for line, _, _ in s["lines"]), *columns.get(s["id"], NO_MEMBERSHIP)]
        digest.update("\t".join(record).encode() + b"\n")
    # The index's own lines too: hundreds of its stations (the commuter belt's) aren't in stops.tsv.
    for entry in index["stations"]:
        record = [entry["id"], entry.get("icsCode", ""), entry.get("station", ""), entry.get("modelHub", ""),
                  json.dumps(entry.get("modeLines", {}), sort_keys=True)]
        digest.update("\t".join(record).encode() + b"\n")
    digest.update(json.dumps(index.get("lineNames", {}), sort_keys=True).encode())
    return digest.hexdigest()[:16]


def with_revision(index, value):
    """[index] with its "revision" set to [value], after its "version"."""
    return {"version": index["version"], "revision": value, **{k: v for k, v in index.items() if k not in ("version", "revision")}}


def checked_membership(stops, columns):
    """Refuses a list where a bus pole lacks its membership: the app would read it ungrouped."""
    missing = [s["id"] for s in stops if s["type"] == "B" and s["id"] not in columns]
    if missing:
        sys.exit(f"{len(missing)} bus stops came out with no stop or hub; refusing to write the list")
    return columns


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
    parser.add_argument("--stations", required=True,
                        help="the station_index.json build_station_index.py wrote; its revision is stamped in place")
    parser.add_argument("--previous",
                        help="the directory holding the last build's stops.tsv and stops_kept.tsv, to keep what this read misses")
    args = parser.parse_args(argv)
    with open(args.stations, encoding="utf-8") as f:
        index = json.load(f)
    previous, kept = {}, {}
    if args.previous:
        # Read before anything is written: --out can be the same file.
        def text_of(name):
            path = os.path.join(args.previous, name)
            if not os.path.exists(path):
                print(f"no {path}; nothing kept from it")
                return ""
            with open(path, encoding="utf-8") as f:
                return f.read()
        previous = read_previous(text_of("stops.tsv"))
        kept = read_kept(text_of(KEPT_FILE))
    found = {}
    collect(read_bus_list(), found)
    for mode in MODES:
        collect(required(mode, (fetch(f"/StopPoint/Mode/{mode}") or {}).get("stopPoints")), found)
    for stop_type in TYPE_LISTINGS:
        collect(required(stop_type, fetch(f"/StopPoint/Type/{stop_type}")), found)
    read, _ = build(found)
    # TfL's read alone must pass: a short answer is refused, not padded out with the last build.
    checked(read)
    # keep() fills the read's stops in place; their lines as read are what the table goes by.
    read_lines = [dict(s, lines=list(s["lines"])) for s in read]
    stops, kept = keep(read, previous, kept, datetime.datetime.now(datetime.timezone.utc).date())
    lines = kept_line_table(read_lines, stops)
    columns = checked_membership(stops, membership(stops, index))
    stamp = revision(stops, lines, columns, index)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as out:
        write(out, stops, lines, columns, stamp)
    with open(os.path.join(os.path.dirname(os.path.abspath(args.out)), KEPT_FILE), "w", encoding="utf-8", newline="\n") as out:
        write_kept(out, kept)
    with open(args.stations, "w", encoding="utf-8") as out:
        json.dump(with_revision(index, stamp), out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"wrote {len(stops)} stops and {len(lines)} lines to {args.out}, revision {stamp};"
          f" {len(kept)} kept from earlier builds that this read missed")

if __name__ == "__main__":
    main(sys.argv[1:])
