#!/usr/bin/env python3
"""Tests for arrivals_recorder.py, on synthetic recordings (no network)."""
import datetime
import http.client
import io
import json
import os
import sys
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(__file__))
import arrivals_recorder as ar  # noqa: E402

STOP = "940GZZLUEUS"
T0 = datetime.datetime(2026, 10, 12, 8, 0, 0, tzinfo=datetime.timezone.utc)


def at(seconds):
    return T0 + datetime.timedelta(seconds=seconds)


def iso(seconds):
    return at(seconds).strftime("%Y-%m-%dT%H:%M:%SZ")


def train(vehicle, due, polled, platform="Southbound - Platform 2", mode="tube", line="northern", location=""):
    return {
        "vehicleId": vehicle, "lineId": line, "platformName": platform, "destinationName": "Morden",
        "towards": "Morden via Bank", "expectedArrival": iso(due), "timeToStation": due - polled,
        "currentLocation": location, "modeName": mode,
    }


def standing(vehicle, due, polled):
    """A train TfL lists at the platform: due already, "At Platform"."""
    return train(vehicle, due, polled, location="At Platform")


def poll(polled, *arrivals, stop=STOP):
    return json.dumps({"stop": stop, "polledAt": iso(polled), "arrivals": list(arrivals)})


def tied_lines_score():
    """`score` over a circle and a district train due at once on one platform, as text."""
    def on(line, vehicle, due, t, location=""):
        return train(vehicle, due, t, line=line, location=location)
    # Spaced 120 s, whichever goes second comes at 320, outside the first poll's five minutes.
    lines = [poll(0, on("circle", "C", 200, 0), on("district", "D", 200, 0))]
    lines += [poll(t) for t in range(20, 401, 20)]
    polls = ar.load(lines)
    return sorted((line, sorted(r["spaced 120s"]["errors"].items()), r["spaced 120s"]["shown"])
                  for line, r in ar.by_line(polls, [120], 60, GAP).items())


GAP = 200  # QUEUE's polls are up to 120 s apart: one unbroken run.
KEY = (STOP, "northern", "2")
RUN = (STOP, 0)


# Three trains TfL first lists 30 s apart, which come 120 s apart: A at 60, B at 180, C at 300.
def bunched_queue():
    """Three trains TfL first lists 30 s apart (A at 60, B at 90, C at 120) that come 120 s apart.

    Polled every 20 s from 0 to 400. Until 40 TfL keeps its bunched times, then has each train's
    real one. Each stands at the platform for 20 s, listed "At Platform", then leaves the board. So
    each reaches the platform between the poll before its real time and the one at it (A at 50,
    B at 170, C at 290), and leaves the board 20 s later (A at 70, B at 190, C at 310).
    """
    trains = [("A", 60, 60), ("B", 90, 180), ("C", 120, 300)]
    lines = []
    for t in range(0, 401, 20):
        listed = [
            standing(v, real, t) if t >= real else train(v, first if t < 40 else real, t)
            for v, first, real in trains if t < real + 20
        ]
        lines.append(poll(t, *listed))
    return lines


QUEUE = bunched_queue()


class SpacedTest(unittest.TestCase):
    def test_each_train_is_spaced_from_the_one_ahead_as_moved(self):
        trains = [("A", at(0)), ("B", at(10)), ("C", at(20)), ("D", at(400))]
        moved = ar.spaced(trains, 90)
        self.assertEqual([at(0), at(90), at(180), at(400)], [t for _, t in moved])
        self.assertEqual(["A", "B", "C", "D"], [v for v, _ in moved])

    def test_trains_already_far_enough_apart_are_left(self):
        trains = [("A", at(0)), ("B", at(120))]
        self.assertEqual(trains, ar.spaced(trains, 90))


