# TODO

Phased plan toward the product in `SPEC.md`. Each phase lands as its own PR (or a small
stack), fully unit-tested, with `./gradlew test` and `./gradlew lint` green. Check items
off as they land; add newly discovered work to the right phase.

The ordering follows the maintainer's call that the **in-app view is the first
deliverable** (SPEC intro): the app screen is testable without the lock-screen host and
exercises the whole spine the widget later renders from.

## Phase 0 — Project scaffolding

- [x] Gradle build: single `:app` module, Compose, DataStore, `mikelward/androidlog`
      as a resolved dependency (mirrors simmo's `settings.gradle.kts` /
      `libs.versions.toml`). kotlinx.serialization lands with the TfL models in Phase 1.
- [x] `minSdk 34`, `targetSdk 36` / `compileSdk 37` per the fleet, versionCode from the
      git commit count.
- [x] `.claude/hooks/session-start.sh` to provision the Android SDK on web sessions.
- [x] CI: the fleet `ci.yml` runs a `build` job (`./gradlew test` + `lint` + debug APK)
      behind the `lanes` classify/gate, on PRs and `main`.
- [x] Shared checks wired: `lanes` (`.github/lanes.conf`), `codex` (the
      `mikelward/codex-review` workflows), `zizmor` — added by the fleet CI scaffold (#3).
- [x] Green `./gradlew test` and `./gradlew lint` (the aggregate tasks AGENTS.md
      requires — CI runs these, not the debug-only variants).

### Phase 0 — remaining (follow-up PRs)

- [x] Trip page: work out its per-tick values off the main thread too. Each tick's estimates, cards,
      open route, what frames them, and each card's alerts, notices and times are one frame worked out on
      the page's worker (`tripFrame`), drawn against its own state and time.
  - [ ] **The rest of the trip page off the main thread**: the open route's leg rows (`RideLeg` →
        `rideLegRows`, their headways), a line page's rows (`legRows` for `detailRow`), and the
        once-per-refresh chain (`onPoles`, `placedStands`/`shownRoutes`,
        `withThroughRoutes`, `plannedLegs`, `rideLines`) still run in composition. So does
        `TripViewModel`'s own refresh on `viewModelScope`: the setup before its first request
        (`timedRoutes`, `lookUpPoles`, `stopsOf`, `rideLineIds`, `closureStops`) and the merging of
        what comes back (`sharedClosures` among it; the verdict checks that move `failures` are on `io`
        already), and the callbacks the page's effects make into it as routes and estimates
        change (`boardAt`, `noteWithheld`, `checkShownStops`).
- [x] The near-me list's refresh off the main thread, before its requests: `refresh`'s own setup
      (`RefreshSetup`: the prior stops by id, `recentlyFetched`, both id sets, the stops asked),
      `fetchBatch`'s per-stop plan (`BatchPlan`: the National Rail board each stop asks for, which may be
      carried over, the pole batches, the lines declared; the caches each stop is launched on are read as
      it's launched, as they can move on meanwhile) and `checkJourneyDestinations`' choice of which to
      ask (#619);
      the widget's snapshot (`forWidget`, `widgetLineChecks`), worked out with the list in one pass so no
      step comes between the list shown and the refresh's settling of its checks; the widget's journeys
      write (`setWidgetJourneys`/`writeWidgetJourneys`: compared with the last written, and its origins
      gathered); and the saved snapshot's restore, on startup or a refresh that races it
      (`restoredLoaded`). The line-status caches the widget reads are one concurrent map, one answer per
      line (`heldLines`). The per-stop merge and the list build moved in #534; the dismissal pass is
      #532's; the screen's journey-stop and destination reports (`setJourneyStops`,
      `setJourneyDestinations`) are worked out on the worker, applied in order; so are a destination
      check's cards.
