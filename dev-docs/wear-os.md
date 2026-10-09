# Wear OS support

**Status: built; release in progress (maintainer, 2026-10-02).** A developer-facing plan for putting StopDash's
departures on a Wear OS watch, so the design and its open questions aren't re-derived each time.
The work is tracked in `TODO.md` (*Phase 6 — Wear OS*), which points here. *Suggested order*
marks which steps are built; the rest of this document describes the design, built or not. The
watch app is released alongside the phone app (*Distribution*).

## Why a watch

StopDash answers one question at a glance: *what's leaving my stops, and when?* (SPEC intro).
The lock-screen widget answers it once the phone is out; the watch answers it without that,
at the moment it matters: walking to the stop, standing at it, deciding whether to run. So the
watch is another **glance surface**, not a second app. It shows the same rows the widget does,
under the same rules: it renders from a snapshot, and never presents stale data as live
(SPEC D4, *One widget, many surfaces*).

## Surfaces, in priority order

1. **A tile** (Wear Tiles, laid out with ProtoLayout Material 3). The user swipes to it from the
   watch face. It's the widget's watch counterpart: the next few departures for the stops the
   widget shows (D1), **favorites first**, with their line pills and the data's age.
   - Tiles don't scroll, so it shows as many rows as fit (about five) and an edge button,
     **All stops**, that opens the scrollable watch app.
   - When the data is out of date, the countdowns turn to `?`, the age stamp is highlighted, and
     the edge button becomes **Refresh**, which asks the phone for a refresh (below).
   - This is the first deliverable.
2. **A complication** (a `ComplicationDataSourceService`). It puts the single next departure on
   the watch face: short text like "N29 3 min", or long text with the destination. The user
   picks which row feeds it, defaulting to the top starred row (D8). A complication is the most
   glanceable surface of all, but it holds one row, so it follows the tile.
