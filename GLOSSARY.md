# Glossary

What each word the spec, `TODO.md`, the code and the pull requests lean on means here. An entry
says what the word names and points to the `SPEC.md` section that says how it behaves: the rules
live there, once. A word that has carried more than one meaning lists the others that have
caused confusion, and the word each of those should use instead: it isn't a census of every place
the word appears. **⚑ marks a meaning not yet decided**: it's this draft's
proposal, for the maintainer to settle; once settled, the ⚑ comes off and the spec follows.

Words users see are marked *(on screen)*, with the text they see where it differs.

## Places and stops

- **Stop** *(on screen)*: today, one TfL `StopPoint`: a bus pole, a station, a tram stop, a pier.
  The code's `StopLocation`, `StopArrivals` and `StopRef` are each a stop. The station model
  (planned, not built) narrows it to a bus **stop**, the road's poles both ways; there a pole is a
  stand, and a station's `StopPoint`s are its records. `dev-docs/station-model.md` has the rules.
  - *Also used for:* a calling point on a route ("6 stops to Whitechapel"): keep, it's what riders
    say. ⚑ "Show this stop only" opens one platform's or pole's card, not one stop: call it "Show
    this platform only" where it's a platform?
- **Pole**: one stop sign by the road, where a bus, coach or tram boards; "Stop D" on screen where it
  has a letter. A **stop pair** is a road's two poles under one name, one each way; TfL usually
  files them as one stop area, but not always.
- **Stand**: any spot where a rider waits for a vehicle, such as a rail platform, a pole by the road,
  or a bus station's stand. The lowest level of the station model (`dev-docs/station-model.md`,
  planned, not built).
- **Platform** *(on screen)*: a rail platform, as TfL (`platformName`) or National Rail names it.
- **Station** *(on screen)*: a rail, Tube, tram, DLR, cable-car or pier stop area. The station model
  (planned, not built) groups a station's TfL records into one, St Pancras's three as one station;
  `dev-docs/station-model.md` has the rules. On screen, "Find a station" and "Show whole station"
  also cover a bus stop area: that's the user's word for any place, and stays.
- **Place**: the stops read as one boarding location: a station's platforms, or a bus junction's
  poles. The code's word for it is **cluster**. In prose say place; in code, cluster. See
  *Departures*.
  - **Favorite place** *(on screen)*: a saved spot (Home, Work, School, or one the user names).
    Always two words in prose; it is not a place in the sense above. See *Trips with a change*.
  - *Also used for:* a geocoded *To…* result, tagged *Place* on screen: keep the tag, and call it a
    "geocoded place" in prose.
  - *Also used for:* the stops a favorite ride's end takes in, which reach wider than one cluster,
    across an interchange (`StopPlace.samePlace`; see *Journeys*): say **hub**, since the station
    model makes a favorite ride one between two hubs.
- **Hub**: where a rider goes: a TfL interchange, or a station with no interchange, each with its
  own bus stops; a junction of bus stops is one too (maintainer, 2026-10-16). The station model's
  top level (`dev-docs/station-model.md`, planned, not built). Until it's built, the code's `hubId`
  is TfL's interchange code alone.
  - *Also used for:* any big station: say "a big station".
- **Interchange**: stations TfL groups under one hub code (King's Cross and St Pancras), one kind
  of hub. In a list each station is still its own place. See *Finding stops*.
  - *Also used for:* "station complex": say interchange.

## Lists

- **Near-me list** *(on screen: "Departures near you")*: the stops around the rider's position. The
  other list placed by location is a **station's list**: one fixed station's, or a saved or searched
  place's started from (`PlaceStart`), as if the rider stood there, however it's opened. See
  *Finding stops*.
- **Nearby set**: the places one position resolved, in two tiers: **eager** (fetched on its own)
  and **more** (found, not fetched on its own). Identified by `clusterSetKey`.
  - *Also used for:* code comments call it "watched": say nearby set. The widget's and the
    watch's stops are their own: say "the widget's stops", "the watch's stops". See *One widget,
    many surfaces*.
- **Watched stop**: a stop the user chose ahead of time. The **watched list** shows them, with no
  location and no distances (D1). See *Watched stops*.
- **Farther cards** *(on screen: "Tap to see")*: the collapsed cards at the foot of a near-me or
  station's list, for stations and buses farther off. See *Finding stops*.
  - *Not to be confused with:* a **far stop**, **faraway favorites**, or a favorite ride's **far
    end**.
- **Starred row** *(on screen: "Pin to top")*: a service lifted above the unstarred rows; on the
  watched list, warning rows still lead (*Departures*). ⚑ The spec says star, the screen says pin:
  pick one word for both.
- **Status row**: a row with no trains, for a stop or a line with a known disruption: it carries
  the disruption, and where its countdown would be says what's known (a dash, "?", "No data" or
  "No key"). See *Departures*. A **"?" row** isn't one: it carries no disruption.
