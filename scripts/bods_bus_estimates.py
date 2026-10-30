#!/usr/bin/env python3
"""Record BODS bus positions in a UK city, and score how well stopdash could estimate bus times.

Outside London no open feed gives bus countdowns: the Bus Open Data Service (BODS) has timetables
and live positions only, so stopdash would estimate them (dev-docs/multi-city-gtfs.md *Estimating
UK bus times from BODS*). This tool measures that estimate against what then happened:

    record   poll BODS's GTFS-RT vehicle positions for a city's box every --interval seconds for
             --minutes, saving each answer as rt-<epoch seconds>.pb in --out;
    score    from a recording and BODS's GTFS timetable for the region (the zip from
             https://data.bus-data.dft.gov.uk/timetable/download/gtfs-file/<region>/), print how
             often the estimate, and the timetable alone, landed in California's accuracy band.

The estimate: at each poll, each bus with a trip id is placed along its trip's stops (straight
lines between them), its lateness is how far it is behind or ahead of its timetable there, and its
time at each stop ahead is the timetabled time plus that lateness, never earlier than now. What
happened: the time the bus passed that stop, between the two positions either side of it, when
they are at most MAX_GAP apart. Passing counts as arriving; dwell is ignored. A position older than
FRESH at its poll is dropped, as an app would show no time for it.

Recordings hold buses' positions, nothing about any user. `record` needs BODS_API_KEY (free from
BODS), which never reaches a log or the recording; `score` needs no key and no network.
"""
import argparse
import collections
import csv
import datetime
import io
import json
import math
import os
import re
import struct
import sys
import time
import traceback
import urllib.parse
import urllib.request
import zipfile
import zoneinfo

FEED = "https://data.bus-data.dft.gov.uk/api/v1/gtfsrtdatafeed/"
# Each city's centre as west,south,east,north, and its BODS timetable region.
CITIES = {
    "manchester": ("-2.35,53.40,-2.15,53.55", "north_west"),
    "liverpool": ("-3.05,53.35,-2.85,53.48", "north_west"),
    "bristol": ("-2.70,51.40,-2.50,51.52", "south_west"),
}
LONDON = zoneinfo.ZoneInfo("Europe/London")
FRESH = 120        # seconds: an older position at its poll is dropped
MAX_GAP = 120      # seconds: the most between two positions a passing time is placed between
MAX_OFF = 150      # meters off the trip's line before a position is ignored
BACKTRACK = 30     # meters a position may fall behind the last before it's ignored as noise
HORIZON = 30 * 60  # seconds: estimates further out aren't scored
BUCKETS = ((0, 2), (2, 5), (5, 10), (10, 20), (20, 30))  # minutes out


def failure(e):
    """An exception as a log can carry it, with the API key cut out (AGENTS.md *Error handling*)."""
    text = "".join(traceback.format_exception(type(e), e, e.__traceback__)).rstrip()
    text = re.sub(r"api_key=[^&\s'\"]*", "api_key=REDACTED", text)
    key = os.environ.get("BODS_API_KEY")
    return text.replace(key, "REDACTED") if key else text


# GTFS-RT, read by hand: only FeedMessage.entity[].vehicle's trip, position, timestamp and id.

def varint(b, i):
    result = shift = 0
    while True:
        c = b[i]
        i += 1
        result |= (c & 0x7F) << shift
        shift += 7
        if c < 0x80:
            return result, i


def fields(b):
    """A protobuf message's (field number, value) pairs; a length-delimited value is its bytes."""
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(b, i)
        elif wire == 2:
            n, i = varint(b, i)
            value = b[i:i + n]
            i += n
        elif wire == 5:
            value = b[i:i + 4]
            i += 4
        elif wire == 1:
            value = b[i:i + 8]
            i += 8
        else:
            raise ValueError(f"unsupported wire type {wire}")
        if i > len(b):
            raise ValueError("message cut short")
        yield number, value


def vehicles(feed):
    """Each vehicle position in a GTFS-RT FeedMessage as a dict: trip_id, start_date, lat, lon, ts
    (when it was recorded); a field the feed leaves out is missing."""
    out = []
    for f, entity in fields(feed):
        if f != 2:
            continue
        for g, vehicle in fields(entity):
            if g != 4:
                continue
            v = {}
            for h, x in fields(vehicle):
                if h == 1:
                    for t, y in fields(x):
                        if t == 1:
                            v["trip_id"] = y.decode()
                        elif t == 3:
                            v["start_date"] = y.decode()
                elif h == 2:
                    for t, y in fields(x):
                        if t == 1:
                            v["lat"] = struct.unpack("<f", y)[0]
                        elif t == 2:
                            v["lon"] = struct.unpack("<f", y)[0]
                elif h == 5:
                    v["ts"] = x
            out.append(v)
    return out


# Recording.

