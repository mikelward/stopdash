# Moving the app's pages onto Navigation 3

**Status: under way, on Navigation 3** (maintainer, 2026-10-22: "ship option 1 and write the plan", then
Navigation 3 over Navigation Compose 2.x). Step 1 is done (*Step 1: what the spike found*). Predictive
back shipped as "shrink only": every page follows the back gesture over a scrim, and only windows over a
page show the page under them (SPEC *Back*). Drawing the real page under every page is the payoff this
plan works toward. The other gain is a navigation model that can't skip pages.

## Why

The app has no back stack. `MainActivity` keeps one flag per screen (`licensesOpen`, `settingsOpen`,
`onTheWayOpen`, `favoritePlacesOpen`, `favoriteJourneysOpen`, `linesPresence`, `stationSearchOpen`,
`openStationId`, …). Rules then work out what shows:
- `topOverlay` picks the overlay on top, by a fixed priority, not by what was opened last;
- `routeStopCovered` says when a route stop's windows step aside;
- `KeptPages` and `openOverlayPages` keep each covered overlay's saved state;
- `NearbyArea` switches between the near-me view and the overlay, and `nearbyBodyGeneration` drops
  the near-me view's pages.

Inside the screens, more flags swap one view for another in place: the platform, station and journey
views, the route and ride pages, a trip's open route and later list, Settings' Disruptions page, the
favorite rides' alerts and picker.

That design is where Back went wrong. "Back skips pages" (#1064) took several new rules to fix, and
two of Codex's findings on that PR were the same mechanism failing in a new place: a Departures window
hidden under Lines…, and a route stop's details coming back after Done. AGENTS.md calls a second
finding in one mechanism a design question. A back stack, where what was opened last is on top and each
entry keeps its own state, deletes the class: there's no priority to get wrong and no flag to forget to
clear.

It is also what predictive back needs. Drawing the real page under the swiped one means that page must
be composed for the gesture, and a back stack knows which page that is. The flags don't, so option 2
by hand would mean restructuring all 17 Back sites to draw a guessed page underneath.

## The library

**Navigation 3** (`androidx.navigation3`), not the Navigation Compose 2.x that ClothesCast uses.
- In Navigation 3, the back stack is a plain list of keys that the app owns: a `NavBackStack`, which
  wraps a `SnapshotStateList`. That fits how StopDash already holds its state, in Compose state it
  changes directly. Navigation Compose 2.x keeps its own stack behind a `NavController` and string or
  typed routes, which is a bigger mismatch.
- Fleet consistency argued for 2.x. The maintainer chose the better fit (2026-10-22), and ClothesCast
  stays on 2.x.

Read from the published sources on 2026-10-22:
- **Version:** `navigation3-runtime` and `navigation3-ui` **1.2.0** are stable (2026-09-23).
  `lifecycle-viewmodel-navigation3` **2.11.0** is stable (2026-06-17).
- **Dependencies:** 1.2.0 needs Compose 1.10, activity-compose 1.12, lifecycle 2.10 and
  navigationevent 1.1.1. The app's BOM 2026.09.00 (Compose 1.12.1), activity 1.13.0 and lifecycle
  2.11.0 meet these. navigationevent is resolved up from 1.0.0 to 1.1.1. Every Back handler in the app
  runs on that dispatcher, so the bump gets its own check.
  - The 1.3.0 alphas need alpha Compose, so stay on 1.2.x.
- **`NavDisplay(backStack, …, entryDecorators, sceneStrategies, transitionSpec, popTransitionSpec,
  predictivePopTransitionSpec, entryProvider)`** draws the stack. Transitions can also be set per entry,
  in its metadata.
- **Saving the stack:** `rememberNavBackStack` saves it through kotlinx.serialization. The app already
  applies that plugin. Every key must be `@Serializable`, and R8 must keep their names and serializers.
- **Entry state:** `rememberSaveableStateHolderNavEntryDecorator` keeps each entry's `rememberSaveable`
  state while it's covered and drops it on pop, which is what `KeptPages` does today.
  - `rememberViewModelStoreNavEntryDecorator` gives each entry its own `ViewModelStore`, cleared on pop.
    That replaces the hand-cleared stores (`station-stores` and the like).
- **Predictive back:** while the gesture is under way, `NavDisplay` composes the previous entry one
  layer below the current one and seeks the transition with the gesture's progress.
  - Its handler is off when the stack holds one entry, so the system's back-to-home takes over.
  - Its default predictive pop is a fade in and a scale-out to 70%. We'd set
    `predictivePopTransitionSpec` to the shrink option 1 already draws: 90%, shifted toward the swipe,
    with rounded corners.
- **Lifecycle:** each entry gets its own `LifecycleOwner`, capped at RESUMED on top.
  - During a gesture, both the swiped entry and the revealed one are at **STARTED**. The revealed
    entry reaches RESUMED only once the pop completes.
  - Covered entries leave composition. Their `rememberSaveable` state and ViewModels are kept, plain
    `remember` state isn't. Today's overlays behave the same.
- **Back handlers inside an entry:** an enabled `BackHandler` or `PredictiveBackHandler` in the top
  entry wins over `NavDisplay`'s, because it registers later on the same dispatcher. So an in-entry
  inner page keeps working, but it gets no library animation: only a page that is its own entry does.
- **Dialogs:** `DialogSceneStrategy` shows an entry as a `Dialog` window over the entries under it,
  which stay composed, at STARTED.
  - The dialog's own back gets no predictive animation. Compose's `DialogWrapper` takes Back itself.
  - A `PredictiveBack` inside the dialog's content still follows the gesture, as today.

**Cost and reliability:**
- No money, no network and no new failure mode at run time.
- A Google first-party dependency, about 113 KB in the release APK with R8 on (*Step 1*), kept
  current by the weekly Gradle batch.
- The risk is in the migration itself: it re-touches every Back path #1064 just fixed.

## What the gesture would start, and how to hold it off

With the page under drawn during the gesture, whatever that page does on entering composition runs,
even for a swipe the rider lets go of. The near-me page is the costly one. Navigation 3 helps here: the
stack doesn't change until the swipe finishes, so the page being revealed isn't on top of it yet. So
effects that should only run for the page the rider is on wait until the page is **on top of the
stack, windows aside, and at least STARTED**, each with a test that a canceled swipe doesn't fire it.
RESUMED alone won't do: a window entry holds the page under it at STARTED, so a route stop's details
would pause the location fix, which they don't today (*Step 1*). In the list, "on top" means that gate:
- The location fix: `resumeRefining()` runs in a `LifecycleStartEffect` today, so a canceled swipe
  would start and stop the GPS follow. It runs only while the page is on top.
- The refresh on coming back to the app: `ConsumeForegroundReturn` reads and clears
  `returnLatch.pending` as it composes, so a glimpse would spend it. It reads it only on top.
- A refinement on offer being applied, and the near-me trip's Direct lookups (`direct.run()`, already
  on RESUMED) and its auto-close when no origin is found.
