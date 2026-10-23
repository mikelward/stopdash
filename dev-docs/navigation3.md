# Moving the app's pages onto Navigation 3

**Status: under way, on Navigation 3** (maintainer, 2026-10-22: "ship option 1 and write the plan", then
Navigation 3 over Navigation Compose 2.x). Steps 1 to 4 are done (*Step 1: what the spike found*, *Step 2:
what changed*, *Step 3: what changed*, *Step 3b: what changed*, *Step 4: what changed*): the screens over the
near-me view, the trip from here among them, are a back stack with a route stop's windows on it, and the back
gesture draws the real page under them. Predictive back first shipped as "shrink only", every page following
the gesture over a scrim, and pages inside a screen still do (SPEC *Back*). Drawing the real page under every
page is the payoff this plan works toward. The other gain is a navigation model that can't skip pages.

## Why

Before step 3 the app had no back stack. `MainActivity` keeps one flag per screen (`licensesOpen`, `settingsOpen`,
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
3. **The overlays become a back stack.** Keys: `NearMe(generation)` at the root, and `Licenses`,
   `Settings`, `OnTheWay`, `FavoritePlaces`, `FavoriteJourneys`, `Lines` and `Stations` above it, in the
   order they were opened.

   Done: *Step 3: what changed*.
   - The trip from here becoming a page of its own moved to step 3b, below.
3b. **Done:** **the trip from here becomes its own page** above `NearMe`, so Lines… under it sits under it on
   the stack and `LinesPresence.UNDER_TRIP` goes (maintainer, 2026-10-22: in step 3, which this follows).
   - Split from step 3 for the near-me list under it: drawn there during a swipe, the list would build its
     departures model again, which the trip's opening drops so that it stops fetching (`listStores`), and
     fetch for a glimpse. It needs to keep the model paused under the trip, or draw what it last showed,
     first.
4. **Done:** **a route stop's windows go on the stack.** The details and the Departures opened from them
   become entries. Anything pushed after them covers them by stack order, so `routeStopCovered` goes.
   They're drawn over the pages rather than as `DialogSceneStrategy` entries (*Step 4: what changed*).
   - The page under a window entry stays on top (`LocalPageOnTop`) but drops to STARTED, so what step 2
     left on RESUMED pauses under a window: `ReportScreen` would count the page again as the window
     closes, and the near-me trip's Direct lookups and Lines…'s status checks stop while it's open.
     Each is checked against what the window does today, which is pause nothing.
5. **The station flow stacks.** Search, `Station(id)`, then the station's trip. A station opened from a
   route stop's details now goes on top of the station under it instead of replacing it, which is
   `TODO.md`'s "takes the place of the station under it". `KeptPages`, which keeps the flow's pages apart
   inside its one page until then, goes. In four PRs:
   - **5a. Done:** the From… search and a station become pages of their own (*Step 5a: what changed*).
   - **5b. Done:** stations stack, each with its own id, name, To… and models, so the one under the top comes
     back as it was (*Step 5b: what changed*).
   - **5c. Done:** a change of start leaves its station under the From… search, so Back from the search to it is
     the stack's, the swipe drawing it (*Step 5c: what changed*).
   - **5d.** A station's trip becomes a page over the station.
6. **In-screen views become entries, a family per PR.** The near-me page's platform, station and
   journey views and its route and ride pages; the trip's open route and later list; Settings'
   Disruptions page; the favorite rides' alerts and picker. Each family gets the real page underneath
   and loses its option 1 handler. Editors and inline pickers (a saved place's editor, the trip's
   To… and Via pickers) can stay in-entry, following the gesture over a scrim as today.
7. **Cleanup.** Retire the flags the stack now answers for (`Pages.flag`,
   `Pages.kept`), `RideReturn`'s bookkeeping where the stack now holds it, and `PredictiveBack` where
   nothing uses it. SPEC *Back* then says what the stack does.

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
step 4 put it on the stack.

What already waits for RESUMED was left alone: a revealed page is never resumed, so a glimpse can't
start it. That's `ReportScreen`, the near-me trip's Direct lookups (`direct.run()`), Lines…'s status
checks and Settings' permission reads. Steps 3 and 4 each note where RESUMED alone stops being enough.

`PageOnTopTest` holds each helper, `ConsumeForegroundReturn`, `ReportChanges` and a window both ways: off
while the page isn't on top, and run once it is.

## Step 3: what changed

The screens over the near-me view are pages of a back stack (`Pages.kt`), drawn by a `NavDisplay` in
`NearbyArea`, which replaces its switch; `topOverlay`, `overlayPage` and `openOverlayPages` went with it.
`KeptPages` stays only inside the station flow (below).

- **What's open is asked of the stack.** The flags the screens open and close by (`licensesOpen`,
  `settingsOpen`, …) read and write it (`Pages.flag`), so every way in and out of each screen works as it
  did: set, a screen shows over whatever is on top; cleared, it closes. What shows is what was opened last.
  `PagesTest` checks each of `topOverlay`'s rankings against the order the rider opens the screens in. The
  one it didn't reproduce on its own is a screen opened again from under another (the trip on the way's
  screen, reopened to say an End failed): set again, a screen comes up from under the pages opened since.
