# Tube arrival measurements

What recorded TfL arrivals say about bunched queues, spacing and duplicate trains. These
numbers back the spacing gaps in `SpacedTrains` and the open items under *Space out bunched
Tube trains* in `TODO.md`.

## Method

`scripts/arrivals_recorder.py record` polls StopPoint Arrivals for a few hubs every 20 s.
Each poll keeps how old TfL's cache copy was (`age`, `cache`) and, per arrival, when TfL made
the prediction (`timestamp`, `timing`), its id and `timeToLive`, and its `destinationNaptanId`
and `direction` (so analysis can replay the app's turnback relabel), none of which scoring reads.
With `--vehicles FILE` it also asks, once a round, for every call ahead of each tube train
listed within a minute of another at one platform number, on any line
(`/Vehicle/{ids}/Arrivals`), into a file of its own: that's what may tell a copy from a real
train. That set is a deliberate superset of the pairs the app grays, so analysis applies the
app's exact rule (newest answer kept, turnbacks relabeled, pairs per line) to the recorded polls
rather than the recorder copying it. `--vehicles` needs `TFL_APP_KEY`. Every request goes through
one limiter that keeps any rolling minute within TfL's limit (about 50 keyless, 500 with a key),
waiting when it's full. A vehicle request goes only while the minute keeps room for a round of
stop polls after it; trains left over are said on stderr and wait for a later round, the ones
asked least lately going first.
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
| End of morning peak | Fri 2026-10-16, 09:17 – 11:14 | 15 below |

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

### Which of a pair vanished

From the Friday recording, by `arrivals_recorder.py pairs`. The pairs and "vanished" are as
above, with four differences:

- Each pair counts once, at the closest TfL showed it.
- A train TfL placed "At Platform" came.
- A pair is unsettled when either train can't be judged. That covers a train still listed at the
  end, one void as `score` takes it (moved platform, listed twice, or two reaching the platform
  under 90 s apart), and one last listed over a minute past its time without being "At Platform".
- A train TfL lists on two lines at once is left out, as the app leaves it out (below).

| Shown apart | Both came | Earlier vanished | Later vanished | Both vanished | Unsettled |
|---|---|---|---|---|---|
| 0–5 s | 29 | 3 | 0 | 0 | 22 |
| 6–60 s | 255 | 4 | 4 | 0 | 153 |
| 61–89 s | 11 | 0 | 0 | 0 | 4 |

- **Neither train of a pair is the safe one to show.** When one vanished, it was the earlier 7
  times and the later 4. Graying only the later isn't supported.
- **With trains on two lines left out, few pairs had a train vanish:** 11 of the 295 settled
  pairs 60 s apart or less. 6 were on the Jubilee, 2 of 15 on the Hammersmith & City, 2 on the
  Central and 1 on the Northern. The settled pairs the app grays were nearly all two real trains.
- **A train listed on two lines at once:** until TfL settles which line a train runs as, it
  lists it at one platform under each line it could be, with the same id, due within a minute,
  most often with no destination. The app grays both listings and leaves them out of the pair
  rule (`DoubtfulTrains`). It was nearly always the Hammersmith & City listing that went:

  | Line | Listings | Settled | Vanished |
  |---|---|---|---|
  | Hammersmith & City | 126 | 108 | 107 |
  | Metropolitan | 108 | 20 | 5 |
  | District | 16 | 0 | 0 |

  Most of the Metropolitan and District listings couldn't be judged: 91 were void as `score`
  takes them, and 13 were still listed when the recording ended. A listing TfL also lists twice on
  its own line has no id to follow, so isn't counted, though it's still left out of the pairs.
- **Far fewer vanished than on Tuesday:** 11 of 295 settled pairs 60 s apart or less, against
  about two-thirds of the 6–60 s pairs there. The Tuesday count didn't set aside trains that
  moved platform, stood listed long past their time, were placed "At Platform" early, or were
  listed on two lines at once. It was also taken in the peak itself. Running `pairs` over the
  Tuesday recording would say which difference matters.
- **None of the signals separates the two.** In the 11 pairs where one train vanished, the one
  that vanished against the one that came:
  - had stopped counting down in its last 90 s listed: 2 of 11, against 1 of 10;
  - had no location at its last listing: 0 of 11, against 0 of 9;
  - was at one location with the other in a poll: in none of the 11 pairs, but in 26 of the
    295 pairs that both came.
- **175 of the 0–60 s pairs were unsettled:**
  - 80 had a train void as "moved platform". That was mostly the same train seen again on its
    way back from the end of the line: 4–7 minutes later at Stockwell (Victoria), 7–10 at
    Waterloo (Bakerloo, Northern). This falls within the 10 minutes `visits` counts as one visit.
  - 55 were two trains reaching the platform under 90 s apart. That is where a copy standing
    with the real train would show.
  - 25 were stale times, 13 were still listed when the recording ended, and 2 were listed twice
    or at the platform together.

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

## Held trains

From a two-hour recording of every line at seven stations, with TfL's prediction times
(`timestamp`), each stop's newest answer kept as the app keeps it. A train counts as **held** once
TfL has placed it at one location (`currentLocation`) for two minutes and its expected time has slid
later by at least half the time passed, while it is a minute or more away (`HeldTrains`). Times 1 to
20 minutes out, against when each train came (TfL's own record, as above, so every row reads about a
minute late):

| | Times shown | Median came after | Quartiles | Over 1 min late | Over 3 min late |
|---|---|---|---|---|---|
| Not held | 76,046 | +72 s | +24 / +151 s | 56% | 20% |
| Held | 2,087 (2.7%) | +386 s | +248 / +566 s | 93% | 85% |

- **Standing still is what matters, not the slide alone.** Over two minutes, trains whose time slid
  at half the clock's pace or more while they moved came up to a minute later than the rest
  (medians +75 s and +118 s, against +64 s); standing still while sliding at that pace, +268 s and
  +313 s.
- **Too spread to correct.** Half the held times came between four and nine minutes late, so the
  app grays them rather than adding a fixed amount.
- **Stricter settings change little.** Three minutes standing, or a slide of three-quarters, marks
  1.9% of times, which come a median +388 s and +426 s; a minute and a half marks 3.3%, +366 s.
- **Riders saw the holds.** A rider on board was held at platforms twice ("regulating the service")
  and stopped once short of a platform; each time TfL's time for the trains behind slid while it
  placed them where they stood. The platform display, watched alongside, already counted the hold:
  at a station behind one, it showed the trains one to two and a half minutes later than TfL's live
  list, and the train behind the hold came within ten seconds of it.
- **Held trains first.** Holds were more common in this recording than riders recall, so the rule
  is to be checked on later ones (`TODO.md`).

## Field reports

Reports from a rider at a platform, against the board the app showed. The station isn't named
(AGENTS.md *Privacy*).

**A Northern line platform, weekend afternoon (2026-10-18).** What was seen, with no listing
matched to a train, since nothing says which listing was which:

- The app showed Battersea 0 · 5 · 7 and Morden 2 · 4 · 9 min, the first four grayed.
- The rider saw Battersea come in 2 min, Morden in 5, Battersea in 6, Morden in 7.
- TfL's own times at that refresh, as worked back from the debug log's spacing shifts: Battersea
  about 0:30, Morden 1:30 and again 30 s later, Battersea 3:00, then trains at about 7:00 and
  later. That is four listings due within the first 3 minutes against one train that came then.
- Each of the four listings in the bunch after the first came within 60 s of the one before,
  a chain of close pairs, so the app grayed all four (`DoubtfulTrains`). Spacing (`SpacedTrains`) set each listing at least 100 s after the one
  ahead, grayed ones included.
- A trip changing there counted on the first listing (0 min).

Open, since the listings can't be matched to the trains: which listings were extras, whether
the trains came early or late against their own listings, and whether an extra held in the
spacing queue pushed a real train later. One report, judged only against what the rider saw. A
recording at a platform with the station display noted alongside would match them.

**A platform recording with the board noted alongside.** Three Northern line stations and one
Piccadilly line platform, each polled every 10 s with `arrivals_recorder.py` while the rider
reported each train as it came, by destination and, where visible, train number, with photos of
the board. Stations, dates and times of day are left out (AGENTS.md *Privacy*); only intervals
are kept.

- **Close pairs were two real trains.** Three southbound Northern pairs were listed 0–60 s
  apart:
  - A Battersea and a Morden listed in the same second for a minute, then 60–86 s apart. The
    rider saw both come, about 1:40 apart.
  - A Morden from the Edgware branch 60 s ahead of the rider's own Morden from the High
    Barnet branch, then 90 s. The first was listed at the platform, and the rider was on the
    second, so the app would have grayed the train the rider was on.
  - Two trains from the two High Barnet-side branches, listed 60 s apart and twice in the same
    second where they meet. Nobody saw them come, but TfL tracked each separately, station by
    station, the whole way.

  TfL squeezes trains still several stations out into steps of 60 s or less; the real spacing
  shows only as they near. Each pair's two listings moved independently, their locations
  advancing on their own.
- **The feed missed real trains.** Two Piccadilly short workings arrived and were seen by
  number, yet neither number appears anywhere in the recording. The board listed each several
  minutes ahead, and listed both trains that followed them correctly. Earlier, one listing with
  no destination ("Check Front of Train") was dropped a few minutes before the first short
  working came, so it may have been that train under another id.
- **A listing outlived its train.** A train stayed listed as approaching the first station after
  the rider, already on the platform, saw nothing come; it was next seen at the platform two
  stations on. The listing was late, not an extra.
- **Answers stepped back in time.** Polls 10 s apart came from cache copies of different ages:
  a fresh answer was followed by one whose predictions were 30 s older, and a train standing at
  the platform was listed 40 s out again. Fixed by keeping each stop's newest answer (#994).
- **The board ran ahead of the feed.** The feed's time for the next train was 30–90 s behind
  what came, and it kept listing a train for up to a minute after it left. The board's minutes
  matched the feed's fresh answers rounded to the nearest minute (60 s showed 1, 99 s 2, 369 s
  6), but it held one train at "1 min" for nearly 3 minutes while the train ahead dwelt.
- **Three regulation holds on the Northern line and a long dwell on the Piccadilly** slowed the
  trains behind them; the feed's times slid later with them.

## Not yet measured

- A weekend morning.
- The 30 s grace: how late trains in their last minute arrive, per line.
- Platforms two lines share (Circle and District at Victoria, say). Spacing treats each line
  separately, though the trains share one track.