- Write-failure snackbars, which clear their flag as they show: shown only on top, so a glimpse can't
  spend one.
- `ReportScreen(HOME)`, so analytics doesn't count a glimpse as a visit.
- `ReportChanges`' reports to the view model (`setJourneyStops`, which can refresh, and
  `screenLines`, which checks lines): reported only on top.
- Dialogs a page had open when it was covered (a disruption card's, the route page's View line): these
  are windows of their own, so drawn during a glimpse they'd open **over** the page being swiped. They
  show only on top.
- Lines…'s reopening and its status checks, which can ask TfL, and Settings' permission reads: only on
  top.

Cheap work can stay as it is: the near-me page's cached route lookups, its rows worked out again on the
worker, the station index read, `AutoRefresh`'s first tick (60 s out).

## The plan

Each step is one PR that leaves the app working, and the Back tests (`NearbyAreaTest`,
`TopOverlayTest`) carry over as back-stack tests.

1. **Spike, test-only.** Add the dependencies and a `NavDisplay` of two keys in a Robolectric test, to
   confirm on this app's stack:
   - the lifecycle states above, during a gesture and after;
   - the dispatcher shared with the app's own `PredictiveBack`;
   - R8 keeping the keys in a release build;
   - process-death restore of the stack;
   - `FontSizePinch`, `TapPause` and `KeyRejectedFrame` still wrapping what shows;
   - the APK size change.

   Done: *Step 1: what the spike found*.
2. **The near-me page's effects wait until it's on top** (the list above), before anything draws it
   during a gesture. Visible behavior is unchanged: today the page is never composed but on top.

   Done: *Step 2: what changed*.