class LoadTest(unittest.TestCase):
    def test_only_numbered_platforms_of_the_mode(self):
        polls = ar.load([poll(
            100,
            train("A", 160, 100),
            train("B", 90, 100),  # listed after its time: kept, it was still there
            train("C", 170, 100, platform="Southbound"),
            train("D", 180, 100, mode="bus", platform="Stop B Platform 1"),
            train("E", 150, 100),
        )])
        self.assertEqual(1, len(polls))
        polled_at, stop, platforms, _, _, _ = polls[0]
        self.assertEqual((at(100), STOP), (polled_at, stop))
        self.assertEqual({KEY: [("B", at(90)), ("E", at(150)), ("A", at(160))]}, dict(platforms))
        self.assertEqual([("E", at(150)), ("A", at(160))], ar.upcoming(platforms[KEY], at(100)))

    def test_a_train_listed_on_two_platforms_at_once_keeps_its_places_without_an_id(self):
        lines = [poll(
            0,
            train("A", 60, 0, platform="Platform 1"),
            train("A", 60, 0, platform="Platform 2"),
            train("B", 90, 0, platform="Platform 2"),
            train("A", 70, 0, platform="Platform 3", line="victoria"),
        )]
        platforms = ar.load(lines)[0][2]
        self.assertEqual([("", at(60))], platforms[(STOP, "northern", "1")])
        self.assertEqual([("", at(60)), ("B", at(90))], platforms[KEY])
        # The same id on another line is another train.
        self.assertEqual([("A", at(70))], platforms[(STOP, "victoria", "3")])

    def test_a_train_listed_twice_on_one_platform_keeps_one_place_without_an_id(self):
        platforms = ar.load([poll(0, train("A", 60, 0), train("A", 90, 0), train("B", 120, 0))])[0][2]
        self.assertEqual([("", at(60)), ("B", at(120))], platforms[KEY])

    def test_a_train_listed_twice_holds_up_the_trains_behind_and_voids_the_counts(self):
        # A is listed twice ahead of B. Spaced, B goes behind A's place, not straight to the front;
        # and no window or gap covering A is counted, since whether A came isn't known.
        lines = [poll(0, train("A", 30, 0), train("A", 40, 0), train("B", 250, 0))]
        lines += [poll(t, train("B", 250, t)) for t in range(20, 241, 20)]
        lines += [poll(t, standing("B", 250, t)) for t in (260, 280)]
        lines += [poll(t) for t in range(300, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual([("", at(30)), ("B", at(250))], polls[0][2][KEY])
        self.assertEqual([("", at(30)), ("B", at(250))], ar.spaced(polls[0][2][KEY], 120))
        self.assertEqual([("", at(30)), ("B", at(150))], ar.spaced([("", at(30)), ("B", at(50))], 120))
        result = ar.score(polls, [120], 60, GAP)
        # A might have come any time to 100 (due at 40, 60 s slack), so windows from 0 to 80 aren't
        # counted. From 100 to 240 B, reaching the platform at 250, is shown and comes each time.
        self.assertEqual((8, 8), (result["raw"]["shown"], result["raw"]["came"]))

    def test_a_visit_later_listed_on_two_platforms_has_no_arrival(self):
        lines = [
            poll(0, train("A", 50, 0, platform="Platform 1")),
            poll(30, train("A", 50, 30, platform="Platform 1"), train("A", 50, 30, platform="Platform 2")),
            poll(60), poll(90),
        ]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_moved_to_another_platform_has_no_arrival(self):
        lines = [
            poll(0, train("A", 50, 0, platform="Platform 1")),
            poll(20, train("A", 50, 20, platform="Platform 2")),
            poll(40, train("A", 50, 40, platform="Platform 2")),
            poll(60), poll(80),
        ]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_with_no_mode_takes_its_lines(self):
        lines = [poll(0, train("A", 60, 0)), poll(20, train("A", 60, 20, mode=""))]
        polls = ar.load(lines)
        self.assertEqual([("A", at(60))], polls[1][2][KEY])
        # With no train of its line to say, it's left out.
        self.assertEqual({}, dict(ar.load([poll(0, train("A", 60, 0, mode=""))])[0][2]))

    def test_one_platform_named_with_and_without_its_direction_is_one_platform(self):
        polls = ar.load([poll(0, train("A", 60, 0), train("B", 90, 0, platform="Platform 2"))])
        self.assertEqual([("A", at(60)), ("B", at(90))], polls[0][2][KEY])


class ActualTest(unittest.TestCase):
    def test_a_train_seen_arriving_takes_its_last_time(self):
        actual = ar.actual_arrivals(ar.load(QUEUE), 60, GAP)
        self.assertEqual({(KEY, RUN, "A", 0): at(70), (KEY, RUN, "B", 0): at(190), (KEY, RUN, "C", 0): at(310)}, actual)
        # Where TfL said it was at the platform, that's when it came.
        arrived = ar.platform_arrivals(ar.load(QUEUE), 60, GAP)
        self.assertEqual({(KEY, RUN, "A", 0): at(50), (KEY, RUN, "B", 0): at(170), (KEY, RUN, "C", 0): at(290)}, arrived)

    def test_a_train_dropped_while_far_out_or_still_listed_at_the_end_has_none(self):
        lines = [
            poll(0, train("A", 400, 0), train("B", 500, 0)),
            poll(20, train("B", 500, 20)),
        ]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_each_stops_last_poll_settles_nothing(self):
        # Two stops polled a second apart: Euston's last poll isn't the recording's last line.
        other = "940GZZLUKSX"
        lines = [
            poll(0, train("A", 50, 0)), poll(1, stop=other),
            poll(20, train("A", 50, 20)), poll(21, stop=other),
        ]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_break_in_polling_settles_nothing_before_it(self):
        # Two recordings appended: A was 30 s out when the first ended.
        lines = [poll(0, train("A", 60, 0)), poll(30, train("A", 60, 30)), poll(3600), poll(3620)]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, 90))
        # Joined into one run, A left the board somewhere in the hour between 30 and 3600.
        self.assertEqual({(KEY, RUN, "A", 0): at(1815)}, ar.actual_arrivals(ar.load(lines), 60, 4000))

    def test_a_train_coming_back_on_a_later_trip_is_another_visit(self):
        # A comes at 60, is gone for 20 minutes of polling, then comes again at 1300.
        lines = [poll(t, train("A", 60, t)) for t in (0, 40)] + [poll(60, standing("A", 60, 60))]
        lines += [poll(t) for t in range(80, 1201, 60)]
        lines += [poll(t, train("A", 1300, t)) for t in (1240, 1280)] + [poll(1300, standing("A", 1300, 1300))]
        lines += [poll(1340)]
        actual = ar.actual_arrivals(ar.load(lines), 60, GAP)
        self.assertEqual({(KEY, RUN, "A", 0): at(70), (KEY, RUN, "A", 1): at(1320)}, actual)
        # Each listing is scored against its own visit's arrival (50, then 1290), not the other's.
        result = ar.score(ar.load(lines), [], 60, GAP)
        self.assertEqual([10.0, 10.0, 10.0, 10.0], result["raw"]["errors"][(0, 120)])

    def test_a_train_never_seen_at_the_platform_counts_but_isnt_scored(self):
        # A is never seen at the platform; B, later, stands there for 40 s.
        lines = [poll(t, train("A", 60, t), train("B", 200, t)) for t in (0, 20, 40)]
        lines += [poll(t, train("B", 200, t)) for t in range(60, 181, 20)]
        lines += [poll(t, standing("B", 200, t)) for t in (200, 220)]
        lines += [poll(t) for t in range(240, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual(at(50), ar.actual_arrivals(polls, 60, GAP)[(KEY, RUN, "A", 0)])
        self.assertNotIn((KEY, RUN, "A", 0), ar.platform_arrivals(polls, 60, GAP))
        # Taken to have reached the platform B's 40 s before leaving the board.
        self.assertEqual(at(10), ar.arrival_estimates(polls, 60, GAP)[(KEY, RUN, "A", 0)])
        result = ar.score(polls, [], 60, GAP, window=150)
        # Every error is B's: predicted 200, at the platform at 190.
        self.assertEqual({10.0}, {e for errors in result["raw"]["errors"].values() for e in errors})
        self.assertGreater(result["raw"]["came"], 0)

    def test_a_borrowed_dwell_never_puts_an_arrival_before_the_first_listing(self):
        # A stands for 60 s; B, never seen at the platform, is first listed at 200 and leaves the
        # board at 250: taking A's dwell would put it at 190, before anything listed it.
        lines = [poll(0, train("A", 20, 0))]
        lines += [poll(t, standing("A", 20, t)) for t in range(20, 61, 20)]
        lines += [poll(t) for t in range(80, 181, 20)]
        lines += [poll(t, train("B", 240, t)) for t in (200, 220, 240)]
        lines += [poll(t) for t in range(260, 601, 20)]
        estimates = ar.arrival_estimates(ar.load(lines), 60, GAP)
        self.assertEqual(at(10), estimates[(KEY, RUN, "A", 0)])
        self.assertEqual(at(200), estimates[(KEY, RUN, "B", 0)])

    def test_a_train_seen_at_the_platform_is_scored_however_it_left_the_board(self):
        # A reaches the platform at 110 but TfL keeps it due at 400 and lists it to the end of the
        # run: it never settles, but its arrival was seen.
        lines = [poll(t, train("A", 400, t)) for t in range(0, 101, 20)]
        lines += [poll(t, train("A", 400, t, location="At Platform")) for t in range(120, 201, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.actual_arrivals(polls, 60, GAP))
        self.assertEqual({(KEY, RUN, "A", 0): at(110)}, ar.platform_arrivals(polls, 60, GAP))
        errors = ar.score(polls, [], 60, GAP)["raw"]["errors"]
        # Predicted for 400 from every poll before it arrived: 290 s late each time.
        self.assertEqual([290.0] * 6, [e for es in errors.values() for e in es])

    def test_a_train_already_at_the_platform_when_its_run_starts_has_no_arrival(self):
        lines = [poll(t, standing("A", 0, t)) for t in (0, 20, 40)] + [poll(t) for t in range(60, 401, 20)]
        self.assertEqual({}, ar.platform_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_already_at_the_platform_when_its_run_starts_came_in_no_window(self):
        # A stands at the platform from the first poll; B, later, gives a dwell to borrow. A
        # mustn't be taken to arrive at the run's start and count as come in its first window.
        lines = [poll(t, standing("A", 0, t), train("B", 200, t)) for t in (0, 20, 40)]
        lines += [poll(t, train("B", 200, t)) for t in range(60, 181, 20)]
        lines += [poll(t, standing("B", 200, t)) for t in (200, 220)]
        lines += [poll(t) for t in range(240, 701, 20)]
        polls = ar.load(lines)
        self.assertNotIn((KEY, RUN, "A", 0), ar.arrival_estimates(polls, 60, GAP))
        result = ar.score(polls, [], 60, GAP, window=150)
        # Only B comes: in the windows from 60 to 180 (it reaches the platform at 190).
        self.assertEqual(7, result["raw"]["came"])

    def test_two_trains_at_one_platform_at_once_are_both_void(self):
        # A and its copy B are both "At Platform" at 60: one platform holds one train, so neither
        # is taken as an arrival, and the time they might have come isn't counted.
        lines = [poll(t, train("A", 60, t), train("B", 70, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), standing("B", 70, 60))]
        lines += [poll(80, standing("B", 70, 80))]
        lines += [poll(t) for t in range(100, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({}, ar.actual_arrivals(polls, 60, GAP))
        self.assertTrue(ar.unresolved(polls, 60, GAP)[(KEY, RUN)])
        # Both keep their places in the queue.
        self.assertEqual(["A", "B"], [v for v, _ in polls[0][2][KEY]])

    def test_a_train_at_a_platform_beside_one_with_no_id_is_void(self):
        lines = [poll(t, train("A", 60, t), train("000", 70, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), train("000", 70, 60, location="At Platform"))]
        lines += [poll(t) for t in range(80, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({}, ar.actual_arrivals(polls, 60, GAP))

    def test_a_train_at_the_platform_just_after_another_is_void_with_it(self):
        # A stands at 60; B, its copy, stands at 80 once A has gone. Under 90 s apart however the
        # polls are read (40 to 80), so neither is taken as an arrival.
        lines = [poll(t, train("A", 60, t), train("B", 75, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), train("B", 75, 60))]
        lines += [poll(80, standing("B", 75, 80))]
        lines += [poll(t) for t in range(100, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({}, ar.actual_arrivals(polls, 60, GAP))
        self.assertTrue(ar.unresolved(polls, 60, GAP)[(KEY, RUN)])
        # Trains reaching the platform a real headway apart are both kept (QUEUE's are 120 s).
        self.assertEqual(3, len(ar.platform_arrivals(ar.load(QUEUE), 60, GAP)))

    def test_a_train_at_the_platform_just_after_two_at_once_is_void(self):
        # A and B are both "At Platform" at 60; C, under 90 s after them, at 80. C can't be taken
        # as real any more than they can.
        lines = [poll(t, train("A", 60, t), train("B", 65, t), train("C", 75, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), standing("B", 65, 60), train("C", 75, 60))]
        lines += [poll(80, standing("C", 75, 80))]
        lines += [poll(t) for t in range(100, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({}, ar.actual_arrivals(polls, 60, GAP))

    def test_a_train_at_a_platform_beside_one_listed_twice_is_void(self):
        lines = [poll(t, train("A", 60, t), train("A", 65, t), train("B", 70, t)) for t in (0, 20, 40)]
        # A, listed twice, and B are both "At Platform" at 60: B can't be taken as an arrival.
        lines += [poll(60, standing("A", 60, 60), train("A", 65, 60), standing("B", 70, 60))]
        lines += [poll(t) for t in range(80, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))

    def test_a_train_at_the_platform_just_after_one_with_no_id_is_void(self):
        # A train with no id stands at 60; B, under 90 s after it, at 80. B can't be taken as real.
        lines = [poll(t, train("000", 60, t), train("B", 75, t)) for t in (0, 20, 40)]
        lines += [poll(60, train("000", 60, 60, location="At Platform"), train("B", 75, 60))]
        lines += [poll(80, standing("B", 75, 80))]
        lines += [poll(t) for t in range(100, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({}, dict(ar.score(polls, [], 60, GAP)["raw"]["errors"]))

    def test_with_no_dwell_seen_anywhere_no_window_a_train_came_in_is_counted(self):
        lines = [poll(t, train("A", 60, t)) for t in (0, 20, 40)] + [poll(t) for t in range(60, 401, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.arrival_estimates(polls, 60, GAP))
        result = ar.score(polls, [], 60, GAP, window=150)
        # A reached the platform sometime between 0 and 50: windows from 0 to 40 might hold it.
        # From 60 on the windows are counted, and empty.
        self.assertEqual((0, 0), (result["raw"]["shown"], result["raw"]["came"]))

    def test_a_train_dropped_for_a_poll_is_still_one_visit(self):
        lines = [poll(0, train("A", 100, 0)), poll(30), poll(60, train("A", 100, 60)), poll(120)]
        self.assertEqual({(KEY, RUN, "A", 0): at(90)}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_last_listed_long_overdue_has_none(self):
        # Still listed for its 60 s time at 300, then dropped: when it came isn't known.
        lines = [poll(0, train("A", 60, 0)), poll(300, train("A", 60, 300)), poll(340)]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, 400))

    def test_a_train_gone_before_it_was_due_has_none(self):
        # Due at 60 when last listed at 0, but already gone at 20: it may have been withdrawn.
        lines = [poll(0, train("A", 60, 0)), poll(20), poll(80)]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_still_listed_overdue_at_the_end_has_none(self):
        lines = [poll(0, train("A", 30, 0)), poll(40, train("A", 30, 40))]
        self.assertEqual({}, ar.actual_arrivals(ar.load(lines), 60, GAP))

    def test_a_train_id_reused_in_a_later_recording_is_another_train(self):
        # A arrives at 60 in the first recording; an hour later a train with the same id is listed
        # and the recording ends before it comes.
        lines = [
            poll(0, train("A", 60, 0)), poll(30, train("A", 60, 30)), poll(90),
            poll(3600, train("A", 3700, 3600)), poll(3620, train("A", 3700, 3620)),
        ]
        self.assertEqual({(KEY, RUN, "A", 0): at(60)}, ar.actual_arrivals(ar.load(lines), 60, 90))


class ScoreTest(unittest.TestCase):
    def test_spacing_beats_the_raw_times_on_a_bunched_queue(self):
        result = ar.score(ar.load(QUEUE), [120], 60, GAP)
        raw = [abs(e) for errors in result["raw"]["errors"].values() for e in errors]
        spaced = [abs(e) for errors in result["spaced 120s"]["errors"].values() for e in errors]
        self.assertEqual(len(raw), len(spaced))
        self.assertGreater(len(raw), 0)
        self.assertLess(sum(spaced), sum(raw))
        # From the first poll, raw has B at 90 when it left the board at 170: 80 s early.
        self.assertIn(-80.0, result["raw"]["errors"][(120, 300)])
        # Spaced, it's at 180: 10 s late, the half poll the board's truth is fuzzy by.
        self.assertIn(10.0, result["spaced 120s"]["errors"][(120, 300)])

    def test_trains_shown_soon_against_trains_that_came(self):
        result = ar.score(ar.load(QUEUE), [120], 60, GAP, window=150)
        # At 0 raw shows A, B and C inside 150 s, where only A came; spaced shows only A.
        raw, spaced = result["raw"], result["spaced 120s"]
        self.assertEqual(raw["came"], spaced["came"])
        self.assertGreater(raw["shown"], spaced["shown"])
        self.assertEqual(spaced["came"], spaced["shown"])

    def test_a_train_dropped_far_out_counts_as_shown_but_not_come(self):
        lines = [poll(0, train("A", 60, 0), train("X", 100, 0)), poll(40, standing("A", 40, 40)), poll(80)]
        lines += [poll(t) for t in range(160, 401, 80)]
        result = ar.score(ar.load(lines), [], 60, GAP)
        self.assertEqual((2, 1), (result["raw"]["shown"], result["raw"]["came"]))

    def test_a_train_not_yet_listed_still_counts_as_come(self):
        # At 0 only A is listed; B appears at 60 and comes at 100, inside 0's window.
        lines = [
            poll(0, train("A", 50, 0)),
            poll(40, standing("A", 50, 40)),
            poll(60, train("B", 100, 60)),
            poll(90, standing("B", 100, 90)),
        ] + [poll(t) for t in range(120, 481, 60)]
        result = ar.score(ar.load(lines), [], 60, GAP, window=150)
        # A reaches the platform at 20, B at 75. Counted polls: 0 (A, B came), 40 (B), 60 (B),
        # 90, and the empty ones to 330 (none).
        self.assertEqual(2 + 1 + 1, result["raw"]["came"])
        # Shown: A at 0 and B at 60. At 40 and 90 each is already at the platform, so not shown.
        self.assertEqual(1 + 1, result["raw"]["shown"])

    def test_a_train_reaching_the_platform_inside_a_window_came_in_it(self):
        # A reaches the platform at 130 (first "At Platform" at 140) and leaves the board at 170:
        # inside the window from 0, though it left the board after it.
        lines = [poll(t, train("A", 135, t)) for t in range(0, 121, 20)]
        lines += [poll(140, standing("A", 135, 140)), poll(160, standing("A", 135, 160))]
        lines += [poll(t) for t in range(180, 401, 20)]
        polls = ar.load(lines)
        self.assertEqual(at(130), ar.arrival_estimates(polls, 60, GAP)[(KEY, RUN, "A", 0)])
        result = ar.score(polls, [], 60, GAP, window=150)
        # Shown and come from 0 to 120: seven polls.
        self.assertEqual((7, 7), (result["raw"]["shown"], result["raw"]["came"]))

    def test_a_train_not_seen_at_the_platform_is_taken_to_dwell_as_others_did(self):
        # A is seen standing for 20 s; B is never seen at the platform and leaves the board at 290.
        lines = [
            poll(0, train("A", 50, 0), train("B", 280, 0)),
            poll(40, train("A", 50, 40), train("B", 280, 40)),
            poll(60, standing("A", 50, 60), train("B", 280, 60)),
            poll(80, train("B", 280, 80)),
        ] + [poll(t, train("B", 280, t)) for t in range(100, 281, 20)] + [poll(t) for t in range(300, 501, 20)]
        estimates = ar.arrival_estimates(ar.load(lines), 60, GAP)
        # A arrived at 50 and left the board at 70: a 20 s dwell, so B is taken to arrive at 270.
        self.assertEqual(at(50), estimates[(KEY, RUN, "A", 0)])
        self.assertEqual(at(270), estimates[(KEY, RUN, "B", 0)])

    def test_a_train_with_no_id_voids_what_it_might_come_in_after_its_listing(self):
        # A train with no id is listed once, at 0, due at 200, then dropped. It might still come
        # up to 260, so a window or a gap that overlaps 0 to 260 isn't counted, though no later
        # poll lists it.
        lines = [poll(0, train("000", 200, 0), train("A", 100, 0))]
        lines += [poll(t, train("A", 100, t)) for t in range(20, 81, 20)]
        lines += [poll(100, standing("A", 100, 100))]
        lines += [poll(t, train("B", 300, t)) for t in range(120, 281, 20)]
        lines += [poll(300, standing("B", 300, 300))]
        lines += [poll(t) for t in range(320, 801, 20)]
        polls = ar.load(lines)
        self.assertEqual({(KEY, RUN): [(at(0), at(260))]}, dict(ar.unresolved(polls, 60, GAP)))
        self.assertEqual([], ar.headways(polls, 60, GAP)[KEY])
        result = ar.score(polls, [], 60, GAP, window=150)
        # Counted from 260 to 640: B, due at 300 and reaching the platform at 290, shown at 260
        # and 280 and coming in both those windows.
        self.assertEqual((2, 2), (result["raw"]["shown"], result["raw"]["came"]))

    def test_a_train_moved_to_another_platform_voids_what_it_might_come_in_on_both(self):
        # X is listed on platform 1, then moved to platform 2; whether and where it came isn't
        # known, so the gap on platform 2 between A and B, which X may have come in, isn't counted.
        p1 = "Platform 1"
        lines = [poll(0, train("A", 100, 0), train("X", 200, 0, platform=p1))]
        lines += [poll(t, train("A", 100, t), train("X", 200, t)) for t in range(20, 81, 20)]
        lines += [poll(100, standing("A", 100, 100), train("X", 200, 100))]
        lines += [poll(t, train("X", 200, t), train("B", 300, t)) for t in range(120, 181, 20)]
        lines += [poll(t, train("B", 300, t)) for t in range(200, 281, 20)]
        lines += [poll(300, standing("B", 300, 300))]
        lines += [poll(t) for t in range(320, 801, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, {v: a for v, a in ar.actual_arrivals(polls, 60, GAP).items() if v[2] == "X"})
        # On each platform, from its first listing there to a minute after it was due at 200.
        self.assertEqual([(at(20), at(260))], ar.unresolved(polls, 60, GAP)[(KEY, RUN)])
        self.assertEqual([(at(0), at(260))], ar.unresolved(polls, 60, GAP)[((STOP, "northern", "1"), RUN)])
        self.assertEqual([], ar.headways(polls, 60, GAP)[KEY])

    def test_a_train_listed_twice_might_come_as_late_as_its_later_time(self):
        # A is listed at 30 and at 300 on one platform, then dropped: its place in the queue is
        # at 30, but it might come until a minute after 300.
        lines = [poll(0, train("A", 30, 0), train("A", 300, 0))] + [poll(t) for t in range(20, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual([("", at(30))], polls[0][2][KEY])
        self.assertEqual(at(360), max(b for _, b in ar.unresolved(polls, 60, GAP)[(KEY, RUN)]))

    def test_no_gap_spans_a_train_seen_at_the_platform_that_didnt_settle(self):
        # A and C leave the board; B, between them, is seen at the platform but its stale time
        # (due at 600) keeps it from settling. A-to-C isn't a gap between consecutive trains.
        lines = [poll(t, train("A", 60, t), train("B", 600, t)) for t in range(0, 41, 20)]
        lines += [poll(60, standing("A", 60, 60), train("B", 600, 60))]
        lines += [poll(t, train("B", 600, t)) for t in range(80, 121, 20)]
        lines += [poll(t, train("B", 600, t, location="At Platform"), train("C", 300, t)) for t in range(140, 181, 20)]
        lines += [poll(t, train("C", 300, t)) for t in range(200, 281, 20)]
        lines += [poll(300, standing("C", 300, 300))]
        lines += [poll(t) for t in range(320, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual({"A", "C"}, {v[2] for v in ar.actual_arrivals(polls, 60, GAP)})
        self.assertIn((KEY, RUN, "B", 0), ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual([], ar.headways(polls, 60, GAP)[KEY])

    def test_a_train_already_at_the_platform_holds_the_queue_but_isnt_shown(self):
        # A stands at the platform from 60 still due at 100; B is due at 110 behind it.
        lines = [poll(t, train("A", 100, t), train("B", 110, t)) for t in (0, 20, 40)]
        lines += [poll(t, train("A", 100, t, location="At Platform"), train("B", 110, t)) for t in (60, 80)]
        lines += [poll(t, train("B", 230, t)) for t in range(100, 221, 20)]
        lines += [poll(240, standing("B", 230, 240))]
        lines += [poll(t) for t in range(260, 701, 20)]
        polls = ar.load(lines)
        # At 60, spaced 120 s, B goes behind A's place: A still holds the queue.
        self.assertEqual(at(220), ar.spaced(ar.upcoming(polls[3][2][KEY], at(60)), 120)[1][1])
        result = ar.score(polls, [], 60, GAP, window=150)
        # Shown at 0, 20 and 40: A and B each time. At 60 and 80 only B: A is already there.
        # B, due later from 100, is shown from 100 to 220 too (7 polls).
        self.assertEqual(3 * 2 + 2 + 7, result["raw"]["shown"])

    def test_a_train_at_the_platform_past_its_time_still_holds_the_queue(self):
        # A stands at the platform from 60, due at 60; B, due at 90 behind it, comes at 190.
        lines = [poll(t, train("A", 60, t), train("B", 90, t)) for t in (0, 20, 40)]
        lines += [poll(t, standing("A", 60, t), train("B", 90, t)) for t in (60, 80)]
        lines += [poll(t, train("B", 200, t)) for t in range(100, 181, 20)]
        lines += [poll(t, standing("B", 200, t)) for t in (200, 220)]
        lines += [poll(t) for t in range(240, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual([("A", at(60)), ("B", at(90))], ar.upcoming(polls[4][2][KEY], at(80), {"A"}))
        errors = ar.score(polls, [120], 60, GAP)["spaced 120s"]["errors"]
        # At 60 and 80, B goes 120 s behind A (180), not at its own 90: 10 s early, not 100.
        self.assertEqual([-10] * 4, errors[(120, 300)])
        self.assertNotIn(-100, errors[(0, 120)])

    def test_a_train_due_now_still_in_the_queue(self):
        # A is due at 80, the poll's time, and not yet "At Platform": a board shows it as due, so
        # it stays in the queue and holds back B behind it.
        queue = ar.upcoming([("A", at(80)), ("B", at(90))], at(80))
        self.assertEqual([("A", at(80)), ("B", at(90))], queue)
        self.assertEqual(at(200), ar.spaced(queue, 120)[1][1])

    def test_a_train_at_the_platform_goes_first_whatever_time_it_still_has(self):
        # A stands at the platform still given 300; B, behind it, is due at 90. A is there now, so
        # it leads at 80, not at the time TfL still gives it.
        queue = ar.upcoming([("B", at(90)), ("A", at(300))], at(80), {"A"})
        self.assertEqual([("A", at(80)), ("B", at(90))], queue)
        self.assertEqual(at(200), ar.spaced(queue, 120)[1][1])

    def test_only_one_train_named_at_the_platform_leads_and_the_rest_are_left_out(self):
        # A and C are both named there; one platform holds one train, so A, the soonest, leads and
        # C, a copy or a stale listing, holds nothing back.
        queue = ar.upcoming([("B", at(90)), ("A", at(300)), ("C", at(300))], at(80), {"A", "C"})
        self.assertEqual([("A", at(80)), ("B", at(90))], queue)

    def test_a_train_known_to_have_arrived_is_at_the_platform_though_tfl_drops_the_marker(self):
        # A reached the platform at 50 (seen there at 60); at 80 TfL lists it again due at 300 with
        # no marker. B is due at 310.
        lines = [poll(t, train("A", 60, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60))]
        lines += [poll(80, train("A", 300, 80), train("B", 310, 80))]
        lines += [poll(t) for t in range(100, 501, 20)]
        polls = ar.load(lines)
        seen = ar.platform_arrivals(polls, 60, GAP)
        visit_of = ar.visits(polls, GAP)
        there = ar.at_the_platform(polls[4], KEY, visit_of, 4, ar.already_there(polls, GAP), seen)
        self.assertEqual({"A"}, set(there))
        queue = ar.upcoming(polls[4][2][KEY], at(80), there)
        # B goes 120 s behind A being there now, not behind the 300 TfL still gives it.
        self.assertEqual(at(310), ar.spaced(queue, 120)[1][1])

    def test_a_train_at_the_platform_leads_no_sooner_than_it_was_seen_to_get_there(self):
        # A reached the platform at 130 (first "At Platform" at 140), and TfL still gives it 100 at
        # 160. B is due at 170. A leads at 130, so B spaced 120 s behind it comes at 250, not 220.
        lines = [poll(t, train("A", 100, t)) for t in range(0, 121, 20)]
        lines += [poll(140, standing("A", 100, 140))]
        lines += [poll(160, train("A", 100, 160), train("B", 170, 160))]
        lines += [poll(t) for t in range(180, 601, 20)]
        polls = ar.load(lines)
        i = next(k for k, p in enumerate(polls) if p[0] == at(160))
        there = ar.at_the_platform(polls[i], KEY, ar.visits(polls, GAP), i, ar.already_there(polls, GAP),
                                   ar.platform_arrivals(polls, 60, GAP))
        queue = ar.upcoming(polls[i][2][KEY], at(160), there)
        self.assertEqual([("A", at(130)), ("B", at(250))], ar.spaced(queue, 120))

    def test_two_trains_placed_there_at_once_still_hold_the_platform_once_the_marker_goes(self):
        # A and B are both "At Platform" at 60; at 80 both are still listed, with no marker. One of
        # them is there, so C, due at 90, goes 120 s behind it, not first.
        lines = [poll(t, train("A", 60, t), train("B", 65, t), train("C", 200, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), standing("B", 65, 60), train("C", 90, 60))]
        lines += [poll(80, train("A", 60, 80), train("B", 65, 80), train("C", 90, 80))]
        lines += [poll(t, train("C", 180, t)) for t in range(100, 181, 20)]
        lines += [poll(200, standing("C", 180, 200))]
        lines += [poll(t) for t in range(220, 601, 20)]
        polls = ar.load(lines)
        errors = ar.score(polls, [120], 60, GAP)
        # C reached the platform at 190. At 80 spaced says 180 (10 s early), not 90 (100 s early).
        self.assertNotIn(-100, errors["spaced 120s"]["errors"][(0, 120)])

    def test_a_train_listed_twice_once_at_the_platform_holds_its_track_while_still_listed(self):
        # A, listed twice, is "At Platform" at 60; at 80 both listings remain, unmarked. Whether A
        # is still there isn't known, so B, due at 90 and reaching the platform at 190, isn't
        # scored at 80 as if the platform were clear (100 s early, 110 s out).
        lines = [poll(t, train("A", 60, t), train("A", 70, t), train("B", 90, t)) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), train("A", 70, 60), train("B", 90, 60))]
        lines += [poll(80, train("A", 60, 80), train("A", 70, 80), train("B", 90, 80))]
        lines += [poll(t, train("B", 180, t)) for t in range(100, 181, 20)]
        lines += [poll(200, standing("B", 180, 200))]
        lines += [poll(t) for t in range(220, 601, 20)]
        errors = ar.score(ar.load(lines), [], 60, GAP)["raw"]["errors"]
        self.assertNotIn(-100, errors[(0, 120)])
        self.assertIn(-10, errors[(0, 120)])

    def test_a_train_with_no_id_still_listed_once_unmarked_holds_back_the_errors_behind_it(self):
        # A train with no id is "At Platform" at 60 and still listed, unmarked, at 80. B, due at 90,
        # reaches the platform at 190. Whether the train with no id is still there isn't known, so
        # B isn't scored at 80 as if the platform were clear (100 s early).
        lines = [poll(t, train("000", 60, t), train("B", 90, t)) for t in (0, 20, 40)]
        lines += [poll(60, train("000", 60, 60, location="At Platform"), train("B", 90, 60))]
        lines += [poll(80, train("000", 60, 80), train("B", 90, 80))]
        lines += [poll(t, train("B", 180, t)) for t in range(100, 181, 20)]
        lines += [poll(200, standing("B", 180, 200))]
        lines += [poll(t) for t in range(220, 601, 20)]
        errors = ar.score(ar.load(lines), [], 60, GAP)["raw"]["errors"]
        self.assertNotIn(-100, errors[(0, 120)])

    def test_an_arrival_known_only_in_hindsight_doesnt_put_a_train_at_the_platform(self):
        # A is never placed "At Platform": its arrival is estimated afterwards, from its leaving the
        # board after 140 less the dwell B showed, as 130. A live board at 140 couldn't know it was
        # there, so A, still listed then, counts as shown.
        lines = [poll(0, train("B", 20, 0)), poll(20, standing("B", 20, 20)), poll(40)]
        lines += [poll(t, train("A", 300, t)) for t in range(60, 121, 20)] + [poll(140, train("A", 150, 140))]
        lines += [poll(t) for t in range(160, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual(at(130), ar.arrival_estimates(polls, 60, GAP)[(KEY, RUN, "A", 0)])
        # B at 0, and A at each of its five listings.
        self.assertEqual(6, ar.score(polls, [], 60, GAP)["raw"]["shown"])

    def test_the_train_that_got_there_last_is_the_one_at_the_platform(self):
        # A reached the platform at 30 and its listing lingers, due at 40; B reached it at 150 and
        # is there now. B, not A, leads the queue, and C behind it is spaced from B.
        lines = [poll(t, train("A", 40, t)) for t in (0, 20)] + [poll(40, standing("A", 40, 40))]
        lines += [poll(t, train("A", 40, t), train("B", 160, t), train("C", 200, t)) for t in range(60, 141, 20)]
        lines += [poll(160, train("A", 40, 160), standing("B", 160, 160), train("C", 200, 160))]
        lines += [poll(180, train("A", 40, 180), train("B", 160, 180), train("C", 200, 180))]
        lines += [poll(t) for t in range(200, 601, 20)]
        polls = ar.load(lines)
        i = next(k for k, p in enumerate(polls) if p[0] == at(180))
        there = ar.at_the_platform(polls[i], KEY, ar.visits(polls, GAP), i, ar.already_there(polls, GAP),
                                   ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual({"A", "B"}, set(there))
        queue = ar.upcoming(polls[i][2][KEY], at(180), there)
        self.assertEqual([("B", at(160)), ("C", at(200))], queue)
        self.assertEqual(at(280), ar.spaced(queue, 120)[1][1])

    def test_two_trains_at_the_platform_at_once_still_lead_the_queue(self):
        # A and C are both "At Platform" at 60, still given 300; B approaches, due at 70. Both are
        # void, but one of them is there, so B queues behind it.
        lines = [poll(60, standing("A", 300, 60), standing("C", 300, 60), train("B", 70, 60))]
        polls = ar.load(lines)
        there = ar.at_the_platform(polls[0], KEY, ar.visits(polls, GAP), 0, set(), {})
        self.assertEqual({"A", "C"}, set(there))
        queue = ar.upcoming(polls[0][2][KEY], at(60), there)
        self.assertEqual(["A", "B"], [vehicle for vehicle, _ in queue])

    def test_only_one_train_with_no_id_is_the_one_at_the_platform(self):
        # A train with no id stands at the platform; B is due at 100, another with no id at 300.
        queue = ar.upcoming([("", at(0)), ("B", at(100)), ("", at(300))], at(0), {""})
        self.assertEqual([("", at(0)), ("B", at(100)), ("", at(300))], queue)
        self.assertEqual(at(120), ar.spaced(queue, 120)[1][1])

    def test_a_train_behind_ones_that_cant_be_followed_isnt_scored(self):
        # A train with no id is "At Platform" at 80, still given 300; another with no id is due at
        # 100, and B at 200. Either may stand at the platform until after B gets there at 210, so
        # B's error isn't scored at any poll, marked or not.
        def lines_with(occupant_location):
            lines = [poll(t, train("000", 300, t), train("000", 100, t), train("B", 200, t)) for t in (40, 60)]
            lines += [poll(80, train("000", 300, 80, location=occupant_location), train("000", 100, 80),
                           train("B", 200, 80))]
            lines += [poll(t, train("B", 200, t)) for t in range(100, 201, 20)]
            lines += [poll(220, standing("B", 200, 220))]
            return lines + [poll(t) for t in range(240, 801, 20)]
        marked = ar.score(ar.load(lines_with("At Platform")), [120], 60, GAP)
        unmarked = ar.score(ar.load(lines_with("")), [120], 60, GAP)
        count = lambda result: sum(len(errors) for errors in result["spaced 120s"]["errors"].values())
        self.assertEqual((0, 0), (count(marked), count(unmarked)))

    def test_a_train_close_behind_a_train_with_no_id_still_there_is_void(self):
        # A train with no id stands at the platform from 60 to 160: with no id, another may have
        # replaced it at any poll. B reaches it at 180, 20 s after it was last seen there.
        lines = [poll(t) for t in (0, 20, 40)]
        lines += [poll(t, train("000", t, t, location="At Platform"), train("B", 170, t)) for t in range(60, 161, 20)]
        lines += [poll(180, standing("B", 170, 180))]
        lines += [poll(t) for t in range(200, 601, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))

    def test_a_void_train_first_listed_at_the_platform_may_have_come_since_the_poll_before(self):
        # A and B are first listed at 60, both already "At Platform": both void, and either may
        # have come any time after the empty poll at 40.
        lines = [poll(t) for t in (0, 20, 40)]
        lines += [poll(60, standing("A", 60, 60), standing("B", 70, 60))]
        lines += [poll(t) for t in range(80, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual(at(40), min(a for a, _ in ar.unresolved(polls, 60, GAP)[(KEY, RUN)]))

    def test_a_train_voided_by_one_close_behind_may_have_come_since_the_poll_before(self):
        # A is first listed at 60 already "At Platform"; B reaches it within 90 s: both void, and
        # A may have come any time after the empty poll at 40.
        lines = [poll(t) for t in (0, 20, 40)]
        lines += [poll(t, standing("A", 60, t)) for t in (60, 80)] + [poll(100)]
        lines += [poll(t, standing("B", 120, t)) for t in (120, 140)]
        lines += [poll(t) for t in range(160, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        self.assertEqual(at(40), min(a for a, _ in ar.unresolved(polls, 60, GAP)[(KEY, RUN)]))

    def test_a_train_with_no_id_first_seen_at_the_platform_may_have_come_since_the_poll_before(self):
        lines = [poll(t) for t in (0, 20)] + [poll(40, train("000", 40, 40, location="At Platform"))]
        lines += [poll(t) for t in range(60, 401, 20)]
        polls = ar.load(lines)
        self.assertEqual([(at(20), at(120))], ar.unresolved(polls, 60, GAP)[(KEY, RUN)])

    def test_a_train_there_when_its_run_starts_is_never_shown_again(self):
        # A stands at the platform in the run's first poll, then TfL drops the marker while still
        # giving it a future time: it arrived before the run, so it isn't shown in any window.
        lines = [poll(0, train("A", 100, 0, location="At Platform"))]
        lines += [poll(t, train("A", 100, t)) for t in (20, 40)]
        lines += [poll(t) for t in range(60, 501, 20)]
        result = ar.score(ar.load(lines), [], 60, GAP, window=150)
        self.assertEqual(0, result["raw"]["shown"])

    def test_a_window_running_into_a_break_is_not_counted(self):
        lines = [poll(0, train("A", 60, 0)), poll(30, train("A", 60, 30)), poll(90), poll(3600), poll(4000)]
        result = ar.score(ar.load(lines), [], 60, 90, window=150)
        self.assertEqual((0, 0), (result["raw"]["shown"], result["raw"]["came"]))

    def test_headways_are_the_gaps_between_actual_arrivals(self):
        self.assertEqual({KEY: [120.0, 120.0]}, ar.headways(ar.load(QUEUE), 60, GAP))

    def test_each_line_is_scored_on_its_own(self):
        # QUEUE is all northern; a victoria train at another stop scores only under victoria.
        other = "940GZZLUKSX"
        extra = [poll(t, train("V", 100, t, line="victoria"), stop=other) for t in range(0, 81, 20)]
        extra += [poll(100, train("V", 100, 100, line="victoria", location="At Platform"), stop=other)]
        extra += [poll(t, stop=other) for t in range(120, 401, 20)]
        lines = sorted(QUEUE + extra, key=lambda line: json.loads(line)["polledAt"])
        polls = ar.load(lines)
        result = ar.by_line(polls, [120], 60, GAP)
        self.assertEqual(["northern", "victoria"], list(result))
        northern = ar.score(ar.load(QUEUE), [120], 60, GAP)
        for method in ("raw", "spaced 120s"):
            self.assertEqual(northern[method]["shown"], result["northern"][method]["shown"])
            self.assertEqual(northern[method]["came"], result["northern"][method]["came"])
        # V's errors are all victoria's.
        self.assertTrue(any(result["victoria"]["raw"]["errors"].values()))
        out = io.StringIO()
        ar.report(ar.score(polls, [120], 60, GAP), ar.headways(polls, 60, GAP), out, lines=result)
        self.assertIn("By line", out.getvalue())
        self.assertIn("  victoria", out.getvalue())

    def test_two_lines_at_one_platform_at_once_are_both_void(self):
        # A circle and a hammersmith-city train both "At Platform" on platform 2 at once: one track
        # holds one train, whatever its line.
        def on(line, vehicle, due, t, location=""):
            return train(vehicle, due, t, line=line, location=location)
        lines = [poll(t, on("circle", "A", 60, t), on("hammersmith-city", "B", 70, t)) for t in (0, 20, 40)]
        lines += [poll(60, on("circle", "A", 60, 60, "At Platform"), on("hammersmith-city", "B", 70, 60, "At Platform"))]
        lines += [poll(t) for t in range(80, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))

    def test_lines_sharing_a_platform_are_spaced_as_one_queue(self):
        # District A due at 60, a circle train at 90, District B at 100, all on platform 2. Spaced
        # 120 s apart on one track, B comes no sooner than 300: outside the next five minutes,
        # though the circle train between them isn't the District's to count.
        def on(line, vehicle, due, t):
            return train(vehicle, due, t, line=line)
        lines = [poll(0, on("district", "A", 60, 0), on("circle", "C", 90, 0), on("district", "B", 100, 0))]
        lines += [poll(t) for t in range(20, 401, 20)]
        result = ar.by_line(ar.load(lines), [120], 60, GAP)["district"]
        self.assertEqual(2, result["raw"]["shown"])
        self.assertEqual(1, result["spaced 120s"]["shown"])

    def test_lines_sharing_a_platform_score_the_same_whatever_the_hash_seed(self):
        # A circle train and a district one are both due at 200 on platform 2. Which leads mustn't
        # depend on how Python happens to order a set, so every hash seed scores them alike.
        import subprocess
        here = os.path.dirname(os.path.abspath(__file__))
        code = ("import sys; sys.path.insert(0, %r); import arrivals_recorder_test as t; "
                "print(t.tied_lines_score())" % here)
        outputs = {subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True,
                                  env=dict(os.environ, PYTHONHASHSEED=str(seed))).stdout
                   for seed in range(8)}
        self.assertEqual(1, len(outputs), outputs)

    def test_a_listing_with_no_line_scores_beside_named_lines(self):
        # TfL leaves the line out of one tube listing on platform 2; another is a northern train.
        unnamed = train("A", 60, 0)
        unnamed["lineId"] = None
        lines = [poll(0, unnamed, train("B", 90, 0))] + [poll(t) for t in range(20, 401, 20)]
        polls = ar.load(lines)
        self.assertIn((STOP, "", "2"), polls[0][2])
        ar.score(polls, [120], 60, GAP)
        self.assertEqual({"", "northern"}, set(ar.by_line(polls, [120], 60, GAP)))

    def test_two_lines_reaching_one_platform_close_together_are_both_void(self):
        # A circle train reaches platform 2 at 60; a hammersmith-city train 40 s later.
        def on(line, vehicle, due, t, location=""):
            return train(vehicle, due, t, line=line, location=location)
        lines = [poll(t, on("circle", "A", 60, t), on("hammersmith-city", "B", 100, t)) for t in (0, 20, 40)]
        lines += [poll(60, on("circle", "A", 60, 60, "At Platform"), on("hammersmith-city", "B", 100, 60))]
        lines += [poll(80, on("hammersmith-city", "B", 100, 80))]
        lines += [poll(100, on("hammersmith-city", "B", 100, 100, "At Platform"))]
        lines += [poll(t) for t in range(120, 501, 20)]
        polls = ar.load(lines)
        self.assertEqual({}, ar.platform_arrivals(polls, 60, GAP))
        # Scored a line at a time, the other line's train still voids it.
        for line in ("circle", "hammersmith-city"):
            self.assertEqual({}, dict(ar.by_line(polls, [], 60, GAP)[line]["raw"]["errors"]))

    def test_report_prints_each_method(self):
        out = io.StringIO()
        polls = ar.load(QUEUE)
        ar.report(ar.score(polls, [90], 60, GAP), ar.headways(polls, 60, GAP), out)
        text = out.getvalue()
        self.assertIn("raw", text)
        self.assertIn("spaced 90s", text)
        self.assertIn(f"{STOP} northern P2: 120, 120, 2", text)

    def test_a_train_with_no_id_keeps_its_place_but_has_no_visit(self):
        lines = [
            poll(0, train("A", 30, 0), train("000", 60, 0), train(" 7 ", 90, 0)),
            poll(20, train("A", 30, 20), train("", 60, 20), train("7", 90, 20)),
            poll(40, train("000", 60, 40), train("7", 90, 40)),
            poll(60, train("7", 90, 60)),
            poll(80, train("7", 90, 80)),
        ] + [poll(t) for t in range(100, 401, 20)]
        polls = ar.load(lines)
        self.assertEqual([("A", at(30)), ("", at(60)), ("7", at(90))], polls[0][2][KEY])
        # Two trains with no id in one poll aren't one train listed twice.
        self.assertEqual(
            [("", at(60)), ("", at(70))],
            ar.load([poll(0, train("000", 60, 0), train("", 70, 0))])[0][2][KEY],
        )
        actual = ar.actual_arrivals(polls, 60, GAP)
        self.assertEqual({(KEY, RUN, "A", 0), (KEY, RUN, "7", 0)}, set(actual))
        # Spaced 120 s apart, 7 goes behind the train with no id too: 30, 150, 270.
        self.assertEqual([at(30), at(150), at(270)], [t for _, t in ar.spaced(polls[0][2][KEY], 120)])
        # Every window up to 40 holds the train with no id, so isn't counted; from 60 on, they are.
        result = ar.score(polls, [], 60, GAP, window=150)
        self.assertEqual(result["raw"]["came"], result["raw"]["shown"])
        # Nor is a gap between trains that spans one: A and 7 left the board either side of it.
        self.assertEqual({KEY: []}, ar.headways(polls, 60, GAP))

    def test_a_platform_name_in_any_case_is_its_platform(self):
        polls = ar.load([poll(0, train("A", 60, 0, platform="southbound - platform 2"))])
        self.assertEqual([("A", at(60))], polls[0][2][KEY])


class HeadwayTest(unittest.TestCase):
    def test_no_gap_spans_a_break_in_polling(self):
        lines = [
            poll(0, train("A", 60, 0)), poll(30, train("A", 60, 30)), poll(90),
            poll(3600, train("B", 3650, 3600)), poll(3620, train("B", 3650, 3620)), poll(3680),
        ]
        self.assertEqual({KEY: []}, ar.headways(ar.load(lines), 60, 90))


class MedianTest(unittest.TestCase):
    def test_an_even_count_takes_the_mean_of_the_middle_two(self):
        self.assertEqual(90, ar.median([120, 60]))
        self.assertEqual(60, ar.median([120, 30, 60]))


class FakeTime:
    """A clock that moves only when something takes time: a fetch, or a sleep."""

    def __init__(self):
        self.now = at(0)
        self.slept = []

    def clock(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += datetime.timedelta(seconds=seconds)

    def take(self, seconds):
        self.now += datetime.timedelta(seconds=seconds)


class RecordTest(unittest.TestCase):
    def test_writes_a_line_per_stop_per_poll_and_carries_on_past_a_failed_fetch(self):
        time_ = FakeTime()
        calls = []

        def fetch(stop):
            calls.append(stop)
            time_.take(1)
            if len(calls) == 2:
                raise urllib.error.URLError("down")
            if len(calls) == 3:
                raise http.client.RemoteDisconnected("closed")
            return [train("A", 60, 0) | {"extra": "dropped"}]

        out = io.StringIO()
        err = io.StringIO()
        saved, sys.stderr = sys.stderr, err
        try:
            polls = ar.record(["S1", "S2"], 20, 30, out, fetch=fetch, sleep=time_.sleep, clock=time_.clock)
        finally:
            sys.stderr = saved
        self.assertEqual(2, polls)
        lines = [json.loads(line) for line in out.getvalue().splitlines()]
        self.assertEqual(["S1", "S2"], [line["stop"] for line in lines])
        self.assertEqual(set(ar.KEPT), set(lines[0]["arrivals"][0]))
        # Each poll is stamped after its answer came, not when it was asked.
        self.assertEqual([iso(1), iso(22)], [line["polledAt"] for line in lines])
        self.assertIn("S2: fetch failed", err.getvalue())
        self.assertIn("S1: fetch failed: closed", err.getvalue())

    def test_rounds_keep_to_the_interval_however_long_the_requests_take(self):
        # Three stops, each answering in 5 s: a round takes 15 s, so it waits 5 s, not 20, and
        # each stop is polled every 20 s.
        time_ = FakeTime()

        def fetch(stop):
            time_.take(5)
            return []

        out = io.StringIO()
        ar.record(["S1", "S2", "S3"], 20, 60, out, fetch=fetch, sleep=time_.sleep, clock=time_.clock)
        stamps = [json.loads(line)["polledAt"] for line in out.getvalue().splitlines() if '"S1"' in line]
        self.assertEqual([iso(5), iso(25), iso(45)], stamps)
        self.assertEqual([5, 5, 5], time_.slept)

    def test_a_round_that_overruns_waits_for_the_next_slot(self):
        # A 25 s answer overruns the 20 s interval: the next round starts at 40, not at once.
        time_ = FakeTime()

        def fetch(stop):
            time_.take(25)
            return []

        out = io.StringIO()
        ar.record(["S1"], 20, 50, out, fetch=fetch, sleep=time_.sleep, clock=time_.clock)
        stamps = [json.loads(line)["polledAt"] for line in out.getvalue().splitlines()]
        self.assertEqual([iso(25), iso(65)], stamps)


if __name__ == "__main__":
    unittest.main()
