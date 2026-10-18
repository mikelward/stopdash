#!/usr/bin/env python3
"""The station model's membership for stops.tsv (dev-docs/station-model.md *The model*).

Works out, from the bundle's stops and the station index's stations, each bus pole's stop (both
directions of its road), each stop's owning station, and each stop's hub, plus the station and
hub of the station records stops.tsv holds that station_index.json leaves out. Run by
build_stops.py so the rules live in one place and the device only reads the answers.

Pure: no network, no files. The inputs are build_stops.build's stop dicts (each with an "ics"
field) and the station index document build_station_index.py writes.
"""
import math
import re
import unicodedata

# How far apart two poles of one stop, or two stops of one junction, may stand (*One grouping rule*):
# every pole of a group within this of every other.
GROUP_METERS = 250.0
# How far from a station a bus stop named for it may stand and still be its own (owner rule 3).
NAMED_REACH_METERS = 250.0
# How far from a station a bus stop of any name may stand and still be its own (owner rule 4;
# maintainer, 2026-10-16).
OWN_STOP_METERS = 200.0
# The prefixes of the station records stops.tsv holds that station_index.json leaves out, and the
# family each groups by. The Ruislip Lido Railway's miniature-railway records group with nothing.
OMITTED_FAMILIES = (("910G", "rail"), ("930G", "pier"), ("940GZZRL", None))


# --- Names, compared as the app compares them (StopName.kt matchStopName, StationMatcher.normalize).

_SAINT_DOT_GLUED = re.compile(r"\b(St)\.(?=[^\W\d_])", re.IGNORECASE)
_SAINT_DOT = re.compile(r"\b(St)\.", re.IGNORECASE)
_CROSS_STREET = re.compile(r"\s*/\s*")
_SUFFIXES = (" (Rail Station Only)", " Underground Station", " DLR Station", " Rail Station",
             " Overground Station", " Bus Station", " Coach Station", "-Underground", " Station")
_LINE_PARENTHETICAL = re.compile(r"\s*\(([^()]*?)\s*\bLines?\)\s*$", re.IGNORECASE)
_LINE = r"(?:H&C|Hammersmith\s*&\s*City|Dist&Picc|District|Piccadilly|Circle|Metropolitan|Met)"
_SHORT_LINE_QUALIFIER = re.compile(rf"\s*\({_LINE}(?:\s*(?:,|&|\band\b)\s*{_LINE})*\)\s*$", re.IGNORECASE)
_PUNCTUATION = re.compile(r"[^\w ]|_")


def _clean_part(part):
    name = part.strip()
    for suffix in _SUFFIXES:
        if len(name) > len(suffix) and name.lower().endswith(suffix.lower()):
            name = name[:-len(suffix)].strip()
            break
    for pattern in (_LINE_PARENTHETICAL, _SHORT_LINE_QUALIFIER):
        match = pattern.search(name)
        if match and name[:match.start()].strip():
            name = name[:match.start()].strip()
    return name


def _normalize(text):
    text = "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")
    return re.sub(" +", " ", _PUNCTUATION.sub("", text)).strip().lower()


def name_parts(raw):
    """[raw]'s places, each in the form names are compared by: TfL's suffixes and line qualifiers
    gone, case, accents and punctuation aside ("Hill Station / High Road" -> ("hill", "high
    road")). Empty parts are dropped."""
    name = _SAINT_DOT.sub(r"\1", _SAINT_DOT_GLUED.sub(r"\1 ", raw.strip()))
    name = re.sub(r"\s+", " ", name).strip()
    parts = (_normalize(_clean_part(p)) for p in _CROSS_STREET.split(name))
    return tuple(p for p in parts if p)


def match_name(raw):
    """[raw] as one compared name: its parts, joined."""
    return "/".join(name_parts(raw))


# --- Distances.

def meters(a, b):
    """The great-circle distance between two (lat, lon) points, as NearestStops measures it."""
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlat, dlon = lat2 - lat1, math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6371008.8 * 2 * math.asin(min(1.0, math.sqrt(h)))


