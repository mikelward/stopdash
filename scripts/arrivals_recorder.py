#!/usr/bin/env python3
"""Record TfL's Tube arrivals at a few stops over time, and score how well they predicted.

TfL predicts each train as if the line ahead were clear, so a queue of trains reads as due within a
minute of each other (0 · 0 · 1 · 2 · 2 min at one platform) when they come about two minutes apart.
This tool measures that, and a fix for it, against what actually happened:

    record   poll StopPoint Arrivals for the given stops every --interval seconds, appending one
             JSON line per stop per poll to --out;
    score    from a recording, find when each train actually reached each platform, then compare
             TfL's raw times with the same times spaced at least --gap seconds apart per platform
             (each train no sooner than the one ahead of it, as adjusted, plus the gap);
    pairs    from a recording, take each pair of trains TfL showed close together at one platform
             (as the app grays them, and a little further apart), and count which of the two left
             the board before it was due, and what the one that did looked like beforehand.

Neither method is judged against its own numbers: the truth comes from where TfL says the train
is, not from any prediction, to within half a poll interval.
- When a train reached the platform: halfway between the poll where TfL first places it "At
  Platform" and the poll before. How far off each prediction was is measured only against this.
- When it left the board: halfway between the last poll that listed it and the next, which didn't.
  It counts only when that last listing was due within --arrived-within seconds of its poll and no
  later than the next one (seen arriving, not dropped while far out or before it was due). TfL
  keeps a train listed while it stands at the platform, so this is nearer when it left than when it
  came: the gaps between trains use it, but nothing measured against a prediction does.
- Whether a train came in a window uses when it reached the platform; for a train never seen "At
  Platform", when it left the board less the median dwell seen at that platform.
- A train that can't be followed (no id, listed twice, moved platform, or reaching a platform under
  MIN_HEADWAY after another) voids every count and gap it might fall in.

Recordings carry nothing about any user, but which stops someone chose to record can: keep
recordings of anything but a hub outside the repository (AGENTS.md *Privacy*). Keyless by default;
set TFL_APP_KEY to use a key.
"""
import argparse
import collections
import datetime
import http.client
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.tfl.gov.uk"
KEPT = (
    "vehicleId", "lineId", "platformName", "destinationName", "towards", "expectedArrival",
    "timeToStation", "currentLocation", "modeName",
)
PLATFORM = re.compile(r"platform (\d+)", re.IGNORECASE)
HORIZONS = ((0, 120), (120, 300), (300, 600), (600, None))
# A train id unseen at a platform this long, then listed again, is the train on a later trip.
# TfL drops a listing for a poll or two at a time; a trip round the line takes far longer.
REAPPEARS_AFTER = datetime.timedelta(minutes=10)
# No two trains reach one platform closer together than this: the tightest scheduled headway on the
# Underground is the Victoria line's 100 s.
MIN_HEADWAY = datetime.timedelta(seconds=90)


def parse_time(text):
    """A TfL timestamp ("2026-10-12T06:21:26Z", sometimes with fractions) as an aware datetime."""
    text = re.sub(r"\.\d+", "", text).replace("Z", "+00:00")
    return datetime.datetime.fromisoformat(text)


def fetch_arrivals(stop, opener=urllib.request.urlopen):
    query = {}
    key = os.environ.get("TFL_APP_KEY")
    if key:
        query["app_key"] = key
    url = f"{BASE}/StopPoint/{urllib.parse.quote(stop)}/Arrivals"
    if query:
        url += "?" + urllib.parse.urlencode(query)
    request = urllib.request.Request(url, headers={"User-Agent": "stopdash-arrivals-recorder"})
    with opener(request, timeout=30) as response:
        return json.load(response)


def record_line(stop, polled_at, arrivals):
    """One poll of one stop as a JSON line: when, and each arrival cut to the fields scoring reads."""
    return json.dumps({
        "stop": stop,
        "polledAt": polled_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "arrivals": [{k: a.get(k) for k in KEPT} for a in arrivals],
    })


def record(stops, interval, duration, out, fetch=fetch_arrivals, sleep=time.sleep, clock=None):
    clock = clock or (lambda: datetime.datetime.now(datetime.timezone.utc))
    start = clock()
    end = start + datetime.timedelta(seconds=duration)
    polls = 0
    while clock() < end:
        for stop in stops:
            try:
                arrivals = fetch(stop)
            except (OSError, http.client.HTTPException, ValueError) as e:
                # OSError covers URLError, timeouts and resets; HTTPException a dropped or cut
                # response; ValueError a body that isn't JSON.
                # A missed poll costs one sample; the next one resumes the series.
                print(f"{clock():%H:%M:%S} {stop}: fetch failed: {e}", file=sys.stderr)
                continue
            # Stamped when the answer came, the moment a rider could first have seen it.
            polled_at = clock()
            out.write(record_line(stop, polled_at, arrivals) + "\n")
            out.flush()
        polls += 1
        # Rounds start every `interval` from the first, however long the requests took, so a slow
        # answer doesn't stretch every stop's gap between polls. A round that overran waits for the
        # next slot rather than racing to catch up.
        elapsed = (clock() - start).total_seconds()
        sleep(math.ceil(elapsed / interval + 1e-9) * interval - elapsed)
    return polls


def vehicle_id(arrival):
    """A listing's train id, or "" when it has none: blank, or TfL's all-zero placeholder ("000"),
    as the app reads it (`TflArrivalDto`). A train with no id still holds its place in the queue it's
    spaced and counted in, but can't be followed between polls, so has no visit or actual time."""
    raw = (arrival.get("vehicleId") or "").strip()
    return raw if any(c != "0" for c in raw) else ""


def platform_key(stop, arrival, mode, line_modes):
    """The platform a train is queued at, or None when it isn't a `mode` train at a numbered one.

    TfL sometimes leaves a train's mode blank; it takes its line's (`line_modes`), as the app does.
    """
    own = arrival.get("modeName") or line_modes.get(arrival.get("lineId")) or ""
    if own != mode:
        return None
    match = PLATFORM.search(arrival.get("platformName") or "")
    if not match:
        return None
    # TfL can leave the line out; "" like the app (`TflArrivalDto`), so keys still sort.
    return (stop, arrival.get("lineId") or "", match.group(1))