3. **A small watch app** (Compose for Wear OS). A **scrollable** list, rotary or swipe, of the
   same rows. A **Favorites** section comes first, then the widget's other stops as cards, one
   per place (a bus stop's card carries its letter and direction, as on the phone). It's
   **dense**: the direction ("towards …") shares the stop-name line, and rows are compact since
   they're read-only, not tap targets. A
   **Refresh** button sits at the end, and opening the app also asks for a refresh. It's
   read-only: starring, watching and settings stay on the phone.

## The core decision: where the watch's data comes from

### Option A — phone as the source (chosen for the first version)

The phone app stays the only thing that talks to TfL. It already writes a persisted
last-good snapshot for the widget (`WidgetSnapshotStore`). With a paired watch it also pushes
that snapshot to the watch over the **Wearable Data Layer** (`DataClient`). The watch renders
only what it last received.

- **Every credential stays on the phone.** The user's optional TfL `app_key` (D7) and
  National Rail key never leave the phone. The phone makes every request, so the watch never
  splits a rate budget with it. The only new requests are the phone's own fetches when the
  watch asks for a refresh (*Battery and cost*).
- **No location on the watch.** "Near me" results come from the phone's last fix, as the
  widget's interim nearby set does today. The watch app needs no location permission.
- **Works on Bluetooth-only watches**, which are most of them, and costs the watch almost no
  battery: no radio of its own, no polling.
- **Watched set and stars are edited only on the phone.** The watch gets a read-only copy of
  the starred rows with each snapshot (below), so it can pin them as the widget does, but it
  never changes them. There's no second editable copy to conflict.
- **Cost:** it needs the phone nearby or reachable, and the watch is only as fresh as the
  phone's last refresh. The watch-initiated refresh below narrows that.

### Option B — a standalone watch that fetches TfL itself

The watch app calls TfL directly over Wi-Fi or LTE.

- It still needs the watched set and stars from the phone, so it doesn't remove the Data Layer.
  It adds a second client on top.
- It duplicates the TfL client, the rate limiting and the key handling on the watch, and a
  per-watch key or location handling would need its own disclosure.
- It costs real watch battery: a radio wake per refresh on a device with a far smaller battery
  than the phone (*Cost and reliability*: a new wakeup is a battery change).
- It only helps LTE watches away from the phone, a small share of users.

**Decided (maintainer, 2026-09-24): a companion app (A) for the first version.** The watch app
is declared **non-standalone** (it requires the phone app). A standalone watch (B) stays open
for a later version, as its own decision with its own cost and privacy review. Nothing in A
precludes it: the shared `:domain` module and wire model are what B would build on.

## Architecture (option A)

### Modules

This ends the single-`:app`-module layout (SPEC *Architecture*):

- **`:domain`**, a pure-Kotlin JVM module extracted from `app.stopdash.domain`. The package
  already has no Android imports, so this is a move, not a rewrite. Both apps depend on it,
  so the watch groups, labels, pins and times rows exactly as the phone does. That's the same
  "can't drift" guarantee the widget has.
- **`:app`**, the phone app, as today, depending on `:domain`.
- **`:wear`**, the watch app (tile, complication, activity), depending on `:domain` and a
  small shared piece of the snapshot format (next section).
- **A small shared Android library module** for the render inputs that aren't in `:domain`:
  - the bundled `route_topology.json` asset (about 9 KB) and its loader, `RouteTopologyStore`,
    which today are app-only;
  - the widget passes the topology to `DepartureRows.destinationLines`, while the domain API
    alone defaults to `RouteTopology.EMPTY`. Without the same topology the watch would split or
    label branching services differently from the widget;
  - the **line-pill styling rules**, split out of `app.stopdash.ui.LinePill`. That means the
    official TfL colors by line id and mode, the APCA black-or-white label choice, and the
    hollow named-Overground form with its contrast-nudged accent (AGENTS *Line pill colors*).
    Only the pure resolver moves: a function from **a line and the surface color it's drawn
    on** to fill, border and label colors. The surface is an explicit input because the hollow
    Overground form nudges its border and label for contrast against the actual surface (today
    `accentInkOn` / `accentEdgeOn` against `MaterialTheme.colorScheme.surface`). That surface
    differs between the phone's light and dark themes and the watch's black. The Compose
    `LinePill` stays in `:app` and passes its theme surface. The watch passes its own surface.
    So the palette and contrast rules exist once, and each renderer still gets the right
    contrast.

  Both apps depend on this module, so they load the same topology and color the same pills the
  same way. A test runs the phone's and the watch's pill rendering against the one resolver,
  including a hollow Overground pill.

Extracting `:domain` and moving the topology into the shared module are worth their own
refactor PR before any watch code. It's useful on its own (faster domain test builds).

Both apps must share the **same application ID and signing key**. The Data Layer only
connects apps that match. So the planned **package rename must land first**. Renaming after
the watch app ships would break pairing between old and new installs.

### The snapshot over the wire

- **Format:** a **watch envelope** in shared code that reuses the persisted **per-stop** model
  (`PersistedStop`) **unchanged**, but not the top-level `PersistedSnapshot`.
  - `PersistedStop` already holds every per-stop input the widget's render path reads through
    `DepartureRows.across`. That includes the grouping fields (`direction`, `branch`), the
    bus-pole and place qualifiers (`clusterId`, `stopLetter`, `bearing`, `towards`), the
    terminating filter's inputs (`nearerIds`/`nearerNames`, each departure's `destinationId`),
    and each stop's `fetchedAt` and `arrivalsFresh`.
  - **One gap:** `StopArrivals.railFeed` (National Rail `LIVE`, `NO_KEY` or `UNAVAILABLE`),
    which `DepartureRows.statusRows` reads so `NoTimes.of` can tell an empty board from a
    missing key or a failed board, isn't persisted today. The envelope step first adds it to
    `PersistedStop`, so it survives a restart and rides to the watch like every other field.
    The parity test covers all three states.
  - So the watch renders from the same input the widget does, with the same code, and a new
    field the widget starts reading reaches the watch without anyone remembering to list it.
    A hand-picked field list would keep drifting behind the domain.
  - The envelope leaves out the top-level journey fields (`journeys`, `journeyOnlyStopIds`:
    origins, destinations, direction). Whether journeys go to the watch is an open question,
    and journey data would ride the Google relay too. Adding them is a deliberate change with
    its own disclosure. (A trip on the way does go, as its own item: *Trip on the way*.)
  - Because journeys are left out, the phone also **drops every journey-only stop** (a starred
    journey's origin outside the nearby set, in `journeyOnlyStopIds`) before building the
    envelope. The widget shows nothing but the journey at such a stop (`withoutJourneys`), so
    sending it bare would let the watch show its ordinary departures, out of the widget's scope.
    A nearby journey origin stays, and its departures show as ordinary rows, since the watch has
    no journey card to show them twice.
  - It adds the carried stops' nearest-first order (`nearestFirst`) and the stop the app shows each
    line from (`nearbyChoices`), the snapshot's own, never the distances, so the watch folds each line
    to the app's stop with the widget's `DepartureRows.glanceFolded`. Additive: an older watch ignores it, an older phone sends none.
  - It adds the starred-row keys (below) and its own format version, so an older watch app can
    refuse a newer phone's snapshot rather than misread it.
  - And the phone's **refreshed route lines** (`routeLines`): the branching lines where a refresh
    took TfL's current routes over the bundled topology (SPEC *Branch merging*), usually none. The
    watch puts them over its own bundled asset with the same `withLive` check
    (`RouteTopologyStore.over`), so a line extended since the build is grouped on the watch as the
    widget groups it, and a phone on a different build than the watch can't hand it routes its own
    asset doesn't agree with. They're kept whatever stops are dropped past the ceiling (a few KB at
    most), and sorted so an unchanged set encodes the same and doesn't republish.
- **Scope:** a **bounded superset** of the widget's stops and their rows, not just the handful
  the tile shows at first. It's enough for every watch surface:
  - the watch app's scrolling list,
  - the complication's row picker,
  - the tile's whole timeline window, so a row can move up when one above it runs out of
    departures.

  Each surface applies its own display limit when it renders. The phone still doesn't send the
  whole in-app list.

  **The cap is on encoded bytes, not rows.** Each included `PersistedStop` carries its whole
  departure list, and a busy interchange can hold hundreds of predictions. So a row count alone
  can't keep the payload under the `DataItem`'s roughly 100 KB limit. The phone therefore:
  - trims each stop's departures to its **freshness window**: every departure predicted before
    that stop's staleness boundary (its `fetchedAt` plus the shared threshold), plus, for each
    destination group, enough departures after the boundary to fill the largest per-group
    display cap the shared renderers use (today three, in the widget and the app). So a fresh
    snapshot whose next departures all fall past the boundary (6, 8 and 10 min) still shows the
    full countdown set on arrival. There's **no per-row count cap** inside the window,
    so a high-frequency row keeps every service the tile or complication could show before it
    turns stale. A later destination group that `DepartureRows.destinationLines` keeps also
    survives the trim. The threshold is a few minutes, so the window stays small even at a busy
    interchange;
  - encodes the envelope and checks its **byte size** against a budget well under the
    `DataItem` limit;
  - if it's over, sends the **whole envelope** as a Data Layer `Asset` referenced from the
    `DataItem`. The widget's stop set is never cut to fit the `DataItem`;
  - only past a hard **transfer ceiling** (a sanity bound on transfer and watch battery, far
    above any realistic watched set) does it drop whole stops, lowest-ranked first by the
    widget's render order, holding back starred and complication-selected ones until nothing
    else is left. The ceiling is hard: in the pathological case where those alone exceed it,
    they go too, lowest-ranked first, with their star keys. That truncation is visible: the
    watch app ends with a "More stops on phone" line, so it's never silent.

  Tests:
  - A synthetic busy interchange stays under the budget.
  - A watched set over the budget goes out whole as an `Asset`, with every stop.
  - A row with more arrivals inside one freshness window than any display cap keeps every one of
    them, so the tile and complication never go empty early.
  - Every destination group survives the trim, with its full display cap of countdowns even
    when all of them fall past the boundary.

  **Rows the watch depends on come first.** The phone fills the envelope in priority order:
  1. every **starred** row;
  2. every row a **complication is set to**. The watch syncs its complication selections to the
     phone as a small `DataItem` of row keys, so the phone knows them.
  3. the rest of the widget's rows, in widget order, up to the ceiling.

  So a row the user picked can't fall out of later envelopes when departures reorder. If a
  picked row has no departures at all, its stop is still sent, and the complication shows its
  empty or stale form, never a missing row.

  **The one exception is the transfer ceiling.** If the starred and selected stops alone exceed
  it (far past any realistic set), the lowest-ranked of them are dropped too. A complication whose
  stop was dropped this way treats it like a stop that left the widget's scope (below): it falls
  back to the default row, or *no data*, never the old row frozen, and it keeps its selection, so
  the row returns when a later envelope fits. The watch app's "More stops on phone" line says
  stops are missing.

  **A picked stop that leaves the widget's scope** (for example, the widget's nearby set moves
  with you and `SnapshotStore.pruneStops` drops it) is no longer in any envelope, since the
  watch shows the widget's stops. Then:
  - the complication **falls back to the default row**, the top starred row, else the widget's
    first row;
  - it shows that row, not the old one frozen;
  - the watch **clears the stale selection** and syncs the change, so the phone stops asking
    for it.

  A test covers a selected stop pruned from the snapshot.

  **No stops at all** (the user watches nothing yet, the widget's nearby set is empty, or no
  envelope has arrived since install) is its own explicit state, never a blank surface or the
  previous envelope's rows:
  - the tile shows one line, **Add stops on your phone**, with a single entry and no staleness
    breaks, since no stop has a boundary; before the first envelope it says **Open StopDash on
    your phone** instead;
  - the complication returns Wear's *no data* result (a dash), not a stale row;
  - the watch app shows the same one-line message as the tile.
  An envelope with zero stops replaces the previous one, so removing the last watched stop
  clears the watch.

  **Stops but no rows** (every stop has no predictions, fresh or carried forward) is distinct:
  - the tile and app list each stop with its empty form, the fresh "No departures" or the
    uncertain "may be out of date" one, per its `arrivalsFresh`, until its boundary;
  - a complication with a selected row shows that row's empty form, as above;
  - a complication with no selection and no row to default to returns *no data*, like the
    zero-stop case, and picks up the default row once one appears. It never keeps an older
    result.

  Tests cover the zero-stop envelope, the never-synced state, and a nonempty envelope with no
  rows, for each surface.
