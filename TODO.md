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
    - [ ] **Usage analytics events** (maintainer, 2026-09-24): custom events carrying categories
          and bucketed counts only, never a stop, line, journey or coordinate — each tap by kind
          (journey card, stop row, change card, swap, star/unstar, search, settings), the "More
          stops" (by mode) and "Faraway favorites" reveals, location permission (precise /
          approximate / denied), fix outcome (fresh / last-known / failed), fix accuracy and
          time-to-fix in bands, and nearby stops per mode bucketed (0 / 1 / 2–3 / 4+).
    - [ ] **Check the stored opt-in before Firebase starts**, not after: Firebase's init provider
          starts the SDKs from their own persisted flags before `Application.onCreate`, while our
          consent load runs afterwards, off the main thread. Today that's safe by ordering (an SDK
          flag is switched on only after a stored yes, and off before a stored no), so an early
          upload rides the last recorded consent; it would not survive our consent prefs being
          lost or corrupted on their own. The fix is manual Firebase init (drop the init
          provider) once the stored choice is read.
    - [ ] **A pending opt-in withdrawn while storage refuses every change** can come back on the
          next start: an opt-out that can't delete the pending marker, write "off", or delete the
          stored choice leaves the disk exactly as it was before the tap, so the next start reads
          the old "pending yes". No layout avoids that once nothing can be written or deleted; the
          SDKs are off, the switch shows off and each failure is logged. If it ever bites, the
          narrower step is to hold a pending opt-in in the SDKs' own state (Analytics on,
          Crashlytics off) so a withdrawal also changes a third, SDK-managed file.
    - [ ] **Invite the opt-in once on the home screen**, as simmo does, since stopdash has no
          onboarding to ask it in: a dismissible card; off stays the default.
  - [ ] **Log recent process-exit reasons at startup** into the shared log
        (`ActivityManager.getHistoricalProcessExitReasons`), as the siblings do
        (`ProcessExitReasons`), so a silent kill or native crash leaves a coarse cause in the
        next run's diagnostics. Coarse reason / importance / status only — never the platform's
        free-text description.
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