def load(lines, mode="tube"):
    """Polls in time order, each (polled_at, stop, {platform: [(vehicle, expected)]}, ambiguous,
    at_platform, crowded): each platform's trains soonest first; for each train listed more than
    once on its line, or "At Platform" alongside another, its latest expected time on each platform
    listing it, {(platform, vehicle): expected}; the (platform, vehicle) pairs TfL places "At
    Platform" there; and the platforms where it places more than one at once.

    A poll that listed nothing is kept: it shows the stop was watched then. So is a train listed
    after its expected time: it was still there, which matters to when it arrived, though it's no
    prediction of anything to come (`upcoming`).
    """
    records = [json.loads(line) for line in lines if line.strip()]
    line_modes = modes_of_lines(records)
    polls = []
    for poll in records:
        polled_at = parse_time(poll["polledAt"])
        platforms = collections.defaultdict(list)
        at_platform = set()
        for arrival in poll["arrivals"]:
            key = platform_key(poll["stop"], arrival, mode, line_modes)
            if key is None or not arrival.get("expectedArrival"):
                continue
            expected = parse_time(arrival["expectedArrival"])
            platforms[key].append((vehicle_id(arrival), expected))
            if (arrival.get("currentLocation") or "").lower().startswith("at platform"):
                at_platform.add((key, vehicle_id(arrival)))
        # At a terminus TfL lists an incoming train on every platform it might use, and it can list
        # one train twice on one platform with two times. Which listing holds isn't in the feed, so
        # a train one poll lists more than once on its line can't be followed: each platform keeps
        # its soonest listing as a train with no id, holding its place in the queue and leaving out
        # any count or gap that covers it (`unresolved`), and its visit to whichever
        # platform it listed alone is void too (`settled`).
        listings = collections.Counter()
        for (stop, line, number), trains in platforms.items():
            for vehicle, _ in trains:
                if vehicle:
                    listings[(line, vehicle)] += 1
        doubled = {key for key, count in listings.items() if count > 1}
        ambiguous = {}
        for key, trains in platforms.items():
            kept = []
            for vehicle, expected in sorted(trains, key=lambda t: t[1]):
                if (key[1], vehicle) not in doubled:
                    kept.append((vehicle, expected))
                    continue
                if (key, vehicle) not in ambiguous:
                    kept.append(("", expected))
                # Its place in the queue is its soonest listing; it may come as late as its latest.
                ambiguous[(key, vehicle)] = expected
            trains[:] = kept
        # A train listed twice stands at the platform with no id, as it's listed: it still holds it.
        at_platform = {(key, "" if (key[1], vehicle) in doubled else vehicle) for key, vehicle in at_platform}
        # One platform holds one train. Two ids TfL places "At Platform" on one at once are a train
        # and the copy its tracking sometimes follows under a second id (see DoubtfulTrains.kt):
        # which is real isn't known, so both are void like a train listed twice, though each keeps
        # its place in the queue.
        # A train with no id counts as one there too, though only the trains with ids have visits
        # to void.
        # The platform is the physical one, whatever the lines: two lines sharing its track can't
        # both have a train there.
        standing_at = collections.defaultdict(list)
        crowded = set()
        for key, vehicle in at_platform:
            standing_at[physical(key)].append((key, vehicle))
        for occupants in standing_at.values():
            if len(occupants) > 1:
                crowded.update(key for key, _ in occupants)
                occupants = [(key, vehicle) for key, vehicle in occupants if vehicle]
                for key, vehicle in occupants:
                    expected = max(e for v, e in platforms[key] if v == vehicle)
                    ambiguous[(key, vehicle)] = max(ambiguous.get((key, vehicle), expected), expected)
                at_platform -= set(occupants)
        for trains in platforms.values():
            trains.sort(key=lambda t: t[1])
        polls.append((polled_at, poll["stop"], platforms, ambiguous, at_platform, crowded))
    polls.sort(key=lambda p: p[0])
    return polls


def modes_of_lines(records):
    """Each line's mode, from the listings that name it: {line: mode}."""
    modes = {}
    for poll in records:
        for arrival in poll["arrivals"]:
            if arrival.get("modeName") and arrival.get("lineId"):
                modes.setdefault(arrival["lineId"], arrival["modeName"])
    return modes


def locations(lines, mode="tube"):
    """Where TfL said each `mode` train at a numbered platform was, at each poll:
    {(polled_at, platform, vehicle): location}, "" where it left it blank. `load` keeps only "At
    Platform"; the pair breakdown (`pairs`) reads the rest."""
    records = [json.loads(line) for line in lines if line.strip()]
    line_modes = modes_of_lines(records)
    where = {}
    for poll in records:
        polled_at = parse_time(poll["polledAt"])
        for arrival in poll["arrivals"]:
            key = platform_key(poll["stop"], arrival, mode, line_modes)
            vehicle = vehicle_id(arrival)
            if key is not None and vehicle:
                where[(polled_at, key, vehicle)] = (arrival.get("currentLocation") or "").strip()
    return where


def physical(platform):
    """A platform (stop, line, number) as the track it is, whichever line's train is on it."""
    stop, _, number = platform
    return stop, number


def upcoming(trains, polled_at, standing=()):
    """The queue behind one platform at `polled_at`, from its listing (soonest first): the trains
    still to come, behind the one at the platform if any is.

    `standing` names the trains taken to be at the platform (`at_the_platform`), each with when it
    was last known to have got there. One platform holds one train, so the one that got there last
    is the one there (of those named without a time, the soonest listed): it goes first, now or at
    the time TfL gives it, whichever is sooner, but never before it was seen to get there, and holds
    back every train behind it. Any other named there is a copy or a listing TfL hasn't dropped yet, and is
    left out. Trains with no id share "" for a name, so of those only the soonest can be the one
    there (`score` leaves a poll out where there's more than one). A name can also be a (platform,
    vehicle) pair, for a queue of several lines' trains on one track; one with no id is (platform, "").
    """
    named = [k for k, (vehicle, _) in enumerate(trains) if vehicle in standing]
    if named and isinstance(standing, dict):
        latest = max(standing[trains[k][0]] for k in named)
        named = [k for k in named if standing[trains[k][0]] == latest]
    front = named[0] if named else None
    queue = []
    if front is not None:
        vehicle, expected = trains[front]
        # A time TfL hasn't moved on since gets it there earlier than it was seen to. Only placed
        # there now (`at_the_platform` gives it now) says nothing of when it got there.
        if isinstance(standing, dict) and standing[vehicle] < polled_at:
            expected = max(expected, standing[vehicle])
        queue.append((vehicle, min(expected, polled_at)))
    queue += [(vehicle, expected) for k, (vehicle, expected) in enumerate(trains)
              if k != front and expected >= polled_at and (anonymous(vehicle) or vehicle not in standing)]
    return queue


def anonymous(name):
    """Whether a train's name in a queue (`upcoming`) is one with no id."""
    return (name[1] if isinstance(name, tuple) else name) == ""


