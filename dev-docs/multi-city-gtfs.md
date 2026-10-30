# Multi-city support and GTFS

**Status: beyond-MVP, not planned.** This is a developer-facing write-up of the
options for supporting cities beyond London, so the analysis isn't re-derived from
scratch each time it comes up. It is not a commitment or a scope — see the
*Beyond MVP (not planned)* item in `TODO.md`, which points here. Any of this is a
product decision for the maintainer, with cost and Play Data Safety consequences,
before a line of it is built.

StopDash is TfL-specific today: the data layer talks only to the TfL Unified API, and
line colors/codes are TfL's. The question this doc answers is *what it would take* to
show departures for a city TfL doesn't cover.

## Why London stays on the TfL Unified API

This is the load-bearing point: **even if stopdash supported other cities, London would
keep using the TfL Unified API, not GTFS.** The reasons:

- **TfL gives a direct per-stop arrivals endpoint.** `/StopPoint/{id}/Arrivals` returns
  the live predictions for one stop. GTFS has no equivalent per-stop query — GTFS-Realtime
  publishes the *whole network's* trip updates, which a client would have to download and
  filter down to the watched stop. TfL is strictly less work, less data, and less battery
  on the device (SPEC §9 battery budget).
- **It is the authoritative source.** The predictions are TfL's own — the same ones on the
  station dot-matrix boards (TrackerNet-derived for tube, iBus for buses). A GTFS-Realtime
  re-derivation of London would at best echo the same data and at worst be coarser: the
  Unified API exposes per-platform tube predictions that a trip-update mapping models less
  precisely.
- **It's already built and tested.** `KtorTflClient`, the domain model, disruptions
  (`lineStatuses`/`stopDisruptions`), and the line-color scheme all map to TfL's model.
  Switching London to GTFS would be pure regression risk for no user-visible gain.
- **It's free and keyless.** ~50 req/min without a key, ~500 with a user `app_key` — well
  inside stopdash's usage (SPEC *Cost and reliability*).
- **It keeps stopdash client-only.** No backend is needed to serve London. GTFS on a network
  London's size pushes toward a backend (see *The core fork* below), which London simply
  doesn't need.

So GTFS earns its keep only for cities **without** a TfL-quality per-stop API. London is
the reference implementation that keeps its native API; GTFS is the fallback for everywhere
else.

## The GTFS feeds

"GTFS" is two things, and a real-time departures product needs both (plus a disruption
source):

- **GTFS Schedule (static)** — a zip of CSVs (`stops`, `routes`, `trips`, `stop_times`, …):
  the timetable and the stop/route/trip **catalog**. Nearby-stop discovery and display
  labels come from here. Published at a URL, refreshed on the order of weekly. On a large
  network the zip is sizeable (tens of MB), so parsing and storing it — on device or on a
  server — is real work with its own download/refresh/caching/failure handling.
- **GTFS-Realtime (protobuf over HTTP)** — three optional entity types:
  - **Trip updates** — predicted arrival/departure times (or delays), keyed to a scheduled
    trip by the Schedule's stop/route/trip IDs. This is the "live" data.
  - **Vehicle positions** — where vehicles are (not needed for a departures board).
  - **Service alerts** — disruptions. **Optional**, and some operators publish them through a
    separate alerts API instead. StopDash's honesty floor (warn about a closed line or stop
    even with no predictions — `lineStatuses`/`stopDisruptions`, SPEC principle 1) needs a
    disruption source; a provider lacking one needs an honest fallback, never
    unverified-shown-as-clean.

A GTFS-Realtime trip-updates feed references IDs defined in the corresponding Schedule feed,
so **Realtime alone can't supply the catalog** — you always need the Schedule too.

## Live vs scheduled

**v1 would show live predictions only, never a scheduled fallback** — matching stopdash's
current behavior (the TfL Arrivals API already returns only predictions, not the timetable)
and its honesty floor (SPEC principle 1: never present scheduled as live).

