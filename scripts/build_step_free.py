#!/usr/bin/env python3
"""Build LDN Go's bundled step-free access table from TfL's station data (SPEC *Step-free*).

Writes the JSON the app reads as `assets/stations/step_free.json`: for each station platform
TfL describes, by stop (its NaPTAN stop area, the id the app's departures carry) and line, how far
a rider can get without a step:

- `none`: no step-free route from the street to the platform;
- `platform`: step-free from the street to the platform, with a step or gap onto the train;
- `ramp`: as `platform`, with staff putting down a ramp onto the train;
- `level`: level onto the train as well, at least at a marked spot (`where`, when TfL names it).

TfL's live StopPoint data doesn't carry this (`accessibilitySummary` is empty, and its
"access via lift" flag is per station, missing at many). Its open station data does, as
a walking map of each station: the areas, the paths between them on one level, its ramps and
its lifts, joined to the street (`<station>-Outside`) and to each platform. A platform is
step-free from the street when that map reaches it from outside; the gap and step onto the train
come per platform and line (TfL counts 85 mm across and 50 mm up as level). A route that needs a
limited-capacity lift, one not every wheelchair fits, is marked `limitedLift`; a platform whose
step-free route needs a particular entrance names it (`entrance`). A platform TfL has no route
information for is left out, so the app shows nothing rather than guessing.

A platform the street reaches only by lift also names its station (`station`) and its place in
that station's lift map (`node`), kept under `stations`: the map cut down to the places that
matter (node 0 the street, the platforms, the lifts' stops), the walks between them (`walks`, one
way each), and each lift by the id TfL's live lift disruptions name it by (`lifts`). The app walks
that map again without the lifts TfL says are out, so a mark drops while one its route needs is out.

A failed download, or a table that's short (under MIN_STOPS stops) or missing a line it always
has, stops the build, so the committed table stays rather than a partial one replacing it. Run
by the `station-index` workflows beside the station index; the sandboxed dev environment may not
reach TfL. One keyless request. Public transport data only — nothing about any user.
"""
import argparse
import collections
import csv
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile

URL = "https://api.tfl.gov.uk/stationdata/tfl-stationdata-detailed.zip"
FORMAT_VERSION = 1
# The 2026 feed describes ~560 stops; far fewer means TfL answered oddly.
MIN_STOPS = 300
# Lines the feed has always described: one missing means a partial feed.
REQUIRED_LINES = {"victoria", "jubilee", "elizabeth", "dlr", "national-rail"}
# TfL's level boarding: a gap no wider than 85 mm and a step no higher than 50 mm.
LEVEL_GAP_MM = 85
LEVEL_STEP_MM = 50
RETRY_DELAYS = (5, 15, 45)


def fetch(url=URL, retry_delays=RETRY_DELAYS, sleep=time.sleep):
    """GET the station data zip as bytes, retrying a timeout or a 5xx."""
    request = urllib.request.Request(url, headers={"User-Agent": "stopdash-step-free"})
    for attempt, delay in enumerate((0,) + tuple(retry_delays)):
        if delay:
            print(f"retrying TfL station data in {delay}s", file=sys.stderr)
            sleep(delay)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == len(retry_delays):
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == len(retry_delays):
                raise


def read_feed(zip_bytes):
    """Each CSV in the zip, by name without `.csv`, as a list of rows (a dict per row)."""
    feed = {}
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        for name in archive.namelist():
            if name.lower().endswith(".csv"):
                text = archive.read(name).decode("utf-8-sig")
                feed[os.path.splitext(os.path.basename(name))[0]] = list(csv.DictReader(io.StringIO(text)))
    return feed


def flag(value):
    """TfL's TRUE/True/FALSE columns, some with stray spaces."""
    return (value or "").strip().upper() == "TRUE"


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def lift_stops(lift):
    """The areas [lift] stops at, in the order TfL lists them."""
    return [area.strip() for column in ("FromAreas", "IntermediateAreas", "IntermediateAreas2", "ToAreas")
            for area in (lift.get(column) or "").split("|") if area.strip()]


def walks_of(feed):
    """The step-free paths that aren't lifts: on one level, and ramps, one way each as TfL lists them."""
    paths = collections.defaultdict(set)
    for row in feed.get("SameLevelPaths", []) + feed.get("RampRoutes", []):
        paths[row["From"]].add(row["To"])
    return paths