def at_the_platform(poll, platform, visit_of, i, there, seen):
    """The trains of `platform` taken to be at it at poll `i`, each with when it was first known to
    have got there: {vehicle: when}. They are those seen to have reached it already, by an arrival
    in `seen` (`platform_arrivals`, or the first poll placing two or more there at once: only what
    a live board could know) or before the run (`there`, the earliest of all); and those TfL places
    there now, alone or two or more at once (`load` voids those, but one of them is there): now,
    unless seen to get there earlier."""
    polled_at, _, platforms, ambiguous, at_platform, _ = poll
    listed = {vehicle for vehicle, _ in platforms.get(platform, [])}
    standing = {}
    for vehicle in listed:
        visit = visit_of.get((i, platform, vehicle))
        if visit in there:
            standing[vehicle] = datetime.datetime.min.replace(tzinfo=datetime.timezone.utc)
        elif visit in seen and seen[visit] <= polled_at:
            standing[vehicle] = seen[visit]
    # Placed there now: it got there by now, if not seen to get there earlier.
    for p, vehicle in at_platform:
        if p == platform:
            standing.setdefault(vehicle, polled_at)
    # A train listed twice has no id in the listing, so only those placed there together match.
    for p, vehicle in ambiguous:
        if p == platform and vehicle in listed:
            standing.setdefault(vehicle, polled_at)
    return standing


def runs(polls, max_gap):
    """For each poll, its stop's unbroken run of polls: (run id, when the run ends).

    A run breaks where one stop's consecutive polls are over `max_gap` seconds apart (failed fetches,
    or two recordings appended to one file) and at the stop's last poll. Nothing can be known about
    a stop between runs, so an arrival, a forecast window or a headway stays inside one. A run's id
    also keeps a train id TfL reuses in another session from being taken for the same train.
    """
    by_stop = collections.defaultdict(list)
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        by_stop[stop].append(i)
    result = [None] * len(polls)
    limit = datetime.timedelta(seconds=max_gap)
    for stop, indices in by_stop.items():
        current = []
        number = 0
        for i in indices + [None]:
            if current and (i is None or polls[i][0] - polls[current[-1]][0] > limit):
                for j in current:
                    result[j] = ((stop, number), polls[current[-1]][0])
                current = []
                number += 1
            if i is not None:
                current.append(i)
    return result


def visits(polls, max_gap):
    """Which visit to its platform each listing is, for each (poll index, platform, vehicle).

    A visit is (platform, run, vehicle, n): the n-th time that train id came to that platform in the
    run, a new one each time it reappears after REAPPEARS_AFTER unseen.
    """
    run_of = runs(polls, max_gap)
    seen = {}
    result = {}
    for i, (polled_at, _, platforms, _, _, _) in enumerate(polls):
        run, _ = run_of[i]
        for platform, trains in platforms.items():
            for vehicle, _ in trains:
                if not vehicle:
                    continue
                key = (platform, run, vehicle)
                last, n = seen.get(key, (None, 0))
                if last is not None and polled_at - last > REAPPEARS_AFTER:
                    n += 1
                seen[key] = (polled_at, n)
                result[(i, platform, vehicle)] = key + (n,)
    return result


def actual_arrivals(polls, arrived_within, max_gap):
    """When each train reached each platform, by visit: see `settled`."""
    return {visit: at for visit, (at, _) in settled(polls, arrived_within, max_gap).items()}


def settled(polls, arrived_within, max_gap):
    """Each visit's actual arrival and its last listing's poll: {visit: (arrival, polled_at)}.

    See `settle`.
    """
    return settle(polls, arrived_within, max_gap)[0]


def settle(polls, arrived_within, max_gap):
    """(settled, void): each visit's actual arrival and its last listing's poll, {visit: (arrival,
    polled_at)}; and when each voided visit might have come, {visit: (from, to)}.

    The arrival is when the train left the board, halfway between its last listing's poll and the
    stop's next poll. Keyed by visit (`visits`). A visit has no actual time when:
    - its last listing was further than `arrived_within` seconds from its poll either way (dropped
      while far out, perhaps withdrawn; or a stale time that says nothing of when it came);
    - that time is after the stop's next poll, which no longer listed it: it went before it was due;
    - that listing was in its run's last poll: it may still have been coming;
    - the same train was listed on several platforms during the visit, at once or in turn: which it
      took isn't known;
    - another train was seen reaching the same platform closer to it than MIN_HEADWAY: one of the
      two may be a copy of the other. Such a visit is void: whether and when it came there is unknown, from its
      first listing to `arrived_within` seconds after the later of its last expected time and the
      poll after its last listing (`unresolved`).
    """
    run_of = runs(polls, max_gap)
    visit_of = visits(polls, max_gap)
    next_poll = {}
    previous = {}
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        if stop in previous:
            next_poll[previous[stop]] = polled_at
        previous[stop] = i
    last = {}
    span = {}
    for i, (polled_at, _, platforms, _, _, _) in enumerate(polls):
        _, end = run_of[i]
        for platform, trains in platforms.items():
            for vehicle, expected in trains:
                visit = visit_of.get((i, platform, vehicle))
                if visit is None:
                    continue  # No id: nothing to follow.
                last[visit] = (polled_at, expected, end, next_poll.get(i))
                first = span.get(visit, (polled_at, polled_at))[0]
                span[visit] = (first, polled_at)
    doubtful = collections.defaultdict(list)
    for i, (polled_at, _, _, ambiguous, _, _) in enumerate(polls):
        run, _ = run_of[i]
        for platform, vehicle in ambiguous:
            doubtful[(run, platform[1], vehicle)].append(polled_at)
    # A train TfL moves from one platform to another between polls (a terminus changing its mind)
    # has a visit to each; which it took isn't known, so both are void.
    by_train = collections.defaultdict(list)
    for visit in span:
        platform, run, vehicle, _ = visit
        by_train[(run, platform[1], vehicle)].append(visit)
    moved = set()
    for visits_of_train in by_train.values():
        for a in visits_of_train:
            for b in visits_of_train:
                if a[0] != b[0] and span[a][0] - REAPPEARS_AFTER <= span[b][1] and span[b][0] <= span[a][1] + REAPPEARS_AFTER:
                    moved.add(a)
    # Two trains seen reaching one platform closer together than MIN_HEADWAY, however the polls'
    # spacing is read, can't both be real: one may be the copy TfL's tracking sometimes follows
    # under a second id, at the platform with the real train or just after it (DoubtfulTrains.kt).
    # Which is real isn't known, so both are void.
    # A train with no id reaching the platform counts too, though it has no visit of its own to void,
    # and so do two or more placed there at once, which `load` takes out of `at_platform`.
    # So does another line's train on the same track.
    sightings_of = platform_sightings(polls, max_gap)
    by_platform = collections.defaultdict(list)
    for visit, (before, seen) in sightings_of.items():
        by_platform[(physical(visit[0]), visit[1])].append((seen, before, visit))
    for (platform, run), (before, seen) in anonymous_platform_sightings(polls, max_gap):
        by_platform[(physical(platform), run)].append((seen, before, None))
    for sightings in by_platform.values():
        sightings.sort(key=lambda s: (s[0], s[1]))
        for (_, before_a, a), (seen_b, _, b) in zip(sightings, sightings[1:]):
            if seen_b - before_a < MIN_HEADWAY:
                moved.update(v for v in (a, b) if v is not None)
    actual = {}
    void = {}
    slack = datetime.timedelta(seconds=arrived_within)
    for visit, (polled_at, expected, end, following) in last.items():
        platform, run, vehicle, _ = visit
        first, latest = span[visit]
        if visit in moved or any(first - REAPPEARS_AFTER <= t <= latest + REAPPEARS_AFTER
                                 for t in doubtful[(run, platform[1], vehicle)]):
            # One first listed already "At Platform" got there after the poll before.
            since = min(first, sightings_of[visit][0]) if visit in sightings_of else first
            void[visit] = (since, max(expected, following or polled_at) + slack)
            continue
        if polled_at == end or following is None or expected > following:
            continue
        if abs((expected - polled_at).total_seconds()) > arrived_within:
            continue
        # When it left the board, not its predicted time: truth independent of what's scored.
        actual[visit] = (polled_at + (following - polled_at) / 2, polled_at)
    return actual, void