- [ ] `fetchBatch`'s launch loop off the main thread: it still runs on the caller's thread, one request
      per stop, each with its constant-time cache reads as it's launched (the shared arrivals, the kept
      closure, `closureStillShown`). Launching from a serial view of `compute` instead needs everything
      the loop starts safe there first: the early line check (`checkLines`) writes main-thread state
      (`lineStatusMarks`, `unknownLineIds`), and the batch's bookkeeping (`arrivalOf`, `poleFromCache`,
      the hub lookups) assumes one thread (Codex, #619).
- [ ] **A stop's closure as the list publishes it, not as it was read** (Codex, #619; the design is the
      maintainer's call). A near-me refresh reads each stop's closure from the shared cache once: as the
      stop is launched (a carry-over's `closureStillShown`, a cached lookup) or as its own lookup settles.
      It publishes after the batch's other work (the other stops, the line check), so a newer lookup
      another screen finishes in between, a closure found or a check failed, shows only at the next
      refresh, which `closureStillShown` makes ask again. `fetchBatch` has always worked this way: it
      decided a carry-over before its awaits. Two ways to close it: reconcile every stop with the cache's
      current lookup at the merge, which narrows the gap to the hop back to the main thread; or have the
      list follow the closure cache and re-merge a stop whose shown lookup is superseded, which closes it.
      The near-me places a refresh merges (`Terminating.nearer`) have the same shape: worked out from the
      distances the batch began with, so a `remeasure` that publishes while the batch is out is
      overwritten by the refresh's older places until the next fix or refresh (Codex, #619). Whichever
      design is chosen should cover both.
- [x] The trip tracker off the main thread (#536): `restore`, `start`, `goTo`, `end` and `refresh`
      each hop to `compute` before taking the tracker's lock, so a refresh's step, a tap's move, a
      start and a restore walk the trip's route on the worker.
- [x] Nearby stops chosen off the main thread: #537 moved `resolveFrom`'s work after its lookup to
      the worker, and made `State.Ready`'s `eagerStops`, `nearbyStops`, `clusterSetKey` and the
      widget's `eagerStopIds` values worked out with the set; the trip origins worked out from it
      (`rememberHereOrigin`) followed onto the worker. A relocation that keeps the same cluster set
      (`MainViewModel.reconcile`, with `reconcileSameSet`'s journey checks) is worked out on the
      worker too, and applied, prune first, before its refetch.
- [ ] The smaller per-tick and per-recomposition passes: `MainActivity`'s farther
      cards built (`fartherCards`, `fartherDistanceMeters`) and the farther stations
      reached (`reachedStopIds`, `fartherReached`, and `FartherBuses.stationStops`/`candidates`
      before their `produceState` hops), all in composition. The search and lookup results are worked
      over on the worker after their requests (`StationSearchViewModel`'s ranking and merging,
      `StationStopsViewModel.retry`'s centered stops, `FavoritePlacesViewModel`'s picked center and
      bundled positions). The DataStore stores (starred rows and journeys, dismissed alerts, alerts
      behind, favorite places) now read and map on the worker (#538). Not a closed list: sweep the screens, the stores, and every view-model
      function a click handler or effect calls, for any pass over a collection that grows with its
      input on the main thread before checking it off. Known still there: `refresh`'s nearby places
      (`nearbyPlacesOf`), and `setJourneyStops`' comparisons. (`FartherCardsViewModel`'s `retain`
      and `open` after its lookup, and the `remeasure` `retain` calls on each opened card's model, now
      work on the worker; the licenses dialog now opens the tapped
      library as it is, and finds a restored one again on the worker; the main screen's
      `emptyStateUncertain` ages its stops on the worker each tick; the route page's saved journeys here,
      lift check and step-free levels are worked out on the worker; so are `MainActivity`'s saved
      journeys oriented and measured, `rememberShownJourneys`.)
- [x] Clear the `WorkerThreadCall` lint baseline (`app/lint-baseline.xml`): every screen now works out
      what its composition used to with the data it comes from, on the worker (the route page's
      `routeDetailWork`, a trip's open ride `rideLegView` and card notices `TripCardView.rideClosures`,
      On the way's board, every stop card's `stopCard`, the main list's and platform view's `ListCards`,
      journey cards' `JourneyCardState.Trains.cards`). The baseline is empty, so any new call fails lint.
- [x] Prune the saved dismissals off the main thread: the list, the trip and the line checks settle
      what they let go of on the worker and drop it from the set atomically (#519), and the store
      lets go of only the dismissals the check saw, so one made after it (of a notice a newer
      refresh found back) is neither pruned in memory nor written away.
  - [x] The widget's own line check (`reconcileWidgetDismissals`) reads the dismissal count before
        its requests, so a line alert dismissed in the app while it runs isn't written away.
  - [x] Each place and line settles on its own answer's dismissal count, so a reused closure or line
        answer no longer keeps the fresh verdicts elsewhere in the same check from letting go.
  - [x] A tap is counted and added to the screen's set in one step, and a check's in-memory prune
        takes the same lock, so a newer check's prune is never undone by the tap's add.
  - [ ] A failed dismiss tap isn't rolled back while the stored set holds the alert, but an older
        refresh settling meanwhile may have kept that stored record only because the tap was in flight.
        The alert then stays hidden until the next refresh lets it go (a minute or so). It needs a disk
        write failure during that settle; tracking why each record was kept would close it.
- [ ] A check canceled after its closure answer lands but before it settles (the trip left, a refresh
      superseded) leaves that newer answer unsettled, and older checks skip the stop for it
      (`StopClosureCache.settling`), so a cleared closure's dismissal stays until the next check of
      that stop, which reuses the answer and settles it. Tracking which check still owes each answer a
      settle (released when it settles or is canceled) would let an older check settle in its place.
- [ ] A surface built from a closure lookup that another screen's newer one overtakes before it's
      shown keeps the earlier answer until its own next check: a destination card (the newer lookup
      failed, say), or the on-the-way check's signals and notes once its settling has waited on a newer
      request (`StopClosureCache.settling` already tells it which answers are no longer the newest).
      Dismissals already settle by each stop's newest answer; the surfaces could read the cache's
      newest the same way as they're published.
- [ ] Finish marking `@WorkerThread`: the first sweep marked the route, alert, journey and board-wide
      work; smaller loops the UI still calls in composition (a row's `Countdown.entries`, and the like)
      aren't marked yet, so `WorkerThreadCall` can't see them. Mark each as its screen moves off the
      main thread, its calls joining the baseline until then.
- [ ] Sharpen `WorkerThreadCall` where it still guesses: a helper's default argument doing the work
      (`fun rows(v = RouteStops.resolve(…))`) isn't followed, and a local function or stored lambda is
      matched to its calls by name, so a shadowing local of the same name is attributed to it. A
      helper returning a function reference (`deferred()(stops)`) isn't followed, nor a composable
      lambda marked only on its literal (`val Stops = @Composable { … }`). A property read isn't
      followed into its getter (`show(model.rows)` whose `get()` reaches marked work, or a
      `@get:WorkerThread` property); no getter in the app reaches marked work yet.
      Static analysis can't be complete; fix each when one bites or a review shows it's cheap.
- [ ] Trip page: a cut pill's card settles into its stop column a moment after it first draws.
      The column's width needs the cut pills measured, which runs on the worker (AGENTS.md *Main
      thread*), so until it answers a row stands out by the cut pill's extra width, as a card's times
      read "Loading". If it shows on a device, measure the pills with the plan, before the cards reach
      the screen.

- [ ] Screenshot job — record + upload landed; **drift-refresh + visual-diff apparatus
      wired**, awaiting one operator step. The `screenshot-tests` job now checks out the PR
      head branch, enforces the `--tests` allow-list against every `*ScreenshotTest`, clears
      then records, and fails on drift on pushes/forks; `sync-screenshots` (the
      `mikelward/ci-commit-artifact@main` reusable workflow) pushes the refreshed PNGs back
      to a same-repo PR branch; `post-screenshot-diff` posts the before/after PR comment.
      **Remaining: `repo setup` must be re-run after this lands on main** — it only
      provisions `CI_COMMIT_ARTIFACT_TOKEN` (in a `ci-commit-artifact` environment) once a
      default-branch workflow actually calls the reusable workflow, so on the PR that adds
      this `sync-screenshots` fails for lack of the token (deliberately kept out of the
      required `lanes` gate so it doesn't block the merge). Also note: the first main push
      after merge may show screenshot drift red on `lanes` if the committed baselines differ
      from the CI render — the next UI PR's `sync-screenshots` commits the CI-accurate set
      and self-heals it once the token is in place.
- [ ] Weekly dependency update — **workflow landed** (`gradle-update.yml` calling the shared
      `mikelward/gradle-update@main`, plus `scripts/check-license-inventory.mjs` + tests and
      its CI wiring), matching the sibling fleet. **Human setup still owed before the batch
      can open a PR**: add `GRADLE_UPDATE_PAT` (or the GitHub App credential pair) to a
      `gradle-update` environment in this repo's settings — an environment secret reaches the
      called workflow no other way. Until then the scheduled/manual run executes but opens no
      PR. Verify with a `workflow_dispatch` run once the secret is in place.
- [x] Deploy job (Play internal track, release notes from commit subjects) — **pipeline
      landed**: `release-apk` (PR-lane R8 smoke test), `release-build` (signed AAB) and
      `deploy` (GitHub prerelease + Play internal-track upload, notes built from commit
      subjects) in `ci.yml`, plus `scripts/publish-github-release.sh` and its two PR-run
      tests, `workflow_dispatch` deploy-force, and `dev-docs/play-store-internal-track.md`. No
      Firebase (dropped every google-services/Crashlytics step). **Human setup still owed
      before a build actually ships** (all in `dev-docs/play-store-internal-track.md`): generate
      the upload keystore; create the `app.stopdash` app on Play Console and seed the internal
      track with one manual upload; create the Play service account; add the five secrets
      (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_PASSWORD`,
      `RELEASE_KEY_ALIAS`, `PLAY_SERVICE_ACCOUNT_JSON`) to a `production` environment
      restricted to `main`; and complete the Play Data Safety / App content form. End-to-end
      proof is the Phase 5 item.
- [x] `AboutLibraries` licenses export + Licenses screen scaffolding. The plugin exports
      the transitive dependency graph to `res/raw/aboutlibraries.json` (committed;
      regenerated with `./gradlew :app:exportBundledLicenses`, since AGP 9 can't wire the
      resource at build time), and a `LicensesScreen` renders it — reached from an About
      dialog behind the top-bar overflow menu. Roborazzi-covered.

## Phase 1 — In-app departures view (first deliverable)

- [x] TfL Arrivals client behind a domain interface (`TflClient`):
      `/StopPoint/{id}/Arrivals`, kotlinx.serialization DTOs mapped to `Departure`,
      Ktor + OkHttp, recorded-fixture (`MockEngine`) tests. (Nearby `/StopPoint` lookup
      lands with Phase 2's "near me now".)
- [x] Domain (pure Kotlin, JVM-tested): arrival→countdown formatting ("0 min"/"3 min"),
      soonest-first ordering, expired-prediction drop (a countdown reaching zero leaves
      the list, never sticks at "0 min"), a single shared staleness threshold, nearest-stop
      ranking. (`Departure`, `Countdown`, `Staleness`, `NearestStops` + JVM tests.)
- [x] Retain TfL's `direction` (inbound/outbound) on `TflArrivalDto` → `Departure` as the
      primary grouping key — it can't be reconstructed from destination/platform in general
      (a branch shares a direction) — then group departures into **(service, stop,
      direction) rows**: a pure domain grouping, soonest-first within each row, JVM-tested
      (D8). When TfL omits `direction`, the key falls back to platform then destination as
      a best-effort discriminator, so opposite directions stay apart whenever TfL gives any
      of those — a prediction with none of the three has nothing to key on and shares one
      "unknown" row.
- [x] `MainScreen`: a **flat list of compact cards, one per (service, stop, direction)**
      (D8) over a seed set this phase — line pill + destination, and the service's next few
      countdowns **merged onto one line** ("0 · 3 · 6 min"), with the "updated N ago"
      stamp and client-side countdown recompute. Ordered location-free (soonest-first).
      (The stop name is not on the card for now — see the row-merge item below.)
      **Star-to-pin lands in Phase 2 with its
      persistence** — not here — so a star always survives restart rather than resetting
      (a half-persisted control loses the user's ordering). The compact swipe-card model
      is a later candidate too (SPEC D8). Landed with `MainViewModel` (fetches the seed
      off the main thread, maps failures to a typed `TflException` → honest offline /
      rate-limited / can't-reach-TfL states), `DepartureRows.across` for the merged
      soonest-first list, and `MainScreenScreenshotTest` (loaded light/dark, empty,
      offline).
    - [x] **Row-merge card redesign** (this PR): the per-departure rows became one compact
      card per service — merged countdowns via `Countdown.mergedLabel`, destination as the
      elided headline (no "inbound/outbound" word). **Platform and (for now) the stop name
      are dropped from the card** — the stop read as clutter in the compact layout and is
      implied by the widget's chosen context; it returns with multi-stop watching (Phase 2).
      Platform stays in the domain model for a later detail surface. A branching direction
      merges only the headline destination's times and keeps each divergent destination on
      its own line, so no countdown sits under the wrong one (D8). Near-uniform card height;
      only a disruption chip or a branch adds a line.
- [x] **Minimal disruption marking** — the honesty floor the first view can't ship
      without (SPEC principle 1 / D3); the *full* disruption experience is Phase 3.
      Landed incrementally across PR #12 (line-status marking + partial-failure handling),
      PR #14 (zero-prediction status rows), and PR #15 (stop closures). Each bullet below
      records its PR.
  - `/Line/{ids}/Status` for the shown stops' lines: **[landed, PR #12]** mark a departure
    whose line is disrupted (a chip carrying TfL's status wording); a good-service line is
    left unmarked. **[landed, PR #14]** show a line's status even when it has zero
    predictions (a suspended line often returns none) as a direction-independent status row,
    from the stop's declared lines (seeded per stop on `StopRef`/`StopArrivals`, carried
    independently of the predictions; Phase 2's watched stops replace the seed). The status
    lookup now covers declared lines too, and `DepartureRows.across` synthesizes a
    status-only row (empty `upcoming`, sorted first) for a disrupted declared line with no
    prediction rows (SPEC *Departures* / *Disruptions*).
  - `/StopPoint/{id}/Disruption` for the watched stops: **[landed, PR #15]** a stop's own
    disruption surfaces as a stop-level status row (sorted above the line-status and timed
    rows) even when its lines' status is normal, so a closed stop isn't shown with
    valid-looking departures. StopDash **marks** (keeps the departures, adds the row) rather
    than suppresses — TfL's closure data is coarse and often absent, so hiding departures on
    it would risk dropping valid ones; and it surfaces **any** stop disruption rather than
    classifying closures (that's Phase 3). A closed stop with zero predictions still surfaces
    the row (SPEC *Departures*), since the row is built from the stop's disruption, not a
    departure.
  - Handle a **partial refresh** (arrivals succeed, disruption lookup fails): **[landed,
    PR #12]** the line-status lookup failing flags the shown departures "status unknown"
    (a banner) rather than presenting them as verified-clean (SPEC *Disruptions*). Keeping
    the *aged last-good* disruption state instead rides with the persisted snapshot (a
    later Phase 1 item — there's no persisted last-good to fall back to yet).
- [x] **`docs/PRIVACY.md` describing the debug log's contents** — moved up from Phase 5:
      Phase 1 introduces the logger and Phase 0 the deploy pipeline, so a build carrying
      the log can reach testers now, and AGENTS.md requires the disclosure to exist before
      the log ships. Landed: `docs/PRIVACY.md` is the source of truth for what leaves the
      device (the TfL requests the product needs, the optional user `app_key` → TfL if set,
      and the platform backup/transfer channel that carries persisted config including the
      key — no off-device channel stopdash adds beyond TfL but the opt-in crash reports and
      usage stats), what the on-device log carries
      (coarse stop/line IDs, HTTP status, location fix outcomes — never a coordinate or
      key), and that a shareable export redacts travel data. See the doc for the precise,
      canonical wording — this line is a pointer, not a second inventory to keep in sync.
  - [x] **Adopt the shared logger (`mikelward/androidlog`) with an on-device persisted file
        sink.** `StopdashDebugLog` (the shared `DebugLog` buffer) is registered in a new
        `StopdashApp` with the library `LogcatSink` and `DebugFileSink` (a rotating file in
        `cacheDir`, chained crash handler — the seam Crashlytics hangs off later). The
        location / departures / stars / update / settings / widget warning seams now route
        through it, so every warning is both in Logcat and persisted on-device
        (`docs/PRIVACY.md`). Built on-device only; its one off-device sink since is the opt-in
        Crashlytics one below, fed only redacted lines. Logcat tags consolidated to
        one `StopDash` tag with an area prefix (e.g. `location: …`).
  - [ ] **Wire the shared logger into the `DataStoreWatchedStopsStore` corruption handler**
        once that store is actually constructed (it has no construction site yet, so there is
        nothing to wire — its `warn` defaults to a no-op). `DataStoreSnapshotStore`'s handler
        already routes to the shared logger via `logWidgetSnapshotWarning`. The watched-set
        discard is the higher-stakes one — it loses the user's own config (though the set also
        rides Android backup). SPEC principle 2 / *never fail silently*; Codex P2 on PR #26.
  - [x] **Crashlytics — off-device crash + breadcrumb reporting** (requested 2026-09-21; built
        2026-09-24). A Crashlytics `Destination.OFF_DEVICE` sink on `StopdashDebugLog` (androidlog
        redacts every argument not marked `safe(...)`), logged exceptions as non-fatals, and
        Firebase Analytics, all behind the **Help make StopDash better** opt-in (off by default,
        Settings). Inert without `google-services.json`; debug builds never collect. SPEC
        *Privacy*, `docs/PRIVACY.md`, `dev-docs/firebase.md`.
    - [ ] **Human setup before it collects anything** (`dev-docs/firebase.md`): after the package
          rename, a Firebase project with the final application ID registered, its
          `google-services.json` as the `GOOGLE_SERVICES_JSON` secret in the `production`
          environment, and the Play Data Safety answers updated.
    - [x] **Usage analytics events** (maintainer, 2026-09-24): custom events carrying categories
          and bucketed counts only, never a stop, line, journey or coordinate — each tap by kind
          (journey card, stop row, change card, swap, star/unstar, search, settings), the "More
          stops" (by mode) and "Faraway favorites" reveals, location permission (precise /
          approximate / denied), fix outcome (fresh / last-known / failed), fix accuracy and
          time-to-fix in bands, and nearby stops per mode bucketed (0 / 1 / 2–3 / 4+). *Done
          (`UsageEvent`, `UsageEvents`): "More stops" is now opening a farther place, by mode
          group; search is opening From… or To… (it runs as you type, with no submit).*
    - [x] **Check the stored opt-in before Firebase starts**, not after: Firebase's init provider
          starts the SDKs from their own persisted flags before `Application.onCreate`, while our
          consent load runs afterwards, off the main thread. Today that's safe by ordering (an SDK
          flag is switched on only after a stored yes, and off before a stored no), so an early
          upload rides the last recorded consent; it would not survive our consent prefs being
          lost or corrupted on their own. *Done without moving Firebase's start (maintainer,
          2026-10-01: an opted-in start reports its crashes, at no cost to startup): the SDKs keep
          starting from their own flag, and a stored choice lost on its own is read back from two marks
          kept apart from it (`FileConsentMark`: a no, set first on a withdrawal and outranking it; a
          yes, set once an opt-in is stored), never from the SDKs' own flags. Left as it was: a no
          whose SDK switch-off threw or was cut short still collects at the next start until the load
          switches it off, as before; and one whose no mark can't be created or moved onto, and whose no can't
          be stored, is kept only once the SDKs are off: a kill before then keeps the yes it withdrew.*
    - [x] **Lines logged before the stored opt-in loads never reach Crashlytics** (Codex on
          #407). `CrashlyticsLogSink` drops a line while consent reads "not opted in", and the
          sink isn't even registered until `installTelemetry`, so for an opted-in user the first
          moments of every start (the settings warm-up's warnings, the process-exit record) are
          missing from that run's crash-report breadcrumbs. DebugLog doesn't replay to a sink
          added late. The fix belongs in the sink rather than per caller: hold what arrives before
          the choice is known in a small bounded queue, then send it once the load says yes or
          drop it on a no. Low stakes today: Crashlytics reads Android's exit records itself for
          its own ANR reports, the on-device log carries every line, and the bug report carries
          the pinned process-exit record and as many of the newest lines as fit. *Done: the
          sink is registered straight after the log is installed, holds up to 64 lines while the
          choice is unknown (the newest, with a count of any dropped), and settles as soon as the
          choice loads, on the next line, or before a fatal crash's drain.*
    - [ ] **A pending opt-in withdrawn while storage refuses every change** can come back on the
          next start: an opt-out that can't delete the pending marker, write "off", or delete the
          stored choice leaves the disk exactly as it was before the tap, so the next start reads
          the old "pending yes". No layout avoids that once nothing can be written or deleted; the
          SDKs are off, the switch shows off and each failure is logged. If it ever bites, the
          narrower step is to hold a pending opt-in in the SDKs' own state (Analytics on,
          Crashlytics off) so a withdrawal also changes a third, SDK-managed file.
    - [x] **Invite the opt-in once on the home screen**, as simmo does, since stopdash has no
          onboarding to ask it in: a card atop the near-me list for an install with no stored
          answer (`TelemetryConsent.unanswered`, `TelemetryInviteCard`), "Yes please" / "No
          thanks" each storing one; off stays the default.
  - [x] **Log recent process-exit reasons at startup** into the shared log
        (`ActivityManager.getHistoricalProcessExitReasons`), as the siblings do
        (`ProcessExitReasons`), so a silent kill or native crash leaves a coarse cause in the
        next run's diagnostics. Coarse reason / importance / status — never the platform's
        free-text description. *Done through androidlog's shared `ProcessExits` (2.3.72), off the
        main thread at startup: the last five exits and the package's install/update times, as
        pinned lines. The bug report reads the log with `boundedSnapshot`, so pinned lines a busy
        run pushed out of the ring still reach it, ahead of the ring. Since androidlog 3.1.74, after
        an ANR it also pins where the main thread was stuck: up to 14 frames (class, method, file,
        line; never a value) read from the platform's trace, on the device's copy and the
        consent-gated report, with a placeholder for Crashlytics. Alongside it, StrictMode notes
        the main thread's disk reads and writes (`MainThreadViolations`).*
- [x] **Persist the last-good snapshot; show a stamped placeholder at once and fill it
      in when the async read completes** (SPEC snapshot-render — never block the first
      frame on the DataStore read; the intro says the first deliverable exercises
      persistence). This is what gives the offline state something to show after process
      death — the two belong together, so snapshot storage lands here, not in Phase 2.
      **The in-memory per-stop snapshot model landed in PR #16 (below); what remained was
      persisting it to DataStore and the stamped-placeholder first frame.**
    - **[landed, this PR] `SnapshotStore` over DataStore + kotlinx.serialization.** A
      `DeparturesSnapshot` domain type (the honest last-good: the stops at their per-stop
      ages, no transient cycle flags), a `SnapshotStore` seam, and a DataStore-backed
      implementation serializing a `data`-layer `PersistedSnapshot` DTO as JSON (Instants as
      epoch millis; a `version` field discards a forward-incompatible format rather than
      mis-reading it; corrupt/empty bytes read as "no last-good"). `MainViewModel` restores
      it on init — the first frame is the Loading placeholder, the async read fills in the
      aged snapshot — and the restored snapshot becomes the **prior the first refresh merges
      into**, so a stop that then fails to refresh keeps its aged rows. Each successful
      refresh persists the new last-good; an empty/error result never clobbers a good saved
      one. The DataStore is a process singleton (one instance per file, so the widget can
      share it). The store opens no off-device channel of its own (SPEC *Privacy*): a private
      file that rides Android backup / device-to-device transfer like the rest of the app's
      data (SPEC §12), a platform path the user controls. **This is the store the Glance
      widget reads next.**
  - **[landed, PR #16] Per-stop last-good with honest per-stop ages.** MainScreen's
    snapshot was one whole-list `Loaded(stops, fetchedAt)` replaced wholesale each fetch,
    so a partial refresh dropped the failed stop's rows entirely and one screen-wide flag
    withheld every stop's countdowns when the snapshot aged. Each `StopArrivals` now
    carries its own `fetchedAt`; a refresh merges into the prior snapshot (update the
    stops that succeeded, keep the ones that failed at their older age, via the pure
    `Snapshot.mergeStop`); and staleness/withhold is per row from each stop's age. This
    was the design the repeated MainScreen review findings pointed at (deferred from
    PR #10, Codex P1 on `3c4befb`); it deletes the drop-on-partial-refresh class rather
    than patching it.
    - **[landed, PR #16] Also decouple a stop's disruption fetch from its arrivals**
      (deferred from PR #15, Codex P2 on `ba72fa1`): a stop's `/StopPoint/{id}/Disruption`
      is now fetched independently of its arrivals, so a stop whose arrivals fail still
      surfaces its available closure (a stop-status row) rather than dropping out — the
      same drop-on-partial-refresh class by a different path, now closed.
- [ ] Offline / rate-limited / error states rendered honestly (SPEC principles 1–2),
      backed by the persisted snapshot above.
- [ ] Unit tests for the domain; Robolectric + Roborazzi screenshot tests for the
      screen and its empty/offline/disrupted states, wired into the CI allow-list.

- [x] An opened farther-station card that failed marks the list partial even when every one of
      its stops is already shown fresh by the list (the merge keeps the list's copy), so "Some
      stops couldn't be refreshed" can show with every row fresh. Predates the named banner
      (Codex P2, PR #217); gate the card's partial flag on a failed row that survives the merge.
      *Landed: a failed or incomplete card marks the list partial only for a stop of its own
      shown from an older copy, or shown by nothing (`withOpenedFarther`).*

### Phase 1 — corrections to shipped departure-label rendering (follow-up)

Corrections to the departure label the card already ships (the via-branch and the
tight-width abbreviation); the Phase 4 widget mirrors the same shared rendering, so each
fix lands in the shared layer, not per-surface. Raised in chat 2026-09-19.

- [x] **Only show the via-branch where the trunk is a choice the rider makes here.** Two
      trains to one terminus by different trunks now merge into one line (branch label dropped)
      only past the junction, on the single shared track (Highgate → High Barnet), and stay
      split (each labeled) wherever the trunks are still distinct — a trunk-only stop ahead
      (High Barnet → Morden, Euston → High Barnet's Mornington Crescent edge) **and at the
      junction/trunk stops themselves** (Camden Town, Euston, Kennington), where a Bank train
      and a Charing Cross train reach the stop by different approaches/platforms and the rider
      still picks one (maintainer, 2026-09-20: "coming from Camden or Euston I need to know
      which branch it takes"). Landed in `RouteTopology` (`grouping()`), used by
      `DepartureRows.destinationLines` (the shared grouping the card and widget both render, so
      they can't diverge), from a bundled `route_topology.json` (regenerated from
      `/Line/{id}/Route/Sequence` for northern / central / piccadilly). Test is an
      **approach-inclusive path comparison**: from the stop *one before* this one through to the
      terminus, equal stop-sets on both trunks ⇒ merge, different ⇒ keep. Including the approach
      stop is what keeps the branch at the junction (trunks reach it by different approaches)
      while merging once past it. Resolution requires an **exact branch match**: a train whose
      branch names no serving pattern keeps TfL's raw label — Battersea Power Station, tagged
      "via Charing Cross" against an unlabeled pattern, keeps "(Charing X)" (maintainer,
      2026-09-20: "battersea keeps Charing X is not only fine, it's better"). **Reverses the
      earlier "start cheap, no topology" note** (maintainer, 2026-09-19) after re-deciding for
      the topology approach (2026-09-20): the ≥2-distinct-branches-in-the-feed heuristic
      couldn't tell a no-choice single-branch stop (Bank → Morden) from a real choice, nor merge
      two same-path trunks. Validated against live TfL data. Unknown line / uncovered stop or
      terminus falls back to TfL's raw label (no merge), so it never merges wrongly on
      incomplete data.
- [x] **Refresh the bundled route topology at runtime** (follow-up). The asset is static and
      ships with the build, so a TfL branch change (a line extension) needed an app update to
      reach it. Now, once the app starts, each bundled line's current `/Line/{id}/Route/Sequence`
      (through the route stops' day-long cache) is read the way the asset was made
      (`routePatternsOf`) and replaces the bundled patterns where it still runs every bundled
      route, an extension past a terminus included (`withLive`); otherwise the bundled line stands. Validated against live TfL on
      2026-09-30: all 16 bundled patterns of the three lines come back identical. Cost £0; at most
      six requests a day (three lines, both ways), shared with the route pages' cache.
- [x] **The refreshed route topology on the widget-only process.** `RouteTopologyStore.use`
      keeps the lines TfL's current patterns stand in for (those differing from the asset) in the
      cache directory, and `load` puts them back over the asset, checked with `withLive` again, so
      a widget-only process (the app not opened since a restart) and the next start's first frame
      group as the last refresh did, with no request. A line today's refresh can't read keeps what
      the last one took, rather than dropping back to the asset.
- [x] **The refreshed route topology on the watch.** The envelope carries the phone's
      refreshed lines (`routeLines`, those differing from the asset: usually none, so no cost),
      and the watch puts them over its own asset with `withLive` (`RouteTopologyStore.over`)
      wherever it reads the topology (home, tile, complication). Additive: an older watch app
      ignores the field and groups by its own asset.
- [ ] **The watch's route topology when the phone and watch ship different assets** (Codex on
      #433; a design change, so the maintainer's call). The envelope carries only the lines where
      the phone's topology differs from the *phone's* asset, so a watch on another build whose asset
      differs keeps its own for the lines the phone's matches, and groups them unlike the widget.
      Rare — it needs a regenerated asset and the two apps on different versions — and older than the
      refresh, which only narrowed it. Proposed: the phone's whole topology in use (~9 KB) as its own
      Data Layer item, put on each publish but synced only when it changes (unchanged bytes don't
      sync), kept by the watch and grouped by as the widget's; a topology change would then be a
      sync of its own rather than an envelope cue. The other option, the whole topology in every
      envelope, costs ~9 KB on each publish (every refresh while the app is open).
- [ ] **Branch truncation follow-up: a fuller branch form under pressure** (maintainer,
      2026-09-19). **Superseded design (2026-09-22):** the original ask below — abbreviate across
      both halves, keep one full word in each — was replaced, ultimately by the floor-and-ellipsis
      ladder (PR #127; see the note at the end of this item), so what stays open is only the
      fuller-branch-form idea, not the parens/per-half rungs. The real-width device check is
      **done** — the maintainer confirmed the shipped result on device and is happy with it
      (2026-09-22). Kept for the rationale. The original ask: rendering
      "Destination (Branch)" at decreasing width, don't
      spend the space keeping one half fully spelled while gutting the other; instead
      abbreviate words across both and **preserve at least one full (unabbreviated) word per
      half**. So "Battersea (Charing X)" — "Battersea" whole in the destination, "Charing"
      whole in the branch with only "Cross"→"X" — beats both "Battersea Power (CX)" (branch
      gutted) and "B.P.S. (Charing Cross)" (destination gutted). Branch word-replacements are
      per `abbreviateBranch` ("Cross"→"X", "East"→"E.", "North"→"N.", "Central"→"C."); "Charing
      X" is the friendly form and beats "Charing…", so word-abbreviate before any ellipsis, and
      the "CX" initialism (both words gone) is only a last resort "if necessary" when even one
      full word per half won't fit. Word-abbreviate-then-clip shipped (the widget always
      abbreviates since it can't measure), but the one-full-word-per-half allocation and the
      CX/ellipsis last-resort rung were **not** built as written — they were overtaken (a
      proportional equal-truncation split shipped first, then the **floor-and-ellipsis ladder**
      of PR #127 replaced that; see the note at the end of this item), and the device check is
      done (see above). What is left is only the fuller-branch-form idea. (An earlier note said
      "prefer plain ellipsis" — backwards.)
      Names with no mappable word (High Barnet, Walthamstow Central once "Central"→"C." is
      spent, Battersea Power Station) can only ellipsize, keeping the most recognizable word.
      **On-device confirmation (2026-09-19, maintainer screenshot):** the shipped card renders
      "Battersea Power S… (CX)" where it should render "Battersea… (Charing X)", and
      "High Barnet (CX)" where "High Barnet (Charing X)" fits — i.e. it currently keeps the
      full destination and initialises the branch, the exact inversion this item fixes.
      **PR #59:** the branch label is normalized to the short board form (`Bank`,
      `Charing X`) — TfL's inconsistent `Bank` / `Bank Branch` / `CX` / `Charing Cross`
      folded to one spelling per trunk, on fetch and on snapshot restore. The
      width-adaptive shortening above (one full word per half, the `Charing` / `CX` rungs)
      belonged to the parens/per-half model that later designs superseded (see the note at the
      end of this item): the branch is now kept whole and the terminus yields down to its floor
      then a single `…`, so the `CX`-instead-of-a-mid-word-clip rung is moot — nothing clips
      mid-word now.
      **Partly shipped (PR #76):** the *destination* now word-abbreviates before it clips —
      a whole-word map (`DestinationAbbreviations`: `East`→`E.`, `Street`→`St`, …, single
      letters dotted, multi-letter bare) applied only when the full name wouldn't fit, then a
      clean clip (no ellipsis). Note there are now two maps — `abbreviateBranch` (branch) and
      `DestinationAbbreviations` (terminus) — that a fuller version might converge.
      **Superseded by the floor-and-ellipsis ladder (PR #127, 2026-09-22):** the proportional
      equal-truncation split was replaced. The branch is now kept whole and the **terminus yields**
      — full, then word-abbreviated, then its **floor** (first word + initials,
      `DestinationAbbreviations.floor`), then a single clean `…` only below the floor, never a
      mid-glyph cut. Where the whole branch fills the narrowest row the terminus drops and the
      branch stands **alone and bare** (no orphaned slash). The old "CX rung / mid-word clip" concern
      is therefore moot — nothing clips mid-word now. A fuller rider-readable branch form under
      pressure (`via Charing Cross`) is the remaining follow-up here.
- [x] **When the row has literally no room for the terminus, reconsider preferring the terminus
      over the branch** (maintainer, 2026-09-21, PR #92; settled 2026-09-22, revised PR #127). The
      floor-and-ellipsis ladder answers this: the branch is kept whole and the terminus yields
      (word-abbreviated, then its floor, then a single `…`), so it survives as a stub rather than
      vanishing. The branch-alone, bare fallback (no leading slash) is kept only for the genuinely
      degenerate case — not even a terminus stub (a glyph plus its ellipsis) fitting beside the
      whole branch, at a large font scale on the narrowest row.
- [x] **Cluster departures under a per-stop header** (maintainer, 2026-09-20; PR #78).
      Rather than a per-card subtitle, the list clusters by stop, **one header per stop**,
      showing the **bare stop name**, with each stop's own warning leading its block (option
      B, warnings-lead-their-stop). `StopGrouping` + `StopGroupHeader` in `MainScreen`. The
      direction/terminus qualifier was deliberately left out of this step — see below.
- [x] **Group by place, not stop id — merge poles under one header, keyed on TfL's cluster**
      (maintainer, 2026-09-21). Two poles of a bus junction (or a station's platforms) are
      distinct stop ids for one boarding place; grouping by id split them into two identical
      headers. `StopGrouping.groupByStop` now clusters by `clusterId` (`clusterKeyOf`), so a
      junction reads as one place — the way a Tube station's single id already did — with both
      directions' cards under one header. `StopGroup` drops its single `stopId` (a group may
      span poles). The cluster key is TfL's `stationNaptan` from the nearby lookup where it
      gives one, else the cleaned display name — the name alone was an unreliable key (TfL
      spells one station several ways), and TfL's own cluster holds distinct adjacent stations
      (King's Cross St. Pancras vs St Pancras International) apart. Not the final grain design;
      the per-direction subhead (below) rides on top.
  - [x] **Split the header by rail direction — the compass** (maintainer, 2026-09-22). The
        header now groups by `(place, direction)`, one per compass direction, parsed from the
        platform ("King's Cross – Eastbound"). The compass — **not** TfL's `inbound`/`outbound` —
        is the key: verified against live `/StopPoint/940GZZLUKSX/Arrivals` (2026-09-22), TfL tags
        one Eastbound platform `inbound` for the Circle and `outbound` for the Hammersmith & City
        and omits some westbound directions, so keying on inbound/outbound would split a platform's
        trains. `PlatformDirection.of` + `StopGrouping` (a `(place, compass)` group with a
        `directionLabel`), `StopGroup.directionLabel` rendered as `NAME – DIRECTION`; JVM tests
        (`StopGroupingTest`, `PlatformDirectionTest`) and the `main-connected-station` screenshot
        (real King's Cross data, TfL's inconsistent directions preserved). **Rail-first**: a stop
        with no compass in the feed (a bus pole, a bare "Platform 4") falls to the bare name.
  - [x] **Abbreviate the direction to a single letter when the header is too tight** (maintainer,
        2026-09-22; **retired by the two-level header, below** — the direction now has its own row and
        never competes with the name for width, so the letter fallback and `abbreviation` are gone).
        When the full compass word won't fit the header row, it falls back to the
        direction's initial ("– E", loop labels "– IR"/"– OR") rather than clip to an ambiguous
        stub — the four cardinals have distinct initials, so the letter still disambiguates, and it
        stays narrow enough to always fit, so the direction cue never vanishes even at the max font
        scale. `PlatformDirection.abbreviation` + a width-measured fallback in `StopGroupHeader`
        (the same `TextMeasurer` pattern the destination line uses); the letter still announces the
        full word for a screen reader. JVM + logic screenshot tests.
  - [x] **Bus terminus qualifier** (maintainer, 2026-09-22; "try the terminus form first"). A
        compass-less **bus** place now takes "➔ Terminus" when the whole stop heads one way (every
        timed bus row names the same, non-blank destination), else the bare name — the bus analog of
        the rail compass, judged honestly (diverging routes or a blank destination stay bare, SPEC
        principle 1). `StopGrouping.sharedBusTerminus` + `StopGroup.terminusLabel`; the header's
        qualifier build/measure generalized to compass-or-terminus (`headerQualifier` /
        `headerQualifierFit`), with a **name floor** so a long terminus at a large font can't crowd
        the stop name to zero (Codex P2, PR #78). JVM + logic screenshot tests.
  - [x] **Bus letter/bearing split — the bus analog of the rail compass** (maintainer, 2026-09-22:
        "make bus stop letters like rail station compass directions"). A bus place now **splits by
        stop letter** — one header per pole, "King's Cross Station (D)" — the way rail splits by
        compass, so a bus interchange isn't a wall of cards under one bare name. No letter → falls
        back to the pole's **bearing** (a direction word, "Southbound"); neither → the shared terminus
        (above); none → bare.
        Precedence: letter → bearing → terminus → bare. `TflStopPointDto` now parses `stopLetter` +
        the `CompassPoint` property, threaded `StopLocation → StopRef → Snapshot.mergeStop →
        StopArrivals → DepartureRow` (the `clusterId` route). The group cue unified into a
        `StopQualifier` sealed type (compass / bus-letter / bus-bearing / terminus); `StopGrouping`
        splits on it; `headerQualifier` renders each. **Near-me only for now**: a watched bus stop
        refreshes from arrivals (no letter) — captured/persisting the letter on the watched-stop add
        flow is the remaining piece (below). JVM + logic screenshot tests.
  - [x] **Two-level header: place name, then platform/pole sub-header** (maintainer, 2026-09-22:
        "the two level hierarchy… hub or whatever as the top level, then the platform or stop letter
        plus the direction in parens"). The one-level `NAME – DIRECTION` header became **two levels**:
        the **place name once** at the top (`StopPlaceHeader`, with the near-me distance reserved at
        its end), then a **sub-header per group** below (`StopSubHeader`). Rail now **splits on the
        platform**, not the compass — "Platform 2 (Eastbound)" — because the compass alone conflates
        physically distinct platforms (King's Cross Eastbound is the Circle/H&C/Met on one sub-surface
        platform but the Piccadilly on a different deep-tube platform); the compass rides in parens as
        the direction cue. Bus keeps its letter/bearing/terminus chain, now with TfL's `Towards` in
        parens — "Stop D (towards Farringdon)" (trimmed at " Or "). `PlatformDirection.platformNumber`
        + `splitOf` (a `RowSplit` of Platform/Compass/Letter/Bearing/None); `StopQualifier` reshaped
        (Platform/Compass/BusStop/BusBearing/Terminus); `HeaderQualifier.subHeaderText` renders the
        two parts; `towards` threaded `StopLocation → StopRef → Snapshot.mergeStop → StopArrivals →
        DepartureRow → PersistedStop` like `clusterId`. The single-letter direction fallback is
        retired — the two rows never crowd each other (`PlatformDirection.abbreviation` removed).
        JVM + screenshot tests (`main-connected-station` re-recorded, King's Cross sub-surface lines
        share Platform 7).
  - [ ] **Per-platform card, one chip-tagged row per route** (maintainer, 2026-09-22; mocked v9).
        A density redesign built on the platform/pole grouping: **one card per group** (platform or
        stop letter), headed by a **single one-line header** `Place – Platform N` / `Place – Stop X`
        in **title case**, place+qualifier one weight/color, distance dimmed in parens (the two-level
        `StopPlaceHeader` + `StopSubHeader` collapse into it, dropping the compass parenthetical). Inside
        the card, **one row per (line, destination/route)** — line pill left, destination, then the
        merged countdown right; a line's several routes each get their own chip (Battersea, Mill Hill
        East no longer chip-less). Disruption becomes an **inline ⚠ just left of the countdown**,
        retiring the separate "Diversion"/status chip row so each route stays one line. Reuses the
        domain as-is (`StopGrouping`, `DepartureRows.destinationLines`, `Countdown.mergedLabel`,
        `DepartureLabels`, `LinePill`); the change is a UI re-composition of `DepartureList` /
        `DepartureRowCard` / the header composables. Open: per-route star + tap treatment inside a
        multi-row card (keep long-press-to-star and tap-to-detail per row).
  - [ ] **Show step-free / accessibility status** (maintainer, 2026-09-26). Wheelchair icons (and
        similar) on the route detail's stop list and on the stop/station view, so a rider who needs
        step-free access can see which stations have it. A step-free-access outage notice should
        then read against it.
    - [x] **The data** (maintainer, 2026-10-02: "build the data first"). TfL's live StopPoint data
          won't do (`accessibilitySummary` empty; an "access via lift" flag per station, missing at
          Victoria, Warren Street, Highbury & Islington), so the app bundles TfL's open station data
          as `assets/stations/step_free.json` (`scripts/build_step_free.py`, rebuilt weekly with the
          station list): per platform and line, none / to the platform / by ramp / level onto the
          train, read by `StepFreeAccess` (SPEC *Data source → Step-free access*). Grading is per
          platform and line, not per station.
    - [x] **The marks on the route page's stop list** (maintainer, 2026-10-02: TfL's own symbols): a
          white wheelchair on a blue disc for level onto the train, a blue one on a white disc for
          step-free to the platform (a ramp or a step onto the train), nothing otherwise; a station
          whose platforms for the line differ is marked as far as all of them reach.
    - [ ] **The marks elsewhere**: the stop/station view and a trip's legs. Open: what a
          platform-less view (a station header) shows when its lines differ, and whether a trip leg,
          which knows its platform and direction, marks that platform's own level.
    - [x] **Lifts out of service**: TfL's live lift disruptions (`/Disruptions/Lifts/v2`) name lifts
          by the station data's ids, so the table carries each lift-only platform's place in its
          station's cut-down lift map, and the route page walks it again without the lifts that are
          out (SPEC *Step-free access → Lifts out of service*).
      - [ ] **Say why a mark came off**: today it just goes. TfL's notice names the lift and often
            another entrance; a line under the station could carry it.
    - [ ] **Keep each rail row's mark space while its marks are re-worked** (Codex, #555): a shown
          rail drops its marks while a new answer is out (a lift newly out, the row's mode filled in),
          since the last ones may be wrong; a fixed slot per row would keep the names from reflowing.
  - [x] **Tap a route row → all stops for that route** (maintainer, 2026-09-22). Extends the
        route-detail tap to show the route's full stop sequence, not just star + disruption text.
        *Done: the route page lists every station from the boarding stop to where the soonest train
        terminates (SPEC D8 → the route detail page).*
  - [x] **A unified, arrow-free appearance for the bus direction header** (maintainer, 2026-09-22;
        landed). The bus compass now reads as a bare direction word (`Southbound`), like the rail
        compass; `Stop` is reserved for a literal pole letter (`Stop E`); the shared terminus reads
        as an arrow plus the destination (`➔ Bank`). Both direction cases (a `CompassPoint` bearing
        and an arrow-in-`stopLetter`) share the one word path. Intercardinals use `bearingSpoken`'s
        hyphenated `-bound` form.
  - [x] **Find a better arrow glyph for the "-> destination" header, and the journey heading**
        (maintainer, 2026-09-22/23). Rendered the candidates in the header style: the font's `→`/`⟶`
        sit below the letters' center; `➔` (U+2794, from the symbol fallback font) sits centered at a
        matching weight, so both the bus terminus header and `journey_title` use it.
  - [x] **Draw the arrow as an inline Material `ArrowForward` icon** (maintainer, 2026-09-24): the
        labels keep `➔` as a marker character, drawn as the icon (centered, lighter than the glyph) in
        `StopGroupHeader` and `JourneyHeader`; the journey heading is spoken "A to B".
  - [x] **Rethink the bus header cue: what's most informative, matched to the signage** (maintainer,
        2026-09-22; settled 2026-10-03): letter → `Towards` (`➔ Archway`, trimmed at `" Or "`) →
        compass word → bare, on the main list and the trip's board alike; the shared terminus is gone,
        since one bus's destination says where it goes, not where the stop heads.
  - [x] **Split a mixed-platform row into a card per platform** (Codex P1, PR #119). A direction
        that runs from several platforms (Camden Town southbound: Platform 2 or 4) now gets a card
        per platform; platform-less predictions beside two platforms sit under the bare compass.
  - [~] **Show the platform on the widget.** The widget's stop headers now name the platform when
        a merged direction row runs from exactly one ("Oxford Circus – Platform 3"). Still open: a
        direction split across platforms stays one row under the bare compass, where the app shows a
        card per platform; splitting it on the widget costs lines its budget may not have.
  - [x] **Tell same-named stations apart by their lines** (maintainer, 2026-10-03). Landed as
        "keep TfL's line qualifier, shortened": "Hammersmith (H&C Line)" shows as "Hammersmith
        (H&C)" everywhere a station is named, rather than qualifying a name only where it isn't
        unique. The line qualifier also settles the platform-number overlap (both Hammersmiths have a
        Platform 1), so there is no uniqueness check. Names pair across TfL's sources when only
        one side carries the qualifier, and not when both do and they name different lines
        (maintainer, 2026-10-03: "compatible qualifiers"). Where a plain-named station really is a
        different one ("Paddington" beside "Paddington (H&C)"), a qualified name matches exactly
        first, or (a train's own calls) not by a stop already on the ride's route.
  - [ ] **Consider matching stations by id rather than name** (maintainer, 2026-10-03: "record
        other options, especially this one"). The bundled station index gives each of Hammersmith's
        two stations, and each Paddington, its own id, so wherever both sides carry an id (or one the
        index can map a name to), comparing ids would remove the name ambiguity at its source rather
        than ruling on brackets. Names stay the fallback where TfL's sources disagree on ids, which
        is why the name rule exists. A bigger change than the qualifier PR (#499), so its own PR.
        Alternatives considered for #499 and not taken: patch each site Codex flags (smallest diff,
        but each fix was local and the next finding came from another site); or show the
        qualifier and match exactly as before it (brackets ignored everywhere), leaving the
        Paddington and Hammersmith mix-ups as they were. Known gap of the name rule: the
        near-me terminating filter keys names without a qualifier (`Terminating.nameKey`, saved
        with each stop), so it still can't tell the two Hammersmiths apart by name alone; TfL's
        destination id decides first, so this only applies when TfL gives none.
  - [ ] **Revisit the header grain for a busy interchange** (maintainer, 2026-09-21). The rail
        platform split and the bus letter split (above) already break a hub into per-platform/per-pole
        blocks. Still open: whether a dense hub wants a *finer* grain still (per-line dividers within
        a block), or whether the blocks are enough — judge on a device; the alternatives are in the mock.
  - [ ] **Persist the bus letter/towards/bearing onto a watched stop.** The near-me path now captures a
        bus pole's `stopLetter`/`Towards`/`CompassPoint` and splits on them (above), but a **watched** bus
        stop refreshes from the arrivals feed, which carries none — so a watched bus stop still shows a
        bare header, not its "Stop D" or "➔ Archway". Capture all three on the Phase-2 watched-stop add flow
        (the StopPoint fetch that resolves a watched stop's cluster) and persist it on the watched
        stop / `PersistedStop`, so it survives restore like `clusterId` rather than being re-fetched
        only on a near-me refresh.
  - [ ] **Make the stop header tappable** (maintainer, 2026-09-21). The **route card tap
        landed** — it opens the full-screen `RouteDetailScreen` (star + full disruption text; see the
        detail-view item under *Watched stops and settings*). Still outstanding: tapping the
        **station-name header** does nothing yet — decide its destination (a stop-detail view;
        maintainer leaning toward a full-screen treatment too, with the maps/nav actions living
        there)
        and wire it.
  - [ ] **Tap-to-filter drill-down over the two-level header** (maintainer, 2026-09-22). With the
        place/platform hierarchy in place, each level's tap could open a filtered view — a three-level
        drill-down onto one boarding decision:
    - [x] Tapping the **place name** (top level) → a view filtered to that hub/cluster (all its
          platforms/poles and routes). Landed as an experiment (maintainer, 2026-09-23): the name
          within the header is the tap target — revisit if its discoverability or target size
          doesn't hold up on a device.
    - [ ] Tapping a **route card** → a view filtered to that line **and direction**.
    - [x] Tapping a **platform/pole sub-header** → a view filtered to just that platform
      (maintainer, 2026-09-23): the main screen filtered in place to the group's stops, back arrow
      in the app bar, no near-me fold. Still open for the other two entry points: the shared
      surface, and whether they converge on one filtered screen with a filter descriptor.
  - [x] **Dedupe a hub-wide alert; collapse the closure card** (maintainer, 2026-09-21).
        v122 showed the same interchange notice as three full-height cards (King's Cross St.
        Pancras + St Pancras International both carrying TfL's "no step-free access" text).
        The near-me path keeps each distinct notice once, on the nearest member
        (`DepartureRows.nearbyDeduped`), and the closure card collapses to one line,
        tap-to-expand (`StopClosureContent`). #89 folded by notice text; the follow-ups below
        then moved the fold to **hub identity** and the card to a **header-less, titled-on-expand**
        shape (both landed). Departures stay grouped per station (SPEC *Disruptions*). Follow-ups:
    - [x] **Alert with no stop-name heading, titled on expand** (maintainer, 2026-09-21). The
          preferred shape landed: a stop-closure alert renders as a **header-less card** ahead of
          the grouped departures — collapsed it is the notice's first line, and tapping titles it
          by the **interchange name** (else the stop) over the full text (`StopClosureContent`).
          `StopGrouping` no longer carves out or headers a closure (the screen filters closures
          out before grouping); the line-status ("No departures") carve-out stays. This replaced
          the earlier `alsoAt` "Also affects …" line from PR #90 (removed): the hub-identity fold
          below makes it unnecessary, and the notice text names the place itself.
    - [x] **Fold by hub identity — resolves "same-named unrelated places"** (Codex P2, PR #90).
          `nearbyDeduped` now keys the closure fold on `(hubId, text)` — TfL's `hubNaptanCode`,
          plumbed from the nearby lookup through `StopLocation`/`StopArrivals`/`DepartureRow`.
          King's Cross and St Pancras share `HUBKGX`, so the hub-wide notice folds to one card;
          two *genuinely unrelated* closures with identical place-less text ("Station closed")
          have different (or blank) hubs, so each keeps its card — the residual the text-only
          dedup couldn't separate. A hub with two different notices keeps a card for each. The
          interchange **display name** is resolved once per hub (cached) via a new
          `TflClient.hubName` (`/StopPoint/{hubId}`), off the render path, degrading to the
          stop's own name on failure (SPEC *Disruptions*).
    - [x] **Stop name always heads the alert (option 3b)** (maintainer, 2026-09-22). The
          title-on-expand shape hid *which* stop a collapsed alert was for — fatal for a bus
          "Bus Stop Closed", which never names its own stop. The place name (interchange, else
          stop) now heads the card collapsed and expanded (`StopClosureContent`); the body still
          collapses to its first line and expands on tap.
    - [x] **Clean the notice body: real newlines + strip a repeated station name** (maintainer,
          2026-09-22). TfL's bus notices arrived with visible literal `\n` escapes and indent
          runs; a tube notice instead led with "&lt;Station&gt; Underground Station: …", repeating
          the new heading. `cleanDisruptionBody` (domain, tested) turns the escapes into real line
          breaks and strips a leading run that matches the place name — by **detection, not by
          mode** (it removes a prefix only when the text actually starts with a form of the name),
          so the bus body (which names no stop) is left alone and relies on the heading.
    - [x] **Fold a bus-stop closure reported per pole** (maintainer, 2026-09-22). A closed bus
          stop is reported against each pole, which share no hub, so the hub-only fold showed a
          card per pole (a stop reported both ways). `nearbyDeduped`'s place key is now the
          coarsest identity that holds — hub, else a **real StopArea** (`stationNaptan`, never the
          display-name fallback: two unrelated same-named stops must not collapse), else stop — so
          real poles fold while genuinely distinct places keep their cards. It folds on the
          **newline-normalized, not name-stripped** body (the strip is deferred to display), so a
          hub's differently-named members keep one identity for a shared notice; folding on the
          stripped text would split them.
    - [x] **Strip the leading name in any member spelling (hub alias set)** (maintainer,
          2026-09-22). The display strip matched only the watched stop's own name, so a King's Cross
          notice that led with a *different* member spelling than the watched stop kept the name in
          the body. The hub lookup (`TflClient.hubInfo`) now returns the interchange's whole member
          alias set alongside its name, threaded to the stop-status row (`placeAliases`) and matched
          by the strip — no one name catches the dozen spellings, the union does. Cosmetic only
          (dedup was always spelling-proof via `hubNaptanCode`); best-effort, so a spelling no alias
          covers still just leaves the name in.
    - [x] **Dismiss a stop-closure alert; reappear on change** (maintainer, 2026-09-22). A ×
          on the closure card taps the notice away — for the "acknowledge and clear" kind (planned
          works, moved stop, step-free outage). Keyed on `(place, notice text)` and persisted
          (`DismissedAlertsStore` / DataStore); a dismiss only **adds**, so dismissing one of several
          cards at a place (the fold keeps a card per distinct notice) keeps the others dismissed. It
          **reappears the moment the text changes** (a reworded notice no longer matches), so a
          dismiss never buries a new or escalated closure, and a **refresh reconciles the set against
          the live notices, scoped to places it actually checked** — a resolved incident's dismissal
          is pruned so it can't later suppress a same-text re-occurrence (keeps it bounded too), while
          a place not queried this cycle or whose disruption lookup failed keeps its dismissal. Fails safe — an unreadable
          set reads empty (card returns, never a hidden warning). Filtered in
          `DepartureRows.withoutDismissed`; the stop's departures still show. Follow-ups below.
      - [x] **Show a stop notice only inside its TfL window** (maintainer, 2026-09-23). TfL lists a
            scheduled closure hours ahead; `fromDate`/`toDate` are now kept and the stop-status row
            filters on the render clock. Undated/unparseable counts as current. Departures untouched.
      - [x] **Key a dismissal on the notice's window too** (maintainer, 2026-09-23). Dismiss lasts only
            until the stated end; an extended or moved window is a new notice and shows again at once.
            An undated notice keeps its text-only signature, so existing dismissals still match.
      - [x] **Dismiss line-status alerts too** (maintainer, 2026-09-23: "dismiss all service
            alerts"). Supersedes keeping acute statuses undismissible. × beside the route detail's
            status chip; keyed per line on severity + label + reason, so an escalation or rewording
            shows again. The list drops the ⚠ (a no-departures status row goes outright); the detail
            says "Service alert dismissed", never "No disruptions". Reconciled only for lines TfL
            returned a status for.
      - [ ] **Expire a dismissal after ~a day?** — *open decision* (maintainer, 2026-09-22: "not
            sure I even want it"). A dismissal currently lasts until the notice text changes; a
            persistent closure then stays hidden indefinitely. Expiring after ~24h would re-surface
            it, and *if adopted* would also bound the residual reconcile corner below. Would need a
            `dismissedAt` timestamp (a schema bump) and a clock-driven max-age filter alongside
            `withoutDismissed`, in its own PR. Decide whether it's wanted before building.
      - [ ] **Known limitation: a failed reconcile write can outlast a restart** (Codex P1, PR #113).
            In-session, `reconcileDismissals` prunes the in-memory set even when the persist fails, so
            a stale signature can't suppress a card that session. The residual window: the persist
            fails *and* the process restarts (reloading the stale set) *and* a same-text incident
            recurs before the next reconcile — then `withoutDismissed` would hide it. Extremely narrow;
            the day-expiry above would bound it uniformly if adopted, else a local tombstone that
            survives store emissions would close it directly.
      - [ ] **Prune a hub-keyed dismissal only when every member was checked** (Codex P2, PR #113).
            Nearby selection pages stops by `clusterId`, not `hubId` (`NearbySelection`), so a cycle
            can fetch one StopArea of a multi-station hub, see it clear, and prune the hub-keyed
            dismissal while another member (unqueried) still carries the notice — the card then
            reappears when that member is next fetched. Safe direction (a card returns, no warning
            hidden), so deferred: `reconcileDismissals` would need to prove all of a hub's members
            were queried before adding the hub id to `checkedPlaces` (track checked source stops, or
            hub-membership completeness), which the current per-cluster paging doesn't cheaply supply.
    - [ ] **Per-description dedup** (Codex P2, PR #91). A stop's disruptions are joined into its one
          stop-status row's text (`stopStatusRow`), so the hub fold keys on the joined string: where
          two members of a hub carry *different sets* of notices — one reports X, the other X · Y —
          the shared X is not folded per individual notice and can show on both cards. Pre-existing
          (the join predates the hub fold; #89 deduped on joined text too), and rare (mostly one
          notice per stop). The fix — split a stop's disruptions into one stop-status row **per
          notice** before the hub fold — changes how multiple notices at one stop render (a card
          each rather than one joined card), so it's a **product/design decision** for the
          maintainer, not autopilot's to take. SPEC *Disruptions* now states the joined-text limit
          plainly rather than over-promising a per-notice fold.
    - [x] **Render a stop notice in place, not pinned to the top** (maintainer, 2026-09-26; landed).
          On the near-me list a notice rides with its place at the place's distance: a lettered
          pole's notice on that pole, a station's or interchange's as its own group (heading,
          distance, notice) above the place's first section, a closed station with nothing running
          as that group alone. Closures (by wording) get a "Closed" chip and the error tone; other
          notices a quieter tone. At most once per interchange. The watched list and platform view
          keep the standalone card. `byStopDistance` still sorts notices first, which no longer
          places them; dropping that key is a cleanup.
    - [ ] **Unify how we identify and group a place across disruptions, departures, and direction**
          (maintainer, 2026-09-22). The stop-disruption fold now groups by hub → real StopArea →
          stop, and the strip matches a wildly-spelled name (King's Cross St. Pancras appears in TfL
          data as "Kings Cross St Pancras", "St Pancras Intern'l & King's X Stns", "… International
          LL Rail Station", and ~8 more). The same place-identity question underlies the near-me
          "group by station and direction" grouping (`StopGrouping`, `dedupeKeyOf`) — worth a single
          shared notion of "which place, which direction" rather than parallel heuristics per
          surface. Design-level; not scoped here.
    - [ ] **Merge the interchange's departures to the hub?** SPEC keeps King's Cross and St
          Pancras as separate departure headers (they are different buildings). Clustering on
          `hubNaptanCode` would merge them into one place; the next grain down is
          `stationNaptan` (today's cluster) as a sub-header. Reverses a SPEC decision and hits
          the dense-header case below — a maintainer call, discussed but not taken.
  - [x] **Carry the stop grouping through the widget** (Codex, PR #78). Landed: `widgetModel`
        groups with the shared `StopGrouping` and titles each place with the in-app header text
        (`groupHeaderTitle`); a header costs one line of the budget and is never drawn without a
        row under it. One place with no qualifier stays header-less. Original note: The widget ships
        now and, on a multi-stop snapshot, renders a flat sequence of destination rows with
        no stop headers — so it gives no boarding location, the same gap this PR just closed
        in the app. `StopGrouping` is pure and shared-ready; adopt it in `widgetModel` /
        `WidgetContent`. The real work is the widget's **tight line budget** — a header costs
        a line, so how many stops/headers/countdowns fit needs deciding (and a screenshot
        test). Deferred to a focused follow-up, not a phase: this PR scoped the change to the
        in-app screen.
  - [ ] **Make the widget re-abbreviate on resize** (maintainer, 2026-09-22). The in-app card
        re-measures and re-shortens a destination as the display resizes (font scale, width — see
        the branch-truncation item and its font-scale fix), but the widget **can't measure width**,
        so it always shows the short branch and never re-abbreviates the destination when the
        widget is resized on the home/lock screen. Making it size-aware — Glance `LocalSize` /
        `SizeMode`, picking the label form from the widget's current size bucket — would close the
        gap. Bigger than the card fix (no `TextMeasurer` in Glance, so it's size-bucket heuristics,
        not measured widths) and needs a widget screenshot test per size; recorded to weigh, not
        scheduled.
        **Start landed:** the widget is now laid out at the exact size the launcher reports
        (`SizeMode.Exact`); below 220dp the stamp drops its "Updated" prefix. Labels can key off
        the same width.
  - [x] **Show the widget in two columns whenever there's room** (maintainer, 2026-10-05), as
        on a tablet or in landscape: from 480dp wide, the breakpoint chosen from screenshots of both
        layouts at 400–900dp; rows split where the columns come out closest in height.
- [x] **Hide services terminating at the current stop by default** (maintainer, 2026-09-20).
      A train that terminates where you're standing isn't boardable onward, so listing it as
      an upcoming departure is misleading — filter it out by default (a departure whose
      terminus is this stop). Watch the edge where an interchange train "terminates" only
      nominally before continuing under a new id; scope it to genuine terminations. Landed in
      c77aad1 (SPEC *A service that goes nowhere for the rider is left out*): matched by TfL's
      destination stop id against the nearby places, by name only when TfL gives no id, and
      hidden as rows are built, so the widget hides the same services.
  - [ ] **A "show terminating services" option**: not built. Add it if a rider misses them.
- [x] **Hide a mode from the near-me list** (maintainer, 2026-09-24): long-press a row or header
      for "Hide ‹mode›"; a one-line "‹Mode› hidden · Show all" banner undoes it; hidden stops aren't
      fetched from the next re-locate; the widget follows. Next ideas, for later:
  - [x] **Mode checkboxes in the overflow menu** (maintainer, 2026-09-24): six fixed groups — Tube &
        DLR, Train (Overground, Elizabeth line, National Rail), Bus, Tram, Boat, Coach — each ticked
        while shown; the long press hides by the same groups.
  - [ ] **Hide one line at a place** ("Hide Thameslink here") and **hide one platform/pole** (its
        card collapses to the header; a stop none of whose cards show isn't fetched).
  - [x] **Hide an individual line everywhere** (maintainer, 2026-09-27), e.g. the Northern or
        Central line, not just its whole mode: "Hide ‹line›" beside "Hide all ‹group› services" in a
        near-me row's long-press menu and a trip card's menu (each line its legs ride). Not on a
        place's header, where a busy place would list dozens.
  - [x] **A "Hidden" list in Settings** to unhide one item at a time, and an **Undo** snackbar
        right after hiding, once there's more than modes to hide. *Done: a long press's hide offers
        Undo for that one item, and Settings lists each hidden group and line with a Show, while
        anything is hidden. A nearby set remembers what it was picked without, so a list or trip
        out of view when Settings showed something re-picks as it comes back.*
  - [x] **Skip the National Rail board for a hidden National Rail mode** at a station that also
        serves an unhidden mode, in the near-me list, a farther station's card and the widget; a
        starred journey's rail times are kept (`HiddenModes.wantsRailBoard`).
    - [x] **A trip's boards with National Rail hidden**: a trip drops routes riding a hidden line,
          so none it times rides National Rail then, and it asks for no board; a station fetched
          without one is fetched again once National Rail shows.
- [x] **"?" where a line's times are unknown, a dash only when none is coming** (maintainer,
      2026-10-03). TfL's live list for a station came back empty during a disruption while trains
      ran, and the status row's dash read as "no trains". The dash now needs StopDash to be sure —
      the line isn't running there, or its TfL timetable has nothing within 30 minutes — and is "?"
      otherwise. The timetable is never shown as times.
  - [ ] **The same marks on a place card that came back empty** (maintainer, 2026-10-03: status
        rows first, cards after). Build each card's board (the lines it shows, at the stops that
        serve them: an interchange's member stations, not its hub; a bus card's picked routes only;
        no National Rail line) where the cards are built, off the main thread, with a revision, and
        tie each mark to the revision it was worked out for. Working the board out in composition, as
        PR #502 first did, drew review findings about stale marks and collection-sized Compose keys.
  - [ ] **The scheduled frequency in place of a bare "?"** (maintainer, 2026-10-03): where the
        timetable has departures due, show its interval with the "every" sign and the same interval
        logic as a trip's future stops, a "?" after "min", likely in gray. Copy to agree first.
  - [x] **Build the departure rows off the main thread**: the list's rows, the journey cards
        (`Journeys.trains`), the platform view's rows and the alert lines and verdicts are built on the
        list's worker (`ListWork`, keyed on the snapshot's identity, not its collections). The screen
        draws against the snapshot and time its rows were built from; the last rows stand in while new
        ones are built, and a new list shows a spinner until its first rows are in.
  - [ ] **The rest of the main screen's loops off the main thread**: the widget journey checks and
        boarding keys, `journeySiblings`, `journeyOrigins`, `fartherShown` and `placeModesShown` still
        loop over cards, rows or stops in composition. Move them to the list's worker the same way.
  - [x] **Work a trip card's times out off the main thread**: each card's `cardTimes` is worked out
        on the page's worker (`rememberWorked`); its last times stand in meanwhile, drawn against the
        time they were worked out for. The card's statuses and closures, and the page's estimates,
        are the Phase 0 item *Trip page: work out its per-tick values off the main thread*.
  - [ ] **Group a route page's rail countdowns by the path their via resolves to**, not the via's
        text (`routeDepartures`/`routeUntimed`): two trains running one path under different via
        wording ("Horsham", "Horsham & Arundel") now split, the page showing only the followed one's.
        Darwin's via table keys its text by station and destination, so this should be rare; it errs
        to fewer countdowns, never a wrong one (Codex, #515).
  - [ ] **The same marks off the phone's list**: the widget and the watch show no dash for a
        line with nothing coming today, so they're unchanged; revisit if either ever shows one.
  - [x] **A good-service line with no live times gets a "?" row** (maintainer, 2026-10-03: a
        line's whole feed went quiet on Good Service and its station showed nothing for it). In the
        near-me list only, and only where its timetable has a train due, or, unable to say, for a
        tube, Elizabeth line, Overground, DLR or tram line; never National Rail, so a hub's
        intercity services add no rows.
  - [ ] **The same "?" rows on a station's own page** (opened from its header): it still lists
        only a quiet line's disruption, not its missing times.
  - [ ] **A "Times unknown" note on a "?" line's page**: a "?" row opens nothing for now, since the
        page has no times and no words yet for why (copy to agree with the maintainer first).
        Until then a "?" row's planned work shows only as its calendar glyph: its full notice and
        dismissal are on that page (Codex, #505).
    - [x] **Don't move a route's direction onto a stop with older data** (Codex, #528): the
      "directions together" rule picked a place by distance alone, so after a partial refresh
      failure it could trade a direction's fresh row for the other place's stale one (a "21:14?"
      guess in place of a countdown). `keepDirectionsTogether` now refuses an older stop, as the
      one-way join from #528 does.

## Phase 2 — Watched stops and settings

- [ ] Add/remove **watched stops** (the source of truth for what's shown) — added from
      search or nearby discovery, removed explicitly; persist the set. Distinct from
      starring; removing a multi-line stop drops all its rows. **[store landed, PR #26]**
      `WatchedStop`/`WatchedStops`/`WatchedStopsStore` + `DataStoreWatchedStopsStore`
      (reactive `watched()`, add/remove; a newer-schema file reads as `Unavailable` and is
      preserved, never overwritten). Still to wire into `MainViewModel` as the departures
      source and build the add/remove UI.
  - [ ] **Version-envelope read before a future incompatible schema bump** (Codex P2 on
        PR #26, deferred). The store preserves a newer file that still *decodes*, but a
        future schema that changes an existing field's shape would fail to decode and be
        discarded as corruption — silently deleting the set on a downgrade. Before shipping
        any schema **v2+**, add a stable version-envelope read (parse `version` alone; keep
        unknown-version raw bytes rather than treating a decode failure as corruption).
        Best built *with* that v2 (its shape is needed to build and test it); v1 is the only
        schema today, so a decode failure now is genuine corruption and correctly discarded.
        Applies to every versioned store that decodes a typed body then checks `version`:
        watched-stops, starred rows, and now favorite places (Codex P2 on #271) — fix them
        together when the first incompatible bump lands.
- [x] **Restore the stop name to the departure card when the list spans more than one
      stop.** The compact-card redesign dropped it (too much clutter, and implicit on a
      single-stop widget), but the current seed is already multi-stop (Oxford Circus +
      King's Cross), so a card gives no boarding location and a countdown can't be told
      apart from the other station's (SPEC D1 / principle 1). Codex P1 on PR #17
      (`discussion_r4049648510`). **Landed as a per-stop group *header*** rather than a
      per-card subline (`StopGrouping`/`StopGroupHeader`), shown whenever >1 distinct stop
      is on screen (or a stop is closed) — cards stay unambiguous without the stop restated
      on every one.
- [x] **Star** rows to reorder them to the top — ranking only, not membership; persisted
      via DataStore so a star survives restart (D8). Keyed by the full
      **`(stop, service, resolved direction key)`** identity (`DepartureRow.directionKey`:
      direction, else platform, else destination), so a star restores to exactly one row.
      Landed: `StarredRow`/`Starred` + `DepartureRows.pinStarred` (warnings still lead —
      a starred service never jumps above a closure or a no-prediction status row),
      `StarredRowsStore` + `DataStoreStarredRowsStore` (reactive `starred()`, toggle; a
      newer-schema file reads as `Unavailable` and is preserved, never overwritten), and
      `MainViewModel.toggleStar`/`starred` wired through `MainScreen` and `MainActivity`.
      Stars ride Android backup/transfer (SPEC *Privacy* backup note), not an app-initiated
      send. Starring is available only on timed cards — a star restores its pin the moment a
      starred, currently-suspended line has departures again. **The interaction is a long-press
      on the card, marked by a gold border** (PR #73): the original filled/outline per-card
      `Star` button was removed because it ate width on every row; the discoverable, labeled star
      now lives in the app bar of the tap-to-open full-screen route detail (below).
- [ ] "Near me now" discovery (on-demand location, nearby `/StopPoint` lookup selected by
      `NearbySelection`) with one-tap add-to-watched; stop search. Distance ranking lives
      here — for *finding* stops to watch — not in ordering the watched list, which stays
      location-free so the view works with location denied (D1). Stop **selection** (two-tier:
      the nearest two clusters of each mode eager — the Tube, Overground, DLR and Elizabeth line
      sharing one cap as one metro mode — plus a computed *more* tier) is implemented in
      `NearbySelection` (two-tier, 2026-09-21; per-mode crowd-out, PR #39), and the eager tier is
      what the near-me list shows. **Surfacing the *more* tier (a per-mode "More" reveal) is
      deferred to its own change** (see the "More" reveal item below); one-tap add-to-watched and
      stop search are still to build.
  - [x] **Nearby selection — settled as the two-tier near-me (2026-09-21).** The product
        constraints — a line appears once (not per stop it passes), no dense mode crowds out
        another, bounded within reach, by line with both directions, closed stops surfaced
        honestly — live in **SPEC *Finding stops → Near me now*** as the intent the
        implementation must satisfy; the two-tier selection (nearest two clusters per mode +
        per-mode "More") is what meets them. The bullets below are the shaping history that led
        there, kept for the reasoning; the radii and the cap are still to validate on a device.
    - **Superseded by the two-tier resolution below (2026-09-21), kept for the reasoning.** The
      earlier lean was distance-shaped: all services within ~0.2 mi, plus at least one stop per
      mode within ~1 mi, expanding the 0.2 mi radius if empty. No inner ring survives in the
      two-tier design, which selects the nearest two *clusters* per mode instead.
    - **Resolved — the two-tier near-me (maintainer, 2026-09-21).** The list is no longer
      distance-shaped-with-no-cap: it shows the **nearest two clusters of each mode** eagerly. The
      per-mode cap bounds the eager fetch burst (below) and answers the count-cap question. The
      **"More" control that pages the rest is deferred** — the count cap ships, but reaching the
      farther clusters (principle 2's "one tap away") comes with the "More" reveal follow-up
      below. See SPEC *Finding stops → Near me now*. **The Tube, Overground, DLR and Elizabeth
      line count as one mode for the cap (maintainer, 2026-09-30)**: counted apart, King's Cross
      fetched Euston (800 m) every refresh as its nearest Overground, whole National Rail board
      and all; a metro station in reach now stands for all four, and a metro line nothing loaded
      serves gets a farther-station card instead. **An interchange's rail stations count as one
      place and load together (maintainer, 2026-09-30)**: counted apart, King's Cross mainline
      (LNER, Great Northern) was left out behind two St Pancras stations, with no farther card.
    - **One canonical distance unit:** miles (the maintainer's numbers are in miles). The reach
      is **~1 mile (~1609 m)** — the TfL query's own radius, the hard bound for one lookup. The
      two-tier design dropped the ~0.2 mi inner ring and its expand-if-empty rule (there is no
      inner ring now). **The reach and the two-per-mode cap are provisional — not yet tested on a
      device — the numbers `NearbySelection` currently uses, to be tuned in the field.**
    - The natural unit may be a "mode-stop" (all services at a nearby stop); how stops /
      mode-stops / directions map onto the TfL model and API is an **implementation** question
      left open here — this bullet is the policy, not the settled shape (which is in SPEC).
  - **Near-me-now display order — closest stop first (maintainer, 2026-09-19, supersedes the
    two-band lean below).** The near-me list now sorts **by stop distance, closest first**
    (`DepartureRows.byStopDistance`), with **soonest-first only as a same-stop tiebreak** (rows
    at one stop are equidistant, so time orders them; it never moves a near stop below a far
    one). Warnings still lead (by `rank`) and starred rows are lifted afterward by `pinStarred`.
    Applies only on the near-me path (distances present); the location-free watched list keeps
    `across`'s soonest-first order (D1). A stop missing from the distance map sorts last.
    - **Why, and why "starting point":** closest-first matches "what can I walk to from here,"
      and — the incidental win — it **cuts reshuffles**: distance is near-constant between
      refreshes, so a stop's rows stay grouped and only re-order *within* the stop as
      countdowns tick, instead of the whole list re-interleaving across stops every tick like
      global soonest-first did. Accepted cost: the closest stop leads even when nothing leaves
      it soon (a far stop's imminent departure sits lower) — judged acceptable to **try on a
      device** and revisit. Not bucketed yet, so fine GPS jitter can still swap near-equal
      stops; coarse-bucketing distance is the first lever if that reads as churn on-device.
    - **Superseded (earlier lean, kept for the reasoning):** a two-band order — inner-ring
      (~0.2 mi) rows first, mode-coverage rows (pulled in from beyond ~0.2 mi) below — so a far
      coverage Tube/pier never floats up on a soon countdown. Closest-first achieves the same
      "far coverage stop doesn't jump the queue" outcome without a threshold to tune, so the
      band split is dropped as the starting point; revisit only if pure distance reads worse on
      a device than banding would.
  - [x] **Show each near-me stop's distance in its group header** (landed here). The near-me
        list shows each stop's distance in parens after the name ("Oxford Circus (120 m)"), so a
        rider can judge which nearby stop to walk to rather than only reading the order; the
        watched list stays location-free and shows none (D1). Interim unit is **metric, m/km**
        via the pure `StopDistance.label` (nearest 10 m below 1 km, nearest 0.1 km above, floored
        at "10 m" so a fix on the stop doesn't read "0 m"). SPEC *Finding stops* updated.
  - [x] **Distance units setting, following the locale by default, with a fractional large unit
        past ~500 m** (maintainer, 2026-09-21 / 2026-09-25). Settings → *Distance units*: Auto /
        Meters / Yards / Feet. Auto reads the locale's CLDR measurement system (UK → yd+mi, US →
        ft+mi, else m+km); the pure `StopDistance.label` takes the system, switching to the long
        unit at ~500 m (feet at 0.1 mi). Screenshot baselines stay in meters (the default outside
        the app root); the bug report keeps meters for diagnostics.
- [x] **Hide services that end where the rider is** (maintainer, 2026-09-24): a departure whose
      terminus (TfL `destinationNaptanId`, else its name) is a nearby place no farther than its
      boarding stop is dropped before the merge (`Terminating`), so the list and widget agree.
- [x] **Find a station and view its departures** (maintainer, 2026-09-24). Overflow → *From…*
      (the location gate's button still reads *Find a station*) → TfL `/StopPoint/Search` as the user types (300 ms pause, 2+ letters) → a match
      opens that station's live departures: its departure-bearing stops from `/StopPoint/{id}`
      (a hub's stations, a station, a bus stop area's poles), fed through a `MainViewModel` of
      its own with no snapshot store, so the widget keeps the near-me set. A look, not a pin.
  - [x] **Fuzzy find, abbreviations and ids** (maintainer, 2026-09-24). A bundled station index
        (`assets/stations/station_index.json`, built from TfL by `scripts/build_station_index.py`
        in the `station-index` workflow) searched on the device with TypeLauncher's tiers (prefix >
        anchored word starts > substring > fuzzy), normalized (apostrophes, punctuation, accents),
        with generated abbreviations ("Cross" → "X") and TfL codes (`HUBKGX` → "KGX"); TfL's
        search merged in after the pause for bus stops. A station inside a matched hub folds into it.
    - [ ] **Consider a "Searching bus stops…" line** (maintainer, 2026-09-24, undecided): the
          bundled stations show at once and TfL's bus stops follow the pause, with only the thin
          progress bar hinting more is coming. A line at the foot of the list while TfL's search
          runs (turning into "Bus stops not searched" on failure) would say so; weigh it against
          the extra flicker as results settle.
    - [ ] **Consider a clear (×) button in the search field** (maintainer, 2026-09-25,
          undecided): a trailing icon, shown only with text in the field, that empties the query
          in one tap, in both the From… and To… searches. Needs approved copy for its content
          description (proposed: "Clear search") before it's built.
    - [ ] **Rank by use**: TypeLauncher breaks ties by how often each item is opened. Here that
          would store which stations a user looks at (user data, on device, riding backup), so it
          waits for a decision and a *Privacy* line.
    - [x] **Refresh the station list on a schedule** (maintainer, 2026-09-24: separate, not riding
          the dependency batch): `station-index-refresh.yml` rebuilds it every Sunday and, when it
          changed, opens or updates a pull request from `station-index/refresh` with the batch's
          `GRADLE_UPDATE_PAT`, so CI and review run on it. A failed build fails the run (emailed)
          and keeps the committed list.
      - [ ] **One shared `UPDATE_PAT`** (maintainer, 2026-09-24): the refresh borrows the
            dependency batch's `GRADLE_UPDATE_PAT` for now; replace the per-purpose tokens with
            a single `UPDATE_PAT` (Contents and Pull requests: read and write) that both use.
    - [ ] **Extract the matcher into a shared `mikelward/*` library**, with TypeLauncher's copy,
          rather than keep two.
  - [x] **The user's own stops, without TfL** (maintainer, 2026-09-24): before typing, the
        search lists *Recent* (last eight opened, most recent on top, kept in no-backup storage;
        *From…* and *To…* each keep their own, maintainer 2026-09-26) and then *Starred* (journey
        ends, places holding a starred row, less those picked lately); as the user types, those and
        the places lately shown near them (widget snapshot, nearby-lookup cache) match on the
        device, the user's own leading their tier by last use.
    - [ ] **Clear or remove a recent entry**: the list only ages out past eight; a long-press to
          remove one (or a "Clear" on the heading) if it proves wanted.
  - [x] **Set the near-me origin to a station** (maintainer, 2026-09-24): *From…* now opens the
        near-me list as if standing at the searched station (its position as a fixed location), so
        its page and its *To…* share the near-me code.
  - [x] **From… To…, direct only** (maintainer, 2026-09-24): the overflow's *Find a station* is
        now *From…*, and a station's page has *To…*, which keeps only the departures whose own
        line's route calls at the picked destination (`DirectTrips.filter`), flagging any it
        couldn't check. *(Superseded: both To…s now open the trip planner, below; the direct-only
        page is left to delete.)*
    - [ ] **Trips with a change** (maintainer, 2026-09-24; designed 2026-09-26, SPEC *Trips with a
          change*): *To…* a stop plans with TfL's Journey Planner, lists routes best first (checked and open, then unchecked, then not running; within each,
          fully live, then est., then withheld; then earliest arrival) as
          alike cards (line pills, duration · arrival, first leg's live row), and opens a route with
          every leg as the list's own header and route card with live times and alerts.
          Mock: the "StopDash Journeys Mock" artifact. `docs/PRIVACY.md` and Play Data Safety are
          updated in the same change (both ends of a trip go to TfL as stop ids).
      - [x] **First version** (#242): *To…* from the near-me list and a *From…* station plans,
            lists and opens routes with live times, walks, ranking tiers, status and refresh
            warnings, the location banner and hidden modes.
      - [x] **Closure checks where the rider gets off** (each leg's alighting stop and the
            destination), from the list's few-minute cache, with the boarding stops (below).
      - [x] **Gray unreachable trains in the leg-by-leg cards** (the list's first-leg row does):
            an opened route grays, on each ride, the trains that leave before the rider gets there.
      - [x] **From row on the destination search** (maintainer, 2026-09-28): the *To…* search's
            bar is From over To, From naming the start ("Here" or the station) and changing it
            through the *From…* search, what was typed for the destination carrying across.
            Replaces a From chip built and removed the same day; the near-me list's chips still
            have no "Here". Needs a device check of the bar's look and the round trips through the
            search.
      - [x] **The routes keep the From/To bar** (maintainer, 2026-09-28, option B of three mocked;
            raised by Codex on #356): a planned trip shows where it starts and where it goes, a tap
            on From changing the start through the *From…* search with the destination kept, and one
            on To reopening the *To…* search with the start kept. Needs a device check of the round
            trips (from here and from a station, to a stop and to a place).
      - [ ] **Change the start in place** (maintainer, 2026-09-28; option C of the mocks, not
            chosen for now): type the new start into the From field itself (Maps-style) rather than
            through the *From…* search and a station's page, which would also retire the
            change-of-start bookkeeping (`OriginChange`) that drew four review findings on #356.
      - [x] **Saved places in the *From…* chip row** (maintainer, 2026-10-04): after "Here", each
            place's chip opens the departures around it, as a *From…* station does, its *To…*
            planning from the stops there as from "Here".
      - [ ] **Borrow from the other search's recent list** (maintainer, 2026-09-26): *From…* and
            *To…* keep separate recents, ordered by last use; maybe a single button to swap in the
            other list, or a way to pick from it (the station you came from as the way home).
      - [x] **Station page's own *To…*** plans trips as the near-me list's does (the station page's
            *To…* has opened the trip planner since *Plan trips with a change from To…*), for a
            station TfL places.
      - [ ] **To… from a station TfL doesn't place**: with no position for any of its stops, the
            station page falls back to its stops' departures with no *To…*. Plan from a plannable
            member of the station's resolved stops rather than drop the action (the Planner takes
            no interchange `HUB…` id; `PlanTargets` rejects them).
      - [x] **Favorite places at the top of the To… picker** (maintainer, 2026-09-27): the near-me
            To… picker lists all saved favorite places before any typing, under a *Places* heading
            above *Recent*/*Starred*; tapping one routes the trip to its coordinate (D9), as the
            Settings route-to path does. UI-only (renders from the search state), and the picker still
            works by typing when there are none. Two follow-ups:
        - [x] **Extend to the From-station To… picker**: a *From…* station's To… lists the saved
              places and routes to one's coordinate, as the near-me To… does.
        - [ ] **Hide a favorite that duplicates a nearby stop** (maintainer, 2026-09-27): when a saved
              place sits at a stop already listed, one of them is redundant in the picker.
      - [x] **Favorite places as chips atop the near-me list** (maintainer, 2026-09-27): one row of
            chips, the list's first item (it scrolls away with the list), a tap routing as the Settings
            and To… paths do; a place within 200 m of an accurate fix is left out (back past 250 m),
            and an approximate fix (or an approximate-only grant) hides nothing. Checked on a device by the maintainer
            (2026-09-28).
        - [x] **Days of the week per place** (maintainer, 2026-09-28): each place picks the days its
              chip shows on (every day by default), in its editor. Checked on a device by the
              maintainer (2026-09-28).
        - [x] **An icon per place, and what its chip shows** (maintainer, 2026-09-28): a fixed set
              of monochrome Material Symbols; the chip shows the icon, the name, or both. Checked on a
              device by the maintainer (2026-09-28).
        - [x] **Long-press a place chip to open Favorite places** (maintainer, 2026-09-28). Checked on
              a device by the maintainer (2026-09-28).
        - [x] **Favorite chips on the "no stops nearby" screen** (Codex on #315): with nothing in
              range the list gave way to the location gate, which had no chips. Done: the gate's
              "No stops found nearby" heads the chips as the list's empty state does (the same
              already-there and day rules, `rememberShownPlaces`), and a tap opens the trip from
              here, which plans from the rider's position and so starts with no stop in range,
              keyed by the position it plans from, moved after 150 m (`hereAnchor`), and
              refreshed by each new position (`tripRepickId`). "No stops nearby" from a last-known fix now carries the approximate
              banner, as the list does, over that trip and for the chips.
      - [x] **To… a geocoded place or postcode** (maintainer, 2026-09-27): the To… search now offers
            geocoded places (landmark, address, postcode) beneath the stop matches, each tagged
            *Place*/*Postcode*; tapping one plans to its coordinate (D9). TfL's Journey Planner is the
            geocoder (`KtorTflClient.searchPlaces`, the same call the postcode resolver makes);
            `PlaceHits.rank` re-ranks a place-name query by our own name matcher (TfL's raw order is
            noisy — a weak partial can outrank the landmark) and drops non-matches. Near-me To… only
            (a To… picker sets `onOpenPlace`). Follow-ups:
        - [ ] **A better geocoder**: TfL's Journey Planner ranks place candidates poorly (a well-known
              landmark ranked well down its raw results in testing); our re-rank fixes the obvious cases
              but a dedicated place/geocoding API (or TfL's `/Place`) would return better candidates.
              Watch the added per-search TfL request cost when choosing.
        - [x] **Rank stops and places together, matching each part of a name** (maintainer,
              2026-09-28): one list by match tier, so "tate" puts "…, Tate Britain" above "… Estate"
              stops; a name's parts (" / ", ", ") each match as a prefix.
        - [x] **Keep the results list still while answers arrive** (maintainer, 2026-09-28): the
              index's best four show at once with a spinner beneath, and TfL's stops and places are
              appended below them as each arrives, never re-ranked above. Follow-up: see on a device
              whether a better TfL match landing below the fold is missed.
        - [x] **Remember a picked place in *Recent*** (2026-10-02): a geocoded place picked from *To…* is
              kept with the stops picked there, by its name and coordinate, in the order picked, and
              listed under *Recent* with its Place/Postcode tag.
        - [x] **Extend geocoded places to the From-station To… picker**: its search offers places too.
      - [x] **Read a rail leg TfL names only by its platform** (found 2026-09-30 recording #420's
            fixtures): the Planner can name a rail leg's end by `individualStopId` alone
            (`9100STPXBOX`, no `naptanId`), and the whole route was dropped as unreadable: Thameslink
            from St Pancras and the Elizabeth line from Liverpool Street went missing that way. The
            bundled station list now carries the platforms TfL lists under each station, and the leg
            is read as leaving that station (`StationIndex.stationOf`).
      - [x] **Time a Thameslink leg from St Pancras low level** (maintainer, 2026-09-30: a trip
            fetches everything it needs, through the same cache as everything else): SPL takes
            STP's board in the station codes; a trip's client gives every stop of a station its
            board (`boardAtEveryStop`), while a list still shows it under one; and each board is
            kept once per station in the shared arrivals cache, so screens share one request.
      - [x] **Delete the unreachable direct-trips page path**: `LookDepartures` is now only a
            station page with nowhere to stand; the direct-trips filter page, a trip from here
            through it, and a To… destination's widening to the stops around it are gone, with
            their tests. The planner keeps `DirectTrips.filter`, `rememberLineSequences` and
            `hereOriginIds`.
      - [x] **Plan to every station of a complex** (maintainer, 2026-09-26: the best way to King's
            Cross St. Pancras whatever the line or mode): once per station code plus one bus
            stop, in parallel, merged, the soonest six routes timed.
      - [x] **Plan a trip from here from the rider's position** (maintainer, 2026-09-28), not the
            nearest stop: from a bus stop the Planner only offered a bus to the Tube, never the walk to
            it. Re-plans 150 m on from where it was planned. Needs a device check that the walk-to-station
            route now appears and its first walk reads right on the card and on the way.
      - [x] **From chip on the To… picker and ahead of the favorite chips** (maintainer, 2026-09-28),
            labeled "Here" by default: built and replaced the same day by the From-over-To bar (SPEC
            *Where a trip starts*); "Here" is the first chip of the *From…* search instead.
      - [x] **Walking speed** (maintainer, 2026-09-28): Slow / Medium / Fast, in Settings and atop a
            trip's routes or an opened route, sent as the Planner's `walkingSpeed`. The device check found every walk
            unchanged: the Planner ignores the speed unless the request names its modes, which it now
            does (walking among them).
      - [x] **Configurable walking limit** (maintainer, 2026-09-30): the Planner's `maxWalkingMinutes`,
            *Max walk* in Settings and as a dropdown under the walking speed atop a trip (10–60 min, default 20 since 2026-10-03, 30 before; it was
            a fixed 15). Each plan also asks the Planner for the fewest changes beside the quickest,
            merged, since its three quickest routes are often one route at three departures.
      - [x] **Mode toggles** at the top of a trip, remembered across trips (the Planner's `mode=`):
            chips under the step-free dropdown, one per mode group the list hides by (SPEC *Modes*);
            the design is autopilot's, see *Decisions needing review*.
      - [x] **Avoid a line** (maintainer, 2026-10-01: sticky, a chip atop the trip and in Settings): a
            route card's long press offers "Avoid ‹line›" for each line it rides; avoided lines are kept
            across trips (`AvoidedLinesSetting`), each a chip atop the trip that a tap clears and a row in
            Settings with Remove, and a route riding one is dropped on the phone as a hidden line's is
            (`AvoidedLines.excluded`). `includeAlternativeRoutes` returned the same routes in testing, so
            it isn't asked for.
      - [x] **Fastest / Simplest headers** (maintainer, 2026-09-30): a bold header over the first
            card ("Fastest") and over the one riding fewest ("Simplest"), or "Fastest · Simplest" on
            one card that is both (`routeLabels`, `:domain`). The rules are autopilot's; see
            *Decisions needing review*.
            - [x] **"Other" over the rest** (maintainer, 2026-09-30): one header over the cards
                  neither Fastest nor Simplest, after those two (`headedCards`), so Simplest moves
                  up beside Fastest; no Other where neither of those is shown.
      - [x] **Least walking** (maintainer, 2026-10-03): a third Planner request per plan
            (`journeyPreference=leastwalking`), merged; a "Least walking" header over every card
            tied for the least walking, the first included (2026-10-04); a route walking 5+ min
            less is kept past the fewer-changes pruning, and the least walking past the timing
            cap. Needs a device check that a trip with a long walk to its station now offers a bus.
            - [ ] **A hidden route's card lingers one worker run** (Codex, #535): the headers are
                  worked out on the worker, and within one plan the last cards stand in, each under
                  its own header, until they're in, so the headers don't blink on every 10 s tick. A
                  route just hidden or avoided can show (tap ignored) for that run. Closing it means
                  telling the cards' routes alike without per-card work in composition: a route-set
                  stamp built with the cards on the worker, once the card work moves there.
      - [ ] **Step-free trips** (maintainer, 2026-09-30; after the intermediate work): a dropdown
            atop a trip, **Step-free: Any / Station / Fully** (Station: step-free from the street to
            the platform; Fully: to the train as well; the maintainer's names, 2026-09-30), planning
            to it and remembered like the walking speed; an **"Accessible"** header over a route that
            meets it, as Fastest / Simplest are; and wheelchair icons on step-free stations (the
            step-free status item above). The labels should tell a rider with a suitcase or a buggy
            that Station suits them too.
            - [x] The dropdown (#408): sent as `accessibilityPreference` (`StepFreeToPlatform`,
                  `StepFreeToVehicle`), with a line under Station and Fully saying what each is for.
            - [ ] The "Accessible" header: the legs' `obstacles` list stairs, lifts and ramps, so a
                  route can be judged; *to decide:* what it means with "Any" chosen.
            - [ ] The wheelchair icons: the data is bundled now (TfL's station data, per platform
                  and line; *Show step-free / accessibility status*); the marks themselves are
                  tracked there.
      - [x] **Move bus stop placement into `:domain`** (Codex, #398): which pole of a stop pair, or
            which stand of a bus station, a bus leg boards and gets off at is product logic, so
            `polesOf`, `onPoles`, `placedOnPoles`, `endPole`, `placedStands`, `PlacedStand`,
            `boardingKey`/`alightingKey` and `canStart`'s placement rule (`busesSettled`) now live in
            the domain's `BusPlacement.kt`, tested by `BusPlacementTest`. The screen keeps what reads
            the trip's state: which placed poles are fetched, and whether a leg is still loading.
      - [ ] **On the way** (maintainer, 2026-09-26; SPEC *On the way*; autopilot): Start on an open
            route follows the rider there, assuming the next train they can catch and switching
            when another is seen (this replaces the mock's "I'm on this one" tap as the way in).
        - [x] Which train makes each departure, and one train's calls ahead (`VehicleSource`).
        - [x] Following a started trip from its train's calls (`OnTheWay`, `:domain`).
          - [x] **A loop train's boarding call jumping over five minutes at once**: a loop train still
                on its previous lap, whose call at the boarding stop moves more than five minutes
                later between two refreshes, is taken for its next lap (it gives the very same calls
                as one that just left with its next lap predicted). Tell them apart from the rider's
                location (still at the stop), or from how long since the call was last seen. *Done
                by location (`OnTheWay.atBoarding`): seen still at the stop, the call is the same one,
                late. Only the fixes the trip already takes; unseen, as before.*
          - [x] **Candidates past the first three**: a pick asks after at most three trains (one
                request each, within the keyless rate limit). At a fork where the first three turn
                off, none was found until one that runs along the leg came up. The board is now
                filtered by the line's route first (`OnTheWay.mayTakeRide`, as the trip view's leg
                rows judge them), at no request cost, so the three asked after are ones that may run
                the rider's way; a train the route can't place, or any without the route, is still
                left to its calls.
          - [ ] **A boarded train that leaves the leg** (a diversion, a short working): it's shown as
                lost and kept followed, since the rider is on it, not at the boarding stop to take
                another. Re-plan from where it goes instead, or, with location, from where they are.
        - [x] Start, the trip's on-the-way screen and End trip; the trip kept on the device and
              followed while the app is in the foreground.
        - [ ] Picking another train by hand ("I'm on this one"), until location can see it.
        - [x] **A ride is two steps, boarding it and getting off it** (maintainer, 2026-09-29): Next
              from boarding says the rider is on the train followed ("Ride to …" its own row).
          - [x] **On board by the rider's word, with the train followed still minutes away**: the
                train they're on is more likely the one at the platform than the one followed, but
                the trip kept the one followed (the next it thought they could catch). A followed
                train due more than a minute after the tap (`OnTheWay.ON_BOARD_GRACE`) is now let go,
                and the soonest due within a minute of it picked, as with none followed.
          - [ ] **On board by the rider's word before the train reaches the stop, on a loop**:
                the call at the boarding stop they boarded at is told by order, the first there with
                none of the ride's stops due since they boarded before it. A train still calling at
                the ride's last stop on its lap before, just ahead of the boarding stop, reads as the
                next lap, so nothing is dropped until that call has passed.
          - [ ] **Held at the boarding stop after being seen due off, through a refresh gap**: on a
                short ride the rider's stop can be due within a minute while the train still stands
                at the boarding stop. From then on its call there is theirs only within five minutes
                of when it was last seen, since a tight loop's next lap can come round inside the
                ride's planned time. Held more than five minutes longer between two refreshes (the
                app away), it reads as the next lap and the ride ends. The rider's location (still
                at the stop) would tell them apart, but not as the trip is built (Codex, PR #452): a
                rider on board by their word asks for no fix, and one only taken to be on board is
                seen at the stop as left behind a minute after the train was taken to leave, before
                its calls are read. Needs the followed train's calls read before that check: one
                still calling at the boarding stop, ahead of the ride, hasn't left them behind.
        - [ ] **The trip's screen as the route view, kept live** (maintainer, 2026-09-27: today's
              Next card and leg list for v1, this after): the route as the rider planned it, done
              legs hidden, a status line (stops left, next stop), the followed train marked, and
              the next leg's live departures. At a change, only the services safe to take, each
              with its live departures and platform. First version: the departures for the leg's
              own route, as planned. Then every departure from the same stop whose service reaches
              the leg's change point (the Circle and the Metropolitan, say): with delays, the best
              next train is often another service from the same platform. Smarter later (tbd): the
              one that arrives first, or only services with all the leg's stops in common. Asks
              only the leg's own stop (TfL's stops split by
              mode: `940G…` metro, `910G…` rail), never its interchange (`HUB…`), and from that
              leg's own source: TfL for a tube leg, National Rail only for a National Rail one.
        - [x] **Live departures for the rider's next step** (maintainer, 2026-09-27): the trip's
              screen shows the live departures that matter where the rider is now: the boarding
              stop's while walking to it or waiting, the change stop's while changing, from the
              leg's own stop and source (as in the route-view item above).
        - [ ] **Consider F3 for the trip card's ride line** (maintainer, 2026-10-03): the card shipped F2,
              "08:18 · 16 min · 4 stops" (dropping the next stop's name). F3 keeps both pairs matched as
              "08:18 · 16 min" and adds a quieter third line, "4 stops · next Tottenham Court Road", in a
              slightly larger font than the mock's. Try it on a device against F2.
        - [ ] **A train through the change, on the way** (maintainer, 2026-09-28): the trip list
              now offers a train that runs through a change as its own route, and drops a route
              whose change buys nothing. A trip already on the way keeps its planned change: when a
              train through it comes first on the next-step board, offer to take it instead.
        - [x] The main view's pinned card (the near-me list; a station page is its own look).
        - [x] "Get off soon" on its own channel (sound, vibration), the permission asked on Start.
              Only while the app is open until the foreground service below lands.
        - [x] **A "time to board" alert** (maintainer, 2026-09-27): a heads-up as the rider's train
              is about to arrive, on its own channel so it can be muted apart from "Get off soon".
              Done: two minutes before the train followed is due at the boarding stop, once for each
              train (`OnTheWay.shouldBoard`, kept on the trip as `boardWarned`), its header counting
              down to the train and lasting only while its answer is live (`CURRENT_FOR`, renewed
              silently by each fresh answer, down for good on a failed refresh); taken down once the
              rider is on board, another train is followed, or the trip ends (`boardStands`). Needs a
              device check of the countdown, the channel and a swiped one staying away.
          - [ ] **"Time to head for the platform"**: the same heads-up while the rider walks to the
                stop, when leaving now just catches the train. The walk is timed from the plan, and
                a train is followed only once the rider is taken to be at the stop, so this needs
                the walk's own time from where they are.
        - [x] **A "Route disruption" alert** (maintainer, 2026-09-29): a heads-up when a trip on
              the way learns something that may stop a leg the rider hasn't finished (a "coming
              leg" below): its boarding stop until its train is boarded, and its line and where it
              gets off until the rider does, so the one being ridden counts too. It keeps the rider
              from waiting for a train that won't come (a train through a change that never turns
              up, say).
              Named for what's known, not a verdict that the route is dead: the text states the
              fact ("Northern line: part suspended", "Change at <stop> may be missed"), and it opens
              the trip, which offers to plan again from where the rider is. It fires on signals of
              medium confidence or higher (tiers agreed with the maintainer, 2026-09-29). Its
              contract goes into SPEC's *On the way* with the change that builds it:
              - **High:** a coming leg's line closed or suspended (as the trip already counts it),
                or part closed or part suspended where TfL places it on the leg's own stretch
                (by the affected stops TfL names, kept with the alert's direction); a stop the
                route still has to reach closed or moved: where a coming leg boards or gets off, or
                where a walk ends (a change between stations, or the stop the route ends at), as
                the trip screen checks them (*Closure checks where the rider gets off* above); or
                planning again can't make the connection.
              - **Medium:** severe delays on a coming leg's line; a part closure or part
                suspension on it where it isn't known whether it covers the leg's own stretch; a
                train through a change still not predicted when the rider is a few minutes from
                its boarding stop.
              - **Low, never alerts:** a leg past TfL's prediction window (about half an hour), so
                no train predicted yet; minor delays; the last train predicted leaving too soon on a
                line not running every few minutes, which may only be where the predictions end
                (the trip list withholds such an arrival and plans again rather than call it
                missed).
              Only a fresh, successful answer is evidence: a check that failed or has gone stale is
              unknown, never a signal, so a TfL outage can't read as a train not predicted. A line
              or stop alert is a signal, at any tier, only where *Disruptions* would warn of it on
              the trip right now (a line's ⚠ on the leg's row, a stop's closure card), by the same
              rules, so the alert and the screen never disagree: not planned work before its start
              day (its ⓘ); not a stop notice outside its window; not one TfL scopes to the other direction from the leg (one with no
              direction known counts both ways); and not one the rider has dismissed, which comes
              back as it does there, when it escalates or TfL rewords it, or when planned work
              starts, since dismissing its advance ⓘ is separate from dismissing its ⚠.
              Planning again from where the rider is waits on *Explore how a trip recalculates
              mid-route* below: a re-plan today starts from the trip's first stop, which would judge
              connections the rider has already made or missed. Until that lands, the re-plan tier
              is out and the alert opens the trip without offering to plan again. Once it lands, a
              re-plan with no usable location starts from the stop the trip has the rider at next.
              It also waited on the trip on the way keeping its destination as chosen, done:
              `ActiveTrip.destinations` and `destinationIds` keep every stop of a station complex
              (or the place) the trip list planned to, so a re-plan asks for them all, not the one
              stop the route ends at. A trip kept before they were has none.
              Kept quiet: one notification per trip, updated in place as signals change, and taken
              down as soon as no signal remains, the trip ends, or it stops being followed (the app
              leaving the foreground, until the foreground service below lands), so it never
              outlives its evidence. It's posted with a system timeout (`setTimeoutAfter`) no later
              than its evidence goes stale, renewed by each refresh that still finds the signal, so
              it comes down even when the process dies with nothing left to take it down. Once per
              leg per signal; on its own channel, muted apart from "Get off soon". Only while the
              app is open until the foreground service below lands.
              Cost: every request it can make, each bounded; all £0 and well inside TfL's keyless
              ~50 a minute.
              - **Line status**, which the trip screen already checks. Confirm when building it
                whether the trip on the way has it too, else one batched request for the trip's
                few lines every 30 s while it's followed, a failure just leaving them unknown.
              - **The detailed status lookup** the status client makes the first time it sees an
                alert (`detail=true`, ~150 KB a line, not waited on: that refresh counts the alert
                both ways, the next by direction). Placing a part closure on the leg's stretch
                reads that same answer. It's the one large download: once per new alert while TfL
                answers it; while it fails, again a minute later, then after twice as long each
                time, up to half an hour, ~150 KB a line each time.
              - **Stop closures**, which the trip screen checks, shared with the list for five
                minutes. Confirm when building it whether the trip on the way has them too, else
                one request a stop (a bus stop's poles several to one) at most every five minutes
                while it's followed, a failure just leaving the stop unknown.
              - **Planning again**, only on a signal and only once the mid-route task lands: one
                re-plan per new or changed signal, however many legs raised it, never while one is
                in flight. A re-plan is the trip's own, so it asks the Journey Planner once per
                stop of the destination (about six to a large complex like King's Cross, SPEC
                *Trips with a change*). A failed one leaves that tier unknown and waits longer
                before the next (doubling from a minute, capped at five), so a TfL outage costs at
                most one such fan-out a minute, not one every refresh. No wakeup of its own: it
                rides a refresh that's already running.
              Privacy: the stops and lines it asks about say which route the rider is on, so they
              are user data (`docs/PRIVACY.md`). They go only to TfL, which a followed trip already
              asks about the same stops and lines, so no Play Data Safety category is new; but the
              policy's *On the way* names what a trip on the way asks TfL, so it names this polling
              before it ships. A re-plan sends the origin the mid-route task settles on (a stop, or
              where the rider is), so its Data Safety answer is that task's. Finer details are
              settled in the change that builds it.
              Done, the high and medium tiers without planning again (SPEC *On the way*): what's known
              (`RouteDisruption.signals`) is checked after each refresh (`RouteDisruptionChecks`, the
              trip screen's closure lookups now shared through `StopClosureChecks`) and posted by
              `RouteDisruptionAlert`, each signal heard once (`ActiveTrip.disruptionsHeard`). Needs a
              device check of the notification, its channel, and a swiped one staying away.
          - [x] **A train through a change still not predicted** as the rider nears its boarding
                stop (Medium, above): five minutes or less before they can board there, a fresh
                board at that stop listing no train of the ride's line its route doesn't send another
                way (`RouteDisruption.changeNear`, `unpredicted`). Read on the ride before for this
                alone, one request a refresh for those minutes; the trip's own board once they walk or
                change there. A bus whose route uses the other pole of the stop pair isn't on that
                board (the pick's own limit, *A train the board never listed* below).
          - [x] **A part closure or suspension placed on the leg's own stretch** (High, above):
                the direction lookup now keeps the sections TfL names each alert as shutting, each
                an unbroken run of its affected stops along an affected route, in its order and
                with its direction (`affectedSections`, `LineAlertDirections.sectionsOf`); every
                part closure under way is kept whole with its own (`LineStatus.closures`,
                `PartClosure.sections`). One with a section taking in two of a coming leg's calls
                in a row, the same way round, is High and named for itself
                (`RouteDisruption.lineSignal`, `PartClosure.coversRide`). Placed elsewhere, or not
                placed yet, it stays Medium.
          - [ ] **Placing a part closure on a bus leg** (Codex, #446): a bus leg's path is stop
                areas (`490G…`) while TfL's sections are poles, so `rideCalls` never finds two
                calls in a section and a bus leg stays Medium (the safe side). Unreachable today:
                every disrupted bus line in TfL's status (2026-10-01, 278 of 675) is a severity-0
                "Special Service", whose inferred label is never placed. If TfL starts wording bus
                part closures, map the path's areas to the route's poles (its line sequence, as
                the trip's cards already load) before `coversRide`.
          - [x] **A bus line's several alerts, each placed** (maintainer, 2026-10-03): every alert
                under way (`LineStatus.underWay`) is placed on its own words, each that may reach the
                ride its own signal, dismissed on its own; the line is left out once none does. The departure board's "behind
                the stop" still needs the line's sole alert: its verdicts key on the shown alert.
          - [x] **Planning again from where the rider is** (the re-plan tier, and the alert
                offering it): the trip's screen offers **Plan again from ‹station›** while something
                is known wrong ahead (`ActiveTripTracker.replanFrom`, `ReplanOrigin`), the trip list
                from there to the destination as chosen, its Start replacing the trip on the way.
                Needs a device check of the station chosen on a moving train.
            - [x] **Keep going** beside it: what's shown is dismissed for this trip only
                  (`ActiveTrip.disruptionsDismissed`), the alert taken down; something new still shows.
                - [ ] **Forget a Keep going once its alert has ended** (Codex on #519): the same
                      words coming back later in the trip stay let go of. Pruning needs a check known
                      to have read every coming line and stop, or a partial failure would bring back
                      what the rider let go of, with sound.
            - [ ] **Plan from when the rider gets there**: the list plans from now, so a route
                  leaving that station before the rider reaches it is offered too. Plan from the
                  trip's own time there (its train's call, or the walk's end) instead.
            - [ ] **"Planning again can't make the connection"** (the High signal above): plan again
                  in the background on a signal, and raise it when no route keeps the connection.
        - [ ] **Not to merge until the maintainer's Play declarations:** the ongoing notification
              with the app closed (a foreground service, which replaces the old *Step by step*
              item), and live location to see the train boarded and follow a bus.
          - [x] The foreground service and its ongoing notification (built; PR held for the
                declaration).
          - [x] Live location: a rider left behind at the boarding stop moves to the next train
                (built; PR held for the declaration).
          - [x] Live location: a rider seen along the ride while its train is awaited (at a later
                stop, or 400 m on toward where they get off) boarded, and is followed on the train
                of the ride's line that left the stop for them (maintainer, 2026-09-29).
            - [ ] **The followed train itself, with TfL behind on it**: a rider seen along the ride
                  on the very train followed, while TfL still shows it due at the boarding stop, isn't
                  taken as on it (its calls still name that stop), so the trip waits until TfL
                  catches up. Rare (TfL's lag is about a minute), but a train that has left could be
                  told apart by its calls moving on past the stop.
            - [x] **A train the board never listed**: the trains asked after were those the boarding
                  stop's board listed while the trip was shown, in memory only, so after a restart,
                  or for a train that came and went between refreshes, the rider seen along the ride
                  was on board by where they were seen with no train named. Done: with none of those
                  theirs, the board at the stop ahead of them is read (one request a fix, while their
                  train is unknown), its trains that take the ride told by their calls (`boardedOn`),
                  for a line whose stops are known by id and whose routes bring every train there
                  the ride's way from the boarding stop, soonest first until a train behind them bounds theirs: the latest past them when seen at a stop, only a lone one between
                  stops (*Two trains between the same two stops*).
            - [x] **A bus on the other pole of the boarding pair**: the board read is the pole the
                  Planner named, alone, for picking a train too, so another of the ride's bus routes
                  that stops only at the pair's other pole is never followed, picked or seen boarded
                  (Codex, PR #451). Reading the pair's other pole as well would find both, for one
                  more request a refresh while waiting (Codex, PR #383). Done: the pair's other poles
                  are read with the board, but only one serving a line whose route runs the ride from
                  there, or whose route isn't known (`RideLines.polesToRead`), so a plain pair costs
                  nothing more; each train counts on its own line's pole's board only
                  (`OnTheWay.listedFor`), for the pick, the trains seen leave, the change's "no train
                  predicted" and the trip's board, which heads each pole by its letter. A pole, or the
                  pair, that can't be read is said (`NextBoard.partial`), never taken for no bus there.
            - [x] **Two trains between the same two stops**: the train the rider is on is the
                  newest that left the boarding stop and isn't behind them, judged by the stop each
                  calls at next. A later train that has caught up to between the same two stops calls
                  there next too, and would be taken for theirs. Done, the honest half: seen between
                  stops, the newest train past them is theirs only once each older one of its line
                  that left after the rider could be at the stop is looked up (a request each, up to
                  three) and shown ahead of them (`OnTheWay.twinOf`), as trains of a line overtake:
                  past where they get off, or calling next further on where every way the line runs
                  there calls first at the newer's next stop (`OnTheWay.passes`; a fast train skipping it
                  may still be short of it). Otherwise, or with its lookup failed (said), neither is
                  named, the rider on board by where they were seen, on those trains' line, until a fix
                  at a stop tells. A train of another of the ride's lines isn't compared: its path's stops
                  aren't the line's to match by position.
              - [ ] **Timing would tell them apart**: the rider's train left the boarding stop after
                    the last fix still there and before the first clear of it, and fixes while
                    waiting could keep those two times. Not done, for what a station does to "clear":
                    the Planner can place a big station 200 m from where a rider waits, so a rider
                    still on the platform can read as clear of it, and their own train, due later,
                    would be ruled out. Safe where the stop is a point (a bus pole), or once clear is
                    judged against the station's entrances too (`StationPlaces`).
          - [x] **On board by position on another of the ride's lines** (Codex, PR #449): seen along
                another offered line's own way (#451) with none of its trains told as theirs, the trip
                still waited as it was, as before #449; only a sighting on the Planner's line put them
                on board by position. Done: they're on board on that line, kept as the line ridden
                (`ActiveTrip.vehicleLeg`, as a told train is), its stops counted on its own path
                (`OnTheWay.onBoardAlong`'s `on`).
          - [x] **A ride line whose check failed is said, not dropped silently** (Codex, PR #459):
                `RideLineChecks.running` left out a line whose route, status or stop-closure check
                failed, and the trip fell back on the Planner's line (#451), but nothing told the
                refresh: it read as current, for every caller. Done: the ride lines carry whether one
                went unchecked (`RideLinesNow.unchecked`). No train picked while waiting then fails
                the refresh (a train found is still followed); the change-board signal is unknown,
                never "no train predicted"; and a rider on board by position, seen off their line
                and placed nowhere, keeps their place with the refresh failed.
          - [x] **Update as soon as location changes** (maintainer, 2026-10-01): while a trip is shown,
                the rider is using the app, so timeliness comes first, within reason. Refresh on a
                location update rather than only on the ~30 s cycle, with a minimum gap between
                refreshes and a fallback timer when no update comes. Since a train is now matched only
                against a fresh fix, this is what decides how soon it's named. Done: with the app open
                and a fix wanted, location every 5 s while the rider moves 10 m; each fix of use
                refreshes the trip at once, at most every 10 s, in whichever loop follows it (the
                app's, or the service's); the 30 s timer stays the fallback (`TripFixes`).
          - [ ] **Moving, but location stale**: decide what the trip shows, and what it asks for,
                when the rider is taken to be moving (on board, or walking) and no fresh fix has come
                in for a while (underground, indoors, a fix refused).
          - [ ] Following a bus above ground by location (get off soon from where the bus is).
        - [ ] **A clock set back during a walk** (Codex, PR #521): a walk stays `Walking` while the clock
              says its time isn't up, so a device clock set back hours keeps any walk (the last included) on
              until it catches up. Re-anchor the walk's start to the new clock, as `atLeg` does, across all
              walks; the last walk's wait to be seen at the destination is already bounded to its window.
        - [ ] **No line wrapping for the next action at all** (maintainer, 2026-10-03): the trip card's
              step ("Walk to …", "Board 43 at …") now takes its places' common abbreviations to stay on one
              line and wraps to a second only if those don't fit. Consider never wrapping it: one line,
              shortened to the floor and then cut with "…", trading a fuller name for a card that never
              grows or shifts.
      - [x] **One-tap trips**: the near-me top bar's Directions button opens *To…* in one tap, with
            room freed by shortening the freshness stamp to "1 min ago" (maintainer, 2026-09-26).
      - [ ] **Better than a bare "est."** (maintainer, 2026-09-26): a leg past its live predictions
            boards "as the rider arrives" on a frequent line, marked "est." — a stopgap. Handle it
            better: a timetable- or headway-based wait, and inferring or knowing whether the rider is
            already partway along the route (ties in with *On the way*), so a later leg is timed from
            where they are rather than from the stop they started at.
      - [ ] **Consider a bus leg's timetable past its live predictions** (maintainer, 2026-09-27):
            a bus reached past its predictions boards on arrival when two predictions show it
            frequent, with a range up to a 10-minute wait; a bus seen once, or at night, still
            withholds the arrival. TfL's timetable (`/Line/{id}/Timetable/{stop}`, one free request
            per bus leg, cacheable for the day) would give the first scheduled bus after the rider
            gets there, at night too. Weigh against: a timetable misses stop closures and diversions
            that live predictions reflect (the maintainer's guess, 2026-09-27), and a bus in traffic
            runs off schedule.
      - [ ] **Prune a route that passes near a single-stop destination** (maintainer, 2026-09-27):
            routes through a station complex go when a route getting off there arrives no later
            (SPEC *Trips with a change*). A single stop has no complex, so a route passing a station
            steps from it and coming back isn't caught. Judge it by distance from the destination,
            which needs a position for each stop a route calls at (the Planner gives one only for
            each leg's ends) and a cutoff to settle.
      - [x] **Check a trip's boarding stops for disruptions** (Codex on PR 259, 2026-09-26): a trip
            fetched its lines' status but not its stops' own disruptions (a closure, a moved stop),
            so a leg's line page couldn't say "No disruptions reported". Fetched as the list does,
            with the stops it gets off at; the page vouches once both checks pass.
      - [x] **Honor dismissed alerts on a trip's cards** (Codex on PR 259, 2026-09-26): a line alert
            dismissed from a leg's page (or the list) is honored on that page, but the trip's own
            cards still show the line's ⚠. Apply the dismissals there as the list does, keeping
            a dismissed line's status out of the warnings without reading it as unchecked.
      - [x] **Check the stand a bus is placed on when the Planner named another** (Codex on #367):
            at a bus station, a line's route can put the bus at a stand of the same name other than
            the one the Planner named. The trip judges the route there, but asks only about the
            Planner's stands and a pair's poles, so such a route is never vouched for at that stop.
            The trip's model doesn't know the line routes the screen places buses by; it needs the
            placed stands handed to it, or its own copy of the routes, to ask about them too. Done:
            the screen hands it every stop it judges the routes at, placed stands and other lines'
            poles included, and the trip checks those it doesn't ask about itself.
      - [x] **Prune a trip's stop-closure dismissals once its own checks see the notice end**
            (Codex on #367): the list reconciles its dismissals after each check, dropping one whose
            alert has ended at a place it checked, so the same text recurring later shows again. A
            trip now does too, for each stop it checked as its own place only (`stopDismissalCheck`,
            shared with a trip on the way, PR #441), as the list's own check of a journey's
            destinations does. A dismissal made at an interchange or stop area is still left to the
            list: the trip looks at only some of its stops, which runs into the list's own open
            question (*Prune a hub-keyed dismissal only when every member was checked*).
      - [ ] **Arrows between a route's pills** if space permits (dropped for width, 2026-09-26).
      - [ ] **Show a route's fare** (maintainer, 2026-09-27). The Planner response a trip already
            fetches carries one per journey (`journeys[].fare`, ignored today): `totalCost` in
            pence, and per fare its zones, `peak`/`offPeak`, `chargeLevel`, `isHopperFare` and the
            taps. No extra request. Open: where it fits on a card (width is short), whether to show
            the fare that applies now or both peak and off-peak, caps and Hopper, and what to show
            when a journey has none (some mixes, e.g. National Rail, may lack it).
      - [x] **Shared-leg pill**: a leg several routes serve alike (buses 43 and 134 share the stops
            to Highgate station) as one diagonally cut pill carrying both routes, and a live row per
            line in the one card (maintainer, 2026-09-26).
      - [x] **Every line between a ride's two stops** (maintainer, 2026-09-27): each ride also shows
            the unnamed lines serving its own boarding and getting-off stops (strict same stop, a
            road's two poles as one), as equals on its pill and with rows of their own.
      - [x] **On the way with a ride's other lines**: a started trip follows a train of any of
            the ride's lines its cards offer (maintainer, 2026-10-01: the cards' rule, `RideLines`
            via `RideLineChecks`), picked or seen boarded, each judged against the ride as its own
            line runs it, and keeps that line's ride to look the train up on, since TfL answers
            for a train on one line only (Codex on #383).
      - [x] **Take a branch off the plan** (maintainer, 2026-10-05; SPEC *On the way*): the trip's
            board lists the ride's line's branches that turn off its way, from the route, grayed, and
            **Take this one** reroutes the trip with a change where the branch turns off (`OffPlan`).
      - [ ] **Notice a branch taken without a tap**: a rider who boards the other branch's train
            without saying so is still lost on it once it turns off.
      - [x] **Change branch, from the trip's screen, riding or waiting** (maintainer, 2026-10-05):
            **Other routes** under the board, and under the step on board (riding, or lost on a train
            that changed its branch), lists the branches still ahead and reroutes from the one picked,
            keeping the rider on their train when on board.
      - [x] **Other lines from the platform in Other routes** (maintainer, 2026-10-05): a line on the
            boarding stop's board that runs the ride's way and then turns off it (the Circle beside the
            District) is a row under its own pill, and taking it rides that line to where it turns off.
            One that reaches the rider's stop was already the board's own.
      - [ ] **Change where both branches call, not only at the fork**: a Bank-branch rider bound for
            the Charing Cross branch can change at Euston as well as Camden Town; offer the choice.
      - [ ] (Consider, maintainer 2026-10-01) **A train held short of the stop reads "0" for
            minutes.** TfL keeps re-predicting a train held between two stations as about to
            arrive, so a trip's "Due in 0 min", its board and the departures list all read "0" until
            it moves; in one report a tube train held under minor delays stayed at "0" for over
            three minutes while the platform sign gave 3 min. TfL's feed doesn't say a train is
            held, so it would be inferred: the same train still due within the minute across
            refreshes a couple of minutes apart. It would stay where it is in the list (it is the
            next train) and only its number would change, to wording the maintainer approves.
            Mind that TfL sometimes serves an answer a minute old, so a train pulling in normally
            mustn't flash as held. Waiting on more reports to see how often it happens and what
            threshold fits, before deciding to build it.
      - [x] **Rank a route by any of a ride's lines** (Codex on #309). A ride whose Planner line is
            closed or unchecked no longer sinks its route while another line that times it, checked
            as running from stops checked open (`RideLines.othersTime`), can take it; the Planner's
            line's own trains, and its timetable, then count only if it's checked as running
            (`RideLines.vouched`). A line whose latest status check failed stands in for none.
      - [x] **Judge a ride's freshness by any of its lines** (Codex on #309): a route's freshness was
            judged at the Planner's pole alone, so when that pole failed but another line's pole was
            current, the frequent-service fallback was withheld though it could stand. Done: the ride
            is current while any of its timing lines' stops refreshed (`rideCurrent`), and only trains
            from stops that refreshed show it running every few minutes (`rideRefreshed`), so the
            failed pole's held predictions are left out of that judgement.
      - [ ] **Merging by place, parked** (#306, maintainer, 2026-09-27): the same idea across an
            interchange's stops (the 43 and 134 at Archway), set aside for strict same-stop; its
            branch holds the place-based version if that's revisited.
    - [ ] **Star a From… To… trip as a journey**: the trip has no single starred line, which
          `StarredJourney` places its ends on, so it needs a line-free journey first.
    - [x] **To… from the near-me list** (maintainer, 2026-09-24): the overflow's *To…* starts
          from the list's default stops plus any within 0.2 mi (`hereOriginIds`). *Superseded
          2026-09-26: To… plans a trip from the rider's nearest stop (SPEC "Trips with a change").*
  - [x] **Find a station from the location gate**: a *Find a station* button under the gate's
        own action, since the search needs no location and helps most a user who denied it (Codex).
- [ ] **Search for a stop by name or line, and pin it.** Beyond nearby discovery, let the
      user type a **stop/station name** (TfL `/StopPoint/Search`) *or* a **line**
      (`/Line/Search/{query}` — the query is a path segment, not a `?query=` parameter like the
      stop search — then that line's stops via `/Line/{id}/StopPoints`) — SPEC *Finding stops*
      requires both
      discovery paths, so a user who knows the line but not a stop name isn't stuck. Pick from
      the matches and add to watched — for stops they care about that aren't near them now
      (home, work, a regular destination). Complements "near me now" and star-to-pin.
      **Privacy:** the typed query is sent to TfL's search endpoints — a new off-device input
      beyond today's coordinates/stop-IDs, so SPEC *Privacy* is updated to disclose it (done)
      and the Play Data Safety answers account for it when this ships. Requested 2026-09-19.
- [x] **Re-locate the "near me now" list on demand, not just on first open** — shipped as
      milestone C (PR #70). **Reversal of the original design, recorded here:** this item first
      called for a dedicated crosshairs "Update location" toolbar button and insisted
      re-location stay **out** of the departures refresh path (the then-D1 "location off every
      refresh"). That is not what shipped, deliberately: the crosshairs button (PR #43) is
      **removed**, and **refresh + pull-to-refresh now re-locate** (force a fresh fix, then
      re-resolve the nearby set) as well as re-fetching departures. D1 was updated to match —
      location stays off the *background/widget* refreshes but a *manual near-me* refresh takes
      an on-demand foreground fix (later extended to a foreground return too — see the
      foreground-relocate item below). The force-fresh requirement this item raised is met
      (`relocate()` → `current(forceFresh = true)` → `FixSelection.resolve(forceFresh = true)`,
      which bypasses the instant fast path entirely, cache only a bounded fallback). Cost/battery note stands: each re-location is one more
      on-demand coordinate to TfL (same recipient/category, £0, negligible on a user tap).
      **Remaining follow-up** is the *automatic* distance-triggered version (milestone A) and
      the same-set-metadata gap — both under *Decisions needing review* below, not here.
  - [x] **Nearby per-mode coverage (crowd-out)** — landed, PR #39. The nearby list shows a
        line once from its nearest stop (dedupe, PR #36) and now mixes in the nearest stop of
        each mode within ~1 mi so a denser mode can't crowd out another — the one Tube within
        reach no longer falls outside the nearest bus stops and vanishes. Superseded by the
        two-tier near-me (2026-09-21), which selects the nearest two clusters *per mode* and pages
        the rest behind "More" (SPEC *Finding stops → Near me now*).
  - [x] Reduce the nearby request burst at a **dense interchange** — the two-tier near-me
        (maintainer, 2026-09-21). The eager set is the nearest **two clusters per mode**, so
        `MainViewModel.refresh()` fetches sharply fewer stops at a dense corner than the old
        inner-ring-all set, which could approach TfL's ~50 req/min keyless budget at a busy
        junction (Codex, PR #39). **Caveat (Codex, PR #85):** the cap bounds the *cluster* count,
        not the request count — a bus cluster is one arrivals request per lettered pole, so a
        single large junction cluster is still many requests. A hard per-cluster fetch budget is
        the follow-up below.
  - [ ] **Cap the eager fetch by request count, not just cluster count** (Codex, PR #85). Two
        clusters per mode bounds how many *clusters* are eager, but a large bus junction cluster
        flattens to one arrivals + one disruption request per lettered pole, so two big junctions
        can still exceed the keyless TfL budget and make the near-me refresh slow or rate-limited.
        Apply a stop/request budget within the eager clusters (which poles of a big junction to
        fetch, and how to present the rest) — a product decision on junction presentation, so its
        own change. Until then SPEC/`NearbySelection` say plainly the cap bounds clusters, not
        requests. **Impact (est.):** caps worst-case requests per refresh to a fixed ceiling — bounds
        the tail at a dense multi-junction corner (two big junctions could otherwise be dozens of
        poles); average case unchanged.
  - [x] **Don't auto-fetch route-less stops** (maintainer bug report, 2026-09-23). A stop TfL lists
        no routes for counted as its own mode, so its nearest two were eager — at a big interchange
        that fetched unserved stops a kilometer off. They now wait behind the generic "More stops".
  - [x] **A quick retry refetches only the missing stops; closure checks are reused 5 min**
        (maintainer, 2026-09-23). A stop fetched <50 s ago (on any screen, since 2026-09-26) is carried over, so a retry after a
        rate-limited refresh fits the budget left; a stop's closure check is reused 5 min. In
        memory only.
  - [x] **Cache more, fetch less** (maintainer, 2026-09-23). Line status is reused 90 s (checked
        every other auto-refresh); a nearby lookup is reused for a day within 150 m (last four
        places, kept in the never-backed-up cache dir); the timer refreshes stops past 500 m every
        other minute; the widget skips a stop the app fetched <50 s ago. Running the nearby lookup,
        line status and departures in parallel is the next lever, not yet done.
  - [x] **Routes and stop areas are kept a day** (maintainer, 2026-09-24). Route sequences and
        stop-area poles were held for the process only; they now persist for 24 h in the
        never-backed-up cache dir, so a route page or journey card after a cold start needs no
        request.
  - [x] **Auto-fetch only within walking reach** (maintainer, 2026-09-23). Eager is each mode's
        nearest two clusters within 500 m; a mode with none that close gets its single nearest out
        to the mile. At a big interchange a second Overground station 1.3 km off no longer loads.
  - [x] **Location fix at a big station waited the full 10 s timeout** (maintainer bug report,
        2026-09-23). Providers were asked one at a time, so indoors fused and GPS each ran out
        their 4 s bound before the network provider was asked. They're now asked at once: an
        accurate fix wins on arrival, a network fix after a 2 s grace.
  - [x] **A searched central station's page failed whole with a timeout** (maintainer bug report,
        2026-09-25). An uncached 1-mile stop search in the City took TfL 7–11 s to start
        answering, past OkHttp's 10 s read timeout. That search now waits up to 30 s, and asks
        only for the Direction properties it reads, cutting its response by about two thirds.
  - [x] **A National Rail route page said "can't reach TfL"** (maintainer bug report,
        2026-09-25). A National Rail line's route sequence (~600 KB) took TfL 4–15 s to start
        answering when uncached, past the same 10 s timeout. It now waits up to 30 s too.
  - [x] **A cold load waited for its slowest stop** (maintainer, 2026-09-25). Each stop now shows
        as it lands; one still out is a collapsed "Loading" card where it will go. Only the whole
        batch is saved.
  - [x] **Don't make a cold load's list jump** (maintainer, 2026-09-25). A "Loading" card on
        screen when its stop lands stays a card, now "Tap to see" (a dash if nothing's running),
        and opens in place on a tap; one off screen expands. The card keeps its list key and
        slot, so the scroll anchor holds.
  - [ ] **Say what a loading card waits on** ("National Rail" vs TfL) if the plain "Loading"
        proves unclear; the client would need to expose which stops take a Darwin board.
  - [x] **A line TfL doesn't know still reads "can't reach TfL"** on its route page, with a
        Retry that can never work (landed). A 404 is now its own `TflException.NotFound`: the route
        page says the stops are unavailable with no Retry, and a line-status 404 is remembered for
        the session instead of asked every refresh. The `lnr-wmr` case itself was our id, not
        TfL's: a board's line id now comes from the operator's code (`LM` →
        `west-midlands-trains`), which also stops Northern trains taking the tube's `northern`.
  - [x] **A "More" tap fetches only the newly revealed page, not the whole set** (landed). `reveal()`
        no longer calls `refresh()`; it fetches just the stops not already shown and merges them into
        the current `Loaded` via `fetchIncremental`, persisting the widened set only when the fetch
        brought fresh arrivals. The per-stop fetch + line-status logic was extracted into `fetchBatch`
        so the whole-set refresh and the incremental reveal share one path and can't drift. A newly
        revealed stop whose fetch fails is absent and the reveal is flagged partial, never an Error —
        the existing snapshot stands. `newStops` is computed as `fetchedStops` minus what's on screen,
        so a quick double-tap self-corrects (a superseded tap's stops are picked up by the next).
        **Impact:** the Nth "More" tap drops from re-fetching every shown pole to just the newly
        revealed page — on a list already expanded a few pages, roughly a 60–80% cut on that tap;
        steady-state auto-refresh unchanged (it already fetches the whole shown set once).
  - [x] **A shared client-side TfL limiter — best-effort throttling toward the budget**
        (2026-09-22). The items above *reduce* request demand; none *bounds* it, so a future caller
        (a new surface, a tighter refresh interval) can still push over. Add a token bucket sized to
        the active budget — ~50 req/min keyless, ~500 with a user `app_key` (below) — that
        delays/queues when empty rather than firing and taking a 429, up to a bounded wait, then
        surfaces the honest rate-limited state (SPEC principle 2) instead of hanging. It sits below
        the render path (network never renders), so the wait only defers a background refresh. Two
        limits to state plainly rather than overclaim a "hard guarantee" (Codex, PR #101): (1) it must
        be a **single shared** bucket, not one per client — `MainActivity` builds separate discovery
        and departures clients and `WidgetRefreshWorker` its own, so per-client buckets would each
        consume the full allowance; and (2) an in-memory bucket **resets on process death** (incl. a
        WorkManager restart) while TfL still counts the prior calls, and won't span a separate widget
        process — so this is best-effort throttling that keeps normal operation within budget, not an
        absolute cross-restart guarantee. **Persisting the accounting across restarts** (a small
        on-disk ring of recent request timestamps) would close the restart gap and is worth doing
        *if it stays cheap* (maintainer, 2026-09-22) — otherwise leave it best-effort. The case that
        makes it matter is a **cold start right after an app update** (maintainer, 2026-09-22): a
        fresh process refreshing every watched stop at once, with no memory of the pre-update spend,
        is exactly when a burst hits the limit and the first post-update experience is a rate-limited
        screen — so **staggering the cold-start fan-out** (and/or persisting the accounting) is a
        real mitigation, not just a nicety. Highest-value
        pairing for the next PR is this + the incremental reveal fetch above (the throttle + the
        biggest single demand cut). **Impact (est.):** total requests unchanged; bounds the app's
        in-process outbound rate toward the budget, turning a likely 429 storm at a dense corner into
        staying within budget in the common case; the residual is the process-restart window above.
        **Shipped** as an in-memory shared token bucket: `domain/TflRateLimiter.kt`
        (`TokenBucketRateLimiter` — burst up to capacity, then paced at the refill rate, which is
        what staggers the cold-start fan-out; a wait past a bound surfaces `RateLimited`), the one
        `SharedTflRateLimiter.instance` wired into all three client construction sites, gated inside
        `KtorTflClient.tflRequest`. Saves the two deferred pieces for follow-ups below.
  - [ ] **Persist the limiter's accounting across restarts** (item-6 follow-up, 2026-09-22). The
        shipped bucket is in-memory, so it resets on process death / WorkManager restart while TfL
        still counts the prior calls, and doesn't span the widget process — the cold-start-after-
        update window. A small on-disk ring of recent request timestamps, reloaded at start, would
        close it. Worth doing **only if it stays cheap** (maintainer, 2026-09-22) — a DataStore
        read/write on every request is not; a periodic/batched flush might be. Otherwise leave it
        best-effort.
  - [x] **Size the shared limiter to the active budget from the user `app_key`** (item-6 follow-up,
        2026-09-22). Done with the Settings paste path: `KeyedTflRateLimiter` holds a keyless
        (~50/min) and a keyed (~500/min, SPEC D7) bucket and selects between them **per acquire** by
        whether a key is active, so a mid-session paste or clear re-sizes at once (no process
        restart). The app's clients read the process-wide `UserApiKeySetting`; the widget worker
        passes its own loaded key so its request and its throttling share one source.
  - [x] **Batch a junction's poles' closure checks into one request** (2026-09-22; landed
        2026-09-23). Verified against the live API: `/StopPoint/{ids}/Disruption` takes a
        comma-separated id list **without** `getFamily` (TfL rejects family for >1 stop) and returns
        a flat array, each entry naming its pole (`atcoCode`), so per-pole coverage holds. Bus poles
        now share one request per 20; stations keep their family-walking request. Side fix: a pole's
        family is its whole junction, which had pinned a sibling's closure on an open pole.
        **Arrivals stay one request per pole:** `/StopPoint/{ids}/Arrivals` 404s on a list, and a
        stop area's (`490G…`) own arrivals come back empty — so a P-pole junction costs P + 1, not
        2P. A per-cluster arrivals budget remains the lever for the arrivals half.
  - [x] **Log a slow or retried request, and a wait for a request slot** (maintainer bug report,
        2026-10-03). A near-me load's fetch line read ~10 s with nothing failed, and the two nearest
        bus places landed as cards while a farther station's rows were already up: the total alone
        couldn't say which request held them. Each call now logs, when over 2 s or when a connect had
        to be retried, its endpoint (identifiers left out), connection / first-byte / read times and
        any failed connect's address family; the shared pool logs a wait of 0.5 s or more for a slot.
  - [ ] **Don't hold a stop's times for its closure check** (same report). A stop lands only once
        both its arrivals and its closure check are back, and all of a junction's poles share one
        batched check, so one slow check holds back every nearby bus place together, and the
        never-jump rule then leaves the nearest ones as "Tap to see" cards below nothing the rider
        needed. Show the times as they land with the closure still "Checking…" (as line status
        already does, SPEC *Freshness → Cold load*), and let a closure found later mark the stop;
        principle 1 still holds, since nothing reads as verified-open until the check is back.
        Confirm against the new slow-request log first: it should name `StopPoint/…/Disruption`.
  - [ ] **A prioritized request queue across TfL and National Rail** (maintainer, 2026-10-03).
        Today the pool is one fair 10-slot queue for TfL alone: every TfL request in the process
        queues on equal terms, and National Rail boards skip it. Instead, one queue for both sources
        with two simple tags: **foreground or background** (on screen, or the widget, a prefetch, a
        lookup nobody is looking at yet) and **departures above closures and the rest** (line status,
        route sequences, timetables, alert directions, hubs). Foreground departures go first; a
        waiting background request still ages into a slot; each source keeps its own rate limit.
        **Needs "Don't hold a stop's times for its closure check" first**: while a stop waits for
        both, a lower-priority closure check would only delay the departures it's paired with. Only
        matters when the queue is full (a normal near-me load is under 10 requests, a big station
        ~20); size it from the "waited N ms for a free request slot" log lines.
  - [ ] **Shorter connect timeout** (same report). OkHttp's default is 10 s, so a connect that
        stalls (a dead IPv6 route after a network change) costs 10 s before OkHttp tries the next
        address. ~3 s would fall over sooner without touching slow-to-answer endpoints, whose read
        timeout is separate. Only if the slow-request log shows failed connects.
  - [x] **A revealed stop whose first fetch fails isn't in the widget's polling set** — *moot: the
        reveal was deleted (see "Delete the now-unreachable 'More' reveal path"), so there are no
        revealed stops.* (Codex P2, PR
        #87 — deferred there). When a newly revealed stop's first arrivals request fails with no prior
        while an eager stop succeeds, the authoritative save persists only the merged (eager) stops, so
        the revealed stop is absent from the saved snapshot — and `WidgetRefreshWorker` derives its
        polling ids from that snapshot, so if the app closes before a later in-app refresh succeeds,
        the widget never polls or shows that revealed stop. In-app it's fine (the key stays in
        `revealedKeys`, so the next refresh retries it); the gap is widget-only and narrow (first
        fetch fails AND app closed before any successful in-app refresh). It is **pre-existing** to the
        prune-persistence redesign (reveal has always used the authoritative-only save; reveal prunes
        nothing). A proper fix persists the revealed *identity* even before it has data — a placeholder
        "known but unfetched" entry the widget worker would poll — which is a change to the snapshot
        model (today a stop with no arrivals, no disruption and no prior is dropped from the snapshot,
        by design). **Tied to the widget-mirrors-current-view vs eager-only decision** (see *Decisions
        needing review*): under eager-only the widget never carries revealed stops, so this moots.
        Settle that decision first, then fix or drop this accordingly.
  - [ ] **Coarsen the cluster key so one logical station's several naptans merge** (maintainer,
        2026-09-21, screenshot). TfL's `stationNaptan` is more granular than the logical station
        at a big multi-operator interchange: St Pancras International has separate naptans for its
        National Rail, Underground, and low-level/Thameslink parts, so the near-me list shows
        three "London St Pancras International" headers repeating one alert. Merge them into one
        cluster **without** collapsing genuinely distinct adjacent stations — TfL's hub (`HUBKGX`)
        over-merges King's Cross with St Pancras, which the maintainer wants kept apart, so the
        right granularity sits between `stationNaptan` and hub (needs a TfL-data look: hub id vs.
        a name/parent normalization). Its own PR.
  - [x] **The "More" reveal — page the *more* tier on demand** (landed — the deferred half of
        PR #85, in its follow-up). A per-mode "More" control at the foot of the near-me list pages
        that mode's next clusters on tap (`NearbySelection.nextReveal`, a bounded few per tap) and
        merges them beside the eager ones; a cluster serving two modes appears under each mode's
        "More", and a modeless overflow cluster gets a generic "More stops". **A revealed expansion
        survives a relocation:** the retained `MainViewModel` owns *both* tiers (the eager tier
        updatable in place), keyed — via `Ready.clusterSetKey` in `MainActivity` — on the whole
        nearby cluster set (order-independent), so a reorder or an eager/more-boundary shift keeps
        it; a dropped revealed cluster (or a departed pole) is pruned synchronously and the reduced
        set persisted even on a non-authoritative refresh, so it can't linger for the widget worker
        to resurrect. The widget mirrors the app's current view — see *Decisions needing review*.
        This closed the two roots the seven PR #85 rounds exposed (in-app retention; disk/widget
        persistence).
  - [x] **A "More" tap reaches through routes already shown to the first new one.** The near-me
        list shows a route once from its nearest stop, so paging the next cluster(s) when they only
        repeat routes already on the list surfaced nothing — the tap looked dead, and only a later
        tap (reaching a stop with a new route) worked. `NearbySelection.nextReveal` now takes the
        routes already on screen and pages *through* a redundant run to the first farther cluster
        that adds a new route (still a distance-ordered prefix, so nothing is permanently skipped;
        it falls back to the bounded page when nothing remaining adds a route). The shown-routes set
        is read from the live rendered snapshot, not eager stops' declared lines, so a declared-only
        line doesn't mask a farther stop that actually shows it (Codex P2, PR #98). Each tap is
        bounded to `NearbySelection.MAX_REVEAL_PER_TAP` clusters so a long redundant run can't fan
        out an unbounded arrivals+disruption burst past TfL's keyless budget (Codex P1, PR #98).
        Within the ~1 mile reach only — reaching *beyond* the reach is the radius-expand rider below.
  - [x] **Only buses keep a "More" button** (maintainer, 2026-09-25). The farther-station cards
        offer the next station of each line the list doesn't serve, both ways and out to 3 mi, so
        the Tube, DLR, Overground, Elizabeth line, tram and rail "More" buttons only duplicated
        them. Coach, river-bus and the generic "More stops" (a modeless, mostly route-less stop)
        went with them; "More bus stops" stays, since buses get no farther card.
  - [x] **Farther bus cards replace "More bus stops"** (maintainer, 2026-09-25). A bus place in
        the *more* tier that adds a route the list doesn't show gets a collapsed card naming those
        routes, at most four, below the station cards within a mile. Offering them costs no request.
  - [x] **A bus card names every route a tap would show** (maintainer, 2026-09-26). It used to name
        only the routes no nearer card named, so a card read "N20" and opened to a 234.
  - [x] **A bus place next to a station on the list wins a tie** (maintainer, 2026-09-26): a pole
        within 150 m of a shown station claims its routes before nearer places, so the station's
        own stops beat one that is nearer as the crow flies but a longer walk.
  - [ ] **Consider showing every route a stop carries** (maintainer, 2026-09-26, undecided). A bus
        card names only the routes a tap would add; the rider may want the whole set — always, behind
        a toggle, or at least on the opened card or a tap on the stop's header. The catch: the opened
        card leaves out a route a nearer stop already shows, so a card naming it would promise a row
        that doesn't appear unless the opened card repeats it too.
  - [ ] **Consider when a night route counts** (maintainer, 2026-09-26, undecided). An N route
        alone can earn a bus card by day, so the card shows "N20" and nothing is running. Options:
        an N route counts only at night (~11pm–6am London time), never, or — the maintainer's lean,
        not settled — also 6am–6pm, so a rider going out for the night sees it's there. Free either
        way (TfL's night-only routes all start with N; 24-hour routes have plain numbers).
  - [ ] **Consider a cap per route and direction, with one "More" that raises it** (maintainer,
        2026-09-25, undecided). Show the nearest two places per unique route, direction and
        orientation of each mode. A single "More" at the very foot of the list raises that cap, and
        what the higher cap lets in arrives as tap-to-load cards, like today's farther cards. Keep
        both a count (small pages) and a distance limit (the mile for buses, 3 mi for stations).
        Direction is the one the list already splits rows by; the cap just keeps it.
  - [x] **Delete the now-unreachable "More" reveal path** (landed). Nothing called
        `MainViewModel.reveal` once the bus cards replaced the last "More" button, so it went with
        `moreState`, `revealedKeys`, `fetchIncremental`, the revealed-cluster handling in
        `reconcile`, `NearbySelection.nextReveal` / `revealableBuckets` / `MAX_REVEAL_PER_TAP` /
        `BUS_MODE`, and their tests; the relocation-prune tests that set up a revealed stop now use
        an eager one. The *more* tier stays: the farther bus cards and the terminating-service check
        read it. Behavior unchanged. The two reveal follow-ups below are moot with it.
  - [x] **Decide "More" progression from fetched/rendered rows, not declared metadata** — *moot:
        the reveal it tuned was deleted (above).* (Codex P2,
        PR #98). `nextReveal` decides how far to page from cluster *declared* lines, before fetching —
        so a nearer cluster that declares a not-shown route but returns no live departure is counted
        as the productive stop, the page stops there, and a farther cluster that actually has a live
        new route isn't reached (a residual dead tap in that arrangement). It can't be fixed pre-fetch
        (declared lines are the only signal a `more` cluster has until fetched); the class-deleting fix
        is **fetch-then-decide** — reveal incrementally, fetch, check whether a new *rendered* row
        appeared, continue if not. That is the same redesign as the two request-burst items below, so
        do them together.
  - [x] **Don't fetch a "More" cluster that only repeats routes already shown** — *moot: the reveal
        it bounded was deleted (above).* (Codex P1 follow-up,
        PR #98). Reaching through a redundant run reveals — and so fetches arrivals+disruptions for —
        clusters whose rows the near-me dedupe then hides, spending requests for nothing. Skipping the
        fetch for a cluster whose *declared* routes are all already shown would cut the burst, but a
        declared line isn't proof of what shows live (and skipping risks missing an opposite-direction
        row the metadata can't reveal), so it needs care. **Impact (est.):** saves the wasted
        arrivals+disruption on each reveal-through redundant cluster — e.g. a tap that spans 3
        redundant clusters before the new one saves ~6 requests; variable, biggest on dense corridors.
        Pairs with the eager request-count budget
        and the incremental-fetch item (fetch only the newly revealed page, not the whole set) — all
        three bound the near-me request burst.
  - [ ] **"More" reveal riders** (the reveal was deleted, above, so each would need a new "More" or
        ride the farther cards): a **widget "More"** that just
        opens the app (the widget can't expand in place; keep widget + main-screen rendering one
        parameterized implementation, and consider hiding "More" on the widget); **line hints**
        (a tappable "VIC" chip for a line nearby but not eager); and a **radius-expand chip**
        (widen the TfL query radius per tap, distinct from paging clusters already within range).
  - [ ] Use measured `Location.accuracy`, not just provider name, on **both** the cached
        fast-path and the fresh-fix waterfall. `AndroidLocationProvider` classifies a fix as
        accurate by provider (GPS/fused, PR #38), which is a proxy: a fused fix derived from
        Wi-Fi/cell can be less accurate than an older GPS fix. So on the cached fast-path a
        recent fused fix can short-circuit, and on the fresh waterfall a prompt fused fix is
        accepted, both without preferring a more accurate GPS fix (Codex, PR #38, both paths).
        Deferred because it is below the resolution the fix targets — #38 exists to stop a
        ~1 km network fix reading a stop half a mile away as nearest, and a fused fix is tens
        of meters, ample for the nearest stop — and choosing an accuracy threshold (what
        counts as "insufficient", whether to wait for GPS and how long) is device-tuning the
        sandbox can't validate and a change to the maintainer-approved provider-name design.
        Preserve `Location.accuracy` through both paths and prefer the most accurate fix;
        settle the threshold and the latency trade on a device.
- [x] **Star a journey** (maintainer, 2026-09-23). Two stations on one line, starred by tapping a
      station on a route page's stop list; cards atop the near-me list show that line's trains from
      the nearer end that call at the other. The heading's ⇄ swaps direction, and a tap on the
      heading opens the journey's own view (Swap direction, Unstar journey, trains headed by
      platform; maintainer, 2026-09-24). Direct and rail only (SPEC *Journeys*).
  - [ ] **Settle the heading's ⇄ tap target.** It is a compact 24dp target (below the 48dp
        guideline) while the control is tried out (maintainer, 2026-09-24); keep, enlarge, or drop
        it once judged on a device.
  - [x] **A discoverable way to star a journey.** A dismissible tip atop a starrable stop list
        says a long press on a stop stars the journey there (maintainer, 2026-09-24).
  - [x] **Star from the stop list by long press, not tap** (maintainer, 2026-10-05, after a stray tap
        starred a journey that then pinned to the widget): no visible button on each row; the tip
        now says a long press stars.
    - [ ] **What a plain tap on a stop does** — nothing for now; the stop's own departures is the
          candidate.
  - [x] **Starred journeys in Settings** (maintainer, 2026-10-05): listed by their stops and line,
        each with Remove.
  - [ ] **Rename a starred journey** — needs a label in the stored file (a schema version bump, so
        an older build can't drop it on its next write).
  - [ ] **Converge "journey" and "trip"** (maintainer, 2026-10-05: "longer term we'll need to
        converge those"): a starred journey is a direct segment, a trip a planned route with changes;
        a starred trip with a change would make them one thing to the rider.
  - [x] **Bus journeys, and a journey as a segment on any line** (maintainer, 2026-09-23: "really
        I'd like to star the segment", e.g. two stops shared by the 43 and the 134). A journey is two
        stops, not a line; the card shows every line from the origin that calls at the far end. The
        way back is placed on the starred line's route by stop id, then TfL stop area, then name,
        then the nearest stop within 400 m (a stop served one way only).
    - [x] **The way back's own page shows the same star.** A route page places each saved journey
          on its route like the card does, so the return poles show (and toggle) the existing
          journey rather than star a second one.
    - [x] **Journeys lead the widget too** (maintainer, 2026-09-23). The app saves each origin
          and its far-end-calling departures (line, destination, branch) with the widget snapshot;
          the widget pins them and hides a journey-only stop's other rows.
    - [x] **One copy of the widget's pins.** Each check is applied to the stored pins in one step
          and the app's saves never write them, replacing the per-screen copy (and its restore,
          re-save and ordering machinery) that kept racing other writers.
    - [x] **Don't repeat a journey card's rows in the near-me list** (maintainer, 2026-09-24): a row
          the card shows in full is left out below it.
    - [x] **Other poles in the origin's stop area.** A bus journey also boards from a pole beside
          its origin (same stop area) whose line reaches the far end; its buses show under that
          pole's heading and the widget pins them from it.
    - [x] **Change at a fork** (maintainer, 2026-09-24). When only the other branch runs from the
          origin (Northern line trains to Edgware, none to High Barnet) and no direct train is due,
          its trains show under "King's Cross ➔ Camden Town (for High Barnet)", the last stop it shares
          with the route to the far end. Rail only; the widget stays direct-only.
    - [ ] (Idea, maintainer 2026-09-24, not planned) **Frame a change around the journey.**
          Alternatives to the "King's Cross ➔ Camden Town (for High Barnet)" heading: head it
          "King's Cross ➔ High Barnet via Camden Town" and show the train's destination as Camden
          Town (where the rider leaves it) rather than its terminus; or prompt the rider to get off
          at Camden Town. The prompt needs reliable location tracking during the ride, which isn't
          on the roadmap (a SPEC non-goal today: no background location).
    - [x] **Hold back faraway journeys** (maintainer, 2026-09-24). A journey more than a mile
          from both ends of a confirmed fix waits behind a "Faraway favorites" button at the foot
          of the list, unfetched until tapped for that nearby set; the widget never pins one.
    - [x] **A relocate that flips a journey's direction refetches once.** A same-set relocate
          refreshed with the journey stops the screen last reported, so when the fresh fix turned a
          journey round (its origin now the other end) the refresh started on the old origin and
          restarted when the screen reported the new one. Landed: a relocate that turns a shown
          journey round (`Journeys.turnsShownJourney`, against the fix the screen's report faced by)
          waits for the screen to report the turned card's stops, as one releasing a held-back
          journey already did, and runs once with them.
  - [ ] **Home, Work and favorite places, routed to by coordinate (door-to-door)** (v1 scope
        ratified 2026-09-26, SPEC D9) — a Settings **"Favorite places"** row to add/edit/delete
        **Home, Work, School, custom**; each stores a **coordinate + label** plus a **stable role**
        (Home/Work/School/custom) and a **stable id**, so the reserved Home/Work pins are keyed by role,
        not the mutable label (SPEC D9); the coordinate is resolved in v1 from a
        stop/postcode via TfL (no third-party geocoder; entry field reads "Stop or postcode").
        **Tapping a favorite in the Settings list routes to it**, so every favorite (incl. School and
        custom) is reachable; Home and Work **additionally pin at the top of the To… menu** (whose own
        search stays **stop/station** — stations and bus stops, not addresses): set → route there;
        unset → prompt once to add it. Plan the trip **to the coordinate**
        (`to = lat,lon`): TfL picks the access stop and returns a final walk leg (already rendered), so
        **no nearest-stop snapping on our side** — but keep the trip's existing per-alighting-stop
        **closure checks** (never trust the Planner alone, SPEC *Trips*). Door-to-door is now in scope
        (SPEC *Non-goals* narrowed to maps/turn-by-turn only). The coordinate is the Location Data
        Safety type already declared (TfL only, no new type) and rides backup with the rest of config;
        **this slice updates the SPEC *Trips* privacy section and `docs/PRIVACY.md`** (both still say
        "coordinates never sent to the Planner") to disclose two TfL sends: the favorite-coordinate
        routing send, **and the postcode/place entry lookup** — the typed query sent to TfL during
        favorite setup (like the existing station search, but the query may be a postcode). Follow-ups:
        finer **street-address autocomplete** (its own resolver recipient
        + privacy review); quick-nav **chips at the top of the main page** to route straight to
        Home/Work; "show whichever you're *not* near / by time of day" (Later).
    - Progress: the persistence layer (`FavoritePlacesStore`) landed in #271; the Settings
      **"Favorite places"** editor (add/edit/delete via a **stop/station** picker, disclosing the setup
      lookup send in SPEC *Trips*/D9 + `docs/PRIVACY.md`) is #275. Still to do, in order:
      **a shared place search** (maintainer, 2026-09-27): reuse/extend the fuzzy finder
      (`StationIndex` + TfL) and add **postcode/place resolution** (the Journey Planner
      disambiguation), wired into **From…, To… and favorite setup alike** for consistency — the field
      then reads "Stop or postcode". UX (maintainer, 2026-09-27, revised): a postcode-shaped query
      isn't sent to the stop search; a **complete** postcode **resolves itself** (no tap) and its single
      place is adopted automatically — see the DONE note below.
      **postcode format detection** — DONE (domain `UkPostcode`): structural, full-UK-coverage
      recognizer (not a London area allow-list, since TfL plans beyond the London postal areas) —
      `looksLikePartial` gates the "Postcode" affordance while typing, `isComplete` gates resolvability,
      `format` canonicalizes.
      **postcode entry in favorite setup** — DONE (favorite setup only): the editor field reads "Stop or
      postcode". UX revised (maintainer, 2026-09-27): a complete postcode **resolves itself** (no "Look
      up" tap — the earlier tap-first flow added steps the happy path didn't need), and since a postcode
      is unambiguous its **single place is adopted automatically** (type → Save); a partial one shows a
      passive "Postcode …" hint, and only a genuine multi-place answer offers a chooser, an empty/failed
      lookup shown honestly (failure retryable). Still to do: **on-device validation of the resolver's
      assumed 200/300 shapes** (see below) before this is trusted; then From…/To… postcode entry,
      same-name disambiguation, and the To… Home/Work pins.
      **postcode resolution** — DONE (`PostcodeResolver` + `KtorTflClient`): plans from the postcode to a
      fixed interchange and reads the place, handling both the 200-resolved origin and the 300
      disambiguation list. **Blind-built against constructed Journey Planner responses** (live TfL is
      unreachable from CI, maintainer-approved for this) — **verify the assumed 200/300 shapes on a real
      device and record a sanitized fixture before relying on it in the UI**.
      **resolve-the-coordinate-on-pick** — DONE (favorite setup): a picked match with no inline lat/lon
      is resolved from its stops' center (`stationStops(id)` → `FixedLocation.centerOf`) on tap, with a
      per-row spinner; a result whose stops carry no position stays shown but unselectable ("no
      location"), while a transient lookup failure (TfL unreachable) is shown as **retryable** (tap the
      row again), never as "no location". Still to fold the same fallback into From…/To… if/when they
      need a coordinate.
      Also **disambiguate same-name results** across all three surfaces (maintainer via Codex,
      2026-09-27): two same-named places TfL keeps distinct (>250 m apart, e.g. several bus-only "Church
      Street"s) render identically — name + modes are all `StationMatch` carries. **For now: show the
      full stop name and let the rider choose** (maintainer, 2026-09-27) — most collisions differ in the
      full name (a distinguishing word, or a road/area prefix); only the handful of truly-identical ones
      stay ambiguous. Finer disambiguation is **parked** — confirmed that
      TfL `/StopPoint/Search` returns **no locality/suburb/postcode** (only `id`, `name`, `modes`,
      `lat`/`lon`, and `towards`/`zone` on *some* stops; verified against a live "Church Street" search).
      Options for later, in rough order of appeal: (a) show `towards`/`zone` where TfL provides them —
      cheap, partial (helps some buses/trams, not the plain "Church Street"s); (b) a per-result parent
      `/StopPoint/{id}` lookup — extra request each, and unconfirmed it even yields an area name; (c)
      reverse-geocode each coordinate to an area — a **new external recipient** (Data Safety change +
      cost/privacy), so a maintainer decision, not to be added unilaterally. Needs a new field on
      `StationMatch` whichever way. Then the **To… Home/Work pins**.
      **routing to a favorite** — IN PROGRESS: plan `to = lat,lon` so tapping a favorite routes to it
      (the coordinate-routing privacy disclosure lands with it); TfL's Journey Planner accepts a
      coordinate endpoint (verified against a live response) and returns a final walk leg to the place.
      The trip starts from the rider's **current location, or an active From… override** (maintainer,
      2026-09-27). The **trip options page shows the From location as its first row**, tappable to change
      it (maintainer, 2026-09-27) — the From-override UX, landing with the trip-screen slice.
      Slice 3a (entry point): landed — tapping a place in the Settings *Favorite places* list plans a
      trip to its coordinate from the rider's current location (Edit/Delete stay on the row). Reuses the
      here-trip's origin selection; the favorite (`TripDestination.Place`) skips the picker and hub
      expansion. `docs/PRIVACY.md` now discloses the coordinate reaching the Planner (same **Location**
      data, no new Data Safety category). Still to do (slice 3b): the tappable **From** first row on the
      trip page to change the origin (maintainer's From-override UX); then the To… Home/Work pins.
      Slice 2 (`TripViewModel` plans to a `TripDestination.Place`): landed — its destination is now a
      list of `TripDestination`, a place is a single coordinate, and only ridden stops are fetched (a
      coordinate has none, SPEC D9); the final walk leg reads with the name the rider picked, not TfL's
      label for the point.
      Slice 1 (typed `TripDestination`; the planner threads a `Place` coordinate to `to = lat,lon`)
      landed. Its coordinate-destination walk-leg test is **built from constructed JSON**: the recorded
      Journey Planner fixtures are all stop-to-stop and carry no `lat`/`lon`, and live TfL is unreachable
      from CI (maintainer-approved blind build). The decoded point shape (`commonName` + `lat`/`lon`, no
      `naptanId`) is verified against the live JourneyResults sample the maintainer captured. **Record a
      sanitized coordinate-route fixture** once a device can capture one, and swap it in for the
      constructed body.
- [x] **Alias en-GB spellings to the other English locales** (maintainer, 2026-09-27) — not
      needed: since API 24 Android's resource matcher falls back en-AU/NZ/IE/IN/ZA/CA/SG (CLDR's
      en-001 family) to `values-en-rGB`, and en-US keeps the base. `EnglishLocaleFallbackTest`
      pins both directions, so an alias directory gets added if that ever stops holding.
- [ ] Per-stop line/direction filters (D2).
- [ ] **Filter or rank by a destination the user enters, and let them save favorite
      destinations** — the user names where they're going (or picks a saved favorite) and
      stopdash surfaces the rows that get them there, complementing starring. Scope it to
      **on-device matching** against each row's retained destination text, so the
      destination is never sent to any network service. (The resolved destination and now
      the via-branch (`Departure.branch`) are both retained, so matching can key on either;
      the raw `towards` — the comma tail of a bus destination like "Pimlico, Grosvenor
      Road", or the downstream branch/interchange label below — is still not kept, so a cut
      that needs those must retain them first.) Saved favorites persist like
      the rest of the user's config and so ride the platform backup/transfer — the
      platform channel, not an app-initiated send, and already covered by SPEC *Privacy*'s
      backup note. An
      off-device "does this stop reach X" lookup (TfL Journey Planner) would transmit the
      destination to TfL; that channel now ships for trips and adds no new Play Data Safety type
      (SPEC *Trips* / D9), but this item stays **on-device by design** — matching against retained
      text, never a send — distinct from planning a trip to a saved destination (D9).
- [ ] **Working hours / trip windows** (requested 2026-09-19, on-device). Let the user say
      when they commute (a morning window toward work, an evening one home), so stopdash can
      emphasize the relevant direction at the relevant time and scope commute announcements
      (Phase 3) to those windows. Matching stays on-device, tied to favorite destinations
      above; the windows persist with the rest of the config and so ride Android backup /
      device-to-device transfer — the platform channel covered by SPEC *Privacy*'s backup
      note, not an app-initiated send (so not "on-device only"). Design the model and where
      it surfaces before building.
- [x] Optional user `app_key` in settings (D7). **Impact (est.):** raises the TfL budget ~50→~500
      req/min (10×) — a higher, still-finite ceiling, not the removal of the constraint: keyed
      traffic can still reach ~500/min, so the limiter and demand controls still apply, sized to
      whichever budget is active. Pasted in Settings, persisted with the other app settings, and
      warmed into `UserApiKeySetting` so the long-lived request clients read it per request (a
      paste takes effect on the next refresh, no rebuild). Sent only with the user's own TfL
      requests; never logged or put in any other off-device artifact.
- [x] **Surface a rejected user `app_key` distinctly** (follow-up to the D7 paste field, Codex).
      A 401/403 answering the user's own key is `TflException.KeyRejected`, recorded process-wide
      (`RejectedApiKey`) so one bar atop every screen says "TfL rejected your API key" with a
      one-tap **Clear key**; screens that name their failure say it too. Chosen over
      validate-on-save: no extra TfL round-trip, and it also catches a key revoked later.
- [x] **Say a rejected key on the watch too.** A watch refresh whose every stop failed on a key
      TfL refused answers `KEY_REJECTED`, and the watch says "Couldn't refresh: API key rejected".
      No version skew to handle: the Wear app isn't released, so no older watch reads the new
      value (and one that did would read an unknown outcome as none, and time out).
- [ ] Extend the persisted snapshot (from Phase 1) to cover the watched-stop set,
      filters, and the **stop-set key it's keyed by** (not the TfL `app_key`, which persists
      separately in settings). **This is what re-enables persistence for the location view**: the
      interim nearby view (PR #21) uses no persisted snapshot, because one process-wide
      snapshot can't represent a set that changes as the user moves (restoring it would show
      a previous location's departures under the newly-resolved stops). Keying the snapshot
      by stop set brings back the instant first frame and gives the offline/failed state
      last-good departures to show instead of only the gate (Codex, PR #21).
- [ ] (Later) Smarter row selection beyond starring — Home/Work "show whichever you're
      *not* near", direction by time of day — heuristic- and location-dependent, and the
      widget is location-free at refresh (D1), so a wrong guess is its own quietly-wrong
      risk; scope carefully (D8).
- [ ] (Later, open call) Evaluate the compact **(service, stop) swipe-card** model
      against the flat list from real device use (D8) — collapses a two-way service to
      one card, but hides the off-screen direction behind a host-owned gesture and needs
      a widget answer for it (a per-service direction filter or "widget shows the
      stop-wide D2 filter"). Owns the swipe work if adopted; not committed MVP scope.
- [ ] (Later, open call) **Label a direction by the next branch/interchange point, not
      the terminus** (D8). London-only, so tractable: from TfL route sequences
      (`/Line/{id}/Route/Sequence/{direction}` — fetch the inbound and outbound variants;
      static-ish, free, cached ahead of time — off the decision path; sends only line ids,
      no user data, so no Play Data Safety change),
      walk downstream from the boarding stop to the first point where the
      route *branches* or meets another useful line/mode, and show that point's name
      ("towards Highgate") instead of TfL's terminus. Key constraint: only a genuine
      branch or interchange counts — a stop merely shared with an unrelated route is not a
      junction. Replaces the terminus label the MVP ships with.
- [x] **Show the via branch beside the destination** — TfL's `towards` "via" trunk
      ("Battersea/Charing X") shown after the terminus joined with a slash, so a
      rider can pick a branching-line train (Northern most visibly). The branch
      **participates in grouping**: within a direction a line splits per terminus *and*
      branch, so two trains to one terminus via different trunks each get their own line
      and countdown (a countdown never sits under the wrong branch). Under width pressure the
      **branch is kept whole and the terminus yields** (word-abbreviated, then its floor, then a
      single clean `…` — never a mid-glyph cut; PR #127 replaced the earlier equal-truncation
      hard-clip). At the narrowest row the branch stands alone and bare. The branch is normalized to one
      short board form per trunk ("Charing X") on every surface, up front, not measured against
      the row; a fuller rider-readable branch form under pressure ("via Charing Cross") is the
      tracked follow-up. The separator settled on a slash: parentheses (#92 shipped a comma list
      first) cost extra spaces and dropped higher-information characters on a narrow row; the slash
      is the compact form (#94). The on-device eyeball of the truncation balance and the
      abbreviations is **done** — the maintainer confirmed it and is happy with the shipped result
      (2026-09-22).
- [ ] (Later, open call) **One row per destination** as an alternative grouping to
      (service, stop, direction) (D8) — every row then names a single unambiguous
      destination (and handles a blank `direction` via `towards`), at the cost of more
      rows for a branching line. Explore against the shipped keying from real use.
- [x] **Per-line Overground pills.** The 2024 named Overground lines (Lioness, Mildmay,
      Windrush, Weaver, Suffragette, Liberty) each show in their own line color, rendered as
      a **hollow** pill (surface fill, line-color border + label, nudged for contrast on the
      surface) — this supersedes the earlier two-color banded idea: the hollow shape tells
      Overground apart from a same-color tube line and matches how TfL draws it (PR #42,
      SPEC). Follow-up: confirm the six hex values against TfL's colour standard (it isn't
      reachable from the build environment).
- [ ] (Later, open call) **Show every destination departing in the next ~30 min, else
      just the next** (D8) — a time-window rule for what a row/list shows: surface all
      distinct destinations with a departure inside the window, and fall back to only the
      single next departure when the window is empty, so a quiet stop still shows
      something. An alternative to a fixed "next few per row"; explore from real use.
- [x] **Fixed-width line pills** — every pill now shares one fixed label width, sized to
      the widest code (a four-character bus route), so they form a tidy column instead of
      ragged-width blobs (equal-width-line-chips). Follow-up: eyeball the column and the
      widest codes on a device — the width is verified by unit test but not yet seen on
      real hardware.
- [x] **Narrower cut pills** — the exception to the fixed width: a pill cut for several lines
      ("43/134") sizes each part to its own code, never narrower than a two-character one, so it
      no longer comes out half again as wide as the pills beside it (maintainer, 2026-09-28).
      Follow-up: see it on a device.
- [x] **Cut pills for three or more lines** — the first line, then "…" in the second's color
      ("43/…"), so the pill stays two parts wide (maintainer, 2026-09-28). Follow-up: see it on a
      device, and reconsider naming every line if the rest turn out to be missed.
- [x] **Codes for National Rail services.** First-three-letters collided (Southern and
      Southeastern both → "SOU"), so a rail operator now shows its initials — the capitals in a
      multi-word name (East Midlands Railway → EMR, Greater Anglia → GA), the initialism a rider
      sees on the train, which also beats the cryptic legacy TOC codes (EM, LE, GR, VT). The few
      single-word operators that would still collide are hand-pinned to their TOC code (Southern
      SN, Southeastern SE, Thameslink TL); c2c stays verbatim; unmapped single-word operators
      fall back to first-three-letters. Elizabeth line and Overground are their own modes and
      keep "ELI"/the hollow pills. The pinned list and the initials want a final eyeball on a
      real device — TfL egress isn't reachable from CI to enumerate the live operator set.
- [x] **Colors for National Rail services.** Each operator now shows a solid pill in its own
      **brand** color (`railOperatorColors`) instead of the neutral fallback, keyed by operator
      name (TfL's `lineName`) like the operator code — a deliberate, maintainer-authorized
      departure from "a confirmed TfL hex only". Fifteen confirmed operators: c2c, Southern,
      Southeastern, Thameslink, Great Northern, Greater Anglia, GWR, SWR, LNER, Avanti West Coast,
      East Midlands Railway, CrossCountry, Chiltern, Gatwick Express, Heathrow Express. Hexes are
      from Wikipedia's UK-railways colour templates, except Great Northern's purple (`#43165C`),
      taken from the operator's own site because the Wikipedia value is a route-diagram blue.
      Rendered solid (not the Overground hollow) since the operator code disambiguates any
      tube-color collision. Like the operator codes, this ships **ahead of** rail departures
      landing (item below — TfL's feed doesn't carry them yet), so it's unexercised until then; a
      rail operator not in the confirmed set still falls back to a neutral pill. The confirmed set
      wants a final eyeball on a real device once rail departures land — the brand hexes weren't
      verifiable against a live TfL operator set from CI.
      London Northwestern Railway (LNR) joined later, green `#27B67A`: the maintainer picked it
      (2026-09-24) from the route-map legend in Wikipedia's *West Midlands Trains* article over its
      template's `#00BF6F`, after seeing both rendered. Matched by name prefix, like its pill code,
      since the rail feed's exact spelling is unconfirmed. Its pill code was LNWR until the
      maintainer switched it (2026-09-30) to LNR, the operator's own short name; LNWR was the
      London and North Western Railway, which ended in 1923.
      The rest of the operators then joined from the same Wikipedia templates: Eurostar, Caledonian
      Sleeper, Lumo, Grand Central, Hull Trains, TransPennine Express, Northern, Transport for
      Wales, ScotRail, Merseyrail, Island Line, and West Midlands Railway (WMR, whose orange
      `#F27B15` comes from the same legend as LNR's green, the maintainer's call on 2026-09-30,
      over its template's `#FF8300`).
      On a device none of LNER, LNR or WMT took its color: the rail feed names LNER "LNER" and
      West Midlands Trains' line "LNR & WMR" (one name for both brands, so the LNWR/LNR name pin
      never matched either), and TfL's "West Midlands Trains" had been left neutral. Colors and
      pinned codes now match by name or, failing that, by the line id the operator's code maps to
      (maintainer, 2026-09-30), and West Midlands Trains' line reads as a green LNR under every
      name; West Midlands Railway named on its own keeps WMR orange. The rail feed's "Lumo
      Stirling" wears Lumo's blue. The line is also named "London Northwestern Railway", the
      brand its London trains carry, in place of TfL's "West Midlands Trains" and the feed's "LNR
      & WMR", renamed where names come in from TfL, the rail feed or a saved copy (maintainer,
      2026-09-30), so directions, spoken labels and titles match the LNR pill.
- [ ] (Later) **Revisit auto-locate-on-open and the location states.** StopDash
      resolves location once on open (a `LaunchedEffect` gated on `PermissionRequired`) and
      the nearby set never re-resolves afterward except via the temporary crosshair button.
      Work out the intended behavior across the states — first open, permission
      granted / approximate-only / denied / permanently-denied, returning after moving,
      returning from Settings, a stale fix — and whether re-locating should be automatic (on
      resume, on a significant move) rather than a manual tap. The crosshair button is a
      stopgap for on-device radius testing until this is settled.
      **Decided a direction (maintainer, 2026-09-20): live location is a wanted feature — do
      not let the on-demand-location contract block it; pursue it with full disclosure.** So
      check location on open, then re-check periodically and on a location-change event while
      the app is open. This deliberately **supersedes** `SPEC.md`'s on-demand-location promise
      (§62-63, D1) and the "keep re-location behind a deliberate near-me action" requirement
      above (`TODO.md:376-379`): update `SPEC.md` to state that stopdash uses live/automatic
      location, and disclose it fully — the **Play Data Safety** declaration plus clear
      user-facing wording that location is used continuously while open (a coordinate goes to
      TfL automatically and more often, not only on a manual tap). Then the build work: design
      the cadence and significant-move threshold, account for the added request frequency and
      the battery cost of a periodic fix + a location-change subscription (SPEC *Cost and
      reliability* / §9-style budget), and fold in the location states above. The disclosure is
      the gate, not the direction — the direction is settled.
- [x] **Configurable display scaling / font size, with a pinch gesture** (maintainer,
      2026-09-20). Make the text/display size a persisted setting on the Settings screen —
      mirror how snoozemo does it (`mikelward/snoozemo`, its SettingsScreen scale control) —
      AND let the user **pinch-to-zoom** on the departures view to change it, the two kept in
      sync through the same stored value. The user likes the current dense layout, so the
      default stays as-is; scaling is opt-in. Its own PR (not part of the auto-locate work).
      Cross-check the snoozemo implementation for the store shape and the density clamp before
      building. (Supersedes the earlier "make text size a setting" note.) **Done:** a Settings
      slider + pinch switch and an app-wide two-finger pinch write one stored size (80%–160%,
      a factor over the system scale), warmed at startup; see SPEC *Display size*. The pinch
      works anywhere in the app, not only the departures view. Device check still owed: the
      gesture and the live resize want an eyeball on hardware.
- [x] **Truncate the row destination instead of ellipsizing it** (maintainer, 2026-09-20;
      **reversed PR #127, 2026-09-22**). The original ask was a clean hard clip over a `…`, because
      the `…` crushed the name to a single character plus `…` and read as a glitch. In practice the
      hard clip cut **mid-glyph** (`/Charin`), which reads worse — so PR #127 reversed this: the
      destination line now **word-abbreviates → floors → a single `…`** (no surrounding spaces,
      never a mid-glyph cut), across the terminus (no-branch and branch cases) and the branch/via
      label alike. The countdown, disruption chip, stop name, and line pill are unaffected. The
      tension with the "Branch truncation" task above is settled the same way: the branch is kept
      whole and the terminus yields; the only open follow-up there is a fuller rider-readable branch
      form under pressure.
- [ ] (Later, open call) **Walk-time reachability filter** — hide departures the user
      couldn't physically reach in time. Rough model: ~6 km/h ≈ 100 m/min walking, so a
      stop 200 m away is ~2 min out; drop a departure leaving sooner than the walk time to
      its stop. Needs the per-stop distance (already available from the nearby lookup) and
      a chosen speed/margin; make the speed and whether it's on a setting. Explore from
      real use — a too-aggressive filter that hides a train the user could have jogged for
      is worse than showing it (SPEC principle 1).
- [x] **Walking speed for the phone's own walk estimate** (maintainer, 2026-09-28). The phone's
      own estimate of the walk to a trip's first stop, used only when a *From…* station's trip starts
      at a neighboring stop, takes the rider's walking speed at the Planner's own paces (measured
      from its walk-only routes: in a straight line about 0.64, 0.90 and 1.14 m/s at Slow, Medium and
      Fast, rounded up to a minute), in place of one fixed pace that read a third longer than the
      Planner at Medium. The reachability filter above takes the same setting when it's built. Still open: don't plan the first leg from when the
      rider reaches the stop — at an assumed pace that would drop the best options for a fast walker
      (maintainer, 2026-09-28: think more before doing that for the first plan).
- [ ] **Explore how a trip recalculates mid-route** (maintainer, 2026-09-28). For a trip on the way
      it starts from the station still ahead on the route nearest the rider (maintainer,
      2026-10-02; `ReplanOrigin`, SPEC *On the way*); the re-plan itself is *Planning again from
      where the rider is*, above. The trip list's own re-plan, below, is still open. A re-plan (the
      15-minute reuse, or 5 minutes while an arrival is withheld) asks the Planner from the trip's
      first stop at "now", however long ago the trip was opened or wherever the rider has got to.
      Explore re-planning from where the rider is and when they'll be ready there, so a trip
      reopened after a wait, or recalculated partway, doesn't assume a connection they've already
      missed. Ties in with *On the way*'s re-plan for a boarded train that leaves the leg. "Where
      the rider is" may be their current location, sent to the Planner as the trip's origin
      (maintainer, 2026-09-28). That's new for *On the way*, which today compares the fix on the
      device only, so the change updates SPEC, `docs/PRIVACY.md` and the Play Data Safety answers
      to say so. Cost: the Planner is free (£0), and a location origin replaces the stop in the
      same request rather than adding one; a location fix taken for it is a battery change, stated
      against SPEC's budget when it's built.
- [x] **Skip a walk the rider's location can't follow** (maintainer, 2026-10-03). A walk between
      two rides whose ends are within 200 m (two 100 m arrival radii), or 290 m within one
      interchange of the bundled index, is a change on foot, not a step; its ends placed by the Planner's positions, else the bundled station index, else the
      same-name rule. Decided once when the trip starts and kept with it (SPEC *On the way*). The
      station's live entrances weren't needed: the index's points settle it with no request.
- [ ] **Skip a short walk to the first ride?** Open question (maintainer, 2026-10-03): whether a
      walk to the first ride within the same 200 m should be skipped too. The maintainer would
      rather keep the first walk, but maybe not when it's that short; today it always stays a step.
- [ ] **Tap a card to open a detail view** (requested 2026-09-19, on-device). The compact
      card drops platform, full direction, and any longer disruption text to stay glanceable
      (SPEC *Departures*); a tap opens the fuller picture — platform and direction (already in
      the domain), longer disruption text, and, if a source exists, **accessibility** info
      (step-free, lifts out of service). Open design questions to settle before building — not
      specified here: where accessibility data comes from (today's TfL surface — arrivals,
      line status, free-text stop disruptions — distinguishes no lift/step-free state, so a
      source has to be found, or the feature drops it); how the view renders honestly
      (from state already in memory, not a tap-time fetch) and how each source's freshness is
      tracked, since arrivals, disruption, and any accessibility data age independently and a
      safety-relevant lift outage must never read as current when it isn't (D4 / principle 1).
      **Width follow-up (maintainer, 2026-09-20):** the per-row star button ate row width and
      crushed the destination (observed `B… (Charing X)`). **Done (PR #73):** the button is
      removed, starring is a long-press on the card, and a pinned card is marked by a gold
      border (no in-row element). **Landed (#100):** a card *tap* opens the detail (no-op tap
      before), carrying the discoverable star, the destinations the service runs to, and the line's
      **full disruption text** — the compact chip's prose, shown collapsed to its first line and
      tapped to expand, the same widget the stop-closure card uses (line status now **retains TfL's
      `reason`** on `LineStatus.fullText`, previously dropped). **Now a full-screen page
      (`RouteDetailScreen`, maintainer 2026-09-22):** the detail was a dialog first; it is now a
      full screen that replaces the departures screen with its own app bar — the bar names the route
      (line pill + destination) and carries the star (filled gold when starred, matching the list
      card's gold pin border; the vendored outline star when not; long-press on the card stays the
      shortcut), and the body names the boarding stop. A full screen because it will grow per-route
      actions (the maps/nav hand-off below), which a dialog would cap. **Stop list (maintainer,
      2026-09-22):** the page lists every station from the boarding stop to where the soonest train
      terminates, on a line-colored rail — TfL `/Line/{id}/Route/Sequence/{direction}`, fetched when
      the page opens (never on the refresh path) and cached in memory for the process; an ambiguous
      path (no branch to pick a trunk) says so rather than guessing. **Still outstanding here:**
      platform and full direction in the detail, and **accessibility** info (step-free, lifts) once a
      data source exists — the open design questions below are unchanged for those.
  - [x] **Bus routes' stop list** — a bus blind's destination rarely names a stop or the
        route, so most buses showed "unavailable"; a bus now runs to its route's end when nothing
        matches, and an unavailable list logs why.
  - [x] **Show connections/interchanges on the route's stop list** (maintainer, 2026-09-22). Each
        station on the route detail's stop list carries a right-aligned line pill per rail-type line a
        rider can change to there — tube, Overground, DLR, Elizabeth line, tram — from the station's own lines
        and its interchange's (TfL `topMostParentId` hub), read from the same Route/Sequence response
        (no new request). Buses are left out (they would swamp it), and so is national rail named only
        at a mixed-mode interchange, since TfL doesn't say which of a hub's lines are trains.
    - [x] **National-rail connections at interchanges** — a hub lists operators and buses together
          with no per-line mode; identifying the operators (from the member rail station, one more
          lookup, or a known-operator list) would add them without guessing. *Done with the
          known-operator list: TfL's line id for each operator the app already maps National Rail
          boards by (`NATIONAL_RAIL_LINE_IDS`) counts as national-rail; no new request.*
  - [x] **Open the route the user tapped, not the row's soonest train** (maintainer, 2026-09-22). A
        card shows one route row per destination of a line+direction, but every one of them opened the
        same detail, whose app bar and stop list followed the row's *soonest* departure — so tapping
        "Edgware" could show the High Barnet stop list. The detail now follows the tapped destination
        and branch; offering every route at the platform to pick from stays an option if the
        single-route page proves limiting.
  - [ ] **Reconsider the detail star: pin vs star, and its relation to favorite routes/destinations**
        (maintainer, 2026-09-22). The detail control is a star for now; decide whether it should be a
        **pin** (pushpin) instead — the user-facing copy and the list marker already use "pin"/"pin to
        top", so the metaphor is mixed — and how a starred/pinned *route* relates to any future
        **favorite routes or destinations** feature (are they the same list, or is pin-to-top a
        lighter, per-session ordering distinct from a saved favorite?). Settle the metaphor and the
        data model together before a favorites feature hardens the current star into an API.
- [x] **Hand off to a navigation app** (requested 2026-09-19, on-device). Landed 2026-09-23: tapping a
      near-me header's **distance** opens that group's nearest stop in the user's default maps app,
      via a `geo:0,0?q=lat,lng(Name)` intent (a labeled pin at TfL's published stop position, never
      the user's fix). No new dependency and $0; with no maps app a toast says so. The stop's
      position and name reach an app the user picked, only on their tap — user-initiated sharing,
      not collection by StopDash, so no Play Data Safety change.

## Phase 3 — Full disruptions

Builds on Phase 1's minimal line-status marking.

- [ ] Stop/line disruptions (`/StopPoint/{id}/Disruption`, `/Line/{ids}/Disruption`)
      and cancellations of specific services where TfL exposes them.
- [ ] Rich in-app disruption text; mark a disrupted line/stop even when predictions look
      normal (D3). Domain summarization JVM-tested.
- [x] **A trip's disruptions row is one line** (maintainer, 2026-10-04). The row shows the chips that
      fit, then "+N more", so it never wraps and pushes the routes down (Codex, PR #543); it bounds the
      pills composed on the main thread too. The sheet below already lists everything the row cuts.
      More pills can reach it since the lines whose trains couldn't be checked joined "Unknown:"
      (Codex on #618), so a refresh adding or dropping enough of them can wrap it until this lands.
      Done: the row is laid out by the home row's `OneLine`, its closed and unchecked stops counted
      among the pills, and heard whole.
- [x] **Every line a trip rides, with its status, a tap on the disruptions row away** (maintainer,
      2026-10-04). Done as a page of its own (`TripLinesPage`, worked out with the row on the worker:
      `tripLines`): one row per line with its worst-severity status, disruptions first, dismissed ones
      toned down, unchecked lines marked with what that means, then the row's stops; a disrupted line's
      reason on its own page. A sheet was tried first; the maintainer chose a full-screen dialog.
  - [x] **Open the lines page from the home screen too** (maintainer, 2026-10-04): the page reads only
        the lines it's given and the app's menu, so the home screen supplies its own.
- [x] **The home screen has a one-line disruptions row** (maintainer, 2026-10-05): every tube
      line, and every line with a departure from a stop within the walking reach (500 m), under the
      favorite places' chips; "+N" for what doesn't fit, a tap opening the lines page (`HomeLines`, `HomeDisruptionsRow`).
  - [x] **Choose the networks the row always covers** (maintainer, 2026-10-05): "Always include" in
        Settings: Tube, Overground, Elizabeth line, DLR and Tram, all on by default; nearby lines always.
  - [x] **A switch to turn the row off**: "Disruptions summary", second in Settings (maintainer,
        2026-10-05: narrower than "Show disruptions").
  - [x] **What's dismissed stays off the row and goes down the page** (maintainer, 2026-10-05): off the
        pills once dismissed every way it's disrupted; on the lines page just above the good services.
  - [x] **Dismiss an alert from the lines page** (maintainer, 2026-10-06): a line's own page offers
        "Dismiss alert" in its overflow menu, on the trip and the home screen alike.
  - [x] **Settings from the lines page** (maintainer, 2026-10-06): its overflow menu opens Settings on
        the Disruptions summary's page, whose Back goes up to Settings, then out.
  - [ ] **Consider an up arrow for Settings and its pages** (maintainer, 2026-10-06: keep the Back button
        where it is for now). Settings and its pages (Disruptions summary, …) have a "Back" text button at
        the right of the title row; consider a top-left up arrow as the rest of the app has, and the
        options: whether every Settings page follows, how it sits beside the overflow menu, and whether
        up and Back ever differ (a page opened from the lines page going up to Settings).
  - [ ] **Let the rider choose what the row covers** (maintainer, 2026-10-05): a list of lines,
        favorite routes, or favorite trips? And how to rank and collapse it when many are disrupted.
  - [ ] **Name the walking reach's closed stops on the row**, as a trip's row names its closed stops.
  - [x] **Fold the list's "couldn't check for disruptions" banner into the row** (maintainer,
        2026-10-05): where the row shows, its "Unknown" says what the banner did, naming what it can.
  - [ ] **Check a bug report sent from the lines page** on a device: the report's screenshot is taken
        of the app's window, which may leave out the dialog's.
- [x] **On the way notes the coming stations' notices** (maintainer, 2026-10-04): a notice that neither
      closes nor moves a station still ahead gets a quiet card on the trip's screen, never an alert
      (`RouteDisruption.stationNotes`, carried with the route check).
- [x] **Every line's page has its map** (maintainer, 2026-10-06; SPEC *Line page*). `LineMap` lays the
      line out from its TfL route sequence, one station a row and a column per branch, turned north up
      where TfL gives positions; a station two branches call at without meeting there gets a row on
      each. TfL's closure sections close the track they cover, else the alert's named stations are
      marked. It opens folded: around the alert with the rest of the line folded to the ends it leads
      to, or with good service to the line's ends and junctions; the rider's starred and trip stops
      never fold. Tested on the real Northern line and recorded Northern, District and Circle
      sequences.
- [ ] **Coming-up closures on a line's page** (discussed 2026-10-06). TfL's future statuses
      (`/Line/{id}/Status/{from}/to/{to}`) for the next week, each drawn on the map like a closure in
      force: one free request when the page opens, cached for hours, a failure saying so with a retry.
- [ ] **A trip's lines page keeps the rider's starred stops on the map too.** Only the home screen's
      row passes them today (the trip passes the stops it rides); the trip's row needs the starred
      rows and journeys read alongside its own work.
- [ ] **The route page draws the line as the folding map too** (maintainer, 2026-10-06): its stop list
      becomes the line page's map (`LineMap`), with the path the rider takes open, from the stop they
      board at to where they get off, and the rest of the line folded to the ends it leads to.
- [x] **An alert off the rider's stops and rides folds, its fold saying how bad** (maintainer,
      2026-10-06; SPEC *Line page*). Shown in full only at the rider's own stops and on the stretches
      their trip rides (each ride from where it boards to where it gets off); anywhere else its stations
      fold, ⛔ on the fold for no service, ⚠ for a station an alert's words name. Each closure TfL places
      is taken on its own, so the map no longer works out which one the page shows. Starred stops stand
      in for a ride from the home screen.
- [ ] **"View line" wherever a stop or departure is shown** (maintainer, 2026-10-06), opening the line's
      page with its map: next up. First the route page's overflow and a trip's ride; then where else it
      fits (the on-the-way screen with the ride's stretch open, a stop's line pills), checked in the code
      before it's offered.
- [ ] **The route page as its origin, a folded span, the current station and the rest of the route**
      (maintainer's idea, 2026-10-06: explore, or discard). Pairs with the folding-map item above.
- [ ] **Rethink long-pressing a stop on the route page** (maintainer, 2026-10-06). It was meant to offer
      starring the route; think the experience through before changing it.
- [ ] **Tapping a stop shows its details or departures** (maintainer, 2026-10-06): explore what a tap on
      a stop in a stop list should open.
- [ ] **The home screen's line page shows its boarding station's notice** (maintainer, 2026-10-04). A
      line page can read "No disruptions reported" while the station it boards at has a notice in
      force (an escalator out, say); the stop's own notice belongs there too.
- [ ] **An opened trip route shows each station's notice where it reaches it**, as closures are shown
      (maintainer's pick, third of three, 2026-10-04); the list's cards stay without them.
- [x] **"Some routes couldn't be checked" moves the trip list when it comes or goes** (Codex, PR #543).
      Once the list is showing, a refresh that turns a check from checking to couldn't-check inserts
      the banner over the routes (after its 1.5 s hold), shifting every card; the reverse removes it.
      It predates the hold-still work. Decided (maintainer, 2026-10-04, mock C): it moves into the
      disruptions row, which always holds its place, under "Unknown:" with the pill of each line whose
      trains couldn't be checked; the expand view says what that means. The banner goes. Done: the
      trip list's banner is gone, and its row names each such line ([trainsUnchecked]).
- [x] **Build the trip list hidden behind its placeholder, then show it** (Codex, PR #543). Everything
      the list draws is now worked out before it shows: the cards' times, order and headers in #529's
      frame, the disruptions row, and each card's pill width, measured behind "Checking routes…" and
      waited on by the reveal. Composing the list unseen stayed unneeded.
- [ ] **Option: every line's status at the top of the home screen** (maintainer, 2026-10-04). A
      setting, off by default, to show disruption status for all lines, not just the watched stops'
      — with a choice of which modes to include. TfL's `/Line/Mode/{modes}/Status` returns a mode's
      every line in one request (free, cached with the other statuses), so it's a few requests per
      refresh. National Rail may not be covered yet: check what TfL returns for it before offering it.
- [x] **A catch-all reason that says a disruption has ended still names it** (Codex, PR #455).
      "The diversion is no longer required" resolved to Diversion: `resolveDisruption` only looked
      for a negation *before* the word. Done: a denial that follows it in the same clause ("is no
      longer required", "has ended", "has been lifted") denies it too, so an ended diversion falls
      back to the non-alerting Service Alert; a clause ends at punctuation or a word that starts
      another ("because", "and", …), so "diverted because the road is no longer open" still names
      one. Same family as the allowlist option below: guessing a label from prose by word lists.
- [ ] **Option: leave out a bus alert only when every sentence is one StopDash reads** (maintainer,
      2026-10-01, an option to weigh, not a decision). A bus line's alert is left out of a ride its
      stretch doesn't reach (PR #455) unless its text has one of a list of route-wide words
      ("delays", "not running", "suspended", "cancelled", …). Review kept finding words the list
      missed, each one a rider who'd lose their only warning, and TfL can always word an outage the
      list doesn't name. *The option:* an allowlist instead: leave the alert out only when every
      sentence is a kind StopDash reads (the stops not served, "diverted via …", a direction, a date
      or reason), so any other sentence keeps it on. *Gains:* it fails safe, with no word to miss.
      *Costs:* some off-ride diversions would still warn until their wording is added. *First:*
      check how many of the TfL alerts collected in PR #455's tests it would still leave out.
- [ ] **Name a disruption from the reason text, and judge relevance** (reported 2026-09-19,
      on-device; refined 2026-09-20). A rider expects "Diversion", not "Special Service".
      **Landed so far:** a vague "Special Service" is replaced by a concise label parsed from
      the reason text ("Diversion") when the text names one, keeping TfL's own wording for
      every informative status ("Part Closure", "Suspended", delays); the line always stays
      flagged (never turned into a good service). **What's left, and it needs the free-text:**
      - Why the text and not a field: **there is no structured discriminator** — checked
        against the live API, a bus's `disruption.closureText` is `null` and `category` is only
        `PlannedWork`/`RealTime`, and `/Line/{id}/Disruption` is empty while
        `/Line/{id}/Status?detail=true` carries the prose in `reason` (== `disruption.description`).
        The reason-scan landed for the two things a vague "Special Service" actually hides —
        **Diversion** and **Curtailment** — most-severe wins across coexisting entries.
        Suspensions and delays are *not* inferred from prose: TfL words those itself
        ("Suspended", "Part Suspended", "Severe/Minor Delays"), kept verbatim on the graded
        path. Extend the inferred vocabulary as new catch-all cases turn up.
      - Stretch: also pull the **affected stretch** from the text — "Diversion Moorgate to
        Monument" — where the text gives a clean from→to. **First step landed** (2026-09-26): each
        station the alert names gets a ⚠ on the line's page (`AlertStops`). **Second step landed**
        (2026-10-01): the stations *between* two named ends where the text says "between X and Y" /
        "X to Y" get one too, on the train's stop list (`AlertStops.affected`), every stretch the
        alert gives, even one it says still runs (maintainer, 2026-10-01: telling them apart from
        prose was open-ended). **Third step landed** (2026-10-02): a bus alert whose stretch lies
        wholly behind a row's stop no longer flags it (`DepartureRows.withAlertsBehind`), and its
        page tells it muted, named from the whole route; a stop quoted as its sign reads ("Bank
        Station/King William Street") now matches the route's cleaned name.
        - [x] **The widget and watch place an alert behind the stop too** (2026-10-02): the near-me
              list keeps its verdicts, by the alert's words, and they apply them as they read the
              stored departures, as they do a dismissal.
          - [x] **An alert that appears while the app is closed** (2026-10-02): the widget's own
                refresh places it, with the routes the app already holds for the day and no request.
                A line whose route isn't held flags until the app next shows that stop.
          - [x] **Planned work whose day has come** (2026-10-02): a stored check keeps each planned
                alert's words as it will show them, so the app's verdict on it applies once its day
                comes, where nothing else is under way.
        - [ ] **Behind the rider on a tube or rail line**: not attempted, as its delays spread
              along the line; a part closure TfL places itself might be, as a trip places one.
        A status-only line page (a
        suspension, no predictions) names the alert's stations beside the chip too — see *Decisions
        needing review*.
      - [x] **Only on rows going the affected way** (maintainer, 2026-09-28): an alert TfL
            scopes to one direction (its affected routes' `inbound`/`outbound`, only in the
            `?detail=true` response) no longer flags rows heading the other way. Looked up once per
            new alert in the background and cached by its text; unknown counts for both.
        - [x] **Back off a direction lookup that keeps failing** (Codex on #366): a failed
              detailed lookup is released and asked again on the next refresh, so while TfL keeps
              failing it (a response the app can't decode, say) the list pays up to ~150 KB a line
              every refresh rather than once per alert. Now it's tried again a minute later, then
              after twice as long each time, up to half an hour; meanwhile the alert shows for both
              directions, as it did.
        - [x] **The widget and watch by direction too**: the snapshot and the watch envelope keep
              each direction's status, dismissals are marked per direction, the complication marks
              its row's direction, and the widget's refresh fills the shared direction cache.
              `docs/PRIVACY.md` names it; no Data Safety change (TfL's public status).
        - [x] **The app's own directionless rows after a one-way dismissal** (Codex on #343): the
              line-wide status is the line's worst alert, so dismissing that alert on a row going
              its way also hid a row with no direction (most rail, a status-only row), even when
              the other way had its own alert. `DepartureRows.withoutDismissed` now shows the
              other way's alert there, as the widget and watch do (`LineStatusCheck.shown`).
        - [x] **Trip cards by direction too**: a collapsed trip card's ⚠ and ⓘ read the line-wide
              status, not the leg's direction, so a card riding the unaffected way still showed a
              one-direction alert (Codex, PR #337). Done: each line's status is taken for the
              direction its trains along the ride are seen going (`LineStatus.alongRides`), before
              dismissals, as a list row's is; a line not seen yet, or seen both ways, keeps the
              line-wide status. The open route's summary does the same.
        - [x] **Planned work's calendar icon on the widget and watch**: they persisted a line check without its
              planned work, so a line whose only alert is still to come showed as a clean
              line there (it showed the ⚠ before), with no calendar in its place (Codex, PR #337). Done:
              the check stores `planned` (label, start day, rank and dismissal identity, never the
              prose), on the line and each direction, and the watch envelope carries it with a
              per-alert dismissed flag (privacy doc updated); the widget, the tile and the watch app
              draw the calendar beside a row's first countdown, and its ⚠ on the day, as the app does.
        - [x] **Upcoming planned work as info** (maintainer, 2026-09-28): read the start date out
              of the reason text ("from 13 Oct 07:00"; `validityPeriods.fromDate` is when TfL posted
              it, not when it starts) and show a not-yet-started alert with an info icon instead of
              as a disruption. `category` is `PlannedWork`/`RealTime`/`Information`; `isNow` is
              true for `RealTime` and mostly false for `PlannedWork`, current or not.
        - [x] **Only planned work is read for a later start** (maintainer, 2026-09-28): a
              `RealTime` or `Information` alert, or one with no category, is current whatever its
              text dates. None of the 31 live alerts read as upcoming were anything but `PlannedWork`.
      - [x] **Current-vs-future comes from the dates in the text, not `isNow`** (landed with
            *Upcoming planned work as info* above). `validityPeriods[].isNow` reads `false` even
            for planned closures in effect (observed 2026-09-20), so it marks "unplanned", not
            "current"; the start is read from the reason instead (`AlertStart`).
      - Decide **whether to show a disruption at all** when it's not relevant to most journeys
        through the stop (the observed case was a detour miles away), with any relevance test
        still erring toward showing over hiding (SPEC principle 1 — a wrongly-hidden real
        disruption is worse than an extra one).
      - **Landed:** **show the full alert text on tapping the card** (requested 2026-09-20) — a
        card tap opens the full-screen `RouteDetailScreen`, which shows the line's `reason` prose (now
        retained on `LineStatus.fullText`) collapsed to its first line, tapped to expand. Still open: a
        **per-condition chip** (below) and making the chip itself the tap target once there are
        several.
- [x] **Bug: the "Couldn't check for disruptions" notice appears to fire constantly**
      (reported 2026-09-20, on-device; fixed 2026-09-20). Root cause: with `getFamily=true`
      the endpoint returns a `DisruptedPointFamily` **tree object**, not the flat
      `DisruptedPoint[]` the DTO parsed — so every per-stop fetch threw `JsonConvertException`
      and `disruptionUnknown` lit on every refresh. Fix: parse `TflDisruptedPointFamilyDto`
      and walk `children`, collecting each node's `disruptions` (drop blanks, dedupe by text).
      The test fixture had been the wrong (flat) shape, which is why this shipped green; it now
      records the real family tree. Line-status path was not implicated (batched call succeeds).
- [ ] **Show a chip per disruption condition, not just the single most-severe one**
      (requested 2026-09-20). Today `mostSevereDisruption` collapses coexisting statuses to
      one label; the maintainer wants to revisit that — show a chip for each condition a line
      carries (a diversion *and* minor delays, say). Keep a chip for every *real* condition,
      including a severe-but-unworded one: that borrows the "Service Alert" label but is
      `isFallback = false` and keeps its real severity, so it must show its own chip, never be
      hidden behind a milder named one. Only the true `isFallback` catch-alls (a "Special
      Service" that named nothing) consolidate into a single "Service Alert" — and only when
      no real condition remains. `resolveDisruption` already reduces each entry to a
      `ResolvedDisruption`, but `toLineStatus()` then collapses them via `mostSevereDisruption`
      and both `LineStatus` and `TflClient.lineStatuses()` expose only that single result — so
      the discarded conditions can't be recovered downstream. This task therefore has to carry
      every resolved condition through the data/domain/state layers (a list on `LineStatus`, or
      similar), not just design the chip layout; the open design is that plumbing plus how many
      chips to show before they crowd the row and the glance surface, and how it meets the
      compact-chip item below. Design before building.
- [ ] **Make the disruption chip lighter-weight than a full row** (requested 2026-09-19,
      on-device). The shipped status chip ("Part Closure", "Suspended", delays) takes a whole
      row, which reads as too heavy for what it conveys — the maintainer suggested a warning
      triangle (or similar compact affordance) instead, e.g. `⚠ Diversion Moorgate to Monument`
      once the label work above lands. Refines the shipped Phase 1 status
      chip's density without dropping the signal (SPEC principle 1/2 — the disruption must
      still be visible and, ideally, tappable to the fuller detail once the Phase 2/3 detail
      surface exists). Design the compact form before building. **Constraint:** the glance
      surface (the widget) must still show SPEC's one-line disruption summary + count
      (`SPEC.md` *Disruptions*) — a screen-reader-only label or an optional tap doesn't satisfy
      sighted at-a-glance use, so icon-only is a **card** option; on the widget the compact
      icon accompanies the visible summary rather than replacing it.
- [x] **Journey alerts: a closure at the destination** (maintainer, 2026-09-24). The journey card
      (lines' status and boarding closures already on it) also shows its far end's closure or move.
      The rest of the item below is on hold: the maintainer's use case is the journey, in the
      direction from the nearer end.
- [ ] **Service alerts on favorite routes, at the very top** (maintainer, 2026-09-23). Show the
      line-status alerts affecting the user's starred routes (starred rows, and starred journeys
      once they land) at the very top of the near-me list, even when that line isn't at a nearby
      stop. Line status is one batched `/Line/{ids}/Status` request, so the extra cost is at most
      the uncached starred lines joined into that request (or one more batched request) per
      refresh, reusing the 90 s cache. **Depends on** the pin-vs-star decision above (*Reconsider
      the detail star*): today's star is "pin to top", ranking only (SPEC), so treating it as a
      favorite could alert on an old pin with no row left to unpin it — settle that model (or a
      distinct favorite-route set) first. Open: how an alert here relates to the same line's alert
      further down (dedupe or both), and whether a dismissal carries across. Touches SPEC
      *Disruptions* and the near-me ordering.
- [ ] **Commute disruption announcements, without being noisy** (requested 2026-09-19,
      on-device). Notify the user of a disruption to *their* commute — a watched line/stop on
      the routes they take — but only when it matters: scoped to their working-hours / trip
      windows (Phase 2 favorite-destinations item) and de-duplicated so an ongoing disruption
      doesn't re-notify. The whole design turns on not crying wolf; a notification is a battery
      and attention cost, so this is a product + battery decision, not a quiet add (SPEC *Cost
      and reliability*). **It also needs a background-refresh mechanism the current model
      doesn't have**: SPEC only polls while the app is open (plus opportunistic widget
      refresh), so a closed-app trip window can't discover a new disruption to announce.
      Designing that means naming the periodic worker (e.g. `WorkManager`), its wakeup/request
      cadence, the added TfL rate-limit pressure, and the stale/error behavior — record those
      before implementing, so this doesn't ship as either a nonfunctional alert or unplanned
      background polling. **Cost £0** (the worker's TfL polls carry the same watched stop/line
      IDs to the same recipient, no new service or key), so likely **no Play Data Safety change**
      — same recipient and data categories as the on-demand departures fetch — confirmed when
      built; the real costs are battery and TfL quota, above. It also needs the runtime **notification permission** (Android 14+):
      an opt-in request and an explicit denied-state behavior — don't run the worker (burning
      battery and TfL quota) while every alert is invisible — with the permission behavior
      recorded in SPEC.
- [x] **National Rail departures, first cut** (maintainer, 2026-09-24). With the user's own Rail
      Data Marketplace key (pasted in Settings, like the TfL key), a rail station's departures
      also come from Darwin's `GetDepartureBoard`, looked up by CRS from a NaPTAN-built
      TIPLOC->CRS table bundled with the app and rebuilt weekly with the station list (an exact
      join on TfL's `910G` id; OGL v3, credited in About). Cancelled and "Delayed"-without-estimate
      trains are left out; TfL-run services come from TfL only; a failed board keeps the TfL rows.
      App and widget. Built against the documented response shape, not yet checked live.
  - [ ] **Re-check Play Data Safety before the release that ships this**: no new data type
        expected (SPEC *Data source*), but confirm the form and the privacy-policy link.
  - [ ] **Check the board parser against a live response** and record a real fixture (a public
        station, no key in the fixture). The endpoint in `KtorDarwinClient` matches the Rail Data
        Marketplace's own for the Live Departure Board product (maintainer, 2026-09-24).
  - [x] **Show delays and cancellations honestly**: a "Delayed" train with no estimate as "Delayed",
        a canceled one as canceled, rather than leaving them out. Done for the in-app list and a
        route's page (maintainer, 2026-10-02): such a train comes apart from the timed ones
        (`RailBoard.untimed`, its own `UntimedTrain` type, never a `Departure` in a list of them),
        rides beside a stop's arrivals (`TflClient.untimed`, `StopArrivals.untimed`, never saved),
        and joins its line's row and destination line in its place (`DepartureRows.withUntimed`,
        `Countdown.entries`).
    - [x] **A line or destination whose every train has no time**: drawn as its own row or
          route line, after the coming trains (`DepartureRow.hasTrains` tells it from a status
          row; `DepartureRows.withUntimed` gives it a row, `destinationLines` a line), and its
          page follows its own train (`followedDeparture`).
    - [ ] **On a trip's boards, the widget and the watch**: each leaves them out, as before. A
          trip's board and route timing read only the timed trains; the widget and the watch draw
          from the saved snapshot, which doesn't carry them (a schema change).
  - [x] **Say why a rail line has no times** (maintainer, 2026-09-24): a status row says "No key"
        (opening Settings) with no key set, "No data" when the board failed or none covers it,
        and a dash when its source answered with no trains — TfL lines included.
  - [ ] **Decide how every user gets National Rail times** (maintainer's call, 2026-09-24). Today
        each user pastes their own key, which is free but a chore most won't do. First read the Rail
        Data Marketplace license for the Live Departure Board product: its request quota, and
        whether it allows showing the data to the public and sharing one key. Then pick:
        - **Keep per-user keys**: £0, no server; low uptake.
        - **Ship one key in the app**: £0, but anyone can extract it from the APK, every user
          shares one quota (roughly 900 users at ~200 board requests a day each, if the quota is
          ~5M per four weeks), and one abuser gets it revoked for all; the license may forbid it.
        - **A small caching server holding the key**, serving each station's board for ~30 s:
          hosting at a few pounds a month, a service to run, and a privacy and Play Data Safety
          change (it sees station codes and IP addresses); its load grows with busy stations, not
          users. The recommended shape if the license allows public use.
  - [ ] **Route pages and journeys for National Rail**: the stop list and journey matching use
        TfL's route data, which National Rail services may lack.
  - [ ] **One priority order for every TfL request** (maintainer, 2026-10-03): the near-me list now
        sends each stop's closure check only after its departures, but other screens (trips, farther
        cards, the widget) still share the client's request pool first come, first served. Swap the
        pool's plain permits for a priority queue, each call tagged with a class: departures, then
        line status, then closures, then hubs and routes.
  - [ ] **A closure found mid-load moves the list**: its notice card appears under the heading and
        pushes that place's departures down. Show it behind "Tap to see" when the place is on screen,
        as a late stop's card is (*Freshness → Cold load*).
  - [x] **Build a cold load's progress off the main thread**: each report while stops land rebuilds
        the part-shown state on the main dispatcher, walking every shown stop (what failed, which
        lines are still checking, what's unknown). Build it on a worker and publish the result,
        newest wins (AGENTS *Main thread*).

## Phase 4 — Widget

- [x] Glance widget rendering from the persisted snapshot (no network on the render
      path); home-screen first. Tap opens the app; stamp + stale note per D4. `widgetModel`
      (the render decision) is pure and unit-tested; the layout is node-tested via Glance's
      unit-test harness (see below). A device eyeball of the real rendering is still owed.
- [x] Lock-screen eligibility on Android 16 QPR (standard widget, no `not_keyguard`
      opt-out); one implementation for both placements. `widgetCategory="home_screen|keyguard"`
      in the provider info — placement needs a real Android 16 QPR device to confirm.
- [~] Refresh strategy beyond app-driven push (decided 2026-09-19 — see *Widget follow-ups*
      below). The app calls `updateAll` on every fetch, so the widget follows the app's last
      refresh; `updatePeriodMillis=0`. The **honesty** half landed in #44: the widget
      schedules one render-only redraw at its staleness boundary (a `WorkManager` one-shot per
      snapshot), so a closed-app widget flips to the stale `?` treatment on its own instead of
      holding a live-looking countdown forever (SPEC D4). The **data-refresh** opt-in landed
      too: the "refresh widget every minute" setting drives a self-rescheduling WorkManager
      one-shot chain (mechanism B), off by default, for the screen-on kiosk case. What still
      remains: **refresh-on-unlock by default** (`ACTION_USER_PRESENT`), and the
      screen-**off** guarantee via a foreground service (mechanism A) — see *Widget
      follow-ups*.
- [x] Layout coverage of the widget states, two complementary forms. `WidgetContentTest` uses
      Glance's own unit-test harness (`runGlanceAppWidgetUnitTest`, under Robolectric for a real
      `Bundle`) to assert the emitted layout nodes (no-data, no-rows, stale-empty, fresh-row,
      branching, via-branch, stale-withheld). `WidgetScreenshotTest` **pixel-captures** the widget
      by rendering it to RemoteViews with `GlanceRemoteViews.compose` and inflating them to a
      `View` (fresh light/dark, stale, partial, no-departures, empty, and the 180x110dp minimum
      size), so clipping/sizing/color regressions are caught —
      recorded via its own `--tests` allow-list step in the `screenshot-tests` job. An on-device
      eyeball of the real host rendering is still owed.
- [x] **Widget parity: starred rows pinned, and per-(destination, branch) lines** (the A/B/C
      "how faithfully the widget mirrors the in-app list" decision — maintainer chose full
      parity, 2026-09-19). `provideGlance` now loads the starred store (off the render path)
      and `widgetModel` applies `DepartureRows.pinStarred` before the cap, so a starred service
      past the six-row cap is lifted to the top (SPEC D8) instead of dropped. `WidgetRow` now
      renders per-(destination, branch) lines via the shared `DepartureRows.destinationLines`
      (used by the in-app card too, so the two surfaces can't drift), replacing the
      headline-only filter that dropped a branching row's divergent destinations; the widget
      shows the via-branch in the board's short form (`abbreviateBranch`, since Glance can't
      measure width). **Closest-first / nearby-dedupe ordering is NOT part of this** — it stays
      the deferred follow-up below (needs distances the snapshot doesn't carry, and is moot once
      Phase 2's watched stops replace the interim nearby source).
- [ ] Widget feeds off the interim *nearby* set (the last stops the app fetched), via a
      save-only `WidgetSnapshotStore`. Replace with Phase 2's user-chosen watched stops so
      the widget shows a stable set rather than "wherever you last opened the app".

### Widget follow-ups (decided with the maintainer 2026-09-19)

The widget review surfaced findings that push against the **persisted snapshot's deliberate
design** (`DeparturesSnapshot` KDoc: it carries *only* the honest last-good departures and
each stop's age — never transient disruption/line-status or refresh-failure state, because a
persisted point-in-time closure or line status ages into a claim we can't stand behind).
The maintainer settled each; #44 ships the render surface with the honest fixes (per-row stale
withhold, explicit empty states, fresh-before-truncate cap, corruption logging, layout tests),
and these carry the rest as their own PRs:

- [~] **Widget refresh (own PR).** The opt-in **"refresh widget every minute" setting (off
      by default)** landed via **mechanism B** — a self-rescheduling WorkManager one-shot
      chain (`WidgetRefreshWorker`) that re-fetches the widget's persisted stops ~1/min and
      saves the snapshot, holding while the screen is on and deferred by Doze otherwise. That
      covers the screen-on kiosk/home case. Two pieces still remain:
      - [x] **Tap the header to refresh in place** (maintainer, 2026-10-04): an expedited one-shot
        refresh of the stored stops (`WidgetTapRefreshWorker`), "Refreshing…" and the failure
        reason in the note; the departures still open the app. Needs a device check that a tap
        from the home screen runs it at once.
      - ~~**Refresh on unlock**~~ — dropped (2026-10-05). Android 8+ won't deliver
        `ACTION_USER_PRESENT` to a manifest receiver, and on Android 14+ (our minSdk) SystemUI
        sends it with `DEFERRAL_POLICY_UNTIL_ACTIVE`, so a runtime receiver in a cached process
        doesn't hear it until something else wakes the process: an unlock to glance at the widget
        would almost never refresh it. Only a live component (mechanism A) or a cooperating
        launcher saying it's showing the widget could.
      - [x] **Stamp the widget with a clock time, not an age** (maintainer bug report, 2026-10-05:
        a widget not redrawn for ~40 min still read "Updated 7 min ago" beside "13:08?" at 13:44).
        The debug log now says how late each boundary redraw ran, and how many stored stops each
        render keeps against the nearby set.
      - [ ] **The widget left out nearby bus stops the app listed** (maintainer bug report,
        2026-10-05): it showed one station while the app showed several bus stops 100 m away. Read
        the new render lines in the next report (stored stops vs nearby set) to tell a snapshot that
        lacked them from a nearby set that dropped them.
      - [ ] **Boundary redraws Android defers** — if the log shows them running long after due, an
        inexact `AlarmManager` alarm allowed while idle may hold the time better than WorkManager.
      - [x] **The trip on the widget** (maintainer, 2026-10-05): while On the way follows a trip,
        the widget shows the departures at the next change, and the next step above them where
        there's room, from the summary
        the watch is sent, redrawn each update. Needs a device check that it updates with the
        app closed and goes back to the departures when the trip ends.
      - [ ] **Let the rider choose what the widget leads with** (maintainer, 2026-10-05: "might be
        intentional to prefer tube or let the user choose"): today it mirrors the near-me list;
        a mode preference (tube first, say) is the candidate, after the bugs above.
      - [ ] **Mechanism A — foreground service, the screen-off follow-up** (recorded as the
        maintainer asked: *start with B, record A as a possible follow-up if B doesn't work*,
        2026-09-20). B is Doze-deferred, so it does **not** guarantee the exact minute with the
        screen off; a foreground service would, at the cost of a persistent notification, the
        Play foreground-service-type policy that carries (the snoozemo precedent), and more
        battery. Take this only if the screen-on case proves insufficient on a real device.
      - Motion-triggered refresh is a *future supplement* only (it shows stale data for the
        first seconds after someone walks up, so the periodic loop stays the reliable core).
      (Note: this is about fetching **new data**. The separate *honesty* case — a closed-app
      widget holding a live-looking countdown past the staleness threshold — is already
      handled: #44 schedules a one-shot render-only redraw at the staleness boundary that flips
      it to `?` without any fetch, SPEC D4.)
      - **Render-only countdown tick while closed (Codex P1 on `ae0cf78`, deferred here).**
        Between redraws the widget's countdown text is static, so within the freshness window a
        closed-app countdown can read up to the staleness threshold optimistic ("2 min" for a
        train that has departed) before the one-shot redraw flips the whole widget to `?`.
        Making countdowns *advance/drop minute-by-minute* while closed needs periodic
        render redraws (~1/min per upcoming departure / label boundary) — the same periodic
        wake cadence this item defers (a battery decision, SPEC D5), just render-only rather
        than fetch+render. So it rides the "Live widget" loop above (which re-renders on its
        cadence anyway); the honesty floor (bounded optimism, then a stale flip) is the interim
        on the default path. Scheduling a redraw *per departure boundary* was considered and is
        the same cadence by another name (a chained wake every few minutes), so it isn't a
        cheaper middle ground — it's the deferred loop.
- [x] **Carry disruption / line-status into the widget (own PR).** Maintainer: *yes, but a
      follow-up.* **[landed]** The snapshot keeps each shown line's status check stamped with its
      time (good ones too, so two writers merge newest-first); the widget marks a disrupted row
      ("⚠ Severe Delays", a budgeted line never dropped to fit) and shows a suspended line with no
      predictions as its status alone, withholding any mark at the shared staleness threshold. The
      widget's own refresh re-checks the lines with the arrivals (one extra request per cycle, lines
      checked in the last 90 s reused). SPEC *One widget, many surfaces* records the reversal. A line with no current check reads
      "Couldn't check for disruptions", as in the app. Stop closures are still not persisted. The
      watch renders them too: *Disruptions on the watch* (Phase 6).
- [x] **Re-check line statuses when arrivals fail (own PR, Codex P1 on #317).** *Done: with no
      arrivals to save, the widget's refresh still checks the stored stops' lines
      (`WidgetRefresh.refresh`), and it and the app's refresh store the answered checks alone
      (`SnapshotStore.updateLineStatuses`), newest per line, the arrivals left as stored.* Both writers
      check statuses only alongside arrivals they save: the worker skips the status call when
      every arrivals request failed, and the app's save gate needs fresh or carried arrivals. So
      during an arrivals-only outage a newly declared suspension doesn't reach the widget. Its rows
      already show `?` and the stale note then, and an older check ages out at the threshold rather
      than being shown, so nothing false is asserted, but the news is late. Fix: merge a successful
      status check into the stored snapshot on its own, keeping the last-good arrivals.
- [x] **Distrust a snapshot the clock was set back across (own PR, Codex P2 on #317).** *Done:
      a fetch's age is told by the device's monotonic clock (`SteadyClock`, maintainer 2026-09-29):
      each fetch is stamped in the process's steady frame, the stored snapshot records which boot
      and frame its stamps are in, and the watch keeps its clock's frame with each envelope, so a
      stop fetched before the clock was set reads at its real age everywhere, however long it's
      held. Across a reboot, a stamp later than the boot's start reads as stale on every read
      (`fromEarlierBoot`: it was fetched before the boot began). For line checks, a stamp more
      than a minute ahead of the clock reads as stale (`Staleness.isStale`), and every write to the
      stored snapshot restamps such a stop as stale and drops such a line check
      (`distrustingFuture`).* A check
      or a stop's arrivals stamped in the future (the clock moved back) comes back into trust once
      wall time reaches its stamp, with no record that it was once seen in the future. Arrivals
      already behave this way (a negative age reads fresh), and a line check is rejected only
      while it's future-dated, so with no refresh in between, a disruption can show for up to the
      threshold after it was really that old. Fix both together: record the rollback when first
      seen (a writer drops or invalidates future-stamped entries on its next save, and a render
      treats them as stale), rather than a check-only memory that leaves the countdowns beside it
      trusted.
- [x] **Age line checks and closure checks by the steady clock too (follow-up to #371).** *Done:
      a line status check and a stop closure lookup are stamped by the steady clock as a fetch is,
      in the list, the widget's refresh, the trip and the shared closure cache, and aged by it
      (`LineStatusCheck.isLive`, `checkCurrent`, the reuse windows). The stored snapshot keeps line
      checks in its steady frame from version 4, moving them as it moves fetches; an older file's
      wall-clock checks are taken into it as the wall clock reads them. The watch is sent them as
      the phone's wall clock reads them, as it is fetches.* A fetch's age was the monotonic clock's
      (`SteadyClock`), but a line status check and a stop closure check were still stamped and
      aged by the wall clock, with only the stamp-ahead rule behind them: one made before the
      clock was set back read as unchecked until the clock passed its stamp, then as current,
      until it was as old by the wall clock as a stale countdown.
- [ ] **Persist a refresh-failure kind / incompleteness for the widget (own PR, rides with the
      above).** *Incompleteness landed (2026-09-24): the snapshot persists the requested stops a
      refresh couldn't get (`missingStopIds`), and the widget is `uncertain` while any is. The
      typed failure kind (offline / rate-limited / unreachable) is still open.* Same schema reversal: the snapshot excludes the transient refresh-failure flag
      by design, so the widget can't say *why* data is old beyond the age stamp. Add a typed
      failure to the persisted schema so the widget can render offline/rate-limited/unreachable.
      **Includes the absent-stop case (Codex P1 on #44):** on an *initial* multi-stop refresh
      where one stop fails, `Snapshot.mergeStop` omits the failed stop entirely, so every
      persisted stop is `arrivalsFresh=true` and fresh — the widget's `uncertain` predicate
      can't tell a requested stop is missing and shows a clean "Updated just now". Persisting
      the expected stop set (or a `partialRefresh` flag) alongside the snapshot lets `uncertain`
      catch it. Deferred with the rest of this family (the snapshot deliberately carries only
      honest last-good + age); the per-row withhold + age stamp are the honesty floor until
      then, and it's moot once Phase 2's stable watched stops make the expected set known.
- [x] **Deduplicate the widget's nearby set before the cap (own PR, Codex P2 on #44).** The
      in-app view calls `DepartureRows.nearbyDeduped(stopDistanceMeters)` so a line served by
      several adjacent stops collapses to its nearest stop; the widget renders from the
      persisted snapshot, which carries no distances, so it can't. Fix needs persisting the
      distances (or a widget-ready deduplicated selection) alongside the snapshot — a
      selection/schema decision, and moot once Phase 2's watched stops replace the interim
      nearby source. Until then adjacent stops can double up a line/direction in the six slots.
      *Done: the app saves the nearby stops' order nearest first (not their distances, which would
      put the rider's position in the backed-up snapshot), and the widget folds by it. Without the
      distances a route's directions aren't kept together at a slightly farther stop, so the widget
      can show them from two stops where the app shows one.*
  - [x] **The watch folds a line to its nearest stop too** (Codex on #473). The tile, the watch app
        and the default complication mirror the widget's rows but still render
        `DepartureRows.across` unfolded, so they can show a farther stop's copy of a line the widget
        folded away. Carry `nearestFirst` through `WatchEnvelope` and apply the same fold in
        `TileTimeline` and `ComplicationTimeline` (both sides of the phone/watch build, so an older
        watch build ignores the field).
        *Done: `WatchEnvelope.nearestFirst` carries the order for the stops sent, and the tile, the
        watch app and the complication fold with the widget's own `DepartureRows.glanceFolded`.*
  - [ ] **Should the saved line choices follow the widget's own background refreshes?** (Codex on
        #550, nine findings on one mechanism; maintainer's call.) The app works the choices out
        again on every write it makes (a save, a fix, a status-only write, a filter change), but
        the widget's background refresh stores new arrivals and statuses while the app is closed
        and can't: the choices need the stops' distances, which are never stored. So between app
        runs the widget and watch keep the stop the app last chose, falling back to the order when
        that stop no longer shows the line. Options: accept that (the choices mean "the app's last
        view"); store a coarse grouping instead of distances (which stops lie within 50 m of each
        other) so the store can recompute on any write; or have the worker skip the choices and
        fold by order alone after a background refresh.
  - [ ] **Does a saved complication pick follow the fold?** (Codex on #549, #550; maintainer's
        call.) A pick made for a line at stop A keeps showing A after a move folds that line to
        stop B on the widget, tile and default complication. Kept as picked for now: dropping the
        pick falls back to the top row, which can be another line altogether. Alternatives: remap
        the pick to the folded stop's row for the same line and direction, or drop it.
  - [x] **The widget and the watch show each line from the app's stop** (maintainer, 2026-10-04).
        The order alone split a route's directions across two stops where the app keeps them
        together a few steps farther. The app now saves, with the order, the stop its list shows
        each line from (`DeparturesSnapshot.nearbyChoices`, stop ids only), and the fold follows it.
        They're worked out again from the stored rows at startup, at each fix, and whenever an
        alert is dismissed or a mode hidden, as the list's own fold reads both.
- [x] **Scope the widget snapshot to its nearby set (own PR, Codex P1 on #44).** The widget
      snapshot is written only on an *authoritative* arrivals cycle, so if the user moves and
      the new set's fetch fails (offline/rate-limited), the previous location's departures stay
      on the widget — and because the widget shows no stop name, they read as live for the new
      context until the stamp ages them stale. An empty nearby resolution never mounts
      `DeparturesForStops` at all, so it can't clear either. Fix: scope/clear the persisted
      snapshot when the resolved nearby set changes (or resolves empty) and push a widget
      update. Same interim-nearby-source family as the dedupe bullet — the snapshot carries the
      stop *set*, so a fix is app-side (clear-if-different-set + `updateAll`), not a schema
      reversal, but it's throwaway surgery on the interim source and needs a device to verify
      the `updateAll`/blank-flicker behavior. Much *rarer* once Phase 2's stable watched stops
      replace the location-derived set (the set then changes only on an explicit edit, not on
      every location drift), but not eliminated — an edit whose first fetch fails still leaves
      the old set on the widget — so this scoping stays an open task even after Phase 2, until a
      render-time snapshot-vs-set comparison (or equivalent invalidation) lands. The aging stamp
      is the honesty floor meanwhile. **PR #53 attempted the app-side clear and was deferred
      (closed unmerged) — see *Decisions needing review*: seven race findings in three review
      rounds all traced to the same shape (an activity effect clearing the shared store
      concurrently with the per-set writers), which is a design signal, not seven bugs. Revisit
      as a render-path scoping (the redesign option below), not as more race patches.**
      *Done as the render-path scoping (maintainer, 2026-10-02): the app stores each resolved
      nearby set in a file of its own (`DataStoreNearbySetStore`), written by one collector in
      the activity and never by the departures writers, and the widget and the watch show only
      those stops from the snapshot (`DeparturesSnapshot.scopedTo`), a new one unfetched counting
      as missing. The background refreshes still refetch the stored stops, so a live-refresh
      widget refreshes the old place's stops (hidden) until the app saves the new set's.*
- [x] **Size-aware row cap (Codex P2 on #44, landed with the header row).** The fixed six-line
      cap overflowed even the default 240x180dp cell. The widget now uses `SizeMode.Responsive`
      height buckets and derives its line budget from the bucket's height (`widgetLineBudget`,
      conservative per-line cost), pixel-checked by the full-budget and minimum-size screenshots.
- [x] **Per-row widget stacking from the row's own width (Codex P2 on #155).** The widget stacks
      a departure onto two lines only on the narrow (<220dp) bucket at 1.3x font or more. A wide-bucket
      row with three times ("0 · 3 · 6 min") at a large font can still squeeze its destination out.
      Fix properly: decide stacking per row from its estimated countdown width, count the line budget in
      dp instead of lines, and add width buckets (the 220dp bucket stands for every width above it).
      Glance can't measure text, so this stays an estimate; judge it on a device. *Done, per
      destination line rather than per row (so a line a tight budget drops can't stack the ones it
      keeps; Codex on #457): a line stacks when its countdown (with the planned-work calendar) leaves
      under about five characters of destination beside the pill (`widgetRowStacked`, widths
      estimated by character), and a status drawn alone on the reason it shows; the budget is the
      rows' height in dp and each line costs what it draws (`LineCosts` on the shared
      `BudgetedRows.select`, which the watch tile keeps counting in lines); and a 300dp bucket joins
      180 and 220 (three widths by five heights, under a host's sixteen). A narrow widget at the
      default font now stacks a line with two or three times too. The estimates still want a look
      on a device.*
- [x] **Named Overground pills on the widget (own PR, Codex P2 on #44).** The widget now draws
      the six named Overground lines hollow, as the app and the watch do (`widgetPillStyle`, from
      the shared `pillColors`): Glance has no border, so the ring is the accent behind a box of the
      widget's background, and every pill takes the one fixed width so the ring's insets can't make
      it narrower. The accent is nudged for a light and a dark background (Material's baseline, since
      the host's dynamic color is known only when it draws). Pixel-checked by `WidgetScreenshotTest`
      (`widget-overground*.png`); an on-device look at the real host is still owed with the rest.
- [ ] **Widget should reuse the in-app row, differences as parameters (own PR, maintainer
      2026-09-21).** The widget re-implements the departure-row shape (destination + branch
      label, countdown, pill) in Glance rather than sharing the in-app card's composable, so a
      rendering rule has to be applied twice and can drift — the branch-join format was just
      changed in both `MainScreen` and `StopDashWidget.widgetLineLabel` for exactly this reason.
      Factor the shared row/label logic into one place and drive the genuine differences
      (Glance vs. Compose primitives, the widget's no-width-measurement constraint, its pill
      fallbacks) through parameters. Reduces the two-surface drift the branch format, the
      Overground pill, and the abbreviation ladder each already pay for separately.
      **Look the same, too** (maintainer, 2026-09-23): the widget should read as a compact main
      view, not a separate design. Glance emits RemoteViews, so it can't call the Material
      composables — share a pure per-row UI model (label, countdown text, pill fill/hollow,
      status badge, stop header) computed once, with two thin renderers. Gaps vs. the main
      view today: status badges (⚠ / "Suspended", needs the persisted-status item), hollow
      Overground pills, the card grouping, and the app icon in the header. Landed: the stamp on
      the title row, a pill on every line, and stop headers (shared `groupHeaderTitle`).

- [x] **Judge a dismissal at read time, not as a stored flag** (Codex on PR #322; maintainer,
      2026-09-28: "do it how you like"). The widget's draw and the watch publish apply the
      dismissed set; nothing stores a copy, so no writer can bring a dismissed mark back.

## Phase 5 — Distribution and polish

- [x] **"Update available" indicator (maintainer, 2026-09-21).** A red dot on the
      departures overflow (⋮) icon plus an "Update available" menu item that opens the Play
      listing, driven by a release-only Play In-App Update *availability* check
      (`PlayUpdateChecker`, gated by `PLAY_UPDATE_CHECKS_ENABLED`), rechecked on each
      foreground. Detection copied from the sibling repos; the presentation is a dot, not
      their banner (maintainer's call), and the tap opens Play rather than running the
      in-app flexible flow. Follow-up if ever wanted: the full in-app flexible download +
      restart flow (the peers' behavior) behind the item.
- [ ] Play internal-track deploy proven end to end; signing keystore via secrets. **The
      pipeline itself landed in Phase 0** (see the Deploy-job item there and
      `dev-docs/play-store-internal-track.md`); what remains is the human setup — upload keystore,
      Play Console app + seed upload, service account, the five `production`-environment
      secrets, and the Data Safety form — and one real push confirmed to reach the internal
      track.
- [ ] Consider a CI check that keeps `docs/play-store/icon-512.png` in step with the icon
      drawables. Measured on the siblings: **folding the assertion into an existing
      screenshot class is near-free; a dedicated Roborazzi step ≈ 8–9s/run** (the ~90s
      Robolectric cold start is paid once by the first screenshot step, so later steps run
      warm). Prefer folding over a dedicated step. Bigger CI-time lever, if it ever
      matters: batch the screenshot job's single-class steps the way simmo did (9→4 saved
      ~3min there) — the icon steps are not the cost. The 512 landed without a check for
      now, script-rendered via `scripts/render-store-icon.py`.
- [ ] **Fan out the release-notes-walk hardenings to the siblings** (Codex, PR #58): the
      "Build release notes" walk in `ci.yml`'s `deploy` job — copied verbatim from the
      sibling Android repos — carried several latent bugs that stopdash's copy now fixes
      and simmo / snoozemo / typelauncher / clothescast still have: (1) both the outer
      workflow-runs query and the per-run jobs query used `… || true`, masking an API
      failure as "no runs / not published" and risking a wrong range base (dropped or
      repeated notes); both now fail closed. (2) the walk skipped runs by head SHA, so a
      `workflow_dispatch` re-deploy of an already-published tip, and a re-run whose
      earlier attempt had published, both went undetected → older base picked, used
      versionCode re-uploaded. The walk now skips no runs and queries per-run jobs with
      `filter=all` (all attempts), relying on the "did the Play-upload step succeed" check
      + the supersession guard. Port to the four siblings' identical walks; maintainer
      coordinates the fan-out (one repo at a time; simmo is private/billed so it goes
      last). (3) Deferred (Codex, PR #58): in the rare case-2 fallback (nothing in the
      searched window has published yet — i.e. before the first-ever Play upload, or a long
      outage), the base is `${oldest_run_head}~1`, which includes only the oldest push's tip
      commit; if that push carried several commits (rebase-merge lands them together), the
      earlier ones are omitted and lost once this run becomes the next base. Correct fix
      needs the head of the run one older than the oldest seen, which the page cap may hide —
      entangled with the redesign below.
      Deeper option if this keeps producing edge cases: base the range on a durable
      marker (the last `v<versionCode>` prerelease that a Play upload accepted) instead of
      reconstructing it from the Actions API — a design change, its cost being that a
      GitHub prerelease is created even when the Play upload skips, so the marker must
      still encode "reached Play". Maintainer's call.
- [ ] **Peer-parity sweep** (requested 2026-09-19, on-device): confirm nothing peer-standard
      from the sibling apps is missing before release. The already-tracked peer features are
      the shareable debug-log export (its own item below), Settings (Phase 2), the
      licenses/About screen (above and Phase 0), and the Play internal track (above) — this
      bullet is only the check for anything else the peers have that fits here.
- [x] Fail a **release** build when the git-derived versionCode/SHA fell back (a
      source-archive or no-git build): Play rejects a non-incrementing versionCode, so a
      silent fallback of `1` is wrong for a shipped artifact (Codex, PR #4). Landed with the
      deploy job in place: `:app:checkReleaseVersion`, on the release variant's first task,
      fails when git can't read the history or the clone is shallow (a short count is as wrong
      as `1`); a debug build still takes the fallback with a warning. CI asserts both failures.
- [ ] Licenses / About screen finalized.
- [ ] **Shareable bug-report export of the on-device log, with travel data redacted.**
      A user-shareable export of the diagnostic log so a bug report can carry it. The
      on-device logging exception does **not** extend to an artifact that leaves the
      device, so the export **redacts travel data** (stop IDs, line ids) — a shared file
      is subject to the same rule as any other artifact that leaves the machine
      (`AGENTS.md` *Privacy*). `docs/PRIVACY.md` already commits to this redaction; this is
      the item that implements it. **Cost £0** (a user-initiated share via the platform
      sheet, no service stopdash runs); the hand-off is a **Play Data Safety** consideration —
      a new off-device channel even after redaction — so the redaction is what keeps it a
      no-op for the declaration rather than a new data type collected, confirmed when built.
      **Note (maintainer, 2026-09-20):** removing the stop/line IDs does keep this export
      location-safe, but it strips exactly the context a routing/location bug is diagnosed
      from — so for those reports the consent-gated richer report below (which includes the
      location openly) is the better tool, not this location-redacted one. This item stays as
      the location-safe option; it is not the one that reveals location.
- [x] **A richer shareable bug report — exact location — behind a consent gate** (requested
      2026-09-20; **maintainer decided 2026-09-20: include the exact location, gated by a consent
      dialog**). Shipped as the overflow's *Send bug report*: it shares the diagnostic log (not
      redacted; since 2026-09-30 a busy run's oldest lines are left out so the report stays
      small enough to share), the **exact location**, and the **per-stop distances** via the platform
      share sheet, behind a **consent screen that spells out exactly what leaves** and a persisted
      **"don't ask again"** opt-out. Rides the shared `mikelward/androidlog` `DebugReport`
      (clipboard + `ACTION_SEND`), reuses the retained nearby fix so the coordinate and distances
      agree, and includes the location **openly, under consent** — the honest report, not a
      location-safe one. `docs/PRIVACY.md` and `SPEC.md` describe the channel in those terms; the
      **Play Data Safety** hand-off is a user-initiated share of diagnostics + location. Cost £0.
- [x] **Add the screenshot to the bug report.** `androidlog` 2.1 gained the optional
      `DebugReport.deliver(screenshot = …)` argument, and 2.2 added the capture itself as the
      shared `ReportScreenshot.capture(activity, dir, log)` — a PixelCopy of the Activity's own
      window (which excludes the consent dialog's separate window), the off-main buffer, the
      age-based prune, and the recycle. stopdash took it at `2.2.69` and calls it off the main thread,
      minting the `FileProvider` URI from the returned file and handing it to `deliver`. A failed
      capture is a text-only report, never a dropped share. The `FileProvider` +
      `res/xml/file_paths.xml` (cache path) stay app-side, and the screenshot is named on the
      consent screen (`bug_report_consent_body`), in `docs/PRIVACY.md`, `SPEC.md`, and this repo's
      Privacy exception. This completes the richer consent-gated report above. (First shipped
      app-local at 2.1.68; the capture moved to the shared library at 2.2.69, so the fleet no
      longer carries divergent copies.)
- [ ] Finalize the store-facing privacy disclosure (location, watched stops, the TfL
      requests) and the Play Data Safety answers — building on the debug-log disclosure
      that landed in Phase 1.

## Phase 6 — Wear OS

- [ ] **Show departures on a Wear OS watch** (requested 2026-09-24). A tile first, then a
      complication, then a small read-only watch app. All of them render the widget's rows from a
      snapshot the phone pushes over the Wearable Data Layer. The watch never calls TfL, holds
      no key and needs no location (companion model, "option A", chosen for the first
      version). The staleness rules carry over (D4): the tile's countdowns tick from a timeline
      and turn stale at the shared threshold, with no polling. The plan, the standalone
      alternative, privacy, battery and testing are in **`dev-docs/wear-os.md`**. The code is
      being built now (maintainer, 2026-09-24). The package rename to `app.stopdash` landed,
      and the maintainer decided to launch on 2026-10-02: the watch and phone apps share one
      application ID and one listing.
      Decided by the maintainer (2026-09-24):
      - a companion app for the first version, with a standalone watch left for later;
      - the watch shows the widget's stops, with no separate watch-only choice;
      - the Data Layer may relay through Google's servers, with a disclosure.

      The doc's remaining open questions (starred journeys, crash reports on the watch, a
      tile-only first release) can be settled as each step comes up.
      Steps, one PR each:
  - [x] **Release gate (lands with the `:wear` module):** `:wear`'s release tasks fail
        unless the build is run with `-Pstopdash.wearRelease=approved` (it also refused the
        pre-rename `app.stopcast` ID until the rename). CI builds only `:app`'s release and never
        passes the flag, so a deploy can't ship the watch app by accident, and a CI step asserts
        that `:wear:bundleRelease` fails without it. *Lifted 2026-10-02 at the launch decision
        (Release the watch app, below).*
  - [x] **Package rename:** the application ID and every Kotlin package are `app.stopdash`
        (phone and watch together, as the Data Layer pairs by ID), and the app is named
        StopDash everywhere (display name, strings, class and file names, Data Layer paths,
        docs). A new ID is a new app on devices and a new Play listing; see the Play item
        above. The repo's URLs (and its Pages privacy URL) point at `mikelward/stopdash`,
        ahead of the maintainer renaming the GitHub repo.
    - [x] **Station builders renamed too**, with the station index and codes rebuilt from
          live data. TfL now gives Clapham Junction's second id (`910GCLPHMJ1`) South Western
          Railway as well as the Overground, so it keeps its `CLJ` code like St Pancras's two
          National Rail ids; the app shows one board between such twins. An Overground-only
          twin is still dropped.
  - [x] Extract `app.stopdash.domain` into a pure-Kotlin `:domain` module (refactor only; the
        package already had no Android imports).
  - [x] Move `route_topology.json` and
        `RouteTopologyStore` into a small shared Android library module, so the watch groups
        branching services with the same topology as the widget. Move the pure line-pill color
        resolver out of `LinePill` into the same module, so both apps share one palette and one
        contrast rule.
  - [x] Share a versioned watch envelope between the apps, reusing `PersistedStop` unchanged.
        (`WatchEnvelope` in `:shared`; `PersistedSnapshot.kt` moved there too.)
        - It's capped by encoded bytes: departures trimmed to each stop's freshness window
          (plus enough per destination group past the boundary to fill the display cap). Over
          the `DataItem` budget, the whole envelope goes as an `Asset`; only past a hard
          transfer ceiling are the lowest-priority stops dropped, shown on the watch as
          "More stops on phone".
        - First persist `StopArrivals.railFeed` in `PersistedStop`, so the watch's National
          Rail empty states match (`LIVE`, `NO_KEY`, `UNAVAILABLE` in the parity test).
        - It carries a bounded superset of the widget's rows, enough for the app, the
          complication picker and the tile's timeline. Starred rows and the rows complications
          are set to (synced from the watch) always come first.
        - Reusing `PersistedStop` means the watch renders from the widget's own inputs. The
          envelope adds the starred rows' keys so the watch can pin them.
        - It leaves out starred journeys (an open question), and drops journey-only stops
          (`journeyOnlyStopIds`), whose ordinary rows the widget doesn't show. Its nearer-stop
          lists, which can name stops the widget doesn't show, are listed in the privacy
          disclosure.
        - Stars and complication selections are keyed by `StarredRow`'s resolved
          `directionKey`, not TfL's raw direction, so blank-direction siblings stay distinct.
        - Test the round trip (blank-direction siblings included), and pin that it carries no
          coordinate or key.
  - [x] Add a `:wear` module skeleton, plus the phone publishing its widget snapshot over the
        Data Layer. **Prerequisite:** *Persist a refresh-failure kind / incompleteness for the
        widget* (Phase 4), so the envelope carries the expected stop set and the watch never
        shows an incomplete refresh as complete. The phone publishes whenever the watch app is
        installed on a paired watch, connected or not, on every snapshot write and every star
        change. The latest snapshot syncs, and is republished, when the watch reconnects. A
        failed publish is logged and retried by one bounded, unique job. The **same PR**
        discloses the channel: a watch paragraph in SPEC *Privacy* and `docs/PRIVACY.md` (the
        sync may pass through Google's servers), plus the Data Safety determination.
  - [x] Disruptions on the watch, after *Carry disruption / line-status into the widget*
        (Phase 4) added an age-stamped status. The envelope carries the kept stops' line checks;
        the tile, app and complication mark a disrupted line and withhold the mark at the same
        expiry (a timeline break, and a split complication entry). A suspended line's status row
        reaches the watch too. Stop closures stay off, as on the widget. `docs/PRIVACY.md`'s watch paragraph names the line status; no Data Safety change
        (TfL's public status, no new category).
        - [ ] **Device check:** the ⚠ line on a real tile and complication face.
        - [x] **Say why a rail line has no times on the glance surfaces** (Codex on #318). A
              status-only row for a disrupted National Rail line says the app's "No key"/"No data"
              (`NoTimes.of`) where its times would be, beside the disruption, on the widget, tile
              and watch app; the widget reuses the app's translated strings. See *Decisions
              needing review*.
  - [x] Tile: the widget's rows, the data's age, and a staleness timeline.
        - Entries break at each countdown minute, each departure time, and each stop's own
          staleness boundary.
        - No stops, or no envelope yet: an explicit one-line setup state, never a blank tile
          or the previous rows. Stops with no rows show each stop's empty form. A complication
          with nothing to show returns *no data*.
        - A fresh, complete tile's foot is **All stops** (opens the watch app); otherwise,
          including when stops were left out for size, Refresh.
        - [ ] **Tile screenshot test still missing:** rendering the ProtoLayout with
              `tiles-renderer` under Robolectric fails (`NoClassDefFoundError:
              androidx/wear/protolayout/renderer/R$style`, as a test or debug dependency). The
              timeline tests pin every frame's content; the look needs a device or emulator
              check until a renderer or preview-tooling route works in CI.
  - [x] Watch-initiated refresh: a tap asks the phone for one debounced, location-free fetch.
        - The phone answers every request with a typed outcome: refreshed, partly refreshed,
          not refreshed with a reason (such as rate-limited), or debounced. The watch says so.
        - When the phone is out of reach, the watch keeps the last snapshot, stamped with its
          age.
  - [x] Complication: a timeline with one entry per upcoming departure, each counted down by
        the system and replaced by the next when it leaves, then a stale entry at the threshold.
        - A carried-forward stop's entries carry the uncertainty marker.
        - It shows the default row: the top starred row, else the widget's first row.
  - [x] Complication row picker: the user picks which row feeds each complication.
        - The selection syncs back to the phone. This watch-to-phone sync is added to the SPEC
          *Privacy* / `docs/PRIVACY.md` watch paragraph and the Data Safety determination in the
          same PR.
        - A selected stop that leaves the widget's scope falls back to the default row.
  - [x] Small watch app: the same cards in a dense rotary-scrolling list ("towards …" on the
        stop-name line), plus the tile's **All stops** button.
        - A foreground ticker advances countdowns and staleness at each boundary, with no
          polling; tested with an injected clock.
        - `WatchHomeScreenshotTest` (round screen, large font) covers it, already in CI's
          allow-list.
        - [ ] **Device check:** rotary scrolling and the tile's All stops launch need a watch
              or emulator.
  - [x] **Release the watch app** (maintainer, 2026-10-02): the release gate is lifted, and CI
        builds, signs and uploads the watch bundle to the Wear OS internal track (versionCode =
        main's commit count + 100,000,000, so it never collides with the phone's).
  - [ ] Play Console (**maintainer only**): add Wear OS as a form factor, upload the Wear OS
        screenshots (`app/src/test/snapshots/images/wear_*.png`), re-check the Data Safety answers
        against the watch sync, confirm the hosted privacy policy carries its *Your Wear OS
        watch* section (this release updates it), and only then set
        `PLAY_WEAR_TRACK_READY=true`, then the Wear OS review
        (`dev-docs/play-store-internal-track.md`, *The Wear OS app*).
  - [x] **Offer the watch app from the phone** (maintainer, 2026-10-02: both a card and a
        Settings row): a one-time near-me card and a Settings row while a connected watch doesn't
        have StopDash, opening its Play page on the watch (`RemoteActivityHelper`).
    - [ ] **Device check:** a watch without the app shows the offer, Install opens Play on it,
          and the offer goes once the app is installed.
  - [x] **Trip view on the watch** (maintainer, 2026-10-02): the current step of a trip on the
        way, with previous/next buttons to page through steps, plus departures for the next leg
        when they fit. Disclosed as a trip leaving the phone (maintainer, 2026-10-03).
    - [ ] **Device check:** a trip followed on the phone shows on the watch, pages, counts down,
          and goes when the trip ends; out of date once the phone stops updating it.
    - [ ] (Later) A trip followed only by the open app (the service refused) isn't sent.
    - [x] **An ongoing activity on the watch** (maintainer, 2026-10-06): the watch posts its own
          silent ongoing notification as a Wear OS ongoing activity while it shows a trip, one tap
          from the trip; the phone's channel stays quiet.
      - [ ] **Device check:** the icon on a watch face and in the launcher, a tap opening the trip,
            the permission prompt, and the activity going when the trip ends or the phone stops.

## Beyond MVP (not planned)

Directions that would change what stopdash *is*, not steps in the London MVP. Recorded so
they aren't re-derived; none is scheduled, and each needs the maintainer's go-ahead.

- [ ] (Later, open call) **Sync settings across devices** (maintainer, 2026-09-28: "maybe not";
      the phone stays the source of truth for now). Each setting (dismissals, stars, watched
      stops) already lives in one store on the phone that every surface reads, so sync would be
      those few sets, not copies spread through the widget's data. Dismissals merge easily (one
      is only ever added, and a refresh drops it once TfL shows the alert ended); conflicts would
      want a time on each entry (`dismissedAt`). Anything through a server is a privacy and Play
      Data Safety decision: the stops someone watches say where they live and travel.

- [ ] (Later, open call) **Other cities beyond London** (recorded 2026-09-19 at the
      maintainer's request). StopDash is TfL-specific today: the data layer talks only to
      the TfL Unified API, and line colors/codes are TfL's. The **domain layer**
      (`app.stopdash.domain` — stops, departures, staleness) is *shaped* around one
      departures model much of a multi-city version would reuse, but it is **not already
      provider-agnostic**: it carries TfL-specific contracts a second provider would have
      to **normalize or redesign, not just adapt behind an interface** — `TflClient` /
      `TflException`, `StopFinder`'s NaPTAN stop-type defaults, `LineStatus`'s TfL
      `statusSeverity` semantics, and `Departure`'s TfL direction/mode semantics. (Pill
      rendering is in the UI layer, `app.stopdash.ui.LinePill`, not the domain.) So the
      pathway is more than an adapter behind the data layer: the TfL contracts above are
      normalized, and a real-time provider is added. **GTFS** is the common denominator,
      which in practice is three feeds, not one — the static **Schedule** (the stop/route/
      trip catalog nearby-stop discovery and labels need), **Realtime trip updates** (live
      times keyed by the Schedule's IDs), and a **disruption source** (GTFS-Realtime
      *Service Alerts*, which are optional and sometimes a separate operator API): stopdash's
      honesty floor warns about a closed line or stop even with no predictions (SPEC
      principle 1; `lineStatuses`/`stopDisruptions`), so a provider lacking an alerts feed
      needs an honest fallback, never unverified-shown-as-clean. Plus per-city line styling
      and branding (the name reads as TfL-flavored). It's a scope expansion, not a refactor:
      **N data sources**, each with its own cost, rate limits, reliability, and **Play Data
      Safety** answer, and an app identity/branding question. Costs are per-provider and
      unknown until one is chosen — GTFS feeds are commonly free/open, but confirmed per
      city, not assumed. **This records the direction and the shape of the work, not an
      exhaustive feed or contract inventory** — the full scoping is done when the item is
      picked up. Product decision; not on the roadmap. **The full write-up —
      the feeds, the client-only-vs-backend-vs-aggregator fork, why London stays on the
      TfL Unified API, live-vs-scheduled and its UI treatment — is in
      `dev-docs/multi-city-gtfs.md`.**
- [ ] (Later, open call) **Smart-home integration** (recorded 2026-09-19 at the maintainer's
      request). Surface the next departures on a smart-home surface — a routine, a display, a
      voice assistant — so "when's my bus?" is answered without opening the phone. It means a
      new integration surface (Assistant/Home APIs or a local hub) with materially different
      cost and failure modes, and a **Play Data Safety** consequence (departures and possibly
      the watched set crossing to another system), and it changes what stopdash *is* beyond the
      London MVP. Direction only; needs the maintainer's go-ahead and its own scoping — which
      must record the chosen surface's **dollar cost** (hosted API vs. a local hub differ
      sharply; marked unknown until the surface is picked) and its degraded/offline behavior
      before implementation, per *Cost and reliability*.

## Decisions needing review

- [ ] **A rerouting's destinations aren't marked on a line's page (autopilot, 2026-10-03).** A
  Northern line alert after a points failure said northbound trains "will go to" two branch ends,
  and a route page whose stop list began past the failure marked only the destination, the one stop
  the alert said was fine. Taken: names in a "will go to" / "will travel to" list (joined by "and",
  "or" or "/") are treated like a "towards" list, so the chip names the cause instead. Where trains
  "terminate" stays marked. *Alternatives:* keep marking every station named (the generous rule's
  default); also leave out where trains terminate; read the rerouting to say whether this train is
  affected, judged too open-ended. **Reversible:** `AlertStops.DESTINATIONS` and its test. **To
  confirm:** on a device, the next rerouting alert's page.
- [ ] **Which routes get a Fastest / Simplest header (autopilot, 2026-09-30).** Taken: Fastest over
  the first card, which the list already ranks as the soonest arrival StopDash stands behind, and
  none on a lone card or one whose arrival is withheld; Simplest over the card with the fewest
  rides, the earliest shown on a tie, and none when every card rides as often; "Fastest ·
  Simplest" when one card is both. *Alternatives:* Fastest by raw earliest arrival across tiers
  (could crown an "est." route over a live one); "Simplest" read as fewest walking minutes. (The
  rest now sit under one "Other" header: the maintainer's call, 2026-09-30, not autopilot's.)
  **Reversible:** `routeLabels` in `:domain` and `RouteLabelHeader`.
  **To confirm:** on a device, that the headers read well over the cards.
- [ ] **Trip mode toggles: which groups, and where (autopilot, 2026-09-30).** Taken: chips under the
  step-free dropdown atop a trip only (not in Settings), one per `ModeGroups` group under the list's
  names (Tube & DLR, Train, Bus, Tram, Boat, Coach), all on by default and stored as the groups turned
  off; a trip setting of its own rather than the list's hidden modes; walking, the cable car and
  replacement buses always sent; the last group riding can't be turned off (a tap on it does
  nothing). *Alternatives:* a finer split (Overground, Elizabeth line, DLR each their own chip), a
  row in Settings too, sharing the list's hidden modes, or a disabled look for the last chip.
  **Reversible:** `TripModes` in `:domain` and `TripModeChips`. **To confirm:** on a device, that
  the chip row reads well under the three dropdowns.
- [ ] **What the step-free menu says under each level (autopilot, 2026-09-30; Codex, #408).** Taken:
  the maintainer's names stay, and Station and Fully each get a small line under them in the menu:
  "Street to platform, for luggage or a buggy" ("stroller" in US English) and "Onto the train too,
  for a wheelchair". Any gets none. *Alternatives:* longer names in place of the maintainer's; a line
  under the dropdown row itself, which would show all the time. **Reversible:** `stepFreeDetail`
  and its two strings. **To confirm:** the wording, and whether the row should also explain the
  level once the menu is closed.
- [ ] **Max walk in Settings is a dropdown, not radio rows (autopilot, 2026-09-30).** Taken: the
  same one-row *Max walk* dropdown as atop a trip, under the walking speed's radio rows; until the
  stored choice is read it shows "–" and opens nothing. *Alternative:* six radio rows like the
  walking speed's three, tried first: they pushed the rest of Settings well down (the API key rows
  and the live-refresh error fell off the first screen). **Reversible:** `SettingsScreen`'s
  `MaxWalkPicker` call. **To confirm:** that a dropdown beside radio rows reads fine on a device.
- [ ] **Where a glance surface says why a rail line has no times (autopilot, 2026-09-28).** Taken:
  a disrupted National Rail line with no times says "No key" or "No data" after its status, in
  the same text ("⚠ Part Suspended · No key"), on the widget, tile and watch app, so the
  disruption always leads and a tight line cuts the reason, never the alert. A narrow widget at a
  large font (the stacked layout) puts the reason beside the pill and the full status below; the
  watch app wraps. The reason is withheld once its stop's arrivals are stale or its latest refresh
  failed. *Alternatives:* the reason in the countdown's place, styled as one (tried first: at a
  large font it squeezed the alert to "⚠ Part S…", three review findings); or a line of its own
  under the status, costing a line of the budget. **Reversible:** `WidgetDisruption`'s `detail`,
  the tile's `disruption` text and `WatchHome.DisruptionRow`. **To confirm:** on a device, that the
  reason reads at the default font (it's cut first at a large one), and the tile at a large font.
  Tapping "No key" doesn't open Settings on any glance surface.

- [ ] **Check and confirm: a line page with no trains names the alert's stations but lists none
  (maintainer asked for a call, 2026-09-26).** A status row (a suspension, no predictions) has no
  train to follow, so it had no stop list and PR #262's markers couldn't show there (Codex on #262).
  Taken: load the line's stations in both directions in the background, off the render path (a
  route fetch TfL is already asked for when a page opens), and show **only the names** beside the
  alert's chip — no stop list, since which direction or branch to list would be a guess on a line
  with nothing running. A failed or slow load names nothing, and the alert's own prose still says
  it. *Alternatives:* list both directions' stations with ⚠s; list one direction (the row's
  platform or bearing, where known); show nothing, as before. **Reversible:** `rememberLineStops`
  and the `lineStops` fallback in `RouteDetailScreen`. **To confirm:** on a real suspension, that
  the names read well without a list, and that the extra route request costs nothing noticeable.

- **Favorite places storage model (autopilot, 2026-09-26, SPEC D9).** Building the v1 foundation
  (`FavoritePlace`, `FavoritePlacesStore`, DataStore-backed store). Reversible guesses taken, flag
  any to change: (1) **School is a fixed `FavoriteKind`** alongside Home/Work/Custom (not just a
  suggested custom name); (2) each favorite has a **stable UUID `id`**, with Home/Work/School kept as
  **singletons** by the ops layer and Custom allowed many; (3) a favorite stores **coordinate +
  label + optional resolved `placeName`** (for display subtext); (4) persisted via **DataStore**
  (rides backup, like starred rows/settings), file `favorite-places.json`; (5) an unknown persisted
  kind within a known version falls back to **CUSTOM** (keeps the place) rather than dropping it — a
  genuinely new kind ships behind a `version` bump. None of these is user-visible yet; the Settings
  CRUD, picker and To… wiring land in later slices.
- **Farther stations: by line, 3 mi, five buttons (two tube), no distance (autopilot, 2026-09-25).**
  The maintainer chose "by branch/line, then cap at 5 or so", with the tube at most "1 or 2". Taken:
  one per rail line TfL names (tube lines, National Rail services, Overground lines, Elizabeth line,
  DLR, tram; no bus, boat, coach or cable car), nearest first; at most **five** buttons, **two** of
  them tube; nothing past **3 mi**; the label "From ‹station›…" with no distance, as sketched; the
  positions and lines from the bundled station list (no request) rather than a wider TfL lookup.
  Branches aren't split (TfL has no branch ids for National Rail). *Alternatives:* per mode for
  non-tube, a branch rule, a larger cap, a distance line, a TfL lookup. **Reversible:**
  `FartherStations` constants and the footer.
  **Routes, not lines, for National Rail (autopilot, 2026-09-25):** a station counts per route end
  its services run to, from TfL's `/Line/{id}/Route/Sequence` at index build time. Taken for National
  Rail only; tube branches, the Elizabeth line's branches and Overground lines stay by line (the
  Overground's named lines are already route-sized). *Alternative:* branches for the tube too, or
  whole route names as keys. Watch the noise at busy south London junctions, where the five-button
  cap does the work. **Reversible:** `FartherStations.reasonsOf`.
- **Farther stations as collapsed cards (maintainer, 2026-09-25; details autopilot).** The
  maintainer chose the card: name and distance as a place header, one row of line chips, "Tap to
  see" in place of times, below the loaded places, opening in place. Taken: the cards replace the
  From… buttons one for one (same picks, caps and 3 mi reach); "More" stays where it adds a line
  and sits above the cards; an opened card's groups stay below the loaded places rather than moving
  up by distance; opened stations are kept across a relocation while still offered, re-measured
  from the new fix. Buses as cards, the loaded-places order, and the pick rule (the nearest station
  of each line in each direction, maintainer 2026-09-25) are next. *Alternative:* open a From…
  page on tap, as the buttons did. **Reversible:** `FartherCardView` and `FartherCardsViewModel`.
- **Farther stations the other way, by bearing (maintainer, 2026-09-25).** Each line also offers
  its nearest station more than 90 degrees round from its nearest one, seen from the rider, and the
  cap rose from five cards to eight (two tube lines, each either way). *Alternative:* the order of
  stations along each line from TfL's line sequences, exact at branches but a build-time download
  and a heavier bundled list; switch if bearing picks badly where a line branches. **Reversible:**
  `FartherStations.OTHER_DIRECTION_DEGREES`, `MAX_BUTTONS`.
- **Opened farther cards: saved, and on the widget (maintainer, 2026-09-25; to do).** An opened
  card has its own departures model for now, held for the session only: a restart closes it and the
  widget never shows it. The maintainer's follow-up is to treat an opened card as a nearby stop
  everywhere until the rider moves away: in the list's fetched set, on the widget, restored after a
  restart (saving which cards are open), with the list's drop path closing it (its failure flags
  and the widget copy go with it). A collapse control is undecided: it adds a tap target to the
  card.
- **From… stands at the middle of the station's stops (autopilot, 2026-09-24).** The maintainer
  asked for From… to be "like setting your location to there"; the point used is the mean of the
  station's placed stops, and the near-me list's picking (nearest of each mode within a mile)
  runs from it unchanged; a To… from it plans from the station (SPEC "Trips with a change"). *Alternatives:* the hub's own coordinate, or the
  nearest entrance. **Reversible:** `FixedLocation.centerOf`.

- **To… is a look with no Star button (autopilot, 2026-09-24).** Picking a destination narrows
  the departures to those that call there; no trip is saved (the destination goes into *To…*'s
  own recent list since 2026-09-26). *Alternative:* star the trip straight
  away, which needs a line-free journey (`StarredJourney` places its ends on one starred line's
  route); logged under *Find a station* above. **Reversible:** the To… state is a few saved UI values
  in `MainActivity`; the filter is a pure `DirectTrips` function.
  *Superseded 2026-09-26: To… plans a trip (SPEC "Trips with a change").*
- **To… from the near-me list starts from the list's default stops plus any within 0.2 mi
  (autopilot, 2026-09-24).** The maintainer asked for "the near-me list's stations": the nearest
  of each mode within a mile (the list's default set, so a "More" reveal isn't carried over), plus
  any stop within 0.2 mi, less hidden modes, worked out afresh on every re-locate so the trip moves
  with the rider. *Alternatives:* freeze the rows shown when To… was tapped (tried: it went stale
  on a re-locate), or the 0.2 mi radius alone. **Reversible:** `hereOriginIds` and its radius.
  *Superseded 2026-09-26: To… plans a trip (SPEC "Trips with a change").*
- **To… reads "No direct trips to ‹place› soon" and "Checking routes…" (autopilot, 2026-09-24).**
  Provisional copy (with "To station or stop" in the search field); strings only, not translated.
  *Superseded 2026-09-26: To… plans a trip (SPEC "Trips with a change").*
- **The "More" reveal widget mirrors the app's *current* view, not eager-only — MOOT.** The reveal
  was deleted, so no revealed stop reaches the snapshot any more. (An opened farther card still adds
  stops to the list; those stay off the widget by design, SPEC *Near me now*.) (autopilot,
  2026-09-21.) The reveal follow-up had to decide whether a revealed expansion reaches the
  persisted snapshot — which the widget renders and its background worker keeps polling — or
  stays in-app only. Taken as **Option A (reaches the widget)**, the maintainer's stated lean on
  the PR #85 threads ("the widget should be the same as the main screen"), with the pruning
  correctness it needs (a dropped stop is persisted out of the snapshot even on a non-authoritative
  refresh, so the worker can't resurrect it). *Cost:* an in-app expansion grows the widget's
  ongoing 1/min polling set (one arrivals + one disruption request per revealed pole) until the
  next relocation resets it — a battery/request-load change (§9). *Alternative:* Option B, an
  eager-only widget snapshot — bounds the widget's load but splits what the screen shows from what
  it saves and the widget never shows revealed stops. **Reversible:** Option B is a filter on the
  snapshot save (persist only the eager stops), no data-model change. Confirm the widget-load cost
  is acceptable, or flip to B.
- **"More" button copy is provisional — MOOT.** The last button ("More bus stops") went when the
  farther bus cards replaced it (maintainer, 2026-09-25), and its string with it.
- **"More" pages `CLUSTERS_PER_MODE` (2) clusters per tap — MOOT.** The reveal it paged was
  deleted once nothing called it.
- **Prune-persistence redesigned — RESOLVED (maintainer approved "you choose", 2026-09-21).** Three
  Codex findings on #87 landed on one mechanism: shrink-to-Error skipped the save (round 1); the
  `pruneNeedsPersist` flag was cleared before the async save durably completed (round 3); and the
  flag lived on a per-set `MainViewModel` that a different-set relocation discards via
  `NearbyDeparturesStores.ownerFor` (round 4). All the same shape — the pruned in-memory set and the
  widget's disk snapshot diverge, and a transient VM-local flag + the refetch's save reconcile them,
  so every way that save can be missed (Error state, cancel, ViewModel discard) is a new hole. Rather
  than a fourth patch: the flag and the save-gate special-case are gone, and `reconcile` now calls
  `SnapshotStore.pruneStops(departed)` at prune time, launched under `NonCancellable` so it outlives
  both the refetch and the per-set ViewModel. The removal is atomic on disk (DataStore update
  transform) and independent of the save, closing the class. The round-4 P2 (a pruned state
  asserting a trusted empty "No departures" through the replacement fetch) is fixed in the same
  change: an all-departed prune shows the loading placeholder; a partial prune flags the shown set
  incomplete. (`saveIfStopsMatch`'s still-deferred "widget-snapshot-scope redesign" — a full
  prior/revision compare for the *same-set* concurrent-writer race — is adjacent but broader and
  left as-is.) **Round 6 (maintainer accepted best-effort, 2026-09-21):** Codex then flagged
  that `pruneStops`'s own write can throw and its `catch` only logs, so a departed stop could
  linger on disk. Declined a durable pending-prune (it would partly re-introduce the persisted
  state this redesign removed): `pruneStops` is a best-effort fast path that swallows a write
  failure exactly as `save` does, the next authoritative refresh save writes `merged` without the
  departed stop regardless, and the residual window (write throws *and* app stays closed while the
  worker runs) is narrow and self-heals on next foreground. The maintainer confirmed best-effort
  is fine, so no durable mechanism was added.
- **App-bar action row is temporarily crowded by the launcher icon — accepted for now
  (maintainer, 2026-09-20).** PR #67 adds the app icon in the `TopAppBar` nav slot. In the
  production loaded state (`onLocateHere` non-null) the bar also carries the freshness stamp
  plus locate + refresh + overflow buttons, so on a 411dp phone the "StopDash" title is
  squeezed and can ellipsize (Codex P2 on #67, deferred with maintainer's sign-off). Accepted
  as-is; the fix is to **slim the action row**. **Partly done:** the crosshairs/locate button
  is now gone (refresh + pull-to-refresh re-locate as well as re-fetch — the milestone-C
  auto-locate work), which frees one slot. The refresh button has since become a crosshairs
  ("use my location"; on a From… station, back to near me), so the row is no wider than before.
  Remaining if the bar is still tight: move anything left to the overflow menu.
  Reversible — layout-only, no data path.
- [x] **Re-locate on a return to the foreground (between C and A)** — shipped (PR #134). Reopening
      the app after it was backgrounded now runs the same re-locate as the refresh control (a fresh
      fix, re-resolve, re-fetch), so walking away and back moves the nearby set without a manual
      pull — the "I have to manually refresh sometimes" case. Stays foreground and user-adjacent
      (bounded to app opens and refreshes, no background poll); the on-screen auto-refresh tick
      stays departures-only. **D1 extended** from "manual near-me refresh" to include the
      foreground return; the shared action is `relocateAction` (pinned by `RelocateActionTest`).
      The *automatic* distance-triggered version below remains the follow-up.
  - [x] **Foreground return relocates even from behind an overlay** (Codex P2 on #134, PR #136).
        The return observer sat inside the departures view, which the Settings/Licenses overlays
        remove from composition — so a background→foreground with an overlay open was never seen,
        and closing the overlay showed the pre-move set until a manual refresh. Moved the observer
        **above** the overlay switch (`ForegroundReturnLatcher`, a top-level `repeatOnLifecycle`),
        latching a pending return the departures view consumes on (re)entry (`ConsumeForegroundReturn`).
        The latch is a retained `ForegroundReturnLatch` ViewModel (survives a rotation while the
        overlay is open, resets on process death where the init reload covers it). Latches only while
        the set is **Ready** (a not-yet-resolved return is the gate's `locate()`, and a permission
        grant via Settings resolves through `locate()`, not a re-locate — so no double-fetch), skips
        the first foreground, and `relocating` gates a mid-relocate return — so grant-return/rotation/
        moved-to-set don't relocate spuriously. Wiring pinned by `ForegroundReturnTest` (drives the
        real latcher/overlay/consume topology).
- [x] **Automatic distance-triggered re-locate (milestone A, after C) — try-it, revisit
  (maintainer, 2026-09-20; built 2026-10-02: 12 s updates while the list is on screen, a move of
  100 m+ on a fix within 50 m, at most once a minute, trip fixes included; the self-idle below is
  still open if a stationary open phone proves costly).** Milestone C makes refresh re-locate; the user pulls to refresh
  when walking past a station, and if that's fast enough (the re-locate forces a fresh fix —
  `FixSelection.resolve(forceFresh = true)` bypasses the cache fast path — so it waits ~1–2 s
  typically, capped at 10 s with a last-fix fallback) it may be enough on its own. If automatic "updates as I walk"
  is still
  wanted, layer on `LocationManager.requestLocationUpdates(FUSED_PROVIDER, minTime≈12 s,
  minDistance=100 m)`, foreground-only (register on resume, remove on pause), re-resolving the
  nearby set on each delivery. Notes for when we do it: the `minDistance` filter gates
  *callbacks*, not the positioning hardware, so it doesn't cut GPS battery — `minTime` +
  provider is the battery lever, and a stationary phone left open (kiosk) still draws while
  the locator cycles; a self-idle (stop updates after N min stationary, resume on a
  `TYPE_SIGNIFICANT_MOTION` trigger) is the mitigation if that proves costly. Throttle the
  TfL re-resolve to **≤ ~once/min** regardless of how often the distance filter fires
  (maintainer's rate goal). No new dependency (framework `FUSED_PROVIDER`, already used).
- **Underground / station-Wi-Fi fixes are confidently wrong — investigate what the fix carries
  (maintainer, 2026-09-23).** On the Tube there's no GPS, so the network provider places the user by
  the *station's* Wi-Fi — often a different station than they're at — and a re-locate then re-resolves
  the nearby set to the wrong stops (not merely stale). **First step is diagnostic:** capture what the
  fix actually reports in this case — the **provider** (fused/network/gps/passive), the **accuracy
  radius** *and whether one is present* (`Location.hasAccuracy()` — a missing estimate must be carried
  as unknown/null, not the default `0f`, or it would read as maximally accurate), the fix's **age**
  (`FixSelection`'s `fallbackAgeMillis` — a recent-but-inaccurate fix and a stale one near the ~30-min
  fallback limit need telling apart to know whether accuracy or staleness is the lever), and the
  coordinate. The consent-gated bug report already sends the exact fix (SPEC *Privacy*), so it's the
  channel to gather real cases — **but the plumbing must be extended first:** `AndroidLocationProvider`
  collapses each `Location` to lat/long-only `Coordinates` and `BugReportRequest` receives only that,
  so today none of provider / accuracy(+validity) / age reaches the report; carry those through (they
  are coarse diagnostics, so the debug log may carry them too, with the `docs/PRIVACY.md` disclosure —
  never the coordinate outside the consent-gated report — *Privacy*). **Then decide handling**, e.g.:
  gate a re-locate on the fix's **measured accuracy** (nullable `Location.accuracy`, treating unknown
  as untrusted) and/or age, not its provider label — a
  *fused* fix can itself be Wi-Fi/cell-derived (TODO *fused fixes can be coarse*), so "prefer
  GPS/fused" would still accept the bad underground fix; keep the current set (and say the fix is
  uncertain) when accuracy is worse than a threshold, or reject an implausible jump — without
  regressing the above-ground "follows you" behavior. Tie-in: this is the reliability caveat behind
  the auto-relocate-on-reopen work (#134/#136).
  **Shipped, diagnostic:** each fix the app uses is logged — source (fresh / recent cached /
  last-known fallback), provider, accuracy radius (or "unknown" when the fix carries none) and age,
  never the coordinate — so a bug report from the Tube shows what the fix reported. Next: gather
  real cases, then pick the accuracy/age gate below.
  **Evidence (maintainer report, on the Tube):** the fresh fix's *fused* and *gps* providers both
  time out (10 s cap), so `FixSelection` falls back to the last-known fix and proceeds; the TfL
  nearby lookup then times out too (poor underground connectivity). So a provider-label check
  wouldn't help — accuracy/staleness is the lever.
  **Near-term deliverable — surface the failure to the user (maintainer, 2026-09-23; SPEC principle
  2, don't fail silently):** today `NoLocation` ("couldn't get your location") and `Failed` ("can't
  reach TfL") are shown honestly, but the **silent-fallback** case isn't — when a fresh fix times
  out and a recent last-known exists, `FixSelection.resolve` returns that last-known and the app
  shows stops for it with no warning (so the Tube shows a wrong/stale area as current). Thread
  "this fix is a stale/last-known fallback (age) / low-accuracy" out of `FixSelection` → provider →
  `NearbyStopsViewModel` and show a visible, honest signal (a banner or stamp: "Couldn't get a
  current location — showing your last-known area"), consistent with the staleness contract (D4).
  **Shipped, first increment:** the **fallback** signal is threaded (`FixSelection.onFallbackUsed`
  → `LocationFix.isFallback` → `NearbyStopsViewModel.locationBanner`), a re-locate onto a fallback
  fix **doesn't jump** (keeps the shown set), and a top **banner with Try again** appears —
  "Couldn't update your location" (re-locate failed) or "Showing your last-known area" (set resolved
  from a fallback). **Still to do:** gate on **measured accuracy/age**, not just the fallback flag —
  a *fresh* Wi-Fi/cell fused fix that's confidently wrong (the station-Wi-Fi case) still isn't
  caught, since it's not a fallback; that needs the accuracy(+validity)/age plumbing above.
  **Shipped, second increment (maintainer bug report, 2026-09-25):** a fresh **coarse** fix
  (network/passive under a precise grant, GPS missed the grace) defers to the last precise fix
  when that is under 10 minutes old and inside the coarse fix's accuracy circle
  (`PreciseFixMemory`) — with no banner when that precise fix is under 2 minutes old (the fast
  path's own age; maintainer bug report, 2026-09-25), else still flagged approximate and checked
  by GPS; otherwise the list shows an "Approximate location" banner and a GPS-only
  follow-up (`LocationProvider.precise`, 8 s) confirms it (within 100 m) or moves the list.
- [ ] **Merge the stops around a remembered precise fix and a new coarse one** (maintainer,
      2026-09-25) — rather than picking one fix, show the union, with each distance either marked
      approximate ("~400 m") or as a range from both fixes ("100–400 m"). Deferred: the list would
      mix two places and every distance needs a second origin; revisit if the remember-precise
      rule above still leaves stops missing.
- [x] **A relocation in flight when the app is backgrounded outlives the return** (Codex on #220,
      2026-09-25) — the foreground return skipped its re-locate while one was running
      (`refreshOnForeground`'s busy check), so a lookup started from a fix taken before the app
      left (a pull-to-refresh, or an applied precise-fix refinement) completed and was shown after
      the rider may have moved. Done: the app leaving marks a relocation still under way
      (`NearbyStopsViewModel.leftForeground`), and the return waits only on one started since
      (`relocatingSinceLeft`), so it re-locates over the older one and its fresh fix wins. Every
      relocate, the refinement's included. One that ended while the app was away without a set
      (no fix, a failed lookup, none nearby) is looked for afresh on return
      (`locateAfterLeftBehind`), since a gate has no set for the return to re-locate.
- **Same-set re-locate discards updated stop metadata (Codex P2 on #70) — RESOLVED by the
  #87 reveal redesign (2026-09-21).** The old gap: `relocate()`'s same-set path kept the old
  `MainViewModel`, whose `seedStops` were fixed at init, so a refresh returning the *same* IDs
  with updated `name`/`lines` (a stop now serving a new line) never propagated — the new line
  never entered `declaredLineIds` and its disruption couldn't surface. The reveal redesign closed
  it without the re-keying originally prescribed: the retained `MainViewModel`'s eager tier is no
  longer fixed at init — `reconcile` reassigns `eagerStops` from the fresh lookup's clusters on
  every same-set relocation (`clusterSetKey` is cluster-key/ID based, so a metadata-only change
  still routes through `reconcile`), and `fetchedStops`/`declaredLineIds` derive from it. So a
  changed name/line now refreshes in place and a newly-served line's disruption surfaces. No
  `NearbyDeparturesStores` re-keying was needed.
- **Resolved 2026-10-02: the maintainer chose the render-path scoping (option 2 below), now
  landed (Phase 4 *Scope the widget snapshot*). Kept for its reasoning.**
- **Widget-snapshot-scope (Codex P1 from #44) deferred: PR #53 closed unmerged; aging stamp
  is the honesty floor and the render-path scoping stays an open task (not closed by Phase 2)**
  (autopilot, maintainer said "defer 53"). The gap is
  real: after a move whose new-set fetch fails, the previous area's departures linger on the
  nameless widget reading as live until the stamp ages them stale (SPEC principle 1 / D4). PR
  #53's app-side clear worked, but Codex found **seven race findings across three review
  rounds**, every one the same shape — an activity-level effect clearing the shared snapshot
  store concurrently with the per-set `MainViewModel` writers (read-check-write; redraw-after-
  clear; retained writer saving after an Empty-path clear; cancellation mid-`updateAll`
  skipping the retry). The same shape recurring — this activity-effect clear racing the
  concurrent per-set writer lifecycle — is evidence about the design rather than seven separate
  bugs (the sibling repos' AGENTS.md codify that as a rule; stopdash's own does not, so this is
  the escalation's reasoning, not a stopdash policy citation). A design change is the
  maintainer's call — so this is escalated rather than patched an eighth time. The three
  options, cheapest-to-revisit first:
  - **Defer (chosen).** Close #53, keep the per-row withhold + aging stamp as the honesty
    floor. The widget still ages stale data to `?` on its own (the staleness redraw that *did*
    land), so the failure mode is "shows the old area's trains until the stamp ages them out",
    not "shows them as live forever". Cost: that window is ~5 minutes **plus** the staleness
    redraw's scheduling delay — it's a `WorkManager` `setInitialDelay` wake (deferrable, and
    Doze/batching can push it past the boundary), so on a closed-app widget the flip to stale
    isn't bounded to a hard 5 minutes. Fully reversible — the work is captured in the closed
    PR and item 691.
  - **Redesign race-free on the render path.** Move the scoping off the concurrent activity
    effect: persist the *current resolved set* on resolution, independently of the snapshot's
    own save path — a snapshot save happens only on a successful fetch, so binding the set to
    it would leave both pointing at the old area in exactly the failed-fetch case this exists
    to fix, preserving the wrong-location window rather than closing it (Codex's finding on the
    first cut of this note). The widget's own `provideGlance` then blanks a snapshot whose stop
    set doesn't match that independently-persisted current set, so the decision is made where
    the widget renders instead of by a second concurrent mutator. Deletes the whole race class
    rather than patching instances, but it needs a new persisted "current set" surface written
    on resolution (ownership coordinated with the snapshot writer) plus a device check — larger
    than #53 was.
  - **Keep patching #53's design.** Fix findings G (cancel the retained writer on the Empty
    path) and H (guarantee the redraw on cancellation) and ship. Rejected as autopilot's call:
    it's the eighth race patch on a shape that keeps producing them, exactly the move the
    design-signal reasoning above says not to make alone.
  Recommendation: option 2 if the window matters before Phase 2. Phase 2's stable watched stops
  make it much *rarer* — the set then changes only on an explicit edit, not on every location
  drift — but they do **not** eliminate it: an edit whose first arrivals fetch fails still
  leaves the previous set's departures on the widget, so the render-path scoping (or a
  render-time compare against an independently-persisted watched set) stays worthwhile even
  then, not fully mooted (Codex P1). **Maintainer's call.**
  - **The "More" reveal is an instance of this same gap, deferred with it (Codex P1 on #87,
    `discussion_r4064937114`, 2026-09-21).** *The reveal was deleted since, so only the eager
    baseline below remains; the rest of this note is history.* On a relocation to a *different* cluster set,
    `relocate()` takes the new-set path (not `onSameSet`), so `reconcile` — the only caller of
    `pruneDepartedFromWidget` — never runs for the departed set; `NearbyDeparturesStores.ownerFor`
    clears the old ViewModel, and if the new set's fetch fails (non-authoritative) the old
    snapshot isn't overwritten, so the previous set's stops linger on the widget. This is exactly
    the deferred gap above ("after a move whose new-set fetch fails, the previous area's departures
    linger"). Codex's suggested fix — a durable prune *on the new-set transition* — is precisely
    what PR #53 attempted (an activity/owner-level clear racing the per-set writer), which drew the
    seven race findings that got the whole class deferred; adding it here re-opens that design, so
    it stays the maintainer's call, not a mid-PR patch. The reveal's only *new* contribution is
    that the lingering set can now include **revealed** stops (not just eager), and that delta is a
    direct consequence of the widget-mirrors-current-view decision (Decision A above): flipping to
    an eager-only widget snapshot would exclude revealed stops from the lingering set and shrink
    this to the pre-reveal baseline. The honesty floor is unchanged — the aging stamp ages the
    lingering stops to `?` (SPEC D4), so the failure mode stays "old area's trains until the stamp
    ages them", not "shown live forever".
  - **`pruneStops` has no prior/revision compare — accepted as this class (Codex P2 on #87,
    `discussion_r4064999935`, maintainer accepted 2026-09-21).** Because the prune runs
    `NonCancellable`, it can land after a newer ViewModel saved a different location's snapshot; it
    then removes the departed IDs from whatever DataStore holds, so if the newer set legitimately
    re-includes a departed stop (two quick relocations, slow write) that valid stop is dropped from
    the widget until the next in-app refresh re-saves. Narrow and self-healing; in-app is never
    affected. The fix is the compare-and-set the redesign deferred — carry the expected set/
    generation and only prune if the stored set still matches, mirroring `saveIfStopsMatch`. Left
    as a follow-up under this class rather than added mid-PR (the same concurrent-writer race).
- **About/Licenses entry point is an overflow menu → About dialog → full-screen Licenses
  overlay, reachable from every state** (autopilot, licenses-screen PR). StopDash has no nav
  graph and, until now, no About/Settings surface, so the licenses screen needed a home.
  Chosen: a `MoreVert` overflow in the departures top bar, **and** an "About" button on the
  location gate, both open the shared About dialog (app name + version); its one action opens
  the licenses list. The licenses route is hosted at the activity top level, above the
  gate/departures switch, as a `rememberSaveable`-gated overlay (system Back closes it via the
  screen's `BackHandler`). Hosting it above the gate (rather than inside the departures view)
  is what makes the legally-required attribution reachable when location is denied, and takes
  the departures refresh out of composition while it's open (both Codex P2s on the first
  cut). Alternatives weighed: a dedicated Settings screen (premature), or a nav library (a
  dependency for one destination). Reversible — a menu item, a gate button, a shared dialog,
  and a boolean swap in `MainActivity`. **Wants a maintainer look** at putting an About
  affordance on the permission-gate screen and at the overflow placement — a real Settings
  surface later would subsume both.
- **Widget staleness redraw uses `WorkManager`, one-shot at the boundary, armed from the render
  path** (autopilot, #44, maintainer said "no opinion" on implement-now vs defer). The
  app-closed honesty gap (Codex P1, raised twice) is closed by a single render-only `WorkManager`
  redraw at `snapshot.fetchedAt + THRESHOLD` that flips the widget to `?` (SPEC D4). It is armed
  from `provideGlance` (the render path), not from `save`: every path that shows the widget — add,
  host rebind, and the app's `updateAll` after a fetch (which re-runs `provideGlance`) — arms the
  flip from the snapshot it drew, which also means a host with no widget never schedules (no
  separate installed-id guard needed) and the widget-add-while-fresh case is covered. Codex raised
  three follow-on findings in this mechanism (no-widget churn, add-path gap) before it settled on
  the render path — that consolidation is the design fix that deleted the class.
  Alternatives weighed: **AlarmManager** (no new dependency, but no reboot persistence without
  a boot receiver, and inexact alarms are Doze-deferred just like WorkManager anyway); **defer
  the whole thing to D5** (rejected — it leaves the PR's own honesty claim with a hole Codex
  won't stop flagging). Cost: one new androidx dependency (`androidx.work:work-runtime-ktx`)
  and one deferrable, batched wake per snapshot — negligible battery, and not a polling
  cadence (fetching new data on a schedule stays deferred, see *Widget follow-ups*).
  Reversible — the scheduler is one file + one call site; swapping to AlarmManager or dropping
  it is contained. Wants a real-device check that the flip actually fires when the app is
  closed (and after a reboot).
- **Widget pixel test renders via `GlanceRemoteViews.compose`** (autopilot, #44). Rather than
  water down the AGENTS.md "Glance layouts get Roborazzi screenshots" rule to node-only, the
  widget is genuinely pixel-captured by composing it to RemoteViews and inflating them to a
  `View` (`WidgetScreenshotTest`). The API is `@ExperimentalGlanceRemoteViewsApi` — if a glance
  bump changes it, this test's render path may need adjusting (the node-based `WidgetContentTest`
  is unaffected). Reversible — it's one test file + one CI step. Baselines are committed but CI
  records (doesn't verify) them; the drift-refresh/verify gate is wired in the
  screenshot-drift-refresh PR (pending the post-merge `repo setup` token step).
- **Staleness threshold = 5 minutes** (`Staleness.THRESHOLD`, Phase 1 domain). The one
  shared "too old to trust" bound past which countdowns are withheld for "tap to refresh"
  (SPEC D4). Alternatives: a tighter 2–3 min (safer, but shows "tap to refresh" more
  often between routine refreshes) or a looser 10 min. Reversible — one constant, pinned
  by `StalenessTest`; change the value and the test together. Wants a look on a real
  device against real TfL refresh cadence.
- **`MainScreen` design guesses (all reversible; drive, MainScreen slice).** The screen
  landed on defaults worth a real-device look: **one flat soonest-first list across
  stops** rather than per-stop sections like the mock — SPEC's "soonest-first" wording
  drove it, but sectioned-by-stop is an easy alternative; a seed of **Oxford Circus +
  King's Cross St. Pancras** (public stations) until Phase 2 watched stops; a
  **10-second** on-screen clock tick for the countdown recompute; and a **three-kind
  error taxonomy** (offline / rate-limited / can't-reach-TfL) via a typed `TflException`.
  The row layout since settled on the **compact per-service card** (line pill +
  destination + merged countdowns, platform and stop name dropped — the row-merge item
  below records that redesign and the Phase-2 stop-name return), so it's no longer a guess
  here. None of the rest is load-bearing; all are cheap to re-shape once the flat list is
  seen on a device.
- **Per-stop snapshot: whole-screen stamp reads the *freshest* stop (PR #16, drive).**
  With per-stop ages, the top "updated N ago" stamp is ambiguous — chosen to be the
  newest stop's age (what "last refreshed" means), with per-row withhold carrying each
  stale stop's own truth, over the oldest stop's age (which would read "Tap to refresh"
  beside live countdowns). The mild understatement is bounded and made honest by the
  per-row "—". Also: `refreshFailure` fires only on a *total* failure (nothing fresh at
  all), `partialRefresh` when some stops refreshed and some were kept aged. All
  reversible — one stamp expression and two banner predicates. Wants a real-device look
  at a stop lagging behind its neighbors.
- **First screenshot job records + uploads only; no drift gate yet** (drive). CI proves
  the screens render (the `build` job runs them for pass/fail) and uploads the recorded
  PNGs, but does not yet fail on pixel drift or auto-commit the canonical set, because
  local and CI rendering can differ and the refresh apparatus isn't wired. The
  drift-refresh + visual-diff-comment follow-up is tracked under Phase 0. Reversible —
  adding the gate is additive. **Superseded by the screenshot-drift-refresh PR**, which
  wires the apparatus mirroring the siblings: `screenshot-tests` clears-then-records,
  enforces the `--tests` allow-list, and fails on drift on pushes/forks;
  `sync-screenshots` (`mikelward/ci-commit-artifact@main`) pushes the refreshed set back to
  same-repo PR branches; `post-screenshot-diff` posts the before/after comment. Kept OUT of
  the required `lanes` gate until `repo setup` provisions `CI_COMMIT_ARTIFACT_TOKEN`
  post-merge, so its bootstrap failure doesn't block that PR.
- **Brand accent = red, and Material You (dynamic color) off by default** (maintainer
  "let's try red", 2026-09-19). `StopDashTheme` now seeds a red `primary` (with its
  container/secondary/tertiary partners) so red reads as an accent on buttons and the
  refresh/progress indicators over neutral surfaces — deliberately *not* the app-bar
  container, to keep it an accent not a wash. Dynamic color is off so the wallpaper can't
  override the brand (leaving it on was why the app read as a neutral charcoal on-device).
  This is a **first pass at the color** the maintainer asked to try, not a settled brand:
  the exact red (`0xFFB3261E` light) and whether to accent the app bar are both open, and
  reversible — the scheme is two colour tables plus one default flag in `Theme.kt`, and
  flipping `dynamicColor` back on restores Material You. Promote to `SPEC.md` once the
  colour is confirmed on a device.
- **Live widget refresh uses mechanism B (WorkManager one-shot chain), A recorded as the
  follow-up** (maintainer: *start with B, record A as a possible follow-up if B doesn't
  work*, 2026-09-20). The opt-in "refresh widget every minute" setting drives a
  self-rescheduling `OneTimeWorkRequest` chain (`WidgetRefreshWorker`) rather than a
  foreground service. B is lighter (no persistent notification, no Play
  foreground-service-type declaration, less battery) and adequate for the scoped
  screen-on case, but it is **Doze-deferred**, so it does not guarantee the exact minute
  with the screen off. Mechanism A (a foreground service) would, at those costs, and is
  recorded under *Widget follow-ups* to take only if the screen-on case proves
  insufficient on a real device. Reversible — B is contained to `WidgetRefreshWorker` +
  the settings store + one call site; swapping to A is additive. Wants a real-device
  check that the ~1/min chain actually holds while the screen is on (and that Doze
  behaves as expected when it isn't).
- **Settings screen is a top-level overlay reached from the departures overflow only**
  (autopilot, this PR — maintainer asked for a Settings screen and said "the Settings
  screen should come first" then "sequence how you like"). Hosted at the activity top
  level like the licenses overlay (a `BackHandler`-closed screen, no nav library), added
  as a "Settings" item in the departures overflow menu **above** "About". The location
  gate's menu still offers About alone — its existing "Open settings" affordance is the OS
  app-settings for permissions, a different thing, so putting our Settings there would be
  confusing while location is denied. The screen composable is UI-only (reflects the
  setting, reports a change); persistence + the WorkManager scheduler are wired by the
  activity, keeping it Robolectric-renderable. Reversible — a menu item, a boolean overlay
  swap, and a UI-only composable. Wants a maintainer look at whether Settings also belongs
  on the gate, and at the overflow ordering.

## Decisions

- **Backup and device-to-device transfer of persisted config — DECIDED**
  (maintainer, 2026-09-18). Allow **both** Android cloud backup and device-to-device
  transfer; block neither. A phone swap keeps the user's watched stops, snapshot, and
  `app_key` (the fleet's "never lose the user's work" over a literal
  never-leaves-the-device wording). **Not MVP-scope work**: there is nothing to
  implement — the platform default already backs up and transfers, so no data-extraction
  rules and no `allowBackup=false`. Cost £0; no Play Data Safety change (Android Auto
  Backup is a platform feature, not data stopdash collects or transmits). Recorded in
  SPEC *Privacy*.
