#!/usr/bin/env python3
"""Tests for bods_bus_estimates.py, on a synthetic trip and recording (no network, no real stop)."""
import io
import os
import struct
import sys
import tempfile
import unittest
import unittest.mock
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bods_bus_estimates as b  # noqa: E402

LAT, LON = 51.5, -0.12
DATE = "20261030"  # GMT that day, so the service day starts at midnight UTC
START = b.service_day_start(DATE)


def north(meters):
    """A point [meters] north of a fixed synthetic origin."""
    return LAT + meters / 110540, LON


# A trip of four stops 1 km apart, timetabled 2 minutes apart from 18:00.
STOPS = {f"S{i}": north(1000 * i) for i in range(4)}
CALLS = [(f"S{i}", 18 * 3600 + 120 * i) for i in range(4)]


def bus_at(meters, when):
    """{ts: (lat, lon)} for a bus [meters] along the trip at [when] seconds after 18:00."""
    return {START + 18 * 3600 + when: north(meters)}


def running(late, every=20, until=420):
    """Positions of a bus running [late] seconds behind the timetable at 1 km per 2 minutes."""
    positions = {}
    for t in range(late, until + 1, every):
        positions.update(bus_at((t - late) * 1000 / 120, t))
    return positions


# Hand-encoded GTFS-RT, just the fields the tool reads.

def key(number, wire):
    return bytes([number << 3 | wire])


def length(number, payload):
    return key(number, 2) + varint(len(payload)) + payload


def varint(n):
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        out.append(byte | (0x80 if n else 0))
        if not n:
            return bytes(out)


def feed(*buses):
    """A FeedMessage of vehicle positions, each (trip_id, start_date, lat, lon, ts)."""
    entities = b""
    for trip, date, lat, lon, ts in buses:
        descriptor = (length(1, trip.encode()) if trip else b"") + (length(3, date.encode()) if date else b"")
        position = key(1, 5) + struct.pack("<f", lat) + key(2, 5) + struct.pack("<f", lon)
        vehicle = length(1, descriptor) + length(2, position) + key(5, 0) + varint(ts)
        entities += length(2, length(4, vehicle))
    return length(1, b"\x0a\x032.0") + entities


class FeedTest(unittest.TestCase):
    def test_reads_trip_position_and_time(self):
        [v] = b.vehicles(feed(("VJ1", DATE, LAT, LON, 1_000)))
        self.assertEqual((v["trip_id"], v["start_date"], v["ts"]), ("VJ1", DATE, 1_000))
        self.assertAlmostEqual(v["lat"], LAT, places=4)
        self.assertAlmostEqual(v["lon"], LON, places=4)

    def test_a_position_cut_short_is_an_unreadable_message(self):
        position = key(1, 5) + b"\x01"
        message = length(2, length(4, length(2, position)))
        with self.assertRaises(ValueError):
            b.vehicles(message)

    def test_bus_with_no_trip_has_no_trip_id(self):
        [v] = b.vehicles(feed(("", "", LAT, LON, 1_000)))
        self.assertNotIn("trip_id", v)