def unresolved(polls, arrived_within, max_gap):
    """When trains that can't be followed might have come, by platform and run:
    {(platform, run): [(from, to)]}.

    Whether and when such a train came is unknown, so a count or a gap that overlaps the time it
    might have come is incomplete, and is left out rather than scored as if it never came. They are
    trains with no id, from the poll listing one (the poll before, if TfL places one "At Platform")
    to `arrived_within` seconds after the later of its
    expected time and the stop's next poll (a listing TfL drops for a poll can come back, so its
    going from the board doesn't settle when it came); a train listed more than once, or "At
    Platform" alongside another, from the poll before to after its latest time; and void visits
    (`settle`), from the poll before if first seen already "At Platform".
    """
    run_of = runs(polls, max_gap)
    next_poll = {}
    previous_poll = {}
    previous = {}
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        if stop in previous:
            next_poll[previous[stop]] = polled_at
            if run_of[previous[stop]][0] == run_of[i][0]:
                previous_poll[i] = polls[previous[stop]][0]
        previous[stop] = i
    slack = datetime.timedelta(seconds=arrived_within)
    seen = collections.defaultdict(list)

    def since(i, platform):
        # One TfL places "At Platform" got there after the stop's poll before, not at this one.
        polled_at, at_platform = polls[i][0], polls[i][4]
        return previous_poll.get(i, polled_at) if (platform, "") in at_platform else polled_at

    for i, (polled_at, _, platforms, _, _, _) in enumerate(polls):
        run, _ = run_of[i]
        following = next_poll.get(i, polled_at)
        for platform, trains in platforms.items():
            for vehicle, expected in trains:
                if not vehicle:
                    seen[(platform, run)].append((since(i, platform), max(expected, following) + slack))
    for i, (polled_at, _, _, ambiguous, _, _) in enumerate(polls):
        run, _ = run_of[i]
        following = next_poll.get(i, polled_at)
        for (platform, _), latest in ambiguous.items():
            # Whether it was already at the platform isn't trusted either (two trains there at
            # once lose their place there): it may have come since the poll before.
            seen[(platform, run)].append((previous_poll.get(i, polled_at), max(latest, following) + slack))
    for (platform, run, _, _), when in settle(polls, arrived_within, max_gap)[1].items():
        seen[(platform, run)].append(when)
    return seen


def overlaps(spans, start, end):
    """Whether any (from, to) in `spans` overlaps [start, end)."""
    return any(a < end and b > start for a, b in spans)


def platform_sightings(polls, max_gap):
    """For each visit TfL first places "At Platform" after a poll that didn't: {visit: (that poll
    before, the poll that first said so)}. One already there in its run's first poll arrived at an
    unknown time before it, however long it then stands, so it has none.
    """
    run_of = runs(polls, max_gap)
    visit_of = visits(polls, max_gap)
    previous_poll = {}
    last_of_stop = {}
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        if stop in last_of_stop and run_of[last_of_stop[stop]][0] == run_of[i][0]:
            previous_poll[i] = polls[last_of_stop[stop]][0]
        last_of_stop[stop] = i
    sightings = {}
    seen = set()
    for i, (polled_at, _, _, _, at_platform, _) in enumerate(polls):
        for platform, vehicle in at_platform:
            visit = visit_of.get((i, platform, vehicle))
            if visit is None or visit in seen:
                continue
            seen.add(visit)
            if i in previous_poll:
                sightings[visit] = (previous_poll[i], polled_at)
    return sightings


def anonymous_platform_sightings(polls, max_gap):
    """For each poll placing a train with no id "At Platform" on a platform, or more than one train
    there at once, after a poll of the same run: [((platform, run), (that poll before, this poll))].
    With no id, one there now may not be the one there before (another can replace it between
    polls); with several, which is there isn't known. So each such poll is a time one may have
    reached the platform, not only the first.
    """
    run_of = runs(polls, max_gap)
    previous = {}
    sightings = []
    for i, (polled_at, stop, _, _, at_platform, crowded) in enumerate(polls):
        before = previous.get(stop)
        previous[stop] = i
        if before is None or run_of[before][0] != run_of[i][0]:
            continue
        for platform in {p for p, vehicle in at_platform if not vehicle} | crowded:
            sightings.append(((platform, run_of[i][0]), (polls[before][0], polled_at)))
    return sightings


def already_there(polls, max_gap):
    """Visits TfL places "At Platform" in their run's first poll: they arrived before it, at a time
    not known, so they have no arrival and came in no window of the run."""
    visit_of = visits(polls, max_gap)
    sighted = platform_sightings(polls, max_gap)
    there = set()
    for i, (_, _, _, _, at_platform, _) in enumerate(polls):
        for platform, vehicle in at_platform:
            visit = visit_of.get((i, platform, vehicle))
            if visit is not None and visit not in sighted:
                there.add(visit)
    return there


def platform_arrivals(polls, arrived_within, max_gap):
    """When each visit reached its platform, where TfL said so: {visit: arrival}.

    TfL keeps a train listed while it stands at the platform, so leaving the board (`settled`) is
    nearer departure than arrival, and spacing, which only moves times later, would look better
    against it than it is. TfL's location "At Platform" marks the arrival itself: it's taken as
    halfway between the poll that first said so and the stop's poll before. It needs nothing of
    how the train left the board, so a train still listed at the end of its run, or with a stale
    expected time, keeps it. Visits never seen at the platform, seen there in their run's first
    poll, or void (`settle`) have none; errors are measured on the rest.
    """
    void = settle(polls, arrived_within, max_gap)[1]
    return {
        visit: before + (seen - before) / 2
        for visit, (before, seen) in platform_sightings(polls, max_gap).items()
        if visit not in void
    }