def nearest(points_a, points_b):
    return min(meters(a, b) for a in points_a for b in points_b)


class _Grid:
    """Points bucketed by about [cell] meters, for finding what stands near a point."""

    def __init__(self, cell=GROUP_METERS):
        self.dlat = cell / 111_320.0
        self.dlon = cell / (111_320.0 * math.cos(math.radians(51.5)))
        self.cells = {}

    def key(self, point):
        return int(point[0] // self.dlat), int(point[1] // self.dlon)

    def add(self, point, item):
        self.cells.setdefault(self.key(point), []).append(item)

    def near(self, points, reach=1):
        """Every item in a cell within [reach] cells of any of [points], once each."""
        seen, found = set(), []
        for point in points:
            y, x = self.key(point)
            for dy in range(-reach, reach + 1):
                for dx in range(-reach, reach + 1):
                    for item in self.cells.get((y + dy, x + dx), ()):
                        if id(item) not in seen:
                            seen.add(id(item))
                            found.append(item)
        return found


# --- One grouping rule (*One grouping rule, for every grouping*).

class Member:
    """Something grouped: whole poles, with their positions, the names a test reads, and an id."""

    def __init__(self, poles, names, key=None):
        self.poles = sorted(poles, key=lambda p: p["id"])
        self.names = frozenset(names)
        self.key = key
        self.points = [(p["lat"], p["lon"]) for p in self.poles]

    @property
    def first(self):
        return self.poles[0]["id"]


class Group:
    def __init__(self, member):
        self.members = [member]
        self.anchor = member.poles[0]
        self.anchor_point = (self.anchor["lat"], self.anchor["lon"])
        self.points = list(member.points)

    def add(self, member):
        self.members.append(member)
        self.points.extend(member.points)

    def fits(self, member):
        """Whether every pole of [member] stands within GROUP_METERS of every pole already here."""
        return all(meters(a, b) <= GROUP_METERS for a in member.points for b in self.points)

    def reach(self, member):
        return min(meters(self.anchor_point, p) for p in member.points)


def group(members, keys_of, groups=None, index=None):
    """[members] grouped by the one rule: taken in order of their lowest-id pole, each joins the
    nearest group (to its anchor, ties to the lower anchor id) whose key it shares ([keys_of]
    gives a member's keys) and whose every pole is within GROUP_METERS of its every pole, else
    starts one. [groups] and [index] (key -> groups) carry groups between calls."""
    groups = [] if groups is None else groups
    index = {} if index is None else index
    for member in sorted(members, key=lambda m: m.first):
        keys = keys_of(member)
        candidates = {id(g): g for k in keys for g in index.get(k, ())}.values()
        fitting = [g for g in candidates if g.fits(member)]
        if fitting:
            best = min(fitting, key=lambda g: (g.reach(member), g.anchor["id"]))
            best.add(member)
        else:
            best = Group(member)
            groups.append(best)
        for k in keys:
            if best not in index.setdefault(k, []):
                index[k].append(best)
    return groups


# --- The stations.

class Station:
    def __init__(self, key, hub):
        self.key = key
        self.hub = hub
        self.records = []

    @property
    def points(self):
        return [(r["lat"], r["lon"]) for r in self.records]


def omitted_family(sid):
    """The family an omitted station record of stops.tsv groups by, None for one that groups with
    nothing; refusing a prefix none is listed for, so a new one is classified by hand."""
    for prefix, family in OMITTED_FAMILIES:
        if sid.startswith(prefix):
            return family
    raise SystemExit(f"{sid} is a station record of a prefix stops.tsv's membership can't group; "
                     "classify it in OMITTED_FAMILIES")


def _majority_hub(records, fallback):
    named = {}
    for r in records:
        if r.get("hub"):
            named[r["hub"]] = named.get(r["hub"], 0) + 1
    return min(named, key=lambda h: (-named[h], h)) if named else fallback


def stations_of(index, stops):
    """The model's stations: station_index.json's, keyed as it keys them, plus stops.tsv's station
    records it leaves out (the M, R and F rows not all buses), each joining the index's station of
    its ICS code and family, else a station of its own. Returns (stations by key, the station key
    of every station record, the omitted records' membership by id)."""
    stations, record_station = {}, {}
    for entry in index["stations"]:
        if entry["id"].upper().startswith("HUB"):
            continue
        if "station" not in entry or "modelHub" not in entry:
            raise SystemExit(f"{entry['id']} has no station or hub in the station index; rebuild it first")
        station = stations.setdefault(entry["station"], Station(entry["station"], entry["modelHub"]))
        station.records.append(entry)
        record_station[entry["id"]] = entry["station"]
    omitted = {}
    pending = {}
    for stop in stops:
        if stop["type"] == "B" or stop["id"] in record_station or all_bus(stop):
            continue
        family = omitted_family(stop["id"])
        key = f"{stop['ics']}:{family}" if stop.get("ics") and family else stop["id"]
        pending.setdefault(key, []).append(stop)
    for key, records in pending.items():
        if key not in stations:
            stations[key] = Station(key, _majority_hub(records, key))
        for stop in records:
            stations[key].records.append(stop)
            record_station[stop["id"]] = key
            omitted[stop["id"]] = {"ics": stop.get("ics", ""), "station": key, "hub": stations[key].hub}
    return stations, record_station, omitted


def all_bus(stop):
    """Whether a station-typed row only buses serve: a stop area TfL types as a station."""
    return bool(stop["lines"]) and all(mode == "bus" for _, mode, _ in stop["lines"])


# --- Poles into stops, stops into junctions, junctions to owners.

def stops_of(poles, record_station):
    """Each pole into its stop. A TfL stop area (not a station record) is one member when its poles
    share a name and stand within GROUP_METERS of each other, else each pole is one."""
    areas, members = {}, []
    for pole in poles:
        area = pole["cluster"]
        if area and area not in record_station and area != pole["id"]:
            areas.setdefault(area, []).append(pole)
        else:
            members.append(Member([pole], {match_name(pole["name"])}))
    for area, area_poles in areas.items():
        whole = Member(area_poles, {match_name(p["name"]) for p in area_poles}, key=area)
        if len(whole.names) == 1 and all(meters(a, b) <= GROUP_METERS for a in whole.points for b in whole.points):
            members.append(whole)
        else:
            members.extend(Member([p], {match_name(p["name"])}) for p in area_poles)
    groups = group([m for m in members if any(m.names)], lambda m: m.names)
    groups += [Group(m) for m in members if not any(m.names)]
    for g in groups:
        g.id = g.members[0].key if len(g.members) == 1 and g.members[0].key else g.anchor["id"]
    return groups


def junctions_of(stops):
    """Stops into junctions (*Junctions*): stops of two or more name parts first, by the same parts
    in either order; then each one-part stop joins the nearest of those whose parts hold its name,
    else a group of one-part stops of its name."""
    def as_member(stop):
        member = Member([p for m in stop.members for p in m.poles],
                        {frozenset(name_parts(n)) for n in _names(stop)}, key=stop)
        member.parts = {p for n in member.names for p in n}
        return member

    members = [as_member(s) for s in stops]
    many = [m for m in members if any(len(n) > 1 for n in m.names)]
    one = [m for m in members if m not in many]
    groups, index = [], {}
    group(many, lambda m: {n for n in m.names if len(n) > 1}, groups, index)
    by_part = {}
    for g in groups:
        for m in g.members:
            for part in m.parts:
                by_part.setdefault(part, []).append(g)
    singles, single_index = [], {}
    for member in sorted(one, key=lambda m: m.first):
        candidates = {id(g): g for part in member.parts for g in by_part.get(part, ())}.values()
        fitting = [g for g in candidates if g.fits(member)]
        if fitting:
            min(fitting, key=lambda g: (g.reach(member), g.anchor["id"])).add(member)
        else:
            group([member], lambda m: {n for n in m.names}, singles, single_index)
    junctions = groups + singles
    for j in junctions:
        j.stops = [m.key for m in j.members]
    return junctions


def _names(stop):
    return {p["name"] for m in stop.members for p in m.poles}


def owner_of(junction, stations, record_station, by_hub, by_ics, grid):
    """The station that owns [junction]'s stops, or None (*Owning a bus stop*). Every rule reads
    every pole of every stop in it; within each, the nearest station wins, ties to the lower id."""
    poles = [p for s in junction.stops for m in s.members for p in m.poles]
    points = [(p["lat"], p["lon"]) for p in poles]

    def distance(station):
        return nearest(station.points, points)

    def closest(candidates):
        return min(candidates, key=lambda st: (distance(st), st.key)) if candidates else None

    records = {p["cluster"] for p in poles if p.get("cluster") in record_station}
    # Rule 1: the interchange any pole, or any station record a pole is filed under, names.
    interchanges = {p["hub"] for p in poles if p.get("hub", "").upper().startswith("HUB")}
    for station in {record_station[r] for r in records}:
        interchanges |= {r["hub"] for r in stations[station].records
                         if r["id"] in records and r.get("hub", "").upper().startswith("HUB")}
    pool = {st.key: st for h in interchanges for st in by_hub.get(h, ())}
    within = bool(pool)

    def allowed(candidates):
        return [st for st in candidates if not within or st.key in pool]

    # Rule 2: the station whose record a pole is filed under, else whose ICS code a pole carries.
    filed = allowed({stations[record_station[r]] for r in records})
    if not filed:
        codes = {p["ics"] for p in poles if p.get("ics")}
        filed = allowed({st for code in codes for st in by_ics.get(code, ())})
    if filed:
        return closest(filed)
    near = list(pool.values()) if within else grid.near(points, reach=2)
    # Rule 3: a station a pole is named for, on either side of a slash, within NAMED_REACH_METERS.
    parts = {part for p in poles for part in name_parts(p["name"])}
    named = [st for st in near if any(match_name(r["name"]) in parts for r in st.records)
             and distance(st) <= NAMED_REACH_METERS]
    if named:
        return closest(named)
    # Rule 4: the nearest station, within OWN_STOP_METERS unless the interchange already decided.
    return closest(near if within else [st for st in near if distance(st) <= OWN_STOP_METERS])


def membership(stops, index):
    """Every row's membership columns, by stop id: (icsCode, stop or station, owning station, hub).
    A bus pole carries all four (owner blank where no station owns it); a station record
    station_index.json leaves out carries its ICS code, station and hub; every other row none.
    Refuses a bus stop or station that comes out with no hub."""
    stations, record_station, omitted = stations_of(index, stops)
    by_hub, by_ics, grid = {}, {}, _Grid()
    for station in stations.values():
        by_hub.setdefault(station.hub, []).append(station)
        for record in station.records:
            code = record.get("icsCode") or record.get("ics")
            if code and station not in by_ics.get(code, []):
                by_ics.setdefault(code, []).append(station)
        for point in station.points:
            grid.add(point, station)
    poles = [s for s in stops if s["type"] == "B"]
    stops_ = stops_of(poles, record_station)
    columns = {}
    for junction in junctions_of(stops_):
        owner = owner_of(junction, stations, record_station, by_hub, by_ics, grid)
        hub = owner.hub if owner else junction.stops[0].id
        for stop in junction.stops:
            for member in stop.members:
                for pole in member.poles:
                    columns[pole["id"]] = (pole.get("ics", ""), stop.id, owner.key if owner else "", hub)
    for sid, entry in omitted.items():
        columns[sid] = (entry["ics"], entry["station"], "", entry["hub"])
    for sid, (_, level, _, hub) in columns.items():
        if not level or not hub:
            raise SystemExit(f"{sid} came out with no stop or hub; refusing to write the list")
    return columns

