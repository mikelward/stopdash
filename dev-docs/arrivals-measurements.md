# Tube arrival measurements

What recorded TfL arrivals say about bunched queues, spacing and duplicate trains. These
numbers back the spacing gaps in `SpacedTrains` and the open items under *Space out bunched
Tube trains* in `TODO.md`.

## Method

`scripts/arrivals_recorder.py record` polls StopPoint Arrivals for a few hubs every 20 s.
`score` then finds when each train actually reached each platform: halfway between the poll
where TfL first places it "At Platform" and the poll before. It compares TfL's times, and the
same times spaced at least a gap apart per platform, against those arrivals. Two numbers per
method:

- **Shown/came**: at every poll, the trains a method put due in the next 5 minutes, against the
  trains that actually reached the platform in those 5 minutes. +12% means the board showed
  12% more trains than came. Polls too close to the end of a recording are left out.
- **Mean signed and absolute error** of each prediction against the arrival. A negative signed
  error means trains came later than shown.

Recordings stay outside the repository (AGENTS.md *Privacy*). All of these stops are hubs.

## Recordings

| Window | When (BST) | Stops |
|---|---|---|
| Morning peak | Mon 2026-10-12, 07:33 – 09:03 | Hubs and junctions on Victoria and Northern (first run) |
| Late night | Mon 23:46 – Tue 00:20 | Euston, King's Cross, Victoria, Warren Street |
| Morning peak | Tue 2026-10-13, 06:29 – 09:29 | 15 below |
| Midday | Tue 11:07 – 12:37 | 15 below |
| Evening | Tue 19:01 – 20:31 | 15 below |
| End of morning peak | Wed 2026-10-14, 09:14 – 10:14 | Camden Town, Euston, King's Cross, Tottenham Court Road |

The 15: King's Cross, Bond Street, Liverpool Street, Stratford, Waterloo, Camden Town,
Stockwell, Baker Street, Farringdon, Paddington (both tube stations), and the Elizabeth line at
Bond Street, Liverpool Street, Farringdon and Paddington.

## Spacing, by line

Average error in seconds and shown/came, raw against each gap. **Bold** is the best gap.

**Victoria**

| | Raw | 90 s | 100 s | 120 s |
|---|---|---|---|---|
| Peak | 44 s, +16% | 42 s, +12% | **40 s, +8%** | 48 s, −1% |
| Midday | 90 s, +18% | 87 s, +14% | **86 s, +11%** | 89 s, +3% |
| Evening | **49 s, −4%** | 49 s, −6% | 51 s, −9% | 60 s, −15% |

**Northern**

| | Raw | 90 s | 100 s | 120 s |
|---|---|---|---|---|
| Peak | 87 s, +7% | 82 s, +5% | 80 s, +4% | **79 s, +2%** |
| Midday | 63 s, +4% | 59 s, +1% | **58 s, 0%** | 60 s, −3% |
| Evening | 64 s, +10% | 59 s, +6% | **58 s, +4%** | 59 s, +1% |
| Late night | 95 s, +12% | 91 s, +10% | 90 s, +9% | **89 s, +8%** |

**Central**

| | Raw | 90 s | 100 s | 120 s |
|---|---|---|---|---|
| Peak | **64 s, +12%** | 64 s, +9% | 64 s, +8% | 66 s, +4% |
| Midday | **79 s, +2%** | 82 s, 0% | 83 s, −1% | 86 s, −4% |
| Evening | 70 s, +6% | 64 s, +3% | **63 s, 0%** | 68 s, −3% |

**Jubilee**: TfL's times already run late (+41 s at peak, +51 s at midday on average), and
spacing makes that worse: 71 → 74 s at 120 s at peak, 66 → 69 s at midday. Too few trains were
recorded in the evening to score.

**Piccadilly, Bakerloo, Circle, Hammersmith & City**: spacing improves each slightly at 90–120 s
and never makes one worse. Trains on these lines are rarely bunched, so the gain is small.

**Elizabeth line**: not scored. Its arrivals carry no location, so the recorder can't tell when
a train reached the platform. Scoring it needs another source of truth.

### Conclusion

- **100 s for Victoria, Northern and Central**, all day. That's within a few seconds of the best
  gap at every time of day, so a time-of-day table isn't worth it yet.
- **A 90 s floor for Piccadilly, Bakerloo, Circle and Hammersmith & City.** It's never worse, and it's
  at or below the signalling minimum of about 90–100 s, so it never spaces trains further apart
  than they can actually run.
- **Jubilee and the Elizabeth line keep TfL's times**, and so do the Metropolitan and District
  lines until they're measured: too few of their trains passed these hubs to score.