def arrival_estimates(polls, arrived_within, max_gap):
    """When each visit reached its platform, best known: {visit: arrival}.

    Where TfL said "At Platform", that (`platform_arrivals`). Elsewhere, for a settled visit, when
    it left the board less
    the median dwell seen at that platform (at any platform if none was seen there; nothing if none
    was seen at all): leaving the board alone puts a train that arrived just inside a window outside
    it, which raw times would be blamed for and spacing, moving times later, credited for.
    """
    truth = settled(polls, arrived_within, max_gap)
    arrived = platform_arrivals(polls, arrived_within, max_gap)
    first_listed = {}
    for (i, _, _), visit in visits(polls, max_gap).items():
        first_listed[visit] = min(first_listed.get(visit, polls[i][0]), polls[i][0])
    dwells = collections.defaultdict(list)
    for visit, at in arrived.items():
        if visit in truth:
            dwells[visit[0]].append((truth[visit][0] - at).total_seconds())
    everywhere = [d for ds in dwells.values() for d in ds]
    estimates = dict(arrived)
    there = already_there(polls, max_gap)
    for visit, (left, _) in truth.items():
        if visit in arrived or visit in there:
            continue
        seen = dwells.get(visit[0]) or everywhere
        if seen:
            # Never before it was first listed: polls before that didn't have it on the board.
            estimates[visit] = max(left - datetime.timedelta(seconds=median(seen)), first_listed[visit])
    return estimates


def spaced(trains, gap):
    """`trains` (soonest first) with each no sooner than `gap` seconds after the one ahead, as moved."""
    out = []
    previous = None
    step = datetime.timedelta(seconds=gap)
    for vehicle, expected in trains:
        at = expected if previous is None else max(expected, previous + step)
        out.append((vehicle, at))
        previous = at
    return out


def horizon(seconds):
    for low, high in HORIZONS:
        if seconds >= low and (high is None or seconds < high):
            return (low, high)
    return HORIZONS[0]


def score(polls, gaps, arrived_within, max_gap, window=300, line=None):
    """Errors in seconds per method and horizon, and trains shown in the next `window` seconds.

    Returns {method: {"errors": {horizon: [signed error]}, "shown": n, "came": n}}, where a
    positive error means the prediction was later than the train. Errors are against when it reached
    the platform (`platform_arrivals`); whether and when it came, for the counts, against the best
    estimate of that for every settled visit (`arrival_estimates`). "shown" counts, over every poll,
    trains a method put within `window`, leaving out any already at the platform; "came" counts the
    trains that actually reached the platform
    in that window, listed at that poll or not. A train TfL listed and then dropped while far out
    counts as shown but not as come. Only polls whose whole window lies in their stop's unbroken run
    of polls, and overlapping no time a train that can't be followed might have come (`unresolved`),
    are counted. With `line`, only that line's platforms are scored, though every line's trains
    still decide what's void (two lines can share a platform).
    """
    actual = arrival_estimates(polls, arrived_within, max_gap)
    arrived = platform_arrivals(polls, arrived_within, max_gap)
    unknown = unresolved(polls, arrived_within, max_gap)
    run_of = runs(polls, max_gap)
    visit_of = visits(polls, max_gap)
    # Trains TfL placed "At Platform" two or more at once: one of them got there then, so each still
    # listed is at the platform from that poll on, as one seen arriving is (`at_the_platform`).
    # The time is known when it's placed there, so it's no hindsight.
    live = dict(arrived)
    for i, (polled_at, _, _, ambiguous, _, crowded) in enumerate(polls):
        for platform, vehicle in ambiguous:
            visit = visit_of.get((i, platform, vehicle))
            if platform in crowded and visit is not None:
                live.setdefault(visit, polled_at)
    arrivals = collections.defaultdict(list)
    for (platform, run, _, _), at in actual.items():
        arrivals[(platform, run)].append(at)
    # A train that came but with no estimate of when it reached the platform (no dwell seen to take
    # off its leaving the board) reached it sometime between its first listing and leaving.
    first_listed = {}
    for (i, _, _), visit in visit_of.items():
        first_listed[visit] = min(first_listed.get(visit, polls[i][0]), polls[i][0])
    there = already_there(polls, max_gap)
    for visit, (left, _) in settled(polls, arrived_within, max_gap).items():
        if visit not in actual and visit not in there:
            platform, run, _, _ = visit
            unknown[(platform, run)].append((first_listed[visit], left))
    # One queue per track: lines sharing a platform's track hold each other's trains back. Each
    # train keeps its own platform (line) for its visit and for whose result it counts in.
    tracks_of = collections.defaultdict(lambda: collections.defaultdict(set))
    for _, stop, platforms, _, _, _ in polls:
        for platform in platforms:
            tracks_of[stop][physical(platform)].add(platform)
    methods = ["raw"] + [f"spaced {g:g}s" for g in gaps]
    result = {m: {"errors": collections.defaultdict(list), "shown": 0, "came": 0} for m in methods}
    limit = datetime.timedelta(seconds=window)
    for i, (polled_at, stop, platforms, _, _, _) in enumerate(polls):
        run, end = run_of[i]
        # A count needs the whole window watched, or trains still to come read as never coming.
        counted = polled_at + limit <= end
        for keys in tracks_of[stop].values():
            scored = {key for key in keys if line is None or key[1] == line}
            if not scored:
                continue
            # Only what was seen by this poll: an arrival estimated afterwards from a train leaving
            # the board is hindsight no live board has.
            standing = {}
            listing = []
            # In a fixed order: two lines' trains due at once keep the same places run to run.
            for key in sorted(keys):
                standing.update(((key, vehicle), when) for vehicle, when
                                in at_the_platform(polls[i], key, visit_of, i, there, live).items())
                listing += [((key, vehicle), expected) for vehicle, expected in platforms.get(key, [])]
            listing.sort(key=lambda t: t[1])
            trains = upcoming(listing, polled_at, standing)
            # The train at the platform, if any, leads the queue.
            occupied = bool(trains) and trains[0][0] in standing
            # A train that may come in this window unseen, or at a time not known, on any line of
            # the track: neither count holds up.
            window_counted = counted and not any(
                overlaps(unknown[(key, run)], polled_at, polled_at + limit) for key in keys)
            came = sum(1 for key in scored for at in arrivals[(key, run)] if polled_at <= at < polled_at + limit)
            versions = [("raw", trains)] + [(f"spaced {g:g}s", spaced(trains, g)) for g in gaps]
            for method, predicted in versions:
                if window_counted:
                    result[method]["came"] += came
                for k, ((key, vehicle), at) in enumerate(predicted):
                    if key not in scored:
                        continue  # Another line's train: it holds this line's back, nothing more.
                    visit = visit_of.get((i, key, vehicle))
                    # The train at the platform holds back the trains behind it, but came before
                    # this window, so it isn't counted as shown in it.
                    here = occupied and k == 0
                    if window_counted and at - polled_at < limit and not here:
                        result[method]["shown"] += 1
                    real = arrived.get(visit)
                    if real is None or real <= polled_at:
                        continue  # Not seen arriving, or this listing came after it had.
                    # A train that can't be followed may have stood at the platform, or come, between
                    # this poll and this train's arrival, holding it back unseen: its error isn't scored.
                    if any(overlaps(unknown[(key, run)], polled_at, real) for key in keys):
                        continue
                    out = (real - polled_at).total_seconds()
                    result[method]["errors"][horizon(out)].append((at - real).total_seconds())
    return result


