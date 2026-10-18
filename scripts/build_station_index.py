#!/usr/bin/env python3
"""Build stopdash's bundled station index from TfL (SPEC *Finding stops → Find a station*).

Writes the JSON the app reads as `assets/stations/station_index.json`: every tube, DLR,
Overground, Elizabeth line, tram, National Rail and pier station in and around London, and the rail
stations of the commuter belt its lines reach (within COMMUTER_RADIUS_KM of Charing Cross), plus
the interchanges ("hubs") that group them, each with its TfL id, name as TfL spells it (the
app cleans it), modes, and hub; a station also carries its position and its lines by mode (tube
lines, National Rail services, Overground lines, …), so the near-me list can name the nearest
station of a line it doesn't reach ("From …"), and the platforms TfL lists under it
("platforms"), so a planned trip's train from a platform the Journey Planner names alone is
read as leaving that station. A National Rail station also carries, per service,
the ends of the routes it's on ("routeEnds"), since one service runs to different places from
different stations — Thameslink to Bedford from one, to Cambridge from another. A station at the
end of every tube, DLR, Overground, Elizabeth line or tram route it's on carries, per line, the
far ends of those routes ("terminusEnds"): TfL lists a train arriving there as bound for the
station itself, and the app relabels it to where it leaves for. Bus stops are left to TfL's live search: there are ~20,000.

Each station record also carries the station model's membership (dev-docs/station-model.md),
worked out here so the device only reads it: its TfL ICS code ("icsCode"), the station it
belongs to ("station": the records sharing an ICS code and a mode family, "1001276:rail" for St
Pancras's three records), and its hub ("modelHub": the TfL interchange its station's records
name, else the station itself). "hub" stays TfL's own interchange. Not yet read by the app.

Built from TfL's per-mode stop lists (`/StopPoint/Mode/{mode}`), one mode at a time, and its
rail-station listing for National Rail. Every listing is required: a failed or empty one stops
the build, so the committed index stays rather than a partial one replacing it.
Run by the `station-index` workflow, which commits the output; the sandboxed dev environment
can't reach TfL. Keyless by default (a handful of requests); set TFL_APP_KEY to use a key.
Public transport data only — nothing about any user.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.tfl.gov.uk"
STOP_TYPES = ["NaptanMetroStation", "NaptanRailStation", "NaptanFerryPort"]
# The modes stopdash shows departures for, bus aside (bus stops come from the live search).
MODES = {"tube", "dlr", "overground", "elizabeth-line", "national-rail", "tram", "river-bus", "cable-car"}
# Greater London with a margin, so a TfL stop just past the boundary (Watford Junction, Epping)
# stays, while TfL's National Rail stations across the country don't bloat the index.
LAT_RANGE = (51.25, 51.75)
LON_RANGE = (-0.65, 0.40)
# Past that box, a rail station on a line that also calls in London stays when it's within this far of
# Charing Cross (maintainer, 2026-10-12): the commuter belt the London lines reach (every Thameslink
# station, Reading, Brighton, Cambridge), as far as journey alerts reach (JourneyAlerts.LONDON_RADIUS_KM).
COMMUTER_RADIUS_KM = 120.0
CHARING_CROSS = (51.5073, -0.1276)
# The modes whose lines run out of London into the commuter belt.
COMMUTER_MODES = {"national-rail", "elizabeth-line", "overground"}
HUB_BATCH = 10
# Spacing between route requests when keyless: TfL allows ~50 a minute without a key.
ROUTE_REQUEST_GAP = 1.5
FORMAT_VERSION = 1
# The modes whose trains TfL lists at a terminus as arriving there, rather than leaving ("terminusEnds").
TERMINUS_MODES = ("tube", "dlr", "overground", "elizabeth-line", "tram")


RETRY_DELAYS = (5, 15, 45)
# Keyless TfL counts requests per minute, so a 429 waits out at least the rest of the minute.
RATE_LIMIT_WAIT = 60


def retry_wait(error, delay):
    """How long to wait before retrying after [error]: a 429 waits for TfL's Retry-After (or a
    minute), anything else the scheduled [delay]."""
    if error is None or error.code != 429:
        return delay
    try:
        after = int(error.headers.get("Retry-After", ""))
    except (TypeError, ValueError, AttributeError):
        after = 0
    return max(delay, after, RATE_LIMIT_WAIT)


def fetch(path, params=None, retry_delays=RETRY_DELAYS, sleep=time.sleep):
    """GET a TfL path as JSON, retrying a timeout, a 5xx (TfL's larger lists sometimes 504) or a
    429 (the keyless per-minute limit, hit when several builds run close together)."""
    query = dict(params or {})
    key = os.environ.get("TFL_APP_KEY")
    if key:
        query["app_key"] = key
    url = BASE + path + ("?" + urllib.parse.urlencode(query) if query else "")
    request = urllib.request.Request(url, headers={"User-Agent": "stopdash-station-index"})
    last_error = None
    for attempt, delay in enumerate((0,) + tuple(retry_delays)):
        wait = retry_wait(last_error, delay) if attempt else 0
        if wait:
            print(f"retrying {path} in {wait}s", file=sys.stderr)
            sleep(wait)
        last_error = None
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if (e.code < 500 and e.code != 429) or attempt == len(retry_delays):
                raise
            last_error = e
        except (urllib.error.URLError, TimeoutError):
            if attempt == len(retry_delays):
                raise


def station_points(points):
    """Every stop point in [points] and their nested children whose type is a station's — a
    per-mode listing nests a hub's stations and a station's platforms, and only the stations
    carry departures here. Deduplicated by id, merging a station listed under several modes (a DLR
    listing's Stratford lacks the tube lines its tube listing carries); a station's own children
    aren't searched."""
    found = {}

    def walk(point):
        if point.get("stopType") in STOP_TYPES:
            sid = stop_id(point)
            if sid in found:
                found[sid] = merged(found[sid], point)
            else:
                found[sid] = point
            return
        for child in point.get("children") or []:
            walk(child)

    for point in points:
        walk(point)
    return list(found.values())


def merged(first, other):
    """[first] with [other]'s modes, line groups, platforms and ICS code added: two listings of one station."""
    groups = {}
    for group in (first.get("lineModeGroups") or []) + (other.get("lineModeGroups") or []):
        mode = group.get("modeName")
        groups.setdefault(mode, set()).update(group.get("lineIdentifier") or [])
    lines = {line.get("id"): line for line in (first.get("lines") or []) + (other.get("lines") or []) if line.get("id")}
    # Each listing can nest a different set of the station's platforms, so all of them are kept.
    children = {stop_id(c): c for c in (first.get("children") or []) + (other.get("children") or []) if stop_id(c)}
    return {
        **first,
        # Either listing's ICS code, so membership doesn't hang on which mode listed it first.
        **({"icsCode": first.get("icsCode") or other.get("icsCode")}
           if first.get("icsCode") or other.get("icsCode") else {}),
        "modes": sorted(set(first.get("modes") or []) | set(other.get("modes") or [])),
        "lineModeGroups": [{"modeName": m, "lineIdentifier": sorted(ids)} for m, ids in sorted(groups.items())],
        "lines": [lines[k] for k in sorted(lines)],
        "children": [children[k] for k in sorted(children)],
    }


def in_london(stop):
    lat, lon = stop.get("lat"), stop.get("lon")
    if lat is None or lon is None:
        return False
    return LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LON_RANGE[0] <= lon <= LON_RANGE[1]


def km_from_charing_cross(lat, lon):
    """The great-circle distance from Charing Cross, as JourneyAlerts measures it."""
    import math
    lat1, lat2 = math.radians(CHARING_CROSS[0]), math.radians(lat)
    dlat, dlon = lat2 - lat1, math.radians(lon - CHARING_CROSS[1])
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(a))


def commuter_lines(stops):
    """The commuter-mode lines that call at a station in London ([in_london]), by id."""
    return {line for stop in stops if in_london(stop)
            for mode, ids in lines_by_mode(stop).items() if mode in COMMUTER_MODES for line in ids}


def london_reach(sequences_by_line, london_ids):
    """National Rail line id -> the stations on its routes that call in London: every stop of an
    ordered route (TfL route sequences) with a stop in [london_ids]. An operator runs lines that
    never reach London too (Bedford to Bletchley), so a station counts by its route, not its line.
    Pure, for tests."""
    reach = {}
    for line, sequences in sequences_by_line.items():
        on = reach.setdefault(line, set())
        for sequence in sequences:
            for route in (sequence or {}).get("orderedLineRoutes") or []:
                ids = route.get("naptanIds") or []
                if any(sid in london_ids for sid in ids):
                    on.update(ids)
    return reach


def checked_reach(reach):
    """[reach], refusing a line that calls in London yet came back with no route through it: an
    empty or malformed answer (TfL's 200 with no routes) would drop every commuter-belt station on
    that line, and the build would ship the gap."""
    empty = sorted(line for line, on in reach.items() if not on)
    if empty:
        sys.exit(f"TfL gave no London route for {', '.join(empty)}; refusing to write a partial index")
    return reach


def in_catchment(stop, london_lines, reach=None):
    """Whether [stop] belongs in the index: in London ([in_london]), or a station within
    COMMUTER_RADIUS_KM of Charing Cross on one of [london_lines] (commuter_lines). On a National
    Rail line [reach] (london_reach) knows the routes of, the station must be on one that calls in
    London; a line without route data counts whole."""
    if in_london(stop):
        return True
    lat, lon = stop.get("lat"), stop.get("lon")
    if lat is None or lon is None or km_from_charing_cross(lat, lon) > COMMUTER_RADIUS_KM:
        return False
    reach = reach or {}
    sid = stop_id(stop)
    for mode, ids in lines_by_mode(stop).items():
        if mode not in COMMUTER_MODES:
            continue
        for line in ids:
            if line not in london_lines:
                continue
            if mode == "national-rail" and line in reach and sid not in reach[line]:
                continue
            return True
    return False


def stop_id(stop):
    return stop.get("id") or stop.get("naptanId") or ""


# A station record's mode family, from its id's NaPTAN prefix (dev-docs/station-model.md *The
# model*): a station is the records sharing TfL's ICS code within one family, so St Pancras's
# three rail records are one station and King's Cross St. Pancras Underground another. Checked in
# order, the Northern line extension's two Underground records ahead of the prefixes.
FAMILY_IDS = {"940GZZBPSUST": "tube", "940GZZNEUGST": "tube"}
FAMILY_PREFIXES = (
    ("910G", "rail"),
    ("940GZZDL", "dlr"),
    ("940GZZCR", "tram"),
    ("940GZZAL", "cable-car"),
    ("940GZZLU", "tube"),
    ("930G", "pier"),
)
# The TfL modes each family's records may list; a record listing none of its family's is refused.
FAMILY_MODES = {
    "rail": {"national-rail", "overground", "elizabeth-line"},
    "dlr": {"dlr"},
    "tram": {"tram"},
    "cable-car": {"cable-car"},
    "tube": {"tube"},
    "pier": {"river-bus"},
}


def family_of(sid):
    """[sid]'s mode family, or None for a prefix no family claims."""
    if sid in FAMILY_IDS:
        return FAMILY_IDS[sid]
    return next((family for prefix, family in FAMILY_PREFIXES if sid.startswith(prefix)), None)


def checked_family(sid, modes):
    """[sid]'s family, refusing the build on a prefix no family claims or a family none of the
    record's [modes] belong to: a new prefix TfL brings in is classified by hand before it ships,
    rather than falling into the wrong family and joining the wrong station."""
    family = family_of(sid)
    if family is None:
        sys.exit(f"{sid} has a prefix no station family claims; classify it in FAMILY_PREFIXES")
    if not set(modes) & FAMILY_MODES[family]:
        sys.exit(f"{sid} is a {family} record but TfL lists it as {', '.join(modes)}; refusing to group it")
    return family


def station_key(sid, ics, family):
    """The model's id for the station [sid] belongs to: its ICS code and family ("1001276:rail"),
    or with no ICS code the record's own id, so two records TfL left without one never merge."""
    return f"{ics}:{family}" if ics else sid


def add_membership(stations, ics_codes):
    """Each station record in [stations] (by id, interchange rows aside) with its ICS code
    ("icsCode"), its station ("station", station_key) and the model's hub ("modelHub"): the TfL
    interchange most of the station's records name, ties to the lower id, records naming none not
    counting, else the station itself (dev-docs/station-model.md *The model*). TfL's own
    interchange stays in "hub". Bus stops join a station's hub in stops.tsv, not here."""
    members = {}
    no_code = 0
    for sid, station in stations.items():
        ics = ics_codes.get(sid, "")
        if not ics:
            no_code += 1
        key = station_key(sid, ics, checked_family(sid, station["modes"]))
        station.update({**({"icsCode": ics} if ics else {}), "station": key})
        members.setdefault(key, []).append(station)
    split = 0
    for key, records in members.items():
        named = {}
        for record in records:
            if record.get("hub"):
                named[record["hub"]] = named.get(record["hub"], 0) + 1
        if len(named) > 1:
            split += 1
        hub = min(named, key=lambda h: (-named[h], h)) if named else key
        for record in records:
            record["modelHub"] = hub
    # Counted on every build, so a refresh that drops codes or splits a station shows in its log.
    print(f"{no_code} station records without an ICS code; {split} stations whose records name "
          "different interchanges", file=sys.stderr)


def platform_owner(platform, records):
    """The record of [records] (each listing [platform]) that keeps it: the only one; else, when
    all are one station's, the record [platform]'s id names (9100X at 910GX, 9400X at 940GX),
    else the lowest record id; None when they're two stations', which says nothing about which
    one a train leaves."""
    if len(records) == 1:
        return records[0]["id"]
    if len({r["station"] for r in records}) > 1:
        return None
    ids = sorted(r["id"] for r in records)
    named = next((s + platform[len(p):] for p, s in (("9100", "910G"), ("9400", "940G"))
                  if platform.startswith(p)), None)
    return named if named in ids else ids[0]


def build_index(stops, hubs, reach=None):
    """The index document from TfL's station list and its hubs' own records, with [reach]
    (london_reach) deciding which commuter-belt rail stations call at London (pure, for tests)."""
    london_lines = commuter_lines(stops)
    stations = {}
    ics_codes = {}
    hub_modes = {}
    # Each indexed line's name as TfL spells it ("Hammersmith & City", "Elizabeth line"), for the
    # app to label a line it knows only by id.
    line_names = {}
    for stop in stops:
        sid = stop_id(stop)
        name = (stop.get("commonName") or "").strip()
        modes = sorted(set(stop.get("modes") or []) & MODES)
        if not sid or not name or not modes or stop.get("stopType") not in STOP_TYPES or not in_catchment(stop, london_lines, reach):
            continue
        hub = stop.get("hubNaptanCode") or ""
        lines = lines_by_mode(stop)
        platforms = platform_ids(stop)
        stations[sid] = {
            "id": sid, "name": name, "modes": modes, **({"hub": hub} if hub else {}),
            # Five decimals is about a meter: plenty to measure how far a station is.
            "lat": round(stop["lat"], 5), "lon": round(stop["lon"], 5),
            # "modeLines", not "lines": an earlier build wrote "lines" as a tube-only list.
            **({"modeLines": lines} if lines else {}),
            **({"platforms": platforms} if platforms else {}),
        }
        if stop.get("icsCode"):
            ics_codes[sid] = str(stop["icsCode"]).strip()
        if hub:
            hub_modes.setdefault(hub, set()).update(modes)
        indexed = {line for ids in lines.values() for line in ids}
        for line in stop.get("lines") or []:
            name = (line.get("name") or "").strip()
            if line.get("id") in indexed and name:
                line_names[line["id"]] = name
    add_membership(stations, ics_codes)
    # A platform TfL lists under records of one station is that station's, kept under one record
    # (platform_owner); under two stations it says nothing about which one a train leaves: neither
    # keeps it, so the trip is read no worse than before the index had platforms.
    listed = {}
    for station in stations.values():
        for platform in station.get("platforms", []):
            listed.setdefault(platform, []).append(station)
    for station in stations.values():
        kept = [p for p in station.get("platforms", []) if platform_owner(p, listed[p]) == station["id"]]
        if kept:
            station["platforms"] = kept
        else:
            station.pop("platforms", None)
    split = sorted(p for p, records in listed.items() if platform_owner(p, records) is None)
    if split:
        print(f"{len(split)} platforms listed under two stations, kept by neither", file=sys.stderr)
    for hub in hubs:
        hid = stop_id(hub)
        name = (hub.get("commonName") or "").strip()
        if hid in hub_modes and name:
            stations[hid] = {"id": hid, "name": name, "modes": sorted(hub_modes[hid])}
    # Sorted by id so a refresh's diff shows only what TfL changed.
    return {
        "version": FORMAT_VERSION,
        "stations": [stations[k] for k in sorted(stations)],
        "lineNames": {k: line_names[k] for k in sorted(line_names)},
    }


# The stops TfL nests under a station that the Journey Planner can name a train's end by alone,
# with no station id ("9100LIVSTLL1"): a rail access area, a tube platform. Its entrances ("4900…")
# and the bus stops around it ("490…") are never a train's end.
PLATFORM_PREFIXES = ("9100", "9400")


def platform_ids(stop):
    """The ids of [stop]'s own platforms, as TfL lists them under it, sorted. Only TfL's hierarchy
    says which station one belongs to: its id needn't share the station's code (St Pancras's
    "9100STPXBOX1" is under "910GSTPX", not "910GSTPXBOX")."""
    ids = {stop_id(child) for child in stop.get("children") or []}
    return sorted(i for i in ids if i.startswith(PLATFORM_PREFIXES))


def lines_by_mode(stop):
    """The lines serving [stop] per mode stopdash indexes, from TfL's lineModeGroups: a map of
    mode to sorted line ids, holding only modes with lines (empty for none). Buses are left out."""
    lines = {}
    for group in stop.get("lineModeGroups") or []:
        mode = group.get("modeName")
        if mode in MODES and group.get("lineIdentifier"):
            lines.setdefault(mode, set()).update(group["lineIdentifier"])
    return {mode: sorted(ids) for mode, ids in sorted(lines.items())}


# The modes whose lines the app offers farther stations for (FartherStations.MODES).
LINE_MODES = {"tube", "overground", "elizabeth-line", "national-rail", "dlr", "tram"}


def modes_without_lines(index):
    """The line-bearing modes the index has stations of but no station carries lines for: a
    listing that came back without its lineModeGroups. Sorted; empty when every mode has lines."""
    with_stations = {m for s in index["stations"] if not s["id"].upper().startswith("HUB") for m in s["modes"]}
    with_lines = {m for s in index["stations"] for m, ids in s.get("modeLines", {}).items() if ids}
    return sorted((with_stations & LINE_MODES) - with_lines)


def route_ends(sequences):
    """Station id -> the ends of the routes it lies on, from one line's route sequences (TfL
    `/Line/{id}/Route/Sequence/{direction}`): each ordered route's first and last stop are its
    ends, and every stop on it reaches both — but not itself: a terminus adds only the far end.
    Pure, for tests."""
    ends = {}
    for sequence in sequences:
        for route in (sequence or {}).get("orderedLineRoutes") or []:
            ids = route.get("naptanIds") or []
            if len(ids) < 2:
                continue
            for sid in ids:
                ends.setdefault(sid, set()).update(end for end in (ids[0], ids[-1]) if end != sid)
    return ends


def add_route_ends(index, ends_by_line):
    """[index] with each National Rail station's route ends per service it runs ("routeEnds"),
    from [ends_by_line] (line id -> station id -> ends). A service with no route data is left
    out, so the app counts it by line alone."""
    for station in index["stations"]:
        per_line = {}
        for line in station.get("modeLines", {}).get("national-rail", []):
            ends = ends_by_line.get(line, {}).get(station["id"])
            if ends:
                per_line[line] = sorted(ends)
        if per_line:
            station["routeEnds"] = per_line
    return index


def terminus_ends(sequences):
    """Station id -> the far ends of its routes, from one line's route sequences, for a station
    that ends every route it's on (a terminus): Walthamstow Central -> Brixton on the Victoria
    line. A station some route runs through is left out, a circular route's stops included: it
    names no far end, and a train runs on through every stop of it.
    Pure, for tests."""
    ends, through = {}, set()
    for sequence in sequences:
        for route in (sequence or {}).get("orderedLineRoutes") or []:
            ids = route.get("naptanIds") or []
            if len(ids) < 2:
                continue
            # A circular route's stops are all run through, its first among them, and it names no far end.
            if ids[0] == ids[-1]:
                through.update(ids[:-1])
                continue
            through.update(ids[1:-1])
            ends.setdefault(ids[0], set()).add(ids[-1])
            ends.setdefault(ids[-1], set()).add(ids[0])
    return {sid: far for sid, far in ends.items() if sid not in through}


def add_terminus_ends(index, ends_by_line):
    """[index] with each terminus's far ends per line ("terminusEnds"), from [ends_by_line] (line
    id -> station id -> far ends), for the [TERMINUS_MODES] lines it serves."""
    for station in index["stations"]:
        per_line = {}
        for mode in TERMINUS_MODES:
            for line in station.get("modeLines", {}).get(mode, []):
                ends = ends_by_line.get(line, {}).get(station["id"])
                if ends:
                    per_line[line] = sorted(ends)
        if per_line:
            station["terminusEnds"] = per_line
    return index


def fetch_route_ends(lines, ends=route_ends, cache=None):
    """Each line's route ends by station ([ends] of its sequences, fetch_sequences)."""
    return {line: ends(sequences) for line, sequences in fetch_sequences(lines, cache).items()}


def fetch_sequences(lines, cache=None):
    """Each line's route sequences, both directions, kept in [cache] so a line asked twice is
    fetched once. A line TfL has no route data for (404) has none: its stations fall back to
    counting the line whole."""
    cache = {} if cache is None else cache
    for line in sorted(set(lines) - set(cache)):
        sequences = []
        for direction in ("inbound", "outbound"):
            if not os.environ.get("TFL_APP_KEY"):
                time.sleep(ROUTE_REQUEST_GAP)
            try:
                sequences.append(fetch(f"/Line/{urllib.parse.quote(line)}/Route/Sequence/{direction}"))
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
                print(f"no {direction} route for {line}; counting it by line", file=sys.stderr)
        cache[line] = sequences
    return {line: cache[line] for line in lines}


def fetch_hubs(hub_ids):
    hubs = []
    ids = sorted(hub_ids)
    for start in range(0, len(ids), HUB_BATCH):
        batch = ids[start:start + HUB_BATCH]
        hubs.extend(checked_hub_batch(batch, fetch("/StopPoint/" + ",".join(batch))))
    return hubs


def checked_hub_batch(batch, found):
    """A hub batch's answer, refusing one missing a requested hub: its member stations would keep
    a reference to an interchange the index lacks, so a search shows them instead of it."""
    # One id answers with an object, several with a list.
    hubs = found if isinstance(found, list) else [found] if found else []
    missing = sorted(set(batch) - {stop_id(hub) for hub in hubs})
    if missing:
        sys.exit(f"TfL returned no hub for {', '.join(missing)}; refusing to write a partial index")
    return hubs


# The share of a listing's stations of its mode that must carry that mode's lines: nearly all do
# (a closed or brand-new station may not), and a listing stripped of its lineModeGroups has none.
MIN_LINED_SHARE = 0.9


def required_points(label, points, line_mode=None):
    """A required listing, refusing one with no stations: an empty answer (TfL's 200 with no
    stops) would otherwise drop the whole mode while the other modes keep the total up. For a
    [line_mode], also refusing one whose stations of that mode mostly lack its lines, checked on
    this listing alone so another mode's cross-listed station can't vouch for it."""
    points = points or []
    stations = station_points(points)
    if not stations:
        sys.exit(f"TfL listed no {label} stations; refusing to write an index without them")
    if line_mode in LINE_MODES:
        of_mode = [s for s in stations if line_mode in (s.get("modes") or [])]
        lined = [s for s in of_mode if lines_by_mode(s).get(line_mode)]
        if of_mode and len(lined) < MIN_LINED_SHARE * len(of_mode):
            sys.exit(f"TfL listed {len(of_mode)} {label} stations but only {len(lined)} with their lines; "
                     "refusing to write the index")
    return points


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="where to write station_index.json")
    args = parser.parse_args(argv)
    # One request per mode: an all-types listing spans the whole country and times out, while each
    # London mode's listing is TfL's own network and a fraction of the size. Every listing is
    # required: a failure stops the build and the committed index stays, rather than a partial
    # one replacing it.
    points = []
    for mode in sorted(MODES - {"national-rail"}):
        points.extend(required_points(mode, (fetch(f"/StopPoint/Mode/{mode}") or {}).get("stopPoints"), line_mode=mode))
    # National Rail's mode listing is nationwide and outlasts TfL's gateway (504); its stations
    # come from the rail-station type listing instead, which answers in time.
    points.extend(required_points("National Rail", fetch("/StopPoint/Type/NaptanRailStation"), line_mode="national-rail"))
    stops = station_points(points)
    # Only the hubs of stations the index can keep: a far-off rail station's hub isn't needed.
    london_lines = commuter_lines(stops)
    sequences = {}
    london_rail = {line for s in stops if in_london(s) for line in lines_by_mode(s).get("national-rail", [])}
    fetched = fetch_sequences(london_rail, sequences)
    # A line whose routes TfL wouldn't give (404) is left out of reach, so it counts whole.
    reach = checked_reach(london_reach({line: seqs for line, seqs in fetched.items() if seqs},
                                       {stop_id(s) for s in stops if in_london(s)}))
    hub_ids = {s.get("hubNaptanCode") for s in stops if s.get("hubNaptanCode") and in_catchment(s, london_lines, reach)}
    index = build_index(stops, fetch_hubs(hub_ids), reach)
    missing = modes_without_lines(index)
    if missing:
        # TfL answered without a mode's line groups: the index would name no nearest station of
        # that mode's lines, silently, so keep the committed index instead.
        sys.exit(f"no {', '.join(missing)} station carries its lines; refusing to write the index")
    rail_lines = {line for s in index["stations"] for line in s.get("modeLines", {}).get("national-rail", [])}
    add_route_ends(index, fetch_route_ends(rail_lines, cache=sequences))
    if rail_lines and not any(s.get("routeEnds") for s in index["stations"]):
        # Every route lookup came back empty: TfL answered oddly, so keep the committed index.
        sys.exit("no National Rail station carries route ends; refusing to write the index")
    terminus_lines = {line for s in index["stations"] for m in TERMINUS_MODES for line in s.get("modeLines", {}).get(m, [])}
    add_terminus_ends(index, fetch_route_ends(terminus_lines, ends=terminus_ends))
    if terminus_lines and not any(s.get("terminusEnds") for s in index["stations"]):
        # No terminus anywhere: TfL answered oddly, so keep the committed index.
        sys.exit("no station carries terminus ends; refusing to write the index")
    if len(index["stations"]) < 200:
        # A near-empty result means TfL answered oddly; keep the committed index rather than
        # shipping a list that can't find most stations.
        sys.exit(f"only {len(index['stations'])} stations; refusing to write a partial index")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as out:
        json.dump(index, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"wrote {len(index['stations'])} stations to {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