## Boards list more trains than come

Across every window, boards show more trains due in the next 5 minutes than arrive. It's worst
where trains aren't bunched: Hammersmith & City up to +35%, Bakerloo +18% to +26%, Piccadilly
+20% to +36%. Spacing barely changes those numbers. Two things drive it:

- **Lateness.** On Bakerloo and Hammersmith & City, trains reach the platform 50–90 s after the
  time shown, on average. A train
  shown at 4 min that comes at 5 falls out of the window. This argues for keeping a train on the
  board, grayed, for a short grace after its time passes, rather than dropping it the moment it
  is due. The grace should be measured from how late trains in their last minute actually come.
- **Duplicates and dropped listings.** These are trains TfL lists that never arrive.

## Duplicates

From the first ~80 minutes of the Tuesday peak: pairs of tube trains at one platform, the first
due within 5 minutes, sorted by how far apart TfL showed them. "Vanished" means the train left
the board with more than 30 s still to go. Pairs still listed when the window ended aren't
counted.

| Shown apart | Both came | One vanished | Both vanished |
|---|---|---|---|
| 0–5 s | 7 | 5 | 2 |
| 6–60 s | 79 | 113 | 45 |
| 61–89 s | 29 | 20 | 5 |

- **Identical times don't prove a duplicate**: half those pairs were two real trains.
- **6–60 s apart is where duplicates and relabelled trains mostly are**: one of the pair vanished
  about half the time.
- **61–89 s apart was mostly two real trains**, so the 90 s doubtful rule grays real trains
  there.
- No tube listing lacked a train id. A blank location is rare on the tube (0.7% of listings),
  so a strong signal when present.

Signals still to test against the recordings, to tell the copy from the real train:

- A time that doesn't count down across feed updates.
- A location that can't fit the queue: a siding, "Around …", or the two trains' locations out
  of order.
- A blank location.

The hour below gives first numbers, from TfL's own record, for a time that slides later and for
two trains swapping order. Neither is settled.

## Slipping times and order swaps

From the Wednesday hour (all lines at the four stops), against when each train actually reached
its platform (the recorder's arrival estimates). It followed a report from another Northern line
platform: one train's time kept sliding for about 11 minutes, always about 30 s behind whichever
train was ahead of it, and TfL briefly listed the two trains in the wrong order a minute before
they came. Both trains were real, about 2 minutes apart.

"Came" here is TfL's own record, as everywhere in this doc: placed "At Platform", or leaving the
board at its due time. The recorder voids a train standing at the platform alongside another, or
reaching it under 90 s after one, which is how a copy usually shows; but a copy TfL drops right
at its due time would still count as having come. Only a station display, watched alongside, is
independent of the feed.

**Slipping times.** Each train due within 5 minutes for at least a minute, by how fast its
expected arrival moved later per second of polling: 0 counts down as it should, 1 doesn't count
down at all.

| Slide | Trains | Came | Vanished | Came after the first time shown within 5 min (median) |
|---|---|---|---|---|
| Under 0.25 | 361 | 328 | 3 | +28 s |
| 0.25–0.5 | 83 | 74 | 3 | +118 s |
| 0.5–0.75 | 3 | 3 | 0 | +298 s |
| 0.75 or more | 0 | – | – | – |

The rest left the board with under 30 s to go and were never placed at the platform, so their
arrival isn't known. Against the time on the board at the moment, 1 to 3 minutes out, a train
whose time had been sliding came a median 19 s after the time shown, and one that had been
counting down 20 s after (more than a minute late: 18% and 17%; 688 and 1,806 times shown).

- Trains whose time partly slid came at much the same rate as the rest in TfL's record (89%
  against 91%). None stopped counting down entirely (a slide of 0.75 or more).
- Their earlier times were too soon, but the time on the board at the moment was as good as any
  other train's.

**Order swaps.** Two trains at one platform, both due within 5 minutes, whose order TfL swapped
at least once: 10 pairs in the hour. In the 6 where both came, the order TfL showed last was
right every time (the order it showed first, 5 times). The other 4 can't be checked from the
feed: in 3, one train left the board near its due time without being placed at the platform, and
1 was still listed when the recording ended. None left early.

Numbers only: one hour at four stations at the end of a peak, judged by TfL's own record, which
settles neither signal. A station display, watched alongside, is what would.

## Not yet measured

- A weekend morning.
- The 30 s grace: how late trains in their last minute arrive, per line.
- Platforms two lines share (Circle and District at Victoria, say). Spacing treats each line
  separately, though the trains share one track.