class ScoreTripTest(unittest.TestCase):
    def test_bus_on_time_is_estimated_exactly(self):
        estimates, timetable = b.score_trip(CALLS, STOPS, running(0), START)
        self.assertTrue(estimates)
        self.assertTrue(all(abs(e) < 1 for e, _ in estimates))
        self.assertTrue(all(abs(e) < 1 for e, _ in timetable))

    def test_lateness_carries_forward_but_the_timetable_misses(self):
        estimates, timetable = b.score_trip(CALLS, STOPS, running(180, until=600), START)
        self.assertTrue(all(abs(e) < 1 for e, _ in estimates))
        self.assertTrue(all(b.within_band(e, m) for e, m in estimates))
        # Three minutes late against a timetable says nothing of it: every stop comes 180 s later
        # than the timetable says, or later than now where the timetabled time has passed.
        self.assertTrue(timetable and all(e > 0 for e, _ in timetable))
        self.assertFalse(all(b.within_band(e, m) for e, m in timetable))

    def test_a_bus_that_loses_time_comes_later_than_estimated(self):
        # On time for the first kilometer, then half speed.
        positions = {}
        for t in range(0, 121, 20):
            positions.update(bus_at(t * 1000 / 120, t))
        for t in range(140, 841, 20):
            positions.update(bus_at(1000 + (t - 120) * 500 / 120, t))
        estimates, _ = b.score_trip(CALLS, STOPS, positions, START)
        early = [e for e, m in estimates if m > 2]
        self.assertTrue(early and all(e > 0 for e in early))

    def test_a_position_polled_late_estimates_only_stops_still_ahead_then(self):
        delay = 100
        late_feed = {ts: (*where, ts + delay) for ts, where in running(0).items()}
        estimates, timetable = b.score_trip(CALLS, STOPS, late_feed, START)
        on_time, _ = b.score_trip(CALLS, STOPS, running(0), START)
        self.assertTrue(estimates)
        self.assertLess(len(estimates), len(on_time))
        # Each estimate was made when its position was polled: none for a stop the bus had passed
        # by then, and minutes out counted from then (the furthest, 6 min out from the first
        # position, is 100 s nearer when that position arrives).
        self.assertTrue(all(abs(error) < 1 and minutes >= 0 for error, minutes in estimates + timetable))
        self.assertAlmostEqual(max(m for _, m in estimates), max(m for _, m in on_time) - delay / 60)

    def test_a_trip_back_along_the_same_road_is_followed_onward(self):
        # Out 1 km and back: its stops are where the straight trip's first two are.
        stops = {"A": north(0), "B": north(1000), "C": north(0)}
        calls = [("A", 18 * 3600), ("B", 18 * 3600 + 120), ("C", 18 * 3600 + 240)]
        positions = {}
        for t in range(0, 241, 20):
            d = t * 1000 / 120
            positions.update(bus_at(d if d <= 1000 else 2000 - d, t))
        there_and_back, _ = b.score_trip(calls, stops, positions, START)
        straight, _ = b.score_trip(CALLS[:3], STOPS, running(0, until=240), START)
        self.assertEqual(len(there_and_back), len(straight))
        self.assertTrue(all(abs(e) < 1 for e, _ in there_and_back))

    def test_positions_too_far_apart_place_no_passing_time(self):
        sparse = {}
        for t in (0, 200, 400):
            sparse.update(bus_at(t * 1000 / 120, t))
        self.assertEqual(b.score_trip(CALLS, STOPS, sparse, START), ([], []))

    def test_a_position_off_the_route_is_ignored(self):
        positions = running(0)
        stray = START + 18 * 3600 + 10
        positions[stray] = (LAT, LON + 0.01)  # about 700 m east
        self.assertEqual(b.score_trip(CALLS, STOPS, positions, START),
                         b.score_trip(CALLS, STOPS, running(0), START))

    def test_a_trip_with_an_unknown_stop_is_not_scored(self):
        self.assertEqual(b.score_trip(CALLS, {"S0": STOPS["S0"]}, running(0), START), ([], []))

    def test_estimates_beyond_the_horizon_are_left_out(self):
        long_calls = [(s, 18 * 3600 + 900 * i) for i, (s, _) in enumerate(CALLS)]
        positions = {}
        for t in range(0, 2701, 20):
            positions.update(bus_at(t * 1000 / 900, t))
        estimates, _ = b.score_trip(long_calls, STOPS, positions, START)
        self.assertTrue(estimates)
        self.assertTrue(all(m <= b.HORIZON / 60 for _, m in estimates))


class BandTest(unittest.TestCase):
    def test_band_widens_with_time_out_and_allows_more_late(self):
        self.assertTrue(b.within_band(20, 0))
        self.assertFalse(b.within_band(-20, 0))
        self.assertTrue(b.within_band(140, 10))
        self.assertFalse(b.within_band(150, 10))


