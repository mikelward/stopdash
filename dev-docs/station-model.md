# Stands, stations and hubs: one model

**Status: phase 1 in progress** (maintainer, 2026-10-16: "write it up as a design first"; `TODO.md`
tracks which of phase 1's three PRs have landed). This
is the developer-facing design for one shared answer to two questions every screen asks: "is this
the same station?" and "is this the same hub?". It replaces the rules each screen keeps today.

## End state

Once all four phases have shipped (*Plan*):
- **One model answers it everywhere.** `Hubs` in `:domain` says which station and which hub any
  id belongs to. The weekly bundle refresh works membership out and ships it in the APK; the
  device only reads it, with no request at runtime, and no screen keeps a grouping rule of its
  own (phase 4 deletes the last).
- **Three levels.** A **stand** is a platform or a pole. A **station** is the records sharing
  TfL's ICS code and a mode family (St Pancras's three records are one); for buses the same level
  is the **stop**, both directions of a road under one name. A **hub** is a TfL interchange, or
  a lone station, with each station's own bus stops within 200 m; a junction of bus stops is a
  hub too. Each level has one id and one place, and its distance from anything is its nearest
  record's or pole's.
- **The rider gets the level they picked.** St Pancras's page and search row open St Pancras;
  the King's Cross row, which names two stations, opens the hub. A trip plans to, arrives at and
  replans at that level: a station's records plus one bus stop it owns, a hub's stations plus
  one bus stop, or one bus stop. Avoiding a station avoids all its records. Direct aims from the
  level's place and keeps its walking reach.
- **Matching is by stop, boarding by pole.** The pole across the road counts as the same stop
  for a bus's end, but a bus boards at the pole its route places, and a closure belongs to the
  pole it was filed against.
- **Alerts fold and are dismissed by hub.**
- **A favorite ride is between two hubs** and shows every line between them, each confirmed by
  its route, from a bounded request budget. Rides saved before that the hub rule would make one
  stay as saved, stop to stop.
- **Saved data keeps TfL's ids** plus the level each pick was made at, versioned. Nothing is
  keyed by a model id and nothing is migrated.
- **Edges fail safe.** A stop the bundle doesn't hold stays on its own until a refresh takes it
  in; an unreadable bundle makes the model a typed `Unavailable`, each caller then matching only
  the exact id it holds (an interchange still planned through its looked-up children), and the
  screen says what wasn't checked.

## Settled

Ten things were settled on 2026-10-16:
- what a station is (*Why ICS and a mode*);
- that a bus stop is both directions of its road, since TfL sometimes names the pole across it;
- when two bus stops are one junction (*Junctions*);
- that a favorite ride is between two hubs (*Favorite rides*);
- that the top level is called a **hub** (*Why "hub"*);
- that the trip planner keeps one request per record of a station, as today (*For the
  maintainer*);
- that a station's own bus stops reach 200 m (*For the maintainer*);
- that the weekly bundle refresh works out every stop's station and hub, and the bundle is their
  only source (*Where it's built*);
- that a stop the bundle doesn't hold stays on its own until a refresh takes it in (*Where it's
  built*);
- that rides saved before which the hub rule would make one ride stay as saved (*Favorite rides*).

Every existing rule has a fate (*Every existing rule, and what happens to it*).

Favorite rides stay (maintainer, 2026-10-16), so phase 3's ride work (*Favorite rides*, *Deriving
every line between two hubs*) goes ahead, and nothing is left open. `TODO.md` (*Finding stops*)
tracks the work.

## Why

StopDash answers the question "where is this stop?" in at least six ways, each written for one
screen and widened one review finding at a time. The 2026-10-15 St Pancras fix was the fourth
finding on station-code matching alone. Since then, `TODO.md` has recorded each new gap as it was
found, and each is the same mismatch between two rules:

- **St Pancras International** is three TfL records: `910GSTPX`, `910GSTPXBOX` (the Thameslink
  low level) and `910GSTPADOM` (HS1 domestic). The maintainer settled on 2026-10-15 that it is one
  station. But each rule groups the records differently:
  - the name-and-mode rule joins STPX and STPADOM;
  - TfL's station code joins STPX and STPXBOX;
  - the name-prefix rule, the National Rail code and the search's member names join all three.
- **Avoiding a station** matches one exact id, so avoiding `910GSTPX` does not avoid its low level
  (`TODO.md`: *A closed station's other ids*).
- **A trip to a station with no TfL interchange** plans to that one id. It counts none of the
  station's own bus stops. Adding a favorite ride does count them, through `StopPlace`.
- **The trip planner plans to each record of an interchange.** A trip to King's Cross St Pancras
  plans to St Pancras three times, once per record, besides King's Cross and the Underground.
  That stays (maintainer, 2026-10-16; *For the maintainer*, item 1): one request per record finds
  every journey, and a merged one needs a check kept in step with TfL's service.
- **The "station's own bus stops" reach** is 150 m in `StopPlace`. Separately chosen distances
  answer similar questions elsewhere: 250 m twice (`SAME_STATION_METERS`, `NAME_REACH_METERS`),
  320 m, 400 m twice, and 200 m or 290 m inside one interchange on the way.
- **Whether a stop's TfL cluster is real** is tested three ways:
  - it differs from the stop's name;
  - it starts with `490G`;
  - any value is taken, the name fallback included.
- **A restored snapshot** keeps each stop's cluster but not its interchange. So after a cold start,
  the alert fold keys a stop by its cluster until the next refresh.

## Every existing rule, and what happens to it

Every rule in the code today that decides whether two ids are one station, one stop or one hub, or
that groups stops, has a fate below (audit, 2026-10-16, of `main` at 23cfee5). Each is kept, or
replaced and in which phase. Nothing that decides membership is left
out. The code's `hubId` fields hold TfL's interchange id, not a hub in this design's sense.

### Stand to station

| Rule today | Asked by | Fate |
|---|---|---|
| `StationIndex.stationOf`: the platforms TfL files under each station, then the `9100`→`910G` / `9400`→`940G` prefix | planner legs, stop details, changes on foot | **Kept** as the model's first step, stand to record |
| The planner's stop pair (`TflJourneyPointDto.stopPair`: a `490G` id becomes a leg's area) | a leg's from and to stop | **Kept** as a source: the area resolves to its stop |

### One station under several records

| Rule today | Asked by | Fate |
|---|---|---|
| `StopLinks.sameStation`: name and modes, then one interchange or `SAME_STATION_METERS` (250 m) | the board asking every id; stop details' `ownIds` and `linesById` | **Replaced** by ICS and a mode (phase 2); deleted with its constant (phase 4) |
| `StopLinks.openId`: From and To on a station under several records open its whole interchange (Codex on #664) | a station page's From and To | **Replaced** by the station itself, every record of it (phase 2), so From and To on St Pancras's page are St Pancras, not King's Cross & St Pancras. The workaround existed only because nothing else gathered a station's records |
| `StationIndex.twins` / `foldTwins` | search's twin fold, stations with no interchange only | **Replaced** by station membership, inside interchanges too (phase 2) |
| `RouteStops.stationCodeOf`, `sameStationId`, `stationIds`, `throughStation`: route sequences' `icsId` | getting off, far ends, Direct, `RideLines`, `FavoriteJourney.matches` | **Replaced** by the bundled ICS code and mode (phase 2). A stop the bundle doesn't hold is a station of its own until a refresh takes it in (*Where it's built*), never grouped by a route's `icsId`. The named-record-first order (`candidatePaths`, `forLeg`) is **kept** |
| `RouteStops.sameStation`: one name the other plus a space ("… LL"), through `LineSequence.callingAt` | Direct, rides, off-plan, on the way, the route page | **Replaced** by station membership (phase 2). A name never pairs two stops, a stop the bundle doesn't hold included (*Where it's built*) |
| `OffPlan`'s name-only matches, with no interchange check | off-plan branch detection | **Replaced** by station membership (phase 2): a name alone is what joins two stations wrongly |
| On the way's `sameStation` and `stationPlaces` | passing a stop | **Replaced** by station membership, and by the stop for a bus (phase 2) |
| `PlaceDirect.oneStation` | a Direct row's off stop | **Replaced** by station membership (phase 2) |
| `RailStationCodes`: National Rail's CRS, a Darwin calling point to every record | National Rail times | **Kept** as the bridge from a CRS to TfL ids, which resolve to one station (St Pancras's CRS and ICS agree) |
| `StationIndex.memberName`, `hubNames`, `StationLead.stopRank` | search's name rows and their order | **Kept** for their names and order. But a member row now carries its station's lead record, not the interchange id (`hubNames` builds it as `StationMatch(hub, …)` today), so picking St Pancras's row opens St Pancras, not King's Cross & St Pancras (phase 2). A row folded from two stations' names keeps the interchange, as today: the King's Cross row takes in the Underground's King's Cross St. Pancras, and no level short of the hub holds two of its stations |
| `StationMatcher.codeOf` | finding a station by its code ("kgx") | **Kept**: search only |

### Stop: both directions of a road