def record(box, minutes, interval, out, opener=urllib.request.urlopen, now=time.time, sleep=time.sleep):
    key = os.environ.get("BODS_API_KEY")
    if not key:
        sys.exit("record needs BODS_API_KEY")
    os.makedirs(out, exist_ok=True)
    url = FEED + "?" + urllib.parse.urlencode({"boundingBox": box, "api_key": key})
    end = now() + minutes * 60
    saved = 0
    while now() < end:
        started = now()
        try:
            with opener(url, timeout=15) as response:
                body = response.read()
            with open(os.path.join(out, f"rt-{int(started)}.pb"), "wb") as f:
                f.write(body)
            saved += 1
        except OSError as e:
            # A failed poll is a gap in the recording, which scoring already tolerates.
            print(f"poll failed, skipped: {failure(e)}", file=sys.stderr)
        sleep(max(0, interval - (now() - started)))
    return saved


# Scoring.

def load_tracks(recording):
    """Fresh positions of buses carrying a trip id, as {(trip_id, start_date): {ts: (lat, lon,
    polled)}}, polled being the first poll that held it (when an app could first have used it),
    with counts of positions read, fresh, and fresh with a trip id."""
    tracks = collections.defaultdict(dict)
    counts = collections.Counter()
    for name in sorted(os.listdir(recording)):
        m = re.fullmatch(r"rt-(\d+)\.pb", name)
        if not m:
            continue
        counts["polls"] += 1
        polled = int(m.group(1))
        try:
            with open(os.path.join(recording, name), "rb") as f:
                feed = vehicles(f.read())
        except (ValueError, IndexError) as e:
            # A truncated or error answer saved as a poll; skip it like a failed poll.
            print(f"unreadable poll {name}, skipped: {failure(e)}", file=sys.stderr)
            continue
        for v in feed:
            counts["positions"] += 1
            if "ts" not in v or "lat" not in v or polled - v["ts"] > FRESH:
                continue
            counts["fresh"] += 1
            if not v.get("trip_id") or not v.get("start_date"):
                continue
            counts["with_trip"] += 1
            track = tracks[(v["trip_id"], v["start_date"])]
            if v["ts"] not in track:
                track[v["ts"]] = (v["lat"], v["lon"], polled)
    return tracks, counts