- [ ] An opened farther-station card that failed marks the list partial even when every one of
      its stops is already shown fresh by the list (the merge keeps the list's copy), so "Some
      stops couldn't be refreshed" can show with every row fresh. Predates the named banner
      (Codex P2, PR #217); gate the card's partial flag on a failed row that survives the merge.

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
- [ ] **Refresh the bundled route topology at runtime** (follow-up). The asset is static and
      ships with the build, so a TfL branch change (a line extension) needs an app update to
      reach it. A cached refresh from `/Line/{id}/Route/Sequence` (warmed at startup, off every
      decision/render path, falling back to the bundled asset) would close that — the maintainer
      approved the "bundled asset + cached refresh" shape (2026-09-20); only the bundled half
      shipped first. Free API; state the cost/reliability note (an added background fetch, no
      user-facing latency) when it lands.
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
        step-free access can see which stations have it. TfL's StopPoint data carries accessibility
        properties; whether they're per station or per platform, and how current they are, is to
        check. A step-free-access outage notice should then read against it.
  - [ ] **Tap a route row → all stops for that route** (maintainer, 2026-09-22). Extends the
        route-detail tap to show the route's full stop sequence, not just star + disruption text.
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
  - [ ] **Rethink the bus header cue: what's most informative, matched to the signage** (maintainer,
        2026-09-22; longer-term). A pole often carries several cues — a real `stopLetter`, a
        `CompassPoint`, a `Towards`, and the live departures' shared terminus. Today's precedence is
        letter → compass word → shared terminus → bare. Open questions to settle: which is most
        informative *about the service* (where it's going) vs *about the stop* (which pole/where the
        rider stands); what riders actually look for at the stop; and what to fall back to when the
        preferred cue is missing — ideally matching the **physical signage**. Cranley Gardens has no
        letter on the pole but the sign reads "Towards Friern Barnet", so `➔ Friern Barnet` (the
        `Towards`) would match reality where `Northbound` (our derived compass) does not. Leaning
        toward preferring `Towards` (`➔ Archway`) over the bare compass; handle TfL's two-way
        `Towards` (`"Farringdon Or Holborn Circus"` — already trimmed at `" Or "` for the spoken label).
  - [x] **Split a mixed-platform row into a card per platform** (Codex P1, PR #119). A direction
        that runs from several platforms (Camden Town southbound: Platform 2 or 4) now gets a card
        per platform; platform-less predictions beside two platforms sit under the bare compass.
  - [~] **Show the platform on the widget.** The widget's stop headers now name the platform when
        a merged direction row runs from exactly one ("Oxford Circus – Platform 3"). Still open: a
        direction split across platforms stays one row under the bare compass, where the app shows a
        card per platform; splitting it on the widget costs lines its budget may not have.
  - [ ] **Revisit the header grain for a busy interchange** (maintainer, 2026-09-21). The rail
        platform split and the bus letter split (above) already break a hub into per-platform/per-pole
        blocks. Still open: whether a dense hub wants a *finer* grain still (per-line dividers within
        a block), or whether the blocks are enough — judge on a device; the alternatives are in the mock.
  - [ ] **Persist the bus letter/bearing onto a watched stop.** The near-me path now captures a
        bus pole's `stopLetter`/`CompassPoint` and splits on it (above), but a **watched** bus stop
        refreshes from the arrivals feed, which carries neither — so a watched bus stop still shows a
        bare/terminus header, not its "(D)". Capture the letter on the Phase-2 watched-stop add flow
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
        **Start landed:** the widget now uses `SizeMode.Responsive` with a compact (<220dp) and a
        wide bucket; the compact one drops the stamp's "Updated" prefix. Labels can key off
        the same buckets.
- [ ] **Hide services terminating at the current stop by default** (maintainer, 2026-09-20).
      A train that terminates where you're standing isn't boardable onward, so listing it as
      an upcoming departure is misleading — filter it out by default (a departure whose
      terminus is this stop). Leave room for a "show terminating services" option. Watch the
      edge where an interchange train "terminates" only nominally before continuing under a new
      id; scope it to genuine terminations.
- [x] **Hide a mode from the near-me list** (maintainer, 2026-09-24): long-press a row or header
      for "Hide ‹mode›"; a one-line "‹Mode› hidden · Show all" banner undoes it; hidden stops aren't
      fetched from the next re-locate; the widget follows. Next ideas, for later:
  - [x] **Mode checkboxes in the overflow menu** (maintainer, 2026-09-24): six fixed groups — Tube &
        DLR, Train (Overground, Elizabeth line, National Rail), Bus, Tram, Boat, Coach — each ticked
        while shown; the long press hides by the same groups.
  - [ ] **Hide one line at a place** ("Hide Thameslink here") and **hide one platform/pole** (its
        card collapses to the header; a stop none of whose cards show isn't fetched).
  - [ ] **A "Hidden" list in Settings** to unhide one item at a time, and an **Undo** snackbar
        right after hiding, once there's more than modes to hide.
  - [ ] **Skip the National Rail board for a hidden National Rail mode** at a station that also
        serves an unhidden mode (today only a rail-only station is skipped); keep a starred
        journey's rail times.

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
      the nearest two clusters of each mode eager, plus a computed *more* tier) is implemented in
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
      below. See SPEC *Finding stops → Near me now*.
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
        couldn't check.
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
      - [ ] **Closure checks where the rider gets off** (each leg's alighting stop and the
            destination), from the list's few-minute cache; not yet fetched.
      - [ ] **Gray unreachable trains in the leg-by-leg cards** (the list's first-leg row does).
      - [ ] **"From" chip on the destination search** naming the start ("Here" or the station).
      - [ ] **Borrow from the other search's recent list** (maintainer, 2026-09-26): *From…* and
            *To…* keep separate recents, ordered by last use; maybe a single button to swap in the
            other list, or a way to pick from it (the station you came from as the way home).
      - [ ] **Station page's own *To…*** still shows direct trains only; move it to trips.
      - [x] **Plan to every station of a complex** (maintainer, 2026-09-26: the best way to King's
            Cross St. Pancras whatever the line or mode): once per station code plus one bus
            stop, in parallel, merged, the soonest six routes timed.
      - [ ] **Configurable walking limit** (maintainer, 2026-09-26): the Planner's
            `maxWalkingMinutes`, fixed at 15 for now.
      - [ ] **Mode toggles** at the top of a trip, remembered across trips (the Planner's `mode=`).
      - [ ] **Avoid a line**: request `includeAlternativeRoutes` and drop routes using it, since
            the Planner has no line exclusion (each returned route names its lines, so filtering
            on the phone is enough). UI to settle; the maintainer leans to the first:
            - a long press on a route offering "Avoid <line>" for each of its lines;
            - an avoided-lines chip at the top of the trip;
            - a setting listing lines to avoid.
      - [ ] **On the way** (later milestone; mocked 2026-09-26): tapping a first-leg train ("I'm on
            this one") starts the trip. A top card shows the next action ("Change at Whitechapel ·
            Platform A"), over a timeline of the route with the rider's position on it, followed
            by the boarded train's vehicle id in TfL arrivals, so it works underground without GPS.
      - [ ] **Step by step** (later milestone): the next action as an ongoing notification, with
            a nudge a stop before each change. A foreground service and a new wakeup, so it's a
            battery and permission decision stated when it's picked up.
      - [x] **One-tap trips**: the near-me top bar's Directions button opens *To…* in one tap, with
            room freed by shortening the freshness stamp to "1 min ago" (maintainer, 2026-09-26).
      - [ ] **Better than a bare "est."** (maintainer, 2026-09-26): a leg past its live predictions
            boards "as the rider arrives" on a frequent line, marked "est." — a stopgap. Handle it
            better: a timetable- or headway-based wait, and inferring or knowing whether the rider is
            already partway along the route (ties in with *On the way*), so a later leg is timed from
            where they are rather than from the stop they started at.
      - [ ] **Check a trip's boarding stops for disruptions** (Codex on PR 259, 2026-09-26): a trip
            fetches its lines' status but not its stops' own disruptions (a closure, a moved stop),
            so a leg's line page can't say "No disruptions reported" and reads "Couldn't check"
            instead. Fetch them as the list does, and let the page vouch once both checks pass.
      - [ ] **Honor dismissed alerts on a trip's cards** (Codex on PR 259, 2026-09-26): a line alert
            dismissed from a leg's page (or the list) is honored on that page, but the trip's own
            cards still show the line's ⚠. Apply the dismissals there as the list does, keeping
            a dismissed line's status out of the warnings without reading it as unchecked.
      - [ ] **Arrows between a route's pills** if space permits (dropped for width, 2026-09-26).
      - [x] **Shared-leg pill**: a leg several routes serve alike (buses 43 and 134 share the stops
            to Highgate station) as one diagonally cut pill carrying both routes, and a live row per
            line in the one card (maintainer, 2026-09-26).
    - [ ] **Star a From… To… trip as a journey**: the trip has no single starred line, which
          `StarredJourney` places its ends on, so it needs a line-free journey first.
    - [x] **To… from the near-me list** (maintainer, 2026-09-24): the overflow's *To…* starts
          from the list's default stops plus any within 0.2 mi (`hereOriginIds`).
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
        own change; it pairs naturally with the "More" reveal, which also reshapes how a junction
        expands. Until then SPEC/`NearbySelection` say plainly the cap bounds clusters, not
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
  - [ ] **A revealed stop whose first fetch fails isn't in the widget's polling set** (Codex P2, PR
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
  - [ ] **Delete the now-unreachable "More" reveal path.** Nothing calls `MainViewModel.reveal`
        any more: remove it with `moreState`, `revealedKeys`, `fetchIncremental`, the revealed-
        cluster handling in `reconcile`, `NearbySelection.nextReveal` / `revealableBuckets` /
        `MAX_REVEAL_PER_TAP` / `BUS_MODE`, and their tests. Kept out of the bus-cards change to
        keep that one reviewable; behavior is unchanged either way.
  - [ ] **Decide "More" progression from fetched/rendered rows, not declared metadata** (Codex P2,
        PR #98). `nextReveal` decides how far to page from cluster *declared* lines, before fetching —
        so a nearer cluster that declares a not-shown route but returns no live departure is counted
        as the productive stop, the page stops there, and a farther cluster that actually has a live
        new route isn't reached (a residual dead tap in that arrangement). It can't be fixed pre-fetch
        (declared lines are the only signal a `more` cluster has until fetched); the class-deleting fix
        is **fetch-then-decide** — reveal incrementally, fetch, check whether a new *rendered* row
        appeared, continue if not. That is the same redesign as the two request-burst items below, so
        do them together.
  - [ ] **Don't fetch a "More" cluster that only repeats routes already shown** (Codex P1 follow-up,
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
  - [ ] **"More" reveal riders** (the reveal now exists, above): a **widget "More"** that just
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
        says a stop can be tapped to star the journey there (maintainer, 2026-09-24).
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
    - [ ] **A relocate that flips a journey's direction refetches once.** A same-set relocate
          refreshes with the journey stops the screen last reported, so when the fresh fix turns a
          journey round (its origin is now the other end) the refresh starts on the old origin and
          restarts when the screen reports the new one. Hand the refresh the flipped origin up
          front, as the faraway hold-back already does for journeys crossing the mile line.
  - [ ] **Home and work, with routing** — the eventual goal: star two places, not two stations,
        and show how to get between them. Needs journey planning, a SPEC non-goal today and a
        separate product + privacy decision (it would send both places to TfL's Journey Planner).
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
      destination to TfL and is a **separate product + privacy decision** (SPEC declares
      the Journey API a non-goal; it would change the Play Data Safety answers), not
      assumed by this item.
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
- [ ] **Surface a rejected user `app_key` distinctly** (follow-up to the D7 paste field, Codex).
      A mistyped/expired/revoked key that TfL rejects with a non-429 4xx currently maps to the
      generic `Unreachable` state, so the app says "can't reach TfL" and keeps retrying the bad key
      (the user recovers by clearing it in Settings — an honest but imprecise state). Map an auth
      4xx (401/403) to a key-specific `TflException` and render it with a one-tap "clear key" action
      on the affected surfaces, **or** validate the key on Save. Weigh validate-on-save's extra TfL
      round-trip (a network call, offline handling, and a Data Safety note) against the simpler
      surface-the-error path. Kept out of the paste-field PR to avoid widening it into error-UX
      plumbing; the baseline there stays an honest typed error, not a silent failure.
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
      London Northwestern Railway (LNWR) joined later, green `#27B67A`: no color template had
      it, so the maintainer picked it (2026-09-24) from the route-map legend in Wikipedia's *West
      Midlands Trains* article over an unsourced `#00BF6F`, after seeing both rendered. Matched
      by name prefix, like its pill code, since the rail feed's exact spelling is unconfirmed.
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
    - [ ] **National-rail connections at interchanges** — a hub lists operators and buses together
          with no per-line mode; identifying the operators (from the member rail station, one more
          lookup, or a known-operator list) would add them without guessing.
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
        Monument" — where the text gives a clean from→to.
      - **Current-vs-future must come from the dates in the text, not `isNow`.** TfL's
        `validityPeriods[].isNow` reads `false` even for planned closures in effect right now
        (observed 2026-09-20, a Sunday: every live Overground/tube part-closure was `isNow:
        false`), so it marks "unplanned", not "current". A not-yet-started diversion currently
        shows a chip today (the safe side — an extra chip beats a hidden disruption); parsing the
        reason's dates is what would let a not-yet-started one be held back without hiding a
        genuinely-current one.
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
  - [ ] **Show delays and cancellations honestly**: a "Delayed" train with no estimate as "?",
        a cancelled one as cancelled, rather than leaving them out.
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
      - [ ] **Refresh on unlock by default** (`ACTION_USER_PRESENT`, a manifest receiver —
        one fetch when the device is unlocked; battery-negligible because it piggybacks on
        active use rather than waking the radio from idle; cellular data is the only real cost,
        ~2–5 MB/day, gate on WiFi/charging if wanted). This is the default path for a user who
        never opts into the every-minute loop.
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
- [ ] **Carry disruption / line-status into the widget (own PR).** Maintainer: *yes, but a
      follow-up.* The widget's `across(...)` runs with empty `lineStatuses` and the snapshot
      has no disruptions, so a delayed/suspended service can show a normal-looking countdown.
      Requires the snapshot to persist an age-stamped status (a deliberate reversal of the
      `DeparturesSnapshot` KDoc, so `SPEC.md` records the reasoning). The per-row stale
      withhold (landed) already stops *old* numbers reading as live; this marks a *fresh*
      disrupted service on the widget.
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
- [ ] **Deduplicate the widget's nearby set before the cap (own PR, Codex P2 on #44).** The
      in-app view calls `DepartureRows.nearbyDeduped(stopDistanceMeters)` so a line served by
      several adjacent stops collapses to its nearest stop; the widget renders from the
      persisted snapshot, which carries no distances, so it can't. Fix needs persisting the
      distances (or a widget-ready deduplicated selection) alongside the snapshot — a
      selection/schema decision, and moot once Phase 2's watched stops replace the interim
      nearby source. Until then adjacent stops can double up a line/direction in the six slots.
- [ ] **Scope the widget snapshot to its nearby set (own PR, Codex P1 on #44).** The widget
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
- [x] **Size-aware row cap (Codex P2 on #44, landed with the header row).** The fixed six-line
      cap overflowed even the default 240x180dp cell. The widget now uses `SizeMode.Responsive`
      height buckets and derives its line budget from the bucket's height (`widgetLineBudget`,
      conservative per-line cost), pixel-checked by the full-budget and minimum-size screenshots.
- [ ] **Per-row widget stacking from the row's own width (Codex P2 on #155).** The widget stacks
      a departure onto two lines only on the narrow (<220dp) bucket at 1.3x font or more. A wide-bucket
      row with three times ("0 · 3 · 6 min") at a large font can still squeeze its destination out.
      Fix properly: decide stacking per row from its estimated countdown width, count the line budget in
      dp instead of lines, and add width buckets (the 220dp bucket stands for every width above it).
      Glance can't measure text, so this stays an estimate; judge it on a device.
- [ ] **Named Overground pills on the widget (own PR, Codex P2 on #44).** The in-app pill
      renders the six named Overground lines as a *hollow* pill (surface fill + accent border
      + accent label, via `overgroundAccentColor`); the widget falls back to a neutral pill for
      them because `lineFillColor` returns null. Glance has no border modifier, so a hollow pill
      needs a nested-Box ring hack that the node harness can't verify — and a solid accent fill
      would reintroduce the tube-color collision #42's hollow treatment exists to avoid (Windrush
      red ≈ Central). So the widget shows a safe neutral pill for named Overground for now;
      revisit with a verifiable Glance hollow treatment (or once pixel rendering lands).
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
- [ ] Fail a **release** build when the git-derived versionCode/SHA fell back (a
      source-archive or no-git build): Play rejects a non-incrementing versionCode, so a
      silent fallback of `1` is wrong for a shipped artifact. The derivation logs a
      warning now; the hard release-side guard lands here with the deploy job that makes
      release integrity meaningful (Codex, PR #4).
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
      dialog**). Shipped as the overflow's *Send bug report*: it shares the diagnostic log (in
      full, not redacted), the **exact location**, and the **per-stop distances** via the platform
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
      age-based prune, and the recycle. stopdash pins `2.2.69` and calls it off the main thread,
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
      being built now (maintainer, 2026-09-24), but **the watch app must not be released
      until the maintainer has renamed the package to `app.stopdash` and decided to launch**:
      the watch and phone apps share one application ID, and a Play listing can't be renamed.
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
        that `:wear:bundleRelease` fails without it. Lift it only after the launch decision, in
        the same PR as the Play step below.
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
  - [ ] Disruptions on the watch, only after *Carry disruption / line-status into the widget*
        (Phase 4) adds an age-stamped status. The watch withholds each one at the same expiry.
        The envelope then carries those line statuses too: a status row (and so a rail line's
        "No key"/"No data", whose feed state `PersistedStop` already keeps) needs one.
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
  - [ ] Play (**maintainer only, after the rename and the launch decision**): lift the release
        gate, file the Data Safety answers decided with the publisher, then a Wear OS release
        track with screenshots and the app-quality review.

## Beyond MVP (not planned)

Directions that would change what stopdash *is*, not steps in the London MVP. Recorded so
they aren't re-derived; none is scheduled, and each needs the maintainer's go-ahead.

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
  station's placed stops, and the near-me list's picking (nearest of each mode within a mile, the
  0.2 mi ring for To…) runs from it unchanged. *Alternatives:* the hub's own coordinate, or the
  nearest entrance. **Reversible:** `FixedLocation.centerOf`.

- **To… is a look with no Star button (autopilot, 2026-09-24).** Picking a destination narrows
  the departures to those that call there; no trip is saved (the destination goes into *To…*'s
  own recent list since 2026-09-26). *Alternative:* star the trip straight
  away, which needs a line-free journey (`StarredJourney` places its ends on one starred line's
  route); logged under *Find a station* above. **Reversible:** the To… state is a few saved UI values
  in `MainActivity`; the filter is a pure `DirectTrips` function.
- **To… from the near-me list starts from the list's default stops plus any within 0.2 mi
  (autopilot, 2026-09-24).** The maintainer asked for "the near-me list's stations": the nearest
  of each mode within a mile (the list's default set, so a "More" reveal isn't carried over), plus
  any stop within 0.2 mi, less hidden modes, worked out afresh on every re-locate so the trip moves
  with the rider. *Alternatives:* freeze the rows shown when To… was tapped (tried: it went stale
  on a re-locate), or the 0.2 mi radius alone. **Reversible:** `hereOriginIds` and its radius.
- **To… reads "No direct trips to ‹place› soon" and "Checking routes…" (autopilot, 2026-09-24).**
  Provisional copy (with "To station or stop" in the search field); strings only, not translated.
- **The "More" reveal widget mirrors the app's *current* view, not eager-only (autopilot,
  2026-09-21).** The reveal follow-up had to decide whether a revealed expansion reaches the
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
- **"More" button copy is provisional, pending sign-off before translation (autopilot,
  2026-09-21).** Only "More bus stops" (`more_stops_bus`) remains since the non-bus buttons went
  (maintainer, 2026-09-25). Wording is the maintainer's call; no other locale exists yet, so
  nothing is translated — settle the English first. Reversible (a string value).
- **"More" pages `CLUSTERS_PER_MODE` (2) clusters per tap (autopilot, 2026-09-21).** Symmetric
  with the eager cap, and keeps each tap's fetch burst bounded; "reveal all of the mode at once"
  is the alternative. Reversible — the `pageSize` argument to `NearbySelection.nextReveal`.
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
- **Automatic distance-triggered re-locate (milestone A, after C) — try-it, revisit
  (maintainer, 2026-09-20).** Milestone C makes refresh re-locate; the user pulls to refresh
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
- [ ] **A relocation in flight when the app is backgrounded outlives the return** (Codex on #220,
      2026-09-25) — the foreground return skips its re-locate while one is running
      (`refreshOnForeground`'s busy check), so a lookup started from a fix taken before the app
      left (a pull-to-refresh, or an applied precise-fix refinement) completes and is shown after
      the rider may have moved. Cancel or supersede the nearby re-pick on stop, so the return's
      fresh fix always wins. Applies to every relocate, not only the refinement.
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
    `discussion_r4064937114`, 2026-09-21).** On a relocation to a *different* cluster set,
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