def joined(paths, lifts):
    """[paths] with each of [lifts] joining every area it stops at."""
    out = collections.defaultdict(set, {a: set(b) for a, b in paths.items()})
    for lift in lifts:
        stops = lift_stops(lift)
        for a in stops:
            out[a].update(b for b in stops if b != a)
    return out


def walking_map(feed, limited_lifts=True):
    """Which areas (and platforms) a rider can move between without a step: paths on one level,
    ramps, and lifts (each lift joins every area it stops at). [limited_lifts] False leaves out
    the lifts not every wheelchair fits."""
    lifts = [lift for lift in feed.get("Lifts", []) if limited_lifts or not flag(lift.get("LimitedCapacityLift"))]
    return joined(walks_of(feed), lifts)


def reachable(paths, start):
    seen = {start}
    queue = [start]
    while queue:
        for nxt in paths.get(queue.pop(), ()):
            if nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return seen


def lift_graph(walks, lifts, outside, targets):
    """A station's lift map, cut down to what the app needs to walk it again without the lifts that
    are out: the street ([outside]), each of [targets] (the platforms only lifts reach) and each
    lift's stops, as nodes; the walks between them that need no lift; and the lifts by id. Places
    that reach each other without a lift share a node, so it stays small. Returns the map and each
    target's node. A lift TfL gives no id can't be reported out, so it joins its stops as a walk."""
    lifts_by_id = {}
    local = collections.defaultdict(set, {a: set(b) for a, b in walks.items()})
    for lift in lifts:
        stops = lift_stops(lift)
        lift_id = (lift.get("LiftUniqueId") or "").strip()
        if lift_id:
            lifts_by_id[lift_id] = stops
        else:
            for a in stops:
                local[a].update(b for b in stops if b != a)
    places = [outside] + sorted(set(targets)) + sorted({a for stops in lifts_by_id.values() for a in stops})
    places = list(dict.fromkeys(places))
    reach = {place: reachable(local, place) for place in places}
    # Places that walk to each other both ways are one node; the street is node 0.
    groups = []
    for place in places:
        for group in groups:
            if place in reach[group[0]] and group[0] in reach[place]:
                group.append(place)
                break
        else:
            groups.append([place])
    groups = [groups[0]] + sorted(groups[1:], key=min)
    node = {place: i for i, group in enumerate(groups) for place in group}
    walk_edges = sorted({(node[a], node[b]) for a in places for b in places
                         if node[a] != node[b] and b in reach[a]})
    graph_lifts = {}
    for lift_id, stops in sorted(lifts_by_id.items()):
        nodes = sorted({node[a] for a in stops})
        if len(nodes) > 1:
            graph_lifts[lift_id] = nodes
    return {"walks": [list(e) for e in walk_edges], "lifts": graph_lifts}, {t: node[t] for t in targets}


def reaches(graph, node, out=()):
    """Whether [graph]'s street (node 0) reaches [node] without the lifts in [out]."""
    edges = collections.defaultdict(set)
    for a, b in graph["walks"]:
        edges[a].add(b)
    for lift_id, nodes in graph["lifts"].items():
        if lift_id not in out:
            for a in nodes:
                edges[a].update(b for b in nodes if b != a)
    return node in reachable(edges, 0)


def boarding(service):
    """How a rider gets onto the train from the platform: `level` (at a marked spot, or within
    TfL's level gap and step), `ramp` (staff put one down), else `platform` (a step or gap)."""
    gap, step = number(service.get("MaxGap")), number(service.get("MaxStep"))
    if flag(service.get("DesignatedLevelAccessPoint")) or (
        gap is not None and step is not None and gap <= LEVEL_GAP_MM and step <= LEVEL_STEP_MM
    ):
        return "level"
    if flag(service.get("LevelAccessByManualRamp")):
        return "ramp"
    return "platform"


def platform_sort_key(entry):
    """Platforms in number order (2 before 10, 3 before 3A), then by direction."""
    number_part = entry.get("platform", "")
    match = re.match(r"(\d+)(.*)", number_part)
    return (int(match.group(1)) if match else 10**6, match.group(2) if match else number_part, entry.get("direction", ""))