- **Telling them apart** — a sketch of the *principle*, not a GTFS-Realtime spec; the exact
  classification rules (each `schedule_relationship`, delay-vs-absolute resolution, feed
  freshness) are worked out against the spec if and when the path is actually built. The live
  candidates are the trips a trip-update actually predicts, from two sources: a **scheduled
  trip with a matching update**, and **realtime-only trips** a feed adds (`ADDED` /
  `UNSCHEDULED` stand alone; `DUPLICATED` copies a referenced static trip and keeps it as its
  template, so it resolves against that trip's `stop_times`, not on its own). A `stop_time`
  with no update of its own is **not automatically scheduled-only**: GTFS-Realtime propagates
  the last reported delay forward to a trip's later stops until another `stop_time_update` or a
  `NO_DATA` overrides it, so a downstream watched stop inherits that delay and is still a live
  prediction — resolve the inherited delay before applying the live-only filter, or valid live
  departures at downstream stops are dropped. Only a stop with no update and no delay to
  inherit is scheduled-only — shown in v1 as nothing. A candidate is `LIVE` only when it
  clears the same honesty floor stopdash already applies to a stale TfL snapshot — a
  **boardable stop, a usable prediction, a trip that is running, from a fresh feed**:
  - a **boardable target stop** — the rider can actually catch this service here, so a
    drop-off-only or reservation-only stop (`pickup_type`) is not a departure to count down;
  - a **usable target-stop *departure* prediction** — the boarding countdown keys off the
    departure event, not an arrival-only update at a timing/layover stop — not `SKIPPED`/
    `NO_DATA`, and resolvable to an actual event time: either an absolute `time`, or a `delay`
    that has a scheduled stop time to add to (a schedule-matched candidate, or a `DUPLICATED`
    trip's template). A realtime-only trip with no scheduled baseline has nothing to add a
    `delay` to, so it needs an absolute `time`;
  - an **acceptable `schedule_relationship`** — not `CANCELED`/`DELETED`;
  - a **fresh feed/entity timestamp** — a stale feed's "predictions" are not live.

  Anything failing these is not-live, never shown as a trusted countdown (SPEC principle 1).
  These are the floor the principle turns on, not the exhaustive field list: the precise
  GTFS-Realtime rules (every `pickup_type`/`drop_off_type` value, each `schedule_relationship`,
  delay propagation, freshness bounds) are settled against the spec at build time, so this
  sketch names *what* must hold, not every field that establishes it.
- **The v1 rule:** render only departures backed by a live prediction; for a service with no
  live data, show **nothing** rather than the timetable. The accepted cost is that a quiet
  stop or off-peak window can look empty — better empty than a scheduled time dressed as a
  live countdown.
- **If we ever added a scheduled fallback (post-v1), how to show it in the UI** so it can't
  be mistaken for live:
  - **Format carries the distinction.** Live = a **countdown** ("3 min", "0 min") — a countdown
    means a **fresh realtime prediction** (a `TripUpdate`), which may or may not be backed by a
    tracked vehicle position; it promises *live data*, not a vehicle we can see on a map.
    Scheduled = a **wall-clock time** ("14:32"), never a countdown. A counting-down number must
    only ever mean a live prediction, never the timetable.
  - **Muted and marked.** Scheduled rows grayed / lower-contrast with a small "scheduled" (or
    timetable "~") marker, sorted **below** the live rows — reusing stopdash's existing
    not-live vocabulary (it already withholds a stale countdown as "—" rather than showing a
    stale number as live).
  - **Domain:** a `source: LIVE | SCHEDULED` flag on `Departure` drives the render. In v1 it
    is constant-LIVE and scheduled rows are never shown.

## The core fork: how we'd source the data

GTFS gives no "departures at this stop" endpoint, so the central architectural decision is
**where the stop-level query happens.** This is the decision that shapes everything else:

1. **Client-only** (matches stopdash today, no backend). Download the static Schedule to build
   an on-device stop index (SQLite), poll the RT trip-updates feed, and filter to the watched
   stops on device.
   - *Cost:* £0 infrastructure.
   - *Reliability / battery:* on a big network the full-network protobuf dump every poll is
     heavy on data and battery — against SPEC §9. Works well only where the RT feed is modest
     or a per-stop query exists.
   - *Privacy / Data Safety:* the Schedule and RT feed downloads leave the device and reach
     each agency's servers, but a **full-feed** download carries no selected stops and no
     location — the filtering is on device — so it adds no collected user-data *type* to
     stopdash's Play Data Safety declaration (Data Safety is about data types collected or
     shared, not "network access", which is just the `INTERNET` manifest permission); the
     provider sees only the request and its network metadata. **Baseline:** stopdash is not
     "location-stays-on-device" today — nearby lookup already sends coordinates to TfL and
     arrivals send watched stop IDs (SPEC *Privacy*). Against that baseline this is the least
     revealing of the three forks: the recipient is each agency's feed host (not TfL), and a
     full-feed download reveals **no per-stop interest** — unlike the per-stop queries in
     forks #2/#3, and unlike stopdash's existing TfL calls.
2. **Add a backend.** Ingest GTFS + RT server-side and expose stopdash's own per-stop arrivals
   API (what Transit / Citymapper do).
   - *Cost:* hosting (a server to run and maintain) — a recurring $/month, not £0.
   - *Reliability:* a new point of failure and latency; the app is down for a city if the
     backend is.
   - *Privacy / Data Safety:* a **new recipient** — a server we run — now sees which stops
     users query (and, for nearby, coordinates). stopdash already sends that data to TfL, but
     routing it to our own server is a **Play Data Safety change** and a departure from
     stopdash's **client-only** posture (a server we operate, not just a third-party API we
     call).