class ServiceDayTest(unittest.TestCase):
    def test_gmt_day_starts_at_midnight_utc(self):
        self.assertEqual(b.service_day_start("20261030") % 86400, 0)

    def test_bst_day_starts_an_hour_earlier_in_utc(self):
        self.assertEqual(b.service_day_start("20260701") % 86400, 23 * 3600)


class RecordingTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def write_poll(self, polled, *buses):
        with open(os.path.join(self.dir.name, f"rt-{polled}.pb"), "wb") as f:
            f.write(feed(*buses))

    def test_stale_and_tripless_positions_are_counted_but_not_tracked(self):
        self.write_poll(1_000, ("VJ1", DATE, LAT, LON, 990), ("VJ2", DATE, LAT, LON, 800), ("", "", LAT, LON, 995))
        self.write_poll(1_020, ("VJ1", DATE, LAT, LON, 990))
        tracks, counts = b.load_tracks(self.dir.name)
        self.assertEqual(list(tracks), [("VJ1", DATE)])
        self.assertEqual((counts["positions"], counts["fresh"], counts["with_trip"]), (4, 3, 2))
        # A position repeated in a later poll keeps the first poll that held it.
        self.assertEqual(tracks[("VJ1", DATE)][990][2], 1_000)

    def test_an_unreadable_poll_is_skipped(self):
        with open(os.path.join(self.dir.name, "rt-1000.pb"), "wb") as f:
            f.write(b"\x0a\xff")
        self.write_poll(1_020, ("VJ1", DATE, LAT, LON, 1_010))
        with unittest.mock.patch("sys.stderr", io.StringIO()):
            tracks, counts = b.load_tracks(self.dir.name)
        self.assertEqual(counts["polls"], 2)
        self.assertEqual(list(tracks), [("VJ1", DATE)])

    def test_score_end_to_end(self):
        for ts, position in running(0).items():
            self.write_poll(ts, ("VJ1", DATE, *position, ts))
        gtfs = os.path.join(self.dir.name, "gtfs.zip")
        with zipfile.ZipFile(gtfs, "w") as zf:
            zf.writestr("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\n" +
                        "".join(f"{s},Stop {s},{lat},{lon}\n" for s, (lat, lon) in STOPS.items()))
            zf.writestr("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" + "".join(
                f"VJ1,18:{t // 60 % 60:02d}:00,18:{t // 60 % 60:02d}:00,{s},{i}\n"
                for i, (s, t) in enumerate((s, t - 18 * 3600) for s, t in CALLS)))
        result = b.score(gtfs, self.dir.name)
        self.assertEqual((result["trips_tracked"], result["trips_scored"]), (1, 1))
        self.assertEqual(result["estimate"]["all"]["within"], 100)


class RecordTest(unittest.TestCase):
    def test_saves_each_poll_and_keeps_the_key_out_of_errors(self):
        clock = [0.0]
        calls = []

        class Response(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def opener(url, timeout):
            calls.append(url)
            if len(calls) == 2:
                raise OSError(f"failed: {url}")
            return Response(feed(("VJ1", DATE, LAT, LON, 1)))

        def sleep(s):
            clock[0] += s

        with tempfile.TemporaryDirectory() as out, \
                unittest.mock.patch.dict(os.environ, {"BODS_API_KEY": "EXAMPLE"}), \
                unittest.mock.patch("sys.stderr", new_callable=io.StringIO) as err:
            saved = b.record("-0.2,51.4,-0.1,51.6", 1, 20, out, opener, now=lambda: clock[0], sleep=sleep)
            self.assertEqual(saved, 2)
            self.assertEqual(len(os.listdir(out)), 2)
        self.assertEqual(len(calls), 3)
        self.assertIn("api_key=EXAMPLE", calls[0])
        self.assertIn("poll failed", err.getvalue())
        self.assertNotIn("EXAMPLE", err.getvalue())


if __name__ == "__main__":
    unittest.main()