- **"?" row** *(on screen: "?", "Times unknown")*: a good-service line with no live times that may
  still be running. The code calls it **quiet** (`DepartureRow.quiet`). See *Departures*.
  - *Also used for:* a disrupted line whose timetable can't say, and a stale time: a **marked
    guess** ("21:14?") on any surface that lists times, or the watch complication's bare "?" in
    place of an old countdown (D4).

## Rides and trips

- **Route**: all of a line's runs between its two termini (maintainer, 2026-10-13). "Bus route" is
  the established term.
  - *Also used for:* a departure row's line to one destination, by branch (`RouteFocus`; see
    *Journeys*). ⚑ Give that its own word, or settle that a row's route is part of a route.
- **Favorite ride** *(on screen; on trial, maintainer, 2026-10-13)*: a saved pair of ends (stops,
  or a favorite place), and, for a ride StopDash can follow, its card of the departures between
  them. The station model (planned, not built) makes a ride one between two **hubs**, with rides
  saved before kept as saved where that would change them (`dev-docs/station-model.md`, *Favorite
  rides*). It was a "favorite journey", and the code and much of the spec still say journey until the
  trial settles (`TODO.md`). See *Journeys*.
  - **Origin**: the end its card counts from; *Journeys* says which end that is. **Far end**: the
    other one.
  - ⚑ The code calls the far end a destination (`journeyDestinations`): rename it to far end with
    the rest of the rename, or keep destination in code only?
- **Journey** *(not in new copy)*: the old word for two things. A favorite ride (`FavoriteJourney`,
  the spec's *Journeys*): say favorite ride. And one of the Planner's answers (`TflJourneyDto`,
  `TflJourneyResultsDto.journeys`): say trip. The code keeps the word until each is renamed.
- **Trip** *(on screen: "Plan a trip to…")*: a planned From → To, legs and changes included. Each
  of the Planner's alternatives is a trip (maintainer, 2026-10-13). See *Trips with a change*.
  - *Also used for:* the spec calls the alternatives a trip's **routes** ("route card"): each is a
    trip, since a route is a line's.
  - **From / To**: a trip's ends as the screen names them; the code's `TripOrigin` and
    `TripDestination`.
  - **Leg**: one part of a trip: a **ride** (one line) or a **walk**.
  - **Change**: getting from one ride to the next. A **change on foot** is one On the way doesn't
    make a step of. See *On the way*.
  - **Planner**: TfL's Journey Planner, which picks the lines and changes.
- **Destination** *(on screen)*: where a departure says it's heading, as its row shows it ("➔
  Farringdon"). For a bus that's its blind, often an area or a landmark rather than its last stop.
  See *Departures*. Not a favorite ride's far end, and not a trip's To.
- **On the way** *(on screen)*: a started trip, followed until it ends. The code's "active trip" is
  the same thing. See *On the way*.
- **Board** (verb) *(on screen)*: getting on. As a noun, a stop's **board** is its departures, as
  On the way reads them. The **National Rail board** is the list from National Rail: always say
  "National Rail" for that one.

## Disruptions

- **Disruption** *(on screen)*: anything that isn't normal service, line or stop. The umbrella word.
  See *Disruptions*.
- **Line status** *(on screen)*: TfL's word on a line. A **line alert** is a line status worth
  flagging: a disruption, or planned work to come.
- **Stop notice** *(on screen: "Stop notices")*: TfL's text about a stop, a station or an
  interchange: closed, moved, a lift out. The code's `StopDisruption`. See *Disruptions*.
- **Closure** *(on screen: "Closed")*: a stop notice saying the stop is closed, and only that
  (maintainer, 2026-10-16).
  - *Also used for:* any stop notice ("closure check", "closure card", `StopClosureCache`): say
    **notice check** and **notice card**. The code's names follow as each is next touched.
  - *Not to be confused with:* a line's **Part Closure** status, which is a line status.
- **Alert** *(on screen: "Dismiss alert")*: a line alert or a stop notice: what the user can dismiss.
  - *Also used for:* the notifications On the way and a favorite ride post: say **notification**.
    The features that post them keep their on-screen names ("Ride alerts", "Get-off alerts").
- **Dismissal** *(on screen: "Dismiss")*: an alert the user put away, saved. See *Disruptions*. Not
  the trip page's "Keep going" or a station note's ×, which hold for that trip only (*On the way*):
  say those **let go**.

## Time

- **Fresh / stale**: whether fetched departures, or a notice or line check's answer, are recent
  enough to show as current. See *Freshness*.
  - *Not to be confused with:* On the way's **live**, or a **reuse window** (how long a fetched
    answer serves another ask).
  - *Also used for:* a **fresh fix**, one just taken: see **Fix**.
