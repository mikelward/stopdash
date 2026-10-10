# Glossary

What each word the spec, `TODO.md`, the code and the pull requests lean on means here, said once.
A word that carries more than one meaning is listed with the others it's been used for, and the
word each of those should use instead. **⚑ marks a meaning not yet decided**: it's this draft's
proposal, for the maintainer to settle; once settled, the ⚑ comes off and the spec follows.

Words users see are marked *(on screen)*, with the text they see where it differs.

## Places and stops

- **Stop** *(on screen)*: one TfL `StopPoint`: a bus pole, a station, a tram stop, a pier. The code's
  `StopLocation`, `StopArrivals` and `StopRef` are each a stop.
  - *Also used for:* a calling point on a route ("6 stops to Whitechapel"): keep, it's what riders
    say. "Show this stop only" opens a platform or pole group, not one stop. ⚑ Call it "Show this
    platform only" where it's a platform?
- **Pole**: one lettered bus stop, "Stop D" on screen. Each pole is its own arrivals request.
  - **Stop pair**: a road's two poles, one each way, which TfL files as one stop area.
  - **Sibling poles**: the poles in a journey's origin stop area, beside its own, that board a line
    reaching its far end.
- **Stand**: a bus-station stand not paired across a road. Use it for that alone.
  - *Also used for:* a stop area's members in search ("listed once, not once per stand"): say
    "stops". The English senses (an answer that *stands in*, a departure the app *stands behind*)
    stay English.
- **Platform** *(on screen)*: a rail platform from TfL's `platformName`, the group a rail stop's
  card shows ("Platform 2").
- **Station** *(on screen)*: a rail, Tube, tram or DLR stop area. On screen, "Find a station" and
  "Show whole station" also cover a bus stop area: that's the user's word for any place, and stays.
- **Place**: the stops read as one boarding location: a station's platforms, or a bus junction's
  poles. Each platform or pole has its own card, headed by the place's name and the platform or
  pole, so one place can span several headers. The code's word for the same set is **cluster**
  (stops sharing a TfL `stationNaptan`, else a name). In prose say place; in code, cluster.
  - **Favorite place** *(on screen)*: a saved spot (Home, Work, or one the user names) that a tap
    on its chip plans a trip to. Always two words in prose; it is not a place in the sense above.
  - *Also used for:* a geocoded *To…* result, tagged *Place* on screen: keep the tag, call it a
    "geocoded place" in prose. An interchange "counting as one place" toward the nearby cap: say
    "counts once".