def build(feed):
    platforms = {p["UniqueId"]: p for p in feed.get("Platforms", [])}
    stations = {s["UniqueId"]: s for s in feed.get("Stations", [])}
    walks = walks_of(feed)
    all_lifts = feed.get("Lifts", [])
    paths, full = joined(walks, all_lifts), walking_map(feed, limited_lifts=False)
    reach, reach_unlimited, reach_walking = {}, {}, {}
    stops = collections.defaultdict(lambda: collections.defaultdict(list))
    # The platforms only a lift reaches, by station: those a lift outage can cut off.
    by_lift = collections.defaultdict(list)
    for service in feed.get("PlatformServices", []):
        platform = platforms.get(service["PlatformUniqueId"])
        stop, line = (service.get("StopAreaNaptanCode") or "").strip(), (service.get("Line") or "").strip()
        if not platform or not stop or not line:
            continue
        # A platform TfL gives no step-free route information for is left out, not called "none".
        if not flag(platform.get("IsCustomerFacing")) or not flag(platform.get("HasStepFreeRouteInformation")):
            continue
        station = stations.get(platform["StationUniqueId"])
        if station is None:
            continue
        sid = station["UniqueId"]
        outside = station.get("OutsideStationUniqueId") or f"{sid}-Outside"
        if sid not in reach:
            reach[sid] = reachable(paths, outside)
            reach_unlimited[sid] = reachable(full, outside)
            reach_walking[sid] = reachable(walks, outside)
        entry = {}
        if platform.get("PlatformNumber", "").strip():
            entry["platform"] = platform["PlatformNumber"].strip()
        if platform.get("CardinalDirection", "").strip():
            entry["direction"] = platform["CardinalDirection"].strip()
        if platform["UniqueId"] in reach[sid]:
            entry["level"] = boarding(service)
            if entry["level"] == "level" and (service.get("LocationOfLevelAccess") or "").strip():
                entry["where"] = service["LocationOfLevelAccess"].strip().rstrip(".")
            if platform["UniqueId"] not in reach_unlimited[sid]:
                entry["limitedLift"] = True
            if (platform.get("AccessibleEntranceName") or "").strip():
                entry["entrance"] = platform["AccessibleEntranceName"].strip()
            if platform["UniqueId"] not in reach_walking[sid]:
                by_lift[(sid, outside)].append((platform["UniqueId"], entry))
        else:
            entry["level"] = "none"
        stops[stop][line].append(entry)
    graphs = {}
    for (sid, outside), entries in sorted(by_lift.items()):
        area = reach[sid]
        lifts = [lift for lift in all_lifts if any(a in area for a in lift_stops(lift))]
        graph, nodes = lift_graph(walks, lifts, outside, [uid for uid, _ in entries])
        for uid, entry in entries:
            if not reaches(graph, nodes[uid]):
                raise SystemExit(f"lift map for {sid} doesn't reach {uid}; not writing")
            # On the street's own node, only lifts that can't be reported out lead there: never cut off.
            if nodes[uid] != 0:
                entry["station"], entry["node"] = sid, nodes[uid]
                graphs[sid] = graph
    if len(stops) < MIN_STOPS:
        raise SystemExit(f"only {len(stops)} stops (want at least {MIN_STOPS}); not writing")
    missing = REQUIRED_LINES - {line for lines in stops.values() for line in lines}
    if missing:
        raise SystemExit(f"no platforms for {', '.join(sorted(missing))}; not writing")
    published = ((feed.get("FeedInfo") or [{}])[0].get("FeedStartDate") or "")[:10]
    return {
        "version": FORMAT_VERSION,
        "source": "Transport for London station data (Powered by TfL Open Data)",
        "published": published,
        "stops": {
            stop: {line: sorted(entries, key=platform_sort_key) for line, entries in sorted(lines.items())}
            for stop, lines in sorted(stops.items())
        },
        "stations": graphs,
    }


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True, help="where to write step_free.json")
    parser.add_argument("--zip", help="read TfL's station data zip from this file instead of downloading it")
    args = parser.parse_args(argv)
    if args.zip:
        with open(args.zip, "rb") as f:
            data = f.read()
    else:
        data = fetch()
    table = build(read_feed(data))
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as out:
        json.dump(table, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"wrote step-free access for {len(table['stops'])} stops to {args.out}")


if __name__ == "__main__":
    main(sys.argv[1:])