- **Contents beyond the stops:** the **starred rows' identities**, meaning the
  `StarredRow` keys the widget pins by (D8): (stop, line, **resolved `directionKey`**), not
  TfL's raw `direction`. When TfL leaves `direction` blank the key falls back to the platform
  or destination, so two sibling rows at one stop stay distinct. Complication selections use the
  same key. The widget reads stars from a separate store, not
  from the snapshot, so the envelope must carry them explicitly. Without them the watch
  couldn't pin starred services or pick the complication's default row.
- **Never a coordinate and never the `app_key`**: `PersistedStop` has neither, and a test pins
  that the envelope doesn't either (*Privacy*).
- **The nearer-stop lists travel too, and are disclosed as such.** `nearerIds`/`nearerNames`
  are stop IDs and names worked out from the phone's last fix, and they can name stops outside
  the widget's displayed set. They stay in the envelope, so the watch runs the terminating
  filter with the widget's own code, and the privacy inventory (*Privacy and Play Data
  Safety*) lists them explicitly as location-derived place data, alongside the widget's stops.
- **Per-stop refresh flag:** after a partial refresh, a stop whose fetch failed is carried
  forward with `arrivalsFresh = false`, while another stop's success makes the snapshot worth
  publishing. The watch shows such a stop's countdowns with the same uncertainty treatment the
  app and widget use (`DepartureRows`), not as live, before they age into stale.
- **Incompleteness, before the watch ships.** On an *initial* multi-stop refresh where one stop
  fails, `Snapshot.mergeStop` leaves the failed stop out entirely. So there's no `PersistedStop`
  to mark with `arrivalsFresh = false`, and the result looks complete and fresh. The widget has
  the same gap, tracked in `TODO.md` (*Persist a refresh-failure kind / incompleteness for the
  widget*). The watch doesn't ship with it:
  - That item is a **prerequisite of the phone-side publisher** (step 4 below).
  - Once it persists the expected stop set (or a partial-refresh flag) and the failure kind,
    the envelope carries them too.
  - The watch then shows a missing stop's absence as "some stops couldn't be refreshed", the
    widget's form, never a clean "updated just now".