- **Marked guess**: a stale departure's time, shown with a "?". ⚑ The spec calls this a "stale
  stand-in": rename, since a stand-in means something else below.
- **Fix** *(not on screen)*: a location reading, **precise** or **coarse**. A **fresh** fix is one
  just taken; a **fallback** fix is a last-known one; a **sure** fix is accurate enough for the
  decision it's used for. See *Finding stops*.

## Fetching and checking

- **Refresh** *(on screen)*: fetch the list's departures again, with the checks that go with them
  as those checks' reuse allows; *Freshness* says what one covers (favorite rides' stops and
  lines among them).
  On the near-me list the refresh control and a return to the app relocate first; the timed
  auto-refresh doesn't. A refresh with nothing saved to show yet is a **cold load**. See *Freshness*.
  - **Relocation**: asking for a fresh fix and finding the nearby set again from it. *Finding
    stops* says what it does when no fresh fix comes. ⚑ The spec spells it "re-locate"; the code,
    relocate: pick one.
  - **Reconcile**: bring what's shown in line when the nearby set is worked out again and comes
    back the same: by a relocation (found again, or kept because no fresh fix came), or by a
    re-pick from the same fix (a mode turned back on, `NearbyStopsViewModel.refilter`). The code
    also reconciles saved state against what's current: say **prune** for saved dismissals, and
    **sync** for starred places' details (`FileStarredPlacesStore`) and learned Wi-Fi places
    (`AndroidWifiPlaces`).
  - *Also used for:* the widget's, the watch's and a trip's own refetches: name the surface ("the
    widget's refresh").
- **Check**: asking about something and judging the answer, on a screen or in the background. Most
  ask TfL: a **notice check** (a stop), a **line check** (lines' status), a **ride check** (which
  departures reach a far end). Others keep their qualified names: a GPS check, an update check.
  A check is answered by one or more lookups (a long line list is split, `LineStatusBatch`).
- **Lookup**: one ask and its outcome, a failure included, however it's supplied: a request of its
  own, a share of a batched one, or an answer already in hand. The **nearby lookup** (the stops near
  a fix) is one.
- **Verdict**: a line check's answer for one line. **No verdict**: asked, and TfL left the line out.
  - *Also used for:* where a bus alert lies (**alert placement**), and whether a train reaches a
    leg's stop (**trip verdict**): each keeps its qualified name. And what a line page compares to
    decide whether to redraw (`sameVerdict`); work to come is "no verdict" there because it isn't
    compared: say **not compared** for that, so "no verdict" keeps TfL's sense.