- **The station flow is one page** (`Stations`) until step 5 stacks it (split in step 5a): open while its search
  or a station is (`Pages.kept`), with its own Backs and their option 1 shrink as before. Inside it, `KeptPages` keeps
  the search and each station's pages apart, as it did across the whole flow, so the search is as it was
  left under a station opened from it (Codex on #1136).
- **Lines… is on the stack only while it shows.** Under the trip from here, it steps off, and the near-me
  page that draws the trip is on top. Its line, stop and scroll were already kept above the pages, so it
  comes back as it was, on top again, when the trip closes. Until step 3b.
- **The near-me generation is its key's.** A bump replaces `NearMe`, dropping what it kept, and leaves the
  pages over it: an arrived trip's Done from its notification over Settings lands on the list afresh under
  Settings, as before.
- **Back from a screen is the stack's.** Each screen's own Back at its root (`RootBack`) hands its close to
  the stack, whose gesture draws the page under it: Licenses, Settings, the trip on the way, the saved
  places (their editor aside), the favorite rides' list and Lines…'s search. A page inside a screen (a
  line's page, Settings' Disruptions page, the editor) keeps a Back of its own, made later, so it goes first
  and still shrinks over a scrim, until step 6.
- **Pages open in 250 ms and close in 200 ms,** sliding an eighth of their width as they fade; the back
  swipe drives the close.
- **A page closing keeps its last look** (found after step 4): closing resets its state at once (the trip
  from here's destination, a station, Lines…'s line), and the page drawn as it slid away showed that,
  the trip's routes turning into its To… search, or built again what closing dropped. Once a page leaves
  the stack its content leaves composition, and its last frame, recorded as it's drawn, is drawn instead.
- **The line and station searches take focus only on top** (Codex on #1136): drawn under a swipe from
  Settings or Licenses opened over one, a search raised the keyboard over the screen being swiped.
- **`ReportScreen` counts a visit by the app leaving the front, not the page pausing.** A page drops to
  STARTED while a swipe is under way, so a swipe let go of paused and resumed it, and counted it twice.

`NearbyAreaTest` drives the host: each page kept for Back as it was left, a closed page forgotten, a new
generation, the page under a swipe drawn but not on top, a swipe let go of, an inner page's Back first, a
page under a swipe not taking Back from the one over it, and the open transition's timing.
`ForegroundReturnTest` and `ReportScreenTest` follow.

## Step 3b: what changed

The trip from here (To… from the near-me list, a stop's To on Lines…, a saved place's chip or shortcut, a place
shared in) is a page of its own, `HereTrip`, over the page it was opened from. The near-me body draws it
(`NearbyArea`'s `body(trip)`), so the location gate, its errors and a re-locate on return apply to it as before.

- **Lines… stays on the stack under the trip** a stop's To planned from it, and `LinesPresence` went: Lines… is
  open while its page is on the stack (`Pages.flag`), and "under the trip" is the stack's order
  (`Pages.isUnder`), by which an arrived trip's Done and a change of start still close it with the trip.
- **The near-me list under the trip is a placeholder.** Its departures model is dropped once the trip covers
  it, not as the trip opens, so the list is drawn as the trip slides in over it (`ListUnderTrip`). A back
  swipe from the trip then draws a spinner in its place rather than building the model again, which would
  fetch for a look and, let go of, leave behind a model the trip's re-locates don't reach. Back from the trip
  builds it again, as it always has, with what the list saved (its route page, its scroll). Keeping the
  model paused under the trip, or drawing what the list last showed, would put the real list under the
  swipe: work in `MainViewModel`, left until a device shows whether the spinner reads wrong.
- **The trip's Back is the stack's** at its root: its routes, the wait for where it goes, and its To… search
  with nothing picked yet (`rootBack` on `TripScreen`, `StationPlaceholderScreen` and `StationSearchScreen`).
  An open route, a later trip's list and a Via… search keep a Back of their own, made later, so they go first.
- **Back from the trip's page with none of its screens up** (its origin still being worked out, or the
  location gate in its place) closes it as its own Back would, its models with it (`forgetHereTrip`).
  Before, the gate over an open trip had no Back of its own, so Back left the app with the trip still open.
- **A new near-me generation opens the trip afresh too** (`Pages.generation`): a place shared in while a trip
  is up replaces it with one to that place, none of the old one's pages kept.

`NearbyAreaTest` drives the list under the trip: drawn as the trip opens, dropped once covered, a placeholder
under a swipe, built again on Back with its route page, and dropped at once under a trip opened over Lines…,
whose Back returns there. `PagesTest` follows the stack.

## Step 4: what changed

A route stop's details (`Page.RouteStop`) and the Departures opened from them or Lines… (`Page.StopDepartures`)
are on the back stack, in the order they were opened, so whatever opens after one covers it until its Back:
Licenses and Settings from their menu, the saved places' editor, Lines… from a line pill, the trip on the way's
screen, and now a trip from here a place in that editor routes to, which used to leave the details over it.
`routeStopCovered`, the list of screens they stepped aside for, went.

- **They stay windows drawn over the pages, not `DialogSceneStrategy` entries.** A dialog entry holds the page
  under it at STARTED, so the near-me list's refresh and clock, the near-me trip's Direct lookups, Lines…'s
  status checks and `ReportScreen` would pause under the details and come back as they closed, where today they
  carry on; and the back gesture from a page over a window would reveal that window's dialog over the page being
  swiped. So `NavDisplay` draws the stack's pages alone (`Pages.pages`), and each window shows while only windows
  are over it (`Pages.shows`), made after the pages in stack order, so the one opened last is on top, a rotation
  included. A window's Back is its own window's, as before.
- **The page under a window is on top** (`Pages.topPage`, `LocalPageOnTop`), as it always was.
- **Opening one again brings it up** from under whatever was opened since (`Pages.show`), as the other screens'
  flags do.

`PagesTest` holds the windows to the stack's order: shown over the page, covered by each screen opened over them
until its Back, Departures over the details or over Lines… opened from them. `NearbyAreaTest` checks a window
leaves the page under it drawn and on top, and is found again under Settings' Back.

## Step 5a: what changed

The From… search (`StationSearch`) and a station's page (`Station`) are pages of their own, where the station flow
was one. A station picked in the search opens over it; a station opened from anywhere else (a route page's stop,
Lines…'s From, a favorite ride's Whole station, the trip on the way's Plan again) opens over whatever is up.

- **The swipe from a station draws the search under it,** as it was left: the station's Back at its root is the
  stack's (`rootBack` on its list, its placeholder, its departures with nowhere to stand, and its trip where the
  trip is all it has). Its trip's routes and To… search otherwise keep a Back of their own, back to the
  station's list, until step 5d.
- **The search's Back is the stack's** too, but where a station's From row opened it: Back from it then
  reopens that station, which was closed for the change and isn't under it, so the search follows the gesture
  over a scrim as before and the station opens over it once let go. Step 5c leaves the station under the
  search instead.
- **The two pages' flags open in place** (`Pages.kept`, now without `others`): set, a page opens over the top,
  or stays where it is if it's open, so the search a change of start goes back to stays under the station.
- **`KeptPages` stays inside the station's page** until stations stack: a station opened in another's place
  still starts afresh, not with that station's pages.

`PagesTest` follows the two pages: a station over its search, its Back to it, a change of start leaving the
search where it was. `NearbyAreaTest` checks the swipe from a station draws the search under it as it was left,
and that a station opened in another's place starts afresh.

## Step 5b: what changed

Each station is a page of its own, `Station(id)`, and what it shows is kept for each station open (`OpenStation`:
its name, the order its rows lead in, the level it was picked at and its To…). A station around a route page's
stop opens over the details and the page they're over, another station's included, where it used to close the
details and take the station's place: Back returns to the details, and theirs to that station.

- **The station showing is the one opened last** (`Pages.station`), under whatever is over it.
- **Each station open keeps its models** (`NearbyDeparturesStores.ownerAmong`), where the flow kept one station's
  at a time: the one under another is drawn as it was under the swipe from that one, and comes back so, its saved
  page (a route page, a scroll) with it. A closed station's go with it, kept in step with the stations open
  (`KeepOpenStationModels`) rather than dropped at each way out, one of which (Back from a change of start's search)
  dropped those of the station still open under it (Codex on #1147). A placeholder in its place under the one
  over it, the first try, dropped its saved page as it went under (Codex on #1147). None of the flow's models
  fetch on their own, so a station kept under another costs memory, not battery. Two stations can be drawn at
  once, so what each works out is its own: the origin its list and trip share and its list's chips, which the
  activity held for every station, are in the station's store (`stationOriginWork`, Codex on #1147). The list
  takes both from whoever draws it, so it keeps none of its own: the near-me list's stay the activity's.
- **A station opened again is brought up** as it was, from under the pages opened since, but picked as a trip's
  new start it opens at that trip's To… (`OpenStation.opened`, Codex on #1147). The trip on the way's Plan again
  opens its station in place of the one showing, as it did.
- **The end of the flow closes every station** (an arrived trip's Done, leaving for near me or the list, a
  place shared in), and a stop's details a station was opened from or over (`Pages.inStationFlow`), which would
  otherwise come back over wherever the app lands; a station's Back closes only that one (Codex on #1147).
- **A station's From row brings up the search it was picked in** when pages are kept between them (another
  station and its stop's details): closing the station alone would show those instead (Codex on #1147).
- **A station picked in the From… search opens at the trip's To…** while a change of start is under way
  (`OriginChange.pickedTo`), where that To… used to wait in the open station's.
- **`KeptPages` went:** each station's page keeps what it saved as any page does.
- **The stack holds a station once for each station open,** each a page the rider goes back through, so its
  walks are of the pages the rider opened, not of anything they have.

`PagesTest` checks a station around a route page's stop going over the details and the station under them, a
station brought up, the flow's end closing every station, and Plan again's in place. `NearbyAreaTest` checks a
station opened over another starts afresh and its Back finds the one under it as it was; `SavedTripTest` checks the
stations open round-trip.

## Step 5c: what changed

A station's From row opens the From… search over the station (`StartSearch(id)`), where it used to close the
station and bring up the search it was picked in. The station stays under it as it was, its models with it
(*Step 5b*), so Back from the search is the stack's: the swipe draws the station's trip as it was left, and
Back finds it so, with nothing looked up again.

- **The change of start's search is a page of its own,** not the search the station was picked in: that one
  stays under the station, for the station's Back to it. One search page, brought up over the station, would
  have taken it from there.
- **A station picked there opens over it,** at the trip's To… as before; its Back returns to the search, and
  the search's to the station the row was tapped in. "Here", or the flow ending any other way, closes the
  change's search with the stations (`Pages.closeStations`).
- **The change's search goes as its station moves:** brought up (picked again there, or a ride's Whole station
  over it), replaced (Plan again) or closed. Picked again, the station comes back from under the search as it
  was, rather than going over it and leaving its Back to skip what it was opened over (Codex on #1152); no search
  is ever left over a page it doesn't change.
- **The change stays under way until a trip is on top again:** the new station's, or on Back the original's.
  A trip says it has appeared only on top (`OnTopEffect`): drawn under the swipe from the change's search, the
  original's would have ended the change while the search was still up.
- **`searchForChange` and `searchAfterChange` went:** no search is opened, or closed again, for the change any
  more; the search the station was picked in, if any, never moves.

`PagesTest` checks the change's search over its station and everything under it, a station picked there, Back
through both, the search going as its station moves, and the flow's end closing it. `NearbyAreaTest` checks the swipe from it draws the station as it
was left.

## Decided (maintainer, 2026-10-22)

- **The trip from here becomes its own entry,** in step 3b, which follows step 3 for the list under it.
  Lines… can sit hidden under the trip today (`LinesPresence.UNDER_TRIP`), which a stack can't express
  while the trip is part of the near-me entry.
- **Pages open and close with a short animation**, not Navigation 3's default 700 ms fade and not at
  once as before: about 250 ms to open and 200 ms to close, a slide and fade, with the back swipe
  driving the close. Shipped in step 3; the durations get tuned on a device.