- **Disruptions, as the widget has them.** The snapshot keeps each shown line's status check,
  stamped with when TfL gave it (`DeparturesSnapshot.lineStatuses`), and the envelope carries the
  checks for the lines its stops show (`WatchEnvelope.lineStatuses`, additive, so an older watch
  app ignores it). The tile, the app and the complication mark a disrupted line the way the widget
  does ("⚠ Severe Delays", a suspended line with no countdown as its status alone) and withhold
  each mark at the same expiry, the shared staleness threshold from its own check time: the
  tile's timeline and the app's ticker break there, and a complication entry spanning it is split.
  A check carries the line's work still to come too (`PersistedLineStatus.planned`, its label,
  start day and dismissal identity, never TfL's prose), so the tile and the app put the widget's
  calendar beside a row's first countdown; the complication has no room for it. Work that starts
  while its check is live turns into its ⚠ at that midnight in London, so the tile's timeline, the
  app's ticker and the complication's timeline break there too (as the widget's redraw does). Each planned
  alert's `dismissed` flag is set by the phone, as the status's is, and the watch keeps its
  identity because that is what the flag marks. So is each status's `behind` (the stops and ways
  the app found its bus alert wholly behind, `DeparturesSnapshot.withAlertsBehind`): the watch has
  no routes, so a row there carries no ⚠ only where the phone placed the alert.
  A line with no live check (never checked, the lookup failed, the check aged out) turns the
  tile's and the app's note to "Couldn't check for disruptions" and the tile's foot to Refresh.
  Stop closures still stay off: they have no age-stamped rendering.
- **When:** on every snapshot write that already pokes the widget, and on every change to the
  starred rows (starring or unstarring updates only the star store, not the snapshot, so it's a
  trigger of its own). Both happen whenever the watch app is
  installed on a paired watch (`CapabilityClient`), **whether or not the watch is connected right
  now**. A `DataItem` is state, not a message: Google Play services syncs the latest one when the
  watch reconnects, so a snapshot written while it was away still arrives. A phone with no watch
  app does no extra work.
- **On reconnect:** as a backstop, the phone also republishes its current persisted snapshot when
  the watch app's capability appears or its node reconnects. So a watch never keeps an older
  snapshot than the phone's just because no refresh happened after the reconnect (for example,
  with live refresh off).

### Refresh

- **Push, don't poll.** The watch never schedules its own data refresh. It re-renders when a
  new snapshot arrives: the tile asks its updater for a re-render, and the complication asks
  for a data update.
- **Watch-initiated refresh.** Tapping the tile's **Refresh** line, opening the watch app, or its
  Refresh button sends a `MessageClient` request to the phone. The phone runs one bounded fetch of the widget's stops, location-free unless *Widget follows you* is on
  (the same work as a `WidgetRefreshWorker` cycle, D1/D5) and pushes the result.
  - It's debounced on both ends, so repeated taps can't become a polling loop: the watch sends at
    most one request per 30 s, and the phone reuses a stop fetched in the last 30 s (answering
    *debounced* and resending the snapshot when every stop was). A failure saves nothing to
    reuse (the phone resends the stored snapshot instead, as for *debounced*). When every stop
    failed, that outcome answers the next request (a watch's or the widget's) within 30 s, while
    the stored snapshot is still the one that refresh started from. Watch refreshes and the widget's own
    refresh cycle take turns, so overlapping ones fetch once.
  - **The phone answers every request** with a typed outcome message, so a tap never silently
    does nothing (principle 2):
    - *refreshed*, with the new snapshot following as a `DataItem`;
    - *partly refreshed* (some stops failed and are marked, above);
    - *not refreshed*, with the reason: rate-limited, TfL unreachable, the user's API key rejected
      (SPEC D7, named over the others since only clearing it on the phone mends it), or no stops
      to fetch;
    - *debounced*: the current snapshot is recent enough, so no new fetch.
  - The watch briefly shows a failure ("Couldn't refresh: rate-limited") over the last
    snapshot, which stays stamped with its age. An all-failed refresh leaves the persisted
    snapshot unchanged (`WidgetRefresh.refreshedArrivals` returns null), so the outcome message
    is the only way the watch can know.
  - The phone acknowledges a request the moment it arrives; the watch waits 30 s for that (or the
    answer), then up to 2 min more once acknowledged, since the phone's work may queue.
    A later answer to the same request still replaces the out-of-reach notice. The unanswered
    request is kept on disk, so a watch process killed while waiting still takes its answer.
  - When no answer arrives in time (an unreachable phone), the watch keeps the last snapshot,
    stamped with its age, and says the phone is out of reach. It never shows a blank tile, and
    never passes old times off as live.
  - Each request carries a random id that its answer echoes, so a late answer to a timed-out
    request can't settle a newer one. The phone queues a watch's requests and answers each.
- The phone's existing opt-in **live widget refresh** (D5) keeps the watch fresh too, since
  each cycle pushes. It needs no watch-side setting.

### Staleness on the watch (D4)

The watch has the same honesty problem as the widget: nothing re-renders it on its own.
Wear's own APIs solve it without wake-ups:

- **Tile:** one snapshot becomes a **timeline** of entries, each with its own validity period.
  An entry ends at the **earliest** of three boundaries:
  - the next whole minute of any displayed countdown,
  - the predicted time of any displayed departure, and
  - **each in-scope stop's own staleness boundary** (its `fetchedAt` plus the shared
    threshold). That means every stop in the envelope's scope, **including a stop that
    currently shows no rows**. Otherwise a tile that opens on "No upcoming departures" would
    have no future entry to turn that stale, and would keep presenting an old absence as
    current.

  A partial refresh leaves stops with different fetch times: `DeparturesSnapshot` keeps each
  stop's own, and its top-level time is only the freshest. So one boundary for the whole tile
  would either keep an older stop looking live too long or mark a fresh one stale too early.
  At each stop's boundary the entry is rebuilt with that stop's rows in the stale form, while
  fresher stops stay live, exactly as the app and widget withhold per stop.

  So the countdowns tick down without the tile being re-fetched, and a service that leaves
  mid-minute **drops off at its departure time**. The rows below move up then, not at the
  following minute, and nothing sticks at "0 min" (D4, SPEC *Freshness*). Each entry is built
  as the in-app list would be at its start time. The last entry starts once every
  in-scope stop is past its threshold, and shows the stale treatment (`?`, "tap to
  refresh"). This is the watch
  counterpart of `WidgetStalenessRedraw`, with no alarm needed. That last entry, like any
  open-ended one, carries **no validity interval** — the renderer's default for any time no
  other entry covers. A start with no end reads to the renderer as a zero-width interval it
  never shows, which left the tile blank.

  A new entry opens only where the frame actually changes, so a tick that moves no shown
  countdown, or a row that can't make the tile's lines, costs nothing. A timeline takes at most
  100 entries, two of them kept for a refresh notice's changes to split (a pending refresh
  turns to out of reach at its timeout on the timeline itself, even if the watch process is gone). A snapshot busy enough
  to need more stops at the first
  instant that doesn't fit: from there one open-ended entry withholds every countdown, and the
  tile asks the system to re-render it at that instant, so a held frame never shows a departed
  service or a frozen countdown as live.
- **Complication:** a **timeline** too, with one entry per upcoming departure of the chosen row.
  - If the chosen stop was **carried forward** after a failed refresh (`arrivalsFresh = false`),
    every timed entry carries an explicit **uncertainty marker**, the same one the app puts on a
    carried-forward stop's rows. A complication has no room for the widget's separate "Some
    stops couldn't be refreshed" note, so the marker has to sit on the time itself. The tile
    has room, so it shows that note as the widget does.
  - Each entry holds a time-difference text counted down by the system to that departure. It
    stays valid until the **earlier** of that departure and the stop's staleness boundary. So
    when one service departs, the complication **advances to the next** instead of holding at
    "now". No departure entry is built to start after the boundary, so a live countdown and the
    stale entry never overlap.
  - If the snapshot's departures for the row run out before its stop's staleness boundary (that
    stop's `fetchedAt` plus the shared threshold), what comes next depends on the stop's
    `arrivalsFresh`, as the widget's empty state does:
    - **Fetched this snapshot** (`arrivalsFresh = true`): a **fresh empty** entry, the row's
      line and stop with no time, until the boundary. A successful fetch established there's
      nothing more to show, so it isn't marked stale.
    - **Carried forward** after a failed refresh (`arrivalsFresh = false`): an **uncertain
      empty** entry, the widget's "may be out of date" form, until the boundary. No fetch
      established that there's nothing newer, so it never claims a fresh empty result.
  - The final entry starts at that stop's staleness boundary and shows the stale form.
- **The watch app** isn't driven by a system timeline, so while it's in the foreground it runs an
  **in-memory ticker**: it recomposes at the next countdown minute, the next departure time, or
  the next stop's staleness boundary, whichever comes first, from the same pure
  (snapshot, now) builder the tile uses. No network, no polling; it stops when the app leaves
  the foreground and recomputes on resume. A test drives a foregrounded app past a departure and
  past a stop's boundary with an injected clock.
- **Setting the watch's clock:** each surface reads the envelope as the clock reads it now, moved
  for any setting since it arrived (SPEC *Freshness*). The ticker does so at each wake, and a
  setting of the clock (`TIME_SET`) asks the tile and complications to re-render, since the system
  steps their timelines by the wall clock and would otherwise hold an entry built before the
  change until the clock caught up with it. That's one re-render per setting, no wake-up otherwise.
- All three use the **one shared staleness constant** from `:domain`, never a watch-specific number.

Verify both mechanisms against the current Tiles and Complications APIs when this is picked up.
The timeline and time-difference support is the reason the watch needs no polling, so confirm
it before committing to the design.

## Trip on the way

- **Decided (maintainer, 2026-10-03):** the watch shows a trip on the way: the step the rider is
  at, Previous and Next to page through the steps, and the next ride's trains when there are some.
  Sending it is a deliberate change to what leaves the phone, disclosed with it (SPEC *Privacy*,
  `docs/PRIVACY.md`).
- **Its own `DataItem`** (`WatchSyncContract.TRIP_PATH`, `WatchTrip`), not a field of the snapshot
  envelope: a trip changes on its own cadence, and is deleted when it ends. The envelope still
  carries no journeys.
- **The phone renders every word** (each step's text, and what to do now, as the trip's own screen
  says it), so the watch adds none beyond its controls and the trip reads in the phone's language.
- **While `OnTheWayService` follows the trip**: sent on each change, and every 30 seconds so its
  minutes count down, but only when what the watch would show changed or a minute has passed
  since the last send (a heartbeat, so a rider standing still isn't marked out of date), and only
  to a watch with the app. A trip followed only by the open app (the service refused) isn't sent.
- **The next ride's trains** are the trip screen's: every pole of the boarding pair, each filtered
  as the ride boards there. A line whose route isn't held yet is loaded through the shared route
  repository rather than its trains dropped; a load that failed waits five minutes before the next.
  Each is named with its branch where that's a choice from the stop ("Morden/Bank"), as the tile
  and the trip screen name them, so the watch's trip and its departures read alike (maintainer,
  2026-10-06).
- **A board too old to stand behind** (D4; maintainer, 2026-10-06): its trains still go, as the trip
  screen keeps them under "Checking…", but in their own field, `WatchTrip.oldDepartures`, not
  `departures`, and the watch draws them as marked guesses ("21:14?") as it does a trip gone out of
  date. The phone's home-screen widget, drawn from the same item, dims them as guesses too. A field
  of their own rather than a flag, so a watch app older than the phone's ignores them and shows
  only the note, never an old board's times as live.
- **On the tile** (maintainer, 2026-10-06): while the watch shows a trip, the tile shows it in the
  departures' place, as the phone's widget does (`TileTrip`): the heading, what to do now, and the
  next ride's trains under their poles. Its timeline changes at each train's departure and countdown minute,
  marks the trip out of date where the watch app would, and asks for a fresh render where the trip would go, when the
  tile shows its departures again. Each update from the phone asks the tile to render again.
  Where the pair's other poles have trains too, each pole's sit under its own "Stop N", as the trip
  screen heads them. One leaving before the rider can be there is grayed, as the trip screen grays
  it, and is sent only where catchable ones leave room in the watch's three.
- **More than one phone's item** (after a phone change, until the old one's is removed): no
  stamp picks between them, since two phones' clocks can't be compared (maintainer, 2026-10-03).
  The latest update received wins; a read-back keeps the held trip's phone while it still has an
  item. A stale item from the old phone is replaced within a minute by the active phone's next
  update, and a removal reads back what's left, clearing the trip if the read fails.
