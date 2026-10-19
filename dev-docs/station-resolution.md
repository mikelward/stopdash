# Resolving TfL ids at the edge

**Status: designed, not built** (maintainer, 2026-10-19: "design the station normalization next").
This builds on the station model (`station-model.md`), which says which station and hub any id
belongs to. That doc settled *membership*. This one settles the *mechanism*: where the model is
asked, and which level each question compares at.

## Why

The model is asked in only a handful of places today (`RouteStopsRepository.byModel`,
`StationIndex.twins`, `PlaceDirect`'s `oneStop`, `Hubs.arrivalIdsOf` and `planTargetsOf`,
`StationFinder`). Everywhere else, a raw TfL id from one feed is compared with a raw TfL id from
another. A 2026-10-19 inventory found about a hundred such comparisons. They mix six rules:
- plain `==`;
- stop areas (`stopAreas`, `fromArea` / `toArea`);
- names (`sameStopName`, `exactStopName`, the name-based `sameStation`s);
- TfL interchange or cluster ids;
- the line's model stations (`sameStationId`, `stationIds`, `callingAt`);
- the model itself.

Each bug in the last month (a sibling id here, a stand across the road there, arriving at another
stand, a turnback at another pole) was fixed at the one site that showed it. The next site with the
same shape then failed the same way, and Codex found another. The fix is to stop comparing TfL ids
at all.

## Decision

The maintainer settled two things on 2026-10-19.

**Two levels, two questions.**
- **Station: where you are, and what's closest.** Which station the rider is at, which departures
  they can board there, and ranking stops near them. For a bus, the station level is the stop: both
  directions of the road.
- **Hub: where you're going, and where you change.** Arriving at a destination, getting off to
  change, the ends of a favorite ride, and the end of a trip.

**Arriving is the first call in the destination hub.** A vehicle arrives when it first calls
anywhere in the hub, whatever level the destination was picked at: a station, a bus stop or the hub
itself (the maintainer: any stand counts as arriving, "and maybe the whole hub too"). Which of the hub's stations or stands the timetable names doesn't matter. A
route that calls at one station of a hub and, much later, another of the same hub has arrived at the
first.

The only rail case in the bundle is the Circle line at Paddington, which calls at Praed Street and
at the H&C station. A ride to Paddington gets off at whichever comes first. Buses do this often: a
loop through a bus station, or a stop on each side of the road.

This replaces part of `station-model.md` *Matching*, which said a trip picked to a station ends only
at that station, never another of its hub ("King's Cross is not St Pancras"). Under this decision a
destination is its hub: a train reaching St Pancras has arrived for a trip to King's Cross. It also
supersedes **the named record first** for arriving: a train gets off at its first call in the
destination hub, even where a later call is the id the rider asked for. `SPEC.md` notes both where
it says otherwise (*Departures*, *Trips with a change*), and those notes become the rule when phase 2
ships, as do the same notes on a bus stop picked as a destination (`station-model.md` *Matching*,
`SPEC.md` *Trips with a change*). The planner still plans to the level picked (*Which level each
feature asks*); only the arrival check widens.