def headways(polls, arrived_within, max_gap):
    """Seconds between consecutive trains leaving the board at each platform, within one run of
    polls (`settled`): the gaps between trains, give or take a dwell.

    A gap is left out where a train that can't be followed may have come within it (`unresolved`),
    or where a train seen at the platform (`platform_arrivals`) whose leaving the board didn't
    settle was there: from its arrival to the stop's poll after its last listing, or the end of
    its run.
    """
    actual = actual_arrivals(polls, arrived_within, max_gap)
    by_run = collections.defaultdict(list)
    for (platform, run, _, _), at in actual.items():
        by_run[(platform, run)].append(at)
    unknown = unresolved(polls, arrived_within, max_gap)
    run_of = runs(polls, max_gap)
    next_poll = {}
    previous = {}
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        if stop in previous:
            next_poll[previous[stop]] = polled_at
        previous[stop] = i
    gone_by = {}
    for (i, _, _), visit in visits(polls, max_gap).items():
        _, end = run_of[i]
        bound = min(next_poll.get(i, end), end)
        gone_by[visit] = max(gone_by.get(visit, bound), bound)
    for visit, at in platform_arrivals(polls, arrived_within, max_gap).items():
        if visit not in actual:
            platform, run, _, _ = visit
            unknown[(platform, run)].append((at, gone_by[visit]))
    gaps = collections.defaultdict(list)
    for (platform, run), times in by_run.items():
        times.sort()
        gaps[platform].extend(
            (b - a).total_seconds() for a, b in zip(times, times[1:])
            # A train that can't be followed may have come between them.
            if not overlaps(unknown[(platform, run)], a, b)
        )
    return dict(gaps)


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def by_line(polls, gaps, arrived_within, max_gap, window=300):
    """`score` for each line on its own: {line: result}. The right gap may differ by line, since
    it follows how often its trains run."""
    names = sorted({platform[1] for _, _, platforms, _, _, _ in polls for platform in platforms})
    # Scored over the whole recording, so another line's train on shared track still voids what it
    # should; only the counts and errors are this line's.
    return {line: score(polls, gaps, arrived_within, max_gap, window, line) for line in names}


# The pair breakdown (`pairs`) follows the app's rule for a pair (DoubtfulTrains.kt): two of a line's
# trains next to each other at one platform, the first due within PAIR_WITHIN. It looks as far apart
# as just under MIN_HEADWAY, wider than the app's 60 s, to see what the bands either side of it hold.
PAIR_WITHIN = datetime.timedelta(minutes=5)
PAIR_BANDS = ((0, 5), (6, 60), (61, 89))
APP_PAIR = 60  # DoubtfulTrains.PAIR, in seconds.
# A train that leaves the board with more than this still to go didn't come when it was shown: it
# vanished. One TfL drops with less to go may have come (TfL can drop a train as it arrives).
VANISHED_WITH = datetime.timedelta(seconds=30)
# A train that has stopped counting down: over its last STALL_WINDOW listed, its expected time moved
# later by at least half the time that passed (one counting down keeps its time). TfL's answer is
# cached for about 30 s, so the window must hold two of its updates: STALL_SPAN of listings at least.
STALL_WINDOW = datetime.timedelta(seconds=90)
STALL_SPAN = datetime.timedelta(seconds=45)


def left_board(polls, max_gap, arrived_within=60):
    """How each visit left the board: {visit: (vanished, poll index of its last listing)}, vanished
    when it still had more than VANISHED_WITH to go when it left (halfway between that listing's
    poll and the stop's next, as `settle` takes it), unless TfL ever placed it "At Platform": that
    marks its arrival, as `platform_arrivals` takes it, whatever time it still showed. None of
    these is in it:
    - a visit still listed when its run of polls ended: it may yet have come;
    - one `settle` voids (moved to another platform, listed twice, or reaching the platform under
      MIN_HEADWAY after another): it can't be followed or can't be real;
    - one last listed more than `arrived_within` seconds past its time and never placed "At
      Platform": a stale time says nothing of whether it came. One TfL placed there came, however
      long it then stood listed."""
    run_of = runs(polls, max_gap)
    visit_of = visits(polls, max_gap)
    last = {}
    there = set()
    for i, (_, _, platforms, _, at_platform, _) in enumerate(polls):
        for platform, trains in platforms.items():
            for vehicle, expected in trains:
                visit = visit_of.get((i, platform, vehicle))
                if visit is not None:
                    last[visit] = (i, expected)
                    if (platform, vehicle) in at_platform:
                        there.add(visit)
    lost = settle(polls, arrived_within, max_gap)[1]
    next_poll = {}
    previous = {}
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        if stop in previous:
            next_poll[previous[stop]] = polled_at
        previous[stop] = i
    result = {}
    for visit, (i, expected) in last.items():
        polled_at = polls[i][0]
        if polled_at == run_of[i][1] or visit in lost:
            continue
        if (polled_at - expected).total_seconds() > arrived_within and visit not in there:
            continue
        left = polled_at + (next_poll[i] - polled_at) / 2
        result[visit] = (visit not in there and expected - left > VANISHED_WITH, i)
    return result


def stalled(history, end):
    """Whether a train had stopped counting down by `end`, from its listings [(polled_at,
    expected)] (time order): its expected time moved later by at least half the time that passed
    over its listings in the STALL_WINDOW to `end`. None when they span less than STALL_SPAN."""
    recent = [(t, e) for t, e in history if end - STALL_WINDOW <= t <= end]
    if len(recent) < 2 or recent[-1][0] - recent[0][0] < STALL_SPAN:
        return None
    (t0, e0), (t1, e1) = recent[0], recent[-1]
    return (e1 - e0) * 2 >= t1 - t0


def on_two_lines(lines, mode="tube"):
    """The listings of a train TfL lists on two lines at once, as the app finds them
    (DoubtfulTrains.kt): {(polled_at, platform, vehicle, expected)} for each not yet due whose id
    another line lists at the same platform of the same stop, due within APP_PAIR of it. Until TfL
    settles which line a train runs as, it lists it under each it could be (the Hammersmith & City
    and the Metropolitan or District) and drops one listing once it knows, so one of the two leaves
    the board early. An id is unique within a line only, so listings further apart are two trains.

    Read from the feed itself, as the app reads it, not from `load`'s polls: those take a train
    listed twice on one line as a train with no id, which would hide its listing on the other."""
    records = [json.loads(line) for line in lines if line.strip()]
    line_modes = modes_of_lines(records)
    found = set()
    for poll in records:
        polled_at = parse_time(poll["polledAt"])
        by_track = collections.defaultdict(list)
        for arrival in poll["arrivals"]:
            key = platform_key(poll["stop"], arrival, mode, line_modes)
            vehicle = vehicle_id(arrival)
            if key is None or not vehicle or not arrival.get("expectedArrival"):
                continue
            expected = parse_time(arrival["expectedArrival"])
            if expected > polled_at:
                by_track[(physical(key), vehicle)].append((key, expected))
        for (_, vehicle), listings in by_track.items():
            for platform, expected in listings:
                if any(other[1] != platform[1] and abs((e - expected).total_seconds()) <= APP_PAIR
                       for other, e in listings):
                    found.add((polled_at, platform, vehicle, expected))
    return found