- **The watch only shows it.** Paging moves nothing; a new step from the phone brings the page back
  to the rider's. The watch app holds it in memory, and one started later reads the item back. The
  Data Layer keeps the item until the phone sends or removes the next one, so one left by a phone
  app that stopped without ending its trip outlasts the 15 minutes it's shown for.
- **An ongoing activity on the watch** (maintainer, 2026-10-06): the phone's ongoing notification
  doesn't reach the watch (it isn't bridged), and a louder channel buzzing the phone to get there would
  undo its point, so the watch posts its own from the trip it holds: a silent, watch-only ongoing
  notification wrapped as a Wear OS `OngoingActivity` (`WatchTripOngoing`). The system shows it as
  an icon on watch faces that support one and at the top of the launcher; a tap opens the watch app
  on the trip. It says only the rider's step in the phone's words, never a countdown, since it isn't
  redrawn between updates. Each update from the phone posts it again, pushing back its timeout to
  where the trip would read out of date (two minutes, *Staleness* below): it can't mark itself out
  of date as the app does, so it stands only while the trip is current, and the trip's removal
  takes it off sooner. A setting of the watch's clock works it out again (`ClockChangeReceiver`). It needs the watch's notification permission, asked for when the watch app
  first shows a trip; refused, the
  watch app still shows the trip. The watch never opens the trip by itself: that needs a
  full-screen intent, and would take over the watch face uninvited.
- **Staleness:** out of date two minutes after the phone stamped it, when stamped ahead of the
  watch's clock, or after two minutes held without an update; not shown after fifteen held without
  one. Time held is measured by each device's monotonic clock (as is the phone's heartbeat), so no
  clock change holds either back, and a trip the phone stopped following without deleting it (the
  process died) doesn't linger as current.
- **Battery:** at most one write per 30 seconds while a trip is followed and a watch has the app,
  each a few hundred bytes, and at least one a minute, each also re-posting the ongoing activity
  (no wakeup of its own); the watch app re-renders the trip every 15 seconds while open. Measure on a watch with the rest.

## Privacy and Play Data Safety