def seconds(text):
    h, m, s = text.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def load_timetable(zpath, trips):
    """Each of `trips`' stops in order as (stop_id, departure seconds after the service day's
    noon less 12 h), and every such stop's (lat, lon)."""
    with zipfile.ZipFile(zpath) as zf:
        stop_times = collections.defaultdict(list)
        with zf.open("stop_times.txt") as fh:
            reader = csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8-sig"))
            for r in reader:
                if r["trip_id"] in trips:
                    stop_times[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"], seconds(r["departure_time"])))
        wanted = {s for calls in stop_times.values() for _, s, _ in calls}
        stops = {}
        with zf.open("stops.txt") as fh:
            for r in csv.DictReader(io.TextIOWrapper(fh, encoding="utf-8-sig")):
                if r["stop_id"] in wanted:
                    stops[r["stop_id"]] = (float(r["stop_lat"]), float(r["stop_lon"]))
    return {t: [(s, d) for _, s, d in sorted(calls)] for t, calls in stop_times.items()}, stops


def service_day_start(date):
    """GTFS times count from noon less 12 hours on the service day, local time (so a day the
    clocks change on is still 24 h of numbers), as epoch seconds."""
    day = datetime.datetime.strptime(date, "%Y%m%d").replace(hour=12, tzinfo=LONDON)
    return int(day.timestamp()) - 12 * 3600


def xy(lat, lon, lat0):
    """Meters east and north of the equator and meridian, flat enough for one trip."""
    return lon * 111320 * math.cos(math.radians(lat0)), lat * 110540


def score_trip(calls, stops, positions, start):
    """[(error seconds, minutes out)] for the estimate and for the timetable alone, from one trip's
    calls [(stop_id, seconds)], stop positions, its positions {ts: (lat, lon[, polled])} and its
    service day's start (epoch seconds). An estimate is made when its position was first polled
    (its ts if no poll is given), so a position reaching the feed late estimates nothing for a stop
    passed meanwhile, and minutes out count from then. Error is actual less estimate: positive
    means the bus came later."""
    if len(calls) < 2 or any(s not in stops for s, _ in calls):
        return [], []
    lat0 = stops[calls[0][0]][0]
    points = [xy(*stops[s], lat0) for s, _ in calls]
    along = [0.0]
    for a, b in zip(points, points[1:]):
        along.append(along[-1] + math.dist(a, b))
    timetabled = [start + t for _, t in calls]

    def timetabled_at(d):
        for i in range(1, len(along)):
            if d <= along[i]:
                f = 0 if along[i] == along[i - 1] else (d - along[i - 1]) / (along[i] - along[i - 1])
                return timetabled[i - 1] + f * (timetabled[i] - timetabled[i - 1])
        return timetabled[-1]

    # Each position as distance along the trip: the nearest point on the trip no further back
    # than the last position, so a trip that loops or comes back along the same road is followed
    # onward rather than sent back to where it passed before. Of equally near points, the first.
    placed = []
    last = 0
    for ts in sorted(positions):
        lat, lon, *polled = positions[ts]
        q = xy(lat, lon, lat0)
        floor = placed[-1][1] - BACKTRACK if placed else -math.inf
        best = None
        for i in range(max(0, last - 1), len(points) - 1):
            a, b = points[i], points[i + 1]
            ab = (b[0] - a[0], b[1] - a[1])
            length = math.sqrt(ab[0] ** 2 + ab[1] ** 2)
            if along[i + 1] < floor:
                continue
            # Only the part of the segment not behind the bus.
            lowest = 0 if length == 0 else max(0.0, (floor - along[i]) / length)
            f = 0 if length == 0 else ((q[0] - a[0]) * ab[0] + (q[1] - a[1]) * ab[1]) / length ** 2
            f = max(lowest, min(1, f))
            d = along[i] + f * length
            off = math.dist(q, (a[0] + f * ab[0], a[1] + f * ab[1]))
            if best is None or off < best[0]:
                best = (off, i, d)
        if best and best[0] <= MAX_OFF:
            placed.append((ts, best[2], polled[0] if polled else ts))
            last = best[1]
    # When it passed each stop, between two close positions either side of it.
    passed = {}
    for j in range(1, len(along)):
        for (t1, d1, _), (t2, d2, _) in zip(placed, placed[1:]):
            if d1 < along[j] <= d2 and t2 - t1 <= MAX_GAP:
                passed[j] = t1 + (t2 - t1) * (along[j] - d1) / (d2 - d1)
                break
    estimates, timetable = [], []
    for t, d, made in placed:
        late = t - timetabled_at(d)
        for j, actual in passed.items():
            if along[j] <= d + 1 or actual <= made:
                continue
            estimate = max(made, timetabled[j] + late)
            if estimate - made <= HORIZON:
                estimates.append((actual - estimate, (estimate - made) / 60))
            bare = max(made, timetabled[j])
            if bare - made <= HORIZON:
                timetable.append((actual - bare, (bare - made) / 60))
    return estimates, timetable


def within_band(error, minutes):
    """California's accuracy band for trip updates (Caltrans): from -60 ln(T + 1.3) to
    60 ln(T + 1.5) seconds, T minutes out, late allowed a little more than early."""
    return -60 * math.log(minutes + 1.3) < error < 60 * math.log(minutes + 1.5)


def summary(rows):
    out = {}
    for lo, hi in BUCKETS:
        bucket = [(e, m) for e, m in rows if lo <= m < hi]
        if not bucket:
            continue
        errors = sorted(abs(e) for e, _ in bucket)
        out[f"{lo}-{hi}"] = {
            "n": len(bucket),
            "within": round(100 * sum(within_band(e, m) for e, m in bucket) / len(bucket)),
            "median_abs_s": round(errors[len(errors) // 2]),
            "p90_abs_s": round(errors[int(len(errors) * 0.9)]),
            "late_share": round(100 * sum(e > 0 for e, _ in bucket) / len(bucket)),
        }
    out["all"] = {"n": len(rows), "within": round(100 * sum(within_band(e, m) for e, m in rows) / max(1, len(rows)))}
    return out


def score(gtfs, recording):
    tracks, counts = load_tracks(recording)
    timetable, stops = load_timetable(gtfs, {trip for trip, _ in tracks})
    estimates, bare = [], []
    scored = 0
    for (trip, date), positions in tracks.items():
        e, b = score_trip(timetable.get(trip, []), stops, positions, service_day_start(date))
        if e or b:
            scored += 1
        estimates += e
        bare += b
    return {
        "polls": counts["polls"], "positions": counts["positions"], "fresh": counts["fresh"],
        "fresh_with_trip_pct": round(100 * counts["with_trip"] / max(1, counts["fresh"])),
        "trips_tracked": len(tracks), "trips_scored": scored,
        "estimate": summary(estimates), "timetable_only": summary(bare),
    }


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    r = sub.add_parser("record")
    where = r.add_mutually_exclusive_group(required=True)
    where.add_argument("--city", choices=sorted(CITIES))
    where.add_argument("--box", help="west,south,east,north")
    r.add_argument("--minutes", type=float, default=75)
    r.add_argument("--interval", type=float, default=20)
    r.add_argument("--out", required=True)
    s = sub.add_parser("score")
    s.add_argument("--gtfs", required=True, help="the region's BODS GTFS zip")
    s.add_argument("--recording", required=True)
    args = p.parse_args(argv)
    if args.command == "record":
        box = CITIES[args.city][0] if args.city else args.box
        print(f"saved {record(box, args.minutes, args.interval, args.out)} polls", file=sys.stderr)
    else:
        print(json.dumps(score(args.gtfs, args.recording), indent=1))


if __name__ == "__main__":
    main()