- **Interchange**: several stations TfL groups under one hub code (King's Cross and St Pancras;
  Canary Wharf's Tube, DLR and Elizabeth line). An interchange counts once toward the nearby cap,
  its stations load together, and a stop notice TfL files against several of them shows once; but
  each station stays its own place in the list, with its own headers, as the different building it
  is. **Hub** is the code's name for it (`hubId`, `HubInfoCache`): in
  prose, say interchange.
  - *Also used for:* "station complex" (trips): say interchange. "A hub" for any big station: say
    "a big station".

## Lists

- **Near-me list** *(on screen: "Departures near you")*: the stops nearest the rider's fix, closest
  first. The other list kind is a **station's list** (opened from search or *From…*), shown as if
  the rider stood there.
- **Nearby set**: the places one fix resolved, in two tiers: **eager** (fetched at once) and
  **more** (found, not fetched). Identified by `clusterSetKey`.
  - *Also used for:* the widget's and watch's stops: say "the widget's stops". They are the eager
    tier, plus any starred journey's origin outside it (`journeyOnlyStopIds`), which shows only its
    journey. Code comments call the nearby set "watched": say nearby set.
- **Watched stop**: a stop the user chose, persisted, the source every surface reads from
  (Phase 2, not built). Nothing built yet is a watched stop.
- **Farther cards** *(on screen: "Tap to see")*: the collapsed cards at the foot of the near-me list,
  for rail lines (farther stations) and bus routes (farther buses) the loaded stops don't serve. A
  National Rail service counts by its route ends, so a farther station reaching an end nothing
  nearby reaches gets a card even on a service already served.
  - *Not to be confused with:* a **far stop** (over 500 m, refreshed every other minute), **faraway
    favorites** (journeys a mile from both ends), or a journey's **far end**.
- **Starred row** *(on screen: "Pin to top")*: a service (stop, line, direction) lifted to the top of
  the list, marked by a gold bar. ⚑ The spec says star, the screen says pin: pick one word for
  both.
- **Status row**: a row with no countdown, carrying a disruption or a mark ("–", "?", "No data",
  "No key").
- **"?" row** *(on screen: "?", "Times unknown")*: a good-service line with no live times whose
  timetable has a train due soon, or, on a frequent mode (Tube, Elizabeth line, Overground, DLR,
  tram), whose timetable can't say. The code calls it **quiet** (`DepartureRow.quiet`).
  - *Also used for:* a disrupted line whose timetable can't say ("?" on a status row), and the
    watch complication's stale mark. ⚑ The spec once says "?" means "a countdown too stale to
    show": that sentence predates the "?" row and should go.

## Journeys and trips

- **Journey** *(on screen: "Starred journeys")*: a direct ride between two stops, any line, both
  ways, starred from a route page. Its card shows the trains from its origin that call at its far
  end.
  - **Origin**: the end nearer the rider's fix (⇄ swaps it).
  - **Far end**: the other end, where the trains must call. The code says destination
    (`journeyDestinations`): ⚑ rename to far end, or keep destination in code only.
- **Trip** *(on screen: "Plan a trip to…")*: a From → To plan from TfL's Journey Planner, shown as
  route cards of legs. "Trips with a change" in the spec covers direct trips too.
  - **From / To**: a trip's start and destination as the screen names them; the code's
    `TripOrigin` and `TripDestination`.
  - **Leg**: one part of a route: a **ride** (one line) or a **walk**.
  - **Change**: getting from one ride to the next, with or without a walk ("3 min to change"). A
    **change on foot** is a walk between two rides whose ends are close (200 m, or 290 m within one
    interchange), which On the way doesn't count as a step.
  - **Planner**: TfL's Journey Planner, which picks the lines and changes. The app times the legs
    itself.
- **Destination** *(on screen)*: a departure's terminus, as its row shows it ("➔ Farringdon"). Not a
  journey's far end, and not a trip's To.
- **On the way** *(on screen)*: a started trip route, followed until arrival or End trip. The code's
  "active trip" is the same thing.
- **Board** (verb) *(on screen)*: getting on. As a noun, a stop's **board** is its list of
  departures, any mode, as On the way reads it (the trip's board). The **National Rail board** is
  the list from National Rail, by station code, which needs the user's key: always say "National
  Rail" for that one.

## Disruptions

- **Disruption** *(on screen)*: anything that isn't normal service, line or stop. The umbrella word.
- **Line status** *(on screen)*: TfL's word on a line: good service, or how it's disrupted and why.
  A **line alert** is a line status that isn't good service, or planned work to come, which shows a
  calendar until its start day.
- **Stop notice** *(on screen: "Stop notices")*: TfL's text about one stop: closed, moved, a lift
  out. Shown only while in force. The code's `StopDisruption`.
- **Closure** *(on screen: "Closed")*: a stop notice saying the stop is closed, which earns the
  "Closed" chip.
  - *Also used for:* any stop notice ("closure check", "closure card", `StopClosureCache`). ⚑ Say
    **notice check** and **notice card** in prose, and rename the code with the next change that
    touches it; or let "closure" cover every stop notice and find another word for the closed
    kind.
  - *Not to be confused with:* a line's **Part Closure** status, which is a line status.
- **Alert** *(on screen: "Dismiss alert")*: anything the user can dismiss: a line alert or a stop
  notice.
  - *Also used for:* On the way's notifications ("Route disruption", "get-off alerts"): say
    **notification**.
- **Dismissal** *(on screen: "Dismiss")*: an alert the user put away, saved, until its content
  changes or a check sees it end; the same alert coming back later is new. Dismissing planned work's calendar puts away only the notice: on its start day the work's
  ⚠ is a new alert, dismissed separately. Not the trip page's "Keep going" or a station note's ×,
  which aren't saved: say those **let go**.

## Time

- **Fresh / stale**: whether departures are recent enough to show as countdowns: stale past five
  minutes (`Staleness`, D4). On screen it's an age ("Just now", "4 min ago") or "Tap to refresh".
  - *Not to be confused with:* On the way's **live** (75 s), or a **reuse window** (how long a
    fetched answer serves another ask: 50 s arrivals, 90 s line status, 5 min notices).
- **Marked guess**: a stale departure's time, shown with a "?" ("21:14?"). ⚑ The spec calls this
  a "stale stand-in"; rename, since a stand-in means something else below.
- **Fix** *(not on screen)*: a location reading. **Precise** or **coarse**; a **fallback** fix is a
  bounded last-known one; a **sure** fix is precise, fresh and as accurate as the decision it's used for needs (On the
  way asks 50 m to tell a rider was left behind, 100 m to tell they boarded).

