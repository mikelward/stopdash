#!/usr/bin/env python3
"""Read where the ways off London Underground platforms meet them, from OpenStreetMap, for review.

Where to stand (SPEC *Where to stand*) is read by hand into `PlatformEnds.TABLE`, so far from TfL's 2015
layout drawings, which cover the stations below ground. The stations above ground have no drawing;
OpenStreetMap maps their platforms, and the stairs, footbridges, escalators and lifts that join them.
This prints, for each platform mapped near a tube station, where along it each of those joins it, as
a fraction of its length, and the two ends' positions, so a person can tie each end to the station
before and after on the line and read it into the table, as with the drawings. It writes nothing the
app reads: the table stays read by hand.

Each output line, tab-separated:

    station_id  station_name  way_id  ref  level  length_m  end1_lat,end1_lon  end2_lat,end2_lon  joins

- joins: each way off as `kind@fraction` (0 at end1, 1 at end2), kind being the OpenStreetMap feature
  (`steps`, `escalator`, `footway`, `corridor`, `elevator`, `entrance`), comma-separated.

Source: OpenStreetMap (ODbL), Geofabrik's Greater London extract, checked against Geofabrik's MD5 sum.
Stations: the bundled stops (`app/src/main/assets/stations/stops.tsv`). Needs `osmium` (pyosmium).
Public data only, nothing about any user.
"""
import argparse
import math
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# How near a tube station's point a platform has to be to count as its.
NEAR_METERS = 300.0
# How near a station a connecting way has to start to be kept: its nodes are held in memory.
CONNECTOR_METERS = 500.0
CONNECTOR_KINDS = {"steps", "footway", "corridor", "elevator", "path", "pedestrian"}


def meters(a, b):
    """The distance in meters between two (lat, lon) points, flat-earth: platforms are short."""
    lat0 = math.radians((a[0] + b[0]) / 2)
    dx = (b[1] - a[1]) * 111320.0 * math.cos(lat0)
    dy = (b[0] - a[0]) * 110540.0
    return math.hypot(dx, dy)


def axis(points):
    """The two points of [points] farthest apart: a platform's ends, whether it's mapped as a line or an area."""
    best, pair = -1.0, (points[0], points[-1])
    for i, p in enumerate(points):
        for q in points[i + 1:]:
            d = meters(p, q)
            if d > best:
                best, pair = d, (p, q)
    return pair


def fraction(point, ends):
    """Where [point] lies along the line between [ends], 0 at the first and 1 at the second, clamped."""
    a, b = ends
    lat0 = math.radians(a[0])
    ax, ay = 0.0, 0.0
    bx, by = (b[1] - a[1]) * 111320.0 * math.cos(lat0), (b[0] - a[0]) * 110540.0
    px, py = (point[1] - a[1]) * 111320.0 * math.cos(lat0), (point[0] - a[0]) * 110540.0
    length2 = bx * bx + by * by
    if length2 == 0:
        return 0.5
    t = ((px - ax) * bx + (py - ay) * by) / length2
    return min(1.0, max(0.0, t))


def connector_kind(tags):
    """What kind of way off a way tagged [tags] is, or None: an escalator is steps that move."""
    highway = tags.get("highway")
    if highway == "steps":
        return "escalator" if tags.get("conveying") in ("yes", "forward", "backward", "reversible") else "steps"
    if highway in CONNECTOR_KINDS:
        return highway
    return None


def is_platform(tags):
    if tags.get("railway") == "platform":
        return True
    return tags.get("public_transport") == "platform" and any(tags.get(k) == "yes" for k in ("subway", "train", "light_rail"))


def read_stations(path):
    """Each tube station in the bundled stops: (id, name, (lat, lon))."""
    stations = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            cols = line.rstrip("\n").split("\t")
            if len(cols) > 5 and cols[0] == "S" and cols[1].startswith("940GZZLU"):
                stations.append((cols[1], cols[3], (float(cols[4]), float(cols[5]))))
    return stations


def nearest(stations, point, within):
    best = None
    for sid, name, at in stations:
        d = meters(point, at)
        if d <= within and (best is None or d < best[0]):
            best = (d, sid, name)
    return best


def read_osm(path, stations):
    """(platforms, connector nodes, entrance nodes) from the extract at [path], near [stations]."""
    import osmium

    platforms, connectors, entrances = [], {}, set()

    class Handler(osmium.SimpleHandler):
        def node(self, n):
            tags = dict(n.tags)
            if tags.get("highway") == "elevator":
                connectors.setdefault(n.id, set()).add("elevator")
            if tags.get("railway") in ("subway_entrance", "train_station_entrance") or "entrance" in tags:
                entrances.add(n.id)

        def way(self, w):
            tags = dict(w.tags)
            try:
                nodes = [(nd.ref, (nd.location.lat, nd.location.lon)) for nd in w.nodes]
            except osmium.InvalidLocationError:
                return
            if not nodes:
                return
            if is_platform(tags):
                station = nearest(stations, nodes[0][1], NEAR_METERS)
                if station:
                    platforms.append((station, w.id, tags, nodes))
                return
            kind = connector_kind(tags)
            if kind and nearest(stations, nodes[0][1], CONNECTOR_METERS):
                for ref, _ in nodes:
                    connectors.setdefault(ref, set()).add(kind)

    Handler().apply_file(path, locations=True)
    return platforms, connectors, entrances


def rows(platforms, connectors, entrances):
    """The output lines, sorted by station then way."""
    out = []
    for (_, sid, name), way_id, tags, nodes in platforms:
        points = [p for _, p in nodes]
        ends = axis(points)
        joins = set()
        for ref, p in nodes:
            for kind in connectors.get(ref, ()):
                joins.add((round(fraction(p, ends), 2), kind))
            if ref in entrances:
                joins.add((round(fraction(p, ends), 2), "entrance"))
        out.append("\t".join([
            sid, name, str(way_id), tags.get("ref", tags.get("local_ref", "")), tags.get("level", ""),
            str(round(meters(*ends))),
            f"{ends[0][0]:.5f},{ends[0][1]:.5f}", f"{ends[1][0]:.5f},{ends[1][1]:.5f}",
            ",".join(f"{kind}@{f:.2f}" for f, kind in sorted(joins)),
        ]))
    return sorted(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--stops", default="app/src/main/assets/stations/stops.tsv")
    parser.add_argument("--pbf", help="a Greater London extract already downloaded; else Geofabrik's is fetched")
    parser.add_argument("--out", default="build/platforms_osm.tsv")
    args = parser.parse_args()
    stations = read_stations(args.stops)
    if len(stations) < 250:
        raise SystemExit(f"only {len(stations)} tube stations in {args.stops}; not reading")
    pbf = args.pbf
    if not pbf:
        from build_place_index import GEOFABRIK, fetch_extract
        pbf = os.path.join(tempfile.mkdtemp(), "greater-london.osm.pbf")
        fetch_extract(f"{GEOFABRIK}/greater-london-latest.osm.pbf", pbf)
    lines = rows(*read_osm(pbf, stations))
    if len(lines) < 200:
        raise SystemExit(f"only {len(lines)} platforms read; not writing")
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Platforms near tube stations, from OpenStreetMap (ODbL), for reading into PlatformEnds by hand\n")
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} platforms at {len({l.split(chr(9))[0] for l in lines})} stations")


if __name__ == "__main__":
    main()