**A step-free ride checks every call it uses, against today's lifts** (Codex on #1005, six findings
on step-free arriving, so one rule rather than one per path). With a step-free level set (Station or
Fully):
- **Every call is checked, both ends.** The call the ride boards at and the call it gets off at, on
  whichever line carries it (the Planner's or another `RideLines` offers), are each judged by their
  own station record in `StepFreeAccess`, which is keyed by records (`940G…`, `910G…`): a
  platform id (`9400…`, `9100…`) is looked up by the record it resolves through (`Hubs.recordOf`,
  the record step `stationOf` already takes), never by its raw id, which the table doesn't hold
  (Codex on #1005), with the current lift outages applied (`withLiftsOut` from the
  shared `LiftOutages`, as Direct already does), re-read after a restore. A call naming several
  records passes only where every one does. **A call that names its platform**
  (`Departure.platform`, `VehicleCall.platform`) is judged at that platform: the platforms
  `platformsFor` reads, filtered to that number as `level(record, line, platform)` filters.
  The platform is read in the table's form, the bare number or letter: any "Platform …" label
  through `PlatformDirection.knownPlatformNumber`, TfL's ("Westbound - Platform 1") and Darwin's
  alike (`DarwinBoardDto` wraps Darwin's bare "B" as "Platform B", which `RailFollow.callAt`
  passes on), and a value already bare as given; each feed's mapped form tested against its
  recorded fixture (Codex on #1005, three findings). So a record with mixed access (Cannon Street on the District; `SPEC.md`'s line list keeps
  the minimum because it names no platform) doesn't reject an accessible train for
  another platform's stairs. Only a blank platform, a label with no number (or TfL's "Platform Unknown"), or one the table doesn't describe, falls back to the
  record-wide `levelFor`, the minimum over the line's platforms (Codex on #1005). Only a street-level call passes unchecked,
  by the predicate `PlaceDirect.atStreet` already uses (`bus`, `replacement-bus`, `coach`,
  `river-bus`, `river-tour`), as `SPEC.md` already says of buses ("a bus always does"): the table
  holds no poles or piers (Codex on #1005, twice). A tram or cable-car call is
  judged by its record like any other, since the table describes those platforms too (West
  Croydon's eastbound tram platform has no step-free route; Codex on #1005).
- **Nothing is taken on trust from the plan.** The Planner checked its endpoint when it planned,
  but a lift can have gone out since, so its endpoint is checked like any other call.
- **Getting off**: the first call in the hub that passes; else the Planner's endpoint if it passes;
  else none does, and the ride says so (the leg shows it is no longer step-free and offers a
  replan, *never fail silently*), never sending the rider to a call that fails. A line other than
  the Planner's that can't board and get off at passing calls isn't offered.
- **Rechecked when the lifts change.** A change to the outage set redoes every judgment, an active
  trip's included: its followed train's endpoint is worked out again from its planned legs, so it
  can move later as well as earlier (`OnTheWay.endedAtDestination` today only moves one earlier).
  Only calls still ahead are rechecked: once the rider has boarded, the call they boarded at is
  behind them and keeps its verdict, so a lift failing there never replaces the train they're on
  or offers a replan (Codex on #1005).

**The first call must be one TfL id, or the ride doesn't move** (Codex on #1005, three findings of
one shape). Wherever more than one answer to "which call is first in the hub" is possible, the
first-call rule applies only where every answer is the same TfL id: every candidate path when
`RouteStops` can't pick one (a Circle train of unknown direction), every portion of a dividing
train, and a National Rail call whose CRS names several records (which names no one id at all).
Otherwise the ride gets off at the Planner's or the route's endpoint as today, or, for a Direct row
with neither, the train is left unjudged rather than shown with a guessed stop. One rule, not one
per source: the next source of ambiguity falls under it too.

**A planned change stays at the Planner's call** (Codex on #1005). First call in the hub is where a
trip *ends*. A change between two legs keeps the station the Journey Planner chose, since the next
leg and its transfer walk start there: a Circle train passing one Paddington before the change the
Planner made at the other gets the rider off at the other, as planned. The hub only answers "is the
rider at the change?": a rider who gets off at another station of the hub is at it, and the trip
replans the walk and the next leg from where they are, rather than counting the planned connection.

**Where you are is told from location where it can be.** The rider's station is the one whose
nearest record is closest to them. Within a hub that may tell King's Cross from St Pancras. Where
the nearest station's lead over the next of the hub is under twice the position's accuracy radius,
so that somewhere the rider may be puts the other nearest (Codex on #1005), it can't, and the rider is
"at the hub". Then every station of the hub boards, as the near-me list already shows them. Either
way there's a walk, so getting this wrong costs a row, not a missed train.

## The resolved id

One value, built once where an id enters the app:

```kotlin
/** A TfL id with its places in the station model, resolved once where it entered the app. */
data class Resolved(
    val tflId: String?,   // as TfL gave it: for requests, predictions and closures only; null for a call naming several records
    val station: String?, // the model's station, or a bus stop (both directions of its road)
    val hub: String?,     // the model's hub
    val stops: Set<String> = setOfNotNull(station), // every stop it names: more than one only for a split area
    val hubs: Set<String> = setOfNotNull(hub),       // every hub those stops are in
    val poles: Set<String> = setOf(tflId),           // the exact TfL poles it names: an area's own, never its stops' others
)
```

- `Hubs.resolve(id, pole = null, near = null): Resolved` is the constructor for one id. It's a map
  lookup (the bundle already holds `stationOf`, `stopOf` and `hubOf`), so it can run inline in a DTO
  mapper off the main thread.
- `Hubs.resolveAll(ids): Resolved` is the one other, for a call that names several records (a
  National Rail CRS, which maps to every TfL record of the station): `poles` holds every id, so the
  step-free test can require all of them; `station` and `hub` are set where every id agrees; and
  `tflId` is null, since such a call names no one id to get off at (*The first call must be one TfL
  id*) (Codex on #1005).
- **A split stop area resolves through what came with it** (Codex on #1005). `Hubs.stopOf` answers
  `Membership.Split` for an area whose poles the model files under several stops, and
  `station-model.md` (*An id the app was handed names the area's poles*) already says how such an id
  is placed. The mapper passes the pole the feed names with it (the Planner's `individualStopId`) as
  `pole`, else the leg's own point as `near`, and the area resolves to that pole's stop and hub (by
  `near`, the stop of the area's own pole nearest it). With neither, `station` and `hub` are both null, even where every child stop is in one hub (Codex on
  #1005), `stops` holds every child stop, and `poles` the area's own TfL poles. A null level matches
  nothing at that level, so the id alone never arrives, replans or compares for detours, as today. An
  avoid or a closure check, which acts on every pole the id names, reads `poles`: a child stop can
  hold poles from another area, which the id never named (Codex on #1005). A dismissal keyed by such an area matches a notice at any of `hubs`,
  every child stop's hub, until each pole has settled, as `station-model.md` already requires (Codex
  on #1005): never only one hub, and never none.
- **Unbundled ids resolve to themselves at both levels.** An id the bundle doesn't hold (`Unbundled`)
  is its own station and its own hub: it matches only itself, as `station-model.md` *Edges fail
  safe* already says. Nothing guesses by name.
- A platform id (`9400…`, `9100…`) resolves through its station record, which replaces
  `ArrivalIds.arrivalOf` as a comparison; `platformRecord`'s prefix step stays inside `Hubs`, as
  the record step `Hubs.recordOf` gives the step-free test.
- Equality is `a.station == b.station` or `a.hub == b.hub`, both non-null, chosen per question
  (*Which level*, below). Never `a.tflId == b.tflId`, except where the raw id is the point (*Where the TfL id
  stays*).

## Where it's built

Each feed resolves its ids in its DTO mapper, in `:app/data`, and hands `:domain` resolved values:

| Feed | Today | Resolved |
|---|---|---|
| Route sequences (`TflRouteSequenceDto`) | `naptanIds`, `stopAreas`, `stopHubs` | each route's stops; `stopAreas` and `stopHubs` go |
| Arrivals (`TflArrivalDto`) | `destinationNaptanId` | `Departure.destinationId`, resolved; `Departure.destination` stays the terminus label shown and matched as text (Codex on #1005) |
| Vehicle calls | `VehicleCall.stopId` | the call's stop |
| National Rail boards (`DarwinBoardDto`) | CRS to every TfL id of it | one resolved station per calling point |
| Journey Planner legs (`TflJourneyDto`) | `fromId`, `toId`, `path`, `fromArea`, `toArea`, planned ids | resolved ends and path, a split area through the leg's pole or point; the areas go once that's built |
| Nearby stops, bundled stops, search | `id`, `clusterId`, `interchangeId` | the stop; `clusterId` and `interchangeId` go from matching |
| Line status affected stops | `naptanId` | resolved |
| Saved data (favorites, recents, starred places, active trip, pending ends) | TfL ids plus level | unchanged on disk; resolved on read through `Hubs.picked`, except an as-saved ride end (below) |
| Persisted feed caches: every store that keeps a feed's TfL ids on disk, of which today departure snapshots (`PersistedSnapshot.toDomain`, and the widget's `WidgetSnapshotStore`); route sequences (`FileRouteStopsStore`); nearby, place and journey-end stops (`FileNearbyStopsStore`'s `nearby-stops.json`, `place-stops.json` and `journey-end-stops.json`); and an active trip's planner legs (`PersistedActiveTrip.toTrip`'s `legs`, `vehicleLeg`, `offLeg` and `plannedLegs`, with a process-death test). A store added later joins the rule, not the list (Codex on #1005, twice) | the feed's raw ids | unchanged on disk (the watch reads them); resolved on restore, as their feed's mapper would, under the cold-start rule below (Codex on #1005) |

Saved data keeps storing TfL ids and the level picked, as `station-model.md` *Saved data* settled.
It's resolved when read, so a bundle refresh that regroups a station moves saved picks with it, and
nothing is migrated. A saved pick is read through `Hubs.picked`, which takes the entry's own saved
fields (its pole, name and modes) and the deterministic fallback `station-model.md` sets out for an
area a refresh has since split, never `Hubs.resolve` on the bare id (Codex on #1005): a pre-phase-2
pick keyed by such an area carries no pole or point, so the bare id would resolve to no station.
**Anything resolved while the model is still loading is resolved again once it's ready**, however
it arrived: read on a cold start (saved data, a persisted feed cache), fetched live, or made by the
rider in that window (a trip started, a favorite added). A read waits for the model first, bounded
as `RouteStopsRepository` already waits (`HubsStore.ready`); past the bound it goes on with exact
ids and is resolved again when `HubsStore` reaches a terminal state, an active trip's legs and
endpoint included. A value resolved while the model was still loading would otherwise stay raw for
the session and fail every station and hub comparison. The bound is only for a consumer that
recomputes when its values change (a screen, a flow); **one that won't run again waits for the
terminal state instead**, off the main thread: a write a model rule decides (a favorite's same-hub
and duplicate guards, so the guard never judges raw ids; the add shows once it has run) and a
one-pass worker (`JourneyAlertWorker`, `WidgetRefreshWorker`, whose finished pass a later
re-resolve wouldn't redo). Exact ids are the lasting answer only where the model failed to load (Codex on #1005,
twice).

Two kinds of saved end keep more than one place, as `station-model.md` already requires (Codex on
#1005):
- **A favorite ride kept as saved** (`asSaved`), whatever its ends are, is never hub-matched: it
  stays matched stop to stop at its saved station or stop, so it gains no sibling's services and
  shares no other ride's alerts (`station-model.md` *Favorite rides*). Where an end is an area a
  refresh has since split, it is never read through `Hubs.picked` either: it keeps the area's own
  poles (`Resolved.poles`) and matches a call by those exact ids, never by a child stop's other
  poles (Codex on #1005).
- **A trip end the bundle can't place whole** (an `Unbundled` interchange or stop area, or any end
  with no model) keeps the children it was looked up to and planned to, each resolved, and the
  destination's matchable hubs are all of theirs.

So a destination is a set of hubs, one for nearly every pick: a vehicle arrives at its first call
whose hub is in it. That set is the destination's matchable hubs only: its `hub` where it's placed,
or the children an end the bundle can't place whole was planned to. A split area that came with no
pole or point has a null `hub`, and its `Resolved.hubs` are for dismissal alone, so they never join
a destination: its last ride ends at the Planner's call (Codex on #1005).

## Which level

| Question | Level |
|---|---|
| Does this vehicle reach the destination? Where does the ride end? | hub, first call in it |
| Direct to a place: does this vehicle reach it? Where does the rider get off? | station, first call at any station of the stops within the rider's walk of the place, never the rest of their hubs (Codex on #1005) |
| Is the rider at a change point (getting off one leg to board the next, replanning from where they are)? | hub; the planned change itself stays at the station or bus stop of the Planner's call |
| A favorite ride's ends, a card's and its alerts | hub; a ride kept as saved stays at its saved station or bus stop, and one ending at an area a refresh has since split at that area's own poles (Codex on #1005) |
| Is the origin also the destination (nothing to ride)? | hub |
| Which station is the rider at? Which departures board there? | station, from location where it can tell; else the hub |
| Ranking nearby stops | station |
| Farther cards, the near-me rail cap | hub (unchanged from `station-model.md`) |
| Does the vehicle stop here on the way (passing a stop, off-plan branch, turnback)? | station |
| Avoiding a stop | station |
| Alert fold and dismissal | hub (unchanged from `station-model.md`); a split area's dismissal at any of its `hubs` |

"On the way" stays at the station level. A train that calls at two stations of one hub mid-route is
passing both, and a bus loop's two stands are two places on its way. Only the end of a ride widens
to the hub.

## Where the TfL id stays

The raw `tflId` is kept and used, never compared across feeds, for:
- **TfL requests**: `/StopPoint/{id}/Arrivals`, `/Line/{id}/Route/Sequence` and the rest.
- **Arrival predictions**: a prediction is keyed by the id the vehicle calls at. A ride that ends at
  the first call in a hub gets off at that call's `tflId`, which is where its arrival is predicted
  (option A, maintainer 2026-10-19).
- **Per-pole closures**: a closure notice closes the pole it was filed against (`station-model.md`
  *Matching is by stop, boarding by pole*).
- **Step-free**: TfL gives each record its own step-free level, so the platform a train calls at is
  judged, not a sibling.
- **Boarding**: a bus boards at the pole its route places, and a near-me row is a stand.
- **Saved data and the debug log**: as TfL gave it.

## What it deletes

When the last caller has moved:
- `LineSequence.callingAt`'s rename, `sameStationId`, `stationIds`, `throughStation`,
  `withModelStations` and `stationCodes`;
- `DirectTrips.knowing`, and `PlaceDirect`'s `oneStop`;
- `ArrivalIds.arrivalOf` (the prefix step stays inside `Hubs.recordOf`);
- every stop-area comparison (`stopAreas`, `fromArea`, `toArea`, `areas[id] ?: id`);
- the name tests used for identity: `sameStopName` and `exactStopName` where they decide sameness
  (not display), `RouteStops.sameStation`, `OffPlan`'s and `StopLinks`' `sameStation`,
  `StopPlace.sameNamed` and `sameInterchange`;
- interchange and cluster ids as match keys (`NoticePlacement`, `LineMap.across`,
  `PlaceDirectViewModel`'s avoided-stop test, `DismissedAlert.stopPlaceKey`'s fallback chain);
- `OnTheWay`'s `samePlace`, `pathIndex` fallbacks and private `sameStation`.

`TODO.md`'s open items "drop the fallbacks to TfL's codes and to names" and "the name tests left"
fold into this.

## Plan

Each step is one PR, ordered. The old rules stay until their last caller has moved. Each adds tests
at hubs only (King's Cross & St Pancras, Paddington, Stratford), per AGENTS.md *Privacy*.

1. **`Resolved` and `Hubs.resolve`, unused** (`internal:`). Route sequences carry resolved stops
   beside their raw ids. Tests: St Pancras's three records resolve to one station and the King's
   Cross hub; Paddington's two stations share a hub but not a station; an unbundled id resolves to
   itself.
2. **Arriving by hub, first call.** Replaces the older plan's arrival steps (`station-model.md`
   *Plan*, phases 2 and 3).
   - **Who asks.** `DirectTrips`, `RideLines`, `RouteStops.forLeg`, `FavoriteJourney` and on the
     way's arrival check (`OnTheWay.endedAtDestination`) ask "first call whose hub is one of the
     destination's" (its matchable hubs, *Where it's built*), for a trip's last ride only; and the
     detour scan (`withoutDetours`, run by `TripViewModel` and `LaterTripViewModel` before any
     `LegEnd` exists) reads a route's whole path the same way, so a route whose earlier ride or
     change already reaches the destination's hub ends there, never telling the rider to ride on
     or change after they've arrived (a regression test covers that route shape, Codex on #1005).
     The call it cuts at is the first in the hub that passes the trip's step-free level, with
     today's lift outages (*Decision*'s step-free rule), so a lift out at the first record it
     reaches moves the cut on to the next passing one, never ending a card where the rider can't
     get off (Codex on #1005). Such a route is no longer dropped when another
     arrives no later: the Planner's time at its old endpoint only bounds when it reached the hub,
     so it can't show another route beats it (Codex on #1005). Every such route is kept, as
     `SPEC.md` keeps the only way found, but cut at that call, and its fare (and the pink-reader
     flag) cleared, since the Planner priced the whole detour, as `SPEC.md` already clears a fare
     on a route StopDash assembles: the legs after it go, and the ride that reaches it ends there,
     arriving at the Planner's time there where it gives one (a change there). Where it gives
     none (a call inside a ride), the card shows no arrival time or duration until the route is
     timed, never the old endpoint's. Its arrival being unknown, it isn't ranked by one: it's
     kept past the trip screen's route cap (`TripViewModel.bestOf`'s `MAX_ROUTES`, which ranks by
     arrival) and listed after the ranked routes, so a route the old endpoint would have ranked
     late, though it may arrive first, never loses its card (Codex on #1005). Its
     run (`TripLeg.run`) is unknown too, since the Planner timed only the whole ride: it's
     timed from the followed vehicle's own expected time at the cut call (`VehicleCall.expected`),
     which `ActiveTripTracker` already reads once the rider is on their way. The planning screens
     add no request for it: there the card shows its departure, says where it gets off, and
     leaves the arrival blank, never boarding time plus the old run, so no new TfL call, wakeup
     or cost comes with it (Codex on #1005, six findings;
     `SPEC.md` notes it under *Trips with a change*); a favorite ride
     kept as saved stays stop to stop (*Where it's built*).
   - **Each leg carries where it ends** (Codex on #1005, four findings that each path needs to know
     whether a leg is a trip's last ride). Rather than thread a flag through every path, each
     route's legs are turned once into `(TripLeg, LegEnd)` pairs, where `LegEnd` is the trip's
     step-free level with one end shape, a level and a set at it: the ride gets off at its first
     call whose place at that level is in the set (Codex on #1005, twice: a variant per caller
     kept missing one, so one shape replaces the list). Each caller fills it in:
     - a middle ride: the station or bus stop of the Planner's call (its `Resolved.stops`), the
       next leg and its walk starting there: a substitute line calling at another record of that
       station still gets off there, while the hub's other stations (Paddington's two) stay apart,
       with a sibling-record test (Codex on #1005);
     - a last ride: the destination's matchable hubs (*Where it's built*);
     - Direct to a place: the stations within the rider's walk of it, never the rest of their
       hubs, so a ride neither misses another record of a reachable station nor takes the rider
       to an out-of-reach sibling;
     - a favorite ride kept as saved: its saved end's station or bus stop (`Resolved.stops`), so
       it stays stop to stop as `station-model.md` promises, a sibling pole or another record of
       that station included (Codex on #1005);
     - a favorite ride ending at an area a refresh has since split: that area's own
       `Resolved.poles`, so it neither loses a saved pole nor gains a child stop's others, with a
       test of a split-area favorite after a bundle revision.
     The step-free level rides on every end, so every
     leg, middle or last, checks a substitute line's boarding and alighting calls (*Decision*'s
     step-free rule) and keys its caches on it (Codex on #1005). A last ride gets the
     destination's matchable hubs only where the Planner's own call for it is in one of them; one the
     Planner gets off outside them and walks on from ends at the Planner's call like a middle ride:
     a trip to a place (`TripDestination.Place`, which has no hub), or to a bus stop reached by
     riding to a neighboring stop and walking the last stretch (`SPEC.md` allows it). Phase-2 tests
     cover both route shapes (Codex on #1005, twice). The pair goes in at the bottom, not path by path: every function that decides where a
     ride gets off or which stretch of a line it covers (`RouteStops.forLeg`, `RideLines.runTo`,
     `DirectTrips.judge`, and the favorite alerts' disruption span, `JourneyAlerts.ridesOn` and
     `ridesOf`, with a cold-start test without a widget pin; Codex on #1005) takes a `LegEnd`
     and has no form without one, so every caller has to supply it, a prefilter included
     (`RideLines.polesToRead`, which `ActiveTripTracker.boardOf` runs before `RideLines.of`, with
     an active-trip test; Codex on #1005, the third finding of a path missed from a list). Every
     cache or map of legs keys on the pair. Today's callers, as a check rather than the rule: the trip paths into
     `DirectTrips` (`OnTheWay.placeTrains` and `routed`), `RideLines.of` (today a map keyed by
     `TripLeg` alone, flattened across routes), `RideLines.through` and `throughWays` (which
     `TripRideLines.withThroughRoutes` runs before a through route exists, so each takes the
     second ride's pair and its `runTo` gets off by that ride's end; a test covers a line reaching
     the destination's hub at a sibling call, Codex on #1005), and `TripVerdicts` (the trip screen's `warm` and
     `warmStops`, read back through `byStops` and `get`). So the same leg, last in one route and in
     the middle of another, is two keys and is never judged once for both. `TripVerdicts` also keys
     on the lift-outage set it applied, since a change to `LiftOutages` makes its verdicts stale. Since `LiftOutages` only asks TfL when a caller asks it (`current()`), the trip screen
     and on the way ask it while a step-free trip is open, at the cache's own pace (one shared
     request at most every five minutes, `LiftOutages.MAX_AGE`, whichever screen asks; no wakeup
     beyond the open screen's), and re-warm the verdicts when the
     set changes (Codex on #1005).
   - **Where the hubs come from.** The destination's from the trip's own pick (`Hubs.picked`),
     passed in resolved; each call's from the route sequence phase 1 resolved, or from the
     planner legs this phase resolves (below). Nothing resolves inside `:domain` (Codex on #1005).
   - **Feeds resolved here, not in phase 3**, because these checks read them (Codex on #1005,
     twice: every feed an arrival or detour check reads resolves in this phase):
     - Journey Planner legs and paths (`TflJourneyDto`), at the mapper, a split area placed
       through the leg's pole or point, and an active trip's legs restored from disk the same way:
       the detour scan reads a route's whole path before any route sequence is in hand, so it
       needs them resolved here;
     - vehicle calls: a call's `stopId`, or for a National Rail call, which leaves `stopId` blank,
       every TfL record of its CRS in `stationIds` (`RailFollow.callAt`), resolving to one station
       and hub. Such a call names no one record to get off at or to take a prediction for, so where
       it is the first call in the hub the ride gets off where the route or the Planner says
       instead (Codex on #1005);
     - National Rail board calling points (`Departure.callingAt`), which `DirectTrips.judge` reads
       before the line's route, each keeping its CRS's records as candidates (`Resolved.poles`).
       Each portion becomes an ordered list of those points, in Darwin's calling order, where
       `CallingPortion.stops` is a set today: the first-call rule needs to know which of two calls
       in the destination hub comes first (Codex on #1005). A calling point whose CRS maps to no
       TfL record keeps its place as an unresolved entry, where the mapper drops it today: one
       before the first resolved call in the hub could be an earlier call there the bundle can't
       name, so the ride then gets off where the route or the Planner says instead (Codex on
       #1005). A train that divides takes a calling-point endpoint only where every portion's first
       call in the hub is the same; where they differ, the rider's carriage is unknown, so it falls
       back the same way (Codex on #1005).
     - an arrival's destination (`Departure.destinationId`, from `destinationNaptanId`), at the
       arrivals mapper, which `DirectTrips.judge` reads to pick the departure's path; the rest of
       arrivals waits for phase 3 (Codex on #1005);
     - nearby, place and journey-end stops (`TflStopPointDto.toStopLocationOrNull`), at the
       mapper: Direct to a place boards at the nearby ones and ends at the place's, comparing
       them at station level, so a live lookup resolves here as a restored one does (the
       persisted-stores rule), each path with its own test (Codex on #1005).
   - **Step-free.** `forLeg`, the `DirectTrips` trip paths, `RideLines`, the detour scan (`withoutDetours`) and `OnTheWay.endedAtDestination` take
     the trip's step-free level and apply *Decision*'s test. `ActiveTrip` and `PersistedActiveTrip`
     gain that level, so it survives process death; a trip saved before the field reads it as
     unknown, and an unknown level never widens. The field takes `station-model.md`'s version
     rule: `FileActiveTripStore`'s version goes up one, and a file of a newer version is kept
     unread rather than rewritten, so an older build can't drop the level (Codex on #1005). The
     test, both ends and the outage recheck are *Decision*'s step-free rule; this phase adds the
     replan offer and lets an active trip's endpoint move later when lifts change, with a test for
     that transition.
   - **Origin is destination** moves to the hub in this step too, so From and To in one hub plan
     and show no ride once arriving is hub-wide (Codex on #1005). Only for a trip's destination:
     Direct to a place keeps comparing the origin with the stations in walking reach, since an
     origin in the same hub but out of reach still has a ride to show (Codex on #1005).
   - **Direct to a place** (`PlaceDirect`, and `DirectTrips` called from it) asks instead for the
     first call at a station of one of the stops in walking reach: a place is not a hub, and a hub
     member out of reach doesn't count (`station-model.md` *Which level each feature asks*). With
     step-free on, that call's own record must meet the level, as `PlaceDirect` checks each reached
     id today; an earlier sibling that doesn't is passed over for a later call that does (Codex on
     #1005). Its row gets off at that call's TfL id, which #1003 left to this step.
   - **Favorite rides kept as saved are marked first.** `FavoriteJourney` can't move to hub
     matching before the rides it must leave stop to stop are known, so this phase brings forward
     `station-model.md` *Favorite rides*' `asSaved` flag, its `PersistedFavoriteJourneys` version
     bump, and the marking (rerun when the bundle's revision changes), all landing before or with
     the switch (Codex on #1005). Adding a ride moves with it: a ride whose two ends are one hub is
     refused, and one joining two hubs a saved ride already joins says it's already a favorite
     (`station-model.md` *Favorite rides*), since a ride added after the marking is hub-matched
     (Codex on #1005).
   - This deletes the `callingAt` rename and `stationIds` from those paths. Tests: a Circle ride to
     Paddington ends at the first Paddington; a Thameslink ride to the King's Cross hub ends at St
     Pancras low level; a change at one Paddington stays there when the train passes the other first.
3. **Arrivals resolve at the mapper.** Arrivals hand `:domain` resolved values, as planner legs,
   vehicle calls and National Rail boards already do from phase 2. One arrivals field goes in
   phase 2 instead: `Departure.destinationId`, which `DirectTrips.judge` resolves a departure's
   path by, so it can't be left raw once phase 2 drops the station-id fallbacks (Codex on #1005). On the way's `samePlace`, `pathIndex` name fallback and the
   stop-area tests go.
4. **Where you are.** The rider's station from location, falling back to the hub where the nearest
   station's lead over the hub's next is under twice the accuracy radius (*Decision*). Boarding and
   move to it. The stop-area and name rules for origins go.
5. **The rest.** The remaining interchange, cluster and name sites (*What it deletes*) move to the
   model, and the dead helpers are removed.

## Risks

- **A hub too wide for a destination.** A hub takes in a station's bus stops within 200 m. A trip to
  a station could then "arrive" at its bus stop. That's the decision: the rider is at the place they
  asked for, and the stop is within a short walk.
- **A ride inside one hub.** Two stands of one hub are already refused as a favorite ride
  (`station-model.md`). A planned leg inside one hub (a bus between a station's two bus stops) has
  zero length at the hub level. It keeps its planned stands and isn't judged by arrival.
- **Unreadable bundle.** The build fails without a model (#1003), so an installed app always has
  one. A bundle unreadable on the device resolves every id to itself: exact matches only, as today's
  `Unavailable`.
- **King's Cross line tags.** The bundled index lists Thameslink and Hull Trains at all three of the
  hub's rail stations, which looks like hub-wide tagging rather than real calls. Arriving by hub
  doesn't depend on it, but "on the way" at the station level does, so phase 1's tests check the
  route sequences, not the index's line tags.