3. **The overlays become a back stack.** Keys: `NearMe(generation)` at the root, and `Settings`,
   `Licenses`, `OnTheWay`, `FavoritePlaces`, `FavoriteJourneys`, `Lines`, `StationSearch` and
   `Station(id)` above it.
   - `NavDisplay` replaces `NearbyArea`'s switch, `KeptPages`, `topOverlay` and `openOverlayPages`. The
     flags become questions about the stack ("is `Licenses` on it?").
   - Done after an arrived trip, and a shared place, reset the stack to `[NearMe(generation + 1)]`.
     The new generation is a new key, so it opens afresh, as the generation bump does today.
   - Licenses opened over the trip on the way is on top because it was opened last, not by priority.
     Each `topOverlay` rule is checked against the open order it stands in for.
   - This is the step that gives overlays the real page underneath during the gesture. Their option 1
     handlers go.
   - The trip from here becomes an entry of its own above `NearMe`, so Lines… opened over it sits
     above it on the stack and `LinesPresence.UNDER_TRIP` goes (maintainer, 2026-10-22).
   - `NavDisplay` provides `LocalPageOnTop` per entry: true for the top entry, false for the one a
     gesture reveals.
   - Pages open and close with a short slide and fade, about 250 ms to open and 200 ms to close, and the
     back swipe drives the close (maintainer, 2026-10-22). Both durations are a starting point, tuned on
     a device.
   - During a gesture the top page drops to STARTED too, so a swipe let go of pauses and resumes it.
     `ReportScreen` counts each resume, so a canceled swipe would count the page twice; it needs the
     on-top gate or a check that the page didn't change.
4. **A route stop's windows become dialog entries.** The details and the Departures opened from them
   become `DialogSceneStrategy` entries. Anything pushed after them covers them by stack order, so
   `routeStopCovered` goes.
   - The page under a window entry stays on top (`LocalPageOnTop`) but drops to STARTED, so what step 2
     left on RESUMED pauses under a window: `ReportScreen` would count the page again as the window
     closes, and the near-me trip's Direct lookups and Lines…'s status checks stop while it's open.
     Each is checked against what the window does today, which is pause nothing.
5. **The station flow stacks.** Search, `Station(id)`, then the station's trip. A station opened from a
   route stop's details now goes on top of the station under it instead of replacing it, which is
   `TODO.md`'s "takes the place of the station under it".
6. **In-screen views become entries, a family per PR.** The near-me page's platform, station and
   journey views and its route and ride pages; the trip's open route and later list; Settings'
   Disruptions page; the favorite rides' alerts and picker. Each family gets the real page underneath
   and loses its option 1 handler. Editors and inline pickers (a saved place's editor, the trip's
   To… and Via pickers) can stay in-entry, following the gesture over a scrim as today.
7. **Cleanup.** Retire `KeptPages`, `NearbyArea`'s body switch, `topOverlay`, `routeStopCovered`,
   `openOverlayPages`, `RideReturn`'s bookkeeping where the stack now holds it, and `PredictiveBack`
   where nothing uses it. SPEC *Back* then says what the stack does.

**Size:** about 6–10 PRs. Step 3 is the largest: `MainActivity`'s content is about 8,000 lines, and
many of its 94 `rememberSaveable`s are navigation state. The plan runs step by step, each merged before
the next, with a device check of the Back flows after steps 3, 5 and 6.

## Step 1: what the spike found

`Navigation3SpikeTest` runs a `NavDisplay` set up as the app would set it up, on the app's own versions:
sealed keys, both entry decorators, `DialogSceneStrategy`, no push or pop transition. Each test holds one
of the claims above, so a library update that breaks one fails the build. Navigation 3 is a test
dependency only until step 3. The spike found:

- **Lifecycle during a gesture: as above.** Both pages are at STARTED while the rider swipes. A finished
  swipe resumes the page under, and a canceled one drops it from composition without ever resuming it.
  The stack itself doesn't change until the swipe finishes.
- **A window entry holds the page under it at STARTED, not RESUMED,** as *The library* says. The first
  draft of this plan gated the near-me page's effects on RESUMED, which this rules out: once step 4 makes
  a route stop's details a window entry, they'd pause the location fix, and today they pause nothing. So
  the gate is the page being on top of the stack, windows aside, and at least STARTED (*What the gesture
  would start*). During a gesture the stack hasn't moved yet, so the page being revealed isn't on top:
  only a glimpse is held off.