def two_line_trains(polls, two_lines, max_gap, arrived_within=60):
    """What became of each visit `on_two_lines` ever lists (`two_lines`): {visit: vanished}, None
    for one that hasn't settled how it left the board (`left_board`). A listing `load` took as a
    train with no id has no visit, so isn't counted."""
    visit_of = visits(polls, max_gap)
    left = left_board(polls, max_gap, arrived_within)
    index = collections.defaultdict(list)
    for i, (polled_at, stop, _, _, _, _) in enumerate(polls):
        index[(polled_at, stop)].append(i)
    result = {}
    for polled_at, platform, vehicle, _ in two_lines:
        for i in index[(polled_at, platform[0])]:
            visit = visit_of.get((i, platform, vehicle))
            if visit is not None:
                result[visit] = left[visit][0] if visit in left else None
    return result


def pairs(polls, max_gap, two_lines, where=None, arrived_within=60):
    """Each pair of trains the app would gray, or nearly (see PAIR_BANDS), and what became of it.

    A pair is two visits a poll lists next to each other at one platform, neither yet due, the
    first due within PAIR_WITHIN, at most PAIR_BANDS' widest apart. A listing of a train TfL lists
    on two lines at once (`two_lines`, from `on_two_lines`) is left out of its queue, as the app
    leaves it out: it is grayed for that, not for the train next to it. One `load` took as a train
    with no id is matched by its time. Each pair counts once, in the band of the
    closest TfL showed them; "earlier" is the one first shown first. Returns a list of
    dicts: "earlier", "later" (visits), "gap" (s), "swapped" (shown in the other order at another
    poll), "line", "vanished" ({visit: bool}, or None for a pair either of which hasn't settled
    how it left the board, `left_board`), and for a pair one of which vanished, "stalled" ({visit: bool or None}: had
    stopped counting down by the vanished one's last listing, `stalled`). With `where`
    (`locations`), also "blank" ({visit: bool}: no location at the vanished one's last listing) and
    "same_place" (both at one location at a poll pairing them). Trains with no id can't be followed,
    so make no pairs.
    """
    visit_of = visits(polls, max_gap)
    left = left_board(polls, max_gap, arrived_within)
    history = collections.defaultdict(list)
    for i, (polled_at, _, platforms, _, _, _) in enumerate(polls):
        for platform, trains in platforms.items():
            for vehicle, expected in trains:
                visit = visit_of.get((i, platform, vehicle))
                if visit is not None:
                    history[visit].append((polled_at, expected))
    widest = PAIR_BANDS[-1][1]
    named = {(t, platform, vehicle) for t, platform, vehicle, _ in two_lines}
    unnamed = {(t, platform, expected) for t, platform, _, expected in two_lines}
    found = {}
    for i, (polled_at, _, platforms, _, _, _) in enumerate(polls):
        for platform, trains in platforms.items():
            # As the app does (Countdown.hasDeparted), a train due now has gone.
            due = [(vehicle, expected) for vehicle, expected in trains if expected > polled_at
                   and (polled_at, platform, vehicle) not in named
                   and (vehicle or (polled_at, platform, expected) not in unnamed)]
            for (va, ea), (vb, eb) in zip(due, due[1:]):
                if ea - polled_at >= PAIR_WITHIN:
                    break
                gap = (eb - ea).total_seconds()
                a, b = visit_of.get((i, platform, va)), visit_of.get((i, platform, vb))
                if gap > widest or a is None or b is None:
                    continue
                pair = found.setdefault(frozenset((a, b)), {
                    "earlier": a, "later": b, "gap": gap, "swapped": False, "line": platform[1],
                    "same_place": False if where is not None else None,
                })
                pair["gap"] = min(pair["gap"], gap)
                pair["swapped"] |= (a, b) != (pair["earlier"], pair["later"])
                if where is not None:
                    here = where.get((polled_at, platform, va)), where.get((polled_at, platform, vb))
                    pair["same_place"] |= bool(here[0]) and here[0] == here[1]
    result = []
    for pair in found.values():
        a, b = pair["earlier"], pair["later"]
        pair["vanished"] = {a: left[a][0], b: left[b][0]} if a in left and b in left else None
        gone = [v for v in (a, b) if pair["vanished"] and pair["vanished"][v]]
        if len(gone) == 1:
            last = left[gone[0]][1]
            end = polls[last][0]
            pair["stalled"] = {v: stalled(history[v], end) for v in (a, b)}
            if where is not None:
                # None for one not listed at that poll: nothing was said of where it was.
                places = {v: where.get((end, v[0], v[2])) for v in (a, b)}
                pair["blank"] = {v: None if place is None else not place for v, place in places.items()}
        result.append(pair)
    return result


def pair_outcome(pair):
    """Which of a pair vanished: "both came", "earlier vanished", "later vanished", "both
    vanished", or None while either hasn't settled (`left_board`)."""
    vanished = pair["vanished"]
    if vanished is None:
        return None
    earlier, later = vanished[pair["earlier"]], vanished[pair["later"]]
    if earlier and later:
        return "both vanished"
    if earlier or later:
        return "earlier vanished" if earlier else "later vanished"
    return "both came"


PAIR_OUTCOMES = ("both came", "earlier vanished", "later vanished", "both vanished")