| Rule today | Asked by | Fate |
|---|---|---|
| `clusterId`: TfL's `stationNaptan`, else the stop's display name | everything below | **Replaced** by the model's stop (search's rows in phase 2, the rest in phase 3): a real stop area, the poles joined to it, or a station's own id. The name fallback stays a display key only (`StopGrouping`), never a stop |
| Whether a cluster is real, tested four ways (differs from the name; has no space; starts `490G`; any value): `stopPlaceKey`, `NoticePlacement`, `YourStops.stopPlace`, `MainScreen`, `JourneyPair.end`, `BundledStops.positions`, `TowardStops.key`, `BusStopIndex`, `NearbySelection`, `MainScreen.placeOf`, `destinationIds` | alert placement, starred places, search, near-me, trips | **Replaced** by one `Hubs.stopOf` (`YourStops.stopPlace` and `BusStopIndex` in phase 2, the rest in phase 3). The display grouping (`StopGrouping.clusterKeyOf`) keeps its own key, since it only groups what's drawn |
| Stop-area equality: `RouteStops.endsAt` / `forLeg`, `FavoriteJourney.matches` tier 1, on the way, `BusPlacement`, `RideLines`, `LineMap.oneWay`, `TripPlan.passedAt`, `Terminating.nearer` | ends, placing a bus, the route map, termini | **Kept** as "the same stop", asked of `Hubs.stopOf` (phase 3), so the pole across the road in another area, or in none, matches too |
| Stop-area equality in closures: `TripClosures`, `TripViewModel.closureStops` / `areaPoles` | which poles a trip asks about, and how it stands by them | **Kept per pole**. The model's stop says which poles to ask, every pole of it, so the one a bus is placed on is asked whichever area TfL files it in (phase 3). But a closure stays the pole's it was filed against: a trip's standing is judged at the pole its bus uses (`TripClosures.judge`), never by a sibling's notice, as `TflClient.poleDisruptions` keeps each pole's own notices today, and a notice filed against one pole alone still sits under that pole's heading (SPEC *Disruptions*), wherever `NoticePlacement` now takes the place from |
| `BusPlacement`'s `sameStopName` fallback, for a stand the planner gives no area | placing a bus leg on its route | **Kept**: it finds a stand on a route from a name, which is not a membership question |
| `sameStopName`, `exactStopName`, `matchStopName`, `cleanStopName`, `conflictingQualifiers` | route stops, termini, every name rule | **Kept** as name tools where ids are missing, never deciding membership where ids exist. That closes *Consider matching stations by id rather than name* (`TODO.md`) |
| `LineMap.across`: same interchange and name, else a name within `ACROSS_METERS` (400 m) | the route map's two directions of one stop | **Replaced** by the stop alone (phase 3): two poles are drawn as one stop's two directions only when the model puts them in one stop, so stops it keeps apart stay separate rows even when they share a hub and a name, such as a same-named area split for spanning more than 250 m (Codex on #890: a hub-and-name fallback would reunite them and put a way-back closure or a rider's stop on the wrong row). An `Unbundled` pole pairs only by the stop test itself, one name and within 250 m. The constant goes (phase 4) |
| `Terminating.nearer` / `nameKey` | the near-me list hiding a bus that ends here | **Replaced** by the stop or station of TfL's destination id (phase 3), the name only where TfL gives no id, as today. That closes *A bus ending at another pole of the place it's listed at* (`TODO.md`): every surface judges from the same tables |

### A station's own bus stops

| Rule today | Asked by | Fate |
|---|---|---|
| `StopPlace.samePlace`: one interchange, one side a station | adding a ride, farther cards | **Replaced** by owner rule 1 (phase 3) |
| `StopPlace.sameNamed` / `nameRoot`: the part before the slash, 250 m, line qualifiers | a ride's pair check and far end, search's fold, farther cards | **Replaced** (phase 3) by owner rule 3 (either side of the slash, between a bus stop and a station) and by the junction rule between two bus stops (*The model*). The qualifier check is kept inside both |
| `StopPlace.ownStop` and `OWN_STOP_METERS` (150 m) | as above | **Replaced** by owner rule 4 and its 200 m reach (maintainer, 2026-10-16), built into the model in phase 1; phase 2's station targets use it, and `StopPlace`'s own callers move in phase 3 |
| `NAME_REACH_METERS` (250 m) | the name rules | **Kept**: one constant for every name rule |
| `JourneyPair`: one place at both ends, `placeStations`, `withPlace`, its 400 m lookup | adding a ride | **Replaced** by hub lookups, a ride being between two hubs (phase 3). The lookup goes inside the bundle's area |
| `FavoriteJourney.destinationsOn` (`sameNamed`) | a ride's far end | **Replaced** by the far hub (phase 3) |
| `Journeys.WAY_BACK_RADIUS_METERS` (400 m), when nothing else matches | a ride's way back | **Replaced** by the hub (phase 3): the way back runs between the same two hubs. A stop outside the hub no longer stands in |
| `FartherBuses.candidates` (`ownStop`; keyed by interchange and name) | the farther bus cards | **Replaced** by owners, keyed by hub (phase 3) |
| `StationIndex.foldNeighbors` / `folds`, and `BusStopIndex`'s same-named rows | search folding a stop into another's row | **Replaced** (phase 3): a bus stop folds into its owner station's row, and other bus stops into their hub's row |
| Either side of the slash, already: `StationMatcher.namesOf` (search) and `AlertStops.crossNames` (an alert naming a stop by the station after its slash, with the stop's letter) | search, alert marks | **Kept** |
| `MainScreen`'s alert-mark `shortName`, the part before the slash | the label of a route stop an alert names | **Kept**: a label, not membership |

### Interchange (TfL's `HUB…`)

| Rule today | Asked by | Fate |
|---|---|---|
| `hubNaptanCode`, the bundle's `hub` columns, routes' `topMostParentId`, `RouteStopsRepository.hubOf`, `HubInfoCache` | everywhere a TfL interchange is read | **Kept** as owner rule 1's source and the interchange's name. The fields are renamed to say interchange (phase 1) |
| `HUB` prefix checks (`RouteDisruptionChecks`, `TripScreen`, `PlanTargets`) | closures, trips | **Kept** behind `Hubs`: an interchange is a hub with a TfL id |
| `stopPlaceKey`: interchange, else real cluster, else stop | the alert fold and every dismissal's key | **Replaced** by the hub (phase 3). Stored dismissals keep their keys, and matching resolves both a stored key and an alert's stop to their hubs, so nothing is migrated. A new one is keyed the same way, by a TfL id, never by a hub's model id (*Each level's id*: a station hub's is `1001276:rail`): the interchange's `HUB…` id, else the TfL id of the record or pole whose notice was dismissed. A hub-keyed dismissal is pruned only once every member the model lists for that hub has settled its closure check: its newest answer, from whichever refresh or screen asked, succeeded and is newer than the dismissal, SPEC's *newest answer* rule for a stop applied to every member (`stopsDisruptionUnknown`, `StopClosureCache.Ask`, `DismissalMarks`). Members needn't settle in one refresh (Codex on #890: a hub larger than nearby paging or the ride-request budget would never settle in one): each member's newest settled answer is kept beside the dismissal until every member has one, so a big hub settles over several refreshes. That progress is **stored with the dismissal** in `DismissedAlertsStore`, not in `StopClosureCache`, which is memory only (Codex on #890: a background refresh's process can be killed before the next, and progress held in memory would start over every run, so a hub no single run can cover would never prune). Each stored hub dismissal carries the members whose newest answer since it was made came back clear, and no others: each member's newest answer rewrites that member's entry, so a clear answer adds it and any other answer takes it out, whether it found the notice again, failed, or never answered (Codex on #890). An answer asked before that member's newest, by `StopClosureCache.Ask` order, changes nothing, so an older clear answer landing late never adds back a member a newer failure took out. Dismissing the alert again starts the list empty. It is an added field with an empty default, so no schema version moves: a set written before it reads back with no progress, its dismissals standing until every member settles again. An older build that opens the set drops the field and reconciles by today's rules, which can prune a hub-keyed dismissal before every member has settled (the gap `TODO.md`'s *Prune a hub-keyed dismissal only when every member was checked* records): the dismissed card comes back, which is the direction this store already fails in (`DismissedAlertsStore`: a dismissed card returns rather than a warning hides), and only on a downgrade, which only a developer meets (*A saved level comes with a version*). A version bump would be worse, not safer (Codex on #890 asked for one): a build that doesn't know the set's version reads it as empty and writes over it, so every dismissed card returns at once, not one hub's, which is why `PersistedDismissedAlerts` already adds fields without one. A member not asked since the dismissal, or whose newest lookup failed, keeps the hub's dismissal, so `reconcileDismissals` adds a hub to its checked places only then: the membership `TODO.md` *Prune a hub-keyed dismissal only when every member was checked* found missing. The trip screen's and a trip on the way's checks (`stopDismissalCheck`) settle each stop as its own place, as today, never a hub |
| `NearbySelection.placeOf` | the near-me list's cap on rail stations | **Replaced** by the hub (phase 3); no change for an interchange |
| `FartherStations.placeOf`, `fartherReached` | farther station cards | **Replaced** by the hub (phase 3) |
| `StopLinks.linksOf`'s same-interchange chips | a station page | **Replaced** by the hub's other stations (phase 3) |
| Search dropping an interchange's members, `rank`, `leads` | search | **Replaced** by the hub (phase 3) |
| `OnTheWay.CHANGE_ON_FOOT_IN_HUB_WITHIN_METERS` (290 m) | whether a change is on foot | **Kept**, read for one hub rather than one interchange. The 290 m cap stays, so King's Cross to St Pancras is still a walk |
| `FavoriteJourney.originHub` | a ride's origin | **Replaced** by the hub (phase 3) |
| The Direct section's avoids (by id, interchange or cluster) | Direct rows | **Replaced** by the avoid's own level, station or stop (phase 2) |
| `MainViewModel`'s interchange alert titles | alert headings | **Kept**: a hub's title is TfL's interchange name, else its station's |

### Trips and avoiding

| Rule today | Asked by | Fate |
|---|---|---|
| `PlanTargets.of`: each member that isn't a bus stop, plus the first bus stop | trips to an interchange | **Kept per record** (maintainer, 2026-10-16: no merge). What changes is the level (phases 2 and 3): every record of the station or hub picked, plus one bus stop it owns |
| `PlanTargets.via` / `viaStops`: every station record, bus stops only where there's no station | a trip via a stop (SPEC *Going via a stop*) | **Kept per record, never merged**. The Planner counts a route as passing only the exact id it was asked via, so merging St Pancras's three records would drop a route through the one not named. What changes is the level the via resolves at (phases 2 and 3): a station picked as via is every record of that station, a hub every record of its stations, a bus stop that stop |
| `destinationIds`: a member's id and cluster to the trip's end | a trip's arrivals | **Replaced** by the ids of the level the rider picked (the station and stop levels in phase 2, with the bus target that phase adds, and the hub level in phase 3): a hub's every station record and bus pole, a station's records plus the bus stops it owns, or a stop's poles, the same ends the trip is planned to (*The rider picks a level*). Each bus stop counts with its poles' TfL stop areas (`490G…`) too, as today's member cluster does, since the Planner names a bus call by its pole or its area, but only an area kept whole: a split area's poles lie in several stops, so its id maps to none of them (Codex on #890), and a leg named by it is placed at one of its poles instead (*A TfL stop area's id resolves too*). One `Hubs.idsOf(level)` gives it, so no caller rebuilds it: a map from every id TfL can name the level by, its stands as well as its records, poles and stop areas, to the stop or station that id belongs to, the shape `destinationIds` has today. A platform maps to its station, since the Planner can name a train's end by its platform alone (*A platform TfL lists under several records*, below); one `Unresolved` between two stations is in neither map. The map holds only bundled ids, so an id it doesn't hold is resolved on the way through `Hubs.stationOf`, the same prefix step that places a platform the bundle doesn't hold (`9400…` to `940G…`), and counts when that station is one of the level's: a platform TfL adds after the bundle was built still arrives, replans and compares as its station. Its keys say when a trip has arrived and where it replans to, so a trip to St Pancras arrives only there, never at King's Cross. Its values are what `withoutDetours` compares (its `stopOf`), so a call at one pole or its stop area and an end at a sibling pole are one stop, as today, and a call at one of St Pancras's records and an end at another are one station |
| `AvoidedLines.drops`: an exact id against from, to, area and planned ids | avoided stops | **Replaced** by the station's every record, or the stop (phase 2). Stored avoids keep their ids |

### Not membership, kept

| Rule today | What it is |
|---|---|
| `DirectTrips.ORIGIN_RADIUS_METERS` (320 m) | how far from the rider a stop counts as where To… starts: the rider's reach |
| `OnTheWay.MISSED_WITHIN_METERS`, `NearbyStopsCache.REUSE_WITHIN_METERS` (150 m each) | a missed stop, and reusing a nearby lookup |
| `StopGrouping`, `DepartureRows`' 50 m `TOGETHER_SLACK`, `CollapsedPlaces`, `MainScreen.placeModes` | how lists are drawn. A station's platforms are now listed nearest the rider first |
| The starred-places key (cluster or stop) | stored as it is, with the level of the page it was starred from (phase 2), and resolved through the model on read |
| The snapshot's cluster | kept for the display grouping. A restored list folds alerts through the bundled `Hubs` lookup by each stop's id, as a live one does, so no interchange id is stored, and an `Unbundled` stop folds by its exact id after a restart as before it (phase 3; Codex on #890: a stored interchange id would join an `Unbundled` stop to that interchange on restore alone) |

### Saved data

Everything saved stores TfL's own ids, at whatever level the rider picked:
- favorite rides store stop and area ids;
- pending ends store the search id (interchange, area, pole or station);
- starred rows, watched stops and recents store stop ids;
- dismissals store `stopPlaceKey`;
- avoids store an area or stop id.

None stores an id this app made up, and none is migrated; every write is additive, beside the ids,
with a schema version bump wherever an older build would drop it (*A saved level comes with a
version*): a favorite ride's `asSaved` flag (*Favorite rides*), the bundle content revision the
rides were last marked against, each ride's derived-lines cache and candidate cursor, the cursors
the route-sequence, arrivals and line-status budgets keep, and from phase 2 the level each pick
was made at.
One shape is read with care, wherever it was saved: before phase 2 an
interchange's member row carried `StationMatch(hub, …)`, the interchange's id with the member's
name, and every store that kept the pick kept that pair. That is pending ends
(`PendingEnd.Station`), the From, To and Via recent searches (`FileRecentStationsStore`, which
also keeps the row's `lead` modes), and any other store that persisted a picked `StationMatch`. So
the model reads it in one place (`Hubs.picked(id, name)`), and every one of those stores resolves
through it on read rather than each keeping its own rule. A saved interchange id whose name is one
of its member rows reads as what that row carries from phase 2: its station, the level the rider
picked, or for a row folded from two stations' names, the hub. The King's Cross row folds in the
Underground's King's Cross St. Pancras, so an end saved from it keeps both stations, as the hub,
rather than losing the Underground. Any other reads as the hub. So reopening a
saved St Pancras recent opens St Pancras, and plans to it, not the whole interchange. Pending
journeys are matched, for replacement and to drop a duplicate, by their ends resolved that way,
not by the stored key, so a new pick of the same row still finds the old one; a recent picked
again replaces the old entry the same way.

**From phase 2 a pick is saved with its level** (Codex on #890: the third finding on reading a
saved pick, so the level stopped being inferred). One id can name two levels: a junction's hub
id is its anchor stop's, and a stop and the junction around it plan and arrive differently. So
every place a pick is kept writes the level the rider picked (`station`, `stop` or `hub`) beside
the TfL id it already keeps, additive like `asSaved`, and `Hubs.picked(id, name, level)` resolves
the id to that level through the model on read.

**A saved level comes with a version** (Codex on #890: the second field after `asSaved` that an
older build would drop, so the rule now covers every store, not one field). An older build reads
past a field it doesn't know and drops it on its next write, and a pick that has lost its level
reads as one saved before phase 2 does, so a junction picked whole, saved under the id of the stop
it was picked through, would come back as that stop. So phase 2 bumps the schema version of every
file store that saves a pick, and each keeps a file of a newer version rather than overwriting it,
as `DataStoreFavoriteJourneysStore` already does:
- favorite journeys, for their pending ends (`PersistedFavoriteJourneys.CURRENT_VERSION` 1 to 2;
  phase 3's `asSaved` then takes it to 3);
- recents (`FileRecentStationsStore`), starred places (`FileStarredPlacesStore`) and the active
  trip (`FileActiveTripStore`), which have no version today: each gains one, and a file without
  it reads as version 1. Each file is a JSON object today, so the field is additive and a build
  without it still reads the file.

Each store reads every version up to its own, not only its own (Codex on #890). Today
`PersistedFavoriteJourneys.toDomain`, and its readers of alerts and pending ends, accept only
`CURRENT_VERSION`, so a bare bump to 2 would hide every ride already saved, each written with an
explicit `version: 1`. So the bump changes each check from "not my version" to "newer than mine":
a version-1 file reads as today's shape, its pending ends with no level and so read by the pair
rule (`Hubs.picked`), and is rewritten as version 2 on the next write; phase 3 reads 1 and 2 the
same way, each ride's `asSaved` absent until the first load with the hub rule marks it. Only a
newer version than the store's own is kept unread.

The rule protects a downgrade only to a build that has it. A build from before phase 2 reads past
the version too, so installing one over a phase-2 build can still drop a level from those three
stores. What that costs is one scope: a junction picked whole comes back as the stop it was picked
through, while every other pick reads back as saved: a station's or a stop's id names it on its
own, and an interchange's id names its hub. Only a developer meets it: Play never serves a lower
version, Android installs one over a higher only for a debuggable app or device, and an uninstall
clears the files. So no second store guards it (Codex on #890 proposed a sidecar the older build
can't see, but it would need reconciling against every add, removal and reorder the older build
made, all to spare a debug build re-picking a junction once). A later field any of these stores
gains that an older build would drop takes the same bump.

**A picked place is never a bare id** (Codex on #890: the fourth and fifth holders found keeping
only an id, so the fix moved from listing holders to the type). The pick becomes one value, `Pick`:
id, name, level, and an optional `extraStopId: String? = null` (a TfL pole id, omitted when null by
the `Pick` codec and ignored when matching two picks for `PendingEnd` or recent replacement, set
only when `Hubs.picked` restores an ambiguous pre-phase-2 `id`+`name` station-record pick that also
needs its same-name `cluster`-linked bus stop; *A TfL stop area's id resolves too*). Phase 2
replaces the `String` id with a `Pick` everywhere a picked stop, station or hub is held, so a
holder that drops the level fails to compile instead of waiting for a review to find it, and one
codec saves and restores it (including `extraStopId` when present) for all of them. The search row
itself, `StationMatch`, carries the same `Pick` from phase 2, its level a required parameter
(Codex on #890: the place editor's `placeToAdd` and `pendingStop`, the sixth and seventh holders,
keep a picked match as its id and name). So a holder that keeps a match whole
(`StationMatchSaver`) keeps the level with no change of its own, and one that saves a match's
parts and rebuilds it fails to compile until it restores the level too. A `StationMatch` also
stands in today for a place that is no stop at all, a searched place or a favorite place as a
trip's From or a new favorite (`SearchEntry.asPlacePick`, `onStartFromPlace`, with a synthetic
`PlaceStart.id`), so the level has a fourth value, **place**, beside station,
stop and hub. A place pick carries its position and is never resolved through `Hubs`: it is
matched by position, as today, and planned to as a place. So the type still forces every
holder to say what was picked, and a place can't be read as a stop by accident. The holders, from
an audit of `main`'s `Persisted*` stores and its `rememberSaveable` and `SavedStateHandle` state:
- the stores above, and starred places (`FileStarredPlacesStore`), keyed today by cluster or stop
  and starred from a page that holds a `Pick`;
- an active trip on the way (`PersistedActiveTrip.destinationStopId`, with its
  `destinationName`), which `ToChoice.of` turns back into the To of a replan from partway along.
  Its arrival set (`destinations`, `destinationIds`) is derived from the pick when the trip
  starts, not picked, and a replan derives it afresh at the pick's level;
- the in-flight state a recreated process restores: the To choice (`SavedTrip.toChoiceFields`,
  and the near-me trip's `hereToId`), the Via choice (`ViaChoiceSaver`'s `TripVia`), a changed
  From (`OriginChange.Station`), and a favorite ride being added in Settings, whose From is kept
  (`journeyFromId`, `journeyFromName`, with `journeyFromPlace` for a favorite place) until its To
  is picked and rebuilt as a `PendingEnd`;
- the pages a rider has open, which `rememberSaveable` keeps today as a bare id: the open station
  or stop (`openStationId`), its departures (`departuresStationId`) and on the way's From
  (`onTheWayFrom`).

A place the app was handed rather than one the rider picked keeps a bare TfL id and is read as
what that id names on its own, so a junction's anchor stop is the stop, never the junction: a stop
on a route's or a line's map (`RouteStopOpen`, `LineStopRef`, and the Lines… flow's
`stationFromLines` taken from one), a Direct row's ends, a trip's legs, watched stops, starred
rows, the nearby set, alerts behind, and a list's platform and station views (`platformStopIds`,
`parentStationIds`, display keys). A saved ride open in its alerts sheet (`AlertsJourneySaver`)
stands in for the store's copy only until that is read. A station added as a favorite place
is saved by its position, not its id; until that position's lookup finishes, the place editor
holds the picked match (`placeToAdd`, `pendingStop`), which keeps its level as above, so a lookup
resumed after process death runs at the level the rider picked.

A junction picked whole keeps the TfL id of a pole of the stop it was picked through, so an
anchor that moves at a refresh still resolves to the same junction. Only an entry written before
phase 2, which has no level, is read by the pair rule above.

**A stop is picked by a pole** (Codex on #890: TfL areas split across several stops, 110 of
today's into stops that all carry one name, so an area id and a name can't say which stop was
meant). From phase 2 a stop-level pick stores the TfL id of one of the picked stop's poles, never
a stop area's: the pole the picked row was built from, else the stop's anchor pole, and a missed
bus stop's avoid (`RouteDisruption.missed`) stores the leg's pole (`toId`, falling back to
`toArea` only when `toId` is blank) the same way, with `TflJourneyPointDto.stopId` returning
`individualStopId` when it is a bundled `B` pole (or, when `Unbundled` or `Unavailable`, a NaPTAN
bus pole matching `^[0-8]\d{2}` with index 3 != `'G'`, covering both `490…` and regional `037…`
poles), whatever parent `naptanId` (`490G…`, `037G…`, or a colliding station record) the Planner
pairs it with, and otherwise retaining a non-blank `naptanId` (such as a `4900…` pole `naptanId`
paired with a non-pole `9000…` `individualStopId` in
`journey_results_euston_to_st_pauls_via.json`) before falling back to `stationOf(individualStopId)`. A row is a
model stop by then (`BusStopIndex` and `YourStops.stopPlace` move to `Hubs.stopOf` in phase 2), so
which stop was picked is known when it is picked, and a ride's end (`YourStops.journeyEnds`, built
from `JourneyEnd.stopId`) and a starred place at the same stop still share one stop key and open
all of the stop's poles. So a refresh that splits an area never makes a pick or avoid made from
phase 2 on ambiguous; only one saved before it can be (*A TfL stop area's id resolves too*).

## The model

Three levels, in TfL's own terms where TfL has them.

- **Stand**: where a rider waits for a vehicle. It is a rail platform (`9400ZZLUKSX1`) or a bus or
  tram pole (`490…`). A vehicle calls at a stand.
- **Station**: where a train calls, under every TfL record of it. A station is the records that
  share **TfL's ICS code and a mode** (maintainer, 2026-10-16; *Why ICS and a mode* below). The
  mode is a record's family, which its NaPTAN type gives, so the grouping is one partition (Codex on
  #890):
  - rail (`910G…`): National Rail, Overground and the Elizabeth line;
  - the DLR (`940GZZDL…`);
  - tram (`940GZZCR…`);
  - cable car (`940GZZAL…`: Greenwich Peninsula and Royal Docks);
  - the Underground (`940GZZLU…`, plus the Northern line extension's Battersea Power Station
    `940GZZBPSUST` and Nine Elms `940GZZNEUGST`);
  - pier (`930G…`).

  `build_station_index.py` checks each `station_index.json` record's family against the modes TfL
  lists for it and refuses to write on an unrecognized prefix or a mode mismatch, so a new prefix
  TfL brings in is classified by hand before it ships rather than falling into the wrong family.
  Only station records are checked: the `HUB…` rows `build_station_index.py` writes into the same
  `stations` array (96 today, each an interchange's name and modes, often from several families) are
  interchange metadata, the hub level's TfL id, never a station. The builder skips them and the
  model reads them as hubs.

  Pairs of mode lists would let a record listing two modes join two records that share none.

  **A record with no ICS code is a station of its own**. A blank code never
  groups, so two records TfL left without one can't merge across hubs. The bundle builder counts
  them on every weekly build, so a refresh that drops codes shows in its log rather than in the
  app's grouping.
  - St Pancras International is one station of three records, and King's Cross another.
  - A station holds its records, the platforms under each, its modes and lines, and each record's
    position. Its distance from anything is the nearest of its records', so a stop
    beside St Pancras's low level is as near St Pancras as one beside its main record.
  - For buses, the same level is the **stop**: both directions of the road (maintainer,
    2026-10-16). TfL sometimes names the pole across the road, so a stop takes in every pole of its
    pair: a TfL stop area's poles (`490G…`) where they pass the stop test themselves, plus any
    pole or area of **the same name** within 250 m of every member it already has, whatever area
    TfL files it in, or none (*One grouping rule*, below).
  - **A junction groups stops into a hub, never poles into a stop**. The same
    name means the same road, both directions. "Church Street / High Road" names the cross street's
    stop at that junction, so it's a different stop, sharing a hub with "High Road / Church Street"
    (*Junctions*). Avoiding a stop, a trip's end and a terminus all act on the stop, and so does
    finding which poles a closure check asks, though a closure itself stays its pole's (*Matching
    is by stop, boarding by pole*); the hub is for where a rider goes.
  - Like a station, a stop keeps each pole's position, and its distance from anything is its
    nearest pole's. Every 250 m and own-stop reach is measured that way, so a spread-out stop has
    one answer whichever pole is asked about. The one exception is a group's own 250 m bound,
    which every pole of the group meets against every other (*One grouping rule*), since it
    bounds how far a group spans rather than how near it is.
  - The maintainer named three uses for it (2026-10-16):
    - a position;
    - its own platforms and departures, listed nearest the rider first;
    - routes that start and end at the same stations, treated as one route.
- **Hub**: where a rider goes.
  - A TfL interchange is a hub: King's Cross & St Pancras holds the Underground station, King's
    Cross and St Pancras International.
  - A station with no TfL interchange is a hub of its own.
  - **A station whose records name different interchanges** joins the one most of them name, ties
    going to the lower interchange id; a record naming none doesn't count. So a
    station is in one hub even where TfL files its records apart, and the builder counts such
    stations on every weekly build.
  - Either way, each station's own bus stops belong to its hub (*Owning a bus stop* below). A
    station with no interchange (a placeholder "Hill Station") takes in its "Hill Station", "Hill
    Station / High Road" and "Vale Road / Hill Station" poles.
  - A junction is in one hub, whatever owns its stops: the stops named for it within 250 m
    (*Junctions*) are owned whole, by one station or by none (*Owning a bus stop*).
    A junction no station owns is a hub of its own. The 250 m holds between every two of its
    stops (*One grouping rule*), so a long road never chains into one hub.

**Junctions** (maintainer, 2026-10-16). A junction is a hub of bus stops: it joins stops, each
keeping its own poles. Two bus stops are at one junction when they're within 250 m and their names
say the same place:
- the same parts in either order: "High Road / Church Street" and "Church Street / High Road";
- or one name alone that is a part of the other: "Hill Station" and "Hill Station / High Road".

Stops that share only one part, the road they're both on ("High Road / Church Street" and "High
Road / Mill Lane"), are different junctions and stay apart. Today's rule compares only the part
before the slash, so it joins those two and keeps the first pair apart.

**A one-part name joins one junction, never two**. A stop named "High Road" could
match both "High Road / Church Street" and "High Road / Mill Lane", and through it the two would
join. So junctions are built in two steps:
1. Stops whose names have two or more parts group first, by the same parts in either order.
2. Each stop with a one-part name then joins the nearest of those groups whose parts include its
   name, within 250 m. With none in reach, it starts a group of its own, which other one-part stops
   of its name within 250 m of it join.

**One grouping rule, for every grouping**. Poles into a stop, and stops into a
junction in each of its two steps, are grouped the same way, so no member ever qualifies for two
groups without a winner:
- **The members.** Grouping poles into stops runs over the bundle's bus poles (`type == "B"` in
  `stops.tsv`; non-`B` rows are station records or parent areas whose `B` poles are already
  present, so none is retyped to `B`). A pole's parent `cluster` (`stationNaptan`) is a TfL stop
  area when it is not a station record (in `station_index.json` or an `M`, `R` or `F` row in
  `stops.tsv` carrying a `station` column, not one of the 4 all-bus parent `M`, `R` and `F` rows;
  18 station records in today's bundle, such as Liverpool Street, appear as the `cluster` of
  forecourt bus poles because TfL also nests those poles under the station's tree; those poles are
  individual members here, as a pole TfL files in no area is).
  A member is a whole TfL stop area, all its poles together, when the area passes the stop test on
  its own: its poles share one name, compared as the test compares names, and each lies within
  250 m of every other. Otherwise its poles are members one by one, as a pole TfL files in no area
  is. TfL's areas aren't clean enough to be trusted whole (Codex on #890): today's bundle has 164
  bus areas whose poles carry more than one name, 133 of them still after case and punctuation are
  ignored, and 117 whose poles span more than 250 m, one of them about 15 km. So an area is kept
  whole only where our own tests would have put its poles together anyway, which keeps today's
  area-as-a-place for the rest, and two areas of one name merge by one joining the other's group.
  Grouping stops into a junction, a member is a stop. A member's names are all its poles' names.
- Members are taken in order of their lowest-id pole.
- A member qualifies for a group when any of its names passes that grouping's test (the same name
  for a stop; the junction tests above for a junction) and every one of its poles is within
  250 m of every pole the group already has. So no two poles of a group are ever more than
  250 m apart, whichever member sorts first (Codex on #890: an anchor-only test let a middle
  member that sorted first join two outer ones 400 m apart; then a test between members'
  nearest poles let two clean areas of 240 m that meet end to end make one stop 480 m long). A
  stop whose poles carry several names uses them all (tested directly on synthetic multi-name
  `Stop` inputs, since stop grouping splits multi-name areas first).
- It joins the nearest group it qualifies for, measured to the group's anchor, ties going to the
  group with the lower anchor id. With none in reach, it starts a group of its own.
- A group's **anchor** is one pole: the lowest-id pole of its first member. It picks the nearest
  group and names it (*Each level's id*); the 250 m bound is the pairwise test above.

So three namesakes in a row, the outer two over 250 m apart, always give two groups, whichever of
the three sorts first, the same two on every build, whether they're poles or stops, and a stop
named for two junctions joins one.

**Matching is by stop, boarding by pole.** A ride's far end, a terminus and the planner's named
pole all match at the stop, so TfL naming the pole across the road still lands on the right stop.
Boarding stays per pole, as SPEC *Departures* has it: a line's buses across the road are its way
back. So do closures: the stop says which poles a check asks, every one of them,
but a notice is judged only at the pole it was filed against, so the pole across the road from a
closed one, and a trip boarding there, stay open (*Kept per pole* above).

**Every stand is in exactly one station or stop, and every station or stop is in exactly one
hub.** Membership is a lookup, never a comparison of two stops. That makes the questions consistent
by construction: two features can't group St Pancras's records differently.

**Each level's id**, worked out by the weekly build and written into the bundle,
never into saved data:
- a stand: its TfL id;
- a station: its ICS code and mode family (`1001276:rail`), or with no ICS code its one record's id;
- a stop: its TfL stop area where its poles are exactly one whole area (not a station record in
  `station_index.json` or an `M`, `R` or `F` row in `stops.tsv` carrying a `station` column),
  otherwise its anchor pole's id (*One grouping rule*), so no two stops share an id when an area is
  split and no bus stop collides with a station record;
- a hub: its TfL interchange id, otherwise its one station's id, or for a junction its anchor
  stop's id.

**Each level's place** (Codex on #890: a junction hub, with no station, had no place for Direct
or the toward-stop search to aim at). One position per level, from `Hubs.placeOf(level)`, so no
caller works one out for itself:
- a stand: its own position;
- a station: the middle of its records, which for a station of one record is that record's own
  point, as today;
- a stop: its TfL stop area's point where it is exactly one whole area, as today, otherwise the
  middle of its poles;
- a hub: the middle of every record of its stations, each record counting once, as
  `StationIndex.positionOf` places an interchange today (Codex on #890: a middle of station
  middles would count St Pancras's three records once and move King's Cross & St Pancras's
  place), or for a hub with no station, a junction of bus stops, the middle of every pole of its
  stops, counted the same way.

A place is where a pick is aimed from, never how near it is: a level's distance from anything is
still its nearest record's or pole's.

Every "lower id" tie-break compares those ids. TfL's own codes hold across weekly refreshes; an anchor
can move when TfL adds a lower id. So nothing is kept keyed by a model id alone:
- Rides, dismissals and starred places store TfL's ids and are matched through the model on read,
  so a ride's identity is worked out afresh after each refresh.
- A ride's derived cache, its last confirmed lines (each whole: id, name and mode), where each
  boarded and got off each way round, and its candidate cursor (*Deriving every line between two hubs*: a fallback, never the answer), is
  stored with the saved ride itself, beside its TfL ids, so it is keyed by no model id. It
  records the bundle's content revision it was last placed against. The
  bundle's own versions (`station_index.json`'s, the `v2` in `stops.tsv`'s header, bumped from `v1`
  when the four membership columns are added) name its format, not its content, so the weekly build
  writes a revision too: a hash over the lines and membership columns, the same in both files. A
  refresh that changes it keeps the ride's lines,
  TfL's line ids, which mean the same whatever the grouping, as candidates and unchecked
  fallbacks until a route re-confirms them (Codex on #890: a change anywhere in the bundle used
  to drop every ride's fallback, so a refresh during a TfL outage lost lines the ride still
  needed). It keeps each line's placement the same way (Codex on #890: dropping the poles on any
  revision change left a bus ride with an unrelated weekly change and a failing TfL no board to
  ask): the ids are TfL's own, as real after a regrouping as before.
  **One rule checks every placement, whatever the mode** (Codex on #890: the third finding on
  what a refresh keeps, after a bus line's boarding pole, its alighting stop and a rail line's
  stations, so the rule stopped being per kind of end). Each cached line keeps, each way round,
  where it boarded and where it got off: a station's record for a train, a pole and a stop for a
  bus. A refresh of another revision checks both ends of every direction against the new
  bundle: each must still lie in the ride's end, in its hub or for a ride kept as saved at its
  saved stop. A direction whose two ends still do stays an unchecked fallback, shown, watched and
  its board asked, until a route re-places it. A direction with an end the refresh moved out is
  no longer placed: nothing says that line still joins the ride's ends that way, so it is neither
  shown nor watched that way round, nor its board asked. A line with no placed direction leaves
  the fallback but stays a candidate, so a route that confirms it brings it back. This is
  narrower than pausing on a failed check, which the maintainer ruled out (*keep watching*): a
  line keeps being watched through a TfL failure, and stops only once the bundle itself says its
  ends are elsewhere. The
  cursor, a place in a candidate order the refresh can change, starts again, which costs a
  candidate an early turn at most.

So a moved id costs at most the cached directions it moved, until a route re-places them, never a
ride or a dismissal. The weekly
refresh's pull request lists every id whose station, stop, owning station or hub changed, so a
move is seen before it ships.

**A platform TfL lists under several records**. Today's bundle builder drops it from
all of them, since it can't say which one a train leaves.
- Listed under records of one station, it now belongs to that station: the builder keeps it there
  (phase 1), under one record picked the same way every week: the record its id names by
  `stationOf`'s prefix step, where that's one of them, else the lowest record id. So
  `stationOf` still returns a concrete record, which a planned leg's end needs: the Planner can name
  a train's end by its platform alone, and the leg asks that record's board. Matching reads the
  record's station, so the record picked never changes what a leg matches.
- Listed under records of two stations, it stays in neither, as today. `stationOf` then returns a
  typed `Unresolved` rather than null, so each caller handles it explicitly, as it handles an
  unknown platform today. The builder counts such platforms on every weekly build.

**So membership is transitive**. Two of an interchange's bus poles named apart are
one hub through its station. Today `StopPlace` judges pairs and keeps two such poles apart. A
favorite ride is between two hubs, so a ride between two poles of one interchange has the same hub
at both ends and can't be saved; today it can. The route map still shows such poles apart.

### Owning a bus stop

A bus stop near two stations still has one owner. `StopPlace.ownStop` is a pairwise
test, so it can say yes for two stations at once. In the bundle, 52 bus stops sit within 150 m of
stations in two hubs. A typical one is named for the station about 70 m away and stands about 125 m
from another. The first rule that applies decides the owner:

1. The interchange TfL files the stop under, if any (including the interchange of any station
   record in `cluster`), narrows the choice to that interchange's stations, and rules 2 to 5 pick
   among them. Within an interchange, rule 4's reach doesn't apply: the stop is the interchange's
   whichever station is nearest. So each of King's Cross & St Pancras's bus stops belongs to one of
   its stations, never just to the interchange.
2. The station whose record the stop is filed under (`cluster` is one of the station's records), or
   if none, the station whose ICS code the stop carries. TfL nests forecourt bus poles directly
   under a station record and gives some poles named for a station that station's own code. Where
   several stations qualify, the nearest.
3. The station it's named for, within 250 m, on **either side** of a "/" (maintainer, 2026-10-16).
   "Hill Station / High Road" and "Vale Road / Hill Station" both count. Today's rule reads only the
   part before the slash, so it misses a stop named cross street first, which TfL gives its own ICS
   code and can file 150–200 m out. Where several stations qualify, the nearest. This is a rule
   between a bus stop and a station only; two bus stops follow *Junctions*.
4. The nearest station within the own-stop reach, 200 m (maintainer, 2026-10-16).
5. On a tie, the lower station id.

**A stop reads all of its poles**. Its poles can come from several stop areas and
disagree on interchange, ICS code or name. Each rule then reads every pole, blanks ignored:
- rule 1 narrows to the stations of every interchange any pole is filed under (including the
  interchange of any station record in `cluster`);
- rule 2 takes every station whose record (`cluster`) any pole is filed under, or if none, whose
  code any pole carries;
- rule 3 takes every station any pole is named for.

Within each, the nearest station wins and a tie goes to the lower id, as above. So a stop has one
owner whatever its poles disagree on.

**A junction is owned whole**. Junctions are grouped over every bus stop first,
owned or not (*Junctions*), and the rules above then run once per junction, reading every pole of
every stop in it, as a stop reads all its poles. Rule 4's reach is measured from the junction's
nearest pole. So all of a junction's stops share one owner, or none, and one hub: a junction with
one side 150 m from a station and the other side 230 m away is the station's whole, never split
between the station's hub and a hub of its own. A stop at no junction is a junction of one.

Every rule is deterministic, so the owner is the same on every build of the same bundle. Fixtures of these shapes get the
tests, never one of those 52 real stops (AGENTS.md *Privacy*).

### Matching

**Records stay visible.** A station page, a board and a near-me header still show TfL's records
and platforms as they do now. The model says what they belong to; it doesn't merge what's drawn.

**The rider picks a level, and a vehicle is matched at that level.**

- A ride, or a trip picked to a hub (an interchange's search row, a hub's page), ends at any of its
  stations or bus stops.
- A trip picked to a station (a station's own row or page) ends at any of that station's records or
  the bus stops it owns, never at another station of its hub: King's Cross is not St Pancras.
- One picked to a bus stop ends at its stop, and only its stop. Its namesakes within
  reach are already in it (*The model*), and one outside it is another stop, so a stop between two
  groups is never accepted by both.
- The rule already settled for St Pancras still holds: **the named record first**. A train whose
  way calls at that record gets off there, before any other record of its station, so
  Liverpool Street's terminus and the Elizabeth line beneath it never stand in for each other
  while the train's own record says where it goes.

### Which level each feature asks

| Feature | Asks | Changes from today |
|---|---|---|
| A station's board, details and search row | station, its platforms nearest the rider first | St Pancras's low level joins its board and search row (today only twins with no interchange fold) |
| Getting off at a station the rider picked, a trip's far end at a station | station (named record first) | one lookup in place of the code and name rules |
| A favorite ride: its ends, its card and its alerts | hub (maintainer, 2026-10-16) | every line between the two hubs, from the rider's nearest station first; a train reaching St Pancras counts for a ride to King's Cross & St Pancras |
| Telling trip options and Direct rows apart | station at each end | routes that start and end at the same stations are one route |
| Off-plan branch detection, passing a stop on the way | station | by membership, not by name with no interchange check |
| Trip planner ends | the level picked: a hub's every station, or one station, each record of it, plus one bus stop it owns | one plan per record, as today (St Pancras's three); one bus stop as well, the first by id of those the picked station owns (preferring one filed under a record of the station in `cluster`), or of the hub's own when a hub is picked, so a hub never gains bus targets; a hub with no station, a junction of bus stops, plans to the stop picked, or its first stop by id when picked as a whole; From and To on a station's page plan to that station, not its interchange |
| Direct section ends | the stops within the rider's walk of the place picked, as today (SPEC *Direct to a place*); membership only says which of them are one stop | none in reach: a stop in walking reach outside the hub still counts and a hub member out of reach doesn't. A station's records, and a bus stop's poles, are one off stop by membership rather than by name (`PlaceDirect.oneStation`, phase 2), and the place picked is the model's for its level (*Each level's place*): a hub at the middle of its stations' records, as an interchange is today, and a junction, which has none, at the middle of its stops' poles |
| Adding a favorite ride (the same hub at both ends) | hub | two poles of one hub are refused |
| Avoiding a stop | station (a bus stop: its stop) | every record of the station is avoided |
| A bus's far end or terminus | stop: both directions of the road | the pole across the road matches, whatever area TfL files it in |
| A bus stop's closure | asked at the stop, judged at the pole | every pole of the stop is asked, whatever area TfL files it in; a notice closes only the pole it was filed against |
| Alert fold and dismissal key | hub | a station with no interchange and its bus stops fold as one hub. A new dismissal stores a TfL id, never the hub's model id (Codex on #890: a station hub's `1001276:rail` would stop resolving when a refresh regrouped its records): the interchange's `HUB…` id where the hub is one, else the TfL id of the record or pole the notice was filed against. Matching and pruning resolve it to its current hub, as they do an older key, so a refresh that regroups the station moves the dismissal with it |
| Farther cards, near-me rail cap | hub | none in effect |
| Near-me, watched and widget headers | record and stand | none |

### Where it's built

**TfL's facts and our rules, both in the weekly build** (maintainer, 2026-10-16: "let's move it to
the batch").

- **The bundle builders** (`scripts/build_station_index.py`, `scripts/build_stops.py`) add each
  station record's and bus stop's ICS code alongside the interchange and platforms they already
  write. The TfL StopPoint lists they read are expected to carry `icsCode`; phase 1 confirms that
  against a recorded response. Today the code arrives only with a route sequence, which needs a
  request per line.
- **They also work out membership**, by every rule above, written as columns beside the rest:
  - each station record's station;
  - each pole's stop;
  - each bus stop's owning station, blank where no station owns it (*Owning a bus stop*), so a
    station picked on its own finds its own bus stops, not another station's in the same hub;
  - each station's and stop's hub;

  and a content revision over the lines and membership columns (*Each level's id*).
  In `station_index.json` a record's fields are `icsCode`, `station` and `modelHub`: its `hub`
  already holds TfL's interchange, which stays as it is for today's readers. The revision is the
  index's `revision` and the `revision=` field of `stops.tsv`'s header. Phase 1 measured the
  columns written in full, no blanks: `stops.tsv` grows from 2.2 MB to 2.9 MB raw, from 630 KB to
  760 KB compressed, so the blank-saving encoding wasn't worth a reader rule. The rules live in
  `scripts/stop_membership.py` (tests in `stop_membership_test.py`), and
  `scripts/membership_changes.py` writes the refresh's list of regrouped ids. On the device,
  `Hubs` (`:domain`) is built from both files by `HubsStore` at process start: about 25–55 ms on a
  desktop JVM once the files are read, on a worker. The APK grows by about 130 KB compressed.
  `build_stops.py` takes `--stations` (`build/stations/station_index.json` in `station-index.yml`,
  `"$INDEX"` in `station-index-refresh.yml`), writes the four columns on `B` rows in `stops.tsv`
  and `icsCode`, `station` and the model's `hub` on the `M`, `R` and `F` rows in `stops.tsv` that
  `station_index.json` omits and that are not all-bus stop-area parents of `B` poles (19 `910G…`
  nested rail sub-station records, 26 `930G…` piers, and 3 `940GZZRL…` unsupported
  miniature-railway rows, omitted from `station_index.json` because 47 carry no lines and `930GGVS`
  lists only rail lines on a ferry record: `build_stops.py` classifies `910G…` as `rail` and
  `930G…` as `pier` by prefix to group with `station_index.json`'s stations by `(icsCode, family)`
  without running `build_station_index.py`'s `modes` check, excludes the 3 unsupported `940GZZRL…`
  rows from `tube` grouping so each stays a single-record station of its own, refuses any other
  unrecognized `M`, `R` or `F` prefix, leaves `stops.tsv`'s existing interchange column intact, and
  leaves the 857 `M`, `R` and `F` rows already in `station_index.json` and the 4 all-bus parent
  areas blank so none has two sources), stamps the shared content revision into both
  `station_index.json` and `stops.tsv`, and bumps `stops.tsv`'s header from `v1` to `v2`
  (`FORMAT_VERSION = 2` and `STOP_FIELDS = 16` in `build_stops.py` and `BundledStops`, with
  `build_stops_test.py` and `BundledStopsTest` updated together).

  The weekly refresh (`station-index-refresh.yml`, Sundays) and the PR builder check
  (`station-index.yml`) pass `--stations` to `build_stops.py`.
  - The rules exist only in the Python builders, tested by `build_station_index_test.py` and
    `build_stops_test.py`. There is one copy, and the device never recomputes it.
  - The refresh's pull request lists every id whose station, stop, owning station or hub
    changed (updating an already-open refresh PR's branch and body as well as creating one, and
    capping the listed ids per category with total counts so the body stays within GitHub's
    65,536-character limit), so a regrouping is reviewed before it ships. A hub change alone isn't
    enough (Codex on #890): a pole moving between two stops of one hub, or a bus stop changing
    owner between two stations of one interchange, changes matching, planning and closures without
    moving either hub. The builders refuse to write, as they already do on short data,
    when a membership column is missing or a bus stop or station comes out with no hub.
  - The cost is four columns for about 21,300 bus poles (`B` rows; the 48 unmatched non-bus
    `M`/`R`/`F` rows carry `icsCode`, `station` and `hub`, and the remaining 861 `M`/`R`/`F` rows
    leave them blank) and three fields for about 1,400 station records, estimated from today's
    bundle:
    - each pole's ICS code, about 180 KB;
    - each pole's stop, about 245 KB, or about 120 KB left blank on a stop's anchor pole;
    - each pole's owning station, about 60 KB, since only about 4,000 poles are within 200 m of a
      station and the rest are blank;
    - each pole's hub, about 245 KB, or about 75 KB left blank where the hub is the stop's own;
    - each station record's ICS code, station and hub, about 80 KB in the station index.

    That's about 810 KB raw written in full, or about 500 KB with those blanks, beside today's
    2.2 MB `stops.tsv` (630 KB compressed). Much less compressed in the APK, since ids repeat.
    Phase 1 measures it and picks the encoding. The weekly job takes a few seconds more. £0, and no
    request at runtime.
- **The device only reads.** `Hubs` loads those columns with the station index and stops it
  already loads, into lookups by id, on a worker. There is no spatial pass and no rule on the
  device.
- **A TfL stop area's id resolves too**. Saved rides, avoids, pending ends and
  dismissal keys can hold an area id (`490G…`, `037G…`) rather than a pole's, and a stop joined
  from several areas takes its anchor pole's id, not theirs. So `Hubs` also keys each area id
  in the bundle's existing area column (excluding ids that are station records in
  `station_index.json` or one of the 48 non-bus `M`, `R` and `F` rows carrying a `station` column
  in `stops.tsv`, which are stations for `stationOf`, `hubOf` and any id handed to the app, with
  `TflJourneyPointDto.stopId` returning `individualStopId` when it is a bundled `B` pole or, when
  `Unbundled` or `Unavailable`, a NaPTAN bus pole matching `^[0-8]\d{2}` with index 3 != `'G'`, so a
  Planner bus point with a colliding station-record or `037G…` `naptanId` keeps its pole id across
  both `490…` and regional `037…` prefixes while a bus point whose `individualStopId` is a non-pole
  `9000…` code retains its pole `naptanId`;
  the 4 all-bus parent `M`, `R` and `F` rows have blank `station` columns and stay in the area
  lookup) to the bus poles that carry it, and resolves it to their stop and its
  hub. An area kept whole is in one stop (*One grouping rule*), so the lookup has one answer. An
  area split into its poles has no single stop, and what its id then means depends on what holds it:
  - **A pick resolves to one of its stops** (Codex on #890: a `Pick` needs one level and one
    place, and taking all the area's stops together would rebuild the grouping the split undid).
    Only a pick saved before phase 2 can hold an area id (or a station record id that `stopPlace`
    took from a bus pole's `cluster`), since every later stop pick stores a pole
    (*A stop is picked by a pole*). It keeps a name as well as an id (*Saved data*), and in
    starred places (`PersistedStarredPlace.stopId`) and recents (`PersistedRecentStation.modes`)
    the starred pole or bus mode too. For an id that is a station record also carried in `cluster`
    by bus poles, `Hubs` keeps those bus poles for `Hubs.picked`, which resolves a pre-phase-2
    pick from that entry's own saved fields alone: to the bus stop when the entry carries one of
    those bus poles (`stopId`), a bus-only mode (`modes == ["bus"]`), or a cleaned saved name
    (`cleanStopName`) that matches one of those bus poles' cleaned names (such as a cross-street
    pole `"Hill/High Road"`) and not the station record's cleaned name (`"Hill"`), and to the
    station when the entry kept only `id` and `name` (`PendingEnd`, `PersistedActiveTrip`) and the
    cleaned names match, setting `Pick.extraStopId` to the lowest-id pole of that same-name
    `cluster`-linked bus stop (persisted by the `Pick` codec when rewritten so a later bundle
    refresh that changes the stop's owner or first owned bus target still keeps it, and included
    and deduplicated by `PlanTargets` and `Hubs.idsOf(pick)` alongside the station's records and
    owned bus target, while ordinary station picks have `extraStopId = null` and stay at one bus
    target). Within
    the bus poles, it resolves to the stop whose poles carry the saved name.
    Where two stops carry it, as for an area split only by distance, it goes to the one holding the
    lowest-id pole that carries it; where none does, since TfL renamed it, to the stop of the
    area's lowest-id pole (or to the station when the id is a station record and the entry carries
    neither a bus pole nor a bus-only mode). That tie-break is a guess, since nothing saved says
    which stop the rider meant, but it is the same on every build, and a rider who meant the other
    picks it once more, after which the pick stores a pole. It is a lookup over the bundled poles'
    names, no grouping on the device, and that stop's level, place and hub are the pick's. This
    holds for every holder of a pick, a pending end, a recent search, a starred place and the
    active trip included, so a refresh that splits an area never leaves a pick without one stop.
  - **An id the app was handed names the area's poles**, exactly as TfL's id does today: an avoid
    or a closure check acts on them (from phase 2 on, `RouteDisruption.missed` keys a missed bus
    stop's avoid by its pole `toId`, falling back to `toArea` only when `toId` is blank, and a
    pre-phase-2 `stop:<id>=<name>` avoid whose `<id>` is a station record also carried in `cluster`
    by bus poles resolves to those bus poles' stops when `<name>` matches one of their cleaned
    names and not the station record's, and to both the station's records and those bus poles'
    stops when the cleaned names match); a trip leg named by it is placed at one of them (Codex on
    #890: a leg ending at one child stop must not arrive at another), the pole the Planner names
    with it (`individualStopId`, which `TflJourneyPointDto.stopId` already prefers), else the
    area's pole nearest the leg's own point, which the Planner gives with each leg, else none,
    so a leg doesn't arrive, replan or compare for detours on that id alone; and a favorite ride
    with that end is kept as saved (`asSaved`), matched by those poles rather
    than by any one stop's or hub's. A dismissal keyed by it stays keyed by that id and matches
    the alert of every hub holding one of its poles (Codex on #890), by its notice as every
    dismissal does (`DismissedAlert.contentSignature`), so the notice the rider dismissed stays
    dismissed wherever its poles now fold and a different notice at those hubs still shows. It is
    pruned only once every one of its poles has settled its closure check, over as many refreshes
    as that takes, the rule a hub-keyed dismissal follows, with the area's poles as its members,
    so no one hub's check prunes it.

  Only an area id no bundled pole carries is `Unbundled`.
- **A stop the bundle doesn't hold** (beyond London's box, or newer than the bundle) has no station
  or hub but its own until a refresh takes it in (maintainer, 2026-10-16). Its membership is a
  typed `Unbundled`: matched by its exact id, never grouped with anything by name, route `icsId` or
  distance.
  - Each caller handles `Unbundled` as it handles `Unavailable` (*A model that can't be loaded says
    so*, below): a board asks the record it was given, an avoid matches the exact id, a trip plans
    to the ids it holds (an interchange by its children, below), and where the rider would
    otherwise be misled the screen says the grouping wasn't checked.
  - No membership lookup is made for it. So it costs no quota, its membership never changes during a session,
    and nothing moves on screen when a response arrives.
  - A platform the bundle doesn't hold still takes its station through `stationOf`'s prefix step
    (`9100…` to `910G…`, `9400…` to `940G…`) where that station is bundled, as today; otherwise it's
    `Unbundled`.
  - An interchange or stop area the bundle doesn't hold is `Unbundled` like any other id, matched
    by its exact id. Its children aren't joined to it, even where a response that
    lists them is in hand, since nothing saved carries that response across a restart: a saved
    ride, pending end, avoid or dismissal naming it would match differently before and after one.
    A child the bundle holds keeps its own bundled station and hub. A page or board that lists
    the interchange's children for display, as `TflStopPointDto` walks them today, still does;
    that's display, not membership.
  - Planning to one isn't membership either. A trip to a parent the model can't
    expand, an interchange or a stop area that is `Unbundled` here or any while the whole model is
    `Unavailable` (*A model that can't be loaded says so*, below), takes its children from a
    lookup, for that trip alone:
    - an interchange, which the Planner doesn't take, is planned as today: its children looked
      up (`StationStopsViewModel`) and each asked for (`PlanTargets.of`, `PlanTargets.viaStops`);
    - a stop area is asked for as itself, and its poles come from the stop-area lookup
      `RouteStops.loadPoles` already makes and keeps a day (Codex on #890: the Planner can name a
      bus call by its pole or its area, so an arrival map of the area alone missed a leg ending at
      a pole). With no answer it arrives at the area alone, as today.

    Its arrival map is the parent's id and those children's ids, each to its bundled stop or
    station where the bundle holds it, else to the parent. Both are lookups the app already
    makes, so they cost nothing new, and like today's `destinationIds` the map is kept with the
    trip (`PersistedActiveTrip`), so a restored trip arrives where it did, and a replan looks it
    up again. Saved data naming the parent still matches it by its exact id only.
  - The cost: a brand-new stop isn't joined to its neighbors until the rider installs an app
    release carrying a refresh that holds it, and one outside London's box never is (Codex on
    #890). The bundle is packaged in the APK, so a refresh reaches a rider only with an update:
    the weekly job bounds how stale the next release's bundle is, not how stale a rider's is. A
    station outside the box that TfL lists under two records loses
    today's name and `icsId` pairing, so a train to its other record isn't matched at the far end.
- **Saved data keeps TfL's ids** and is resolved through the model on read. No ids of the model's
  own are stored, because a weekly bundle refresh can regroup them. The additions are a pick's
  level (*Saved data*) and a ride's derived cache. A restored snapshot stores nothing new: its
  alert fold resolves each stop through the bundle, as a live list does, so an `Unbundled` stop
  keeps its exact id across a restart. The one field that stops counting is a favorite ride's line
  (phase 3, *Deriving every line between two hubs* below).

**Cost and reliability.** £0. No membership question makes a request: the bundle answers every one
it can, and a stop it doesn't hold is `Unbundled` without asking. No new wakeup or battery cost.
The bundle's growth and its load time are measured in phase 1.

**The planner's targets grow in one place**. Each target is four Planner requests
with buses on (quickest, fewest changes, least walking and buses alone, SPEC *Four requests per
plan*), three with them off:
- the same at an interchange: every record is a target, as today, and a hub keeps one bus target
  for all its stations, as `PlanTargets.of` does today;
- one more at a station with no interchange that owns a bus stop, which today plans to its records
  alone: the gap the audit names, four more requests a trip there with buses on, three with them
  off;
- a target that fails leaves the others' routes shown and the plan unkept, as a failed member does
  today (`TripViewModel`), so one failed bus target costs that target's routes, not the trip.
- the search for bus stops toward the destination (`TowardRequests`, a trip from the rider's
  position, for now or later) aims at the place the rider picked, not at the first target. Today both trip screens hand it `destinations.first()`, which for an
  interchange is already its first expanded member rather than the interchange, and with every
  record a target a station of several records would do the same. So the screens pass the
  pick's own place from the model (*Each level's place*), a junction's included, as
  `StationIndex.placeOf` works out a hub's today.

**Play Data Safety: no change**. Membership sends nothing anywhere. Phase 3's ride
requests (*Deriving every line between two hubs*, below) go to TfL, the app's one recipient, and
carry what today's requests carry:
- a route sequence names a line and a direction, as the route page's does;
- a status request names lines, as alerts' do;
- an arrivals request names one stop of the hub the rider is near, as the near-me list's does.

No request names a ride, a saved pair of hubs or the rider's position. Route sequences are cached
on the device, so which pair a ride joins is never sent. So no new data type leaves the device,
`docs/PRIVACY.md` needs no new line, and no declaration changes.

**Deriving every line between two hubs** (phase 3). A favorite ride's card and
alerts take every line between its two hubs (`TODO.md`, *A favorite ride stores one line; derive
them all*).
- **A ride kept as saved derives its lines between its saved ends, not their hubs** (Codex on
  #890). Below, a ride's ends are its two hubs for a hub ride, and for an `asSaved` ride the stop
  or station each saved id names on its own (*Favorite rides*). So an `asSaved` ride's candidates
  are the bundled lines listed at both saved ends, its own saved stops' boards and stop-area
  lookups, and its cached lines; its routes confirm a line only between those ends; and it asks
  no other member of either hub. A line serving King's Cross but not the ride's saved St Pancras
  end never reaches its card or alerts, matching its stop-to-stop identity.
- **A ride stores no line** (`TODO.md`). Its lines are derived and kept in a cache
  stored with the saved ride (*Each level's id*), so the card, its alerts and Settings' label read
  one list.
  - **Only a route confirms a seed**. A ride starred from a line's route page
    seeds the cache with that line and the page's placement in the direction the page showed,
    which that route has just confirmed. A page showing one direction loads only that sequence
    (`RouteStopsList`'s `repository.load(row.lineId, row.direction)`), so the other direction
    starts unplaced, as a Settings seed does, until its own sequence is fetched. A ride added in Settings has no route behind its line: `addFavoriteJourneyPair`
    takes a line listed at both ends (`StationFinder.stationStops`, `JourneyPair.resolve`) and
    loads no sequence. So its line, like an older ride's stored line, starts as the ride's first
    candidate with no placement, never as a cache entry: shown as not checked, and watched against
    the ride's saved ends as today's saved line is, until a route places and confirms it, or drops
    it when that route doesn't run between the ends. It leads the candidate order, so the ride's
    first read spends its budget there first.
  - The file keeps writing that seed in the line field, but nothing reads it as the ride's own
    line. A downgrade from phase 3 shows no rides, since an older store reads only its own version
    (`PersistedFavoriteJourneys.toDomain`), but keeps the file whole rather than overwriting it, so
    the next upgrade has every ride back (*A saved level comes with a version*; Codex on #890). That is enforced, not hoped for (Codex on
    #890: another surface found reading the saved line): phase 3 renames the field the seed, read
    only by the cache's codec, so every reader of `FavoriteJourney.lineId`, `lineName`, `mode` or
    `bus` fails to compile until it reads the ride's derived lines, less its hidden ones. The
    readers on `main` today are the card (`MainScreen`'s star sequences, segment placement and
    checking states, and `JourneyWork`'s origins and lines), its alerts (`JourneyAlerts`, run by
    `JourneyAlertWorker`), Settings' label (`FavoriteJourneysScreen`), the savers that keep a ride
    over a recreation (`AlertsJourneySaver`, `RouteStopOpenSaver`), the disruptions row
    (`HomeLines.journeyLineIds`, `MainActivity.favoriteLineIds`), `HomeLines`' line refs and the
    rider's lines, and `RideLineHide`.
  - The saved line's other jobs go with it. The way-back pole its route placed is the other pole of
    the ride's stop (*Matching*). Its status check is the derived lines'. Today it can't be hidden;
    with no saved line, the rider can hide any of a ride's lines but the last one shown.
  - **A hidden line leaves the ride everywhere, the seed included**. It's dropped
    before anything else reads the candidates, as `journeyOriginsOf` drops a hidden line
    (`hides`) today: it isn't a candidate, so it spends no sequence request; it isn't among the
    cached lines the card and alerts fall back on when a check fails; and its status isn't
    asked. So hiding the line a ride was starred from takes it off the card and out of its
    alerts until the rider shows it again, however the route checks go. Hiding deletes nothing
    (Codex on #890): a hidden line's pending candidate and cached placement stay stored, dormant,
    so Undo or the ride view's Show brings it back as it was, even when no board names it any
    more and the bundle never listed it, and its next turn checks it like any candidate.
  - **So does the saved mode**. Two hubs can be joined by rail and bus at once,
    so a ride seeded from a bus has no single mode. Each derived line carries its own, from the
    bundle, the board it was seen on or its route sequence, and the cache stores each confirmed
    line whole, as a `LineRef` with its name and mode. So a cached line restored
    with no board or sequence answer still renders and boards by its own mode, including a
    live-only line the bundle doesn't list, at a mixed-mode hub where `LineSequence.stopLines`
    can leave a mode blank. What reads `FavoriteJourney.mode` or `bus` today reads the ride's
    lines instead:
    - `RideLines.boardsTowards`, with no far end known, takes a line of any mode among the ride's
      lines rather than the saved one's; with hubs the far end is nearly always known, so this
      is the `Unbundled` far end's case;
    - the card's copy (`MainScreen`'s checking, none and not-checked states, which say buses or
      trains from `journey.bus`) says buses only when every line the ride shows is a bus, trains
      only when none is, and otherwise a mode-neutral form, in the shortest
      words, as the app's other neutral states already read (`AGENTS.md` *Concise copy*):

      | State | Buses | Trains | Both |
      |---|---|---|---|
      | checking | Checking buses… | Checking trains… | Checking… |
      | none | No buses to %1$s soon | No trains to %1$s soon | Nothing to %1$s soon |
      | not checked | Couldn't check buses | Couldn't check trains | Couldn't check |

      The none-direct state, shown when a ride lists changes and today worded "No direct trains
      to %1$s soon" whatever the mode, becomes "Nothing direct to %1$s soon" for a ride of both
      modes. The four new strings, two of them wording the app already shows elsewhere
      ("Checking…", "Couldn't check"), ship in English only, under `<!-- TODO: translate -->` and
      `tools:ignore="MissingTranslation"`, for the maintainer to confirm on a device before the
      translations pass, as every new string does (`AGENTS.md` *Translations*); a test asserts
      a ride of both modes renders each;
    - which pole the card fetches for a bus line stays per line (`Journeys.siblingPoles`), so
      `MainScreen`'s `takeUnless { journey.bus }` for the fetched stop moves onto the line too.
- Which lines might serve the ride comes from three places, so a line TfL adds or reroutes after the
  bundle was built is still found:
  - the bundle: each station's lines and each bus stop's routes, at no request;
  - the near hub's live data, as `journeyLineIdsOf` gathers it today: every line its members'
    boards show and their stop data lists, already fetched for the card;
  - the lines TfL's stop-area lookup lists at every pole of the ride's own boarding stop, as
    `journeyLineIdsOf`'s sibling lines read them today. That lookup
    (`RouteStops.loadPoles`) answers per area, not per board: one request a TfL stop area a day,
    persisted, which the card already makes today for a bus ride's origin area, to find the lines
    boarding beside it. The bundle names every area of the stop, so none waits on a route. One
    area a ride is asked as today, its first origin's; any other area of the stop is charged to the
    app-wide budget for everything a card adds (*Live times at a hub are bounded*, below), on the ride's turn before its other members, and a cached area costs nothing.
    So a line added at a pole no confirmed line boards from is
    still a candidate without its board being asked, and once its route confirms it, its pole
    joins the origin and is asked. A failed lookup keeps the copy it held, as today. A pole TfL
    files in no area is looked up by its own id through the same `loadPoles`, which answers a
    lone pole as itself with its lines (`TflStopPointDto.leaves`), one request a day and charged
    to the same budget as an area, so a line added there is found too.

  A bundled line is a candidate only when the bundle lists it at both hubs; a live one always is,
  and so is each of the ride's cached lines, hidden ones aside. So a line found
  live once that the bundle doesn't list stays a candidate overnight or through a disruption,
  when no board shows it, rather than leaving the card and its alerts when no service runs.
  Every candidate then goes through the route-sequence check below, within its budget. An end the
  bundle doesn't hold has only its live lines, plus the ride's cached ones, as a failed sequence
  has below.

  Live data reaches only the boards the card fetches (*Live times at a hub are bounded*, below),
  always the ride's own boarding stop at the near hub (its *origin*, below) and every member
  that boards a confirmed line, plus the stop-area lookup's lines at every pole of that stop. So
  a line TfL adds there, at any of its poles, is found as today, where the card boards there
  alone. A
  line added only at another member, one that boards no confirmed line, is found once a release
  carries a weekly refresh that lists it, the freshness membership already has (*Where it's
  built*), rather than through a discovery request for every member on every refresh (Codex on
  #890; maintainer, 2026-10-16: "put it in the batch"). Such a change is rare: a new rail line or
  extension every few years, and a bus route change at any one hub about once or twice a year.
- Confirming that a line calls at both ends uses route sequences through `RouteStops`' existing
  persisted cache: one request per line and direction it doesn't already hold, at most
  `MAX_CONCURRENT_FETCHES` at once, so live times keep their slots. That limits how many run at
  once, not how many are sent, and a busy hub lists many lines: King's Cross & St Pancras's
  bundled rows list 28, two directions each. So the check is bounded twice:
  - **Only lines both ends might share.** A bundled line is checked only when the bundle lists it
    at both hubs, which narrows King's Cross's 28 to the few that also serve the far hub. A live
    line, one a fetched board shows, is always checked, so a line TfL adds or reroutes there
    after the bundle was built is still found.
  - **At most six sequence requests a minute, across the app** (`MAX_DERIVED_SEQUENCE_REQUESTS`), each direction counting as one. The budget is shared, not per ride: every
    ride's card and every alert run draws on the same one, so ten rides with a cold cache in one
    `JourneyAlertWorker` run still send six, not sixty. The count itself is per process and in
    memory, as `SharedTflRateLimiter`'s is (Codex on #890: an alert run that spends six and is
    killed, then a relaunch within the minute, can send six more). That is the posture the
    maintainer chose for request accounting (`TODO.md` *Persist the limiter's accounting across
    restarts*: only if it stays cheap, never a write per request), and its worst case is twelve
    sequence requests in one minute, every one still paced by the shared limiter that keeps the
    app under TfL's budget, beside the arrivals and closures that limiter already serves. If that
    item lands, this count rides the same on-disk accounting; until then a test pins the bound: a
    worker spending six, then a relaunch within the minute, sends at most six more, through the
    shared limiter. Rides take turns, one candidate each in
    their own order, so no ride waits behind another's whole list, and the turns carry over
    between reads: each read starts with the ride just past the last one that
    had a turn, wrapping round, so seven or more rides whose first fetches keep failing can't
    hold the budget on every read while the rest are never checked. That ride cursor is one
    value stored beside the favorite rides, like each ride's own cursor below, so it survives
    process death between alert checks. A ride added since starts in the order where it sorts. A ride's order is its starred
    line first, unless hidden, then the bundled lines listed at both hubs, then the live ones,
    then the cached lines that are neither, each group in line-id order. So a
    cached-only line, one no longer bundled at both hubs nor on a board, still gets its turn, and
    its route either re-confirms it or drops it from the cache. **A candidate leaves only on its
    own turn** (Codex on #890: a live line a board showed once, past the budget, would vanish if
    the next board had no prediction for it). A live line no other source names is stored with
    the ride's cursor as pending, a whole `LineRef`, its id, its name as the board gave it and
    its mode, with no placement (Codex on #890: a pending line restored after the process died,
    on a board that no longer shows it, has no other source for its name), until its turn comes; its
    route then confirms it into the cache or drops it. Hiding it keeps it stored, dormant, until
  the rider shows it again (*A hidden line leaves the ride everywhere*). A sequence `RouteStops` already holds, or knows TfL has no route
    for, costs nothing. A route or trip page the rider opens doesn't draw on the budget: its own
    fetches are asked as today. A candidate past the budget waits for a later read, named as not
    checked meanwhile, with the ride's cached lines standing for it, so a cold cache fills over a
    few minutes rather than in one burst against the rate limiter. Six a minute is an eighth of
    the keyless allowance (about 50), so live times keep the rest.
  - **A failed fetch waits its turn** (Codex on #890: the third finding on retrying, so retries
    stopped running on a clock). `RouteStops` remembers only TfL's answer that a line has no
    such route; any other failure leaves nothing behind, so in a fixed order the same failing
    lines would take the budget on every read and the lines behind them would never be checked.
    A five-minute wait didn't fix that: alerts are checked by `JourneyAlertWorker` every 15
    minutes or later (`JourneyAlerts.CHECK_INTERVAL`), so every check found the wait expired.
    So each ride keeps a **cursor** in its candidate order, stored with its cached lines, so it
    survives process death between alert checks. A read asks the
    candidates still needing a fetch from just past the last one it asked, wrapping round, and
    moves the cursor past each one it asks, whatever the answer. Every candidate therefore gets
    a turn before any comes round again, at any cadence, a card's refresh or an alert check, and
    a failed line is asked again only on its next turn, named as not checked meanwhile. A new
    ride, or one whose cursor a refresh dropped, starts at the front, its starred line first.
    Nothing else reads the cursor, so a trip page still retries a route at once.

  That is a few requests the first time a ride is shown, then about one a line and direction a
  day, as `RouteStops` refetches a sequence older than its `MAX_AGE` (24 hours).
- **Confirmed afresh on every read, never kept in place of the sequences** (Codex on #890: a
  second finding in this cache, so the cache stopped being the answer). Each time the card or an
  alert reads a ride's lines, every candidate is checked again, on a worker, against the
  sequences `RouteStops` holds then. A branch or calling pattern TfL changes therefore reaches the
  ride within the day the old sequence is kept, with nothing to invalidate. The ride's persisted
  cache is only its last confirmed placement: each line and, each way round, where it boarded and
  got off, the pole and the stop for a bus and the station records for a train. It seeds a new ride and answers for a line whose sequence
  can't be had (a failed or rate-limited fetch), which the card names as not checked, as below.
  It never confirms a line on its own.
- Alerts ask about the extra lines through the batched line-status request (`LineStatusBatch`),
  which splits ids into 260-character groups, about 15 lines each. A ride's extra
  lines usually fit the group already being sent; each further group is one more request, sent
  and counted against the rate limiter like any other. After a failed group the rest aren't sent,
  and their lines read as unchecked, which the alert says, as a failed status check does today.
  More lines mean more groups, so a group that keeps failing must not keep the ones after it
  from ever being sent (Codex on #890): each check takes its lines in a stable order of their
  ids, starting at a cursor, wrapping round, and the cursor moves to the first line it left
  unsent. The cursor is one line id stored beside the favorite rides, like the route-sequence
  budget's ride cursor, so it survives the worker's process ending between checks; a cursor
  naming a line no longer asked starts at the next id after it.
- A sequence that fails or is rate-limited leaves that line unconfirmed. The card shows the lines it
  could confirm plus the ride's cached ones, and says the rest weren't checked. Alerts keep
  watching the cached and pinned lines (maintainer, 2026-10-16: "keep watching"),
  so nothing is lost against today, where they watch the saved line unchecked. A failed check is
  far commoner than TfL rerouting a line that day, so pausing would mostly drop real disruption
  alerts. An alert about a line whose route wasn't re-checked says so.
- **An alert is weighed on the stretch the ride rides, for every line it watches** (Codex on
  #890). Today `JourneyAlerts.onRide` places a bus line's alert on the ride by the saved ends and
  any widget pins (`ridesOn`), and counts the whole line as on the ride when none of them is on the
  line's route. A derived line can board at another member of the near hub and get off at another
  member of the far one, so the saved ends alone would miss it, and a background check, which often
  has no pins, would then alert on a diversion anywhere on the line. So each watched bus line
  (only a bus line's alerts are weighed by route, `routesToRead`) also passes `ridesOn` its own
  placement as a ride, per open direction: the pole it boards from and the
  far member it gets off at, from this read's route where it confirmed the line, else from the
  ride's cache, which keeps exactly that placement. The far end is the stop the line got off at
  that way round, as the route placed it, never the far end's boarding pole for the way back: a bus
  that arrives at one stop of the far hub can start back from another, and a leg ending at the
  pole it starts back from is one the outbound route doesn't run.
  The line the ride was starred from keeps its saved ends as well, and so does a line no route has
  placed yet (a ride added in Settings, an older ride's stored line): it is watched against the
  saved ends alone, exactly as today's saved line is (Codex on #890: an unplaced seed watched
  before any route proves it). That is SPEC's own rule for alerts: "Unknown is on: a route not
  read, or a direction none of whose rides it can place, keeps the alert" (maintainer,
  2026-10-15). Holding the alert back until a route answers would trade a whole-line alert for a
  missed one, for a line `JourneyPair.resolve` already found at both ends. So the whole-line
  fallback stays only for what it covers today: no route read, or a direction none of whose rides
  the route runs.
- **Live times at a hub are bounded**. Today a card boards at one stop. A hub
  card boards at the ride's **origin** at the near hub, as today, and at the near hub's other
  members that board a confirmed line. The origin is the stop the ride was saved at, resolved per
  line as `journeyOriginsOf` resolves it today for the one saved line: at a
  station, the station; at a bus stop, the pole each of its confirmed lines boards toward the far
  hub, which shown the other way round is the saved pole's way-back pole across the road, not the
  saved pole. Two lines at one stop can board toward the far hub from opposite poles, as when each
  runs one way round a loop, so a bus origin can be more than one pole.
  **A route that can't place the pole falls back to the last one it did** (Codex on #890: a
  third finding on the bus origin, so the origin stopped depending on this read's routes alone).
  The ride's cache keeps, with each line, the pole it last boarded from at each end, each way
  round, and the stop it got off at, from the placement that confirmed it. While a line's sequence is unfetched, failed or
  waiting for its budget, its cached pole stands in for its origin and is asked, so a live line
  that board shows still becomes a candidate, and the card names that line's route as not
  checked. A ride starred from a route page has that page's placement from the start, in the
  direction the page showed; the other direction waits for its own sequence. Only a line with no
  cached pole, an older ride's or one added in Settings before its first placement, or one whose
  pole a bundle refresh moved out of its end, waits for its board, which costs no request, as
  today, with the card saying that stop wasn't checked. The board is never the saved pole taken
  blindly, which shown the other way round boards away from the far hub. **Discovery never waits
  for a placement**: the stop-area lookup needs no direction, so it runs from the
  ride's saved boarding stop whatever has been placed, the saved pole's area counting as the
  ride's one area, as today, and a stop the bundle doesn't hold looked up by its saved id, which
  `RouteStops.loadPoles` answers for an area or a lone pole alike. So a new or rerouted line
  boarding there still becomes a candidate while the seed's own route fails or no longer runs
  between the ends. What a card asks is
  bounded:
  - each station once with every one of its records, as its own page asks for them today (every
    id or none), but for an origin station the budget below leaves at that one record, and of a
    bus stop only the poles its confirmed lines board toward the far hub
    (`Journeys.siblingPoles`, as today, per line), each pole once however many lines board it;
  - every shown card's first origin, one request as today: at a station the one record
    `journeyOriginsOf` asks today (its route's origin, else the saved `from.stopId`), or at a bus
    stop the pole its first shown line boards from: the starred line's, unless the rider hid it,
    else the first line in its candidate order that is shown and not hidden, confirmed this read
    or shown from the cache at the pole the cache placed it, so a hidden seed never
    spends the one request a card is sure of, and a card whose shown lines are all cached still
    asks one board. That baseline isn't this design's to cap: it is what each shown card asks on `main` today, its first area's lookup included.
    Like every TfL request it waits on the app's one rate limiter (`SharedTflRateLimiter`, one
    token bucket for every client in the process), which paces the whole app toward TfL's
    allowance however many rides are shown, so twenty cold rides fill in over a minute rather
    than bursting. Which card waits behind which is the prioritized request queue's question
    (`TODO.md`). What the model adds is the bounded part below;
  - **everything a card asks beyond today's**, its origin stop's other stop areas and area-less
    poles to look up, its origin
    station's other records, its other origin poles and the near hub's other members, shares
    **one app-wide budget** with every other ride card's (Codex on #890: the third finding on a
    ride's requests, so the bound stopped being per ride, as the route-sequence one did; then a
    ride's several origin poles, its origin station's other records and its stop's other areas,
    so the budget became everything a card adds rather than a list of kinds): at most six
    requests a refresh between all the cards (`MAX_RIDE_EXTRA_REQUESTS`), each area, record or
    pole counting as one and counted after the caches answer. **The requests wait in one queue,
    with one cursor** (Codex on #890: the fourth finding on this budget's fairness, after the turns
    across refreshes, across process death and within one ride, so the rotation stopped being
    nested). The queue lists every shown ride's extra requests, nearest ride first, each ride's in
    its own order: its other areas first, since they say which poles are candidates, then its
    origin station's other records as one unit, then its other origin poles, the starred line's
    before the rest unless hidden, then the near hub's other members nearest the rider first, a
    station as one unit. A refresh starts at the cursor and takes units in order, wrapping round,
    until the next one doesn't fit what is left of the six; the cursor stops at that unit, so the
    next refresh starts with it and the whole budget. So every unit comes round in turn, a ride
    with more than six extras and a station that wouldn't fit after other requests included, and
    none is asked twice before every other has had its turn. No unit is bigger than the whole
    budget: a station of more than six records is split into units of six, in record id order
    (Codex on #890), so a refresh always asks the unit at its cursor and moves on, and such a
    station is asked whole over successive refreshes, its card naming the records not yet asked
    as it names any stop it didn't ask. The cursor names the unit it stopped at and
    is stored beside the favorite rides, as the route-sequence budget's ride cursor is, so a rider
    who opens the app briefly and has it killed between visits still sees it move on; the queue is
    rebuilt each refresh from the rides shown, and a cursor whose unit is gone starts at the next
    unit in order. Between its turns a stop's last answer stands from `ArrivalsCache`, aged by
    its countdowns like any cached board, until it is too old to show (`Staleness.THRESHOLD`, five
    minutes), so a card holds still rather than dropping a stop on the refreshes it sits out; one
    with no answer young enough is named as not checked.
    So nine near rides with cold, distinct hubs cost their nine first origins and
    areas, as today, plus at most six, however many areas, records or poles they board from. One
    ride from King's Cross & St Pancras fits whole: its origin and five more, King's Cross, St
    Pancras's three records and one bus pole. A card never looks complete with a stop missing:
    one whose origin station stopped at its first record, or whose areas, origin poles or members
    went unasked, says which;
  - every member's board goes through `DepartureSource` and `ArrivalsCache` like any board, so one
    the near-me list or another ride already fetched costs nothing. A card shows where the rider
    is near its hub, which is where the near-me list is already fetching those boards.
    `mergeJourneyOrigins` asks a member two rides share once;
  - each request goes through the rate limiter and request pool like any board;
  - a member whose board fails shows as a failed board does today, named as not checked, and the
    rest of the card stands;
  - each member's closure check is counted too. It goes as every board's does
    today: sent once that member's departures have settled, through `StopClosureCache.SHARED`, so
    a member checked within five minutes (`DISRUPTION_REUSE`), or one another screen has a check
    out for, costs nothing; bus poles share one request per 20 (`StopDisruptionBatch`), and each
    rail or Underground record is its own. So a refresh adds at most six requests (arrivals or
    area lookups) and six closure checks to today's, whatever the number of rides, fewer as the
    caches answer, all through the rate limiter;
  - until a member's closure check answers, and after one fails, its departures stand and the card
    names it among the stops not checked for closures, as the near-me list does today, never as
    clear.
- £0 and no new wakeup: the requests ride the refreshes that already show the card.

**Ready before anyone asks, and never two answers**. The model loads on a worker as
the app's process starts, beside the bundled stops it reads, and is published as state that turns
ready once, from not yet loaded to loaded.

- A worker that needs the model waits for it and never falls back to today's rules in the
  meantime. A fallback would let a board, an avoid filter or an alert fold made during the load
  disagree with one made after it.
- **Composition never calls the model.** Not every caller is on a worker today: `stopPlaceKey`
  runs in `MainScreen`'s composition, and `PlanTargets.of` on `MainActivity`'s trip path. Each
  moves into its view model's worker in the phase that points it at the model. The screen reads
  what that worker computed, and is recomputed when the model turns ready. Each moved function gets
  the single-thread test AGENTS.md *Main thread* asks for.
- **A restored snapshot holds still while the model loads** (SPEC principle 4). Its
  stops and departures render at once, as today, since they need no model. What the model groups,
  a ride's card, an alert fold, a hub's header, holds its place as a placeholder until the model is
  ready, as "Checking trips…" does, and then appears once, settled. It never renders today's
  grouping first and regroups under the rider's thumb. The load reads files the app already reads
  at start, so the placeholder is rarely seen. The first frame never waits.

**A model that can't be loaded says so**. The bundle ships inside the app, so this
is corruption, not a network failure. An unreadable station index, or a missing membership column,
makes the model a typed `Unavailable`, never an answer that merely looks complete. Each caller
handles that explicitly, as it handles a failed lookup today:
- A board asks the record it was given.
- An avoid matches the exact id.
- A trip plans to the ids it already holds, but for an interchange, which the Planner doesn't take:
  that is planned through its looked-up children, as an `Unbundled` interchange is (*Where it's
  built*), so a trip to or via a `HUB…` pick still gets routes.

Where the rider would otherwise be misled, the screen says what wasn't checked. The debug log
records the failure every time (principle 2).

**Multi-city later** (`dev-docs/multi-city-gtfs.md`). The levels map onto GTFS: a stand is a stop
(`location_type` 0), a station is a station (`location_type` 1, the stops' `parent_station`). A
hub has no GTFS equivalent, so it would be built from `transfers.txt` and pathways, or by the same
own-bus-stop rule.

## Plan

Each step is its own PR, and the old rules stay until their last caller has moved. Any PR that
changes behavior, architecture or what's persisted updates the SPEC sections it changes in the same
PR (AGENTS.md *Project documentation*), so `SPEC.md` is never behind a merged phase (Codex on
#890).

1. **Bundle the model, unused** (`internal:`). The builders bundle the ICS codes and work out each
   record's station and each station's and stop's hub (*Where it's built*), and
   `station-index.yml` and `station-index-refresh.yml` pass the stations file to
   `build_stops.py`, the refresh workflow updating an already-open refresh pull request with a
   capped diff body rather than exiting early. Add `Hubs` to `:domain`, reading those columns, with
   `stationOf`, `stopOf`, `hubOf`, `sameStation`, `sameStop`, `sameHub` and `placeOf` (*Each
   level's place*).
   Rename the code's existing `hubId` fields to say interchange, so a TfL interchange and a hub
   never share a name. Only the Kotlin name changes: a field written to or read from disk keeps
   its serialized key (`@SerialName("hubId")`), the persisted stores' (`PersistedNearbyStop`,
   `RouteCacheStop`) and the bundled station index's alike, since the stores ignore unknown keys
   and a renamed key would read every cached interchange as blank after an upgrade (Codex on
   #890); a test reads a file written before the rename and finds its interchanges. Kotlin tests
   read the real bundle, at big stations only:
   - King's Cross & St Pancras: St Pancras's three records are one station, and King's Cross and the
     Underground station are separate stations in the same hub.
   - Liverpool Street and Paddington.
   - The two Hammersmiths, which stay apart.
   - Reading and Weybridge's twins.

   The rules' own tests are the builders' Python tests, on fixtures, never real bus stops
   (AGENTS.md *Privacy*):
   - a station of two records at different positions, whose distance is its nearer record's;
   - a platform listed under two records of one station, kept under the record its id names, and
     one whose id names neither kept under the lower record id; one listed under two stations,
     `Unresolved`;
   - "Hill Station" with namesake poles on either side of a slash;
   - two stops at one junction named in either order, two stops in one hub rather than one stop,
     and two that share only their road;
   - a one-part name within reach of two junctions, which joins only the nearer;
   - a stop area whose poles carry two names, and one whose poles span more than 250 m, each
     split into its poles, which group by the name and distance tests into stops named by
     their anchor poles, not the area; a clean area kept whole; a split area's id, handed to
     the app, resolving to its poles, never to one stop; and a pick of a split area resolving to
     the stop that carries its saved name, else the stop of the area's lowest-id pole; a stop
     picked from phase 2 on stored by a pole of it, never its area, and a ride end's match built
     from its pole, so a later split leaves both resolving to the stop picked;
   - a bus pole whose `cluster` is a station record's id, grouped as an individual pole into a
     stop named by its anchor pole rather than the station record's id and owned by that station;
     `M`, `R` and `F` rows in `stops.tsv`, all-bus stop areas mis-typed as `M`, `R` or `F`
     included, left out of pole grouping, with `icsCode`, `station` and `hub` written on the
     non-bus `M`, `R` and `F` rows omitted from `station_index.json` (`940GZZRL…` kept as
     single-record stations of their own rather than grouped as `tube`) and blank on the rows
     already in `station_index.json` or acting as all-bus parent areas;
   - a bus stop filed under an interchange, owned by one of its stations;
   - records of one ICS code in two mode families, which stay two stations;
   - a record of each family, the cable car and the Underground's own prefixes at Battersea
     Power Station and Nine Elms included, and a builder that refuses a `station_index.json` record
     whose prefix is unrecognized or whose prefix's family none of its TfL modes match, or an
     omitted `stops.tsv` station row with an unrecognized prefix;
   - an interchange row whose modes span two families, which builds, and is read as a hub and
     never as a station;
   - two records of one family with no ICS code, which stay two stations;
   - a same-named pair whose poles TfL files in two areas, or in none, as one stop, each area
     joining whole, and an area whose poles are nearer another area's group than their own
     area's still kept together;
   - two clean areas of one name that meet end to end, each under 250 m but together over it,
     which stay two stops;
   - a spread-out stop of several poles, measured from its nearest pole;
   - a stop whose poles disagree on interchange and name, owned once;
   - three namesake stops in a row, the outer two over 250 m apart, grouped the same way every
     build, the first of them spread out over several poles, and the same for three same-named
     poles forming stops;
   - the same three with the middle one sorting first, which still gives two groups, never one
     spanning the outer pair;
   - a stop whose poles carry two junctions' names, in one hub;
   - a station whose records name different interchanges, or only some name one, in one hub;
   - a bus stop within reach of two stations, owned by one;
   - a junction with one side within a station's reach and the other just outside it, owned whole
     by that station and in its one hub;
   - each level's id, the same whatever order the bundle lists its members in;
   - a refresh that moves a pole between two stops of one hub, a bus stop between two owning
     stations of one interchange, and a stop to another hub, each listed in its pull request, and
     a refresh with more changes than the pull request body cap listing the first ids in each
     category with the total count;
   - a builder that refuses to write when a membership column is missing;
   - the content revision, the same in both files, changed by a change to lines or membership
     and by nothing else.

   And on the device, in Kotlin:
   - a bus stop's owning station read from its column, so a station picked on its own plans to
     its own bus stop, not another station's in the same hub;
   - a pre-phase-2 pick whose id is a station record also carried in `cluster` by bus poles (in a
     synthetic fixture), resolved by `Hubs.picked` from that entry's own saved fields alone: to the
     bus stop when the entry carries one of those poles (`stopId`), a bus-only mode
     (`modes == ["bus"]`), or a cleaned bus-pole name (`"Hill/High Road"`) that differs from the
     station record's cleaned name (`"Hill"`), to the station with `Pick.extraStopId` set to that
     same-name `cluster`-linked bus stop's lowest-id pole (persisted across save and restore and
     deduplicated by `PlanTargets` and `Hubs.idsOf(pick)` against the station's single owned bus
     target so a later refresh that changes ownership still keeps that stop alongside the station)
     when the entry kept only `id` and `name` with matching cleaned names (`"Hill"`), even if
     another saved store holds the bus stop, and always resolved as the station record by
     `stationOf`, `stopOf` and `hubOf` (with `TflJourneyPointDto.stopId` returning
     `individualStopId` when it is a bundled `B` pole or, when `Unbundled` or `Unavailable`, a
     NaPTAN bus pole matching `^[0-8]\d{2}` with index 3 != `'G'`, across both `490…` and regional
     `037…` poles, over a colliding station-record or `037G…` parent `naptanId`, while retaining a
     valid pole `naptanId` when `individualStopId` is a non-pole `9000…` code as in
     `journey_results_euston_to_st_pauls_via.json`); and a
     non-bus `M`, `R` or `F` row in `stops.tsv` omitted from `station_index.json` resolved to its
     station and hub from `stops.tsv`;
   - a stop the bundle doesn't hold, `Unbundled`, matched by its exact id and never grouped;
   - a saved area id absorbed into a stop joined from several areas, restored to that stop and
     its hub rather than `Unbundled`;
   - a platform the bundle doesn't hold, at its bundled station through the prefix step;
   - an interchange the bundle doesn't hold, `Unbundled` by its exact id whether or not a response
     listing its children is in hand, so a saved reference to it matches the same before and
     after a restart, in a persisted-state test, and a child of it the bundle holds keeping its
     bundled hub, while a trip to it, or via it, still plans to its looked-up children and
     arrives at them, and so does a trip to or via a bundled interchange with the model
     `Unavailable`.

   Measure the APK's growth and the load time, and load the model at process start. SPEC's
   *Finding stops* gains the model in the same PR: its three levels, the bundle as their only
   source, worked out by the weekly build and loaded once on a worker as state, with the reasons.
2. **Station identity** (`refactor:` where behavior holds). Point these at `Hubs.sameStation`,
   keeping the named-record-first order:
   - stop details' `ownIds`, `linesById` and From and To (`openId`), which resolve to the station
     or stop picked. The planner then plans to that station's records plus one of the bus stops it
     owns, or to a picked stop, so narrowing From and To from the interchange to the station keeps
     their bus journeys (Codex on #890). The trip's arrival map moves with it, in the same phase
     (Codex on #890: a bus target added in phase 2 with the map left for phase 3 would be a route
     the trip couldn't arrive by): `Hubs.idsOf` at the station and stop levels, a station's records
     and platforms and the bus stops it owns with their poles' stop areas, or a stop's poles and
     whole stop areas, each to its station or stop, so a route ending at the new bus target or at
     any pole of a picked stop arrives, replans and compares there. Phase 3 adds the hub level;
   - getting off, far ends and Direct;
   - off-plan detection and passing a stop;
   - the board's ids and search's twin fold;
   - search's member rows, which carry their station's lead record instead of the interchange id,
     except a row folded from two stations' names, which keeps the interchange;
   - search's bus-stop rows (`BusStopIndex`, `YourStops.stopPlace` and `YourStops.journeyEnds`,
     pointed at `Hubs.stopOf` so every bus-stop row is a model stop and picks by a pole in phase
     2), with `StationStopsViewModel`, `addFavoriteJourneyPair` and `FavoritePlacesViewModel.onPick`
     expanding a picked stop through `Hubs.sameStop` (`Hubs.stopOf`) and a picked station through
     `Hubs.sameStation` before calling `StationFinder.stationStops` so opening a stop, adding a
     favorite ride or resolving a positionless pick queries every pole of the model stop or every
     record of the station before phase 3 moves favorite rides to hubs;
   - every store that saved a member row's pick before this phase (pending ends, the From, To and
     Via recents), which reads it through `Hubs.picked` and from now on holds a `Pick`, as every
     other holder of a picked place does: an open trip's To, Via and changed From, and the open
     station page, departures view and on the way's From in their saved state (*Saved data*);
   - the schema version of each file store that saves a pick, bumped, with a file of a newer
     version kept rather than overwritten (*A saved level comes with a version*).

   An id outside the bundle is `Unbundled` (*Where it's built*). Each behavior change
   gets a regression test:
   - avoiding takes in every record of a station, keys a missed bus stop's avoid by its pole
     (`toId`), and resolves a pre-phase-2 `stop:<id>=<name>` avoid whose `<id>` is a station record
     also carried in `cluster` by bus poles to those bus poles' stops when `<name>` matches one of
     their cleaned names and not the station record's, and to both the station's records and those
     bus poles' stops when the cleaned names match;
   - St Pancras's low level joins its board and search row;
   - a favorite ride's end and a starred place at the same bus stop collapse into one search row
     and open every pole of the stop, and `addFavoriteJourneyPair` on a bus stop row (including one
     joined from two stop areas) queries every pole of the model stop via `Hubs.sameStop` so a
     direct line served only from a sibling pole is found rather than saved grayed as unsupported;
   - From and To on St Pancras's page plan to St Pancras and one of its own bus stops, not its
     interchange;
   - a trip's destination ids (`destinationIds`) at the station and stop levels: a trip to St
     Pancras counts as arrived only at St Pancras's records or the bus stops it owns, and replans
     to them, never to King's Cross or its Underground; a route ending at the bus target this phase
     adds arrives; a trip to a bus stop joined from two areas arrives and replans at either pole or
     whole stop area, and drops a route riding on past one pole for one ending at its sibling pole
     no later; a planned leg ending at a platform id alone arrives at the station picked,
     replanning there and counting for detours, a platform the bundle doesn't hold included,
     through the prefix step; and a route riding on past one of a station's records is dropped
     for one ending at another no later;
   - a pending journey saved from St Pancras's member row before this phase, holding the
     interchange's id and St Pancras's name, restored as St Pancras, and replaced, not
     duplicated, when the same pair is picked again, in a persisted-state test;
   - one saved from the King's Cross member row, which folds in the Underground's name, restored
     as the hub with both stations, and matched by a new pick of that row;
   - a From, To and Via recent saved from St Pancras's member row before this phase restored as
     St Pancras, opening and planning to St Pancras rather than the interchange, and replaced,
     not duplicated, when the row is picked again, in a persisted-state test;
   - a pending end, a recent, an open trip's To, Via and changed From, and an open station page,
     departures view and on the way's From, each picked in this phase, save their level and
     restore to it after process death, and each shape an older build saved, with no level,
     restores as before, in persisted-state tests;
   - each versioned store reads its older versions: a favorite journeys file saved today, with
     its explicit `version: 1`, keeps every ride, alert and pending end after this phase's bump,
     and again after phase 3's; a file with no version reads as version 1; and a file of a newer
     version is left untouched, levels included, rather than written over;
   - a trip via St Pancras asks via each of its three records, never one merged request, and a
     route through the record the rider didn't name still counts as passing it.

   SPEC's *Departures*, *Finding stops* and *Trips with a change* say so in the same PR.
3. **Hubs.** Point these at `Hubs.hubOf`:
   - `StopPlace`'s callers;
   - favorite rides: their ends, identity, distance from the rider (`Journeys.oriented`,
     `farMeters`, `nearestFirst`), card and alerts, deriving every line between the two hubs
     (`TODO.md`, *A favorite ride stores one line; derive them all*). Tests:
     - a persisted-state test restores a ride saved between two poles of one interchange, with its
       stored line, and checks it still shows that line between those two stops;
     - a new ride starts with the line it was starred from, and nothing reads the stored field as
       its line;
     - a ride added in Settings starts with its line unplaced, shown as not checked and watched
       against its saved ends, until its first route read places it, and a fixture line listed at
       both ends whose route doesn't run between them is dropped rather than ever shown as
       confirmed;
     - a ride starred from a route page showing one direction is placed that way round only, the
       other placed by its own sequence when fetched;
     - a live line a fixture board showed past the budget is still asked on a later read whose
       board has no prediction for it, then confirmed or dropped by its route;
     - an older ride whose seed's route fails still finds, through its saved stop's stop-area
       lookup, a fixture line boarding there, with no board asked until a route places it;
     - the last line a ride shows can't be hidden;
     - two rides saved before between different members of the same two hubs stay two cards,
       each matched stop to stop as saved, and a third between those hubs is refused, in a
       persisted-state test;
     - removing one of those two leaves the other matched stop to stop by its `asSaved` flag,
       a version 2 file without the flag is marked on load and written back as version 3, and a
       version 2 reader left with a version 3 file keeps it untouched, flag included;
     - a ride kept as saved derives only the lines between its saved ends: a line at King's Cross
       that doesn't serve its saved St Pancras end is never a candidate, on its card or in its
       alerts, and no other member of either hub is asked for it;
     - a refreshed bundle with a new content revision that moves one ride's end into another
       ride's hub marks both, which stay separate cards with their own alerts, and one whose two
       ends it makes one hub is marked too, in a persisted-state test;
     - a ride is oriented, ordered and shown by the nearest member of each end's hub;
     - nine near rides with cold, distinct hubs, some boarding from several poles, some from a
       stop filed in two areas and some at a station of several records, ask their nine first
       origins and areas, as today, and at most six other areas, records, origin poles and
       members between them, nearest ride first in turns, a station of up to six records whole or
       not at all; each card says which stops it didn't ask, and keeps the rest of its card when
       one member's board fails;
     - over successive alert runs, eight rides each with an uncached sequence whose first six
       fetches keep failing all get a turn, the ride cursor surviving process death between
       runs, in a persisted-state test;
     - over successive alert runs, status lines spanning three groups, the first of which always
       fails, all get asked, the status cursor surviving process death between runs, in a
       persisted-state test;
     - over successive refreshes at the screen's one-minute cadence, every extra request gets a
       turn: eight rides that each need some, one ride that needs more than six, a station that
       wouldn't fit after other requests, and a station of more than six records, asked whole
       over successive refreshes with every later unit still reached, the cursor carrying over
       from refresh to refresh and across process death between short visits, in a
       persisted-state test, and a stop
       sitting out a refresh keeps its last answer until it is five minutes old;
     - a ride whose starred line is hidden and whose other shown lines are all cached fallbacks
       still asks one board, at the first such line's cached pole, with the extra budget spent;
     - a ride kept as saved whose two ends now share a hub is oriented, ordered and judged near
       or far from its saved stops, not from any member of the hub, in a persisted-state test;
     - a line found live and cached that the bundle doesn't list stays on the card and in its
       alerts on a refresh where no board shows it, a candidate checked by its route sequence,
       and leaves only when that sequence no longer calls at the far hub; hidden while still
       pending, it stays stored and dormant, asked nothing and shown nowhere, and Show restores
       it to the card with no board naming it, in a persisted-state test; and a pending line
       restored after process death, its next board empty and its route failing, still shown by
       the name its board gave it;
     - a ride's closure checks are at most one per asked record, bus poles batched, none for a
       member checked within five minutes, and a member whose check fails or hasn't answered is
       named as not checked while its departures stand;
     - an alert set on a ride before the change still fires after it, in a persisted-state test;
     - with no widget pins, as in a background check, a derived bus line boarding at another
       member of the near hub is placed by its own ends: a fixture diversion on its stretch alerts,
       one elsewhere on the line doesn't, whether the line was confirmed this read or comes from
       the cache after a failed sequence fetch, and for a fixture loop that arrives at one stop of
       the far hub and starts back from another, scoped by the stop it got off at;
     - two rides kept as saved, between different stop pairs, keep separate derived-lines caches,
       in a persisted-state test;
     - a fixture ride with an end the bundle doesn't hold matches that end by its exact id and
       shows its cached lines there;
     - a line the origin's fixture board shows live but the bundle doesn't list is checked by
       route sequence and, calling at both hubs, joins the ride's lines, and the origin's board is
       asked even when it boards no line confirmed on that read: a station's, or at a bus stop
       the pole the cache last placed;
     - a line the stop-area lookup lists only at a pole of the ride's boarding stop that no
       confirmed or cached line boards from becomes a candidate without that pole's board being
       asked, also at a pole filed in no area, and once its route confirms it, that pole is
       asked;
     - a bus ride with every sequence fetch failing asks the pole its cache last placed for that
       way round, not the saved pole, takes a live-only line that board shows as a candidate
       named as not checked, and with no cached pole asks nothing and names the stop as not
       checked, in a persisted-state test;
     - a ride from King's Cross & St Pancras with a cold route cache checks only the bundled lines
       listed at both hubs, plus the live ones, sends at most six sequence requests a minute, the
       starred line's first, and names the rest as not checked until a later read fetches them;
     - ten rides with a cold route cache in one alert run send at most six sequence requests a
       minute between them, one candidate per ride in turn, and a route page opened meanwhile
       fetches its own sequence without waiting on that budget;
     - an alert run that spends the six and is killed, then a relaunch within the minute, sends
       at most six more, each through the shared limiter, the per-process bound the budget states;
     - hiding the line a ride was starred from drops it from the card, the alerts, the status
       request and the sequence checks, also when a check fails and the cached lines stand in;
     - with the first three candidates' fetches failing, the next read asks the three behind
       them, also when the ride's cursor is restored from its store between the two reads, as
       after process death, and at the alert worker's 15-minute cadence; the failed three come
       round again only once every candidate behind them has had a turn;
     - a bus ride shown the other way round fetches the way-back pole its route places, not the
       saved pole, and before that placement the one its cache last placed that way round, or with
       none waits without spending a request;
     - a bus ride whose two lines board toward the far hub from opposite poles of its stop asks
       both before any other member, its starred line's pole first and the other from the shared
       budget, each pole once, and one with more origin poles and members than the budget leaves
       names the ones it didn't ask; with the starred line hidden, its first request goes to the
       pole of the line still shown, with the budget exhausted, so that line's departures show;
     - a ride's cache restored against a refreshed bundle of another content revision keeps each
       line's directions whose two ends the new bundle still places in the ride's ends, shown and
       watched as not checked and their boards asked, also while every sequence fetch fails; a
       direction with an end moved out, a bus line's boarding pole, its alighting stop or a
       train's station, is neither shown nor watched, and a line with no direction left leaves the
       fallback but comes back once a route confirms it; the cursor restarts; against the same
       revision it keeps everything; all in a persisted-state test, with every sequence fetch
       failing;
     - a ride between two hubs joined by rail and bus, seeded from the bus, shows both, boards
       each by its own mode, and uses the mode-neutral copy, and one whose lines are all buses
       keeps the bus copy;
     - the disruptions row asks about every derived line of each ride, less its hidden ones, and
       never a hidden seed;
     - a cached-only line, neither bundled at both hubs nor on any board, takes its turn in the
       cursor order and leaves the cache when its route no longer reaches the far end;
     - a live-only line the bundle doesn't list, cached with its name and mode, restored after
       process death with no board showing it and every sequence fetch failing, still renders by
       its name and boards by its mode, in a persisted-state test;
     - a line whose refreshed sequence no longer calls at the far hub leaves the ride on the next
       read, with the bundle unchanged, and a line whose sequence can't be had shows from the
       cache as not checked and is still watched by alerts, which say its route wasn't
       re-checked;
   - the trip planner's targets:
     - one per record of each station, as today (maintainer, 2026-10-16: no merge), St
       Pancras's three included;
     - plus one bus stop, whether or not the station is in an interchange: the
       first by id of those a station picked on its own owns, as from phase 2, or of the hub's
       own when a hub is picked, so a hub plans one bus target as today;
     - a hub with no station, a junction of bus stops, plans to the stop picked, or to its first
       stop by id when picked as a whole, with a unit test that such a trip still has a target,
       and a persisted-state test that the junction's anchor stop picked as a stop and the
       junction picked whole, saved as a pending end, a recent, a starred place, an open trip's
       To (the near-me trip's too) and Via, an active trip's destination, a favorite ride's From
       picked in Settings before its To and the place editor's match before its position lookup
       finishes, and opened as a page, each restore to the level picked after process death and
       plan, arrive and show at it, with an active trip on the way restored from disk and
       replanned from partway along planning to the stop it was picked as, not the junction, and
       the other way round; and a searched place picked as a trip's From or a new favorite
       restores as a place, never resolved through `Hubs`;
   - the toward-stop search on a trip from the rider's position, for now and later, aims at the
     picked hub's or station's place, for a fixture hub whose first target sits apart from it,
     and at the middle of a fixture junction's stops' poles when the junction is picked whole as
     To;
   - a trip's destination ids (`destinationIds`) at the hub level, phase 2 having built the
     station's and stop's: one to the hub arrives at any of its members, and a planned bus call
     named by its stop area rather than its pole counts as reaching that stop, with a regression
     test for each, a trip to a fixture stop area the bundle doesn't hold arriving at a leg that
     ends at one of its looked-up poles, and a detour test: a route riding on past one pole of the
     picked stop is dropped for one that gets off at its sibling pole no later; and a leg named by
     an area split across two stops, placed by its named pole, else its nearest pole, arriving
     only when that pole is in the trip's end and never at the other child stop;
   - Direct's place for a hub picked as To, the middle of its stations' records as an
     interchange's is today (King's Cross & St Pancras's place from the real bundle equal to
     `StationIndex.positionOf`'s for its interchange), its reach still the rider's walk from
     there, with a test that a bus stop in walking reach outside the hub still
     gives a row and a hub member out of reach doesn't, and that a fixture junction picked whole
     as To is placed at the middle of its stops' poles and gives its Direct rows from there;
   - a trip's closures, asked at every pole of each stop it boards or leaves but judged per pole,
     with a regression test closing one pole of a stop joined from two areas: its sibling, and a
     trip boarding there, stay open, and the notice sits under the closed pole only;
   - the alert fold, and the dismissals it keys, pruned only once every member of the hub has
     settled its closure check, with regression tests of a lone station and its own bus stop
     where only the station was queried, and where both were queried but the bus stop's lookup
     failed, and in each the dismissal stands; a hub whose members are asked over three
     refreshes, the dismissal pruned on the third once every member's newest answer is clear,
     the store recreated between each refresh (the process killed) and still pruning on the third,
     a set saved before the progress field read back with none and its dismissal standing, a
     member found carrying the notice again taken out of the progress, a member answered clear
     and then failed taken out too, so the dismissal stands when every other member then clears,
     with an older clear answer for it landing after the failure not adding it back, a dismissal made again
     starting it empty, and a member's older clear answer landing after a newer one that found the
     notice keeping it; and a dismissal keyed by an area split across two hubs, hiding its notice at both,
     showing a different notice at either, and standing until every pole of the area has
     settled its check; and a dismissal made at a station hub with no interchange, stored by
     the TfL id of the record whose notice it was, never the hub's model id, still matching after
     a refresh regroups that station's records;
   - the route map pairing a stop's two directions by the model's stop alone, with a test that
     two same-named stops of one hub, split from one area for spanning more than 250 m, are drawn
     as two rows, each with its own closures.

   A restored snapshot folds alerts through the bundle, with a test that an `Unbundled` stop
   carrying a TfL interchange id folds by its exact id both live and after a restart, and that a
   bundled interchange's member folds into its hub after a restart as it does live. SPEC's *Trips
   with a change*, *Journeys* and *Disruptions* are updated in the same PR.
4. **Delete what's left**: every rule marked replaced in *Every existing rule*. That means:
   - `StopLinks.sameStation` and `SAME_STATION_METERS`;
   - `StationIndex.twins`;
   - `RouteStops.sameStation`'s membership uses;
   - on the way's copy and `PlaceDirect.oneStation`;
   - `StopPlace`;
   - the four "real cluster" tests;
   - `WAY_BACK_RADIUS_METERS` and `ACROSS_METERS`.

   Then fold the per-section wording the earlier phases kept current into phase 1's *Finding
   stops* paragraph, so the model is described once at product altitude. The terms join `GLOSSARY.md` (`TODO.md`).

## Why ICS and a mode

Decided by the maintainer, 2026-10-16. The alternatives were:
- the National Rail code, which covers National Rail only;
- today's name and mode rule, a heuristic that already splits St Pancras's low level off.

What the data shows:
- An ICS code alone isn't a station: it's what the Journey Planner treats as one place to arrive
  at. TfL's StopPoint data (2026-10-16) shows how much that varies:
  - At King's Cross it draws the station lines exactly: the Underground is 1000129, King's Cross is
    1001171, and St Pancras's three records are all 1001276.
  - At Liverpool Street one code, 1000138, covers the Underground, the terminus, the Elizabeth line
    and its bus stops.
  - At Paddington the terminus and the Elizabeth line share 1001221, and the Underground's two
    stations keep 1000174 and 1000175.
- Adding the mode family keeps Liverpool Street's Underground apart. Its terminus and Elizabeth
  line, both rail, stay one station. The named-record-first rule keeps each train at its own
  record, and display stays per record.
- An interchange's ICS is its main station's (`HUBKGX` carries the Underground's 1000129), so
  only TfL's `HUB…` id names an interchange.

## Favorite rides

**A favorite ride is between two hubs** (maintainer, 2026-10-16). The alternatives were:
- **Two stations.** A ride saved from King's Cross's Underground would miss a Thameslink from St
  Pancras and a bus from outside, which are the same walk for the rider.
- **A radius around each end.** The rider's walk around one station can take in the other end's own
  stops on a short ride (`TODO.md`, *Decide the station bus-stop radius*), and two nearby rides
  become hard to tell apart.

What follows from it:
- The card lists every way from one hub to the other, from the rider's nearest station first.
  This matches *Derive every line between the two places* (maintainer, 2026-10-15, `TODO.md`).
- A radius still decides *when* a ride shows (`TODO.md`, *Make the favorite radius a setting*), but
  not what its ends are.
- A ride already saved keeps its stop ids and is read as their hubs, so nothing is migrated.
- **A ride is its two hubs**. Its identity is its two hubs' ids sorted, worked out
  on read. `FavoriteJourney.key`, the two stop ids, stays the key everything is stored under: its
  alerts (`JourneyAlerts.directionKey`), its hidden lines and its line seed. So nothing stored is
  rekeyed, and an alert set before the change still fires after it.
  - Adding a ride between two hubs a saved ride already joins says it's already a favorite.
  - **Rides saved before keep working as saved where the hub rule would make them one**
    (maintainer, 2026-10-16). Two rides between different members of the same two hubs, or one
    whose two ends now read as one hub, each keep their two stops as their identity and are
    matched stop to stop, as today. Each is its own card with its own alerts and hidden lines:
    nothing is merged and nothing is rekeyed.
  - **Kept as saved is recorded, not worked out from the other rides**. Read
    from the rides that exist, a ride's identity would change when the rider removed its
    sibling, and the survivor would quietly take on another station's departures and alerts. So
    the first load with the hub rule marks each ride it keeps as saved with a stored `asSaved`
    flag, and the flag decides from then on: removing a sibling leaves the survivor matched stop
    to stop. Unlike `alerts` and `pending`, which were added without a version bump, the flag
    comes with one (`PersistedFavoriteJourneys.CURRENT_VERSION` 2 to 3, after phase 2's 1 to 2 for
    the pending ends' levels): an older build that read past it would drop it on
    its next write, and if a sibling were removed there, nothing left could say the survivor was
    kept as saved. The store already preserves a file of a newer version rather than overwrite
    it, so an older build leaves the flag alone; a version 3 build reads an older file and marks
    it. Installing an older build over a newer one is a developer's path, since Play never
    serves a lower version, but it is the one that could lose the flag.
  - **The marking reruns when the bundle regroups**. A weekly refresh can move a
    ride's end into another hub, making its two ends one hub or putting it on another ride's hub
    pair, after the first marking has passed it by. So the rides' file also keeps the bundle's
    content revision (*Each level's id*) they were last marked against, added the same way, and
    any load that finds a different one reruns the marking before anything matches a ride: a
    ride without the flag that the new grouping would merge with another, or whose ends now
    share a hub, is marked and saved. A flag is never cleared, so a ride once kept as saved stays
    matched stop to stop whatever later refreshes do. A ride the rider stars after the change
    gets the flag only if a later refresh regroups it that way.
  - **Everything derived is kept with the saved ride**, so two rides kept as saved never share a
    derived-lines cache.
- **A ride's distance from the rider is its nearest member's**, as a station's is
  its nearest record's. `Journeys.oriented`, `farMeters` and `nearestFirst` measure from each
  end's hub, not the position saved with the end, so a rider by another station of that hub sees
  the ride oriented and ordered from there. The saved position is used only where the end is
  `Unbundled` or the model `Unavailable`, and for a ride kept as saved: matched
  stop to stop, it is measured stop to stop too, from its saved ends as today, so a ride whose
  ends now share a hub keeps two different distances and its orientation, and shows near its
  own stops, not near any member of their hubs.
- A ride whose two ends now read as one hub is a ride between two poles of one interchange, which
  today's rules let a rider save and the new rule refuses. Kept as saved, it's
  never matched as a hub to itself, so its card shows what it shows now.
- A train reaching any station of the far hub counts. That reverses one part of the 2026-10-15
  rule for rides only, the part that kept King's Cross from counting for St Pancras. A trip to a
  station the rider picked keeps it.

## Why "hub"

The maintainer chose the name on 2026-10-16. "Place" was already taken three times over:
- favorite places (Home, Work);
- the landmarks, streets and postcodes that search lists (`dev-docs/PLACES.md`);
- the near-me list's display places.

"Interchange" is too narrow, since a station with no interchange is a hub too. "Complex" reads
oddly for one station and its bus stops. A TfL interchange is one kind of hub, which is why the
code's `hubId` fields are renamed in phase 1.

## For the maintainer

Settled:

1. **The planner keeps one request per record** (maintainer, 2026-10-16: drop the merge), as
   today. An earlier draft planned a station's records of one mode once, St Pancras's three as
   one request, on stations a recorded-response check had approved. Each approval went stale as
   TfL changed a station's records, lines or timetable (three Codex findings on #890), so keeping
   it honest outgrew what it saved: six to eight Planner requests on a trip to St Pancras, at the
   few stations with several records of one mode. Per record finds every journey, with nothing
   to keep in step. A via keeps every record too (`PlanTargets.via`).

2. **A station's own bus stops reach 200 m** (maintainer, 2026-10-16), up from `StopPlace`'s 150 m
   (`TODO.md`, *Decide the station bus-stop radius*).
   - It is one constant for every caller, the near-me list's "at the station" included.
   - At Paddington it takes in a route that 150 m misses, and it changes nothing at King's Cross
     St Pancras (measured in that `TODO.md` item).
   - Its cost is that at a small station a stop on the next road can count as the station's own.
   - With stops named on either side of a slash joined by name, the reach only matters for stops
     named for neither.

3. **The weekly build works out membership** (maintainer, 2026-10-16: "let's move it to the
   batch"), and the device only reads it (*Where it's built*). One copy of the rules, every
   regrouping reviewed in the refresh's pull request, and no spatial pass or readiness wait for a
   build on the device. The cost is about 500 KB raw in the bundle (*Where it's built*), to be
   measured.

4. **A stop the bundle doesn't hold stays on its own until a refresh takes it in** (maintainer,
   2026-10-16). There's no TfL lookup on the device. The cost is a brand-new stop ungrouped until
   the rider installs a release carrying a refresh that holds it, and one outside London's box
   always.

5. **Rides saved before stay as saved where the hub rule would make them one** (maintainer,
   2026-10-16), each its own card matched stop to stop, rather than merged into one card.

6. **A line TfL adds at another stop of a ride's hub comes in with the weekly batch**
   (maintainer, 2026-10-16: "put it in the batch"), not through a live lookup at every member on
   every refresh. One added at the ride's own boarding stop is still found live, as today.

7. **Alerts keep watching a ride's last confirmed lines when a route check fails** (maintainer,
   2026-10-16: "keep watching"), rather than pausing until the route is confirmed again, and
   say the route wasn't re-checked.

8. **Favorite rides stay** (maintainer, 2026-10-16: "I don't see a reason to drop favorite
   rides"). `TODO.md`'s *Retire favorite journeys*, written when Direct shipped, is deleted, so
   phase 3 builds on rides.