- **Saved state:** a covered page leaves composition. Its `rememberSaveable` state comes back, its
  `remember` state doesn't, and a popped page's state goes with it. A page's `ViewModel` lasts while the
  page is covered and is cleared when the page is popped.
- **Back handlers:** they share one dispatcher with the app's. A page's own `PredictiveBack` or
  `BackHandler` goes first, and the stack draws nothing under it. With one page left, Back goes past
  the stack to the system's back-to-home. A window entry's Back is its own window's, as today.
- **Process death:** the stack and every covered page's saved state come back. The keys are saved by
  their own sealed serializer (`NavBackStackSerializer(Page.serializer())` through
  `rememberSerializable`), not `rememberNavBackStack()`'s, which finds each key's class by name. Nothing
  is looked up by name, so R8's renaming can't break a restore.
- **Opening a page with no transition:** the page is drawn on the second frame and settled (resumed,
  the page under out of composition) by the fourth, about 67 ms. A flag's swap today draws on the first
  frame. That's one frame later, so a page opened with no transition would still appear at once; pages
  get a short animation instead (*Decided*).
- **The wrappers, by construction rather than a test:** `NavDisplay` goes inside `StopDashAppRoot`'s
  content, so the text size, its pinch, `TapPause` and `KeyRejectedFrame` wrap every page as they do
  today. A window entry is a window of its own, so it re-applies `FontSizePinchWindow` inside, as today's
  dialogs do.
- **Size:** a `NavDisplay` like the spike's, built into a throwaway release build, adds about 113 KB to
  the APK with R8 on: 16,601,784 to 16,717,242 bytes, 0.7%.
- **navigationevent:** Navigation 3 needs 1.1 or later, and the app had 1.0.0 through activity-compose.
  The app now declares 1.2.0 itself, in a commit of its own ahead of the spike, so it ships the version
  its unit tests run on and a Back regression from the bump is that commit's alone. The whole suite
  passes on it.

## Step 2: what changed

Pages now read `LocalPageOnTop` (`ui/PageOnTop.kt`): whether the page composing is the one the rider is
on, a window over it aside. Nothing provides it yet, so it's true everywhere and nothing a rider sees
changes; step 3's `NavDisplay` provides it per entry. Two helpers read it:

- `OnTopEffect` is a `LaunchedEffect` that runs only on top, and again each time the page comes back on
  top, as a page re-entering composition does today. It holds the refinement on offer (the near-me trip's
  and the departures view's), the near-me trip's auto-close, `ConsumeForegroundReturn`,
  `ReportChanges`, Lines…'s reopening and every write-failure snackbar.
- `StartedWhileOnTop` is a `LifecycleStartEffect` that starts only on top. It holds the location fix
  (`resumeRefining` and `pauseRefining`).

Every window a page opens of its own (About, a disruption card's, the trip's lines and replace dialog,
the timetable, the boarding route, the rail key, a search row's menu, and the other confirm and picker
dialogs) returns early when its page isn't on top, so a glimpse can't open one over the page being
swiped. The bug report consent and the Departures opened over a route stop stay as they are: the first
belongs to the app, not a page, and the second is the activity's, covered by `routeStopCovered` until
step 4.

What already waits for RESUMED was left alone: a revealed page is never resumed, so a glimpse can't
start it. That's `ReportScreen`, the near-me trip's Direct lookups (`direct.run()`), Lines…'s status
checks and Settings' permission reads. Steps 3 and 4 each note where RESUMED alone stops being enough.

`PageOnTopTest` holds each helper, `ConsumeForegroundReturn`, `ReportChanges` and a window both ways: off
while the page isn't on top, and run once it is.

## Decided (maintainer, 2026-10-22)

Both apply from step 3.

- **The trip from here becomes its own entry in step 3.** Lines… can sit hidden under the trip today
  (`LinesPresence.UNDER_TRIP`), which a stack can't express while the trip is part of the near-me
  entry.
- **Pages open and close with a short animation**, not Navigation 3's default 700 ms fade and not at
  once as today: about 250 ms to open and 200 ms to close, a slide and fade, with the back swipe
  driving the close. The durations get tuned on a device.