3. **Use an aggregator API** (e.g. Transitland, or a paid transit API) that already exposes
   per-stop departures across many agencies through one API.
   - *Cost:* per-call or subscription pricing, and a third-party dependency.
   - *Reliability:* their uptime and rate limits become stopdash's.
   - *Privacy / Data Safety:* like the backend, each per-stop request reveals the queried stop
     to a **new recipient** — here the **vendor** rather than a server we run — and the same
     departure from the client-only posture. But the Data Safety consequence is **not**
     identical: sending to our own backend is *collection*, whereas sending to an independent
     vendor is additionally *sharing* unless the vendor qualifies as a service provider under
     Play's terms — a stricter declaration whose answer turns on the vendor's role and
     contract, decided when a vendor is actually chosen.

The honest summary: fork #1 keeps stopdash a **client-only app**; forks #2 and #3 make it a
**client that depends on a server** (ours or a vendor's). That is the decision to put to the
maintainer first — everything below is downstream of it.

## Domain contracts to normalize

The domain layer (`app.stopdash.domain`) is *shaped* around one departures model much of a
multi-city version would reuse, but it is **not already provider-agnostic**. A second provider
would have to normalize or redesign these TfL-specific contracts, not just adapt behind an
interface:

- `TflClient` / `TflException` — TfL-named, TfL-shaped.
- `StopFinder`'s NaPTAN stop-type defaults — a UK/TfL taxonomy.
- `LineStatus`'s TfL `statusSeverity` semantics.
- `Departure`'s TfL direction (inbound/outbound) and mode semantics.

(Pill rendering is in the UI layer, `app.stopdash.ui.LinePill`, not the domain.)

## Access and registration

- Most agencies publish their GTFS + RT feeds through an open-data portal, behind a free API
  key or a plain feed URL; rate limits and auth vary per operator.
- The **Mobility Database** and **Transitland** are catalogs of GTFS and GTFS-Realtime feeds
  worldwide — the practical starting point for "does city X have a feed, and where."
- London: TfL's API portal issues a free `app_key`; but per *Why London stays on the TfL
  Unified API*, London would use the Unified API, not TfL's GTFS export.

## What stopdash would not do

- **No crowd-sourced live locations.** Some apps blend their own users' reported positions
  ("I'm on this bus") to sharpen or invent predictions where the agency feed is poor. That is a
  different product: it **continuously broadcasts the user's live position** to a shared
  collection service, a far heavier Play Data Safety profile than stopdash's on-demand,
  one-shot coordinate to TfL (see the baseline above — stopdash is not "location stays on the
  device", but it also never streams location to a crowd-sourcing backend). Out of scope
  regardless of city.

## Candidate cities: Melbourne and Sydney (scoped 2026-10-30)

The maintainer asked for both cities to be scoped side by side before picking one. Sources:
MobilityData's catalog (`mobility-database-catalogs`), each authority's open-data listing.
This sandbox couldn't reach either API (the network policy blocks both hosts), so feed sizes,
latency and actual coverage are **unmeasured**.

| | Melbourne (Transport Victoria / PTV) | Sydney (Transport for NSW) |
|---|---|---|
| Schedule | One GTFS zip, CC BY 4.0, no key | Greater Sydney GTFS zip, CC BY, no key |
| Trip updates (countdowns) | Metro trains, trams, metro buses, V/Line | Buses, ferries, light rail, trains, Metro, regional buses (the catalog lists only trains, Metro and light rail; the authority's trip-update dataset lists them all) |
| Vehicle positions | Same modes | Same modes |
| Service alerts | Metro trains and trams in GTFS-RT; buses unconfirmed | One GTFS-RT alerts feed for all modes |
| Access | Free key, sent as a `KeyID` header | Free key from the open-data portal |
| Rate limit | 24 calls a minute per endpoint per key (one record says 20–27 for metro trains, cached 30 s) | Unconfirmed |
| Refresh | Trains and buses near real time; trams every 60 s | Every feed 8–37 s old when read |

**Both list real countdowns for every mode**, which would let either city hold to the live-only
rule above (no timetable fallback), though Melbourne's tram and V/Line feeds read empty (below). The catalog alone made Sydney's buses look missing, which they
aren't: check the authority's own listing, not just the catalog.

**Melbourne, read with a key** (2026-10-30 20:20 UTC, 07:20 on a Saturday there), each feed
answering in under a second:
- **Metro trains:** trip updates for 141 trips, 56 KB a read, 26 s old; vehicle positions;
  10 alerts.
- **Buses:** trip updates for 1,210 trips (26,792 stop updates), **1.0 MB a read**, 16 s old;
  vehicle positions (59 KB). No bus alerts: the endpoint refuses the key (403).
- **Trams:** trip updates and vehicle positions **answered but held nothing**, three reads in
  a row, while trams were running; 5 alerts.
- **V/Line:** trip updates empty; vehicle positions one train, 4.7 h old; alerts refused (403).
So listed isn't working: until trams and V/Line carry updates, Melbourne has real countdowns
only for metro trains and buses, and a full feed per mode read every 30 s costs the proxy
about 2 MB a minute for buses alone. Worth reading again on a weekday before deciding.

**Sydney, read with a key** (2026-10-30 20:35 UTC, 07:35 on a Saturday there): **every mode
live**, each feed 8–37 s old:
- **Buses:** trip updates for 2,437 trips (87,357 stop updates), **3.5 MB a read**; vehicle
  positions for 902 buses (173 KB).
- **Sydney Trains** 337 trips (160 KB), **NSW TrainLink** 183, **Metro** 34, **ferries** 41,
  **light rail** 14 (Inner West, `v2`) and 49 (CBD and South East, `v1`).
- **Alerts:** 284 in one feed for every mode (433 KB).
So Sydney has real countdowns and alerts for every mode, where Melbourne (above) had them for
trains and buses only. Its bus feed is three times Melbourne's: about 7 MB a minute read every
30 s, for the proxy to carry.

**What decides the fork.** Neither city has a per-stop arrivals endpoint like TfL's: each
publishes whole-network feeds per mode. Melbourne's 24 calls a minute is per key, so a shared
key can't serve riders' devices directly, and a rider's own key on the device means
downloading the whole network's feed on every refresh (fork #1's data and battery cost, still
unmeasured). The middle path is the proxy stopdash already runs for National Rail: it would
fetch each mode's feed about twice a minute, whatever the number of riders, and answer
per-stop requests from it. That is fork #2's shape on infrastructure we already have, with
fork #2's Data Safety consequence: our proxy would see which stops a rider asks about, as it
already does for National Rail boards.

**Also to settle per city, before code:**
- Feed sizes and how often each changes (sets fork #1's cost, and the proxy's).
- Stops and stations: see *Finding stops outside London* below.
- Line colors from GTFS `route_color`, where present, in place of TfL's palette (*Line pill
  colors* in `AGENTS.md`).
- Disruptions: the honesty floor needs alerts for every mode; Melbourne's bus and V/Line alert
  endpoints refused a key (403).
- Accuracy: unmeasured for either city; a recorded week of trip updates against what then ran
  would show it.
- App identity: one app with a city choice, or one app per city, and the TfL-flavored name.

## Finding stops outside London (2026-10-30)

London's stops ship with the app (`assets/stations/stops.tsv`, built by
`scripts/build_stops.py` from TfL), so the near-me list shows the moment there's a fix (SPEC
*Finding stops → Near me now*). Another city would keep that design and change only its
sources: a build script per region writes the same kind of file, rebuilt weekly as its
timetables change. London's file is 22,206 stops in 2.9 MB.