def pair_report(found, out=None, two_lines=None):
    """`pairs`' breakdown as a table; with `two_lines` (`two_line_trains`), what became of the trains
    left out of it as listed on two lines."""
    out = out or sys.stdout
    def band(gap):
        return next((low, high) for low, high in PAIR_BANDS if gap <= high)

    def row(label, counted, unsettled=None):
        counts = collections.Counter(pair_outcome(p) for p in counted)
        cells = "".join(f"{counts[o]:>18}" for o in PAIR_OUTCOMES)
        tail = f"{unsettled:>14}" if unsettled is not None else ""
        print(f"  {label:>18}{cells}{tail}", file=out)

    print("Pairs of a line's trains next to each other at one platform, the first due within 5 min,"
          " by the closest", file=out)
    print(f"TfL showed them. A train vanished if it left the board with more than"
          f" {VANISHED_WITH.seconds} s to go;", file=out)
    print("earlier and later are as first shown. Unsettled: still listed when the recording ended,"
          " or void as `score` takes it", file=out)
    print("(moved platform, listed twice, or two reaching the platform under 90 s apart), or one last listed long"
          " past its time.", file=out)
    print("A train TfL lists on two lines at once is left out, as the app leaves it out.", file=out)
    print(f"  {'Shown apart':>18}" + "".join(f"{o:>18}" for o in PAIR_OUTCOMES) + f"{'unsettled':>14}",
          file=out)
    for low, high in PAIR_BANDS:
        in_band = [p for p in found if band(p["gap"]) == (low, high)]
        row(f"{low}-{high} s", [p for p in in_band if pair_outcome(p)],
            sum(1 for p in in_band if pair_outcome(p) is None))
    swapped = sum(1 for p in found if p["swapped"])
    print(f"  {swapped} of {len(found)} pairs were shown in both orders.", file=out)
    close = [p for p in found if p["gap"] <= APP_PAIR]
    print(f"By line, 0-{APP_PAIR} s apart (the pairs the app grays):", file=out)
    for line in sorted({p["line"] for p in close}):
        on = [p for p in close if p["line"] == line]
        row(line, [p for p in on if pair_outcome(p)], sum(1 for p in on if pair_outcome(p) is None))
    one = [p for p in found if pair_outcome(p) in ("earlier vanished", "later vanished")]
    print(f"Pairs one of which vanished ({len(one)}), the one that vanished against the other:", file=out)

    def tally(signal):
        mine = [p[signal][v] for p in one for v in (p["earlier"], p["later"]) if p["vanished"][v]]
        other = [p[signal][v] for p in one for v in (p["earlier"], p["later"]) if not p["vanished"][v]]

        def share(values):
            known = [v for v in values if v is not None]
            return f"{sum(known)} of {len(known)}"
        return share(mine), share(other)

    mine, other = tally("stalled")
    print(f"  stopped counting down in its last {STALL_WINDOW.seconds} s listed:"
          f" vanished {mine}, the other {other}", file=out)
    if found and found[0]["same_place"] is not None:
        mine, other = tally("blank")
        print(f"  no location at its last listing: vanished {mine}, the other {other}", file=out)
        both_came = [p for p in found if pair_outcome(p) == "both came"]
        print(f"  both at one location at some poll: {sum(p['same_place'] for p in one)} of {len(one)}"
              f" (both came: {sum(p['same_place'] for p in both_came)} of {len(both_came)})", file=out)
    if two_lines is not None:
        print("Trains listed on two lines at once (left out above), by line: listings, settled,"
              " vanished:", file=out)
        for line in sorted({visit[0][1] for visit in two_lines}):
            on = [vanished for visit, vanished in two_lines.items() if visit[0][1] == line]
            settled = [v for v in on if v is not None]
            print(f"  {line:>18}{len(on):>10}{len(settled):>10}{sum(settled):>10}", file=out)


def report(result, gaps_by_platform, out=sys.stdout, lines=None):
    def label(h):
        low, high = h
        return f"{low // 60}-{high // 60} min" if high else f"{low // 60}+ min"

    print("Mean absolute error (s), mean signed error (s), predictions — against when trains seen", file=out)
    print("\"At Platform\" got there, by how far out the train was:", file=out)
    for method, data in result.items():
        print(f"  {method}", file=out)
        for h in HORIZONS:
            errors = data["errors"].get(h, [])
            if not errors:
                continue
            mae = sum(abs(e) for e in errors) / len(errors)
            bias = sum(errors) / len(errors)
            print(f"    {label(h):>9}: {mae:6.0f} {bias:+7.0f} {len(errors):6d}", file=out)
        print(f"    shown in the next 5 min: {data['shown']}, came: {data['came']}", file=out)
    if lines:
        print("By line: shown in the next 5 min against came, mean signed error (s), mean absolute"
              " error (s):", file=out)
        for line, line_result in lines.items():
            print(f"  {line}", file=out)
            for method, data in line_result.items():
                errors = [e for es in data["errors"].values() for e in es]
                over = f"{data['shown'] / data['came'] - 1:+.0%}" if data["came"] else "n/a"
                if errors:
                    bias = f"{sum(errors) / len(errors):+.0f}"
                    mae = f"{sum(abs(e) for e in errors) / len(errors):.0f}"
                else:
                    bias = mae = "n/a"
                print(f"    {method:>12}: {data['shown']}/{data['came']} ({over}), {bias}, {mae}", file=out)
    print("Gaps between trains leaving the board (s): min, median, count — per platform:", file=out)
    for platform in sorted(gaps_by_platform):
        gaps = sorted(gaps_by_platform[platform])
        if not gaps:
            continue
        stop, line, number = platform
        print(f"  {stop} {line} P{number}: {gaps[0]:.0f}, {median(gaps):.0f}, {len(gaps)}", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    rec = sub.add_parser("record", help="poll arrivals into a JSON-lines file")
    rec.add_argument("stops", nargs="+", help="StopPoint ids, e.g. 940GZZLUEUS")
    rec.add_argument("--interval", type=float, default=20, help="seconds between polls (default 20)")
    rec.add_argument("--duration", type=float, default=3600, help="seconds to record (default 3600)")
    rec.add_argument("--out", required=True, help="file to append to")
    sc = sub.add_parser("score", help="score TfL's times and spaced times against a recording")
    sc.add_argument("recording")
    sc.add_argument("--gap", default="60,90,120,150",
                    help="spacing(s) to try, in seconds (default 60,90,120,150)")
    sc.add_argument("--arrived-within", type=float, default=60,
                    help="a train last listed this close counts as arrived (default 60 s)")
    sc.add_argument("--max-gap", type=float, default=90,
                    help="a stop's polls further apart than this break its coverage (default 90 s)")
    sc.add_argument("--mode", default="tube")
    pa = sub.add_parser("pairs", help="what became of trains shown close together at one platform")
    pa.add_argument("recording")
    pa.add_argument("--max-gap", type=float, default=90,
                    help="a stop's polls further apart than this break its coverage (default 90 s)")
    pa.add_argument("--arrived-within", type=float, default=60,
                    help="a train last listed further past its time is left unsettled (default 60 s)")
    pa.add_argument("--mode", default="tube")
    args = parser.parse_args(argv)
    if args.command == "record":
        with open(args.out, "a", encoding="utf-8") as out:
            record(args.stops, args.interval, args.duration, out)
        return 0
    with open(args.recording, encoding="utf-8") as f:
        lines = f.readlines()
    polls = load(lines, args.mode)
    if not polls:
        print(f"{args.recording}: no polls", file=sys.stderr)
        return 1
    if args.command == "pairs":
        two_lines = on_two_lines(lines, args.mode)
        pair_report(pairs(polls, args.max_gap, two_lines, locations(lines, args.mode), args.arrived_within),
                    two_lines=two_line_trains(polls, two_lines, args.max_gap, args.arrived_within))
        return 0
    gaps = [float(g) for g in args.gap.split(",") if g]
    report(
        score(polls, gaps, args.arrived_within, args.max_gap),
        headways(polls, args.arrived_within, args.max_gap),
        lines=by_line(polls, gaps, args.arrived_within, args.max_gap),
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