- The departures, the names of the widget's stops, each stop's **nearer-stop IDs and names**
  (location-derived, and possibly stops the widget doesn't show) and the starred-row keys move
  from the phone to the user's own watch through Google Play services' Wearable Data Layer. In the other
  direction, the watch sends the phone the **(stop, line, `directionKey`) key of each row a
  complication is set to** (*Rows the watch depends on*), and its refresh requests. When the watch
  isn't on Bluetooth (a Wi-Fi-connected watch), that sync may relay through Google's servers,
  so the watched stops and their nearer-stop lists can leave the device to Google.
- **Decided (maintainer, 2026-09-24):** the relay through Google's servers is acceptable,
  with a disclosure. The watch doesn't have to be Bluetooth-only.
  - `docs/PRIVACY.md` and SPEC *Privacy* say that the watch sync, nearer-stop lists
    included, goes through Google Play services and may pass through Google's servers.
  - The Play Data Safety answers are checked for whether that counts as data shared or
    collected, before the watch release.
  - At implementation, confirm how the Data Layer actually routes, so the disclosure describes
    it accurately. Confirmed at launch (2026-10-02) from Google's Data Layer overview: an
    encrypted Bluetooth channel when connected, otherwise routed through Google Cloud, where
    "all data transferred … is end-to-end encrypted". `docs/PRIVACY.md` now says so.
- No coordinates, no key and no location permission on the watch (option A).
- The watch app's diagnostics follow the same rules as the phone's: on-device only unless the
  user opts in (SPEC *Privacy*). Whether the phone's crash-report opt-in also covers the watch
  is an open question below.
- `docs/PRIVACY.md`, SPEC *Privacy* and the Data Safety determination land **in the same PR
  as the phone-side publisher** (step 4 below), not later. That's the first PR that can send
  watched-stop data through Google's relay, so the disclosure can't trail it. The complication
  PR (step 7) then extends the same paragraph and the Data Safety determination with the
  watch-to-phone selection sync, because that PR introduces it.

## Battery and cost

- **£0:** there's no new external service. The Data Layer is part of Google Play services.
- **TfL and National Rail traffic:** the watch adds no *periodic* requests, since pushes reuse
  the phone's own fetches. But a watch-requested refresh **is** a new request source: each
  accepted tap is one fetch of the widget's stops, the same size as an app open (plus, with *Widget follows you* on and the phone moved, one stop lookup).
  - It runs through the same rail-aware client as `WidgetRefreshWorker`. So for a user with a
    National Rail key, it also makes **one National Rail request per rail station** among the
    widget's stops. That's £0, and it counts against that user's own key's limit (SPEC *National
    Rail*).
  - A failed or rate-limited board fails on its own, as in the app, never failing the refresh.
  - Both keys stay on the phone.
  - The phone debounces the taps: a tap inside the window gets the current snapshot rather
    than a new fetch. So the worst case is one refresh per window per watch.
  - That's well inside TfL's keyless per-IP budget (about 50 requests a minute). But it shares
    that budget with the phone app and live refresh, through the same shared rate limiter
    (`SharedTflRateLimiter`).
  - A rate-limited refresh keeps the last snapshot and says so.
  - Pick the debounce window when the work starts.
- **Phone battery:** one Data Layer write per snapshot the phone already takes, and only when the
  watch app is installed on a paired watch. A watch-requested refresh is one extra fetch per tap, debounced.
- **Watch battery:** no fetching of its own and no polling. But **receiving** each pushed
  snapshot costs something: a Bluetooth or Wi-Fi transfer, a decode, and a tile/complication
  update. With the phone app open, or live refresh on, that could be about once a minute. So
  the phone keeps pushes to what changes:
  - It **suppresses identical publishes**: a snapshot whose wire content (rows, times, flags,
    stars) is unchanged since the last publish isn't written again. A `DataItem` rewritten with
    the same bytes doesn't sync anyway, but skipping the write saves the phone work too.
  - It **coalesces bursts**: at most one publish per short window, latest wins, so a refresh
    that saves several times sends one update.
  - **A pending publish survives process death.** The phone keeps a small marker of the last
    envelope it published: its content hash, in app storage. On every start, and on reconnect,
    it compares that marker with the envelope the current persisted snapshot and stars would
    produce, and publishes if they differ. So a publish lost when Android killed the process
    mid-window goes out on the next start, without waiting for a new snapshot.
  - **A failed publish is retried.** If `putDataItem` fails while the process lives, the phone
    logs it (sanitized: the failure type, no stop IDs), leaves the marker unchanged, and
    enqueues one unique, bounded retry job (WorkManager, backoff, a few attempts, latest
    envelope wins). The start and reconnect checks still backstop it. So a failed write never
    leaves the watch stale until some unrelated snapshot happens along.
  - The watch requests one tile and one complication update per received snapshot, and renders
    nothing between updates beyond the timeline ticks the system runs anyway.

  Measure the transfer and render cost on a real watch before release, and state it in the
  release PR as a battery change.

## Testing

- **Domain:** unchanged. It keeps its JVM tests with recorded TfL fixtures, now in `:domain`.
- **Wire model:** a test pins that it has no journey fields (as well as no coordinate or key),
  so adding journeys takes a deliberate change.
- **Snapshot contract:** a round-trip test that the phone's envelope decodes on the watch side
  to `PersistedStop` values **equal** to the phone's. With the shared topology loaded, it builds
  **the same rows and the same destination groups** (`destinationLines`) as the widget. The cases include branching and two-direction stops, bus poles with letters,
  services the terminating filter drops, a journey-only origin (absent from the envelope),
  pinned stars, and blank-direction siblings (two rows
  at one stop whose `directionKey` falls back to platform or destination, each pinned and
  selected on its own). It also checks a
  version-mismatch refusal. Another test pins that no coordinate or
  key fields exist in the wire format.
- **Tile, complication and watch-app layouts:** screenshot tests (the app's dense list on a
  round screen, clipping at the edges, and the largest font scale), like the widget's
  Robolectric/Roborazzi tests, wired into CI's `--tests` allow-list (AGENTS *Testing*). Check
  what the Wear Tiles tooling supports in Robolectric; fall back to preview-based rendering
  tests if needed.
- **Staleness:** the tile and complication timeline builders are pure functions of
  (snapshot, now), tested with an injected clock.
  - A departed service drops off and the next one advances at its exact departure time, even
    mid-minute.
  - A row that runs out of departures shows the fresh empty form until its stop's boundary if
    the stop was fetched this snapshot, and the uncertain form if it was carried forward.
  - A tile that starts with no rows at all still turns stale at its stops' boundaries.
  - With stops fetched at different times, each stop turns stale at its own boundary while
    fresher stops stay live.
  - The last entry shows the stale form once every stop is past its boundary.
  - No entry ever shows a live-looking "0 min".
- **Reconnect:** a snapshot written while the watch was away is what the watch shows after it
  reconnects (a fake Data Layer).
- **Process death:** a snapshot persisted, then the process killed inside the coalescing window
  before the publish, is published on the next start (last-published marker vs current
  envelope). A publish that fails in-process is retried by the bounded job and succeeds on a
  later attempt, with no new snapshot.
- **Pill resolver:** hollow Overground contrast is checked on a light surface, a dark surface,
  and the watch's black.
- **Refresh request:** the phone-side handler is tested with a fake message source and a test
  dispatcher. The cases are debounce, a partial refresh, an all-failed or rate-limited refresh
  (each answered with its typed outcome), and the phone-unreachable timeout on the watch side.
- **Incompleteness:** an initial refresh where one stop failed reaches the watch marked
  incomplete (via the expected stop set), not as a clean fresh result.
- **Envelope priority:** starred rows and complication-selected rows are always in the envelope,
  even when departures reorder and the cap would otherwise cut them.
- **Per-stop flag:** the round trip keeps each stop's `arrivalsFresh`. A carried-forward stop
  renders with the uncertainty treatment, not as live: the marker on every complication entry,
  and the note plus marked rows on the tile.
- **Device check:** a Wear OS emulator paired with a phone emulator for the Data Layer path.
  Say so in the PR when this can't be run.

## Distribution

- A **Wear OS release** in the same Play listing and package, on its own form-factor track. The
  Wear AAB is built by `:wear` in the same CI, signed with the same key.
- **Released (maintainer, 2026-10-02).** The release gate that kept `:wear`'s release build
  from running until the launch decision is gone. CI builds and signs the watch bundle with the
  phone's, and uploads it to the Wear OS internal track once Play Console is set up for it
  (`dev-docs/play-store-internal-track.md`, *The Wear OS app*). Its versionCode is main's commit
  count plus 100,000,000, since every bundle in one listing needs its own.
- It must meet Play's Wear OS app-quality requirements: watch screenshots and a tile that works
  on round screens and at large font scales. The watch honors the system font size; StopDash's
  own text-size factor stays a phone setting unless the maintainer wants it synced.
- **Offered from the phone** (maintainer, 2026-10-02): a one-time near-me card and a Settings row
  open the Play page on a connected watch without the app (SPEC *On the watch*, *Getting the
  watch app*). The phone tells which watches lack it from the `stopdash_watch` capability the
  watch app declares, against the connected nodes, and opens the page with `RemoteActivityHelper`.
- **Minimum version:** Wear OS 5, the Android 14 / API 34 base that matches the fleet floor. To
  be confirmed against the tile and complication APIs this plan relies on.

## Suggested order

1. **Done.** The package rename to `app.stopdash`, a prerequisite for releasing the watch app.
2. **Done.** Extract `:domain` into its own module, and move the route topology asset and
   loader into the shared Android library module (refactor only).

   Disruptions on the watch waited on the widget's age-stamped status item in `TODO.md`; both
   have landed. The widget's
   refresh-failure / incompleteness item, by contrast, is a **hard prerequisite of step 4**,
   because the watch mustn't present an incomplete refresh as complete.
3. **Done.** Move the snapshot format into shared code, versioned and carrying the starred-row
   keys, with the round-trip and no-location tests.
4. **Done** (not released). Add the `:wear` skeleton and the phone-side publish over the Data
   Layer. Gate it on the watch app being installed on a paired watch, **connected or not**. The
   same PR includes:
   - the republish on reconnect;
   - the watch paragraph in SPEC *Privacy* and `docs/PRIVACY.md` (the sync may pass through
     Google's servers);
   - the Data Safety determination.
5. **Done** (not released). The tile, with the staleness timeline. Its fresh-state **All stops**
   button came with step 8.
6. **Done** (not released). Watch-initiated refresh.
7. **Done** (not released): the complication, and its row picker with the selection synced back
   to the phone (and the disclosure that sync needs).
8. **Done** (not released). The small watch app, which adds the tile's **All stops** button,
   with its foreground ticker and its screenshots (`WatchHomeScreenshotTest`, already in CI's
   allow-list).
9. Play: the Wear OS track, screenshots, app-quality review, and filing the Data Safety answers
   decided in step 4. **The repo side is done** (gate lifted, the watch bundle built, signed and
   uploaded by CI); the Play Console side is the maintainer's
   (`dev-docs/play-store-internal-track.md`, *The Wear OS app*).

Each step is its own PR with its own tests. Steps 2 and 3 are useful even if the watch work
stops there.

## Decided

- **Companion app for the first version** (maintainer, 2026-09-24): see *Option A*. A
  standalone watch stays open for later.
- **The watch shows the widget's stops** (maintainer, 2026-09-24): the same set the widget
  renders (watched stops, D1, and until Phase 2 the widget's interim nearby set). There's no
  separate watch-only choice and no watch setting to keep in step. The envelope is built from
  the widget's scope, and changing which stops the widget shows changes the watch too.
- **Layout: same stops, favorites first, scrollable** (maintainer, 2026-09-24). Starred rows
  lead on every surface, as they're pinned on the phone (D8). The watch app scrolls through
  everything in the envelope, and the tile shows the first rows with a way into the app. The
  maintainer has round-watch mocks of the tile, the complications, the stale tile and the app,
  at the top and scrolled.
- **The Data Layer may relay through Google's servers**, with a disclosure (maintainer,
  2026-09-24): see *Privacy and Play Data Safety*.

## Open questions for the maintainer

- **Starred journeys on the watch:** only the widget's rows, or journey cards too?
- **Crash reports on the watch:** does the phone's *Help make StopDash better* opt-in cover the
  watch app, or does the watch ask separately?
- **Tile-only first release?** Shipping the tile (steps 1–6) before the complication and app
  would get the main glance surface out sooner.