**UK (outside London):**
- **Stops: NaPTAN**, the Department for Transport's register of every bus stop, station and
  pier in Great Britain: open, no key, the source of TfL's own stop ids. BODS uses the same
  ATCO codes, so stops join its timetables and live buses without translation. It holds what
  `stops.tsv` reads from TfL: name, position, pole letter, compass point, and the stop area
  that groups a station's or interchange's poles (in place of TfL's `stationNaptan` and
  `hubNaptanCode`, dev-docs/station-model.md).
- **Lines at each stop: BODS timetables**, as the routes whose trips call there (TfL's
  `lines` today).
- **Rail stations:** NaPTAN lists them with their CRS codes, which the National Rail boards
  already use (`scripts/build_crs_index.py`).
- **Size:** BODS's timetables hold 36,796 stops for the North West and 43,096 for the South
  West, so about 5–6 MB per region at London's density. Shipping one region, fetched when
  the rider picks it, keeps the APK from growing with each region.
- **Measured (NaPTAN, 2026-10-30):** active stops by ATCO area: Greater Manchester 14,045,
  Merseyside 7,461, City of Bristol 1,547 (Bristol's wider area adds its neighboring
  councils' areas). Every active bus stop (`BCT`) has an indicator (TfL's pole letter is one)
  and nearly all have a bearing. A city's slice is 0.4–5 MB as NaPTAN's CSV.

