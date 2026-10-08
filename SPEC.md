# StopDash

StopDash shows live London transport departures for the stops you care about, at a
glance — on the Android **lock screen**, on the home screen, and in full in the app.
It reads Transport for London's live prediction feed and answers one question fast:
*what's leaving the stops near me, and when?* — plus the disruptions (delays,
cancellations, stop and line closures) that would otherwise make those predictions a
lie.

Primary surface: an Android **lock-screen widget** on devices that support it —
Android 16 QPR onward, where the OS re-introduced widgets on the phone lock screen.
The same widget is an ordinary home-screen widget everywhere else, and the in-app
screen is the full view. See *One widget, many surfaces*.

**First deliverable is the in-app view, not the widget** (maintainer, 2026-09-18):
the departures screen is testable on an emulator and in Robolectric without wrestling
the lock-screen host, and it exercises the whole spine — the TfL client, the domain
logic, persistence — that the widget then renders from. The widget follows once that
spine is proven. `TODO.md` phases it this way.

Minimum supported version: Android 14 (API 34), the device floor across the sibling
fleet. The lock-screen *placement* needs Android 16 QPR; stopdash's widget is a
standard widget that becomes lock-screen-eligible where the OS allows it, so there is
no separate lock-screen code path.

Coverage is London / TfL only. StopDash is not affiliated with Transport for London,
and uses the free, public TfL Unified API.

## Product behavior

StopDash watches a small set of **stops** and, for each, shows the next few departures
and any disruption that would change whether you'd trust them. A "stop" is a TfL
`StopPoint`: a bus stop, an Underground/Overground/Elizabeth-line/DLR station, a tram
stop, or a pier.

### Watched stops

The user builds a short list of **watched stops** — the stops stopdash shows. A watched
stop can be narrowed to specific **lines** and/or a **direction** (inbound/outbound,
or a named platform), so a surface shows only the departures the user actually takes —
"Victoria line southbound at Warren Street", not every service through the station.
The default is all lines, both directions.

Watched stops are the source of truth for every surface, chosen ahead of time — see
decision **D1** for why the widget renders watched stops rather than "nearest to me".

### Finding stops

The app finds stops two ways:

- **Near me now** — with location permission, stopdash lists the stops nearest the
  user's current position (TfL `/StopPoint` by coordinates) so pinning the right ones
  is one tap. It asks for **precise location** (`ACCESS_FINE_LOCATION`): a coarse fix
  can be off by up to ~1 km, enough to read a stop half a mile away as the nearest, so
  precise is what makes "nearest" mean nearest. An *approximate*-only grant still works,
  degraded, rather than dead-ending. So the fix sent to TfL on demand is precise when the
  user grants precise **and an accurate fix is available** — it prefers GPS/fused, but falls
  back to an approximate (network/passive, or an approximate cached) fix when no accurate one
  can be obtained, so even under a precise grant the fix sent is occasionally approximate;
  under an approximate-only grant it is always approximate. Precise location is the Play Data
  Safety type the action **may collect** and so declares, not a claim that every fix is
  precise; either way it is never a background send. This is an in-app,
  on-demand action, never a background one.

  The fix is taken when the near-me view first resolves, **on every refresh**, and **on a
  return to the foreground** (maintainer, 2026-09-23): the crosshairs and pull-to-refresh
  re-resolve the nearby set as well as re-fetching departures, and reopening the app after it was
  backgrounded does the same, so a user walking from stop to stop sees the set follow them without
  a manual pull (the common "walk to the next stop and check" case). It stays **foreground and
  user-adjacent** — the fix is bounded to the user's own refreshes, app opens, and the list
  following them while it's on screen (below), never a background send (the on-screen
  auto-refresh keeps departures live but does **not** relocate). Sending the location on a foreground return is the deliberate trade for that UX. Its
  cost is **one extra forced fix and one extra `/StopPoint` request per app-open** (on top of each
  refresh's): a small, bounded **battery** draw on the locator for the ~1–2 s fix, and one more
  keyless TfL request (**£0**, well within the ~50 req/min budget). It adds **no new Play Data
  Safety surface** — the same precise-location-to-TfL the near-me action already declares, on the
  same foreground, user-adjacent path, not a new recipient, category, or background collection. The re-locate
  **forces a fresh fix** (it does not take the
  recent-cached fast path a first open may use): a rider who has walked since the last fix
  must not be re-resolved against the old position, so a cached fix is only a bounded fallback
  here — a fresh fix is ~1–2 s in the common case, comfortably under the refresh spinner. For the
  same reason, a re-locate still under way when the app was backgrounded (a pull, say) doesn't
  stand in for the return's: it was asked from where the rider was then, so the return re-locates
  over it and its own fix wins. If it ended while the app was away without stops to show (no fix,
  a failed lookup, none nearby), the return looks again with a fresh fix rather than leave that
  outcome up until the rider retries.
  Re-resolving skips the "locating…" spinner on the common success path — the departures stay
  on screen during the fix rather than
  flashing back to the gate on every pull — but an unsuccessful re-resolve is **surfaced
  honestly, not swallowed** (principles 1–2): a failed fix, an unreachable lookup, or an
  out-of-range "no stops nearby" replaces the list rather than leaving a previous location's
  stops on screen as if current (cards omit the stop name, so a stale set is
  indistinguishable from the real one). The app bar carries a **crosshairs** ("use my location")
  in place of a refresh button (maintainer, 2026-09-25): a Refresh button that also moved the
  list read as unclear. On the near-me list, and a trip from here, it re-locates as above; on a
  **From…** station page, and a trip from it — loading, failed or empty included — it leaves the
  station for the near-me list. The
  one-minute auto-refresh keeps departures live, and pull-to-refresh still refreshes (re-locating
  near me), so no separate refresh control is needed.

  **While the list is on screen, it follows the rider** (maintainer, 2026-10-02): a rider who
  walked off with the app open kept the old stops until they pulled. Location updates are asked
  for every ~12 s while the near-me list (or a trip from here) is on screen and the app is in the
  foreground, and stop the moment it isn't — including while a failure or "no location" is shown,
  which has no list to move. "No stops nearby" counts as a list here, as it does for the precise
  follow-up: it was found from a position, and walking on from a stopless patch is exactly when
  moving finds stops. An update moves the list, through the same path a
  precise follow-up takes (below), when it is **sure** (a fresh fix reporting accuracy within
  50 m, so an underground network fix placing the rider at the wrong station never moves it),
  **100 m or more** from where the list was found, and **a minute or more** after it was found, so
  a brisk walk costs TfL one lookup a minute at most. A move that comes inside that minute waits
  it out rather than being dropped, so a rider who stops at the next stop still has the list moved;
  if the wait has left it older than the freshness bound, one fresh precise fix is taken instead. A trip on the way's own fixes count too, at
  no extra battery. Its cost is the locator running while the list is open (the interval is the
  battery lever; the distance only gates callbacks) and up to one `/StopPoint` request a minute
  while moving (**£0**, well inside the budget); no new Data Safety surface.

  A fix the device can't refresh — the fresh attempt failed and a **bounded last-known** fix is
  used instead (no GPS underground, where a station's Wi-Fi also places the network provider at a
  *different* station) is **low-confidence**, and never silently presented as the current position
  (principle 2). On a re-locate the set is **not jumped** to it — the stops already shown are kept
  rather than re-resolved to a previous position — and a **top banner** over the list says the
  location couldn't update, with a **Try again**. A set that could only be resolved *from* such a
  fix (a cold start with no fresh fix) shows the same banner worded "your last-known area." The
  banner clears the moment a fresh fix resolves. Gating on the fix's *measured accuracy/age*
  (a fused fix can itself be Wi-Fi-derived) is a later refinement (`TODO.md`).

  A **coarse fresh fix** — a network one, used because GPS didn't answer within the short grace —
  can be hundreds of meters out, so the stops nearest the rider can be missing (maintainer bug
  report, 2026-09-25). Two things keep that from standing (maintainer, 2026-09-25):
  - **The last precise fix is remembered for 10 minutes**, in memory only. A coarse fix whose own
    accuracy circle still contains it — the rider may well not have moved — defers to it, and the
    list is built from the precise fix. A precise fix **no older than two minutes** — the age at
    which a cached one is used outright on open — stands as the current position, with no banner:
    the coarse fix agrees with it, and indoors GPS never answers to clear a banner, so it stayed up
    over a 12 m fix from half a minute before (maintainer bug report, 2026-09-25). An older one is
    still only a guess (the rider may have moved within the circle), so it is treated as the coarse
    case below: banner, and a GPS check that confirms or moves it.
  - Otherwise the list is shown from the coarse fix at once, under an **"Approximate location"**
    banner, and GPS is **asked again** for a few more seconds. A precise fix within 100 m confirms
    the list and clears the banner; a farther one **moves the list** to it, re-picked as a refresh
    does. None in time, and the banner stays, with its Try again. "No stops found nearby" from a
    coarse fix says "Approximate location" too, and looks again from any different precise fix.
    "No stops found nearby" says why it most often happens (maintainer, 2026-10-02): "StopDash only
    shows stops in and around London." The ask is foreground-only: it
    stops when the list is left or the app backgrounded, and is made again on return while the
    banner still shows. The list is a
  **useful, scannable spread, not a raw nearest-N**:
  - a **line appears once**, not once per stop it passes — a raw nearest-N repeats the same
    bus route several times, one per adjacent stop, which reads as noise;
  - a **denser mode doesn't crowd out another** — the nearest station of a mode surfaces even
    when several stops of another mode are closer, as long as it's within reach (so the
    nearest Tube shows even where bus stops dominate the immediate area; the metro modes count as
    one, below);
  - it is **bounded to within reach** — on the order of a mile — so a far stop never appears
    just because nothing nearer shares its line. That reach is the TfL lookup's own radius; a
    London locate effectively always has a stop within a mile — if somehow none does, an honest
    "couldn't find stops" beats reaching arbitrarily far. The list shows the **nearest two
    clusters of each mode**, which keeps a dense interchange scannable and caps how many
    clusters are fetched; farther stations and bus stops are reached through the *farther*
    cards at the foot of the list (see below);
  - it is **by line, both directions shown** for now — paired stops across a road serve a line
    in opposite directions, so neither direction is dropped; narrowing by direction or
    destination is a later refinement tied to *favorite destinations*;
  - a **route's two directions stay at one place** when they can (maintainer, 2026-09-25): each
    direction goes to its nearest stop, but where that would put northbound at one stop pair and
    southbound at another, both come from a place serving both ways if its stop for each
    direction is **within 50 m** of that direction's nearest. Two equally near stop pairs
    otherwise split a route across two headers on a few meters' difference. A place is a TfL
    stop area (a road's pole pair); over 50 m, the route splits for the shorter walk. A direction
    is never moved onto a stop with a closure or other stop notice in force (TfL can still list
    times at a closed pole): its open nearest stop keeps it, and the notice still shows;
  - a **route showing only one direction joins its neighbors** (maintainer, 2026-10-04): with
    nothing of its own to keep together, it would otherwise sit at its nearest stop and split two
    routes along one road across two headers on whether the other direction happens to have a
    departure due. It moves to a place within 50 m that serves it and already carries routes kept
    there in both directions — the one carrying the most — under the same closure rule. A route's
    **no-times row** (disrupted, no countdown at a pole) joins the same way, first to the place its
    own timed rows were kept at, so "due one way, Diversion the other" reads as one place;
  - a **closer closed stop is surfaced honestly** — its status is shown rather than silently
    routing the user to a farther open stop with no explanation.

  The selection is **two-tier** (maintainer, 2026-09-21): stops group into **clusters** (a
  station's platforms, a bus junction's poles — keyed on TfL's `stationNaptan`, D8), and the
  **nearest two clusters of each mode within 500 m** are *eager* — their departures fetched and
  shown at once. A mode with nothing that close contributes just its **single nearest** cluster, out
  to the ~1 mile reach, so the nearest station of a sparse mode (a Tube up to a mile off) is always
  eager without the eager set reaching a mile out for every mode (maintainer, 2026-09-23: "the
  nearest two of each mode" fetched a second Overground station 1.3 km from a big interchange).
  **The Tube, the Overground, the DLR and the Elizabeth line count as one mode here** — London's
  metro rail (maintainer, 2026-09-30): turn-up-and-go rail across the city, so a station of any of
  them in reach stands for all four, and they share one cap of two. Counted apart, each fetched its
  own nearest station however far: at King's Cross, with the Tube a few hundred meters off, Euston
  (800 m) was fetched on every refresh as the nearest Overground, bringing its whole National Rail
  board with it — requests against the keyless rate budget for a station nobody there walks to. A
  metro line no loaded stop serves still gets its farther card (*Farther stations*), which costs no
  request until tapped. National Rail, trams, buses and the rest still pick apart, and only picking
  folds the four: hiding a mode keeps its own groups (*Hiding a mode*).
  **An interchange's rail stations count as one place** toward their mode's cap, and load together
  (maintainer, 2026-09-30): King's Cross and St Pancras are separate TfL stations in one
  interchange, as are Canary Wharf's Tube, DLR and Elizabeth line stations. Counted apart, the cap
  took the two St Pancras stations and left King's Cross mainline out, and since its interchange was
  already on the list it got no farther card either — so the list showed St Pancras's trains and
  silently none of King's Cross's LNER or Great Northern ones, reading as the whole interchange when
  it wasn't (principle 2). The cost is the interchange's other stations' requests: two more per
  refresh at King's Cross. Bus poles around an interchange still count one by one — each is a stop
  and a request of its own. The
  stop lookup itself still covers the mile — one request for every stop's name and routes, no
  departures — so the fallback and the farther bus cards have the whole reach to draw on. A stop TfL lists **no routes** for (a disused or
  unserved stop) is **never eager**: it has no departures to show, and auto-fetching it spent two
  requests a pole of the rate budget the running stops need (a big interchange pulled in route-less
  stops a kilometer off), and it isn't offered at all, since it has nothing to show. The cap is two rather than more because
  expanding is not free: TfL doesn't aggregate a bus junction, so each lettered pole is its own
  arrivals request, and a low eager cap keeps the list short and caps how many clusters are
  fetched — sharply fewer than "everything in reach" at a dense corner. It bounds the cluster
  *count*, not the request count: one large junction cluster is still an arrivals request per
  pole, so a hard per-cluster fetch budget is a `TODO.md` follow-up. The clusters beyond the cap
  are the *more* tier, and **no "More" button pages it** (maintainer, 2026-09-25): its bus places
  come in through the *farther* bus cards (*Farther stations*, below), which name the routes a tap
  would add, and its stations through the farther-station cards. Every mode once had a "More"
  button at the foot of the list; the station ones went when the farther-station cards arrived,
  since those offer the next station of each line both ways along it and out to 3 mi, and "More bus
  stops" went when the bus cards replaced it. **The widget mirrors the near-me list's own stops**:
  an opened card is never on it.

  To keep the lookup fast and honest: a recent cached position is used at once. A fresh fix asks
  **every location provider at once**: an accurate (fused/GPS) fix is used as soon as it arrives,
  and an approximate (network) one after a ~2 s grace if no accurate fix beats it — so indoors or
  underground, where fused and GPS can't see the sky, the list starts from the network fix in a few
  seconds rather than after each accurate provider in turn has timed out (~10 s; maintainer bug
  report, 2026-09-23). If a fresh fix
  is slow or absent, a *somewhat-stale* cached one substitutes for it rather than making the
  user wait or fail — but only within a bounded age, past which stopdash reports "couldn't get
  your location" rather than showing a previous location's stops as current (a user who has
  traveled would be misled). A failure to get a fix is always logged, and so is each fix the
  app uses — which provider supplied it, the accuracy radius it reported, and its age, never the
  coordinate — and each lookup's stop count. The positions themselves, and each lookup's stops
  with their distances (which would pin the position down), go only to a short in-memory window —
  each fix the app got (a rough one set aside for a remembered precise one included — it is where
  the network placed the user, the underground diagnosis) and each lookup, the last 15 minutes and
  at most 20 entries, never the log or its file — which the consent-gated bug report includes
  (maintainer, 2026-09-25: a window around a problem, not a record of where someone has been).
  Together they make a misfire, like station Wi-Fi placing the user at the wrong station,
  diagnosable from a bug report.

  Both tiers draw from one TfL `/StopPoint` lookup within the **~1 mile reach** (the lookup's
  own radius); the eager clusters' stops are fetched for arrivals at once, and a *more* cluster's
  stops are fetched only when its farther card is tapped (*Farther stations*). **The reach and the two-per-mode cap are
  not yet validated on a device** — whether either wants tuning at a real interchange lives in
  `TODO.md`; what is durable is the two-tier shape and the constraints above. **The near-me list is ordered
  closest stop first**, with soonest-first breaking a same-stop tie (a stop's several services
  are equidistant). **A line-status alert (a suspended line's status row) rides with its
  stop and gets no special order** — it is not hoisted above a closer stop, nor lifted within its
  own stop's section; it simply trails that stop's departures, so a nearer stop is never pushed
  below a farther one for carrying an alert. Starred rows are still pinned to the top. **A stop
  notice rides with its place too** (maintainer, 2026-09-26): drawn at the place's distance, not
  lifted to the top (*Disruptions*). Earlier the alert lifted its whole stop above closer ones, which read as
  the app ignoring distance. Distance orders only this location-derived list — never the
  location-free watched list, which stays soonest-first (D1). Each near-me stop's header also **shows its distance** in parens after
  the name ("Oxford Circus (120 m)"), so a rider can judge which of two nearby stops to walk
  to rather than only reading the order; the watched list carries no distance and shows none
  (D1). **Distance units** are a setting (maintainer, 2026-09-25): *Auto* (the default) follows
  the phone's locale — yards and miles in the UK, feet and miles in the US, meters and kilometers
  elsewhere — and *Meters*, *Yards* or *Feet* pin one. Each choice is named by its short unit, and
  a longer distance reads as a fraction of the long one ("0.6 km", "0.4 mi") rather than a long
  count of the short: meters and yards switch at ~500 m, feet at a tenth of a mile, as US maps
  do. That order is a
  provisional starting point to judge on a device (it keeps
  what's nearest on top and cuts reshuffles, at the cost of the closest stop leading even when
  nothing leaves it soon); the reasoning and alternatives live in `TODO.md`. (How the
  *services* a line repeats across adjacent stops collapse to one row is *Departures*; this is
  only which stops are looked up.)
- **Find a station** (maintainer, 2026-09-24) — the overflow's *From…* (labeled so a *To…* for
  a trip can sit beside it; spelled out as *Find a station* on the location
  screen's button, since it needs no location) searches
  TfL's stops by name as the user types (a short pause after the last letter, and at least
  two letters, so a name costs one request rather than one per keystroke). Each match shows
  its name and modes on one line, the name given priority (ellipsized only when it must; the modes
  bounded to a share of the row so a many-mode station can't squeeze it). A long word a rider needn't
  see spelled out is abbreviated **for display only** — today *International* → *Intl* (maintainer,
  2026-09-27) — while the full name still backs stop matching and disruption text, which need TfL's own
  spelling. Picking a match opens **the near-me list as if you stood at that station**
  (maintainer, 2026-09-24): the station's own stops (distance 0) and the other stops around it,
  chosen, ordered and folded exactly as near me is, with distances from the station, and the same
  *farther* cards and hidden-mode behavior (maintainer, 2026-09-25: the cards replace the
  *More* buttons there too). The station's position (the middle of its stops) stands in for
  the device's, so the page shares the near-me list's code rather than keeping a second copy of it;
  a station TfL places nowhere falls back to its own stops alone. The page is titled by the
  station, back returns to the search with its matches kept, and it refreshes while shown (a
  refresh re-picks from the same place) like the main list. It is a look, not a pin: the widget keeps showing the near-me set, and the
  station is only remembered in the search's own *Recent* list (below). Stars and dismissed
  alerts are shared with the main list.

  **The user's own stops** (maintainer, 2026-09-24) come without a TfL search. Before anything
  is typed, the search lists them **by last use** (maintainer, 2026-09-26): **Recent** first (the
  last eight places picked from that search, the most recent on top), then **Starred** (the ends
  of favorite journeys, then the places holding a starred row, each recorded when starred), less
  any picked lately. *From…* and *To…* keep **separate recent lists** — where the rider looks from
  and where they go — so each search lists its own history. As the user types, those and every place the app has lately shown near them (the
  widget's last departures and the nearby-lookup cache, less any stop TfL lists with no lines,
  which would open to nothing) match on the device alongside the bundled stations, so a starred bus stop appears at once; a bus stop (a journey's end
  included) lists as its stop area, whose page holds its poles. A recently picked or starred place leads its matching tier, the most recently picked first. All
  of it is read from the device and sent nowhere; the recent list stays on the device and out
  of backups (*Privacy*).

  **Matching** (maintainer, 2026-09-24) runs on the device against a **bundled list of London's
  stations and interchanges** (tube, DLR, Overground, Elizabeth line, tram, rail and piers — not
  bus stops), so results appear as the user types and an abbreviation or a station code finds
  its station: "KX", "KC" and "KGX" all find King's Cross. The list is built from TfL by a
  workflow and ships with the app; TfL's own search still runs after the typing pause for what
  the list lacks (bus stops, a newer station), and both are ranked together. The ranking is
  TypeLauncher's, so the fleet's type-to-find surfaces agree: a name that **starts with** the
  query first, then one whose **word starts** spell it ("kc" → King's Cross), then one that
  **contains** it, then one holding its letters **in order**. A station code counts as a
  prefix. Apostrophes, punctuation and accents are ignored ("kings" finds King's Cross).
  Abbreviations are **generated, never listed**: a word "Cross" also reads "X", so "KX" and
  "CX" (Charing Cross) need no alias table. Within a tier an interchange leads, then the
  shorter name. A station whose interchange also matches is folded into it, since the
  interchange's page holds it. TfL lists a place's bus stop areas one by one, so results **of
  the same name within 250 m of a better-ranked one fold into it** (maintainer, 2026-09-25):
  "Archway" is listed once, not once per stand, and its page takes in the stops around it. A
  station TfL lists under **two ids with no interchange** to stand for both (Weybridge) opens both:
  its page asks for each id's stops, and fails with a retry if either lookup does rather than
  showing half the station as if it were whole.
  Same-named places farther apart ("Church Street" in two boroughs) both stay, and a
  result TfL gives no position for is never folded. If TfL's search fails but the list matched, the list's matches
  stand, with a line saying bus stops weren't searched.
- **From… To…** (maintainer, 2026-09-24; planned since 2026-09-26) — **To…** plans a trip to a
  picked station or stop, as *Trips with a change* below describes; a pinned **Home/Work favorite**
  instead plans to its saved coordinate (D9). It is offered on the **near-me
  list**, from where the rider is, and on a **searched station's page** (reached with the overflow's
  *From…*), from that station, titled "From ➔ To"; back returns to where it was opened. A station
  whose stops TfL gives no position for has no *To…* yet (`TODO.md`). The first
  design, keeping only the departures that call at the destination directly, was superseded by the
  planner: a trip needing a change is planned the same way as one that doesn't. For an **ordinary
  stop pick**, the destination's name and id go only to TfL, as the station search's already do
  (*Privacy*), and *To…*'s own *Recent* remembers it on the device; a **saved favorite** instead sends
  its **coordinate** (D9); its name and stable id are **never sent to TfL** (they ride Android backup /
  device transfer with the rest of the user's config, per *Privacy*).
- **Where a trip starts: the From row** (maintainer, 2026-09-28) — the *To…* search's bar is two
  labeled rows, **From** over **To**, with the saved places' chips just below, lined up under the To
  field as its quick picks. From names the start:
  **"Here"** behind the crosshair when it's the rider's position, else the *From…* station's name; To
  is the search field. **The routes keep the same bar** (maintainer, 2026-09-28, option B of the
  mocks), so a planned trip always says where it starts and each end is one tap to change: From as
  above, and To, naming the destination, reopens the *To…* search with the start kept — a pick plans
  again from the same start, and Back returns to the routes. The app's overflow sits at the From row's
  end; one route opened is titled "To ‹place›" as before, and Back from it returns to the bar.
  A tap on From opens the *From…* station search, which heads its list with
  **"Here"**. Picking a station there opens that station's trip **as it was**: its *To…* search when
  the row was tapped in one (what was typed for the destination kept), or its routes to the same
  destination when tapped over routes — the start changes, nothing else. Its page's placeholder, with
  Retry, shows meanwhile: nothing can be planned until the new start is known. "Here" (or the
  crosshair on a station's page, which means the same) goes on as the near-me trip, again as it was,
  and Back returns to the trip the row was tapped in. The change is done only once the new start's
  trip appears (the new station's, or on Back the original one's, reloaded): backing out of a station
  still loading, one that failed, or one with nothing to start from (every mode there hidden) returns
  to the *From…* search with the change still under way, so another station opens at the trip's *To…*
  and Back or "Here" still lands where the row was tapped. A station with nothing to start from
  shows its own page, which says why and offers "Show all": showing its modes opens its trip at the
  same *To…*, as if it had had somewhere to start all along. So changing the start, or thinking
  better of it, never loses the destination, picked or being typed. Leaving a station to change
  the start closes its page, which stops its refreshes. This replaces a From **chip** built and
  removed earlier the same day; the near-me list's place chips still have no "Here", since a chip for
  where the rider already is adds nothing. A saved place or geocoded address as the **start** is not
  offered yet (`TODO.md`).
- **To… a place or postcode** (maintainer, 2026-09-27) — the *To…* search offers **geocoded places**
  (a landmark, an address, a postcode) among the stop matches, each tagged *Place* or *Postcode* in
  the right column where a stop shows its modes; tapping one plans to its **coordinate** (a final walk
  leg, D9), like a favorite. TfL's Journey Planner is the geocoder (the only one in the free Unified
  API), the same call the postcode resolver makes — so it adds a **second TfL request** per *To…*
  search alongside the stop search (*Cost*; only a *To…* picker makes it — from the near-me list or a
  *From…* station alike — not the From… search itself), and
  sends only the typed query, as the stop search already does (*Privacy*). TfL's geocoder orders its
  candidates **noisily** (a weak partial can outrank the obvious landmark), so a place-name query is
  **re-ranked by the app's own name matcher** (Prefix over Anchored over Substring over Fuzzy) and
  non-matching candidates dropped; a postcode query keeps TfL's order (its resolved location's name
  needn't contain the digits). Stops and places are **one list ranked by match**, so a place the query
  starts ("Tate Britain" for "tate") sits above a stop that only contains it ("… Estate"); a stop wins a
  tie, and a postcode's places follow the stops (maintainer, 2026-09-28). The list **never reorders
  under a finger**: the bundled index's best four show at once with a spinner beneath them, and TfL's
  stops, then its places, are **appended below** what's already listed as each arrives, ranked among
  themselves, even when they match better (maintainer, 2026-09-28: append, don't reorder). A listed row
  takes TfL's fuller copy of itself in place, and one TfL's answer shows to be a duplicate of another
  (the same station twice) leaves. Only coming back from a station re-ranks the whole list. A name in parts — a stop with
  its cross street ("Foo Street/Bar Road"), a place after its area ("City of Westminster, Tate
  Britain"), an interchange after its stations ("King's Cross & St Pancras International") —
  matches a query starting **any part** as a prefix, so "st pa" finds St Pancras. Best-effort: a geocode failure yields
  no places and the stops still stand. A better geocoder is a later option (`TODO.md`). A place
  picked is **remembered under *To…*'s *Recent*** with the stops picked there, in the order picked
  (maintainer, 2026-10-02), its name and coordinate kept on the device as the stops are (*Privacy*);
  a tap routes to it again, tagged as in the results. It matches nothing typed (the geocoder finds it
  again), and a search that routes to no place (*From…*) never lists one.
- **Farther stations** (maintainer, 2026-09-25) — where the near-me list reaches only one tube
  station, the rest of the network can be two miles off. Below the loaded places, a **collapsed card** stands for the nearest station of each **rail line** the loaded
  nearby stops don't serve (a station left unfetched in the *more* tier doesn't count, since nothing
  else would page it in) — a tube line, a National Rail service (Thameslink, Great Northern…), an
  Overground line, the Elizabeth line, the DLR, a tram — nearest first, within 3 mi, **at most
  eight** cards, from at most two tube lines. A line's nearest station can lie the wrong way for
  the rider's trip, so a line also gets its **nearest station the other way** (maintainer,
  2026-09-25): more than a right angle round from the nearest one, seen from where the rider
  stands. **Buses never** earn one: every stop has them.
  A station standing for several lines is one card, and an interchange's stations are one card
  standing for the interchange; a hidden mode's lines get none. A **National Rail** service counts
  by the **ends of its routes** from each station (maintainer, 2026-09-25): Thameslink runs to
  Bedford from one station and to Cambridge from another, so a station reaching an end nothing
  nearby reaches earns a card even on a service already reached, and a route end, which names its
  direction already, keeps one station. The other modes count by line: a
  second tube station on another branch of a line already reached isn't offered (a branch rule
  would be finer but noisier). A card is headed like a loaded place, its name and distance, over
  **one row of the lines it adds** and **"Tap to see"** where the times would be (maintainer,
  2026-09-25). A tap looks up the station's stops and loads their departures, and the card **opens
  in place**, below the loaded places, so the list doesn't jump; the cue reads "Loading…" meanwhile,
  "Tap to retry" if it failed (a tap retries), and a line row's dash if nothing is running ("Closed"
  when its notice says the station is closed, as for a held card under *Freshness → Cold load*). An
  opened station shows only what the list doesn't already show from a nearer stop, like any
  near-me place, and refreshes with the list. It is **held for the session, not saved**
  (maintainer, 2026-09-25): it stays open while the list still offers it, closes when a move stops
  offering it or the app's process ends, is never on the widget, and has no collapse control (it
  would be a new tap target on the card). Nothing is fetched to offer the cards:
  the positions, lines and route ends come from the bundled station list, worked out on the
  device, so they cost no request and send nothing (*Privacy*); a tap costs one stop lookup plus
  each stop's departures.

  **Farther bus places** (maintainer, 2026-09-25) get the same collapsed card, replacing the "More
  bus stops" button, so the rider sees which routes a tap brings in. A bus place — a junction's
  poles, or an interchange's same-named ones (a card is named after its nearest pole, so a
  differently named stop gets its own) — within the nearby lookup's mile, beyond the list's nearest two, earns
  a card when a pole serves a **bus route the list doesn't already show** and no nearer card
  already offers it. The card names **every route a tap would show** — each one the list doesn't
  already show, even one a nearer card names too (maintainer, 2026-09-26): naming only the routes
  no nearer card offered read "N20" on a card that opened to a 234. A place **next to a station the
  list shows** (a pole within 150 m of it) claims its routes before the others (maintainer,
  2026-09-26): the rider walks to the station anyway, so its bus stops beat a place a little
  nearer as the crow flies but a longer walk — the lookup gives straight-line distances only, and
  walking ones would cost a journey-planner request a place. **At most four** bus cards, nearest first.
  They sit **below the station cards within a mile and above the farther ones** (maintainer,
  2026-09-25). A farther pole of a route the list already shows, running the other way, earns no
  card (the route's own page shows its stops both ways); a route-less stop earns none, and hiding
  buses hides the cards too. Offering them costs no request: the nearby lookup already listed each
  stop's routes, and a tap fetches just the place's poles, which it already knows, with no lookup.
- **Search to pin** — by stop name or by line, for pinning a stop the user isn't standing at
  (home, work, the school run); arrives with watched stops.
- **Hiding a mode** (maintainer, 2026-09-24) — a busy place can fill the near-me list with a mode
  the user doesn't ride (a dozen Thameslink rows, every bus at a junction). Modes hide by **group**,
  named as a rider thinks of them (maintainer, 2026-09-24): **Underground** (TfL's turn-up-and-go metro: the
  Tube, the DLR and the Elizabeth line), **Overground**, **National Rail** (other operators'
  timetabled trains, Eurostar with them), **Bus**, **Tram** and **Boat** (TfL's "river bus"). The
  menu says under a name what it doesn't ("Tube, DLR, Elizabeth"; "Thameslink, Southern, …"), kept
  short; the trip chips, with no room, carry the name alone. Until 2026-10-08 the groups were Tube &
  DLR and one **Train** (maintainer: National Rail is the one that floods a big station and has no
  times without a key, and riders ride the Elizabeth line like the Tube); a stored hide follows the
  new groups, the Elizabeth line the Tube's, and a trip's Train choice carries to Overground and
  National Rail. A coach counts as a **Bus**: TfL lists no coach routes
  or stops, and riders don't tell the two apart (maintainer, 2026-10-08; until then Coach was a sixth
  group, empty in practice). The overflow menu lists all six, always the
  same, each with a checkbox ticked while it shows. **National Rail starts unticked while no
  National Rail key is set** (maintainer, 2026-10-08): without one its rows have no times and flood a
  big station. Ticking it while no key is set, however it came to be hidden, opens a dialog saying a free Rail Data Marketplace key gives its
  times, with **Get a key** (the sign-up page), **Add key** (Settings) and **Show anyway**. Adding a
  key shows it by itself. The default is never stored: once the rider shows or hides National Rail
  themselves, their choice stands, key or none. The near-me banner and Settings' Hidden list leave
  the default out (an empty list still names it). A trip follows it like any hide (maintainer,
  2026-10-08): no routes riding National Rail without a key, its banner naming National Rail, whose
  Show all shows it. Exempting trips meant a stop pick of their own, since the near-me pick leaves out
  a station only National Rail serves. A long press on a near-me
  row opens a small menu, pinning or unpinning it and **"Hide all ‹group› services"** (a bare "Hide
  Train" read as hiding that one train); a long press on a place's header offers it for each group
  it serves. Starring a near-me row therefore takes the
  menu's first item. Journey cards keep a long press as a direct star, since a favorite journey is
  the user's explicit choice and hiding doesn't reach it.
  A hidden mode's stops aren't picked for the near-me set, so they cost no request; a place that also
  serves other modes keeps them, with the hidden mode's rows left out, and the widget leaves them
  out too. With National Rail hidden, such a place's National Rail board isn't asked for either,
  from the next refresh of the list, a farther station's card, a trip or the widget, since it
  would only fill rows left out; a favorite journey taking a National Rail line from the place still gets its
  times, and a place showing National Rail again ("Show all", or a journey starred from it) is
  fetched with its board at once. A searched station's page shows all its services, so it keeps its
  board. A closure still shows at a place that keeps an unhidden mode, so hiding one mode never
  hides a closed stop the user still rides from; a stop serving only hidden modes isn't checked at
  all, since its closure matters only to a rider of that mode and checking it would spend the
  requests hiding saves. Hiding takes
  effect on the list at once, and the fetch saving from the next re-locate; if every mode nearby is
  hidden, the stops are still fetched so the list can say what's hidden rather than claim nothing
  runs. While any mode is hidden, **a one-line banner** over the list names them ("Bus hidden") with
  **"Show all"**, which brings them back from the same fix without a new lookup, so a shorter list
  never passes for all there is (principle 2). The hidden set is a setting, kept on the device.
  **A single line hides the same way** (maintainer, 2026-09-27): a row's long press, and a trip
  card's for each line its legs ride, also offer **"Hide ‹line›"** ("Hide Northern line", "Hide
  134"), for a rider of the mode who never takes that line. A hidden line is left out everywhere a
  hidden mode is — the list, its stop picking, trips, the widget and the watch — and the banner
  names it with the hidden groups ("Northern line hidden"), counting several ("2 lines hidden");
  "Show all" brings lines back with the modes. It lives in the same hidden set as the modes, so
  everything that follows one follows the other. A place's header doesn't offer lines: a busy one
  would list dozens. **Bringing back one item** (2026-10-01): a hide from a long press offers
  **Undo** for a moment ("Northern line hidden · Undo"), which shows just that group or line again
  — offered on the screen landed on when the hide ended a trip by hiding all it started from —
  and **Settings lists what's hidden** while anything is, each group and line with its own **Show**
  — where "Show all" is the one way to bring everything back. Whatever shows something again
  re-picks the nearby set from the same fix, so its stops come back, as "Show all" does; a list or
  trip out of view when Settings showed it re-picks as it comes back.

### Departures

For each watched stop, stopdash shows the next few departures: **line**, **destination**
(where the service is headed), and a **countdown**. Countdowns render as minutes — "0 min"
when imminent, "3 min", "12 min" — sorted soonest-first.

**A service that goes nowhere for the rider is left out** (maintainer, 2026-09-24): one whose
terminus is a nearby place no farther from the rider than the stop it leaves from — a bus
arriving to terminate at this stop, or a train ending at the station the rider is nearest to.
Boarding it would only bring them to where they already are. The terminus is matched by TfL's
destination stop id against the nearby stops and their stop areas or stations; only when TfL
gives no id, by name (two places can share one). The services are hidden as the rows are
built, not dropped from the stop's data, so a new location applies at once; each stop's
nearer places are saved with it in the snapshot, so the widget, whose own refresh has no
location, hides the same services. A stop with no known distance (a journey's far
origin, a searched station) keeps every departure. A line whose predictions were all left
out gets no "No departures" status row either, since it has departures, just none that help.

**The unit of display is one card per platform or pole** (maintainer, 2026-09-22) — a
group of same-cluster stops split by platform / stop letter (see below). Each card is
headed by a **single title-case line** naming the place and that platform/pole, and holds
**one row per route** inside it: the line pill on the left, the destination, and the
service's **next few countdowns merged onto one line** on the right ("0 · 3 · 6 min", the
"min" unit written once). A line that runs several routes (a branching direction) shows
its **pill on each route row**, so every destination reads as its own service rather than a
chip-less continuation. The **stop name is not repeated on every row**: it read as clutter
restated per row. Instead each group's **header names the place and its platform/pole once**, on
one line — "King's Cross St. Pancras – Platform 1", "Victoria – Stop G", or "Turnpike Lane ➔ King's Cross" for the way a letterless pole's sign points (the arrow joins them, so no dash) — with the
near-me distance dimmed after it ("(120 m)"); place and platform share one weight and color,
only the distance is muted. A *place* is the set of stops that share a
**cluster** — a bus junction's two poles, a station's several platforms — grouped together so
they read as one boarding location, the way a Tube station (a single stop id aggregating its
platforms) already did; grouping by stop id instead split a junction's northbound and southbound
poles into two identical headers (settled 2026-09-21). The cluster key is TfL's **`stationNaptan`**
where the nearby lookup gives one, else the cleaned display name. Keying on TfL's own cluster is
what keeps a station it spells several ways together (King's Cross St. Pancras has several forms,
so the name alone is an unreliable key) while holding genuinely distinct adjacent stations apart
where TfL gives them different clusters — the maintainer's worked example is keeping King's Cross
St. Pancras separate from St Pancras International (2026-09-21). Within a place, each group's
header carries a **qualifier segment** naming the **platform or pole** its card boards from — the
cue that tells two groups of one place apart — so a busy interchange reads as one card per platform
rather than a wall of cards under one bare name (settled 2026-09-22). A **rail** place splits on its
**platform**: "**Platform 2**". The platform number is parsed from TfL's `platformName` ("Eastbound
- Platform 2"); the platform is the grouping key, **not** TfL's `inbound`/`outbound` and **not** the
compass. Not inbound/outbound because at an interchange TfL tags one platform inconsistently across
lines (King's Cross runs the Circle "Eastbound" as `inbound` but the Hammersmith & City the same
platform as `outbound`, and omits some westbound trains' direction entirely). Not the compass because
it conflates physically distinct platforms — King's Cross Eastbound is the Circle/H&C/Metropolitan on
one sub-surface platform but the Piccadilly on a different deep-tube platform — so keying on the
platform number keeps a header naming one physical platform. The compass is **not shown** on the
one-line header (the destinations carry the direction a rider reads); it stays in the domain as the
direction cue and disambiguates a rail direction with no platform number, which falls back to the
**bare compass** ("Northbound"). A line whose one direction leaves from **several platforms** — Camden
Town's southbound Northern line runs from Platform 2 or 4 depending on the northern branch it came
from, a terminus alternates platforms — splits into a card per platform, since which platform the
next train is at is the point; a prediction in such a direction with no platform number can't be
placed, so it sits under the bare compass rather than a platform it may not be at. The widget
groups its rows under the **same place headers**, one per place, but keeps one merged row per
direction: its line budget is tight, so a direction split across platforms is one row whose header
drops to the bare compass (or place) rather than name a platform only some of its trains use. A
header costs a line of that budget, and a place is shown only with at least one departure under it,
and a header is judged against every departure the widget has, not only those that fit: a place
keeps its name when the others didn't fit;
a widget too short for a header and a departure drops headers rather than show no departures. A **bus** pole carries no platform in the arrivals feed, so it
splits on its **stop letter** — the "D" a rider reads on the physical stop: "**Stop D**". The letter,
bearing, and "towards" come from the near-me `/StopPoint` lookup (`stopLetter`, `CompassPoint`,
`Towards`), not the arrivals feed, so a **watched** bus stop (no near-me lookup yet) has none until
that capture lands. TfL is inconsistent about where it carries the compass — some poles use
`CompassPoint`, others put an arrow in `stopLetter` (`->N`) in place of a real letter — so an
arrow-in-`stopLetter` is normalized to the bearing, and a compass-only pole renders the one way
whichever field TfL used. With no letter, the pole falls back to the **"towards"** its sign shows,
as an arrow plus the first place it names ("**➔ Farringdon**" for "Farringdon Or Holborn Circus"), even when
several routes serve the pole, since the sign shows it all the same; then to its **compass bearing** — a
bare direction word ("**Southbound**"), like the rail compass; with none of the three, the **bare place
name**. A pole is never headed by one bus's destination, which says where that bus goes, not where the
stop heads (maintainer, 2026-10-03). "Stop" is reserved for a literal pole letter; the direction word
and the "➔ towards" carry no "Stop". Precedence: letter → towards → bearing → bare. The trip's board
heads the boarding pole the same way. The header is **one line** — "Place – Qualifier
(distance)", title case, no small caps — where the place name and the qualifier **share the row**
(each weighted, each keeps at least its half and clips within it) so neither a long name nor a long
qualifier can crowd the other to zero, and the short near-me distance is reserved after them. A
two-way service at a stop is two cards, one per direction; a one-directional case (a terminus
platform, a
one-way-street stop, a single branch) is one. Nothing is hidden behind a gesture, which
is what a glance surface needs (**D8**). TfL's `direction` is the primary key and the
domain retains it — it can't be *reconstructed* from destination or platform in general
(a branch shares a direction; a terminus doesn't imply one). TfL omits `direction` on
some services, though — sometimes only some of one service's trains — so a direction-less
prediction first takes the direction the **same service's** other trains (same line, platform
and destination) agree on, so one service stays one row; a platform can run a line both ways,
so the platform alone is no evidence. Failing that the grouping falls back to the platform, then
the destination, as a best-effort discriminator (the resolved *direction key*) rather
than merging opposite directions; a prediction with none of the three is genuinely
indistinguishable and shares one "unknown" row. TfL names a train's platform only for about
the next half hour, printing "Platform Unknown" after that; a service (line, direction, destination
and via-branch) that already has three upcoming trains, all on one named platform, folds its later
"Platform Unknown" ones into that platform's row, past the three times it shows, rather than
repeat itself under a "Platform Unknown" header (the route detail still lists them). A sooner
unknown train, or one of a service with fewer named trains, keeps its own group, so no catchable
departure is hidden or put under a platform nobody named. When a direction *does* branch
(same line, same direction, different destinations), the headline names the soonest
departure's destination and merges only that destination's times; each **divergent
destination keeps its own line and its own merged countdown**, so a countdown is never
shown under the wrong one.

**On each route row**, the **inbound/outbound direction word** is not used ("inbound" /
"outbound" is TfL jargon): the destination *is* the direction signal a rider reads, so the
row carries the destination and drops it — the countdown, the one thing that must always
stay legible, keeps the room. The platform is named once by the card's header, not repeated
on every row. A **disrupted route** shows an **inline warning glyph** (⚠) just left of its
countdown rather than a separate status-chip row, so the route stays one line; the glyph
announces TfL's status wording to a screen reader and the full text is reachable in the
detail view. The destination elides to a
single line, so a long one ("Harrow & Wealdstone") truncates rather than wrapping the card
taller or pushing the countdown off the edge. The destination's **station-type suffix is
trimmed** the way stop names are — TfL's "Brixton Underground Station" shows as "Brixton"
(*Concise copy*); "Bus Station" and "Coach Station" go whole the same way ("Canada Water Bus
Station" → "Canada Water", the bus pill saying it's a bus, maintainer 2026-09-27); the bare
" Station" is dropped too, so a terminus like "Battersea Power
Station" reads "Battersea Power". A trailing **line-name parenthetical** is kept but
shortened to its lines — "Hammersmith (H&C Line)" shows as "Hammersmith (H&C)", "Edgware Road
(Circle Line)" as "Edgware Road (Circle)" (maintainer, 2026-10-03). TfL adds that bracket only to
tell apart two stations of one name a street apart, so it is shown wherever such a station is
named — a headline, a card header, the route page, a trip's steps, the watch, a train's
destination — and only the word "Line" goes. A *geographic* parenthetical with no line
("Stratford (London)") is kept whole, as is TfL's own "Edgware Road (Bakerloo)". Since TfL names
one station with the bracket in some sources and without it in others, everything that **pairs**
names across sources — a terminating service, an alert's stops, a route's stops, a walk within
one place, a search result folded into its neighbor — compares them without a line qualifier, so
"Hammersmith (H&C)" still matches a plain "Hammersmith". A qualifier on both sides still has to
name a line in common: "Hammersmith (H&C)" never pairs with "Hammersmith (Dist&Picc)", which is
the other station (maintainer, 2026-10-03). A walk between those two still counts as within one
place, a street apart. National Rail's
board marks a station sharing its name with a tube station "(Rail Station Only)", and that goes
too, as the station-type suffix it is — "Heathrow Terminal 5 (Rail Station Only)" shows as
"Heathrow Terminal 5", the name TfL's route gives the stop, so the train can be followed on it. A short list of **hardcoded display renames** shortens a
terminus further where the trimmed name is still longer than a rider needs — "Battersea Power"
shows as "Battersea" — applied at label time only, so grouping and branch resolution still key
on the full terminus and a rename never changes which trains share a row. **The
one exception is a destination-less
service**: when TfL gives neither a destination nor a "towards", the direction word — or,
failing that, the platform — is shown *in the destination's place* as the only cue that
keeps two directions of the same line distinct (never mislabel a countdown). That fallback
is the reason the platform and direction stay in the model; it is a safeguard, not a
reversal of dropping them when a real destination exists.

On a **branching line** (the Northern most visibly) two trains to the same terminus can
run via different central trunks, and TfL names the trunk in `towards` ("Battersea Power
Station via Charing Cross") — the cue a rider uses to pick their train. So when `towards`
carries a "via", the branch is shown **joined after the destination with a slash**
("Battersea/Charing X", the destination display-renamed from "Battersea Power Station" as
above). **A slash is always shown unspaced** (maintainer, 2026-10-03): TfL lists a stop with its
cross street as "Aldwych / Somerset House", and the name is shown as "Aldwych/Somerset House", so a
tight row fits more of each place. The branch **participates in grouping**: within a direction, a
line is split not just per destination (D8) but per terminus-and-branch, so two trains to
one terminus via different trunks (Edgware via Bank and via Charing Cross) each get their
own line and their own merged countdown. Merging across branches would label the later
train's countdown with the first train's branch, defeating the disambiguation — the
countdown must never sit under the wrong branch any more than under the wrong
destination.

TfL spells the same trunk several ways in its feed — the Northern line's two central
trunks arrive as "Bank", "Bank Branch", and "CX", plus the full "Charing Cross" — so the
label is normalized to one short board form per trunk ("Bank", "Charing X"), the spelling
a rider reads the same on every row.

The branch is shown only where it names a **choice the rider makes here**. Two trains to
one terminus by different trunks are the *same service* only once the trunks have
physically joined — past the junction, on the single shared track — "from the perspective
of someone traveling away from Bank and Charing Cross the origin doesn't matter". There the
rows **merge into one line and the branch label is dropped** (one "High Barnet" from
Highgate, not a "High Barnet/Bank" and a "High Barnet/Charing X"). The branch stays,
each row labeled, wherever the trunks are still distinct — including **at the junction and
trunk stops themselves** (Camden Town, Euston, Kennington): a Bank train and a Charing Cross
train reach Camden by different approaches (via Euston vs via Mornington Crescent) and leave
from different platforms, so the rider still picks one there even though both go to High
Barnet the same way onward. It stays too where a trunk-only stop lies ahead — High Barnet →
Morden picks which central stations you pass, Euston → High Barnet picks whether the train
calls at Mornington Crescent. The test is an **approach-inclusive path comparison** over
TfL's Route/Sequence data: from the stop *one before* this one through to the terminus, equal
sets of stops on both trunks ⇒ merge and drop the label; different ⇒ keep both. Including the
approach stop is what keeps the branch at the junction (the trunks reach it by different
approaches) while still merging once past it (the approach is then shared).

Resolution requires an **exact branch match** — the arrival's branch must name a route
pattern that actually serves this leg. Anything the asset doesn't model that way keeps TfL's
raw label and merges nothing: an unknown line, a stop or terminus off every pattern, or a
branch no serving pattern carries. That last case is Battersea Power Station — TfL tags its
trains "via Charing Cross" but its route pattern carries no "via", so the branch matches no
serving pattern and the train keeps "/Charing X", the trunk it runs (redundant but not
wrong; the maintainer prefers keeping it over dropping it). That data is a **bundled static
asset** (regenerated from TfL, no runtime cost on any path), **refreshed from TfL in the
background**: once the app starts, each bundled line's current Route/Sequence (through the route
pages' own day-long cache, so at most a daily fetch) replaces the bundled patterns, but only where
it still runs every route the bundled data has (on the same branch, calling at all its stops in
order) — a new station or an extension, even one past a terminus, is taken, while an answer missing
or cutting back a route keeps the bundled line, since a missing route could make a branching leg
look single-path. The refresh adds no line the bundled data doesn't model. The lines it took are
kept on the device (the cache directory, public route data only), so the widget's process and the
next start group the same way without asking TfL, checked against the bundled data again in case a
newer build ships different routes; a line a refresh can't read keeps what the last one took. The
phone sends the watch the lines that differ from its bundled data (usually none), and the watch puts
them over its own the same way, so it groups as the widget does. Until
a refresh has taken a line, or wherever it can't be had, the bundled data stands. Incomplete or
stale data degrades to "show what TfL said", never to a confident wrong merge.

The one exception to that raw fallback is a **single-branch stop** — one every serving pattern
reaches on the same trunk. There the branch names which trunk the train came up behind this
stop, never a choice, so the label is dropped even for a short-working the asset doesn't model
as a terminus. King's Cross is Bank-only, so a Bank-branch train there terminating at Golders
Green or Finchley Central shows no "/Bank".

Where the pair won't fit, the **branch is kept whole** — it is the cue that tells the two trunks
apart, so truncating it would lose which train this is — and the **terminus yields**: it shortens
common whole words to a compact form (`East`→`E.`, `Street`→`St`, `Lane`→`Ln`, `Market`→`Mkt`, `Station`→`Stn`, and the rest —
`DestinationAbbreviations`; each part of a slash-separated name alike, so "Shepherd's Bush
Market/Wood Lane" keeps both places, and below the floor each place elides on its own rather than all but
the first behind one trailing `…`), then, for a name no word maps, drops to its **floor** — the first word
in full with each later word an initial (`Battersea Power`→`Battersea P.`) — and only below the
floor does it **elide with a single `…`** — never a mid-glyph cut, and the **terminus yields before
the branch**. **Both sides are abbreviated before either is cut** (maintainer, 2026-09-25): the
full branch is kept while the terminus, in full or abbreviated, fits beside it; otherwise the branch
takes its abbreviated form too ("Charing X", "Newbury Pk"), and only then does the terminus drop to
its floor or elide. A branch abbreviates with the terminus's own word forms (`South`→`S.`,
`Road`→`Rd`, `Park`→`Pk`, …) plus the board's `Cross`→`X`. So a tight row reads "Battersea P.
/Charing X"; at the largest font scales, where the whole branch fills the row and not even the
terminus's first glyph fits beside it, the branch stands **alone and bare** — its abbreviated form
with no leading slash, so no orphaned "/". Only an
unusually long branch standing alone in that narrowest row can itself reach the single-`…` last resort — there is nothing left to yield, and a
clean elision beats a mid-glyph cut. The full name shows whenever it fits and stays the accessible
label throughout. This supersedes an earlier proportional-split rule
that clipped both halves mid-glyph and a no-ellipsis preference alongside it (maintainer,
2026-09-22): a clean word/initial boundary, then a single `…`, reads better than a hard cut. The
`…` carries no surrounding spaces. A fuller rider-readable branch form under pressure ("Charing
X"→"via Charing Cross") remains a tracked refinement (`TODO.md`).

**Text is chosen to fit its space** (maintainer, 2026-09-26). A word or label is picked in a form that
fits where it goes, not written long and cut to fit: a status in a times slot is a short word that
fits the slot ("Loading", "Tap to see", "Closed", "–"), never a phrase trailed with "…"; a name too
long for its space takes its shorter forms (the abbreviations, then the floor, above) before
anything is elided.

The row set is not purely prediction-derived: a watched stop or line with a **known
disruption** but **zero predictions** still contributes a row — a status row (for the
stop, or for that service at the stop) carrying the disruption and no countdown,
direction-independent since no prediction supplies a direction. So a suspended line or a
closed stop is surfaced, not silently dropped for want of a departure to build a row from
(see *Disruptions*) — the quietly-wrong failure the whole model exists to avoid.
Where its countdown would be, the status row says what is known (maintainer, 2026-09-24): a
**dash** only when StopDash is sure no train is coming, **"?"** (heard as "Times unknown") when one
might be but its source hasn't said when, **"No data"** when no source answered for it (a National
Rail line whose board failed, or that no board covers), and **"No key"** for a National Rail line at a
station a key would cover when none is set — a tap on it opens Settings.

*Sure* means (maintainer, 2026-10-03): a National Rail board that answered with no trains, since it
lists every train it runs; a TfL line whose status says it isn't running here (closed, suspended, or
a part closure with this stop inside it); or a TfL line whose **timetable** has nothing leaving within
**30 minutes**, about as far ahead as TfL's live list reaches. TfL's live list can come back empty
while trains run — during a disruption it can lose track of a whole branch — and a dash there read
as "no trains" when they were only unseen; at night the same empty list is honestly "none", and the
timetable is what tells the two apart. **The timetable decides only which mark: its times are never
shown in place of live ones**, since it knows nothing of the disruption that emptied the board
(maintainer, 2026-10-03). It is fetched only for a line with no live times, kept for the service
day. While it is still being fetched, or a failed one is being asked for again, the row shows a small **spinner** (heard as "Loading times"),
not "?": a "?" during a page load read as an answer when it was only a wait (maintainer,
2026-10-03). A timetable that failed, or can't say, is "?" — StopDash can't be sure. A place card whose
stops came back with nothing (*Freshness → Cold load*, *Farther stations*) still shows a dash for
now; giving it the same marks is planned. On a bank holiday, and from Christmas Eve to 3 January, the timetable gives no answer ("?"):
TfL runs another day type's timetable on some ("Saturday (also Good Friday)"), and its weekday's
could say "none" while trains run.

A line on **good service** (as TfL confirmed, not merely unchecked) with no live times gets a row too, in the near-me list (maintainer,
2026-10-03): TfL's live feed for a whole line can go quiet while its status still reads Good Service,
and the line then vanished from a station with nothing to say why. Its row reads **"?"**, and appears
only when the line's timetable has a train due within the 30 minutes; a line whose timetable has none
due (an infrequent service, a night bus by day) adds no row. When the timetable can't say, a tube,
Elizabeth line, Overground, DLR or tram line still reads "?", since half an hour without a train is a
fault there; a bus doesn't appear. National Rail lines never get one: their board lists every train
they run, so an empty one is an answer, and a hub's infrequent intercity services would otherwise fill
it with rows. A line with trains at another nearby stop gets none either, its trains being the better
answer. A stop with a notice in force (a closure, a moved stop) gets none: its notice is already
there, and the row is for a gap nothing explains. Nor does a stop whose notices couldn't be checked,
which may be closed for all StopDash knows. A line gets one such row, at the nearest stop whose timetable has a train due, so a branch the
nearest stop doesn't see still shows from the stop that does. The glance surfaces (widget, watch) have no timetable to decide with, and show none.

The list shows the **watched stops'** rows (D1) — stopdash renders the stops the user
chose ahead of time, not "nearest to me" — ordered **location-free** so the view works
with location denied: soonest-first, with **starred** rows pinned to the top. Starring is
ranking only, separate from which stops are watched (add/remove membership). A star keys on
the row's `(stop, service, resolved direction key)` identity, so it restores to exactly one
row and survives restart. Starring is toggled by a **long-press on a route row**, and a starred
route is marked by a **gold leading-edge bar** on its row (no in-row element, so it costs no width)
plus its position at the top; the earlier per-row star button was removed because it consumed width
on every card. (A card now holds several route rows — one per route — so the mark is a per-row bar
rather than the whole-card border the one-service card used.) A **tap on a route row opens a
full-screen route detail page** for **the route tapped** — a card shows one row per destination,
and the page follows that row's trains (its destination, and its branch where the card splits the
destination by branch), not whichever of the line's trains is soonest
(once the tapped route has no trains left it falls back to the soonest, and the title follows, so the
title and stop list always name the same train) — its own app bar naming the route (line pill +
destination, with the branch where the card showed one, "Hainault/Newbury Park") and
carrying the star (so the long-press is the shortcut, the page the discoverable path), then, heading
the body, the service's **full name** — "Victoria line", "London Northwestern Railway" — so a short
pill code is never a puzzle (left off where the pill already is the name: a bus number, DLR), then
**every upcoming departure on that route** — not the card's first three — on one full-width
line of countdowns in the card's format (times only; any that don't fit ellipsize off the end, and the
line is withheld while stale, D4), and the line's **full disruption text**, which the row's inline **⚠** glyph stands
in for: shown collapsed to its first line, tapped to expand (the same widget the stop-closure card
uses). A **web link** in an alert — with or without `https://`, as TfL writes them ("visit
tfl.gov.uk/status-updates") — is underlined and opens in the browser on tap; only http/https links
are made, an email address isn't treated as one, and with no browser the tap says so. The same holds
for every disruption text the app shows in full (maintainer, 2026-10-07: National Rail's reason is often
its page alone): a line's page off the disruptions row or a trip, and the On the way screen's disruption
and station notices. Below that it lists **every station from the boarding stop to where the soonest train
terminates** (a short-working ends where it does, not at the line's end), on a rail in the line's
color. **Where the line's sequence is in hand it is drawn as the line page's map** (maintainer,
2026-10-06; *Line page*): that stretch held open whatever is placed on it, the rest of the line folded to
the ends it leads to, a closure's ⛔ and the work to come's calendar marked as on the line page. It is
drawn from the route data the page already holds for the train's way, never a second request for the
line's other way. Its folds open one at a time, the last folding again as the next opens, with no "Show all stations". The list below stays wherever that map can't be drawn (a route still loading or
failed, a line the map can't lay out): the train's stops in hand are never replaced by a note. **A tap on a station opens its own page** (maintainer, 2026-10-06), by its stop area where TfL
gives one, so a bus stop opens as the whole place; Back returns to the route page (from a station's
own page, the opened one takes its place). A trip's route pages leave their stations inert for now. The boarding stop is marked with a blue "you are here" dot (map-location blue, ringed to
stand off a blue line's rail), announced to a screen reader as "Your stop"; every other stop, the terminus included, is hollow,
so the one filled dot is the rider's (the terminus keeps a bold name). **A station step-free for the line
carries TfL's own symbol after its name** (maintainer, 2026-10-02), from the bundled table (*Data
source → Step-free access*): a white wheelchair on a blue disc where the rider can get from the street
onto the train without a step, a blue wheelchair on a white disc where they can reach the platform but
need a step or the staff's ramp onto the train, as TfL's map draws them. Nothing marks a station with
no step-free route, or one TfL's data doesn't describe. A station whose platforms for the line differ
(Cannon Street on the District) is marked only as far as all of them reach, since the rider may need
either. **A mark comes off while a lift its route needs is out** (maintainer, 2026-09-26: "a
step-free-access outage notice should then read against it"): where a station listed is step-free
only by a lift, the page asks for TfL's live lift outages once its list is up, and again when that
answer is 5 minutes old while the page is in the foreground; a platform the street no longer reaches
without the lifts that are out loses its mark until they're back. A station another lift still serves
keeps it. Until TfL first answers, the marks are the bundled table's; after that, TfL's last answer
stands until it gives another, through a refresh and through one that fails, and a page opened later
draws its first frame from it: it only ever takes marks off, so holding it errs toward "not
step-free" rather than putting marks back on lifts TfL never said were working. A screen reader reads the symbol as its words ("Step-free to the train"). The list is headed only by the **direction** the train runs ("Southbound") — read off its
rail platform, or a bus pole's compass bearing, and left off when neither names one — not by "From" or
"Stops to": the list opens on the boarding stop and the app bar already names the destination. Only
while no list is shown (a status row, or a list loading, failed, unavailable, or withheld) does the
body name the boarding stop ("From Victoria"), so the page always says which stop it is about. The list comes from TfL's Route/Sequence for the line, fetched **when the page opens** — never
on the refresh path — and kept for a day, in memory and in the app's cache directory, so a reopened route needs no fetch, even after the process was killed. The list is matched off the main thread; a train's page reopened in the same process shows the list it last showed in its first frame while the current one is matched, and a list ready within a few frames appears without a flash of "Loading"; until
it arrives the page says so, a failed fetch says why with a retry, and where TfL's data admits more
than one path from here (a Northern train with no branch named) the page says the list is
unavailable rather than guessing a trunk (principle 1). **A bus runs to its route's end** when its
destination names neither a stop ahead nor the route: a bus blind shows an area or landmark, not
its last stop's name, so matching by name alone left most buses with no list. A
bus short-working whose label *does* name a stop ahead still ends there, and two variants that part
ways ahead are still unavailable. Rail keeps the strict name match — its destinations are stations,
so a miss there is a working the sequence doesn't model. **A terminus named another way is found by
its id**: a National Rail board spells some stations its own way ("St Albans" for TfL's "St Albans
City"), so where no stop ahead has the destination's name, the stop with its terminus's id ends the
list — TfL's id, or the board's station code read as TfL's, where the code is one station — never a
looser name. **A loop goes the way its platform faces**
(maintainer, 2026-09-26): a Circle line train "to Edgware Road" can reach it either way round, so
where more than one path matches, the platform's compass ("Eastbound", or a loop's "Inner Rail" /
"Outer Rail", the outer running clockwise) keeps the path that leaves that way. TfL's direction
can't decide it — on a loop both trips pass the same platform. Where the platform names no compass
("Platform 1"), the train's direction does instead, keeping the routes TfL runs that way. **A
National Rail train goes the way its board's "via" names**: its board gives no direction and its
platforms no compass, so a train round a loop to one terminus ("via Wimbledon") keeps the paths
calling at every station the via names after boarding. The via narrows the path only — it isn't
shown — and a via no path calls at leaves the list unavailable rather than guessed. **A train TfL shows as "Check Front
of Train"** keeps TfL's wording on the list, as the platform board has it, but names no place: it
has no stop list, and a trip or journey counts it only where every way it may run from its platform
agrees (all pass the rider's stop, at least as far as where they part, or none does); otherwise it's
one of the routes that couldn't be checked. **A service ending at the stop it's listed at** (a bus
arriving at its stand, a train turned short there during engineering works) has no stop list, and a
trip or journey counts it as not going there, never as a route that couldn't be checked: TfL lists
it among the stop's arrivals, but it takes no one anywhere from there. It's matched as the
near-me list matches a terminus — by TfL's destination id against the stop and its stop area, by
name only when TfL gives no id. **A station TfL lists under one id and routes
under another** (St Pancras's Thameslink departures, whose route calls at the station's low-level
platforms) boards at the same-named station in the same interchange — never a different station
in it, like King's Cross beside St Pancras. A journey starred there keeps the id the departures
carry, and its card places it the same way. Which interchange a stop is in comes from the bundled
station index, so this holds however the stop reached the screen — nearby, watched, or a journey's
end. Whenever the list is unavailable, the
debug log records why (principle 2). The list follows one predicted train, so
it is **withheld while the row is stale** — that prediction may no longer be the next train — and
returns with the next refresh (D4). Each station carries a **line pill per connection** — the
other tube, Overground, DLR, Elizabeth line, tram and National Rail lines at the station or its
interchange, from the same response. Buses are left out, since nearly every station has several and
they would swamp the list. An interchange names its lines without saying which run trains, so there
a National Rail operator counts by its TfL line id, from the known list the app already maps
operators by; one not on it is left out rather than guessed at. It is a full screen rather than a dialog
because it will grow per-route actions (a maps/nav hand-off), which a dialog would cap. **On the watched list, warning rows still lead**, above even a starred service — a stop
closure or a no-prediction line-status row is something the user must see, and pinning a
starred service above it would push a warning down the list (principle 2). **On the near-me
list an alert gets no special order** — it rides with its stop at its stop's distance (trailing
that stop's departures) rather than being lifted, above starred or otherwise (*Finding stops*); a
stop notice rides with its place there too (*Disruptions*). Distance ranking belongs to *finding* stops
(near-me discovery, *Finding stops*), not to ordering the watched list.

**The per-platform card model shipped** — one card per platform/pole, a chip-tagged row per
route (per *Departures* above); the compass is not shown on the header, since the destinations
carry the direction a rider reads. A more compact **(service, stop) card that swipes between
directions** remains a candidate to iterate toward once the shipped list has been used on a
device — it would collapse a two-way service to one card but hides the other direction behind a
gesture the widget host owns, so it is a later call, not a prerequisite (**D8**). Each **route
row's** direction headline is the resolved terminus the domain carries (`destinationName`, else
`towards` before its " via "), plus the via-branch alongside it where TfL gives one (see the
branching-line paragraph above); a branching direction keeps **one row per terminus and branch**
(each with its own countdown and its own line pill). One refinement is recorded to explore (`TODO.md` Phase 2): labeling a direction
by the **next branch or interchange point** downstream rather than the terminus, feeding
letting users set **favorite destinations** to filter or rank by.

Tapping a **platform/pole header** drills into that group alone (maintainer, 2026-09-23): the same
screen, filtered to the header's stops, with a back arrow in place of the app's mark (system back
returns too) and the header's text as the title. The title is **elided from the start**
("…ouse – Platform 2"): it shares the bar with the freshness stamp, and the platform is the part
that tells one view from another. The view is built from those stops' own departures, **not** the
near-me fold: a line the full list showed only at a nearer stop still appears on the farther
platform that also serves it. It reads the same snapshot as the list and fetches nothing of its
own. When a refresh no longer holds that platform — its stops weren't fetched, its last train has
left, or the feed dropped the platform number — the view closes back (to the full list, or to its
station when opened from one) rather than
show an empty platform as "no departures". A route tapped inside it opens the route page, and back
from there returns to the platform view.

Tapping the **place name** within a header opens the same view for the **whole station** — every
platform/pole group of that place — titled by the bare place name (maintainer, 2026-09-23, an
experiment: the name is a narrow tap target and nothing marks it as one, so its discoverability is
on trial). The rest of the header row still opens the one platform, and a screen reader offers the
name as its own "whole station" button. Inside the whole-station view a platform header narrows
further to **that platform**, and back steps out one level at a time — platform, then station, then
the full list (maintainer, 2026-09-23). A refresh that drops a platform opened this way closes it to
its station, and on to the full list if the station is gone too. Tapping the **distance** shows that
group's nearest stop in the user's maps app — a pin at TfL's published stop position, labeled with
the place name, never the rider's own fix (maintainer, 2026-09-23; like the name, an undecorated tap
target). With no maps app installed it says so rather than doing nothing.

TfL's endpoint is named "Arrivals"; for a bus stop these are departures *from* that
stop, which is what a rider wants. StopDash calls them departures throughout the UI.

Each service wears its line's identity: a pill filled with the line's official TfL color
carries the line's **three-letter code** (its first three letters, uppercased — VIC, BAK,
ELI; a bus keeps its route number), so the pill stays narrow and the row keeps its width
for the countdown, while the list still scans by line the way the network map does. **National
Rail is the exception** to first-three-letters — it collides ("Southern" and "Southeastern"
both → SOU) — so a rail operator shows its initials (the capitals in a multi-word name: East
Midlands Railway → EMR, Greater Anglia → GA), which is also the initialism a rider sees on the
train and beats the cryptic legacy TOC codes; the few single-word operators that would still
collide are pinned by hand to their official TOC code (Southern SN, Southeastern SE), and
West Midlands Trains' line reads LNR, the short name of the brand that runs all its London trains
(the rail feed calls it "LNR & WMR", whose capitals overflow the pill). A code pinned to a line
holds by its line id as well as its name, since a rail board's line id comes from the operator's
code, which the feed sends reliably, while names differ between the feed and TfL. **Every
pill shares one fixed width**, sized to the widest code shown (a four-character bus route),
so the codes form a tidy left column the eye runs straight down a card list, rather than a
ragged edge that steps in and out as each code's length changes; a shorter code centers in
the shared box, and the width holds the widest code complete rather than truncating it. A pill cut
for several lines is the exception: its parts size to their own codes (*Trips with a change*),
since one fixed width per part would make it half again as wide as the pills beside it. The
full line name is the pill's accessible label, so a screen reader announces "Victoria",
not "VIC". Tube
lines take their color by line (Northern black, Central red, …); bus, DLR, the Elizabeth
line and trams take one color per mode; the six named Overground lines take their color by
line too, rendered as a hollow pill (below). The label's black-or-white color is
chosen by **APCA**, the perceptual contrast model headed into WCAG 3, rather than the
WCAG-2 luminance ratio: WCAG-2 is luminance-only and misreads white on saturated
mid-tones (Victoria, DLR, Bakerloo), rating black higher where white is plainly more
readable, so a pill can sit below the WCAG-2 AA number and still be the right, readable
choice. The color is decorative — the identity is always text (the code, plus the full name as the
accessible label), never color-only.
The six named Overground lines (TfL's 2024 renaming) each take their own line color, but as
a **hollow pill** — the card surface shows through, the line color is the border and the
label — rather than a solid fill. Two reasons: several of the Overground colors sit close to
a tube line's (Windrush red ≈ Central, Mildmay ≈ a tube blue), so a solid pill would read as
that tube line; and TfL itself draws the Overground as hollow/parallel lines — so the hollow
shape says "Overground, not tube" even where the color collides. The accent is nudged to
stay legible on the surface (darker on the light card, brighter on the dark one). The widget and
the watch draw it the same way. An
Overground service whose id isn't one of the six (legacy `london-overground`) falls back to
the single mode orange. National Rail resolves by **operator** rather than mode — every rail
service shares the one `national-rail` mode, so its operator is its identity — and each operator
wears its own **brand** color (c2c magenta, Southern green, EMR aubergine), a solid pill like the
tube, the way Google Maps shows them. These are operator brand hexes, not TfL's palette — the one
authorized departure from "a confirmed TfL hex only" — so each is a confirmed brand value (the
color Wikipedia's UK-railways templates carry, or the operator's own site where that value is a
route-diagram color rather than the brand, as for Great Northern's purple). Where a template
varies by route (Northern, ScotRail), the operator takes its default, not one route's. An operator
matches by its name or, failing that, by its code: the rail feed spells some names its own way
("LNER", "LNR & WMR"), so the line id the operator's code maps to catches a spelling nobody listed
(maintainer, 2026-09-30). One exception, by the maintainer's choice: West Midlands Trains runs
London Northwestern Railway (LNR, green) and West Midlands Railway (WMR, orange) as two brands under
one operator code and one TfL line, and both wear the colors from Wikipedia's *West Midlands Trains*
route-map legend rather than their templates' (LNR's picked from the two rendered side by side,
2026-09-24; WMR's to match, 2026-09-30). The rail feed doesn't tell the brands apart, so the line
wears LNR's green under the feed's name, TfL's, and its code — every train it runs from London is
LNR (2026-09-30) — and only a service named for West Midlands Railway alone wears the orange. The
line is also *named* for that brand, "London Northwestern Railway" — the brand its London trains
carry — in place of TfL's parent-company "West Midlands Trains" and the feed's "LNR & WMR" for both
brands (maintainer, 2026-09-30). The rename happens where names come into the app — from TfL, from the rail feed, and from a
saved copy read back, a hidden line's banner label included — not where each screen shows one (the maintainer's call, 2026-09-30), so a
trip's directions and warnings, a spoken label and a route page's title agree with the pill, which
keeps its short code, LNR. The
operator code
(EMR, AWC, c2c) already reads distinct from any tube code, so the rare color collision with a tube
line (a rail red near Central) can't be mistaken for it — the identity is text, not color. An
operator without a confirmed brand hex still falls back to a neutral pill rather than an invented
shade — a cosmetic gap, not a correctness failure.

### Journeys

A rider can **favorite a journey** — a segment between two stops, rail or bus (maintainer, 2026-09-23;
called *starred* until 2026-10-06, when the maintainer renamed them *favorite journeys*, en-GB
*favourite*): on a route page, a long press on a stop on the stop list (after the boarding stop) saves
the segment from the boarding stop to it — both directions — and marks the stop with a star; another
long press removes it. **A tap on the stop opens its page** (*Route detail*), headed by the same
journey ("Victoria ➔ Warren Street") with **Favorite**, or **Remove favorite** once saved (maintainer,
2026-10-06): the discoverable path, the long press the shortcut. It waits on the saved journeys
being read rather than guess which way a tap goes, says a change it couldn't save, and a removal
drops the widget's pins as Settings' Remove does. The boarding stop's page offers no journey. A long press, not a tap (maintainer, 2026-10-05): a stray tap while scrolling the stops
favorite journeys the rider never meant to, which then pinned to the widget.
A journey is a **segment, not a line**: the same two stops starred from another line's page (the 43
or the 134 between two shared stops) are the same journey. Favorite journeys lead the near-me list as
cards, each headed by the direction shown ("Highgate ➔ King's Cross St. Pancras ★", the gold star
marking a favorite journey so its heading reads apart from a bus place's "Place ➔ Destination" header,
maintainer 2026-09-24) with the trains or
buses **on any line** from the origin that **call at the far end** (from each line's route; one whose
path can't be resolved is left out, not guessed). The origin is whichever end is **nearer the rider's
fix**, from TfL's published stop positions; the ⇄ button at the end of the heading shows the other
direction in place, for planning the way back (maintainer, 2026-09-24). A tap on the heading opens
the **journey's own view**: the journey in full, **Swap direction** and **Remove favorite** buttons, and
its trains with each group headed by where it boards (the platform or pole), rendered from the same
snapshot as the list; unstarring closes it.

A journey more than **a mile from both ends** of the rider's fix is **held back** (maintainer,
2026-09-24): the foot of the list, below the farther cards, has a **Faraway favorites** button, and
until it's tapped those
journeys aren't fetched — sparing the request budget and battery for trains the rider can't be
catching. A tap shows them in full at the foot of the list, each heading carrying its distance,
until the rider moves to a new set of nearby stops. The widget never pins a far journey, tapped or
not: it refreshes unattended, so it keeps to trains the rider can catch. Without a confirmed fix
(none, an approximate last-known one, or one that couldn't be refreshed), or an end's position,
every journey shows in full rather than be hidden on a guess.

A bus's way back leaves from the pole across the road, so each direction is **placed on the starred
line's route**: an end matches its own stop, else a stop in the same TfL stop area, else one of the
same name, else — for a stop served one way only — the route's nearest stop within a short walk
(400 m). When the route can't place the origin as one stop, the card says it couldn't check rather
than guess one. Another line counts as reaching the far end when it stops at the **same place**
there, even where TfL files that stop under another stop area and name (maintainer, 2026-09-24): a
stop whose name starts the same ("Hill Station", "Hill Station / High Road") within
150 m. Both are needed — a name alone would join same-named stops across town, a distance alone a
road that merely passes by — and it applies to the far end only. At the near end a bus journey
also boards from the **poles beside its origin** (maintainer, 2026-09-24): a pole in the same TfL
stop area whose line — one the origin itself doesn't serve — reaches the far end on its route (a bus
from stop K beside the starred bus's stop L). Its buses join the card under their pole's own heading,
and the widget pins them from that pole. It costs one lookup of the stop area's poles a day,
the routes of lines found only at those poles, and one more arrivals request per qualifying pole
per refresh.

**Every direct route, by any mode** (maintainer, 2026-10-08). A journey between two stations shows
the buses between them too, and a bus journey the trains: beside its origin the card boards from
every stop within 320 m (as far as *To…* from the near-me list starts), and a train or bus counts as
reaching the far end when it calls at **any stop within the rider's max walk** of it, at their pace —
the reach a trip's *Direct* section gives a place, so the two always agree on what's direct (the 134
a long walk from the far end counts, as it does there). Only lines that also serve a stop at the far
end are weighed, so a big station's dozens of buses cost a route lookup only for those that may go
there. The stops around each end are one TfL lookup by its published position, cached a day (no new
data leaves the device: public stops around public stations). To keep a hub-to-hub journey within
the request budget, a card fetches **at most its nearest 6 boarding stops** each refresh (one
arrivals request each, most often already fetched for the near-me list, and a closure check every
5 minutes); and it draws **its first 3 rows**, nearest stops first, then **+N more** — or **More**
when further stops board it — which opens the journey's own view, where every row and boarding stop
is shown and fetched. The widget pins from the rows the card judged, as many as fit. An end without
a published position keeps to its own stop and stop area, as before.

A pole is dropped only once it has been judged: while its lookup or a line's route is
loading or has failed, the card says so and the widget keeps what it had. The origin's departures are fetched alongside the near-me stops (one request, none
when it's already near) and stay out of the near-me list; each line's route is the same lookup the
route page makes (one per line at the origin, a day). Until they are in, the card says it's
checking rather than claim there are none.

**On the widget**, a journey's departures lead the list too (maintainer, 2026-09-23), in the
direction the app last showed. The widget can't load routes, so the app saves, with the widget's
snapshot, each origin and the departures it found to call at the far end (by line, destination and
branch); the widget pins those and shows nothing else from an origin that isn't nearby. A
destination the app hasn't seen yet is left out until the app next works the journey out; one a
complete check finds no longer calls there is dropped, while a check that can't finish (a route
loading or failed, a restart) keeps what was last saved. The saved pins are the one copy: each
check the screen reports is applied to them where they're stored, in one step, and the app's
ordinary saves leave them alone — so no screen, restart or relocation can write an older copy over
a newer one. An origin is kept (and counted by the widget's stamp and live refresh) only while a
pin starts from it. With live refresh on, each saved origin that isn't nearby
adds one arrivals request per refresh (free; well within TfL's keyless budget for a few journeys),
the same kind of request the app already makes for it — no new data leaves the device.

**Not shown twice.** A near-me row that a journey card above already shows in full — the same stop
and line, every one of its departures on the card — is left out of the list below (maintainer,
2026-09-24); a stop left with nothing loses its heading. A row the card shows only in part (some
trains don't reach the far end) stays, as do status-only rows, closures, and a stop's own platform
view.

**Alerts for the journey shown.** A journey card already carries its lines' status (a delay marks
the train, a suspension shows even with none predicted) and its boarding stops' closures; it also
shows a closure or move at its **far end**, so a trip can't end somewhere shut (maintainer,
2026-09-24). Only the destination's stop-level disruption is checked, not its departures: with each
refresh, reusing the same few-minute cache as every stop's closure check (bus poles batched), so a
destination costs a request only every few minutes. A failed check keeps the last known notice and
claims nothing new. Alerts on lines with no favorite journey (a separate favorite-lines list) stay a
`TODO.md` idea.

**Alerts** (maintainer, 2026-10-08). A favorite journey can post a **silent notification** when one
of its lines is disrupted at the times the rider travels it. Alerts are **off by default** and set
**per direction**, since the way there and the way back are ridden at different times: each direction
has its own switch, days of the week and one or more time windows (in the device's time zone, a
window not crossing midnight). Turning a direction on starts it at **Monday to Friday, 08:00–10:00**
for the way the journey was saved and **16:00–18:00** for the way back; guessing which way is the
morning one (from favorite places, say) is a `TODO.md` follow-up. The settings live on the journey's
own **Alerts** screen, opened by tapping its row in Settings' list (a direction keeps at least one
day and one window, and has at most four: its switch is how it's turned off; a change that couldn't be saved is said there), which says each watched
direction's days and times ("Alerts to Waterloo: Mon–Fri 08:00–10:00") or "Alerts off". That list
names a journey with a two-way arrow ("Euston ⇄ Waterloo"), since it is saved both ways; a card and
the Alerts screen's sections keep ➔ for the way shown.

The check runs **in the background** (WorkManager), only while a window is open: one at a window's
start (at once when a direction is turned on, or the app starts, mid-window), then **every 15
minutes** (WorkManager's floor) and once as it closes, and none outside one. Each asks TfL for the status of
the journey's line and the lines the app last found running directly between its ends (its widget
pins), in **one batched line-status request** for every journey being watched: £0, about 8 requests
per two-hour window, the same kind the list makes, so nothing new leaves the device. Battery: one
deferrable wakeup every quarter hour inside a window, needing a network. Nothing is scheduled while
no direction is watched, or while Android won't show the app's notifications or has this channel
turned off (the Alerts screen says so, with **Allow**), since every alert would be invisible. The
check is re-scheduled when the device's clock or time zone changes (a window is a time of day where
the rider is), and on a return to the app when none is pending or the one pending was left timed
for an old clock. The
lines a check asks about come from the pins worked out for the direction it travels, else those
for the other way: the widget keeps only the direction last shown, and a direct line between two
stops almost always runs both ways.

One notification per journey, on its own **low-importance channel** (no sound, vibration or
heads-up), titled the way the open window travels ("Euston ➔ Waterloo") and naming each disrupted
line with TfL's label and words. It is posted once for a disruption: kept up to date silently while
it shows, posted again only when what TfL says changes, and **not brought back** once swiped away
(one that only timed out, behind a late check, is). Alerts turned back on in Android's settings
re-arm the check the next time the app comes to the foreground.
It goes when the lines recover or the window closes: it times out at the window's close, or sooner
if two checks pass without renewing it, so it never outlives its window or the check behind it (D4),
offline or not. Where both directions' windows overlap, both directions' lines are checked. A check TfL didn't answer claims nothing:
what's shown stays until its timeout. A tap opens the app. The check asks about every alert on the
line, either direction: which way a disruption runs is known only for some bus alerts, and only after
a lookup the background check doesn't make.

**Change at a fork.** When a line runs only one branch from the origin (a Northern line train to
Edgware from King's Cross, none to High Barnet) and no direct train is due, the card shows the
other branch's trains instead, under "King's Cross ➔ Camden Town (for High Barnet)", with "No direct
trains" above them (maintainer, 2026-09-24). The change stop is the last stop the train's path
shares with the route to the far end, from the route data the card already loads; the brackets name
the journey's own end, not the branch's terminus. While a direct train is due none is offered, since
changing mostly lands the rider on that same train at the fork. It names only where to change, not
the connecting train's time, which isn't known. Rail only: a bus's path is often the route's end,
too loose to send a rider to change on. The widget keeps showing direct trains only.

**Direct only, for now.** A favorite journey is saved on one line between two stops (its route
places the ends) and shows every direct line between them; a starred trip with a change
(the eventual goal behind starring home and work) builds on *Trips with a change* below. The long-press entry point has no
cue of its own, so a starrable stop list opens with a one-line tip ("Long-press a stop to favorite the journey
there") until the user dismisses it (maintainer, 2026-09-24); the dismissal is kept with the app's
settings. **Settings lists the favorite journeys** (maintainer, 2026-10-05), third, under the
disruptions switch: each by its two stops and line, with **Remove**, so one starred by mistake can be
found and dropped without riding past its card; Remove only ever removes the favorite. Its **Add**
(maintainer, 2026-10-07) picks the journey's two stations over the list: the station search asks
where it starts, then, with that standing in its From row, where it ends; the From row changes the
start, and Back from the end drops back to it. The pair is saved on a line both stations serve (rail
before bus), as a long press saves one, and the list says what it's doing ("Adding Euston ➔
Waterloo…") and why a pair wasn't added: no line serves both ("No direct line from … to …"; a
journey with a change waits on *Trips with a change*), the same station twice, already a favorite, or
the lookup or the save failed. It costs one stop lookup per station picked, as opening a station does. Favorite journeys are
kept on the device with the rest of the user's config and never logged (*Privacy*).

### Trips with a change

*Planned* (maintainer, 2026-09-26; mocked the same day). *To…* — on the near-me list, one tap on the
app bar's **Directions** button (maintainer, 2026-09-26; the overflow keeps its *To…* too) — plans a trip to a **stop** — a station
or bus stop picked from the station search, never an address or a map point — from **where the rider
is**: the fix the near-me list was found from goes to the Planner as the trip's start, and it walks
from there to whichever stop serves the trip best — a station ten minutes' walk away as readily as
the bus stop at the corner (maintainer, 2026-09-28: starting at the nearest stop only ever offered
routes from that stop, so a bus to the Tube was offered and the walk to the Tube never was). From the
*From…* station, when one is set, it plans from that station's own stop.
This is the ordinary search flow; **any saved favorite** is the exception, planning to its
saved coordinate rather than a picked stop (D9) — the coordinate goes only to TfL, which walks the last leg.
Every favorite is routed by tapping it — in the Settings *"Favorite places"* list, as a **chip atop
the near-me list** (*Routing from the near-me list*, below), or as the same **row of chips at the top of the To… picker** (from the near-me list or a *From…* station),
which offers **all** the saved favorite places before any typing (maintainer, 2026-09-27, broadening
an earlier Home/Work-only framing) so the rider routes to a saved place in one tap without leaving
the trip. If that list can't be read it says so with a **Retry**, rather than
hiding the section as "no places" (principle 2); station search stays usable meanwhile. The search page keeps its look (each
result's name over its modes) under a **From-over-To bar** (maintainer, 2026-09-28): the start is
the rider's position unless they picked a station with *From…* or changed it from the From row
(*Where a trip starts*, above). **"Here"** as a pick appears only in the *From…* search, as the first
chip of the same chip row the *To…* search's places sit in (behind the crosshair, shown before the
rider's saved stops are read), and taps back to the rider's position; never among the *To…* search's
or the near-me list's place chips, since a trip to where the rider already is goes nowhere. The saved
places follow "Here" in the *From…* row too (maintainer, 2026-10-04): a tap starts there as a picked
station does — the near-me list as if the rider stood at the place's coordinate, with no stop of its
own, and *To…* from it plans from the place's coordinate as *To…* from "Here" does from the rider's,
even with no stop in range (it then opens straight on its *To…* search) — so setting out from Home is
one tap. The trip opens on a **list of routes, best first**: ordered first by how far StopDash
stands behind each route (tiers, below — usable before not, fully live before "est."), and within a
tier by the earliest end-to-end arrival, worked out leg by leg from live trains (below); an estimate
goes ahead of a live route only when it is sooner even at the latest end of its range (below). The
first route is therefore the fastest one StopDash can vouch for, never an estimate that could turn
out later. A bold header says
so over it, **Fastest**, and **Simplest** heads the card riding fewest times (fewest changes; the
earliest shown on a tie), or **Fastest · Simplest** heads one card that is both (maintainer,
2026-09-30), so the trade between them reads at a glance. Neither heads a lone card, Fastest never a
card whose arrival is withheld, and Simplest only when the cards don't all ride as often. The cards
headed so come first, Fastest then Simplest, and the rest follow in their own order under one
**Other** header (maintainer, 2026-09-30), so a Simplest card arriving later moves up beside Fastest
rather than splitting the rest in two. **Least walking** (maintainer, 2026-10-03) heads **every** card
walking least by the Planner's times (at the rider's pace), the first card included (maintainer,
2026-10-04: "Fastest · Least walking" on the top card when it walks as little as any), unless every
card walks as much; after Fastest and Simplest, sharing a header with them the same way ("Simplest ·
Least walking"): each plan also asks the Planner for its least-walking routes beside the quickest and the
fewest changes, so the bus to the station is offered beside the long walk there. Such a route stays
on the list though it changes more and arrives later (below), and is timed past the cap on routes
timed. With none of these headers shown, no card is headed Other. Routes riding the
same lines in turn but changing at a different stop are **cards of their own**, told apart by the
stops their ride rows name (below) (maintainer, 2026-09-27). Routes whose first ride goes between the
same two stops by the same mode and then ride the same lines — the 43 or the 134 to Highgate
station, then the Northern line — **share a card**: its header shows that leg's lines as **one pill
cut diagonally** ("43/134", read as "43 or 134"; each part as wide as its own code, never narrower
than a two-character one, so the cut pill stays compact yet a one-character route keeps room of its
own — maintainer, 2026-09-28; three or more lines show the first, then "…" in the second's color,
"43/…", so the pill stays two parts wide however many lines share the leg, with every line still
in its accessible label and the ride's times — maintainer, 2026-09-28), then the later lines and the best of their
arrivals, and its first ride's row times every one of those lines together (below). **Every ride**
also takes in the lines the Planner didn't name that serve **its own two stops** (maintainer,
2026-09-27): another line of the same mode whose route runs from the ride's boarding stop to its
getting-off stop rides beside the Planner's as an equal — on that ride's pill, its trains among the
ride's times and headway, and a departure row of its own on the open route. "Stop" is strict: the
same stop, a road's two poles counting as one, never an interchange's other stops, so every walk and
change stays as the Planner gave it. Such a line is found among the boarding stop's arrivals (or the
plan's other rides), and needs its route loaded to vouch that it reaches the stop. Only a line that
also calls at the same stops in between shares the ride's time on board and times the route; one that
gets there another way still shows its trains, but a route is never timed as if it rode that way.
Its trains are offered, and time the route, only once its line is checked as running and the stops
it boards and gets off at, its own poles, are checked open; a notice at one of them shows on its ride.
Nor do they time it from a stop whose last refresh failed. Where the Planner's own stop failed but
such a line's refreshed, the ride still has fresh arrivals, and only those show whether it runs
every few minutes (below): trains held from the stop that failed may have stopped running since.
**A train that runs through a change is a route of its own** (maintainer, 2026-09-28): where a
ride is followed straight away by another of the same mode, and a line boarding at the first ride's
stop runs on to where the second gets off, the trip also offers that one ride in place of the two —
one stop on the Victoria line then the Northern line, when a Northern line train from the first stop
goes the whole way. It is found as the other lines are (the boarding stop's arrivals and the lines'
routes, no request added), its trains are checked on the way like any ride's, so a train for the
other branch isn't offered, and it is timed and ranked like the Planner's routes. It is offered only
while a train through is predicted: the Planner has no times for it, and the times of the two rides
it replaces belong to other trains. Once opened it stays, as below, its arrival withheld while none
is predicted. A walk between the
rides is a change of station, so those stay as planned. **A route with more changes is listed only
when it gets there sooner or walks 5 minutes or more less** (maintainer, 2026-09-28; walking 2026-10-03): one that another route with fewer changes
beats or ties, without walking that much more, while StopDash stands behind that route at least as far (usable, then unchecked, then
not running; then live, estimated, withheld — stricter than the list's order, which ranks an
unchecked route a live train times among the checked, so a route checked open is never left off for
one that couldn't be checked), is left off the list, and of two arriving together the one with fewer changes comes first.
An arrival that is withheld is never compared, and a route already open stays open.
Tapping the card opens its best route. **Every route looks alike** — no
route is expanded — as a card whose top row is its **duration · arrival** ("22 min · 08:24"); the
duration is from now to that arrival, so it takes in the same walks, waits and legs. Where waiting
for a frequent line past its predictions (below) could make it later, by a minute or more, both
show as a **range** (maintainer, 2026-09-30: once a route can rank above a live one on its latest, a
single estimated time hid why it sorted where it did) ("est. 41–49 min · 11:26–34", the end's hour dropped within the same hour to
save width): from no wait up to the line's longer typical gap, the route timed again with each such
wait at its longest, so a wait that misses the next leg's train counts that too. Where that later
route can't be timed at all (the connection missed, nothing else known), it reads as open-ended
("est. 41+ min · 11:26+"). It counts waiting only, not a slow run, and ranking still goes by the earliest arrival (maintainer,
2026-09-27), except that an estimate is set against a live route by the latest end of its range
(below). Under the top row, the **walk to where it starts** has a row of its own — a walker in the
pills' room, so the stop starts where a ride's does beside its pill (every row's pill column as wide as
its card's widest pill, a cut pill's included, so a card's stop names line up; per card, not down the
whole list, whose width moved every card each time a status or route landed while the page loaded —
maintainer, 2026-10-04), then the first stop, and its minutes in the
times column, in parentheses ("(3 min)", a duration, set against the first train's) — since it's why a train too soon to reach is grayed, and it reads as the
route's first leg (maintainer, 2026-09-27, replacing "From ‹stop›" in the top row). A first stop
under a minute away has no walk row; the top row says **"From ‹stop›"** before the arrival instead,
the stop's name cut before the time is. Then a
row per ride names **where it gets off** — its line pill (the first ride's lines as the same cut
pill), the stop, and a ⚠ just before the times when that line is disrupted, where the main
screen's rows put it (maintainer, 2026-09-28) — so cards riding the same lines but changing at different stations read apart
(maintainer, 2026-09-27); walks between rides are left to the route's page. The first ride's row
also carries its **live times**, every line on the card together in time order ("1 · 3 · 5 min"):
each is a way to the same change, so only when matters, not which line, and the card stays four
rows tall rather than growing a row per line (maintainer, 2026-09-27, replacing a live row per
line). The stop's name gives way before the times do, and shortens as a departure's destination
does — whole words first ("Wood Ln", "Bush Mkt"), then its floor, and only then a single "…" — as do the walk
row's stop and "From ‹stop›" (maintainer, 2026-09-27). A **later ride's row** says instead how often
its line runs, **"↻ 2–4 min"** (↻ for "every", to save width; a screen reader hears "Every 2 to 4 min"): the rider isn't there yet, so its countdowns say nothing they can
use. It is the middle half of the gaps between that leg's live trains (those along its route), so a
bunched pair or one long gap doesn't set it, and it shows only with at least three trains known
(maintainer, 2026-09-27); no extra fetch, since each leg's boarding stop is already fetched. A screen reader still hears each time with its
destination. The card is one choice, so tapping anywhere on it opens its best route, and a long
press anywhere on it offers **"Hide all ‹group› services"** for
every group any of its legs rides, so a slow tube leg can be hidden from the card as readily as the
bus that starts it. The open route's leg rows still open their line's page and offer "Hide ‹mode›"
for their own line, as on the list. Its header, the route's pills then its arrival, carries one ⚠ just
before the arrival when any of its lines is disrupted, as every screen puts it before the times
(maintainer, 2026-09-28); the leg rows below say which. While a
line's route is still being checked, the times take the line's live trains at that stop (those heading for
the Planner's terminus, whether or not a name adds a place such as "(London)") as the main screen
does, timing nothing until the check vouches for them: only a bus to the terminus on no named branch
shows plain meanwhile, and one that may skip the rider's stop (another terminus, a named branch such
as "via Bank", or any rail service, which may run fast to the same terminus) grayed until then.
Checking a line with many routes (a main-line railway's) never stalls the page: a train not yet
checked reads as its line still being checked.
The times read "Loading" until the
boarding stop's arrivals arrive, and "–" when none can be vouched for (*Text is chosen to fit its
space*). A bus stop the Planner names by its stop pair (a road's two poles) boards at the pole its
bus uses, worked out from the line's route: the pole the Planner names can be the other side of the
road, where the buses run the other way. Both poles are fetched, and the times read "Loading" until
the route says which. A bus station's stands are in no pair, and the Planner can name one the line
doesn't use, so the ride gets off at the route's own stop of that name, and boards at it too, its
times fetched there and reading "Loading" until the route says which stand and they arrive, as at a
stop pair (maintainer, 2026-09-30): at a stand the line doesn't use none of its buses is ever
predicted, and the route could never be placed and so never checked. A train the Planner names by
its platform alone, with no station (Thameslink from St Pancras's low-level platforms, the Elizabeth
line from Liverpool Street), boards at the station TfL lists that platform under, from the bundled
station list, since only the station has live trains and a National Rail board; such a route was
once dropped as unreadable, so the trip lost it (found 2026-09-30). A route that is all walking (two stops close
together) shows "Walk" where the pills go and its minutes, with no live row and no arrivals request.

**Routing from the near-me list** (maintainer, 2026-09-27): the saved places lead the near-me list
as **one row of chips**, one per place in the saved order, each showing the place's icon, its name, or both, as the place chose (below); a tap plans the trip
as above. The row is the list's first item, so it **scrolls away with the list** rather than taking
a pinned row's space, and scrolls sideways when the chips don't fit; when the stops near have no
departures it still heads the empty state, just when a route elsewhere is wanted, and with no stops in
range at all it heads "No stops found nearby" the same way: the trip from here plans from the rider's
position, so it needs no stop to start from (Codex on #315), and a position from a last-known fix is
flagged approximate over that trip, as over the list. A move of 150 m or more plans that trip afresh,
as a new nearest stop does a trip from the list, so routes from a place left are never shown for
where the rider is now, even while the new plan loads (Codex on #439). It is on the
near-me list only — not a station's page or a drill-down, which are about one place. A place the
rider is **already at** is left out, since "Home" at home is a route to nowhere: **within 200 m** on
an accurate fix (one whose own reported accuracy is within 100 m), back once past **250 m** so a
wandering fix doesn't make a chip flicker. The rule leans towards showing: a fix the list flags as
approximate, coarse or not updated, a last-known fix (old, however tight), or one only an approximate
location grant allows, hides nothing,
as a wrongly hidden chip costs the one-tap route while a wrongly shown one costs only space. The
places are read from the first frame, from the device (no request), so the row is there when the
list is: the list, and "No stops found nearby", wait on their placeholder until the places are read and
the chips worked out (off the main thread), rather than drawing first and the row a moment later
(maintainer, 2026-10-04); a place list that can't be read shows no row here — Settings and the To… picker are where
that is said, with a Retry. Each place chooses the **days of the week** its chip shows on — Work on
weekdays, say — in its editor, as one row of seven day toggles under "Show on main screen" (maintainer,
2026-09-28). Every day is the default, so a place saved before the choice existed keeps its chip, and
no days at all keeps the place for the To… picker and Settings without a chip. The day is the
device's, and the row follows it past midnight and across a time-zone change while the list is up. Every place — Home and Work included — has a name the rider can edit, and can wear **one icon**
from a short fixed set (house, office building, briefcase, backpack, graduation cap, park, stadium,
hospital, airport, gym, store, café, restaurant, theater, beach, heart). The icons are monochrome
Material Symbols tinted like the text around them, not emoji: the system's color emoji were too busy
to read at chip size (maintainer, 2026-09-28). A place with an icon chooses what its chip shows —
**both** (the default), **the icon alone**, or **the name** — while the saved-places list and the To…
picker, which have room, show both (maintainer, 2026-09-28). Home, Work and School start with the
house, the briefcase and the graduation cap — including ones saved before icons existed — and a
custom place with none; tapping the chosen icon clears it, and a cleared icon stays cleared. A trip's
title and TalkBack use the name alone, so an icon-only chip is still announced by name. A **long press** on a chip opens the saved places' own screen, to edit them (maintainer, 2026-09-28).

**Launcher shortcuts** (maintainer, 2026-10-07): long-pressing StopDash's icon on the home screen
offers **one shortcut per saved place**, in the saved order, as many as the launcher shows, each
labeled with the place's name and drawn with its icon (the app's own for a place with none). A tap
opens the app on the **trip to that place from the rider's position**, as its chip does, closing
whatever screen was on top. Unlike the chips, every place is offered every day and none is left out
for being where the rider is: the long press is asked for, and keeping the list in step with the
day or the rider's position would cost a wakeup for nothing. The shortcuts follow the saved places
— added, renamed, reordered or removed in the app — and a shortcut **pinned** to the home screen
for a place since removed is disabled, the launcher saying "Place no longer saved"; one tapped in
the moment before that lands says the same in the app, as does one tapped while the places can't
be read ("Couldn't read your places"), never opening a trip silently or nowhere. A shortcut carries
only the place's saved id, never its coordinate, so what the launcher keeps names no position and a
tap routes to the place as it is saved now.

The trip's top bar carries the app's **overflow** as the list's does — its red dot while an update
is available, "Update available", "Send bug report" and About — so a problem seen on a trip can be
reported from it (maintainer, 2026-09-26). So does **every other screen** with no menu of its own —
On the way, a route's page, a station's page, the From…/To… search, Settings, the saved places and
the licenses (maintainer, 2026-09-29): a problem is reported from where it is seen, and the report's
screenshot shows that screen. The location gate has it too (maintainer, 2026-10-02), in every state,
with From… and Settings as well, since both work before any stop is found; its stuck states keep their
own "Send bug report" button beside it.
Settings masks both API keys again as its menu opens, so a key revealed with Show is never in a
report's screenshot: a credential is not one of the things the report discloses.

Tapping a route opens it, drawn with the list's own parts, **every leg a card**: the leg's platform header ("Highbury
& Islington – Platform 2") over a route card of that line's live departures toward the change, then
"6 stops to Whitechapel", then the next leg's header and card at the change station, down to "2 stops
to Canary Wharf". Only the **next ride's** rows count down; a **later ride's** rows say how often
each line runs there ("↻ 2–7 min"), as its row on the list's card does and from the same trains, so a
ride on one line reads the same on both (maintainer, 2026-09-29): the rider isn't at that stop yet, so
its next few trains say nothing they can use. Each line's row gives its own line's figure, and none
where fewer than three of its trains are known. A row still opens its line's page, which counts down.
A walk reads "Walk to ‹place› (5 min)" (one within a place onto a ride reads as the change, *On the way*), its minutes in parentheses so they read as how long it
takes, not a time of day (maintainer, 2026-09-27); a walk to a station's entrance, which the
Planner names by its street then the station ("Cannon Street, Cannon Street Rail Station"), goes by
the station ("Cannon Street"), never the street repeated. Line status and stop closures for every leg show exactly as on the list:
the ⚠ on a disrupted row, the status chip on a line with no trains, the closure card at a closed
stop. **Tapping a leg's row opens its line's page**, as a row on the list does: the line's full
service alert, with the list's × to dismiss it line-wide (*Disruptions*), and its stops (maintainer,
2026-09-26); back returns to the route. The page says "No disruptions reported" only once both the
line's status and its stop's closure check are known and current, as the list's does: neither may
be as old as a stale countdown (D4), as the checks a trip shown again holds are while it checks
again. The route is still ranked by them meanwhile. **Every stop a route boards
or gets off at** is checked for a closure or a moved stop — each leg's boarding and alighting stop,
so both ends of a walk between stations or from a stop the route starts at, and the destination, as a favorite journey's far end is
(*Alerts for the journey shown*), from the list's own few-minute cache, so a stop the list just
checked isn't asked about again, and a trip shown again takes a closure the list found since. For a
bus, every pole of the stop pair is checked, since the one it uses may be the other side of the road:
until its line's route shows which, the route isn't vouched for there, and once it does, only the pole
the bus uses counts, by a check of its own. A bus station's stand the line's route puts the bus at, in
place of the one the Planner named, is checked too once the route shows it, and until then the route
isn't vouched for there.
A notice shows as the list's closure card on the open route, where
the ride boards or gets off, and as the ⚠ on the list card's ride. A failed check keeps the last known
notice and claims nothing new. Routes sort
in tiers: a route with a suspended or closed leg, or a stop it boards or gets off at that TfL says is
closed (a moved stop doesn't count), sorts below every usable one, whether live or
estimated (below), so a route that can't be ridden is never listed first while one that can exists.
A route whose line status or closure check failed with nothing known yet ranks on its arrival like a
checked one while a live train times any of its rides: the Planner offered it, and the train shows it
running (maintainer, 2026-09-30: a faster route isn't buried under a slower one because a check
failed). With none, it sits between the two: below every route checked and open, above those known
not to run. Either way it says what it couldn't check, until a check succeeds — "Unknown:" then
each line's pill and each stop's name (maintainer, 2026-10-04), each line whose status isn't known or whose check failed, then each stop with
no current check (its bus not yet placed on a pole there included), by the names the cards show
(maintainer, 2026-09-30) — and of two arriving together the one checked comes first. A stop whose
bus waits on its line's route to be placed on a pole is still being checked, not one that couldn't
be: the trip reads as checking until the route loads, or while one that failed is loaded again,
and names the stop only once a load has failed with none under way. A
ride goes by any of its lines: one whose Planner line is suspended or unchecked still counts as
usable while another line riding the same stretch, checked as running from stops checked open, can
take it, since that line's trains time it too. The Planner's
line then answers to its status like any other: suspended or unchecked, its trains neither time the
route nor show as catchable, and its timetable gives no time either, so the arrival waits for a
train that can take the ride. A line whose latest status check failed stands in for none: the status
kept from before is the last one known, not a current one. The trip still says it couldn't check the
Planner's line.

**A trip's disruptions have a row of their own, always there over its routes** (maintainer,
2026-10-04): "Disruptions:" then each disrupted line's pill (as the cards' ⚠s judge them, less what
was dismissed) and each stop with a closure notice in force, then the check's word — "Checking…"
while one runs, "Unknown:" and the pills and stops it couldn't check when one couldn't (in red), else
"None" once there's nothing to show. A line whose trains couldn't be followed to where the rider gets
off (its route failed to load, or a train's path couldn't be told) is named there too, but only while such
a train could be the one the rider catches: one leaving after the train a route is timed from can't change
its times, so it leaves nothing unchecked: the row took the place of the "Some routes couldn't
be checked" banner, which slid in over the routes once the list showed (maintainer, 2026-10-04), and the
lines page says what an unchecked line means for its times. The row is one line high whatever it says
(maintainer, 2026-10-04), as the home screen's is: what doesn't fit is counted as "+N" rather than
wrapped onto a second line that would push the routes down, and it's heard whole, every line and stop
named; the lines page lists everything. It replaced a note that came and went as the page's loads landed one after another, moving
every card under it for the first few seconds. A new check says so at once; any other word waits
until it has held for a moment (1.5 s), so a gap between two loads never reads as "None" or as
"couldn't check". While the row's lines are still being worked out for newer cards it shows the last
ones a moment longer, as a card shows its last times, then "Checking…": never a "None" the current
cards may not bear out. The "Checking routes…" banner over the list is held the same way.

**A tap on the disruptions row opens every line the trip rides with its status** (maintainer,
2026-10-04), its chevron always there so the row reads as a way in. A full-screen dialog over the trip
(maintainer, 2026-10-04: not a sheet), with the app's menu and Back returning to the trip where it
was, and one the home screen can open too. Its menu offers Settings (maintainer, 2026-10-06), after
a line page's own Dismiss, opening Settings on the Disruptions summary's page, since what the row and this page cover is set
there: the page closes first, and Back from the Disruptions page goes up to Settings, then out, so
the rest of Settings is a Back away. The page's lines: one row per line, its pill and the worst status any card shows for it (a line not
running ahead of one running with delays, whatever TfL's numbers say). Disruptions come first, worst
first; then lines that couldn't be checked (a failed or left-out check stays so until one succeeds);
then "Checking…" lines; then any the rider dismissed, just above the good services, still named by the
alert dismissed ("Diversions · dismissed", never the "Good service" left once it's gone) but toned down, since a dismissal quiets the alert, not the line's state (maintainer, 2026-10-05: demoted,
not hidden); then the good services. A worse alert dismissed while a milder one stands is named beside
it, toned down the same way, on the home screen's page as on a trip's. A dismissed line is never on the row's pills: on the home
screen's row, a line counts as dismissed once its alert is dismissed every way it's disrupted, as the
list's rows dismiss their own way's alert. A line with no current status is never
called a good service. The stops the row names follow, then, only when a line couldn't be checked, a
note that its times may be wrong. While the page is open each line keeps its place, so a check landing
changes what a line says, never where it is; a line new since it opened goes last, one gone from the trip
keeps its place saying so, and a rotation keeps the order. Each line is one row high whatever its status, and every line opens a page of its own: TfL's
reason where it's disrupted, and the line's map (*Line page*). A disrupted line's page has an overflow menu that dismisses
that alert as a departure's line page does (maintainer, 2026-10-06: an overflow action, since a × reads as closing the
page). A trip with no ride says it has no lines. The page renders the row as it was
worked out, never working anything out itself.

**Once the list is showing, its cards re-sort as their times move** (maintainer, 2026-10-04): the
soonest stays on top. A card slides to its new place rather than jumping, so the eye can follow it,
and a tap or long press on a card while it moves is dropped, since it could land on the card that
just moved under the finger. That holds whatever moved it: a re-sort, a note or header coming in
above it, or a scroll (maintainer, 2026-10-04).

**TfL's Journey Planner chooses the lines and changes; StopDash's live arrivals give the times**
(principle 1). The first leg counts down like any row. From "Here", the rider still has to reach the
first stop: the Planner's own **first walk leg**, from their position along real streets, is that walk,
shown as the route's first dotted link ("Walk to ‹stop› (7 min)"), and first-leg trains that leave
before the rider can get there are grayed (from a *From…* station the rider is taken to be there
already). That walk is timed at the rider's walking speed (*Walking*, below); where a *From…*
station's trip starts at a neighboring stop, the phone estimates the walk there itself, at the same
speed. A later leg is timed by the change station's live
trains, those the rider can't reach in time passed over. The **arrival is worked out leg by leg**:
the first first-leg train the rider can reach plus its run time gives the time at the change, plus
the change or walk time; the first live train there that the rider can reach starts the next leg,
and so on to the end. A train only counts for a leg if its route **calls at that leg's alighting
stop**, as a direct trip's trains must reach the destination (*Journeys*): on a branching line, a
train for the other branch isn't shown on the leg at all, so the rider sees only trains they can
take (maintainer, 2026-09-27). A train the list wouldn't count down — canceled, or with no time TfL
stands behind (*Disruptions*) — is never a reachable train: it is skipped for the arrival and the
ordering, and shown as the list shows it.
Run and change times are the Planner's, so the arrival reads "about". The same end-to-end arrival
orders the routes within a tier, so "fastest" never assumes a connection the rider can't make. A leg with no live
train in reach yet (none predicted that far ahead, its arrivals failed, or they have gone stale under
D4) falls back to the
Planner's own time for it, as long as the rider can reach the Planner's departure for that leg
(the legs before it, or the walk from "Here", get the rider there in time). A Tube, DLR, Overground
or Elizabeth line leg the rider reaches past its live predictions (its trains running, just not
predicted that far ahead) is boarded as they arrive, since those lines run every few minutes
(maintainer, 2026-09-26) — as long as its predictions show it doing so now: several trains, none long
after the one before, where thinning trains may be the night's last (maintainer, 2026-09-27). How far
the predictions reach is no test: TfL predicts only trains already running, so near a line's start
they end within about a quarter of an hour all day, which withheld routes changing there. A bus
leg is boarded on arrival the same way when its own predictions show it frequent now: TfL predicts a
bus only about half an hour ahead, so two buses, the second soon after the first, are enough, and
the arrival's range allows the longest wait a frequent line has (maintainer, 2026-09-27: a bus past
its two predictions withheld its route). Such a frequent leg is boarded on arrival even while the
Planner's train can still be made, when that train leaves more than the line's longer typical gap
after the rider gets there: the Planner planned the trip leaving later, and an earlier train is the
one the rider would take (maintainer, 2026-09-30: a bus caught sooner than planned waited nine
minutes at the change for the Planner's train). Otherwise the Planner's train is missed and nothing says when the next one
leaves (a National Rail or tram leg, a bus seen only once or far apart, a line with no trains, or arrivals that failed), so StopDash
**withholds that route's arrival** rather than guess a wait: the route shows "arrival unknown" in place of duration · arrival, and
sorts after every route in its tier that has an arrival, until a refresh brings live trains for the
leg. Since the Planner's timetable does say when the next one leaves, a withheld arrival on a plan at
least 5 minutes old plans the trip again at once rather than wait out the 15-minute reuse (maintainer,
2026-09-27): once per plan, so it asks at most every 5 minutes, and each time the log says which leg
withheld the arrival and why. A re-locate in flight (a return to the app) is waited for first, as the
minute tick waits for it, so the routes change once, for where the rider is now. Otherwise its arrival reads **"est."** instead of "about", and within
its tier it sorts **after every route whose legs are all live** — unless it is timed from at least one
live train and gets there before that route **even at the latest end of its range** (maintainer,
2026-09-30: a live ride then a frequent bus boarded on arrival sat below a slower live route even at
its worst). So an estimate
is listed first only when it beats the live-confirmed routes however its waits fall, never on its
hopeful end; estimates keep their own order among themselves, so none passes an earlier one to get
there. Walks
between stations show as a dotted link with the Planner's minutes. **Every walk the Planner includes counts** toward which
trains are reachable and toward the arrival: one before the first ride (from the stop sent to a
better one), between stations, and after the last ride to the picked stop.

**Lifecycle.** The trip screen appears at once, titled with both ends, with a "Planning…"
placeholder; the Planner call runs off the render path. A plan is kept in memory for the trip and
reused if the same trip is reopened within 15 minutes. From "Here", a re-locate (the crosshairs, or
a fresh fix) that resolves to a different nearest stop **150 m or more** from where the trip took its
stop discards the plan and re-plans at once, showing "Planning…" rather than the old routes. Closer than
that (a refined fix, a few steps that tip the nearest stop over), it is the same trip (maintainer,
2026-10-07): the Planner plans from the rider, not the stop, so the routes stay up rather than flicker
through "Planning…". A new fix that keeps the trip keeps the
plan while the rider is within **150 m** of where it was planned from (a fix's wander, or a few steps);
once they are farther, the plan's first walk is from somewhere they've left, so the trip plans again
from where they are, keeping the old routes up meanwhile, so the reachable first-leg trains follow
the rider. A plan reused from earlier is held to the same test when the trip opens. **Pulling the
routes down** is the rider asking for the latest (maintainer, 2026-09-29): the trip plans again at
once, from the same start to the same place at the same walking speed, however young its plan, and
asks afresh for every boarding stop's arrivals and National Rail board, forgetting what every screen
keeps, as the list's pull does; the routes stay up until the
new plan has answered, and a failed one says so over them with its Retry. From "Here" it also takes a
fresh fix, as the list's pull does, so the 150 m test above applies to where the rider is now, and
the pull shows as under way until the trip has planned and fetched for that fix. A plan made or arrivals asked for before the pull are never reused in place of asking again, however
recent (maintainer, 2026-09-29): a later re-plan, from that fix or a walking speed changed, asks
afresh for any stop it boards at, and a fix nearer another stop, which is a trip of its own, plans
and fetches afresh too rather than take up a plan or arrivals kept from before the pull. A request
that fails keeps the stop's last arrivals, marked as failed and aging as any failed refresh's do
(*When something is wrong*), rather than blank the leg. An
opened route has no pull: its times keep the minute tick. While a re-locate is in flight, or after
one that fails (which keeps the old stop, as on the list, and shows the list's location banner over
the trip), the origin is unconfirmed: until a fix is confirmed again, the walk is
from the last confirmed position and every route's arrival reads "est." at best, never live-confirmed. A plan older than that is re-planned, on open
or when it expires while the screen is visible, showing the older plan stamped with its age meanwhile,
so a route that has since become viable can appear. An opened route is matched across a re-plan by its lines and
stops in order, never by its place in the list; if the new plan no longer has it, the trip goes back
to the refreshed list rather than keep showing a route the Planner no longer offers. While the plan
offers it an opened route stays open and timed, even once live times move it out of the few a trip
times. A train through a change is kept whole once opened, and stays open while the plan offers
the change it runs through, whether a train is predicted or not (maintainer, 2026-09-29): with none,
its line reads "–", as a line with no trains does on the list, and the route's arrival is withheld
rather than taken from the Planner's times for the change, which belong to other lines. Once the plan
no longer offers it (a new plan without it) or its mode is hidden, it's closed for good rather than
reopening unbidden when it comes back; a plan still landing keeps it. The live times refresh with the list's refresh cycle
while the screen is visible, and follow the list's staleness rule (D4): a stale leg withholds its
countdowns rather than show them as live, and stops feeding the route's arrival and ordering: it falls
back as above ("est.", or "arrival unknown" when the Planner's departure is no longer reachable). If planning fails, the screen says why with a **Retry**
("Couldn't plan the trip: you're offline", as the list words its errors), over the last plan for the
trip if one is held, never a blank. Retry is disabled while its call is in flight. A change station whose arrivals fail withholds that leg's times
and falls back the same way; it is retried on the next refresh. Nothing retries in a loop:
Planner and arrivals requests go through the same rate limiter as every TfL request.

**Walking** is capped per walk at the rider's **max walk** (the Planner's `maxWalkingMinutes`), the
walk from where the rider is included, so it never offers a longer walk than they chose beside the
rides: 10, 15, 20, 30, 45 or 60 minutes, **20 by default** (maintainer, 2026-10-03; 30 from 2026-09-30). It was a fixed
15, timed like every walk at the rider's pace, so a walk to a station that beat every ride (21 minutes
at the average pace) was never offered (maintainer's report, 2026-09-30). It is one setting, chosen in Settings (under the walking speed) or from a chip beside the walking
speed atop a trip's routes (maintainer, 2026-09-30), and a change plans the trip again at once, as a
speed change does; plans are kept per limit. Every plan, whatever asks for it, waits for the walking
speed and max walk (and step-free level, below) to be read from storage, and the trip's chips open nothing until then, so no
route is planned under the defaults in place of the rider's own choice; a read that never lands delays
a plan by two seconds at most, then it plans with the defaults and plans again once the read lands. Every
walk is timed at the rider's **walking speed** — Slow, Medium or Fast (the Planner's `walkingSpeed`;
Medium is its average and the default) — so a brisk walker isn't shown a ten-minute walk they do in
six, nor told a train is out of reach that isn't (maintainer, 2026-09-28). It is one setting, chosen
in Settings or from a chip atop a trip's routes, and a change there plans the trip again at once,
since every walk and the connections after it were timed at the old pace. An opened route shows none
of the trip's choices (walking speed, max walk, step-free, modes, avoided lines): it is the route
chosen, so its page is its own legs (maintainer, 2026-10-04). Plans are kept per pace.
The request names the Planner's modes (its own default set, walking among them): left to its
defaults, the Planner accepts `walkingSpeed` but times every walk in a route that rides at its
average, so the setting changed nothing (2026-09-28). Named, the pace times each walk and so which
connections it offers, while the routes stay those it offers by default.

**Step-free.** A trip's routes can be held to a step-free level (maintainer, 2026-09-30), the
Planner's `accessibilityPreference`: **Any** (no requirement, the default; nothing sent), **Station**
(step-free from the street to the platform, maybe a step or gap onto the train: what a suitcase or a
buggy needs) or **Fully** (step-free onto the train as well: what a wheelchair needs). The Planner
then plans with lifts, ramps and level walkways in place of stairs and escalators. It is one
setting, chosen in Settings, labeled **Step-free: Any / Station / Fully** (the maintainer's names), or
from a chip beside the max walk atop a trip's routes. In its menu, Station and Fully each
carry a line saying what they're for, since the bare names don't tell a rider with a suitcase or a
buggy that Station suits them too. A change plans the trip again at once, as a walk change does; plans are kept per level. A plan's wait for the settings to be read
(above) takes in the step-free level too, so a rider who needs step-free routes is never first shown
routes with stairs.

**Direct to a place** (maintainer, 2026-10-07). A trip to a place (a favorite, or a place or postcode
picked in *To…*) shows a **Direct** section under its choices and over its routes: each line from the
trip's origin stops whose route calls at a stop within the rider's max walk of the place, at their walking
pace (as far as the Planner lets the routes' own last walk go, so a stop the routes walk on from counts
here too; maintainer, 2026-10-07; a change to either looks again),
once, from its nearest stop, with its next three trains ("3 · 8 · 13 min"), nearest stop first, a row on
show keeping its place while the trip is open (a line found later goes under the rows, never above, and
a stop the rider's fix wavers just past the trip's reach stays one until 50 m past it; SPEC principle 4); a line
not running a good service says so under its row ("Jubilee: Minor Delays"), as does a closure at the
stop it boards at, or at every stop near the place it gets off at; a line whose status, or a stop whose
closures, couldn't be had counts as unchecked. With a
step-free level chosen (*Step-free*), a line shows only where the stop it leaves from and a stop near
the place both meet it, by the bundled step-free table (a bus always does); a station the table
doesn't describe is left out and the section says it couldn't check every line. An avoided or
hidden line, or a mode the trip's chips turned off, never shows; a change to any of them, a new
nearby set, or a pull looks again at once. Three
rows show, then "+N more". A line is judged by its route as a trip's leg is, so a departure whose route
isn't known yet is left out and flagged rather than guessed; with none it says **None**, while routes
load **Checking…**, and when the place's stops or every origin stop's arrivals couldn't be had, or with
no rows something a Retry may get didn't come back (a stop's arrivals, a line's route), **Couldn't
check** with a Retry. A train no Retry can tell (past the calling points its station's board gives, at
a busy terminus only the next ten; a line TfL has no route for, as Eurostar) doesn't fail the section:
with no rows it says **None** (maintainer, 2026-10-07). Any line or train left unchecked is said beside
the **Direct** header ("Couldn't check every line"), never as a line under the rows, so finding it out
after the rows show moves nothing; where the section itself says **Couldn't check**, that says it, and
the header doesn't repeat it. A line TfL answers it has no route for isn't asked about again for a
day. The place's stops are one TfL `/StopPoint` lookup by its coordinate
(cached a day, apart from the rider's own areas); arrivals come from the shared cache, fetched only past
its age, and only while the trip is on screen. Within 200 m of the place (where its chip hides) the
section isn't shown and nothing is looked up. It replaces favorite journeys as the way to see the
trains that go straight somewhere.

A Direct row tapped opens its ride as a route of the trip (maintainer, 2026-10-07), as a route card
does: the walk to its stop, that line to the stop near the place, the walk on, timed live and startable
*On the way*. The plan's own route riding just that line from that stop when it has one; otherwise
TfL's Journey Planner is asked once for the trip via that stop, under the rider's own choices, and its
route riding just that line is opened. That route is never a card of its own and is let go once closed;
while open, each re-plan asks for it again from where and under what that plan is (a rider who has
moved, or changed a choice, never keeps a walk timed from before), as does the trip restored after the
app was closed in the background. An answer to choices the rider has since changed is asked again. A
re-ask that can't reach the Planner says **Couldn't update the Direct route** with a Retry, keeping the
route on show only while it was planned from the same place under the same choices.
While it's found the row says **Planning…** where its times go; when none rides that line alone, or the
Planner can't be reached, **Couldn't plan**, never another route in its place.

**One row of choices** (maintainer, 2026-10-07). Atop a trip's routes the walking speed, max walk
and step-free level are one row of dropdown chips rather than a full-width row each, so the routes
start two rows higher: the pace by a walking icon ("Medium"), the limit by a word ("Max 20 min"), the
level by the step-free icon ("Any"). Each is announced by its full name ("Walking speed, Medium") and
opens the same menu as its Settings row; the row scrolls sideways when it doesn't fit. The picks are in
the plain text color, not the accent: they're choices, not alerts. Each step-free level says what it's
for in its menu, Any included ("Stairs are OK").

**The choices scroll with the routes** (maintainer, 2026-10-08). Everything above a trip's routes
(the choices, the modes, the lines avoided, a place's Direct trains and the banners) scrolls away as
the routes scroll, rather than staying fixed over them. A phone turned on its side has room for the
routes too. They come back only with the top of the list, so a scroll back up never covers the routes.

**Modes.** A row of chips under the walk choices says which kinds of transport a trip may
ride: one per group the list already hides modes by, under the same names
(**Underground**, **Overground**, **National Rail**, **Bus**, **Tram**, **Boat**), selected while the trip rides it.
A tap turns a group off or on and plans again at once, as the options above do; plans are kept per
choice, and the choice is remembered across trips. It is sent as the Planner's `mode` list, less the
groups turned off. Walking, the cable car and rail replacement buses always stay: every route needs
its walks, the cable car is in no group, and a replacement bus stands in for a train or Tube the
rider still rides. The last group riding can't be turned off, since a trip riding nothing has no
route. It is its own setting, not the list's hidden modes: a mode hidden from the nearby list (a
noisy bus stop outside) can still be the best way somewhere, and a mode turned off for a trip (a
Tube strike) doesn't empty the list. The settings wait takes it in too.

**Avoiding a line** (maintainer, 2026-10-01). A route card's long press offers **Avoid ‹line›** for each
line it rides, beside its *Hide* items. An avoided line is **sticky**: it is left out of every trip
until the rider stops avoiding it, and each trip says so with a chip per line under the mode chips
("Avoiding" · **Northern line ×**), which a tap clears. Settings lists them too (**Avoided lines**,
"Left out of trips"), each with a **Remove**. The Planner has no way to leave out a line, and asking
it for alternative routes (`includeAlternativeRoutes`) returned the same routes in testing, so a
route riding an avoided line is dropped on the phone from the two plans a trip already asks for, as a
route riding a hidden line is: never shown, nor fetched for. When every route found rides an avoided
line, the trip says it found none, with the chips above to clear. It is its own setting, not the
hidden lines: avoiding a line leaves it out of trips only, not the list, the widget or the watch, and
the "hidden" banner never counts it. The settings wait takes it in too, so a route on an avoided line
isn't shown first.

**Avoiding a stop** (maintainer, 2026-10-08) works the same way, for a station found closed (*Stop
closed* under a missed change): sticky, a chip ("Avoiding" · **Bank ×**) and a Settings row (**Avoided**,
now listing stops beside lines) to clear it, kept in the same set. A route boarding, getting off or
changing on foot there is dropped on the phone, since the Planner can't leave out a stop either; one
riding through it isn't, as a train runs through a closed station. A bus stop is avoided by its stop
area, both its poles. A place's Direct trains neither board nor get off there.

**Three requests per plan.** The Planner answers with about three routes, often one route at three
departures, so each plan asks it three times at once: for the quickest routes (its default), for the
**fewest changes**, which finds the walk to a station or the one bus the whole way that the quickest
three passed over (maintainer, 2026-09-30), and for the **least walking**, which finds the bus to the
station in place of a long walk there (maintainer, 2026-10-03). The answers merge in that order, and
a route more than one offers (the same lines between the same stops at the same times) appears once;
the list then orders them as always (tiers, then arrival). Any answer alone still plans the trip, and
says which request failed in the debug log; only all failing fails the plan.

**One ride to the fastest route's last stop.** To a **place** at a coordinate — a saved favorite, or
a place or postcode picked in the *To…* search — the fewest changes can trade the one bus the whole way for a train and a long walk from the station, since a walk
counts as no change, while the quickest routes change on to that same bus — so neither request offers
the bus a short walk from the rider (maintainer's report, 2026-09-30). So when the route to a place
arriving soonest by the Planner's times rides more than once, the Planner is asked a **fourth** time,
for the fewest changes to the place **via** the stop that route gets off its last ride at; each
route it offers riding fewer times joins the list as its own card, ranked like any other. The
Planner builds the whole route, its walk on to the place included, so StopDash joins nothing: an
earlier design planned to the stop and added the fastest route's walk on, and each thing the join
had to carry (which pole, the change time, a bus later moved to its line's pole) turned up in review
one at a time (maintainer, 2026-09-30). It waits on the quickest answer, so its card lands a moment
after the rest; failing, it leaves the plan as it was and says so in the debug log. A trip to a stop
never asks: its fewest changes are already planned there.

**Around severe delays.** The Planner plans as if trains ran to the timetable, avoiding only a line that
isn't running, so with severe delays on the Northern line every route it offered could still ride it
(maintainer's report, 2026-10-08). So when the route it offers soonest rides a line with **severe
delays**, it's asked once more for the quickest routes with that line's **mode** left out (the tube,
say), once a plan however many stops a complex has (to the one that route reaches), and what it adds
joins the plan: the Planner can't leave out one line, only a mode, so the routes
already planned stay, other lines of that mode with them (maintainer, 2026-10-08). The soonest route's
lines are judged by the statuses the trip holds, the rest asked for (once, on a trip's first plan); a
plan made before the delays began (one reused, or the delays started since) is planned again once when
a refresh finds them, so the request is made then. A
route riding a line with severe delays then ranks as if it got there **ten minutes** later: its trains'
predictions stand for little, so a route clear of the delays arriving about as soon goes first, while
one much sooner still leads. Minor delays change nothing. Failing, it leaves the plan as it was and says
so in the debug log, which names only the modes left out.

**One stop per end, every station of a complex.** The Planner takes a single stop or station id for
each end, not an interchange's or a folded search result's several stands, and it leans toward the
end's own mode: aimed at King's Cross St. Pancras's Underground station it offered a change onto the
Metropolitan line where Thameslink runs direct to St Pancras. So the start is one place (the rider's
position, or the *From…* station's own stop), and a picked **station complex** (an interchange,
TfL's `HUB…`) is planned to **once per station code** — never merged, since neither names nor ids
tell a station's platform variant from another station — **plus once to one of its bus stops** (the Planner walks between
stands), the requests in parallel (maintainer, 2026-09-26: the best way there whatever the line or
mode). A route that reaches one of the complex's stops and rides on — through it, or on from a change
there, to another of its stops — is dropped when another route gets off at that stop and arrives no
later by the Planner's times: planned to the bus stop, the Planner can ride through King's Cross to
Angel and bus back, where the rider would get off the first time. A detour nothing beats stays, as it
may be the only way the plan found (maintainer, 2026-09-27), and only a route the rider can see beats
one: a route riding a hidden mode never takes out one they can, and showing the mode again brings
back what it beats. The answers merge, and of the routes not riding a hidden mode, the six arriving soonest by
the Planner's timetable are timed, and an opened route besides; routes show as each answer lands. If some stations can't be planned to, the others'
routes stand and the trip says "Couldn't plan to every station", with a retry; only a whole plan is
reused. Not the complex's centre point: that ended every trip at a street address with a walk and
didn't lift the lean. An ordinary pick is planned to as picked, the Planner walking the last
stretch itself where a neighboring stop serves the trip better, so a same-named stand the search
folded into the result is reached on foot rather than lost.

**What leaves the phone:** a trip from here sends the **rider's position** to TfL's Journey Planner as
its start (maintainer, 2026-09-28): the fix the near-me list was found from, usually one the nearby
lookup has just sent TfL, though when a recent lookup of the same spot was reused from the device
the trip is what sends it; the destination goes as a stop id, or — routing to a saved favorite place (D9) — as
its stored **coordinate**. To any place at a coordinate (a favorite, or a place or postcode picked in
*To…*), the id of the stop the fastest route there gets off at goes too, as a stop to route via
(*One ride to the fastest route's last stop*), which says no more than the coordinate already does. All of it is the **Location** type already declared, sent only to TfL, no
new Data Safety type. An earlier design sent the nearest stop's id in place of the position; that
narrowing cost the routes the rider most wanted (the walk to a station rather than a bus to it) and
protected nothing TfL didn't already have, so it is not to be reinstated as a privacy measure. It is free and keyless
(within TfL's anonymous budget). The Planner is called when a trip opens without a plan under 15
minutes old (the plan is held in memory only, so a trip reopened after process death re-plans), again
every 15 minutes while the screen stays visible, on a re-locate to a new nearest stop or 150 m on from where it was planned, and once
per tap of Retry or pull on the routes: about four plans an hour for a trip left open, plus one per re-locate, Retry or pull the
rider makes, each **three** Planner calls (quickest, fewest changes and least walking, above), so about
twelve calls an hour; a trip to a place whose fastest route changes adds a **fourth** each time (above),
about sixteen. To a station complex each plan is those three per station plus three for its bus stops
(about six targets at King's Cross, two or three at a typical interchange): about 72 calls an hour at
King's Cross, within the keyless ~50 a minute. Ranking needs every listed route's live trains, so each refresh fetches arrivals
at every stop where any listed route boards a ride (its first stop and each change), once per
stop however many routes share it: the Planner offers a handful of routes, so a few requests,
under ten in practice, plus one line-status call for all their lines. Closure checks at the stops
the routes get off at share the list's few-minute cache (bus poles batched), so each such stop costs
a request at most once every few minutes, however many refreshes and routes include it. **Battery:** all of it runs only while the trip screen is visible, on the list's
existing foreground refresh tick (the 15-minute re-plan is checked on that tick, not a timer of its
own); a trip adds no background wakeup, alarm or worker, and nothing refreshes once the screen is
left. The battery change is a few extra requests per visible refresh, on a screen that is already on. `docs/PRIVACY.md` describes this before it ships, naming
the Journey Planner as a recipient of a trip's two ends together. **Play Data Safety: no new data
type.** A pair of stop ids places the rider no more finely than the nearby-stops lookup's
coordinates already do, so the **Location** type StopDash declares for that lookup (*Privacy*)
covers it, for the same purpose (app functionality) and with the same handling (sent to TfL to
answer the request, not collected or kept by StopDash); the form is re-checked before the release
that ships it.

### On the way

*In progress* (maintainer, 2026-09-26; mocked the same day). An open trip route has a **Start**
button; the trip is then **on the way** until the rider arrives or taps **End trip**, and follows
them there. Once it has arrived, its **Done** (or Back) closes the trip options it was started from
too, the near-me trip or a station's, and Lines… they were opened from, landing on the near-me
list: the trip is done with (maintainer, 2026-10-07). A screen it was opened over from its notification (Settings, say) is left
as it was.

- **"Train" means whatever the leg rides.** The step names what the rider is looking for by its
  mode (maintainer, 2026-09-28): "Finding your bus…" / "Can't find your bus", and the same for a
  coach, a tram, a boat (river bus) and a cable car; every rail mode — Tube, DLR, Overground,
  Elizabeth line, National Rail — is a train.
- **Which train.** Start assumes the **next train the rider can catch** on the first leg — the
  soonest the route lists that TfL names (its `vehicleId`) and that is due after the rider can
  reach the stop — and switches when another is seen to be the one they're on: picked by the
  rider, or (with location, below) seen moving with them. A ride goes by **any of its lines the
  trip's cards offer** (maintainer, 2026-10-01), by the cards' own rule (*Trips with a change*): the
  Planner's, and another line of the ride's mode whose route runs from its boarding stop to where it
  gets off, once checked as running from stops checked open, and not one the rider avoids. So a
  rider who boards the first train that takes them is followed on it (a Circle train along the
  Hammersmith & City, say), and the trip never follows a line the cards wouldn't offer. The trip
  checks the lines as the cards do whenever it picks a train (a line's status reused for a minute and
  no longer, its stops through the closure checks the screens share); with no other line, or none
  checked, it follows the Planner's alone. A line whose check failed (its route, its status, or one
  of its stops couldn't be read) isn't followed, but it may be running, so that's said wherever it
  could have changed what the trip shows: no train found while the board lists a train of one that
  went unchecked is the refresh failing ("Couldn't update"), not no train; a train found is followed
  all the same. At a bus stop pair (a road's two poles), another of the ride's lines may stop only
  across the road from the Planner's pole, so the trip reads the pair's other poles' boards as well,
  but only a pole serving a line of the ride's mode whose route runs the ride from there (or whose
  route isn't known): usually none, so a plain pair costs nothing more. A train counts only on the
  board of the pole its line boards at, since a line's buses across the road are its way back. A pole
  that should be read and can't be, or a pair whose poles can't be looked up, is said the same way
  ("Couldn't update"), never taken for no bus there. Each train is
  judged against the ride as its own line runs it, by its
  own stops between: the Metropolitan from Baker Street to Finchley Road passes the Jubilee's stops
  without calling. Its line is kept with the trip, since TfL answers for a train on its own line
  only, and the step to board names it ("Board Circle at …"). A train is told by its line as well as
  TfL's id for it, which is each line's own. A change picks the next leg's train the
  same way once the rider has got off and walked; a change with no walk of its own shows its time
  first, as the route does ("3 min to change"). A route that starts with a walk shows the walk
  first (maintainer, 2026-09-27), and its first ride's train is picked once it's done. The walk
  from where the rider is to where the route starts comes first of all, as the route shows it,
  whether the route starts with a ride or a walk of its own. A walk to a ride's boarding stop ends
  when the rider is **seen there** (maintainer, 2026-09-28): with location allowed, each refresh on
  such a walk takes one precise fix, and a rider seen at the stop is there, so the ride's train is
  picked from then. "The stop" is every place the station can be walked into: the point the Planner
  gives, the station's own published point and each entrance TfL lists for it (maintainer,
  2026-09-28), whichever are known (the Planner often gives no point), since a big station spans far
  more than one point and the Planner can place it 200 m from the entrance a rider stands at. A
  rider is at a point within 100 m, since it can sit well inside a big station, but at an entrance
  only within 50 m, since an entrance is the door itself: 150 m around every point and entrance of a
  big interchange told riders still in the streets around it that they'd arrived (maintainer,
  2026-09-29). Both count the fix's uncertainty. The entrances
  cost one TfL request per station walked to (a Tube, Overground, DLR, Elizabeth line or rail
  station; a bus or tram stop has none and costs nothing), asked with the first fix and kept for the
  process; a fix gone stale while TfL answered isn't acted on, and a failed read is asked again on the
  next refresh, the walk ending as before until then; without a fix, the walk runs its
  estimated time as before. The estimate errs long on purpose (it grays trains a rider might miss),
  so it mustn't hold a rider already at the station on "Walk to…". Nor one already past it: a fix
  never catches a rider underground, so one seen further from the boarding stop than they could be on
  foot (the walk's planned time and the time since, at a brisk 2.5 m/s) and **seen along the ride**
  is on board, as when waiting for its train (below), not still told to walk there. The **walk to the destination**
  (the trip's last leg, with the destination placed) ends when the rider is **seen there**, within
  100 m with the fix's uncertainty counted, not on its time (maintainer, 2026-10-03): ending on its
  time said "arrived" to a rider still a few hundred meters away. Never seen there (indoors, a
  destination placed off its door), it ends 10 minutes past its time, so neither the trip nor GPS
  runs on. Each refresh on it takes one precise fix, past its time too — up to 10 minutes more of
  fixes than before. An unplaced
  destination can't be seen, so its walk still ends on its time. Every walk says **how far is
  left**, straight to its end, from the last fix that placed the rider, where a ride says its stops
  ("450 m", in the distance units the app shows). It follows **each fix as it comes** while the trip
  is shown, not only each refresh (maintainer, 2026-10-04): measured on the device from a fix it was
  taking anyway, it costs no request and no extra fix. A walk just begun, before any fix on it, is
  estimated from the newest precise fix taken within the last 2 minutes (the trip's own, or the one
  the app remembers, such as the one the trip was planned from) and reads as approximate ("~450 m")
  until a fix on the walk places the rider; with no such fix, nothing. A train ride ends the same way
  at the station the rider gets off at (maintainer, 2026-09-28): from about two stops out (the
  train followed due there within 4 minutes) until the ride ends, or 5 minutes past that train's
  time (so a time gone stale, with TfL quiet, doesn't keep GPS on), each refresh takes one precise
  fix, and a rider seen at that station the same way (its placed or own point, or an entrance) is off,
  on to the next leg, its "get off soon" taken back. The train followed can be a later one than the
  rider's, still a stop away when they're already there, so the train alone can't end the ride
  then. A bus or tram stops in the street, where being near the stop says nothing of being off, so
  its ride ends on its train as before. Where neither can tell — a
  station far bigger than the one point the Planner places it at, a fix that never comes — the
  rider says so (maintainer, 2026-09-28): **Next** (with **Back** beside it, End trip set apart at
  the other end), or a tap on any step in the list, puts them at that step now, as if they'd just
  got there. A walk is one step and a ride two (maintainer, 2026-09-29): boarding it (its line's row)
  and **getting off it** ("Ride to …", said as a continuous action, as "Walk to …" is; maintainer, 2026-10-01), so the rider can say they're on
  before they say they're off. Getting off is a step for Next, Back and the card, but not a row of its
  own in the route's legs: one row per leg, the ride's row the rider's while they board it and ride it
  (maintainer, 2026-10-03). A **change on foot** — a walk between two rides
  whose two ends are within 200 m of each other — is no step at all (maintainer, 2026-10-03). A
  rider counts as at a stop within 100 m of it, so at 200 m the areas round the two ends meet and
  location can't follow the walk from one to the other: "walk to" says nothing that can be tracked,
  nothing tells when they reach the platform, and the ride's card names the stop or platform to go
  to. Hammersmith's two stations (about 150 m apart) are one change; Stratford to Stratford
  International (about 500 m) stays a walk the rider is followed on. Within one interchange of the
  bundled index the bar is 290 m: its stations 200–290 m apart (Paddington's lines, London Bridge,
  West Hampstead, Canary Wharf, Seven Sisters) are changed between mostly indoors, where location
  can't follow the walk predictably, so as a step it would only end on a tap; a bar that high
  everywhere would also skip a walk along the street between two places (Aldgate to Aldgate East,
  Bayswater to Queensway). So every change at Paddington is one change, while King's Cross to St
  Pancras (about 300 m and more, one interchange) stays a walk. The ends are placed by the
  Planner's own positions, else by the bundled station index; where an end can't be placed, a walk
  from and to the same name ("Stratford" to "Stratford") is a change on foot and any other a walk. Which walks are changes is decided once,
  when the trip starts, from what's on the device (no request), and kept with the trip, so its steps
  never change on the way or across a restart; a trip kept by an older build goes by the names. The
  route page shows the same changes, decided the same way in the background as the routes come in.
  The trip goes
  straight from the ride before to boarding the next; until the walk's time and the change after it
  are up it reads as that change ("Change to ‹line› at ‹place›"), as a timed change between two rides
  does, so no train is followed before the rider can reach it, then as boarding. One before the first
  ride is no change, so it stays a walk step. Those minutes also
  say which trains are in reach, and the route page shows them as the change's "N min to change". At a boarding step, a walk's time runs from then and a ride's train is
  picked at once from then; at a getting-off step they're on board the train followed (the next they
  could catch, as the trip assumes, or with none yet one at the platform when they said so, due within
  a minute of it; with none there it says it can't find their train rather than name a later one). A
  train followed that is still more than a minute from the stop can't be theirs, so the one at the
  platform is picked instead, the same way. The ride is counted from its train's
  calls with the boarding stop behind them, and never taken back as left behind: that check only
  second-guesses the trip's own assumption, not their word. A "get off soon" said for the leg left is
  taken back; Back, or a tap on an earlier step, goes back to it, so a mistaken tap can be undone.
  Back straight after Next moved them off a ride they were on puts them back on it as it was, on its
  train or still on none named, rather than looking for one afresh at a stop they left minutes ago
  (while the app keeps running). Next, Back or a tap never moves the trip straight to
  arriving — past the last leg, or onto a closing walk of no length — so Next is off there (off, not
  gone, so the buttons never move): arriving forgets the trip, which nothing could undo, and End trip
  is already the way out there. A move that can't be saved on the phone isn't made, and the screen
  says the trip couldn't be saved: a trip restored after the app dies is always the one on screen,
  so its "get off soon" is never at odds with it. While the rider walks to a ride,
  changes onto it or waits for it, **the trains at its boarding stop that take them on** are listed
  right under the card at the top, before the route's legs (maintainer, 2026-10-03: the board the
  rider is heading for, not one further down), drawn as the departures board draws a stop — its
  platform header, or a bus pole's letter ("Archway – Stop D", maintainer, 2026-09-29: not where the
  buses go), over the board's own outlined card, and a bus pair's other pole read with it (*Which
  train*) under its own header ("Stop E") — so they read as a board, not as another step
  (maintainer, 2026-09-28), and after its train has left until they're seen on board (above): every line of the ride's mode whose route calls at
  where they get off — judged by each train's own line route, so another branch's trains stay out —
  a row per line and terminus with its next few times, not only the train followed, a time grayed
  when it leaves before the rider can be there (as a trip's cards gray one). Once the board is too
  old to stand behind (D4) its rows stay, drawn as the boards draw an old one (each line's soonest a
  marked guess), under "Checking…", with no train off the plan offered from them (maintainer,
  2026-10-06); it shows "Loading" from the step's first frame
  until the first board is in. It costs one arrivals request per refresh during those steps, plus,
  for a line at that stop whose route isn't already held, the same route lookup a trip's cards make
  (one or two TfL requests per line, kept a day and shared with them), and for a bus, coach or tram
  stop one request for its pair's poles (its letter, and where another of its lines boards), kept
  for the process, and one arrivals request a refresh for each other pole read. All free, within the
  keyless budget, and naming only stops and lines, never the rider; a route that can't be loaded
  says "Couldn't check every line" rather than drop its trains. **A train tapped on the board opens
  its line's page** (*Line page*), with its status and its map, the ride's stretch marked where it's
  the ride's line (the ride it was tapped for, whatever the trip has done since), else the board's stop
  (maintainer, 2026-10-06: a tap rather than a "View line" item). It honors and makes the near-me
  list's dismissals as every line page does (maintainer, 2026-10-08): an alert dismissed there is named
  dismissed, its work to come dismissed is left out, and the page's own dismiss lands in the same store.
  Its status is the trip's own disruption check's, which asks about the board's lines (of the
  ride's mode, as the board shows) in the same request as the trip's, so it costs no request of its own: "Checking…" while a check under way
  asks about it, and "Couldn't check" where that check failed, left the line out or has gone stale, or
  where no check asks about it any more (the board failed, or moved on to the next ride), never a good
  service it can't stand behind (D4). The walk's check costs one GPS fix
  per refresh until the rider is seen there or the walk's estimated time is up. Below the next step
  the route's legs are listed, a ride by
  its pill and a walk by a walker in the pills' column, so every leg's stops line up. A route with a **National Rail** train offers no
  Start but says it can't be followed yet: its times come from National Rail's boards, which name
  no train to follow. **One trip at a time:** while one is on the way, a route offers **Open current
  trip** beside Start, and Start asks "Replace current trip?" before ending that one and starting
  this route in its place (maintainer, 2026-10-04): ending a trip can't be undone. A route that
  can't be followed offers only the open.
- **The card at the top** leads with the whole trip, then the step at hand, with no labels
  (maintainer, 2026-10-03): where the trip goes ("To Canary Wharf") with, at the end of its row, how
  long is left and when it gets there; then the step ("Walk to Stratford"), the one large line, so it
  reads first, over a row with a ride's stops left and the next stop ("4 stops · next Euston",
  "Next stop") and, at its end, how long
  until the step's done and when ("16 min · 08:18"), the minutes first. Where the step's time isn't
  known, its own words stay ("Finding your train…", "Updating…"); from an answer that no longer
  stands, its time stays beside "Checking…" in place of the stops. A time is
  never squeezed or cut: where a row's text and time don't both fit (a narrow window, large text),
  the text yields (the time keeps its one line wherever it fits beside the text's last stub); a
  ride's next stop shortens only as far as it must to fit beside the count: as named where it fits,
  else with its common words shortened ("next Tottenham Court Rd"), else at its shortest, and cut
  with "…" only where even that doesn't fit, the count beside it held still (maintainer, 2026-10-07); its places shortened as a board's are ("Upper Taxi Road" → "U. Taxi Rd", then
  the shortest form) and cut with a single "…" only as a last resort. The step shortens its places'
  common words to stay on one line, and wraps to a second only if even that doesn't fit (then its
  shortest form, then "…"), since a line that wraps reads slower (maintainer, 2026-10-03); the words around a place ("Walk to", "Board … at") and a
  line's name never do (maintainer, 2026-10-03).
  The app bar says StopDash: the card names the trip, so the bar needn't.
- **Time left.** On the card, under where the trip goes, it says when it gets there and how long is
  left (maintainer, 2026-10-01; moved onto the card 2026-10-03): "29 min · ~08:31". It's "~" when it's TfL's prediction for where
  the rider gets off the ride they're on, with only walks after. It's "est." when any of it is the
  Planner's ("est. 37 min · 08:39": the "est." leads, before the minutes, and it's the first thing the
  card drops when the row is too short for the trip's name beside it; maintainer, 2026-10-04): a train still to come (its time at the boarding stop, then the time on board), or a ride
  or change still ahead. Still to board, with the train followed gone by (TfL can list a bus past its
  time, or drop it without a word), not yet found, or lost, it's timed from the next train the boarding
  stop's board lists that the rider can catch (maintainer, 2026-10-03), rather than leave the card
  without a time. Like the step, it claims no time it can't take from a train: none with no train on
  that board either, while where the rider gets off is beyond TfL's predictions, or for
  a rider on board by where they were seen until their train is told, nor once the time it's counted
  from (TfL's, or the end of a walk or change) has passed without a newer one. While the last answer
  is too old to stand behind, it shows as that answer had it while the step's row says "Checking…",
  as the step's own times do.
- **Following it.** The trip asks TfL for **the followed train's calls ahead of it**
  (`/Vehicle/{id}/Arrivals`, in one request) about every 30 s while it is shown, and sooner on the
  move: while the app is open and a fix could move the trip on, each new fix as the rider moves
  refreshes it at once, at most every 10 s, since it's then that a train is named or a walk seen
  ended (maintainer, 2026-10-01: while the trip is shown, being current comes first, within
  reason). A trip started in place of another is refreshed at once, so its next ride's board shows
  without waiting out the last trip's 30 s (maintainer, 2026-10-04). Each refresh, and each Next, Back or step tapped, works the trip out off the main
  thread, so walking a long route never holds up a frame. Still due at the
  boarding stop, the rider is **waiting** for it; once it has left, they are taken to be **on
  it**, the next stop and the stops left to getting off counted from its calls — so it works
  underground with no GPS. **Taken to be is not seen to be** (maintainer, 2026-09-29): a rider still on
  the platform underground looks the same, so until they're **seen on board** — by location (at a
  later stop of the ride, or well on along it, as *sees a rider already on their way* below; the
  checks carry on after the train leaves, within the same ten minutes of the wait) or by their word
  (Next, or a tap on "Ride to …") — the step stays the ride's: **"Take the train to …"** (by its
  mode, as "Finding your bus…" is), its board of departures still under it. Seen on board, the step
  becomes "Ride to …" with the stops left and the board goes. "Get off soon" is still said on the
  train followed, and a stop or two out the step says so, seen or not: a rider underground the whole
  way would otherwise never be told. TfL predicts only about half an hour ahead, so a stop further on is
  **not yet predicted, not passed**: while the train keeps to the leg's planned stops, the stops
  left are counted from the plan and no time is claimed. Its calls moved on past where they get
  off, once it was seen due there by now, they have **got off**: the trip walks on for the walk's
  planned time, then waits for the next leg's train. An empty answer once they were seen due off
  is the same (TfL has no calls left for a train that has reached its terminus). Without that
  sighting, calls that leave the leg (a diversion, another branch) or an empty answer claim
  nothing: the rider is **lost** on it rather than moved on. A train is only followed on a leg if every stop it calls at between
  boarding and getting off is one of the leg's own, in the leg's order (not another branch, nor a
  loop's other way round); one that calls off the leg on the way is **lost**, not ridden. A bus
  leg can't be checked that way: the Planner names its stops by stop area, which the live calls (by
  pole) never name, so a bus is taken on its boarding and alighting stops alone, matched by pole: a
  stop the Planner gives only as a stop area is never matched to a pole by name (a route can call at
  two stops of one name), so such a leg isn't followed. Until its calls
  reach the rider's stop it is taken only if it shows the Planner's terminus (a short working or
  another branch looks the same until then), and beyond its predictions it is ridden with the stops
  left not counted, until it was seen due at the stop and has passed it. A followed train that neither calls at the
  boarding stop nor keeps to the leg is **lost** (the wrong train, or TfL lost it), and another is
  picked; calls that can't be fetched claim nothing (principle 1). A loop train's call at the
  boarding stop a lap later is its next lap, told by order and by how much later it has come to run.
  While a fix sees the rider **still at the boarding stop** waiting for it, a call there is the same
  one however late (a train held on its way round to them), before the time it was due there when
  first held, since the next lap would have taken them away. A rider still there after that time may
  have missed it, however late the train has been called since, which is left to the check for one
  left behind. Only the fixes the trip already takes count: none is asked for this.
- **Only a recent answer is live.** A train's time, the stops left and "get off soon" show as live
  only while the last answer is under about a minute and a quarter old. Older, or after a failed
  refresh, the step stays, its stops (or a train's due time) say **Checking…**, and the time left and
  arrival keep the step's last answer's (principles 1 and 2; maintainer, 2026-10-06), a time already
  gone by dropped, and "get off soon" withheld; with no answer for the step yet (just moved on, a
  reroute, a restart) a time or stops held from before say **Updating…** until the next one (a wait
  with no train yet still says "Finding your train…"). The card, the main view's banner, the
  ongoing notification and the watch alike; the notification, one line, says "Couldn't update" beside
  the time after a failed refresh.
- **Time left on the ride** beside its stops (maintainer, 2026-09-29): "6 stops (~6 min) · next …",
  counted to when the train followed is due where they get off, as the boards count a time; none while
  that stop is beyond TfL's predictions, as no time is claimed there.
- **Get off soon**, from **one stop, or two minutes, out**, said once per leg, titled "Get off at …": once the stop is
  next, the next step on the trip's card, the main view's card and the ongoing notification says so too,
  while the step's own row keeps "Ride to …" (maintainer, 2026-10-01). An answer too old to stand
  behind goes back to "Ride to …": the stop being next is no longer known. On board by where they
  were seen, with no train's time, one stop out is when a fix places them there, or, as a fallback for
  fixes that don't come underground, when the Planner's time for the ride, spread evenly over its stops
  and counted on from where they were last placed, says they're there (maintainer, 2026-10-04): a
  stop early or late at worst, never a time claimed. Said that way, then a fix finding them held short
  of that stop takes it back, and it's said again when they're due one stop out from there.
- **Time to board** (maintainer, 2026-09-27), from **two minutes before the train followed is due**
  at the boarding stop, while the rider waits for it: heard once for each train, so the next one is said
  for in its time when they're left behind by the first. It names the ride ("Board Jubilee at Bond
  Street") and counts down to the train in its header. **Only a live answer stands behind it**, as
  behind the trip's own times (*Only a recent answer is live*, below): each fresh answer posts it
  again, silently, at the train's latest time, and it lasts only as long as that answer is live, so
  with the app gone it comes down by itself. A refresh that fails, or an answer that can't keep it up
  (the train with no time now, or past it), takes it down at once, for good: it has been heard by then,
  and it is never brought back, so one the rider swiped away stays away.
  It comes down once the rider is on board (the train has left the stop, or they said so), another
  train is followed, or the trip ends, and never outlasts two minutes after the train was due.
- **Route disruption** (maintainer, 2026-09-29): a heads-up when the trip learns something that may
  stop a **coming leg** (the one the rider is on, and every one after): its line until they get off
  it, so the one being ridden counts too; its boarding stop until they board; where it gets off until
  they do; and where a walk ends that no ride meets (two stations a change walks between, or the stop
  the route ends at). It keeps the rider from waiting for a train that won't come. It's **named for
  what's known, not a verdict** that the route is dead ("Victoria: Part Suspended", "Example Road
  closed"), with TfL's own words for a line's alert under that (where it's diverted or shut, which
  stops), and opens the trip, whose screen shows each thing known in full, with the ride it's on
  (maintainer, 2026-10-01): a bare "Diversion" doesn't say where. Swiping the alert away leaves it on
  the screen while it's still known, and no card outlasts its evidence, as the alert doesn't. It fires on **high** signals (a coming line closed, suspended or not
  running, as a trip's ranking counts it; a part suspension or part closure **TfL places on the leg's
  own stretch**; a stop still to reach that a notice in force says is closed or moved) and **medium**
  ones (severe delays; a part suspension or part closure placed elsewhere on the line, or not placed
  yet; and **no train predicted at a change** the rider is **five minutes** or less from boarding at,
  "Jubilee: none soon at Bond Street"), and **the trip taking a branch by itself** because none of the
  plan's trains is listed (*Branches off the plan*; maintainer, 2026-10-06: the unexpected is said),
  "None direct to Morden · Change at Kennington", heard once and kept up until the rider is past the
  ride to the change, said under the trip's step rather than as a card. **High** too: the rider **seen past
  the stop they got off at** (maintainer, 2026-10-06: the one to say loudest), "Missed Liverpool Street",
  first seen from the fixes the walk or wait after it already takes: within fifteen minutes of getting
  off, at least 500 m beyond the stop (the fix's uncertainty counted against it), at or between the line's
  next few stops, and faster than walking pace since getting off, so on a train; never at a stop the trip
  goes on to anyway. Its card offers **Plan again from** where they are (the stop they're at, or the next
  their train reaches), the plan's own stops being behind them. Under it, **‹line› closed** (maintainer,
  2026-10-08) names the line they were to change onto: for a rider who stayed on because it's closed, it
  avoids that line as a route card's Avoid does (*Avoiding a line*: sticky, until its chip or Settings
  clears it) and plans again from the same stop, so the list opens without it. Beside it, **‹stop› closed**
  (maintainer, 2026-10-08: sticky) avoids the stop they went past (*Avoiding a stop*), for a rider who
  couldn't get off there, and plans again the same way. Where they went past is kept, through a
  restart, until they're seen back where they got off or at the next ride's stop, or board on (seen or said);
  fixes go on being asked for until then, or until the rider taps Keep going on it, which lets it go for
  that ride (a battery cost only while it stands); its alert,
  like every one, stands only while the trip's answer is live, and is back with the next refresh that works. Only a change is watched so:
  arriving ends the trip, so the last stop isn't yet (`TODO.md`). Minor delays and the like never alert. A **bus** line's alert
  that names stops only off the rider's part of the route is left out, and the debug log says so
  (maintainer, 2026-10-01): TfL gives a bus diversion's stretch only as prose ("not serving stops
  between 'Bank Station' and 'Moorgate Station'"), read as the route page marks it, and a diversion at
  the far end of a long route says nothing of the ride. It's left out only where every route that runs
  the ride has stops the alert says won't be served and the ride calls at none of them. Only the words
  TfL's bus alerts are seen to use count (maintainer, 2026-10-01): a stretch ("not serving stops
  between 'A' and 'B'", "the stops from 'A' (E) to 'B' will not be served", "missing stops from A 'F'
  to B 'T'", "bus stops from 'A' to 'B' will be missed", "the 'Hail & Ride' section from A to B are not being
  served"), stops one by one ("missing the stop 'A'",
  "will not serve stops 'A' and 'B'", "will miss stops A and B", "bus stop 'A' will not be served"), or a curtailment ("curtailed to
  A", "will terminate at 'A'" leave out the stops after it; "will start from stop at 'B'" those
  before it, on every route of the line, so it can only keep an alert). A stop is named as the route
  lists it, give or take "Station" and a cross street before a slash in the alert ("King William Street
  / Monument Station (G)" for "Monument"), or by the part of its listed name after the slash where the
  alert gives the stop's letter ("Moorgate Station (L)" for "Finsbury Square / Moorgate"), or as its
  sign reads, the slash unspaced and either part with "Station" ("'Bank Station/King William Street'"
  for "Bank / King William Street"; maintainer, 2026-10-02: a route lists its stops without
  "Station", so no stop quoted with its cross street had matched). Any other wording places
  nothing, and so does an alert that also says stops are missed in other words, since reading free prose for where service is affected kept
  misreading it, and a stop merely named may be an aside. And only when every stretch the alert
  gives is one of these, and only for a status about part of the route (a
  diversion, curtailment or part closure, with no route-wide words such as delays): a suspension or
  severe delays is the whole line's, whatever stretch it also names. With several alerts under way
  on the line (a bus route often has a handful at once, each about another stretch), each is read on
  its own words (maintainer, 2026-10-03): each one off the ride is left out, and each that reaches
  it, or can't be placed, is shown and alerted as **its own** card, named and identified for itself
  as its Dismiss identifies it (severity, label and TfL's words), so Keep going lets go of the
  ones seen on this ride and the others still show. One TfL scopes to the
  other way only is off a ride whose every route runs it this way, however it names its stops.
  Unknown counts as on. A tube or
  rail line's alert never is: its delays spread along the line. TfL places a part
  closure by the stops it names as affected, taken section by section: each unbroken run of them
  along a route TfL says it affects, in the order that route runs, the section's two ends included.
  So two sections apart are never read as one shutting the stretch between them, and each carries
  its direction: a section shut one way doesn't place a ride the other way through the same
  stations, nor, where the rider's direction is known, one TfL scoped to the other direction's route
  even where both run it the same way round (a bus's one-way loop). The leg runs through a section when two of its calls in a row, from where it boards
  through where it gets off, are both in it the same way round; a leg the Planner gave no stops along
  isn't placed, since its two ends alone don't say which way it runs (a branch, a loop), nor is a bus
  leg, whose stops the Planner gives as stop areas TfL's sections never name (TfL words bus alerts as
  a catch-all, which isn't placed anyway). A leg that
  only meets a section's edge doesn't (trains still run up to it); one placed elsewhere stays medium rather than dropped, as
  TfL's section may not be all a closure touches; where TfL gives no route to order the stops
  by, nothing is placed; and nor is a diversion read out of a catch-all's text (*Disruptions*), which
  ranks beside a part closure but isn't one. Every part closure or suspension under way on the line
  places, not only the one the line's warning names: two can be under way at once on different
  stretches, and the warning names whichever TfL ranks worse, which can be milder (TfL numbers minor
  delays above "Part Closed"). A planned one keeps the stretch it was planned for, so a check kept
  across the day it starts places it at once, not after the next check. One placed on the leg is what the alert says, **named for itself**
  ("Victoria: Part Closed"), whatever the line shows above it; a line already high stays as it is, and
  isn't heard again for it. One not placed on the leg is medium as any other, and behind an alert
  that never alerts (minor delays) it's still said, named for itself. An alert TfL gives no reason for has no text to tie its detail to, so
  it's never placed. The sections come
  with the alert's direction, from the one lookup of its detail (*Disruptions*), looked up again when
  the same text comes back as another kind of alert (a new severity), so the first check
  after a new alert, before that lookup lands, calls it medium; placing it on the next is an
  escalation, heard as one, though a rider who dismissed the alert has already read where it is, so
  the dismissal holds, for as long as that closure is under way, even once a milder alert shows over
  it. Dismissing a different alert the line shows doesn't hide a closure placed on the leg, which the
  rider was never shown. No train predicted at a change is read off the board at
  the stop the ride boards at (with its pair's other poles, each train on the board of the pole its
  line boards at), for any of its lines the trip would follow (*Which train*): no train
  of them listed there that its own line's route doesn't send another way, a train its route can't
  place counting as predicted, as does one leaving before the rider
  can get there (that may only be where the predictions end). With a train listed there of a line left
  unchecked (*Which train*), or a pole of the pair that couldn't be read, it may take the rider on:
  unknown, never a signal. It's a change's only, a
  ride with one
  before it on the route: at the first, the rider is looking at its board. Five minutes is well inside
  TfL's predictions (about half an hour), so a line running there has a train predicted by then. The
  board is the one the trip's screen reads once the rider walks or changes to that stop; on the ride
  before, it's read for this alone (one request a refresh, for those last minutes), and not shown. A line or stop alert counts **only where *Disruptions* would warn
  of it on the trip right now**, by the same rules, so the alert and the screen never disagree:
  planned work only from its start day, a stop notice only in its window, a line's alert only for the
  direction the leg goes (one with no direction known counts both ways), and nothing the rider has
  dismissed, which comes back as it does there. The leg's direction is learned only from the train
  the trip takes for it, and stands for the rest of that ride, through the train being dropped or
  boarded; never from the other trains on the stop's board, which lists the line both ways. A ride
  followed on another of its lines (*Which train*), or ridden on one by where the rider was seen, is
  checked as that line, the one the rider is boarding or on, its direction then not known, and its
  stops as that line's own (a bus's other pole of the pair), a part closure placed on that line's own
  stretch. **Only a fresh, successful
  answer is evidence**: a check that failed or has gone stale is unknown, never a signal, so a TfL
  outage can't read as a line suspended. **One notification for the trip**, on its own channel,
  updated in place as what's known changes: something not heard before on this trip (an escalation,
  a new reason, or a notice's new window included, as a dismissal would show it again) is heard, once, and kept on the trip and on the notification itself, so a restart doesn't sound
  it again, even one posted just before the app died; each check that still finds it keeps it up
  silently. It **lasts no longer than its evidence is current, nor than a stop notice it names is in
  force, nor than the trip's own answers stay live** (*Only a recent answer is live*), renewed by each check, so it
  comes down by itself once nothing follows the trip; and it comes down at once when nothing is known
  any more, a check fails (its own, or the trip's refresh, whose times then stop being stood behind),
  or the trip arrives or ends. Like "time to board", one gone (taken down, or
  swiped away) isn't brought back short of something new. Each refresh asks TfL for the coming lines' statuses, one batched
  request, and the stops still to reach through the closure lookups the trip's screen and the list
  share, each reused for five minutes, and a change's board as above.
- **Branches off the plan** (maintainer, 2026-10-05): the branches of the ride's line that run its
  way for a stop or more and then turn off it (the Bank branch where the ride keeps to the Charing Cross
  branch, Battersea where it goes on to Morden) are offered behind an **Other routes** button, so they
  don't crowd the board: under the board of trains that take the rider on while they walk to or wait
  for the ride, and under the step while they're on board it (riding, or on a train the trip can no
  longer place on the ride), when the board is gone. Each is a row, **grayed, its name in brackets**
  ("(Battersea)"), with the next few times TfL lists for it while there's a board. **When the board lists
  none of the plan's own trains but some off it** (maintainer, 2026-10-06; the Journey Planner's timetable
  can route a train TfL's live board doesn't list, with no alert to say so), they're the board's only live
  trains, so they show open, no button, under a warning in the error color: "None direct to …" (where
  the rider gets off: these still get there, with a change), and "Change at …" when every one turns off
  at the same stop, led by the plan's own row with a dash for its times, named by the terminus the
  Planner's train runs to, as the platform's boards say it ("High Barnet —"), so the planned train
  reads as missing, not the board as short. **The trip then takes the
  branch by itself** (maintainer, 2026-10-06), by the soonest such train the rider can catch, as Take
  this one would: the ride becomes one to where it turns off and a change there, so the next board shows
  at the change. Only from a fresh board with every train checked, before a train is followed; at worst
  the change's board lists the train the rider is already on. The warning stays under the trip's card
  until the rider is past that ride, kept with the trip through a restart, so the reroute doesn't hide why, and
  sounds once as a *Route disruption*. They're read from the
  line's route, not TfL's live labels, so a branch shows even when TfL lists none of its trains or names
  them for the other branch, as it can; route patterns that run on alike past the fork are one row,
  named by a "via" past the fork, else by where it ends. **Other lines from the platform** are offered
  the same way, each under its own pill (maintainer, 2026-10-05): a line of the ride's mode on the boarding
  stop's board that runs the ride's way for a stop or more and then turns off it (the Circle beside the
  District), after the ride's own line's rows; one that takes the rider where they get off is no row, as
  its trains are already the board's own. Taking one rides that line to where it turns off. They come
  from the board, so they're offered only while there is one: on board, the rider is on a train of the
  ride's own line. On board, only forks still ahead are offered,
  as the last stop known ahead says (kept with the trip, so through a restart), and only from an answer
  current enough to stand behind (D4): from an older one the train may have passed a fork since.
  A tap on a row opens **Take this one** under it with where the rider would change ("Change at
  Kennington", where the Bank branch's Morden trains call too); a stray tap changes nothing. Taking it
  **reroutes the trip**: a ride to where the branch turns off, then a change there onto the rest of the
  ride, followed as any route is (maintainer, 2026-10-05: like a reroute, no timing or tracking of its
  own). Waiting, the ride to the fork takes the next train that reaches it, whatever TfL calls its way.
  On board, the rider stays on the train they're on, now ridden to the fork: one that changed its branch
  on the way, or one TfL labels wrongly; a "get off soon" said for the old end is taken back. Either
  way "get off soon" is said for the fork, and the change picks the next train on. Taken while walking to
  the ride, the walk goes on. No request added: the route is the one the board already loads, kept a
  day. A bus has none. Plan again wouldn't serve here: the Planner works from the timetable and would
  offer the same direct train. The ride's planned time is shared between its two parts by stops.
- **Plan again:** while something is known wrong ahead, the trip's screen offers **Plan again from
  ‹station›**: the trip list from **the station still ahead on the route that's nearest the rider**
  (maintainer, 2026-10-02), not their raw position, to where they chose to go (a station complex
  whole, or the place). It's a stop of a ride they can still board or get off at, never one their
  train has passed; with no fix sure and recent enough, the stop the trip has them at next. The
  Planner then picks up from somewhere the trip already goes, rather than from a pavement beside a
  moving train, and the rider's position never leaves the device for it. Start there takes the trip
  on the way's place (one trip at a time otherwise opens the one on the way). The list plans from
  now, not from when the rider reaches that station, so a route leaving before then is theirs to
  pass over (`TODO.md`).
- **Keep going** (maintainer, 2026-10-03) sits beside Plan again, and alone where there's nowhere to
  plan from: the rider has read what's wrong and stays on the route. What it answers is no longer
  shown or alerted, for **this trip only** (kept with the trip, so through a restart, and gone when
  it ends); something new still is, a worse status for the same line included, and so is a line's
  other live alert that the one let go of had been shown ahead of. The alert comes down with it,
  since what it said has been read.
- **Station notes** (maintainer, 2026-10-04): a station still ahead (where the rider boards, changes
  or gets off) with a notice in force that neither closes nor moves it, a lift or an escalator out or
  an exit shut, gets a quiet card on the trip's screen under anything that may stop the trip: the
  station, then TfL's words. Never alerted, never a reason to plan again: it changes how the rider
  walks through the station, not which train to take. Checked with the route's closures, standing
  only while that check is current; one the rider dismissed on a list is left out here too. Its ×
  (maintainer, 2026-10-06) lets it go for **this trip only**, as Keep going does; it comes back once
  TfL's words for it change.
- **Where it shows:** the trip's own screen (the next step over the route), a **card pinned at the
  top of the main view**, the trip screen's own card (where to and when, then the next step;
  maintainer, 2026-10-04), its arrival without the board's next train, which only the trip screen
  fetches (the near-me list, under the top bar, not scrolling with it; a station's
  page, and a station, platform or favorite journey opened from the list, are each their own view),
  pinned in the same place over the location prompt and the "Finding stops near you…" spinner too,
  while near me can't come up or isn't up yet (maintainer, 2026-09-29: at the top, not centered
  with them). On a short window or with large text, where pinned it could crowd out the prompt's
  buttons, it scrolls with the prompt instead, at its top. And the notifications below; each opens
  the trip.
- **Kept on the device** while on the way (the route, the leg, the train followed, and the destination
  as chosen: every stop of a station complex, or a place, so the trip can be planned again from
  partway along to any of them, not just the stop the route ends at), in app storage
  that is never backed up, so it survives the app being closed; where a rider is going is theirs
  (*Privacy*), and it's forgotten when the trip ends. Its steps go to the rider's own watch, if it
  has the app (*Privacy*, the Wear OS watch sync).
- **On the widget** (maintainer, 2026-10-05: "show the departures at the next change"): while the trip
  is followed, a placed widget shows it in place of the departures: the trains at the next change,
  as the watch shows them, under the clock time it was last updated, and above them the next step
  in the notification's words where the widget has room for it as well as every train (text only,
  no line pill; maintainer, 2026-10-05). The summary is the one the
  watch is sent, kept on the phone for the widget (never backed up) and redrawn each update; it
  reads as out of date after 2 minutes without one, its trains as guessed times ("21:14?", D4), and
  the widget goes back to its departures after 15, or when the trip ends. A widget on the lock
  screen shows the trip there too.

**Notifications** need Android's notification permission, asked for when the rider taps Start;
declined, the trip still follows them on its screen and the main view's card, and its screen says
get-off alerts are off (checked again on every return, so a change in Settings shows). "Get off
soon" has **its own channel, high importance with sound and vibration**, so it can be silenced
apart from the trip's ongoing notification; it goes **five minutes after the stop** (or 15 minutes
with no time to it), so a stale alert doesn't linger. "Time to board" and "Route disruption" each have
**a channel of its own** too, alike, so any can be muted without the others. Until the foreground
service below, all three are said only while the app is open, as the trip is only followed then.
The first two ask TfL for nothing: each is worked out from the answers the trip already has.

**Pending the maintainer's Play declarations** (built, not merged until they're made; maintainer,
2026-09-26): an **ongoing notification with the app closed**, which needs a foreground service
(a Play Console foreground-service declaration), and **live location** to see which train the
rider boarded and to follow a bus above ground, which needs a location declaration. Until then the
trip is followed while the app is open. The **foreground service** (special use: the live progress
of a trip the rider started) is started from the app, on Start and on every return to it with a trip on the way, and
follows the trip every 30 s app open or closed; its ongoing notification, on a quiet channel of its
own, is the next step (or says it couldn't update) and opens the trip. It stops itself once the
rider arrives or ends the trip, and a process that dies takes it with it: the next opening of the
app starts it again from the kept trip. With location allowed, the same service **sees a rider left
behind**: in the five minutes after the followed train leaves the boarding stop, each refresh takes
one precise fix (used only when sure to within 50 m and taken in the last 10 s), and a rider still within 150 m of the stop a minute or more after it left didn't
get on, so the next train they can catch is followed instead (switch when seen). It also **sees a
rider already on their way** (maintainer, 2026-09-29): for the first ten minutes they wait for a
ride's train, counted from the start of the wait however many trains leave without them (a clock set
back before it ends it), each
refresh takes one precise fix (sure to within 100 m; about twenty at most, so a long wait for a
delayed train doesn't keep GPS on), and a rider seen clear of the boarding stop
(beyond 150 m of it) and either at one of the ride's later stops or 400 m on toward where they get
off, within 300 m of the ride's way (its stops joined up), has boarded, whichever train was followed. The trip then follows the train they're on: of the
trains the stop's board listed while the trip was shown, on any of the ride's lines the trip would
follow (*Which train*; a bus pair's other poles' boards too), seen along that line's own way and bound where the rider gets off (not another
branch), the one that most recently left it and isn't behind them (up to three
asked after, a TfL request each, only then; a bus at either pole of the rider's stop pairs counts).
Behind them is a train still to call at a stop they're past, or, seen at a stop, one due there more
than a minute later: a newer train is tried first, so this is what keeps it from being taken for
theirs. Two trains between the same two stops as the rider can't be told apart this way: seen between stops,
the newest past them is theirs only when each older one of its line that left after they could be at
the stop (a request each, up to three; with more, none is named) is shown ahead of them, as trains of
a line overtake: past where they get off, or calling next further on where every way the line
runs there calls first at the stop the newer calls at next (a fast train that skips it may still be
short of it). Otherwise (the same next stop, no calls, or a lookup that fails, which is said) neither
is named and they're on board by where they were seen (below), on those trains' line, until a fix at
a stop tells. Seen where
they get off, they've done the ride, whichever train took them: the trip moves on to the next leg,
as when a rider on a train is seen at their station. A train of a line the cards don't offer
(another mode's, one not running, one the rider avoids) isn't followed. With none found they're
**on board all the same** (maintainer, 2026-10-01: seen at the next stop, they're on): the step
becomes "Ride to …", the stops left counted on the plan's path from where they were seen, and no
time claimed. Seen along another of the ride's lines instead (its own way, by other stops between),
they're on board on that line, its stops counted on its own path, as a train told on it would be.
Which lines those are comes from the boarding stop's board, as on the cards; on board, it's read for
its lines whenever a fix isn't along the line they're counted on, never an earlier read (kept in
memory, or lost to a restart), since a board lists only the lines with a train predicted then; a read
that fails, or a line of it left unchecked, leaves them where they were counted and says it couldn't
update. Later fixes move that on, never back: each refresh takes one precise fix for the ride's
planned time from then and five
minutes more for a slow train, then none (battery). A train is only ever matched against a fresh
fix, never against where they were last seen (maintainer, 2026-10-01): that
says where they were but not when, so a train that was behind them could match later just by moving
on. Each fresh fix looks again for the train that took them, at or past the furthest they were seen;
a fix behind that keeps where they are and matches nothing. It looks first among the trains the
boarding stop's board listed that have left it (one of those with no calls or destination to place
it may be theirs, so then none is named); with none of them theirs, or none known (after a restart, or a train
that came and went between refreshes), on the board at the stop ahead of them,
which lists theirs arriving: one request a fix, only while their train is unknown, and only on a line
whose stops are known by id (a bus's aren't). Not where the line's routes bring trains to that stop
other than the ride's way, from the boarding stop by just its stops: one starting there, joining from
another branch, or coming by another way can't be theirs, and nothing it says of the stops ahead
tells it apart. Only a train that takes the
ride is tried there, not a short working. Trains reach that stop in the order they run, those ahead of the rider first, so it's
walked soonest first until a train its calls show behind them marks where theirs ends, or the board
does. A few lookups that find neither, or a train on it TfL no longer knows, gives no id for, or
whose calls or destination don't place it (which may be theirs), name no train: only a train shown
behind them ends theirs. Seen at a stop, one behind is still due
there, so the latest past them is theirs; between stops, one behind that has left the boarding stop
calls next where theirs does, as one ahead of them does, so only a lone train is taken for theirs. Found that way, the ride's time still runs
from when they were first seen on board. A lookup that fails is said ("Couldn't
update"), but where they were seen still moves the step on and says "get off soon", since none of it
stands on TfL. The ride's stops are placed from its line's route, and each train's from its own
line's, which the trip's cards already hold. Underground, where no
fix comes, it can't tell. The stop's position is the Planner's (or its line's route's); the fix is
only compared with it, on the device, and never logged, kept or sent.
Underground, where no fix comes, nothing is guessed. Outside those windows no fix is asked for; the
battery cost is at most ten fixes per ride after boarding, and about twenty while waiting. A rider
on board by where they were seen is the exception: one fix a refresh (about two a minute) for the
ride's planned time and five minutes more, so about fifty on a twenty-minute ride, and then none. Following a bus above ground by location is still to do. It holds a partial wake lock (screen off) for the trip, since
a sleeping phone would otherwise stall the refresh and the get-off alert: renewed for two minutes on
each refresh, so a stalled service lets go. A trip never ended is followed in the background until
four hours after it started; the service then stops, and the app still follows the trip whenever it's open.
A kept trip it can't read is read again for up to 10 minutes, since its age isn't known until then.
If Android refuses the service, or it fails, the trip's screen says it updates only while the app is
open (never failing silently); each opening tries the service again.
The battery cost is that, the ~30 s train lookup while shown, and with location, those few precise
fixes; and with the app open, location every few seconds while a fix could move the trip on and the
rider moves, with the screen already on (none standing still, none once no fix is wanted), and the
lookup up to every 10 s instead of 30. Nothing new leaves the device but the followed train's TfL id.

### Disruptions

A departure time is worse than useless if the service is cancelled or the stop is
closed — showing the number alone is the "quietly wrong" failure (see *Engineering
quality bar*). So stopdash surfaces, for watched stops and their lines:

- **Line status** — minor/severe delays, part-suspended, suspended (TfL line status).
- **Stop closures and stop-level disruptions** — a closed entrance, a moved stop.
- **Cancellations** of specific predicted services, where TfL exposes them.

**The home screen has a disruptions row** (maintainer, 2026-10-05) under the favorite places' chips,
so Home and the favorites keep the top, scrolling with the list as they do: the trip's row ("Disruptions:", each disrupted line's pill, then "Checking…",
"Unknown:" with what couldn't be checked, or "None"), always one line high, the pills that don't fit
counted as "+N", with no chevron. The pills go worst first, the rider's own lines (those near them, those of their starred rows
and journeys, and those near their favorite places) ahead of the rest when as bad, then by name, a route by its number (the 43 before the 134),
so "+N" takes the mildest first (maintainer, 2026-10-07:
it put the rider's own first whatever their severity, a far suspension behind a near minor delay); a
dismissed line is never a pill. Its page goes in the same order (maintainer, 2026-10-05). It covers the rider's own lines (maintainer, 2026-10-06: any other line only by choice) — each line of their favorite journeys wherever its stop, asked about in the list's same line-status request whatever the stops' departures did, so a far journey not yet fetched is judged too, and each starred row's line while its stop is in the list, since a star ranks only there (Codex, #640) — every line of each station within the walking reach of a favorite place, wherever the rider is (maintainer, 2026-10-07: the lines of
home, work and the rest, so a disruption there shows before setting out; stations only, from the bundled station index, so it costs no
request; bus routes there are a planned setting, off by default), every line the rider chose (below; none by default) and each line with a departure from a stop within the walking
reach (500 m, the near-me list's eager radius); on the watched list, which has no distances, every
watched stop's lines. A line the list shows goes by the list's own check; the other chosen lines are
asked in the list's same line-status request and share its reuse window, so the row costs no request
of its own. A line with no current check is never called a good service; a chosen line whose last check
has aged out while a refresh is under way (back from the background, say) reads "Checking…", not
"couldn't check", until that refresh answers (maintainer, 2026-10-05). A tap opens the trip's lines
page for those lines (*Trips with a change*), where one no longer near since it opened says so.
Where the row shows, it takes the place of the list's "Couldn't check for disruptions" banner
(maintainer, 2026-10-05), so nothing comes and goes over the list: a line on the list whose check
didn't answer, however far its stop, is named after "Unknown:"; a stop whose closure check failed is
named on its page; and what can't be named (a departure with no line to check) still reads "Unknown".
**Disruptions summary**, second in Settings under the favorite places, says what the row does ("Show
alerts and delays on the home screen", maintainer 2026-10-06: the name alone doesn't say it), or "Off",
and opens its own page, since its settings control only the row (maintainer, 2026-10-05); what the row
covers is set and seen there. The lines page's menu opens Settings on that page too, its Back going up
to Settings. There a **Show on home screen** switch turns the row off (on by default;
the banner comes back with it off); the row waits for the stored choice, so one turned off never
flashes up. Under it, **Always include** picks the lines
covered whatever's near (maintainer, 2026-10-05; lines one by one 2026-10-06): a chip per line, under the near-me list's mode groups (maintainer, 2026-10-08): **Underground** (the Tube's lines,
the DLR and the Elizabeth line), **Overground** and **Tram**; no chip picks a whole network (maintainer, 2026-10-07: the row leans on the lines near the rider and their favorites', so nobody should need every line), and one turned off under a network an older build chose leaves the rest picked; none on by default (maintainer, 2026-10-06: every one was, which filled the row with lines far from the rider's own) (the chosen lines ride the list's one status request, splitting it
only when many nearby lines push it past TfL's length limit); the lines near the rider and near their favorite places are always included, a choice of none leaving them alone, and the page says so
("Favorite places and lines near you are always included") with a **Favorite places** button that opens them, Back returning to this page. A
line chosen with no current check reads "Checking…" while it's asked about, at once
on the choice (or on turning the row on), not at the next refresh: one line-status request at most,
current verdicts reused (maintainer, 2026-10-05); never a verdict it can't stand behind; one chosen again before any refresh since may show the
check it still has. With the row off, the chosen lines aren't
asked about at all.

A disrupted line is always kept flagged — its countdowns are never shown as verified-clean
(principle 1). The chip's label is TfL's own wording where it names the disruption ("Part
Closure", "Suspended", "Severe Delays"), and a concise label recovered from the free-text
reason where the wording is only TfL's vague bus catch-all "Special Service" — which names
nothing on its own, the real state (usually a diversion) living only in the text. So a
diverted bus reads "Diversion"; one the text denies or says has ended ("not diverted", "the
diversion is no longer required") isn't named. A vague status whose text yields nothing better falls
back to "Service Alert" — never the meaningless "Special Service" — rather than being hidden.
On a line's page, **each station the alert's text names carries a ⚠** after its name, and **beside
the alert's chip, each run of marked stations is named by its ends** ("Diversion  Moorgate to
Monument", or one station alone by its name), so where it is reads next to what it is, compactly: the
stop list's ⚠s give the detail (maintainer, 2026-09-26; runs 2026-10-01) — a first guess at the stretch it
affects, since TfL gives that only as prose. A station matches without the place TfL qualifies it
with ("Stratford" for "Stratford (London)") but not inside a longer station name on the same page, and a
bus stop matches on its own name, not its cross
street, and destinations in a "towards …" list, or in a rerouting's "trains will go to …" / "will travel to …" list
(joined by "and", "or" or "/"), are not taken as the affected stops: they're where services still run to, so a marker
there reads as the one stop the alert says is fine being hit (maintainer, 2026-10-03). Only where services do go:
"trains will not travel to …" or "do not go to …" names a station the alert is about, and a rider's journey ("customers
travelling to …") says nothing of where services run, so both stay marked, as does
where trains **terminate**, since a ride is cut short there. An alert
written all in capitals carries no case to tell names from words, so it marks every station it
names, destinations included — judged a clause at a time, so an all-caps clause stays generous even
when the alert goes on in ordinary case (maintainer, 2026-09-26): a marker too many beats a missed stop. A page with no
stop list to show — no train to follow (a suspended line), or a stale snapshot whose train-specific
list is withheld — loads the line's stations just to **name** the ones the alert mentions beside the
chip, and lists none: which direction or branch to list would
be a guess on a line with nothing running. A name counts as a place only where it isn't a line, a branch or a holiday of the same name
("Victoria line", "Bank branch", "Bank Holiday"), and only as a capitalized whole phrase; the guess
errs toward marking, since it only adds a marker and never hides or reorders anything. **Where the
alert gives a stretch** — "between Oxford Circus and Euston", "Oxford Circus to Euston" — the
stations between the two named ends on the train's stop list carry a ⚠ too (2026-10-01), and the
run beside the chip spans them. Every stretch the alert gives is marked, even
one a sentence says still runs ("Trains are running between Morden and Kennington only"): telling
the two apart from TfL's prose is open-ended, and one marked too many costs a glance (maintainer,
2026-10-01). Not where an end isn't on the page, since what lies between off it isn't known. Every
stop a trip reads as not served (*Route disruption*: a bus alert's stretch, the stops it misses one by
one, the stops a curtailment leaves out) carries a ⚠ too, so the page and the trip read an alert the
same way (maintainer, 2026-10-01). Where the train's list marks no stop, the run beside the chip is
named from the whole route the list is part of (maintainer, 2026-10-02): the list starts at the
boarding stop, so a stretch before it would otherwise go unnamed. The
compact chip is a follow-up (`TODO.md` Phase 3).
**A bus alert wholly behind the stop flags nothing** (maintainer, 2026-10-02): a diversion a bus
from here has already passed says nothing of the buses here. It's read as a trip reads an alert off
a ride (*Route disruption*), with each route through the stop, from there to its end, standing in
for the ride, since where the rider gets off isn't known. So unknown keeps it on: no route loaded
yet, a route through the stop the alert gives no stretch on, or one of several alerts. Its row
carries no ⚠, and the route's page tells it muted, where it is and its prose ("Diversion before this
stop: Bank to Moorgate"), never claiming the line is clean. The routes come from the route page's
own cache: one request a day for each bus line at the shown stops with an alert that could be placed.
This applies to the in-app list, a platform view, the journey cards and the route page, and to the
widget and the watch too, which have no routes: the near-me list keeps each verdict it reaches (the
line, the alert by its full words, the stop and the way) for them, and they apply it where they read
their stored departures, as they do a dismissal. The verdicts kept mirror the list as it is: one at a
stop it no longer shows goes, so the app never keeps where the rider has been, and so does one on an
alert no longer on its rows, so the same words back later flag until the app places them again. A
surface applies, and the watch is sent, verdicts only for the stops it carries. The widget's own
refresh places the alerts it fetches the same way, at the stops the widget then shows, with the routes
the app already holds and no request of its own, so one that comes up while the app is closed needn't
flag where it lies behind a stop. It counts as checked only the lines it asked TfL about, those TfL
left out of its answer included, and when it keeps what it found it speaks only for the stops it placed
at that are still shown, and for the lines whose stored check is still its answer: a verdict anywhere
else is the app's to keep or drop, so a refresh that runs while the app moves the list on, or checks a
line again, can't undo the app's verdicts. A verdict counts only for the alert it was reached
on, and only while that's the line's sole alert. It goes as soon as the app weighs that alert at that
stop again and no longer finds it behind (a refreshed route, say); one the app doesn't weigh again
stands a day after it was last reached, the life of the routes behind it. Work whose day has come
counts as the alert the app placed, on its own words, where it's all that's under way; beside another
alert it flags, as it does in the app. So until the app has placed an alert at a stop, those surfaces
flag it, as before.
**Work that hasn't started yet is noted, not flagged** (maintainer, 2026-09-28): a closure next
month says nothing about today's buses, and a ⚠ for it trains the rider to ignore the ⚠. Neither of
TfL's fields can tell: `isNow` reads `false` even for planned closures in effect (it marks
"unplanned", not "current"), and `validityPeriods.fromDate` is when the alert was *posted*. What TfL
does say is the alert's kind, and only one TfL files as **planned work** is read for a later start: a
real-time or information alert is happening now whatever dates its text mentions ("suspended until
further notice; replacement buses from 13 October"), and one with no kind counts the same. For
planned work the start is read from the alert's own text — its **first** date, and only when the text affirmatively
makes it a start: a start word before it ("from 13 Oct", "on 10 October") or the first of a range
("12-17 October"). Any other first date — "until 23 November", "expected to finish by 13 October",
wording not foreseen — is work already under way. A year TfL leaves out is the one nearest the day it posted the alert — work is
announced around when it starts, often just after it has. When the start is a later day, the row carries a muted calendar icon instead of the ⚠ (a date to come; the circled "i" it replaced read as a clock, maintainer 2026-09-30) and keeps its
countdowns as they are, and the route's page lists the alert under its chip with the day it starts
("From 13 Oct"), each with its own × to dismiss it like any other alert. Anything the text doesn't date plainly — no date, an end first, a shape not
recognized — counts as under way, and work starting later today already counts: the guess errs
toward the ⚠, since a disruption flagged a day early beats one hidden while it runs. The start is
judged against the day the rows are drawn, not the day the status was fetched: a status kept past a
failed check turns its calendar into the ⚠ once the work's day comes. Dismissing the calendar puts away the
notice, not the disruption: on the day the work starts its ⚠ shows as it would for any new alert,
and dismissing that is a second, separate choice. The widget and the watch (its tile and app) carry
the same calendar beside a row's first countdown, taking no line of their own, for the soonest work
still to come on its line, and follow the app's dismissals and its turn to the ⚠ on the work's day,
redrawing at that midnight:
the line's stored check keeps the work to come with the rest of its status, aged out with it.

**A line alert is shown only on rows travelling the way it affects** (maintainer, 2026-09-28): a
northbound diversion says nothing about the buses heading south, and flagging them sends the rider
reading about a stretch they won't touch. TfL names an alert's direction only in its detailed status
response, ~40× the regular one, so the refresh stays plain and each new alert's direction is looked
up once in the background and remembered by its text while it's shown (reworded text, or the same
text back after it was gone, is a new alert: TfL reuses wording for a later closure). A lookup that
fails is tried again a minute later, then after twice as long each time, up to half an hour, so TfL
failing it over and over costs a detailed response every so often, not one every refresh; a new alert
on the line is still looked up at once, its wait starting over. Until that
answer arrives, or if it fails, or where TfL scopes an alert to no direction or a row has none (most
rail predictions), the alert shows in both directions, as before — the split only ever hides an
alert TfL itself says is for the other way. The same answer names the stops each alert affects, kept
with it, section by section, for a trip on the way to place a part closure on a leg (*On the way*):
an entry's stops and directions stay its own, apart from another with the same text that TfL ranks
differently or files as planned work, so a closure planned for later never places one under way, and
a closure one way never counts for minor delays the other way worded the same; and the same text
come back as another kind (a new severity) is looked up again, once. A trip's cards follow it too, by the direction each ride's
trains are seen going: a line not seen along its ride yet, or seen going both ways, keeps its
line-wide alert. The widget and watch do the same: the widget's
snapshot keeps each direction's status beside the line-wide one, the watch receives them with
dismissals applied per direction (dismissing one direction's alert leaves the other direction's
marked), and the widget's own refresh fills the same remembered answers. Until a lookup lands, a
line's dismissals aren't pruned by that refresh, since an unsplit status can't show a one-way
alert has ended.

A stop notice, by contrast, is shown **only while its TfL window (`fromDate`–`toDate`) covers
now**, checked against the render clock: TfL lists a scheduled stop closure hours ahead ("Bus Stop
Closed" at breakfast for a 10:00–15:00 closure), and a card claiming the stop is closed beside its
live, catchable departures is itself quietly wrong (maintainer, 2026-09-23). The departures are
untouched either way. A notice with no date, or one that can't be parsed, counts as current — an
unreadable window never hides a closure.

**On the near-me list a stop notice is drawn with its place** (maintainer, 2026-09-26), at the
place's distance like any section, and **at most once per interchange** (the fold below):
- A notice filed against **one bus pole alone** ("Bus Stop Closed" at Stop E) sits under
  that pole's heading, above its departures: a junction-wide heading would say Stop F is shut too.
  One TfL files against several stops of the place is about the place, so it heads the place's
  own group instead (below).
- A notice about **a station or interchange** is its **own group**: the place's heading (the
  interchange, else the station) and distance, then the notice, directly above the place's first
  platform section — one heading for every platform, never repeated on each.
- **A closed station with nothing running** is that group alone, with no line or time rows.
- A notice whose wording says the stop or station is closed ([ClosedNotice] — "Bus Stop Closed",
  "Station closed…") is error-toned, with a **"Closed" chip** on its heading; any other notice (a
  lift out of service, one entrance shut) takes a quieter tone and no chip, since it doesn't close
  the stop.
- A closed place still loading as a card, or opened from a farther card, shows as its notice
  group in that card's place, so it's never drawn twice.

On the **watched list** and a platform's own view, a notice keeps its standalone card ahead of the
list (warnings lead there), headed as below.

A standalone stop-closure card is **headed by the place name — the interchange, else the stop — always
shown**, with the notice **collapsed to a single line and expanded on tap**: TfL's stop notices
are prose (a paragraph on a lift outage), and a glance surface shouldn't be dominated by one, so
the body is the notice's first line until tapped. The name heads the card because the body does
not reliably carry it: a bus "Bus Stop Closed" names no stop at all, so only the heading says
*which* one. The notice text is **cleaned for display**: TfL's escaped line breaks (a literal
`\n`) become real ones, and where the text *does* lead with the station ("&lt;Station&gt;
Underground Station: …", a tube closure), that leading repeat is stripped so the heading isn't
said twice. Whether the text names the stop is not decided by mode, so the strip is by detection
— a leading run that matches the place name — not a per-mode rule; and because TfL spells one
station many ways ("King's Cross St. Pancras" / "Kings Cross St Pancras"), the match is by the
name's word tokens, not character-for-character. It matches against **the whole interchange's
member-station names**, not just the watched stop's: TfL spells King's Cross St. Pancras a dozen
ways across its members and a notice may lead with any of them, so the alias set (resolved with the
hub name, from the hub's member stops) is what lets the strip drop a leading name in whichever
spelling it appears. The strip is **best-effort**: a spelling no alias covers only leaves the name
in the body, never mangles the notice.

A stop-closure card carries a **dismiss (×)**: these notices are the "acknowledge and clear" kind
(planned works, a moved stop, "Bus Stop Closed — use the next stop", a step-free-access outage),
so once read the user can tap them away to declutter. A dismissal is keyed on `(place, notice
text, TfL window)` and **reappears the moment the content changes** — a reworded or replaced notice no longer
matches, so a dismiss never buries a new or escalated closure (maintainer, 2026-09-22). The window
is part of that identity, so a dismiss lasts only until the stated end: when TfL extends or moves
the window the card is back at once, not after the original end (maintainer, 2026-09-23). It is
persisted (survives restart, rides Android backup like the rest of the config — SPEC *Privacy*),
one entry per place so the set stays bounded, and **fails safe**: a stored set this build can't read
reads back empty, so the worst case is a dismissed card returning, never a warning hidden. The
stop's departures still show. A dismissed closure at a place with nothing else to show keeps its
heading and "Closed" chip: dismissing hides the prose, not the fact that it's shut. A closed
bus pole that still lists buses keeps its chip too, since it heads that pole's own departures,
which may not call there; a station-wide closure's chip goes with its notice group.

**Every service alert is dismissible**, line statuses included (maintainer, 2026-09-23) — acute ones
too: the user has read "Severe Delays" and doesn't need it repeated on every glance. A line's alert
is dismissed from the route detail (× beside the status chip) and is keyed on `(line, severity,
label, TfL's reason)`: line-wide, so it clears at every stop the line serves, and back the moment the
line escalates or TfL rewords it. A dismissed line drops its ⚠ but keeps its countdowns, and the
detail says "Service alert dismissed" rather than claim a clean line (principle 1); a no-departures
status row, which exists only to carry the alert, goes with it. A refresh
prunes it only for a line whose status TfL actually returned, as for places, and a trip's own line
check prunes it the same way, so an alert dismissed on a trip shows again when it recurs, without
waiting for the list to check that line. A trip's own closure checks prune a stop's closure dismissal
the same way, and so do a trip on the way's: each stop it checked, as its own place only. A
dismissal made at an interchange or stop area, which also holds stops the trip didn't look at, is
left to the list, which looks at the whole place. Each stop's closure dismissal is settled only by
its **newest answer**, whichever screen asked: a check whose answer for a stop has been overtaken by
another screen's newer one leaves that stop to the newer one, and checks settle one at a time, so an
older "clear" landing late never lets go of a closure a newer check found back. One whose newer check
was dropped before it answered (the trip was left) still settles it. Expiring a dismissal after a
day is a `TODO.md` follow-up.

The order is **dedupe, then title, then strip** (maintainer, 2026-09-22): the near-me fold groups
by place first, on the newline-normalized-but-**not-name-stripped** text, so it stays independent
of any one member's name; the kept card then takes its heading (the interchange, else the stop) and
strips that name from the body for display. Stripping *before* the fold would split a hub's
differently-named members — see below.

On the **near-me list** one notice reported against **several stop points** is shown **once**, on
the nearest point, not once per point — and likewise on a list without distances (a searched
station's page, the watched list), where it is kept on the first point listed (maintainer,
2026-09-24): a hub-wide "no step-free access" against both King's Cross
St. Pancras and St Pancras International, or a closed bus stop reported against each pole of one
junction (reported both ways). The fold keys on `(place, normalized text)` — line breaks fixed but
the leading name **not** stripped, since that strip is per-member and would otherwise give a hub's
differently-named members different text for one shared notice. `place`
is the coarsest identity that still holds: the **interchange** (TfL's `hubNaptanCode` — both
King's Cross points are `HUBKGX`) when there is one, else a **real StopArea** (`stationNaptan`, the
id a junction's poles and a station's platforms share — but *not* the display-name fallback the
cluster otherwise uses, since two unrelated stops can share a name), else the stop alone. Folding by
that identity — not by the notice text alone — is what keeps two genuinely distinct places apart:
TfL's text does not always name its own stop (a place-less "Station closed"), and two unrelated
closures with that identical text belong to different StopAreas — or, lacking one, are kept on their
own stop ids — so each keeps its own card and no warning is dropped (principle 1). Two different
notices at one place likewise key apart and keep a card each. The fold keys on a point's **joined** notice text (a stop's several disruptions are
joined into its one stop-status row), so where points of a place carry *different sets* of notices
a shared one is not folded per individual notice — a rare residual (mostly one notice per stop)
whose per-description fix is a documented follow-up (`TODO.md`). This dedupes across the place
without merging its departures, which stay grouped per station (D8).

On a glance surface a disruption is a one-line summary plus a count ("Victoria line:
severe delays"); in the app it's the full text. A disrupted line/stop is marked even
when its predictions still look normal, because the prediction is the thing not to be
trusted — **and even when it has no predictions at all.** A suspended line often returns
zero arrivals, so stopdash retains the watched stop→line mapping independently of the
predictions and shows a line's status from that mapping; otherwise the surface would say
"no departures" for a suspended line and leave the user waiting for a service that isn't
coming — the quietly-wrong failure in its purest form.

Disruption and arrivals are separate requests, so a refresh can get one and not the
other. When the disruption lookup fails but arrivals succeed, stopdash does **not** present
those departures as verified-clean: it keeps the last-good disruption state (aged and
stamped like any other data) or marks the affected departures "status unknown", rather
than showing normal-looking times whose disruption status was never actually checked.

### Finding a line

**Lines…** (maintainer, 2026-10-07) sits in the near-me list's overflow under From… and To…, and in
the location screen's, since it needs no location: a search for any line by name or number ("299",
"Victoria", "Elizabeth"), opening the line's own page (*Line page*, below) — its status and its map.
Before anything is typed it lists the **lines opened lately**, newest first (maintainer, 2026-10-07:
recent lines rather than the lines nearby, so it needs no fix and works anywhere), or a one-line prompt
when none has been. Matching is on the device, as the station search's is (*Find a station*): a name
by its start or a word's, a number by its start then with it inside ("29" lists the 29, then 290 to
299, then 129 and N29), never loosely, so a number (or a lettered one, "N25") never brings up a route
merely sharing its characters.
Each match shows the line's pill, its name and its mode. The lines are **TfL's own list**, every
tube, DLR, Overground, Elizabeth line, tram, bus, river bus and cable car line (not National Rail's
operators, nor coaches), fetched once a day and kept on the device, so typing never waits on the
network: a kept list is up at once, whatever its age, and one a day old is renewed behind it (checked
each time Lines… comes up); a list that can't be fetched keeps the last one, and with none kept the search says it
couldn't load the lines, with Retry. A line opened here has no stop of the rider's on its map but the one
nearest them (*Folding*), and draws the whole line; its status is asked of TfL as the page opens, in detail (the stops each alert
covers) since no refresh follows to fill them in, so a part closure is drawn on the map at once; the
pill alone until it's in, and "Couldn't check" where it couldn't be or TfL gave none. While the page
is up, and on a return to the app, it asks again every 45 seconds, keeping the last answer up meanwhile;
an answer is taken down at 90 seconds old whatever is still being asked, so none older shows as current. Its
alert is dismissible there as on any line's page, and one dismissed elsewhere reads as dismissed. Back from the page returns to the search; Back from the search,
to where it was opened. A station tapped on that map opens its **details** (maintainer, 2026-10-07): its full name and how
far it was from the rider's last fix when tapped ("Oxford Circus (350 m)"; the name alone with no fix),
kept with it so the title never changes under the buttons, with
**From** and **To**, which do what From… and To… do with this stop picked. From opens the stop's own
page over Lines…, its Back returning to the stop; To plans a trip there from the stops near the rider,
Lines… stepping aside for it with the trip's Back returning to the stop, and is offered only where
To… is (the near-me list has stops to plan from).
The same details open from a station tapped on the home screen's or a near-me trip's line page, by that line: Lines…
comes up on the stop with the line's page under it, and Back from the stop (or from a line its pills open)
closes Lines… back to the page it was tapped on, as it was left. Over a trip's line page it offers no To, as a
trip from there would take the place of the one under it. Where something else is already open over that page,
its stations stay inert.
A bus stop shows its **letter and the way its buses go** under From and To ("Stop H, towards Oxford
Circus"), or its compass heading where TfL gives no letter or "towards", as the near-me list heads its
poles: from its stop area's poles, the area as the line's route data places the stop, both from the
route pages' day-long cache (the route loaded if it isn't there yet, as on a page restored after the app
was closed), so at most one request per area a day. Its line is held, blank, from the first frame, and a long "towards" is cut to that one line, so
what's under it never moves as it comes in. A station shows its **fare zone** on that line instead
("Zone 1", "Zone 2/3"), from its own TfL record, one request a station a day, kept in memory; a station
asks for no poles, a bus stop for no zone, and a river bus pier or cable car station (which have no
zone) for neither: each by its own mode, not the line's, as a station opened from another's details can
be of another (a tube station beside a pier). A stop the lookup couldn't place, or one TfL gives no zone
(outside the zones, or "NA" where zone fares don't run), keeps the blank line.
Under its zone a station says how **step-free** it is ("Step-free to the train", "Step-free to the
platform", "Not step-free"), from TfL's bundled step-free table: for the line it was opened from, else
the level every line through it meets, blank where the table leaves any of those lines undescribed
(unknown, never a guess), across every id TfL lists the station under (St Pancras's entry is under its
high-speed id), each id answering only for the lines the index says it serves, and a line unknown
where any id serving it is undescribed. Where only a lift makes those lines step-free, TfL's lift outages are watched
as the route page watches them (no new request), and while a lift it needs is out it says "Not
step-free: a lift is out". That line too is held from the first frame for a station, blank where the
table says nothing; a bus stop holds none.
Under From and To are the **lines through it** as pills, each opening that line's page in place of the
stop's (under, so the buttons never move as they come in); then the other stations of its interchange (**Same interchange**: the rail station beside
a tube one) and the stations a short walk from it (**Nearby**, within 800 m, nearest five, with how far),
each opening that station's details in place of these (maintainer, 2026-10-07). All three come from the
bundled station index, with no request, so a bus stop, which it doesn't hold, shows none. A station
opened that way that the line doesn't call at has no line to lead its board: every service is shown,
none first, and "No departures" with nothing due.
Under them is the stop's **board** (maintainer, 2026-10-07): the line it was opened from first, by
platform or pole as the near-me list draws them, then the stop's other services under **"Also here"**,
so a rider who came for the line sees it before anything else. A line with nothing due says so ("No
Northern departures") over the rest. The board is the stop's live arrivals, from the same shared cache
as a station's page, asked as the details open and again while they're up and on a return to the app,
as a station's page is, and asked again each time the details open; until they're in it says it's
loading, a failure says so with Try again, and a refresh that failed keeps the board up with a line
saying so, while a stale card withholds its times (D4). A stamp over the board gives its age, or says
its disruption checks are still out ("Checking…") or couldn't be made, so unchecked times never read as
clean; a closure or moved-stop notice heads the board, and a bus alert wholly behind the stop is muted,
as on the near-me list. A row opens its **route
page** over the details, as on a station's page, Back returning to the stop. It works as there: its
star pins the route to the top, its alerts can be dismissed, and a station on its stop list opens that
station's page in place of Lines…, Back returning to the route page as from any route page. A pin or dismissal that couldn't be
saved says so on the stop's details once the route page is closed. A star in its top bar **adds it as a favorite place** (maintainer, 2026-10-07):
the favorite-places editor opens over it, filled in as a custom place named for the station at its
position (the index's, else its stops' center, as a pick from the place search, a lookup that fails
offering a retry on its row), so the rider can still rename it or pick its icon and days before Save;
Save or Cancel returns to the stop. Home and Work are set from Settings, as before. A pin beside the star **shows it on
a map** (maintainer, 2026-10-07): the phone's maps app opens on a labeled pin at the stop's published
position (the index's, else where the line's map placed it), never the rider's fix, as a tap on a
near-me header's distance does; with no maps app it says so. The pin waits on the stop's links, so the
index's position wins over the map's, and is greyed for a stop placed by neither. The star waits on the stop's links, which carry its position. Back from a station opened from another's details returns to that one, as it
was left, and so back through the stations opened (the last ten); Back from the first returns to the line, its map just as it was,
scrolled and unfolded, Back from From's station page included; closing the line forgets it, so the line
opened again starts at its top.
Only a line page opened from Lines… opens stops so far (`TODO.md`).

### Line page

**Every line opens a page of its own from the lines page, its map on it whatever its status**
(maintainer, 2026-10-06: "tapping on any line should show the line name and the route map, even ones
with good service"). The page is the line's row as the lines page shows it, TfL's reason where it's
disrupted, the line's work that hasn't started yet under **Coming up**, then the map. Coming up
looks different from what's under way (maintainer, 2026-10-08): muted, each alert in a neutral
outlined chip with the day it starts ("From 10 Oct"), as a route page lists it, and only where
there's work to come. From the lines page, the home screen's and a trip's, it also carries the week
ahead's closures from TfL's own date-range status, which the line's current status doesn't list: one
answer per line for every page, asked once as a line's page opens,
kept three hours or until the soonest of it starts, whichever is sooner, and "Couldn't check the week
ahead" where that ask failed or its answer couldn't be read, asked again at the page's next check. Each is dismissible on its own there, as on a route page, and one dismissed
anywhere stays off every line page; a route page's line page lists the work to come the route page
does, a good service's included. A page restored after a rotation keeps it as last shown; after the app was closed and came back it
waits for the line, the work having maybe started or been called off meanwhile. It is no verdict: changed, it
comes in with the line's row, never taking the page down meanwhile as a changed alert does. A closure to come
is on the map too (maintainer, 2026-10-08): each station it will shut, every track to it closing, carries a
calendar and "No service from 10 Oct", muted, where a closure in force draws ⛔ and dashes; its rails are drawn as
they run today, and it folds as any plain station does, its fold carrying the calendar. A route page opens its line's page too, from its overflow's **View line**
(maintainer, 2026-10-06), over the route page, which Back returns to: the line's status as the route
page has it (an alert behind the stop included, the page being about the whole line; "Couldn't
check" where the line's own check failed or has gone stale, beside a status kept from before too,
the stop's closure check being the route page's to doubt, not the line's), and the route's stop
kept on the map as the rider's. It opens on the tap, the line's pill alone until its status is in.
It holds still through the same alert fetched again, but an alert that began, ended or changed, or
its check now more or less sure, takes the old verdict down at once, the pill alone until the new one is
in. A trip's ride's route page keeps where the ride boards and gets off, its stretch shown as on the
trip's lines page.

**Map.** The whole line, one station a row down the page, each branch on a rail of its own in the
line's color, curving into another where branches meet and out where they part, as TfL's own line
diagrams draw a branching line. It's laid out from the line's TfL routes, never from a picture of
the line, so whatever TfL runs is what's drawn, a new station or branch included, and it reads north
to south where TfL gives stations' positions. A bus calls at a different pole each way (across the
road, or round a one-way street): the way back is drawn too where it runs elsewhere, each of its
poles taken as the stop across the road where TfL puts both in one stop area, or names both alike
in one interchange or a short walk apart, so one place is one row, never a fork; two of an
interchange's stops named apart stay two places, each named as alerts name it. Where the
way back only passes a stop without calling (a one-way street), it's drawn through that stop rather
than round it: a fork appears only where the way back calls somewhere of its own. Never so as to draw
a stop twice. A track the bus runs one way only (round a one-way street, or to a stand the way back
doesn't start from) carries an arrowhead the way it runs, said to a screen reader too; where the way back can't be drawn, or isn't
known, no arrows, since which way the tracks run can't be told. A station two branches call at without meeting there
is drawn once on each (Euston on the Northern line's two trunks; Edgware Road where the Circle comes
round again before running on to Hammersmith), since no train goes from one to the other there and a
curve between them would say one does. A line whose routes can't be read top to bottom (a loop with
no end), or would need more branches side by side than a phone has room for, says it has no map
rather than drawing a wrong one. The route data comes from the route pages' day-long cache: loaded
when the page opens, never on a refresh path, saying so while it loads and why when it can't. A map
drawn for an alert that has since changed never stands in for the new one (a closure that has ended,
or none where one began): it says it's loading for that moment instead (principle 1 over holding
still).

**Where the alert is.** A closure TfL places (its affected sections, the part suspension's or part
closure's own) draws the track it shuts dashed in red, and a station whose every track is shut reads
"No service"; the open stations at its ends stay plain but always on the page, since they're where
the rider changes or turns back. TfL shuts a stretch one way or both and its sections say which, so a
station shut only one way reads "No service one way": trains still call going the other way, and
"No service" would say none do. TfL gives its sections only as data, so the alert the page shows, where
it isn't a closure TfL places, marks the stations its words name with the route page's ⚠, never a red
stretch: a guess from prose is shown as one (principle 1). That holds beside a closure from another
alert, so the map always shows where the page's alert is; a placed closure's own words aren't read
that way, since they name where it is already drawn and the stations it sends riders to instead,
unless none of its track is on the map (a bus's way back left off), when they are. Each closure TfL
places is taken on its own, whichever one the page shows. A closure the rider dismissed, still named
on the page beside a milder alert that stands, is drawn the same way. A closure the map can't put
anywhere, neither its track nor a station its words name being drawn, is said in a line under the
map's heading ("This closure isn't on the map"), so the map is never read as unaffected.

**An alert shows in full only where it touches the rider** (maintainer, 2026-10-06): at their own
stops, and on the stretches their trip rides on the line, each from where it boards to where it gets
off; a closure counts there only where it shuts a track the trip rides, the way it rides it. Anywhere
else an alert stays folded and gives nothing away of where it is: the stations it's
placed on fold with the plain stations around them on their track, up to the next station the map
shows (a junction, an end of the line, the rider's own), a station on its own included, and the fold
says how many and how bad without naming any of them: where a station or a track in it has no service
(one way or both), a no-entry sign on its rail and "No service" under its label ("9 stations", then
"No service"), so the diagram shows where the line is shut and the words say what (maintainer,
2026-10-08); else ⚠ after its label where an alert's words name a station. A tap opens it in full. **The
line's ends and junctions always show**, an alert on one or not (maintainer, 2026-10-06), so where the
line goes and where it parts read at a glance: Uxbridge, named in a delay along its branch, stays on
the page with its ⚠, the stations along the branch folding behind it; a Central line delay naming
North Acton and Leytonstone leaves both on the page with the trunk between them a plain run. A junction
or an end a closure begins beside stays as it would with good service, its closed track running into
the fold (Kennington, beside a closure of the Battersea branch). A fold
leading to the line's ends is still named by them, an alert in it or not: that says where it leads,
not where the alert is. Opened from the home screen, with no trip, the rider's starred stops stand in
for a ride, and so does the line's station nearest them where one is within walking reach: they stay
on the page, and read "No service" where a closure shuts one.

**Folding.** The map opens folded, so what's the rider's reads at a glance with the rest of the line
around it (maintainer, 2026-10-06). With an alert on the rider's own stops or a stretch they ride,
each other stretch of the line folds to one row naming the line's ends it leads to ("Edgware · High
Barnet · Mill Hill East", "22 stations"); a stretch between two kept stations that leads to no end of
the line shows its runs instead, each on one track, so no row jumps between two trunks. Otherwise the
line's ends and junctions show, and only the runs of plain stations between them fold ("Burnt Oak to
Chalk Farm", "8 stations"), any alert among them folding its run as above. A tap opens a fold where
it is, below what's above it, which doesn't move, showing every station it holds in one tap, never its
ends alone with the rest folded again (maintainer, 2026-10-06). "Show all stations" opens everything
and "Fold" puts it back as it opened. **The rider's own stops never fold** (maintainer,
2026-10-06): their starred stops (a starred row's or journey's end, starred), on a trip's lines page as on
the home screen's, the stops of the
trip they're riding the line on (the route page's blue dot), and, opened from the home screen, the
line's station nearest them within walking reach, reading "Nearest" (maintainer, 2026-10-06). Every line
page marks one (maintainer, 2026-10-08): where the near-me list holds none of the line within walking reach
(opened from **Lines…**, a trip, a departure, or a home-screen line with no stop nearby), the line's own
station nearest the rider's last fix, however far. Wherever the fix is known "Nearest" says how far
("Nearest · 300 m"), so the rider judges whether that's near rather than the page hiding it past a cutoff.

### Freshness

Live predictions go stale within about a minute, and no surface may present stale data
as if it were live (see **D4**). Every surface stamps what it shows with the age of the
fetch ("Just now", "2 min ago" — the app bar's stamp is the age alone, with no "Updated" to
take up room; maintainer, 2026-09-26) and recomputes each countdown from that fetch
time as the clock advances — so "3 min" becomes "1 min" between network calls without a
new request, and the numbers stay honest.

A prediction whose countdown reaches zero is **dropped from the list client-side** — it
is never held at "0 min" or shown as negative time for a service that has already gone —
so the next departure advances between fetches without waiting for one.

"Too old to trust" is **one shared policy, not a per-surface judgment**: a single
staleness threshold, applied identically by every surface, beyond which countdowns are
withheld and the surface shows "tap to refresh" instead of numbers that are probably
wrong. The exact value is a tuned constant defined in one place in code (and pinned by
tests), not in this spec — but there is exactly one, so no two surfaces can disagree
about when data has gone stale.

**How old a fetch is, is told by the device's monotonic clock, not its wall clock** (maintainer,
2026-09-29), so setting the clock doesn't make old departures read as new. So is how old a line
status check or a stop closure check is, since each vouches for what's shown only while it's as
young as a countdown would be. Countdowns stay on the wall clock the rider sees; only a fetch's or
a check's age is kept apart from it. What the phone stores says
which boot of which install its stamps are from (so a copy restored onto another device is never
taken for this one's), and the watch keeps its own clock's reading with each update it receives,
in the same write, so a stop fetched before the clock was set back reads at its real age on every
surface, however long it's held and whether or not anything is written in between.

Across a reboot the monotonic clock starts again, but whatever was fetched before a reboot was
fetched before the boot began: a stamp from an earlier boot that's later than the boot's start
can only be from before the clock was set back, so it counts as stale from the first read. For
data that doesn't record its boot (an older build's, or saved where the boot couldn't be told), and
on the watch, which is sent the phone's stamps as its wall clock reads them, data stamped **ahead
of the clock** counts as stale: a stamp more than a moment ahead (a margin for a screen's own tick,
and for a watch whose clock isn't the phone's) is treated as stale, as is a line status checked
then. Such a stop is restamped as stale and such a line check dropped. A line check an older build
stored by the wall clock is read as the wall clock gives it, which is how it was aged before.

**Whatever a read finds is kept**, on the phone and the watch alike: the first read after the
clock was set, across a reboot or not, moves what's stored into the current clock and records any
stop or line check it found stale, so no later read reconsiders it against a clock that has moved
on since. Neither comes back into trust however the clock is set after, and a fetch made since
always replaces such a stop rather than losing to its later-looking stamp. A read that finds
nothing to change writes nothing. A surface already showing it reads it again once the clock has
been set, so none keeps showing what it judged before.

- The **app** refreshes on open, on return to the foreground, on pull-to-refresh, and
  **auto-refreshes once a minute while the screen is on** (paused when backgrounded).
  The primary targets are home users and an always-on **kiosk** display (see *Non-goals*
  / D6), where a screen left open all day must keep its predictions live without a manual
  pull. A failed auto-refresh keeps the last-good departures on screen with a "couldn't
  refresh" warning rather than blanking — and once the data is truly stale (past the
  threshold) the per-row countdowns are withheld, so nothing wrong is shown as live.
  When only some stops fail, the others refresh and the warning **names the stops that
  didn't** (the nearest, the rest as a count) **and why** — offline, rate limited, a network
  error (the request didn't complete), or a server error (TfL answered with one) — so an outage
  at one station reads as that, not as the app failing somewhere unnamed. The reason shows only when every named stop failed the same way; stops that failed
  differently are named without one, rather than given a reason untrue of some. A failure in an
  opened farther-station card isn't named: the warning falls back to "some stops". A snapshot
  restored from disk names the stops but not the reason, which isn't kept.
- **The list's rows are built off the main thread**, from the snapshot, so a long list never holds
  up a frame (maintainer, 2026-10-03). While a refresh's or the clock's new rows are built, the list
  keeps its last rows for that moment, including on a return from a page over it. A list with no
  rows of its own yet (shown for the first time, or for other places) shows a spinner until they
  are in, never an empty list that would read as "No departures". So is a cold load's list as its
  stops land: each stop's merge and the part-shown list are worked out off the main thread, which
  only shows them. So is the nearby set the list is built for: the stops a lookup finds
  are picked, measured and described off the main thread.
- **Cold load** (nothing saved to show yet): the app waits up to **2 s** for every stop's
  departures (maintainer, 2026-09-26), showing its loading stamp meanwhile, so a typical load (well
  under a second to two) paints once, whole, with nothing to jump or tap. It doesn't wait on stop
  closure checks, which can take another second or more at a big station (maintainer,
  2026-10-03): the requests go out in a fixed order, every stop's departures first and each stop's
  closure check only once its own departures request has settled (a junction's poles, once all of
  theirs have), and a stop whose closure check is still out shows its departures with a small
  spinner in its heading where the "Closed" chip would be. Its route page says it's checking for
  disruptions, never "No disruptions reported", until the check is back. Past the 2 s, it shows each
  stop as soon as its departures are back, rather than a spinner until the slowest stop answers
  (one slow National Rail board shouldn't hold up the tube). A stop still out shows as a collapsed card —
  its name, distance and lines, with "Loading" where the times go — in the place it will land:
  by distance on a near-me or station list (below any starred ones), at the foot of the watched
  list, whose soonest-first order can't be known until it's in. The list never jumps under the
  rider's eyes: a card on screen when its stop lands, **with a place's loaded rows below it** (times,
  or a status such as a suspended line — anything the rider may be reading), stays a
  card, now reading "Tap to see" (a dash if nothing's running, or **"Closed"** when a notice in force
  says the stop or station itself is closed — its own wording, "Bus Stop Closed", "Station closed…",
  since a lift or entrance notice doesn't close the stop; maintainer, 2026-09-26), and a tap opens it where it is,
  even after a rotation, until the next refresh re-sorts the list. One with only cards (or nothing)
  below it opens in full, since it pushes nothing loaded the rider is reading — so the nearest tube,
  landing after the buses above it, isn't left behind a tap (maintainer, 2026-09-26). On the watched
  list a card on screen always stays a card: it waits at the foot only because its soonest-first
  place isn't known, and that place could be above what's on screen. One that
  lands off screen opens in full, and rows landing above the screen don't move what's on it. When
  every stop is back with nothing running anywhere, the screen says so ("No upcoming
  departures") rather than keep a list of dashes. Line status is checked alongside the
  departures — the lines each stop lists, once the first stop is back — so the list vouches for
  them without waiting on the slowest stop (maintainer, 2026-09-30). A line only a departure
  names, not the stop's own list, is checked once every stop is back, as is every line on the
  watched list, whose stops don't carry their lines yet. While any shown line is still to be
  checked, the stamp reads **"Checking…"** in place of the age rather than a banner, so the list
  doesn't move (maintainer, 2026-09-30); a check that comes back failed shows the "Couldn't check
  for disruptions" banner at once, since it won't be asked again before the load finishes. A line
  TfL says it doesn't recognise (Eurostar at St Pancras International, asked about on its own)
  raises no banner: no check could have succeeded, so the banner would sit on every list near that
  station and hide one that did fail. Its own row still says its disruptions weren't checked. Asked
  about with lines TfL does know, TfL answers for those and leaves it out, which can't be told from
  a known line with no status, so there it still reads as unchecked and the banner stays. A
  part-loaded list is never saved for the widget or the next launch; only the whole batch is. A
  load cut short (a relocation) names the stops it never got, like any other failed stop. With
  a saved snapshot on screen, a refresh keeps it whole until the batch is done.
- **Shared arrivals**: every stop's last arrivals are kept in memory for the process, with when
  they were fetched, whichever screen (or the widget's refresh) fetched them. A screen that needs a stop fetched within the
  last **50 s** — a trip's boarding stop the list just fetched, the list after a re-locate, a
  return to the app, or its own minute tick — shows those at once rather than ask TfL again
  (maintainer, 2026-09-26: about a minute, a little under so the once-a-minute auto-refresh never
  skips a cycle). They're shown at their real age like any snapshot, and never once stale.
  **Pull-to-refresh** is the rider asking for fresh times: it asks afresh for every stop and
  forgets the rest; the crosshairs re-locate and reuse what's recent, and a National Rail key
  added or removed forgets them too. A station whose National Rail board a screen places under one
  of its twin stop ids isn't shared by stop, since another screen may place it under the other; the
  board itself is kept once for the station, and read by every screen. Nothing is saved to storage.
- The **widget** refreshes opportunistically — on tap, on host update, and on a
  bounded periodic schedule while it is plausibly visible — and degrades to on-demand
  rather than polling hard in the background (**D5**). The spec's guarantee is honesty
  about staleness, not a fixed interval; the interval is a battery-tuning matter for
  `dev-docs`/`TODO.md`.

### When something is wrong

StopDash never blanks or lies when it can't get fresh data. If TfL is unreachable, the
rate limit is hit, or location is denied, the surface says which ("offline", "can't
reach TfL", "location off") and shows the last good data stamped with its age, rather
than an empty box or unlabeled stale numbers.

### About and open-source licenses

An overflow menu in the departures top bar opens an About dialog naming the app and its
installed version. Its one action is the open-source licenses screen — the transitive
dependency graph, and for each component its version, authors, and license identity —
which stopdash ships to meet those licenses' attribution terms (Apache-2.0 §4 among them).
That attribution is exported at build time and bundled, so the list itself renders with no
network. The full license *text* is not bundled (following the sibling repos' export, which
omits it): each license links out to its canonical text, one tap to the browser.

The dialog also credits the data sources, as their licenses require (maintainer, 2026-09-24):
**"National Rail"** for the live National Rail times (the Rail Data Marketplace license names
the organizations to attribute), **TfL** for everything else, and NaPTAN's station codes under
the Open Government Licence v3.0. A test pins the credits so a rewording can't drop one.

### Settings

An overflow-menu entry opens a Settings screen, hosted at the activity top level like the
licenses screen (an overlay whose own Back closes it) rather than through a navigation graph
— stopdash still has no nav library. Its first row opens the favorite-places editor (D9), and the third the favorite journeys (*Journeys*);
the opt-in "refresh widget every minute" toggle (D5) follows below them. The screen composable
is UI-only for the toggle: it reflects the setting and reports a
change, while persistence (a typed DataStore, mirroring the starred-rows store) and the
refresh scheduler (WorkManager) are wired by the activity, so the screen stays
JVM/Robolectric-renderable without touching Android services.

About — and so the license attribution — is reachable in **every** state that can last,
including the location gate when permission is denied and departures never resolve: it is
hosted above the gate, not inside the departures view, so a user who never grants location can
still open it, from the gate's overflow, in every state. Settings shares that top-level hosting, and
the gate's overflow offers it too (maintainer, 2026-10-02). Opening either takes the departures view
(and its background refresh) out of the picture, so nothing polls TfL behind the static
screen.

### Display size

The user can make StopDash's text bigger or smaller than everything else on the phone — a
glance surface is read at arm's length and from a pocket. The size is a **factor on top of the
system's own font scale**, not a replacement for it, so an accessibility setting made in
Android is still respected and StopDash only says how much larger or smaller it should be than
the rest of the device. It multiplies only text: paddings, icons, and touch targets keep the
4dp-grid layout, so larger text grows what is read without breaking what is tapped. The
offered range is 80%–160% of the system size — wide enough to help a low-vision reader,
bounded so a departure card's one-line countdown still lays out beside its line pill at the
top of the range (D8). The default is the system's own size (100%): StopDash follows the
platform setting until the user chooses otherwise, so the dense default layout stays as-is and
scaling is opt-in.

Two controls change the one stored size, kept in sync because they write the same value: a
**slider** on Settings and a **two-finger pinch anywhere in the app** (a pinch resizes the
*app*, not the page it happened on). A switch on Settings gates the pinch, for a user who
would rather not resize by accident. Both move the size *live* as they are used and persist
once, when the gesture or drag ends; a size arriving from storage while the fingers are down
is held rather than snapping the text out from under them. The size is warmed into memory at
startup so the first frame is already the user's size — corrected a frame later on a cold
start rather than held behind a blocking disk read (principles 3–5). The stored value is one
number and one boolean about how the app draws itself — nothing about the user, the place, or
the time — and travels with the rest of the config through Android's backup like any other
setting.

### Scroll cue

A scrolling screen marks its top or bottom edge while there is more to scroll past that edge,
and marks nothing where the list ends. Without it, a list that happens to stop near the bottom
of the screen reads the same as one cut off mid-content, so a rider misses stops or settings
below the fold (maintainer, 2026-09-26). The mark is a short fade into the background with an
up or down chevron centered over it — a fade alone proved too easy to miss, and the chevron
follows Type Launcher's scroll chevrons. It is a hint, not a control: a tap on it reaches the
row underneath, whose middle is its destination, and screen readers skip it, since their users
already scroll by gesture. It covers every
scrolling screen on the phone — the near-me list and its empty and error states, a route's
page, station search, the location gate, Settings, and Licenses. Dialogs are left as the
platform draws them, and the watch's list already scales its edges.

### Update indicator

When Google Play reports a newer version, the departures overflow (⋮) icon carries a small
red dot, and the menu gains an "Update available" item — at the bottom, so the other items
keep their positions whether or not an update is pending — that opens the Play listing — Play
does the download and install. It is a lightweight nudge, not a banner: a dot costs no row
or top-bar width, and there is no in-app update flow to shoehorn a download/restart UI into.
The loading screens also surface the same nudge as an outlined **Update available** button
at the bottom of the screen — its room kept whether or not an update is pending, so the
spinner and everything else stay where they are either way. On the **location gate** ("finding stops") and the departures
**cold-load** spinner alike, the overflow (with its dot) is there too, so the button is a more **direct** prompt than a dot
the user may not notice while waiting, not the only path to it. Either way a user sitting on a
slow fix or a cold load can act on the update without hunting a menu, and it stays a secondary
offer (outlined, at the bottom), not the screen's main action.
Availability is Play's own answer, checked in the background on each foreground (never on a
render path). The check is release-only: a debug build's `.debug` applicationId isn't a Play
app, so it would only ever fail. An inconclusive check (Play absent or erroring) hides the
dot rather than guessing — the worst case is a missed nudge, and Play still updates the app
on its own schedule regardless.

This is stopdash's one off-device call that is not a TfL request. The Play In-App Update
library (`com.google.android.play:app-update`) is **free** and its check is off every render
path (a background Play `Task`). It sends **no user data** — no location, no watched stops,
no API key: it is a Play Services query about the app's *own* update availability (the
package and installed version Google Play already knows as the app's distributor), so it
adds **no new Play Data Safety surface**. If Play is unavailable the feature silently no-ops
(dot hidden). A failed check logs the exception's class name only (PII-free) — see
`docs/PRIVACY.md`.

### Language

The app's users are in the UK, so **British English (en-GB) is first-tier**: a phone set to
English (United Kingdom) reads "Faraway favourites", "licences", "per cent". The strings are
written in US English (en-US) as the base — for tooling and parity with the sibling repos — and
every one whose British spelling or usage differs carries an en-GB override (maintainer,
2026-09-24). The other Commonwealth English locales (Australia, New Zealand, Ireland, India,
South Africa, Canada and the rest Android groups with UK English) read the same British strings
through Android's own locale fallback, so they need no copy of their own; English (United States)
reads the base. TfL's own line and place names stay as TfL spells them.

## Architecture

- **Kotlin + Jetpack Compose**: an `:app` module (mirroring simmo and Type Launcher), with
  all product logic in a pure-Kotlin **`:domain` module** (`app.stopdash.domain`) that is
  testable on the JVM with no Android, and that a Wear OS app can share
  (`dev-docs/wear-os.md`): nearest-stop ranking,
  arrival→countdown formatting, disruption summarization, and staleness
  classification live there.
- A small **`:shared` Android library** holds what the phone and a Wear OS app must do
  identically but that needs Android types: the bundled route topology and its loader, so
  both group branching services the same way, and the line-pill color rules (*Line pill
  colors*) as one pure resolver taking the line and the surface color, so a pill is colored
  the same on the phone and the watch. It also holds the stop-header titles and the choice of
  which rows and headers fit a fixed number of lines, so the watch tile shows what the widget
  would. Compose UI, the widget and the stores stay in `:app`.
- A **`:wear` Wear OS app** (a companion: it shows only what the phone sends, over the Wearable
  Data Layer, and never calls TfL). It shares the phone's application ID and upload key, as the
  Data Layer requires, and ships in the same Play listing on its Wear OS track (launched
  2026-10-02, once the package rename had landed).
- The **TfL client** (Ktor/OkHttp + kotlinx.serialization models) sits behind a
  domain interface, so the decision logic is tested against recorded fixtures, not the
  live network.
- **Widget via Glance** (Compose-style widgets that emit RemoteViews), so home-screen
  and lock-screen placements are one implementation and share rendering vocabulary with
  the app's own composables where practical.
- **Persistence via DataStore**: watched stops, per-stop line/direction filters, the
  optional user API key, and the **last-good snapshot** each surface renders from.
- **Snapshot-render, warm at startup.** Every surface renders immediately and refreshes
  in the background — it never blocks its first frame on a network call *or a disk read*.
  The snapshot is warmed into memory at startup so the first frame is the real content;
  on a cold launch after process death, when the snapshot is still only on disk, the
  frame is a **stamped placeholder** shown at once and filled in when the async DataStore
  read completes, then refreshed. The widget in particular reads only the snapshot on its
  render path and kicks off a refresh; nothing on the draw path awaits a fetch.

## Data source, cost, and reliability

StopDash's one required **data** dependency is the **TfL Unified API** — free and public; National
Rail's boards are an optional second one, used only with the user's key (below). (A
release build also makes one non-data Play Services call to check for app updates — see
*Update indicator* above; it is free, carries no user data, and adds no Data Safety
surface.)

- Endpoints: `/StopPoint` (nearby by lat/lon + stop types + radius) and `/StopPoint/
  Search` for finding stops by name, plus `/Line/Search/{query}` then `/Line/{id}/
  StopPoints` for finding a stop by line (Phase 2); `/StopPoint/{id}/Arrivals` for
  departures; `/StopPoint/{id}/Disruption`, `/Line/{ids}/Status` and `/Line/{ids}/
  Disruption` for disruptions.
- **Cost: £0.** Anonymous access is limited to ~50 requests/min; a free, user-supplied
  `app_key` raises it to ~500/min.
- **D7 — stopdash ships no baked-in key.** It works keyless out of the box, and a user
  may paste their own free `app_key` in settings for the higher limit. A shared, baked-in
  key would pool every user's traffic into one 500/min bucket and put a credential in
  the APK; a per-user key does neither. A key TfL refuses (a 401 or 403 answering it:
  mistyped, expired, revoked) is said as such: one bar atop every screen reads "TfL
  rejected your API key" with a one-tap **Clear key**, since every request with it fails
  the same way, and a screen that names its failure says the same rather than "can't reach
  TfL". One bar, not an action per screen, because many screens fold a failure into a plain
  "couldn't update". Clearing it goes on keyless from the next request; a later answer to
  the same key, or a different key pasted, ends the bar. A key change that fails to save (a
  Clear included) says so with Try again, since it would not survive a restart and the
  widget, which reads the stored key, would go on sending the old one. No request checks a key when it is
  pasted: the refusal surfaces on the next request that would have sent it anyway. A
  keyless refusal says nothing about a key and stays a plain failure.
- **National Rail (optional, the user's own key).** TfL's arrivals feed has no times for
  National Rail services (Great Northern, Thameslink, Southern…). With a free Rail Data
  Marketplace key pasted in settings (maintainer, 2026-09-24), a rail station's departures also
  come from National Rail's own live departure boards (Darwin's `GetDepartureBoard`, by the
  station's three-letter CRS code), alongside TfL's; without one, a disrupted line's status row there says "No
  key" and opens Settings (*Departures*). Like D7, stopdash ships no key. TfL's `910G` ids end in the station's TIPLOC, and
  NaPTAN (DfT, Open Government Licence v3.0) pairs each TIPLOC with its CRS, so the app bundles
  that table (rebuilt weekly with the station list) and the two join exactly, never by name.
  Where TfL lists one station under two National Rail ids, a list shows its board under one of
  them, the first whose own TfL fetch works, which keeps it while it keeps asking; a trip times a
  train from the board whichever of them it boards at. A trip also asks for each station's board
  with its trains' calling points (Darwin's `GetDepBoardWithDetails`, in the same product, so the
  same key; maintainer, 2026-10-07): a train's own stops say whether it calls where the rider gets
  off, which its line's routes can't when one runs fast and the next all stations (a London
  terminus's trains, read off the routes, mostly came out "Couldn't check"). That board lists only
  the next 10 trains, so the plain one still gives the times, and a train past it is judged on its
  line's routes as before; a train that divides is judged on its stops only where every portion
  agrees. Only a trip asks for it, not the lists, the widget or the watch: one more free request per
  station a trip boards at, each refresh, carrying the same code and key. The plain board stays the one
  every screen shares; the one with details is kept beside it, and a train's stops are worked out off the
  screen as its other checks are. If it fails, the trip says so
  in the debug log and judges every train on its routes. A board is kept with the stops' arrivals
  (*Freshness → Shared arrivals*), so it's fetched once for every screen until it's 50 s old, and
  screens asking at the same moment share that one request. A stop's arrivals are as old as their
  oldest part, so a board read from the cache keeps its own age. A
  station whose own board the service refuses takes the board its trains come on: St Pancras's
  low-level Thameslink platforms (SPL) read St Pancras's (STP).
  Only times National Rail gives are counted down. A canceled train, or one "Delayed" with no
  estimate, has none, so the in-app list shows it as a word in its place among its line's times,
  by when it was scheduled: "Canceled" ("Cancelled" in British English, which most riders see), or
  "Delayed" for one with no estimate (maintainer, 2026-10-02; it used to be left out). A word, not
  "?", which already means a countdown too stale to show. "5 min · Canceled"
  is the next two trains, the cap of a row's few times counting both. A canceled train goes at its
  scheduled time, as a timed one does; one delayed with no estimate stays while its board lists it,
  since it hasn't left (and a stale stop withholds it as any). It joins the row and
  destination its timed trains are in, on its own platform when it names one: a train named for a
  platform none of those timed trains is on is that platform's own, never drawn under another
  platform's header or hidden in a row naming none. A line, platform or destination whose every train has no
  time is drawn as its own row or route line, never as a "No departures" warning: it goes after
  the trains that are coming, since none of its is known to be, and opening it follows its own
  route, never another's train. A favorite journey's card shows one only beside a train of the
  journey to the same place, as its route to the far end goes unchecked. Nothing that times a
  journey sees it: a trip, the widget and the watch leave it out, as before. The TfL-run services a board also lists (Overground, Elizabeth line,
  and tube trains on shared platforms, such as the District at Richmond) come from TfL alone. A board's train
  joins TfL's line by the **operator's code**, mapped to TfL's own line id, not by its brand name
  (maintainer, 2026-09-26): West Midlands Trains runs as two brands TfL has no line for, and
  "Northern" would take the tube line's id. A line TfL has no entry for at all (the Caledonian
  Sleeper) is one TfL answers "not recognised" for: its route page says the stops are unavailable,
  with no retry, and its status isn't asked again that session — its rows stay unchecked, never
  clean. The optional dependency fails on its own: a failed board (down, rate-limited, a bad key,
  a garbled answer) leaves the station's TfL departures in place, its National Rail lines' status rows saying
  "No data", and is logged; it never fails the stop or blanks the list. **Cost:
  £0**, at most one request per rail station per refresh against the user's own key's limit,
  shared by every screen; an open trip adds one more for that station's calling points, so two at most
  there. A failed calling-points request leaves the plain board standing.
  **Play Data Safety:** no new data type — a request carries only a public station code and the
  user's own key for that service, sent at their request; `docs/PRIVACY.md` names National Rail as
  a recipient, and the Data Safety form and privacy-policy link are re-checked before the release
  that ships it.
- **Step-free access (bundled; maintainer, 2026-10-02).** How far each platform is step-free comes
  from TfL's open station data, not its live API: a StopPoint's `accessibilitySummary` is empty, and
  its "access via lift" flag is one answer per station, missing at many (Victoria, Warren Street).
  The station data maps each station: its areas, the level paths, ramps and lifts between them,
  joined to the street and to every platform, plus the gap and step from each platform onto each
  line's trains. A platform is step-free from the street when that map reaches it without a stair,
  and its level, per line, is **none** (no step-free route), **to the platform** (a step or gap onto
  the train), **by ramp** (staff put one down) or **level** onto the train (within TfL's 85 mm gap
  and 50 mm step, at least at a marked spot, which TfL names). TfL grades **per platform and line**,
  not per station: Westminster is level onto the Jubilee line and by ramp onto the District; Bank's
  Northern line is step-free and its Central line isn't. A route needing a lift not every wheelchair
  fits is marked, as is an entrance it must start at. A platform TfL gives no route information for
  is left out: unknown, never assumed either way. The table is built by
  `scripts/build_step_free.py`, bundled beside the station list and rebuilt with it weekly; the app
  reads it by stop and line (`StepFreeAccess`). **Cost: £0**, no request from the app for the table;
  the build downloads ~200 KB once a week. Shown on the route page's stop list (*Route detail*); the
  stop and station views and a trip's legs come next (TODO).
  - **Lifts out of service.** A platform only a lift reaches carries its place in its station's map,
    bundled cut down to what an outage can change (the street, those platforms, each lift's stops,
    and the lift-free ways between them), with each lift under the id TfL's live lift disruptions
    (`/Disruptions/Lifts/v2`) name it by. The app walks the map again without the lifts that are out.
    Exact rather than per-lift: a platform two lifts serve stays step-free while either works. One
    keyless request answers for every station; it's held for 5 minutes whichever screen asks, and
    asked only for a list with a station that depends on a lift (*Route detail*). **Cost: £0**, at
    most one request per 5 minutes (a failed one included) while such a page is in the foreground,
    through the shared rate budget; no location or user data in it. If TfL can't answer, its last
    answer stands (none before its first), and the failure is logged as a coarse class. TfL's map is
    TfL's: at one station in nine checked (2026-10-02) its live notice and its own map disagreed, the
    map having a one-way path the notice didn't allow for.
- **Reliability:** one required dependency, so if TfL is down or throttling, stopdash shows
  stamped last-good data and an offline/rate-limited notice (never a blank or an
  unlabeled stale number). Added latency lives off every render path (snapshot-render,
  above).
- **A refresh fans out in parallel, capped.** Every stop's departures go out together rather
  than one after another, through one small pool shared by the app and the widget, so a
  refresh costs a couple of round trips instead of one per request. A stop's closure check
  follows its own departures, never ahead of them (a junction's shared pole check, all of its
  poles'), so departures never queue behind a closure check and a cold load can show them while
  it's still out (*Freshness → Cold load*; maintainer, 2026-10-03). Other screens' requests share
  the pool first come, first served. The pool caps requests *at once*;
  the rate budget above still caps requests *per minute*, so a keyless fan-out larger than
  the burst is paced rather than fired at once.
- **A refresh spends the budget only where it's needed.** A stop whose departures came back
  less than 50 s ago — on this screen or another (*Freshness → Shared arrivals*), and whose
  closure check didn't fail — is carried over as it is rather than refetched, keeping its own age,
  so a retry right after a rate-limited refresh fetches only the stops still missing instead of
  hitting the limit again. The 60 s auto-refresh is past that window, so it refetches every stop
  within the walking reach (a far stop every other minute — below); a pull-to-refresh refetches
  every stop. A stop's closure check (a closed or moved stop) is
  reused for 5 minutes — closures change over hours, and the check is half of every stop's cost —
  while line status, the fast-moving signal, is reused for 90 s — so the 60 s auto-refresh
  re-checks it every other cycle and a new suspension still shows within about two minutes. Both live in memory
  only; a failed request is never reused, nor is a closure answer asked before a check of that
  stop that has failed since, so the stop is asked again. Every screen that checks stops shares these
  closure answers, and the list shows each stop's **newest answer as of when it's shown**, whichever
  screen asked: one another screen finds while a refresh is still out is shown with it, and one that
  fails meanwhile leaves the stop unchecked, rather than the refresh showing the answer it read first.
  **One closure request per stop is out at a time**: a check needing a stop already
  being asked about waits for that answer, failed or not, rather than send its own. A junction's **bus poles share one closure request**
  (TfL takes several stop ids at once); each pole gets only its own notices, so an open pole
  never shows a sibling's closure. A station keeps its own request, since its closures live on
  child platforms. Keyless, the app sends up to **20 requests at once, then 40 a minute**, at most
  10 in flight: a whole typical cold near-me load
  (the nearby lookup plus ~15-18 requests) goes out unpaced in two quick waves (2026-09-23). A flat-out minute
  can reach 60, over TfL's ~50 — only sustained heavy use gets there, a 429 shows as rate-limited,
  and fewer requests per refresh (caching) is the planned way back under.
  Each departures refresh logs its request count and timing to the on-device debug log (the
  nearby-stop lookup before a near-me load is one more request), so a slow load is diagnosable
  from real numbers.
- **Far stops refresh less often on the timer.** The 60 s auto-refresh carries over a stop more
  than 500 m away (past the walking reach) that was fetched within the last 90 s, so it's
  refetched every other minute; its countdowns stay under about two minutes old, and any user
  refresh fetches it again.
- **A repeat nearby lookup close by is answered from memory.** A lookup's stop list is reused
  for up to a day when a new fix is within 150 m of where it was made (the last four places),
  saving a request and the round trip every near-me load otherwise waits on. The list is
  re-ranked from the new fix, so distances and order are current. The entries (each lookup's
  position and stops) are kept in the app's cache directory so a reopen after the process was
  killed still benefits (maintainer, 2026-09-23); the OS never backs that directory up, and it
  never reaches a log or leaves the device (`docs/PRIVACY.md`).
- **Routes and stop areas are kept for a day.** A line's route sequence and a stop area's poles
  barely change, so each is fetched at most once a day (maintainer, 2026-09-24) and kept in memory
  and in the app's cache directory, so a route page or journey card opened after the process was
  killed shows its stops without a request. The file is read once at startup, off the main thread;
  an entry a day old is refetched and leaves the file with the next write.

## One widget, many surfaces

There is one widget. On Android 16 QPR and later, where the OS re-added widgets to the
phone lock screen, it is eligible to sit there; everywhere else it is a home-screen
widget. Both use the standard AppWidget/Glance API — a lock-screen widget is just a
widget the host is allowed to place on the keyguard — so there is no lock-screen-specific
code path to maintain. StopDash does **not** opt out of lock-screen placement (the
`not_keyguard` category). The app and its home-screen widget run on the fleet floor
(Android 14 / API 34); the lock-screen *placement* simply appears on devices new enough
to offer it.

The widget is laid out for **each size the launcher reports for it** (portrait and landscape,
say), not the nearest of a few canned sizes, so it fills its cell: a tall widget shows as many
departures as fit, and a wide one wraps a row's times under its destination only when they really
don't fit beside it. A size it hasn't laid out yet (a resize) briefly draws the minimum size's
layout, which fits any cell, until its own is ready. From **480dp wide** (a tablet's widget, or a wide one in
landscape; never a phone's in portrait) the rows go in **two columns** side by side, read down the
first and then the second, split where the two come out closest in height, with a place that runs on
into the second column named again at its top. The rows are chosen by priority for both columns'
room, as for one taller column, so a split never drops a sooner departure than one it keeps
(maintainer, 2026-10-05, from screenshots of both layouts at widths from 400 to 900dp).

The widget renders the **persisted last-good snapshot** the app writes — never the
network. It reads the snapshot once when the host asks it to update and renders from it,
so it can't stall on a fetch, and it stamps the data's age and marks it stale rather than
passing old times off as live (D4). Its rows **mirror the in-app list**: the same rows
grouped the same way — one line per (destination, branch), so a branching service's
divergent trains each keep their own countdown — and the user's **starred** services
pinned to the top (D8), sharing the domain's grouping and pinning so the two surfaces can't
drift. It shows the via-branch in the same normalized short form as the app ("Charing X"):
the label is one short form per trunk on every surface, so neither has to measure a fuller
name. As in the app, a line two nearby stops both serve shows once, from the stop the app shows
it from: the app saves with the snapshot the nearby stops' order nearest first and, for each line,
the stop its list chose — stop ids, never the distances, which together would pin down where the
rider was. So a route's two directions kept together at a stop a few steps farther stay together
on the widget too. A line the saved choices don't name (one that has appeared since the app last
worked them out) folds by the order alone, to its nearest stop. The choices follow the rider
whether or not a refresh succeeds: at each fix, and when the app starts, they're worked out again
from the saved rows at the new distances, never kept from where the rider was. (Reordering the widget's rows closest-first is not mirrored.) The app pushes an update whenever it fetches, so the
widget follows the app's last refresh rather than waking on the OS's periodic schedule
(battery). Because the widget's host never re-renders it on its own (no periodic update),
the widget also schedules **one render-only redraw at its staleness boundary**, so a widget
left untouched after the app closes flips itself to the stale treatment ("21:14?", D4) instead of
holding live-looking countdowns forever (D4) — and its header stamps the data with the **clock time** it is from
("Updated 13:00"), not an age, since Android can defer that redraw and an age frozen at the last
render would read as newer than the data is (maintainer bug report, 2026-10-05) — a single bounded wake per snapshot, not a
polling cadence, and not a data refresh (fetching new data while the app isn't driving the
widget stays deferred, D5). While the app is on screen, the widget is also redrawn from what's
stored **where its countdowns change** (a train departing, a count going down), and at least once a
minute, so they keep counting down (maintainer, 2026-10-06): a render only, with no fetch. While On the way follows a trip, the trip it shows is redrawn with each of the
trip's updates, every 30 seconds. A setting of the device's clock redraws a placed widget at once too,
since its countdowns and age are drawn against the clock as it read then (*Freshness*). The snapshot also records which of the stops it should show a
refresh asked for but couldn't get, with nothing earlier to fall back on; while any is missing
the widget says its stops are partly out of date, so a first refresh where one stop failed never
reads as complete (principle 1). **The widget shows only the stops the app last found near the
rider**: each nearby set the app resolves is stored on its own, apart from the departures (which
are saved only once a fetch succeeds), and the widget and the watch show only those stops from
the stored departures, counting a new one not fetched yet as missing. So after a move whose
first fetch fails, the last place's trains come off at once instead of reading as live until
they age (principle 1); a pinned journey from the old place keeps its own rows. Deciding this
where the snapshot is read, not by clearing the stored file, keeps it from racing the app's own
saves. **Disruptions reach the widget too** (D3): the snapshot keeps each
shown line's last status check, stamped with when TfL gave it, so a delayed or suspended line's
rows carry the same "⚠ Severe Delays" mark as in the app, and a disrupted line with no countdown
shows as its status alone, saying "No key" or "No data" where its times would be when that's why
it has none (a National Rail line, *Departures*), as the app does; the tile and the watch app
draw it the same way. A mark is withheld once its check is as old as a stale countdown (the
shared threshold, D4), never asserted on an old verdict: the widget redraws itself at the first
check's expiry as it does at the arrivals' boundary. A live countdown whose line has no current
check (never checked, the lookup failed, TfL left it out of its answer, or the check aged out)
makes the widget say "Couldn't
check for disruptions", as the app does, rather than read as verified-clean. The widget's own
refresh re-checks the lines with the arrivals. When every arrivals request fails but TfL answers
for the lines, either refresh (the app's or the widget's) stores the new checks alone, leaving the
arrivals to age: a suspension declared during an arrivals outage reaches the widget at once rather
than with the next arrivals worth saving. An app refresh with no stops of its own to check (a cold
start whose arrivals all failed) checks the lines the widget shows instead. A mark takes a line of the widget's height, and is never the line dropped
to fit another departure. Good-service verdicts are kept too, so when the app and the widget's
refresh both write, each line keeps whichever check is newer. Stop closures are still not
persisted: they have no age-stamped rendering. A line status dismissed in the app leaves the widget
and the watch too (maintainer, 2026-09-28), at once, while its line still counts as checked, so its
countdowns don't read as unverified; like the in-app dismissal it lasts only until the status
changes (a new severity or wording), which is marked again. The dismissed set is the one place a
dismissal lives: the widget applies it each time it draws and the phone each time it publishes to
the watch, redrawing and republishing when it changes, rather than copying it into the stored
snapshot. A copy there was overwritten by whichever writer saved last, and brought dismissed
alerts back. A line's dismissal whose alert a refresh saw end or change is kept for one staleness window
after, since what the widget has stored can lag that refresh, and forgetting it at once would show
the ended alert there again. Kept that way, it hides only a check made before the end was seen,
which is stale by the time the window closes; the end and the check are both timed by the
monotonic clock (*Freshness*), so setting the clock changes neither which checks it hides nor how
long it's kept. The app's own screens, which show what they
fetched, never apply it. So the same alert recurring is a new incident and shows everywhere at
once, and no widget write can race it. **Interim data source**: until Phase 2's user-chosen watched stops exist, the
widget shows the last *nearby* set the app fetched — "the stops near where you last
opened the app". Phase 2 replaces that with the watched stops; a live-refresh cadence for
the widget when the app isn't driving it is deferred (D5).

### On the watch

The Wear OS companion (`dev-docs/wear-os.md`) shows the widget's snapshot as the phone last sent
it, on a tile, a watch-face complication and a small app. It never calls TfL itself, and like the
widget it renders only what is stored, stamped with its age and marked stale rather than passed
off as live (D4). A delayed or suspended line is marked on all three as the widget marks it (D3), and each
mark is withheld at the same threshold from its own check: the tile and the app list "⚠ Severe
Delays" under the service (a suspension with no countdown as its status alone), and the
complication shows "⚠" by the line code and the status beside the line's name in its long form. A
live countdown whose line has no current check makes the tile and the app say "Couldn't check for
disruptions", as the widget does, and the tile's foot offers Refresh; the complication has no room
for that note. As on the widget, a line two nearby stops both serve shows once, from the stop the
phone's list shows it from, by the order and line choices the phone sends with the snapshot (never
the distances), on the tile, the app and the complication's default row alike, through the
widget's own fold.

**The complication** puts one row's next departure on the watch face: the line and its countdown,
with the destination when there's room. It shows the row the user picks for it in the watch
face's editor, or by default the widget's top row, starred ones first (D8). The watch tells the
phone which rows its complications show, so every snapshot keeps them even as departures
reorder; a picked stop that leaves the widget's scope drops its pick and falls back to the top row.
The watch face counts it down and moves on to the next departure by itself, from a timeline built
when the snapshot arrives, so it needs no polling. It never outlives its data:
- a stop carried forward after a failed refresh marks the time itself (`~3m`), since there's no
  room for the widget's note;
- a row with nothing left says so ("None"), or "?" when the last refresh failed;
- a stop past the staleness threshold shows "?", never an old countdown;
- with no stops at all it shows the watch face's no-data dash.

**The tile** shows the first rows that fit, favorites first. While they're fresh and complete its
foot reads **All stops**, which opens the watch app; once they're out of date, partly out of date
or missing stops left out for size, or a refresh is under way or failed, it reads **Refresh** instead (with what happened), since that's
what an out-of-date tile needs.

**The watch app** lists every row the tile would, favorites first under the widget's stop
headers, in a dense list the crown scrolls, with Refresh at the end. While it's open it
re-renders at each countdown minute, departure and stop's staleness boundary, from the stored
snapshot alone: no polling and no network, and nothing runs once it leaves the screen.

**Refresh from the watch**: tapping the tile's Refresh line, or opening
the watch app, asks the phone for one refresh of the widget's stops. The phone does the same
location-free fetch as a widget refresh and sends the result the usual way. Why and how:

- **Every request gets an answer** (principle 2). The phone answers with what happened:
  refreshed, partly refreshed, rate-limited, TfL unreachable, the user's key rejected (D7; it's
  cleared on the phone), no stops, or recent enough to skip.
  A failure shows briefly on the watch over the last snapshot, which keeps its own age stamp. No
  answer in time reads "Phone out of reach", never a blank screen or old times shown as live.
- **Debounced on both ends** (battery and the shared rate budget): the watch asks at most every
  30 s, and the phone reuses a recent fetch. Refreshes from several watches and the widget's own
  cycle take turns, so overlapping asks cost one fetch, even while TfL is failing.
- **Robust to the watch being killed**: an unanswered request and a failure's notice outlive the
  watch app's process, and the tile shows the switch to "Phone out of reach" on time without it.

**Getting the watch app** (maintainer, 2026-10-02): a watch app is otherwise found only by
searching Play on the watch, so the phone offers it to a connected watch that doesn't have it, in
two places. A card atop the near-me list asks once ("Install" or "Not now"; either answer puts it
away for good, and it waits while the telemetry question is being asked, one question at a time).
Settings keeps an **Install on watch** row for as long as such a watch is connected. Install opens
StopDash's Play page on the watch, and the phone says whether it reached it ("Check your watch", or
"Couldn't reach your watch"), since nothing happens on the phone itself. Which watches lack the app
is read from the phone's paired devices each time the app comes to the front: nothing new leaves
the phone, and a phone with no watch (or no Wear OS app) never sees the offer.

## Privacy

StopDash handles location and the set of stops the user watches — which together reveal
where they live, work, and travel. StopDash itself sends none of it anywhere except the
TfL requests that *are* the product (and, with the user's National Rail key, a rail station's
code to National Rail, below): a nearby-stops lookup necessarily sends coordinates
to TfL — **precise** where the user granted precise and an accurate fix is available,
approximate under an approximate-only grant or when no accurate fix can be obtained (see
*Finding stops*) — and a departures lookup necessarily sends the watched stop
IDs. **Find a station** likewise sends the typed name to TfL's stop search (and a later
search-to-pin would send a stop-name or line query); the query is never saved or logged. The
places picked from it are remembered for its *Recent* lists (*From…*'s and *To…*'s apart), and each starred row's place (so
a star is listed by name), in the app's no-backup storage: never logged, sent, or backed up. That is inherent to each feature and disclosed; precise location is the
Play Data Safety type the nearby action may collect (and so declares), not a claim that
every fix sent is precise.

All of stopdash's persisted config — watched stops, per-stop filters, row stars, starred
journeys, any saved favorite destinations, the user's `app_key` — and the last-good snapshot (all but
the crash-report opt-in, which is per install, and the rows paired watches' complications show,
relearned from the watches still paired) travel through
**Android's own backup and device-to-device transfer** — stopdash allows
both, deliberately, so a phone swap keeps the user's setup rather than losing it
(maintainer, 2026-09-18; the fleet's "never lose the user's work" over a literal
never-leaves-the-device wording). This is the platform's user-controlled channel tied to
the user's own Google account, not an off-device channel stopdash adds: cost £0, and no
Play Data Safety change (Android Auto Backup is a platform feature, not data stopdash
collects or transmits). The guarantee is therefore precise, not absolute — the only **user data**
*stopdash* sends off the device on its own goes in its TfL requests, and its National Rail
requests when the user has added a key (*Data source*) (its one other network
call, the release-only Play update check, carries none — see *Update indicator*); the user's
own backup/transfer carries their config under their control; and a **consent-gated bug
report** (see below and `docs/PRIVACY.md`) carries the exact location, per-stop distances, and
a screenshot of the reporting screen the user explicitly agrees to share on a screen they can
decline; and, only while the user has opted in, **crash reports and usage stats** go to Firebase
(below) — no coordinate, stop, journey or key, but app interactions, device details and an
IP-derived region.

No **user data** else leaves the device unbidden — no analytics over the user's stops or
movements, no coordinate, stop list, or API key in logs, and no user's coordinate, stop list,
or API key in commits, PRs, or fixtures (hub and interchange data is fine there, *Testing and distribution*); the
consent-gated bug report is the one user-authorized exception, and it discloses exactly what
it carries before anything leaves. Without the opt-in, the one off-device call that is not a
TfL request is the release-only Play update-availability check (*Update indicator*): a Play
Services query about the app's own version that carries no user data and adds no Data Safety
surface. With it, Firebase is the other. The on-device
debug log carries coarse diagnostics only: a stop ID, a line id, an HTTP status, the
destination TfL shows for a train or bus whose route couldn't be checked (its terminus, never
where the rider gets off: the ids alone can't say which working the routes don't model;
maintainer, 2026-10-04), a failed Play update check's exception class, or a slow request's timing (who it went to, its
endpoint with every identifier left out, its connection and answer times, a failed connect's
address family and exception class, a wait for a request slot), or, on a ride followed by where the
rider is seen, how far along it each fix placed them as a stop count ("stop 5 of 9") with the fix's
accuracy, and a "get off soon" shown with its stops left and what they're counted from (maintainer,
2026-10-04) — never a raw coordinate, anything typed, or the user's API key.

**The Wear OS watch sync** (dev-docs/wear-os.md; maintainer, 2026-09-24): when a paired watch has
the StopDash watch app, the phone sends it the widget's snapshot — its stops' names and IDs, their
departures, the nearer-stop lists the terminating filter compares against (location-derived place
data), the starred-row keys, the hidden modes, and TfL's public line status for the lines those
stops serve, each with when it was checked and whether the user dismissed it — never a
coordinate or a key. The line statuses are
public TfL data, and they name only lines the snapshot's stops already imply, so they add no Data
Safety category. The watch sends back the rows its
complications show and its refresh requests. It goes over Google Play services' Wearable Data Layer,
which may relay through Google's servers when the watch isn't on Bluetooth; that relay is accepted,
with this disclosure. Nothing is sent when no paired watch has the app. `docs/PRIVACY.md` carries
the user-facing wording and the Data Safety determination, re-checked before the watch release.

**A trip on the way goes to the watch too** (maintainer, 2026-10-03), by the same route and on the
same terms: while the phone follows a trip, it sends the watch the trip's steps (each walk and ride,
its line and the stations at each end), the step the rider is at with what to do there now, and the
trains for the next ride. It is the one exception to a trip being kept on the phone only, made so
the watch can show the step the rider is at; it is taken off the watch when the trip ends, and the
watch stops showing one the phone stopped updating (its copy in the Data Layer goes with the
phone's next trip). The watch only shows it: paging through the
steps there looks ahead or back without moving the trip. The watch's tile shows the trip in its departures' place while
the watch shows one, as the phone's widget does, so neither keeps showing the departures where
the app was last opened while the rider travels (maintainer, 2026-10-06). While it shows one, the watch keeps an
ongoing activity of its own (maintainer, 2026-10-06): a silent watch-only notification, one tap
from the trip, saying the step the rider is at; the phone's ongoing notification stays quiet and
on the phone.

With a National Rail key set (*Data source*), a rail station's departures request also goes to
the Rail Data Marketplace, carrying that station's CRS code and the user's own key, never a
location; it is disclosed alongside the TfL requests. The key is a credential, handled like the
TfL `app_key`: never logged or placed in any other off-device artifact.

**Crash reports and usage stats are opt-in** (maintainer, 2026-09-24, following `mikelward/simmo`).
Firebase Crashlytics and Analytics are compiled in but collect only while the persisted **Help make
StopDash better** setting is on — **off by default**, since data leaving the device waits for the
user to agree. A build without a Firebase config never starts Firebase, and a debug build never has
one. Crash reports carry the diagnostic log's **off-device** rendering (`mikelward/androidlog`),
where any argument not explicitly marked safe — a stop ID, a line id, a coordinate — is replaced
before it leaves, and exceptions travel without their messages — a fatal crash too, redacted in a
handler placed in front of Crashlytics' own. Lines logged at startup, before the stored choice has
loaded, are held in memory and follow that choice: sent on a yes, dropped on a no. Usage stats carry Firebase's
automatic events and its IP-derived region, under a random app-instance ID (reset on opt-out;
Crashlytics keeps its own installation ID); the advertising ID is not collected. stopdash adds its
own events in **categories and ranges only**, never a stop, line, journey, search text or coordinate
(maintainer, 2026-09-24): each tap by kind (a journey card, a stop row, a change card, swapping a
journey, starring or unstarring, opening search or Settings, refreshing the widget), opening a
farther place (by mode) or the faraway favorites, the location permission left after asking
(precise, approximate or denied), how a near-me fix went (fresh, last known or failed) with its
accuracy and time in ranges, and how many stops of each mode a near-me lookup found (0, 1, 2–3, 4+).
Since 2026-10-07 (maintainer: "what walk speed and accessibility settings… which type of route"),
also: each screen as it comes up (Analytics' own `screen_view`, named by the app, since one activity
draws them all), what opened the app (its icon on a cold start, the widget, a notification or a
shortcut), each trip plan (how it went: routes, none, partly or wholly failed and why; its route
count in ranges; from here or a stop, to a stop or a place; first or again), each route opened from
its card and each started (by the card's label: Fastest, Simplest, Least walking, a combination,
Other, or none; its rank or its changes in ranges), and each setting changed with its new value as a
category or band (a line or stop avoided, hidden or added to the disruptions row counts only that one was).
And **user properties**, sent as the opt-in lands, when a setting changes and each time the app
comes to the front, so each event reads against the rider's setup: the trip options (walking speed,
longest walk, step-free level, modes off, avoided lines and stops as a count), hidden modes by group with
hidden lines as a count, distance units, text size in bands, the Settings switches, whether there's
an own TfL or National Rail key (never the key), widgets placed, a paired watch with the app or a
connected one without it, starred rows, saved places and favorite journeys as counts in ranges, and whether notifications
and location are allowed. Step-free choices are sent though they can hint at a rider's mobility: the
maintainer asked for them, they're user properties under the resettable app-instance ID, and they
read as a routing preference ("step-free to the platform" is also what a suitcase or a buggy needs).
Every value comes from a fixed list, so nothing a screen holds can be passed through. An event raised
before the stored choice has loaded (a cold start's open and first screen) is held in memory, at most
16, and follows that choice, as the crash log's held lines do; one raised once it reads no is
dropped. Turning the setting
on never releases a crash captured before consent: collection starts at once only if the crash SDK
found none waiting, otherwise the crash is discarded and collection starts on a later launch that
finds none; turning it off stops collection and discards what's unsent, so no report crosses the
consent line either way. A withdrawal reaches the SDKs before the tap returns, an opt-in is stored
before it reaches them, and the stored choice counts only while the SDKs agree with it (or an opt-in
is pending): a failed write, a kill mid-change, or a backup restored onto a new install all resolve
to **off**, so the user is asked again rather than collected from. The SDKs start themselves from
their own remembered setting before the app reads anything, so an opted-in user's crash during
startup is reported at no cost to startup. A stored choice lost on its own is read back rather than asked again
(maintainer, 2026-10-01), from two marks kept apart from it: a no, which a withdrawal sets before
anything else and which outranks the stored choice, so a withdrawal cut short still reads as the no
(where it can't be created, the no is kept another way first, each in one step: the yes mark moved onto it, the no stored, or, with no yes mark, the stored yes deleted, which reads as never answered); and a yes, set once an opt-in is stored. Never from the SDKs' own flags or a pending opt-in: before
the marks, a withdrawal that couldn't store its no deleted the stored choice instead, and could leave
them set. With no mark it's never answered; a mark that can't be read fails closed, as a stored
choice that can't be read does: off, and not asked. An answer stored before the marks existed is
marked at the next start, and an opt-in clears the no first, and isn't applied where it can't. **The question is put once**,
as a card atop the near-me list, or atop its empty state where nothing near has departures (as the
sibling apps do; stopdash has no onboarding to ask it in), its two answers wrapping onto a line
each where they don't fit side by side:
only to an install with no stored answer, and only once that's known, so it never flashes at
someone who answered. **Yes please** and **No thanks** each store an answer, as the switch does, so
the card doesn't return; a no that couldn't be stored is still kept by its mark (asked again next
start only where that couldn't be set either), and a yes that
couldn't be kept (not stored, or never taken up by the SDKs, at a start or after the tap) is asked
again at once and next start, its stored choice deleted rather than kept as a no. It isn't shown
on a station's page, a platform's, or a journey's own view. `docs/PRIVACY.md` is the user-
facing disclosure and the Play Data Safety source.

## Engineering quality bar

In priority order; where a rule below conflicts with a principle, the principle wins.

1. **Never show a departure stopdash doesn't stand behind.** The worst outcome is the
   user missing a bus, or running for a cancelled one, because stopdash showed a number
   it shouldn't have trusted. Stale-but-unlabeled, or a normal-looking prediction for a
   suspended line, is worse than an honest "can't refresh" or "severe delays". Every
   surface is honest about age and disruption.
2. **Never fail silently.** If stopdash can't refresh — offline, rate-limited, location
   denied — it says so where the user is looking and shows stamped last-good data,
   rather than a blank or a silent stale render.
3. **Do the work ahead of time.** A surface renders from the persisted snapshot; the
   network is never on the render path. Warm at startup, cache, refresh in the
   background.
4. **Hold still.** Never move what the user is reading or about to tap (maintainer,
   2026-10-04). A list that settles in front of them, or a row that pushes it down, makes
   them lose their place or tap the wrong thing. This outranks showing content early:
   while what a list shows is still landing, its place says so ("Checking routes…"), and
   the list appears once, settled, rather than filling in under the user's thumb. Where
   something must move, such as a trip's cards re-sorting, it slides rather than jumps,
   and taps wait until it stops.
5. **Jank-free.** The app list and the widget render from in-memory/snapshot state; no
   I/O in composition, and no blocking a first frame on a fetch *or a disk read* — a
   stamped placeholder shows at once and fills in when the persisted snapshot loads.
6. **Battery is the user's cost.** Background refresh is bounded and degrades to
   on-demand; anything that adds a wakeup or a location request is a battery change and
   is justified as one.
7. **Say why.** Non-obvious decisions are recorded where the next reader needs them — a
   comment for a mechanism, the debug log for a refresh/disruption decision, the PR for
   a design trade-off.

New behavior is covered by a unit test; a bug fix adds a test that fails before and
passes after.

## Testing and distribution

Mirrors the sibling fleet:

- Product logic lives in the pure domain layer and is JVM-unit-tested against recorded
  TfL fixtures (never the live API in tests, and never a user's coordinate in a fixture).
  Hubs and interchanges are public network data, not user data, and their real details are
  always fine; a bus stop or anything else neighborhood-level needs the maintainer's OK
  first. Never data captured from the maintainer's or a user's own device, location or
  reports, and no set of examples that together single out where someone lives.
- Compose screens and Glance widget layouts get Robolectric + Roborazzi screenshot
  tests, wired into CI's screenshot allow-list, rendered in British English (en-GB), as the
  app's users see it (maintainer, 2026-10-02).
- `./gradlew test` and `./gradlew lint` green before every push.
- CI mirrors the sibling `ci.yml` (build + unit tests + lint, a screenshot job, a
  Play-internal-track deploy job with release notes built from commit subjects) plus
  the shared `lanes`, `codex`, and `zizmor` checks.
- **Diagnostics are a persisted, on-device debug log**, off every render path: warnings are buffered
  and written to a rotating file in app-private storage that survives a crash or a silent process
  kill, so a misbehaving fix or refresh can be diagnosed after the fact (serves *never fail
  silently*). It stays on the device and carries coarse diagnostics only (see *Data source, cost,
  and reliability*); its one off-device sink is Crashlytics, only while the user has opted in, and
  only its redacted off-device rendering (see *Privacy*). The implementation is the shared
  `mikelward/androidlog` buffer, resolved as a published dependency. At startup it also records
  why the app's last few processes ended — the platform's reason (a crash, an ANR, a low-memory
  or standby kill), never its free-text description — pinned so a busy run can't push that out of
  a bug report, since a silent kill otherwise leaves the next run's log looking like a clean start.
  After an ANR it also pins where the main thread was stuck: a few frames of the frozen code, read
  from the platform's own record of that freeze, since an ANR's reason alone doesn't say which
  code froze the screen. A frame is a code location, never a value: the device's copy carries it,
  and so does a consent-gated bug report (which sends that copy unredacted), while the Crashlytics
  rendering carries a placeholder in its place.
- **The main thread's disk reads and writes are noted in that log**, in every build (reports come
  from release builds, where logcat is out of reach): the kind and the first of the app's own
  frames under it, once each per run and at most 20 a run. The main thread is for UI only, and a
  stutter too short to be an ANR otherwise leaves no trace. Network use on the main thread isn't
  among them: the platform refuses it outright (it throws), which is kept, and an uncaught throw
  is a crash the process-exit lines already record.
- **A bug report leaves the device only under explicit consent.** The overflow's *Send bug
  report* composes the log plus the **exact location**, per-stop distances, and a **screenshot of
  the reporting screen** and hands it to the platform share sheet — user-initiated, £0, no service
  of stopdash's own. Because it carries the location the log itself never does, it is gated by a
  consent screen that names exactly what leaves, with a persisted "don't ask again"; nothing is
  assembled until the user passes it. It is the *honest* report, not a location-safe one — a
  routing bug is diagnosed from where you were, so it says so rather than stripping that context (a
  separate location-redacted export stays a distinct, planned tool). It rides the shared
  `mikelward/androidlog` `DebugReport`, which attaches the screenshot; the shot is of the app's own
  window, so the consent dialog (a separate window) is not in it. See `docs/PRIVACY.md`.

## Non-goals

- **A backend of our own.** StopDash must never require the maintainer to run or operate a server:
  every network call goes to a third-party service the user or the app talks to directly — e.g. TfL
  (keyless, or the user's own key), National Rail with the user's own key, optional Firebase (a
  managed service, on opt-in), and the platform services already documented under *Privacy* (the
  release-only Play update check, the Wearable Data Layer). A feature that would need a
  StopDash-operated server, proxy, or datastore is out. This, not "door to door", is the real
  constraint: a capability a third party already provides — routing to a coordinate, which TfL does —
  is in scope; door-to-door was only ever set aside because it looked hard, not on principle (D9).
- **Maps and turn-by-turn walking directions.** Routing to a place is in scope — a saved favorite
  plans to its coordinate and TfL walks the last leg (D9, *Trips*) — but a walk shows as a timed leg,
  not a map or step-by-step directions to the door, and the To… search itself stays **stop/station**
  (stations and bus stops, not addresses; favorites are the door-to-door path). StopDash still leads
  with "what's next from here".
- **Non-TfL operators** outside the Unified API (coach, etc.), National Rail aside: its
  times come from National Rail's own feed once the user adds a key (*Data source*). Without
  one, TfL gives no times for them, so a National Rail line TfL reports disrupted at a station
  shows only as its status row, saying "No key" where times would be (*Departures*); one in good
  service isn't listed.
- **Ticketing**, Oyster/contactless balances, and service maps.
- **Writing to TfL.** StopDash is read-only.
- **Continuous background location / geofencing.** Location is used on demand in the
  app to find nearby stops, never tracked in the background. The one exception is a trip the
  rider started: a few precise fixes just after boarding, compared on the device and never sent
  (*On the way*).

## Decision log

- **D1 — The widget renders watched stops; the app offers both watched and nearest.**
  The lock screen is a glance surface that must always have something to show without
  waiting on a location fix — and background location on the keyguard is restricted,
  often ungranted, and battery-costly. So what a surface shows is chosen ahead of time.
  The app still uses on-demand location to *find and suggest* nearby stops to pin, and
  to show a "near me now" list. Location stays off the **background and widget** refresh
  paths — those re-fetch a fixed set of stops with no fix (the widget's persisted watched
  set; the near-me view's already-resolved set). The paths that **do** take a fix are the
  **user-adjacent near-me refreshes**: a refresh (button or pull-to-refresh) *and* a return to
  the foreground on the near-me departures re-resolve the nearby set as well as re-fetching, so
  walking to the next stop and reopening — or refreshing — follows the user (see *Finding stops*).
  That is still on-demand and foreground — a user gesture or an app open, never a timer or a
  background wake (the on-screen auto-refresh tick stays departures-only).
- **D2 — A watched stop can be filtered to lines and/or a direction.** A station serves
  many lines and platforms; the rider takes one or two. Default is all.
- **D3 — Disruptions are surfaced alongside departures, and mark the line/stop even
  when predictions look normal.** A time for a cancelled or suspended service is the
  "quietly wrong" failure; the disruption is what makes the number trustworthy or not.
- **D4 — No surface presents stale data as live.** Data is stamped with its fetch age;
  countdowns recompute from the fetch time client-side; an expired prediction drops off
  the list rather than sticking at "0 min"; and a single shared staleness threshold (one
  tuned constant, not a per-surface number) decides when numbers are withheld for "tap
  to refresh".
  - **The stale stand-in is a marked guess (maintainer, 2026-10-04).** Past the threshold, a
    line shows its soonest train's predicted time in London, dimmed and marked "21:14?", in
    place of a bare "?", on every surface that lists times: the app's cards (as they reload
    after a while away), a trip's board of next trains, the widget, the watch tile and the watch's trip. It still says
    roughly when, while the "?", the dimming and the age stamp say it's no longer live; a
    clock time, unlike a countdown, doesn't read as recomputed from now. A guess goes once its
    train is due, as a departed countdown does, so a surface that doesn't redraw on its own
    redraws at those times: the widget schedules a render-only redraw at the soonest guess it
    draws (a few bounded wakes after the boundary, none once no train is left), and the tile
    adds a timeline entry at each, past which the frame is open-ended. Where the tile's entries
    run out, its tail withholds every time as "?". The watch complication, too small for a time
    and a mark, keeps "?".
- **D5 — Widget refresh is opportunistic and bounded, not aggressive polling.** Tap,
  host update, and a bounded periodic schedule while plausibly visible; degrade to
  on-demand. The interval is a battery-tuning detail, not a spec guarantee. Keeping the
  widget *honest* is separate from refreshing its *data*: because the host never re-renders
  a static widget on its own, the widget schedules one render-only redraw at its staleness
  boundary (a single bounded wake per snapshot) so it flips to the stale treatment when the
  app is closed (D4) — fetching new data on that schedule was the deferred part.
  - **Tap the header to refresh (2026-10-04).** The widget's header — the title, its stamp,
    and the note under them ("Tap to refresh") — refreshes the widget's stored stops in place,
    location-free like the live refresh (D1), whatever that setting says; the departures below
    open the app, which re-locates. The note says "Refreshing…" while it runs, and why a refresh
    that fetched nothing failed (rate-limited, TfL unreachable, the key rejected) for as long as
    the data it couldn't replace is still out of date (principle 2). It is an expedited one-shot
    job, one at a time with the live refresh and a watch's request, so a tap costs one round of
    requests and nothing in the background. Without it the widget, off by default, read "Tap to
    refresh" whenever the app hadn't refreshed in the last five minutes, and the tap only opened
    the app.
  - **Opt-in live refresh (off by default).** A Settings toggle, "refresh widget every
    minute", drives a self-rescheduling one-shot WorkManager chain that re-fetches
    arrivals for exactly the widget's persisted stops (location-free, D1) about once a
    minute and saves the refreshed snapshot, which pokes the widget to re-render. It is
    off by default because it costs battery and data the passive widget doesn't. A failed
    cycle keeps the last-good and still reschedules, so a transient TfL error doesn't
    break the chain. This is a **deferrable** one-shot, not a foreground service: the OS
    runs it roughly once a minute while the device is active and defers it under Doze
    (screen off and unplugged), but it is **not screen-state-gated**. With the app closed
    there is no live component to hear screen on/off, so the chain can still run with the
    screen off while charging (Doze may not engage) — which is why the setting's copy
    describes it as a background refresh, not a screen-on-only guarantee (decided with the
    maintainer, 2026-09, on Codex's finding that the earlier "while the screen is on" copy
    over-promised). A true screen-on-only scope — and guaranteeing the exact minute with
    the screen off — would need a foreground service, its persistent notification, and the
    Play foreground-service-type policy that carries; that is the deferred follow-up
    (mechanism A, *Widget follow-ups* in `TODO.md`).
- **D6 — The app refreshes on open, on foreground return, on pull-to-refresh, and
  auto-refreshes once a minute while the screen is on** (paused when backgrounded). The
  intended targets — home users and an always-on kiosk display — leave the screen open,
  so a one-minute cadence keeps TfL predictions (which update roughly every ~30 s) fresh
  without a manual pull, while staying a tiny fraction of the keyless per-IP rate budget.
  A failed tick keeps the last-good departures with a warning (D4), never a blank screen.
  The interval is one tuned constant in code, not a spec guarantee.
- **D7 — No baked-in TfL key; works keyless, optional user key for the higher limit.**
  A shared key would pool all users into one bucket and ship a credential; a per-user
  key avoids both. See *Data source*.
- **D8 — Ship one card per platform/pole, a chip-tagged row per route, with star-to-pin;
  final display model left open.** A station serves many lines and most run two ways, so the
  display has to present direction somehow. The list gives each **platform (or bus pole) its
  own card**, and inside it **one row per route** — line pill + destination + the next few
  countdowns merged onto one line — so nothing is hidden and it stays glanceable, a line's
  several routes each carrying their own pill. Each route row is a **compact,
  near-uniform-height block**; only genuinely extra information (a branch's second
  destination is its own row) adds height, and a disrupted route flags with an **inline ⚠**
  left of the countdown rather than a chip row. The destination **elides** to one line so a
  long name never wraps or crowds out the countdown. **The "inbound/outbound" direction word
  is dropped** — the destination is the direction signal a rider reads. **The stop name is not
  on every row but heads the card**: the list is **clustered by place (stops sharing a cluster
  — a junction's poles, a station's platforms) and split by platform/pole**, each group headed
  by a **single title-case line** — "Place – Qualifier (distance)". The header's **qualifier** is
  the cue that tells its groups apart: a rail **platform** ("Platform 2", parsed from
  `platformName`, keyed on the platform not the compass, since one compass spans physically
  distinct platforms; a platform-less rail direction falls to the bare compass), a **bus** pole's
  **letter** ("Stop D" — "Stop" only ever precedes a literal letter), else the **towards** on its
  sign as an arrow plus its first place ("➔ Farringdon"), else its **bearing** as a bare direction
  word ("Southbound"), else nothing — never one bus's destination, which says where that bus goes,
  not where the stop heads (towards settled 2026-10-03; the single line settled 2026-09-22,
  superseding the two-level header (place name once + indented sub-header) and the one-level
  `(place, direction)` step before it). The compass direction is not
  shown on the header (the destinations carry it). The cluster key is TfL's `stationNaptan` where
  the nearby lookup gives one,
  else the cleaned display name — keying on TfL's own cluster keeps a station it spells
  several ways together while holding distinct adjacent stations (King's Cross St. Pancras
  vs St Pancras International) apart. The qualifier's
  grain at a busy interchange remains a follow-up (mocked 2026-09-21; `TODO.md`). Its
  cost is length — a busy stop is many cards — which starring (ranking,
  distinct from watched-stop membership) and, later, smarter selection are meant to manage.
  The list orders the watched stops' cards location-free (soonest-first, starred pinned),
  so it works with location denied; the grouping then clusters that order by place (a
  place's cards stay adjacent, led by its soonest) without changing which place leads.
  Distance ranking is for *finding* stops, not ordering this list (D1). A more compact
  **(service, stop) card that swipes between directions** is
  the leading candidate to iterate toward, but it hides the other direction behind a
  gesture the widget host owns, so it is deferred until the flat list has been used on a
  device — not a prerequisite. TfL's `direction` is the primary key and is retained (it
  can't be reconstructed from destination/platform in general); when TfL omits it, grouping
  falls back to platform then destination as a best-effort discriminator, and an all-blank
  prediction shares one "unknown" row. A branching direction merges only the headline
  destination's times; each divergent destination — and each via-branch of one terminus —
  keeps its own line and countdown, so none is mislabeled. One refinement remains recorded
  to explore (`TODO.md` Phase 2):
  labeling a direction by the next branch/interchange point downstream rather than the
  terminus, feeding user-set favorite destinations. Supersedes the earlier open question;
  the flat-list-vs-swipe-card choice is the remaining open call, to settle from real use.
- **D9 — Home, Work and other favorite places are saved by *coordinate*, routed to directly.**
  A favorite stores a **coordinate + a label** (e.g. "Home") plus a **stable role** — Home, Work,
  School, or custom — a stable id, and the **resolved place name** (the stop/station the coordinate
  came from, shown under the label; it also rides Android backup, *Privacy*), so the reserved
  Home/Work slots are identified by role, not by the mutable, possibly-duplicated label (a custom
  place may reuse the word "Home"; a label may change on edit or with localization). The coordinate is resolved from a **stop/station or a
  postcode via TfL** (its `/StopPoint/Search` matches usually carry lat/lon, and the **Journey Planner**
  resolves a postcode — no third-party geocoder, £0); the favorite-setup field reads **"Stop or
  postcode"**. A typed stop/station is **sent to TfL's stop search** (the *Find a station* path), once
  typing pauses, to resolve the coordinate — the same send as the station search. When the text instead
  **looks like a postcode**, a **complete** one **resolves itself** through the Journey Planner (as the
  stop search resolves a station on a pause — no extra tap), which either geocodes it to one point or
  returns **candidate places**. A postcode is unambiguous, so a **single place is adopted straight away**
  (type → Save); only a genuine **multi-place** answer offers a chooser the user **picks** from, and an
  empty or failed lookup is shown honestly (a failure stays retryable). While the postcode is still
  partial the editor shows only a passive "Postcode …" hint (from two characters, the same floor station
  completions use). A postcode-shaped query (a digit present) is **not also sent to the stop search** —
  only the Journey Planner — so it costs one request, not two (a plain two-letter prefix, how a station
  name starts, still searches). Postcode entry is **favorite-setup only** for now (From… and To… stay
  stop/station fuzzy search). The **search query text is not persisted** (it isn't written to saved
  state, `FavoritePlacesStore`, or backup — *Privacy*, `docs/PRIVACY.md`); the place the user then
  **chooses** is saved by its coordinate and its resolved name exactly as a chosen station is — and for
  a postcode that name may be the postcode itself, no more revealing than the coordinate already stored
  beside it. A match TfL gives no inline position for is **resolved on pick from
  its stops' center** (`stationStops(id)` → mean of the members' positions, as *To…* already does), so
  a station the search and bundled index both left positionless is still selectable; only a result whose
  stops carry no position stays **unselectable in favorite setup** ("no location"), while a **transient**
  lookup failure (TfL unreachable) is shown as retryable rather than as "no location" — neither is left
  silently doing nothing. A favorite is saved only once a coordinate resolves; such an unplaceable match
  stays a valid **ordinary** *To…* destination, which routes by stop id and needs no coordinate. A trip is planned
  to the **coordinate** (`to = lat,lon`): TfL picks the access stop and returns a final **walk leg to
  the place**, which the trip screen already renders. StopDash does **no nearest-stop snapping of its
  own** — TfL chooses the access stop, so a shut local station never breaks the trip — but the trip's
  existing **per-alighting-stop closure checks** (*Trips*) still run on the resulting route: a route
  through a closed stop is still caught, never trusted to the Planner blindly (SPEC principle 1). The
  coordinate is the **Location** Data Safety type already
  declared (same as the nearby lookup), sent only to TfL, so **no new type**; it persists with the
  rest of the user's config and rides Android backup / device transfer (a user-controlled platform
  channel, *Privacy*), and is handled like watched-stop data — never in the debug log or any pushed
  artifact. Favorites (Home, Work, School, custom) are managed from a Settings **"Favorite places"**
  row, and **tapping a favorite there routes to it**. The trip screen's **To…** picker **additionally
  lists all** the saved favorite places at its top, before any typing, so a rider routes to a saved
  place in one tap without leaving the trip (maintainer, 2026-09-27, broadening an earlier framing that
  pinned only Home and Work and prompted to add an unset one — the picker just lists what is saved;
  adding a place stays in Settings). Its own search stays **stop/station** fuzzy search — stations and
  bus stops, per *Trips*, not addresses. Ratified by the maintainer 2026-09-26, superseding an earlier `TODO.md`
  note that treated the Journey API as a non-goal and left the privacy call open — that note predated
  trip planning. Routing to a point with a last-leg walk **is door-to-door routing, now in scope**
  (see *Non-goals*). A finer **street-address autocomplete** for entry is a later opt-in — it adds an
  address-resolver recipient (a Data Safety change) — so it stays a separate decision; the coordinate
  storage already supports it.