- **Determined**: a line TfL returned a status for. **Unknown** has meant many things; say which.
  Among them:
  - an **unrecognized line**: one TfL doesn't know (`unknownLineIds`);
  - an **unchecked line**: one with no current verdict: its check failed, got no status, aged out,
    or was never made;
  - an **unchecked stop**: one whose notice check failed (`stopsDisruptionUnknown`);
  - **something unchecked**: something on the screen whose disruption couldn't be checked
    (`disruptionUnknown`); `disruptionUnknownOf` says what counts;
  - a **dropped stop**: a stop whose fetch failed with nothing earlier to show;
  - a **no-direction row**: departures TfL gave no direction;
  - an **unplaced alert**: one the app can't place on a ride;
  - a **withheld arrival** *(on screen: "arrival unknown")*: a trip's arrival StopDash won't guess
    (`TripTiming.Basis.UNKNOWN`). See *Trips with a change*.
- **Carry-over**: a refresh reusing a recent fetch at its own age. Not **carried forward**: a stop
  whose fetch failed, kept and shown aged.
- **Pending**: waiting on something not done yet: an ask not answered, work queued and not
  started, an opt-in or an update not yet taken up. Name the thing pending (`pendingStops`,
  `pendingOptIn`).
  - *Also used for:* a saved favorite ride StopDash can't follow yet (`PendingJourney`): say
    **unsupported ride**.

## The screen's work

- **The worker**: the dispatcher for work that grows with its input (`LocalWorker`), never the main
  thread (AGENTS.md *Main thread*). Not a WorkManager worker (`WidgetRefreshWorker`): say
  **background job** for those.
- **Slot**: where one stage of a screen's work keeps its answer for the inputs it was worked out
  for (`rememberWorked`).
- **Answer standing in** (stand-in): a slot's last answer, shown for newer inputs while theirs is
  worked out.
- **Held**: a stage keeping its last answer while what it's worked out from is still pending
  (`heldWhile`). ⚑ Keep "held" for this alone: a cold load's collapsed card is a **kept card**,
  faraway favorites are **set aside**, a cached line answer is **kept** (`heldLines` →
  `keptLines`).
- **Report**: what the screen tells the view model it worked out, made only as it changes
  (`Reported`). Not a **bug report** or a **crash report**, which always carry their noun.
- **Settle**: apply an answer so the newest one wins: a lookup's (`StopClosureCache.settle`) or a
  check's verdicts (`StopClosureCache.settling`). Not a list coming to rest: say **hold still**
  (principle 4).
  - *Also used for:* many other things in the code, and new ones keep appearing, so in prose name
    what it does instead. Among them: taking down a time-to-board notification that no longer
    stands (`ActiveTripTracker.settleBoard`): say **take down**; drawing a change only once it has
    held for a moment (`TripScreen.settled`, `NOTE_SETTLE_MILLIS`): say **steady**; and sending or
    dropping what was held until consent was known (`UsageEvents.settle`): say **release**.
- **Snapshot**: the **saved snapshot** (`DeparturesSnapshot`) is what the last refresh saved on the
  phone for whatever reads it later: its departures, and what's needed to show them honestly.
  Its fields say what's kept; notices aren't. A followed trip's summary (`WatchTrip`) is saved
  apart from it. The **list's snapshot** is what the screen draws now. Say which.

## Pages

- **Route page**: the page a tapped row opens for its route, with its departures and stops where
  there are any.
  - *Also used for:* a Planner alternative opened on the trip screen, with its legs and changes
    (`TripViewModel.openRoute`): each alternative is a trip (*Trip*), so say an **open trip**.
- **Line page**: a whole line's page: its status, work coming up, and its map where there is one.
  See *Line page*. ⚑ `TODO.md` and the spec sometimes call the
  route page a line page: use route page.
- **Lines page**: the page a disruptions row opens, on the home screen or a trip: each line's
  status, then whatever that row has to say about its stops, which differs between the two. See
  *Trips with a change*.
- **Lines…** *(on screen)*: the line search in the overflow menu, which opens a line page.
  See *Finding a line*.