**Australia (Melbourne, Sydney):**
- **Stops, stations and lines all come from the GTFS Schedule itself**: `stops.txt` holds
  each stop's id, name and position, and `parent_station` groups platforms and poles under
  a station; `stop_times.txt` gives the routes calling at each stop; `routes.txt` gives line
  names and, where set, `route_color`. Both cities publish the Schedule without a key (CC
  BY), and its stop ids are the ones their trip updates use.
- **No pole letter or compass point** as NaPTAN has: a stop's name and `platform_code` (where
  set) would carry the side of the road, so the near-me list's labeling would need checking
  against real stops.
- **Measured (Melbourne's Schedule, 2026-10-23):** one 260 MB zip holding a GTFS feed per
  mode. About 29,500 stops: 22,634 metro bus, 3,331 regional bus, 1,617 tram, 779 metro train
  platforms (in 227 stations) and smaller regional sets. Train platforms are grouped under
  their stations by `parent_station`, but tram and bus stops aren't grouped at all, so
  stopdash would group a stop's poles itself, by name and distance. Tram stops carry their
  stop number in the name ("… #45"). Every route has a `route_color`.
- **Melbourne's line colors.** The GTFS `route_color`s are one color per mode for buses
  (`FF8200`, all 974 metro and regional routes), V/Line (`8F1A95`) and coaches (`A57FB2`),
  one per line group for metro trains (Burnley group `152C6B`; Cranbourne, Pakenham and
  Sunbury `34ACE1`; Clifton Hill group `BE1014`; Northern group `FFBE00`; Frankston and
  Stony Point `028430`; Sandringham, Werribee and Williamstown `F178AF`), and one per route
  for trams: 24 near-neighbor colors (route 96 `C6007E`, route 109 `E87722`, …). PTV's
  guide uses them on maps, route diagrams and timetable headers, but riders don't know a
  tram by them ("they don't feel like an identity", maintainer, 2026-10-30), and some clash
  with other modes' (109's orange is the buses'). So trams would likely follow London's
  buses: one mode color, the route number as the label. Train line groups work like tube
  lines.
- **Melbourne's mode colors**, from *PTV Master Style Guide 2018, 2.4 Colour* (supplied by
  the maintainer, 2026-10-30), each a mode's primary palette: Metropolitan Train Blue
  `0072CE` (PMS 285), Tram Green `78BE20` (PMS 368), Bus Orange `FF8200` (PMS 151, as
  GTFS), Regional Train Purple `8F1A95` (PMS 2070, as GTFS; the guide's own RGB for it,
  127/13/130, is `7F0D82`), Night Network Indigo `041C2C` (PMS 296), Ferry Teal `00A499`.
  Its tram route colors match GTFS exactly. Its metro train line colors predate the 2025
  regrouping (Sunbury then with Craigieburn and Upfield; Werribee and Williamstown then
  with Frankston; Cranbourne and Pakenham `279FD5`), so line colors come from the current
  GTFS, not the guide. Its bus route palette is 12 colors repeated across routes, not an
  identity, which GTFS doesn't use either.
- **Measured (Sydney's Schedule, 2026-10-31):** one 312 MB zip ("full Greater Sydney", but it
  reaches well beyond Sydney): 90,823 stops under 78,038 parent stations. Nearly every bus
  stop is its own parent, so the parent groups a stop's records rather than an
  interchange's poles, and stopdash would still group nearby poles itself. 10,267 routes, all
  with a `route_color`, 8,651 of them school buses (`route_type` 712), which a near-me list
  would want to tell apart. Stop names say which side of a junction a pole is on ("Rifle
  Range Rd Before Simpson Av"). Each region's slice would need cutting from the one file.
- Sydney's feeds, read with a key: see *Candidate cities* above.

## Cost summary

- **London (TfL Unified API):** £0, keyless (or a free `app_key`). No change.
- **GTFS feeds:** commonly free/open, but confirmed per city, not assumed.
- **A backend (fork #2):** recurring hosting cost, per traffic.
- **An aggregator (fork #3):** per-call or subscription pricing.

Costs are per-provider and unknown until a specific city and fork are chosen.