## Fetching and checking

- **Refresh** *(on screen)*: fetch the departures again for the stops already shown. The one-minute
  tick is an automatic refresh.
  - **Relocation**: take a fresh fix and resolve the nearby set again. On the near-me list, a **pull to
    refresh** is a relocation, then a refresh; on a station's list it's a refresh alone. ⚑ The spec spells it "re-locate"; the code, relocate: pick one.
  - **Reconcile**: what a relocation does when it finds the same nearby set: update in place,
    drop what left, refresh. The code also reconciles saved dismissals against what's live: say
    **prune** for that.
  - *Also used for:* the widget's, watch's and trip page's own refetches, each named by its
    surface ("the widget's refresh").
- **Check**: a screen asking about something and getting an answer it can show: a **notice check**
  (one stop), a **line check** (lines' status), a **journey check** (which departures reach a far
  end). A check is answered by a lookup.
- **Lookup**: one request and its answer, in the order it was asked. A check may reuse a recent
  lookup, join one already out, or send its own.
  - *Also used for:* the **nearby lookup** (TfL's stops near a fix): keep, it is a lookup.
- **Verdict**: a line check's answer for one line: good or disrupted. **No verdict**: asked, and TfL
  left the line out.
  - *Also used for:* whether a bus alert lies behind a stop (**alert placement**), and whether a
    train reaches a leg's stop (**trip verdict**): each keeps its qualified name.
- **Determined**: a line TfL returned a status for. **Unknown** has been used six ways; say which:
  - a line TfL doesn't recognize: an **unrecognized line** (`unknownLineIds`);
  - a line check that failed: **unchecked lines** (`disruptionUnknown`);
  - a notice check that failed: an **unchecked stop** (`stopsDisruptionUnknown`);
  - a stop whose fetch failed with nothing earlier to show: a **dropped stop**;
  - departures TfL gave no direction, grouped together: a **no-direction row**;
  - an alert the app can't place on a ride, which counts as on it: an **unplaced alert**.

  On screen, "Unknown:" on a trip's disruptions row names the lines and stops a failed check
  couldn't confirm.
- **Carry-over**: a refresh reusing a stop fetched within the reuse window, by this screen or
  another, at its own age. Not **carried forward**, which is a stop whose fetch failed, kept and shown aged.
- **Pending**: asked and not answered yet. Name the thing pending (`pendingStops`,
  `closurePending`).

## The screen's work

- **The worker**: the dispatcher for work that grows with its input (`Workers.compute`, reached as
  `LocalWorker`), never the main thread (AGENTS.md *Main thread*). Not a WorkManager **worker**
  (`WidgetRefreshWorker`): say **background job** for those.
- **Slot**: where one stage of a screen's work keeps its answer for the inputs it was worked out for
  (`ListWork`'s `rows`, `shown`, `journeyOrigins`, …, via `rememberWorked`).
- **Answer standing in** (stand-in): a slot's last answer, shown for newer inputs while theirs is
  worked out, where the stage allows it (`keep`).
- **Held**: a stage keeping its last answer while what it's worked out from is still pending
  (`heldWhile`). ⚑ Keep "held" for this alone: a cold load's collapsed card is a **kept card**,
  faraway journeys are **set aside**, a cached line answer is **kept** (`heldLines` →
  `keptLines`).
- **Report**: what the screen tells the view model it worked out (the journey stops, the far ends,
  the widget's pins), made only as it changes (`Reported`, `ReportChanges`). Not a **bug report**
  or a **crash report**, which always carry their noun.
- **Settle**: two steps, each so the newest answer wins. Settling a **lookup** applies its outcome in
  the order it was asked (`StopClosureCache.settle`). Settling a **check** drops the dismissals its
  verdicts no longer hold, one check at a time across every screen, so an older verdict never undoes
  a newer one (`StopClosureCache.settling`). Not a list coming to rest: say **hold still**
  (principle 4).
- **Snapshot**: the **saved snapshot** (`DeparturesSnapshot`) is the last good departures on disk,
  which the widget and watch draw; the **list's snapshot** (`DeparturesUiState.Loaded`) is what the
  screen draws now. Say which.

## Pages

- **Route page**: a tapped row's trains and stops.
- **Line page**: a whole line's map. ⚑ `TODO.md` and the spec sometimes call the route page a line
  page: use route page.
- **Lines page**: the list of line statuses a disruptions row opens, on the home screen or a trip
  (`TripLinesPage`).
