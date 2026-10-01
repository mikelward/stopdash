# Multi-city support and GTFS

**Status: beyond-MVP, not planned.** This is a developer-facing write-up of the
options for supporting cities beyond London, so the analysis isn't re-derived from
scratch each time it comes up. It is not a commitment or a scope — see the
*Beyond MVP (not planned)* item in `TODO.md`, which points here. Any of this is a
product decision for the maintainer, with cost and Play Data Safety consequences,
before a line of it is built.

Routemo is TfL-specific today: the data layer talks only to the TfL Unified API, and
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
    separate alerts API instead. Routemo's honesty floor (warn about a closed line or stop
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

## Cost summary

- **London (TfL Unified API):** £0, keyless (or a free `app_key`). No change.
- **GTFS feeds:** commonly free/open, but confirmed per city, not assumed.
- **A backend (fork #2):** recurring hosting cost, per traffic.
- **An aggregator (fork #3):** per-call or subscription pricing.

Costs are per-provider and unknown until a specific city and fork are chosen.
