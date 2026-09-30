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
  user-adjacent** — the fix is bounded to the user's own refreshes and app opens, never a
  background send or a timer (the on-screen auto-refresh keeps departures live but does **not**
  relocate). Sending the location on a foreground return is the deliberate trade for that UX. Its
  cost is **one extra forced fix and one extra `/StopPoint` request per app-open** (on top of each
  refresh's): a small, bounded **battery** draw on the locator for the ~1–2 s fix, and one more
  keyless TfL request (**£0**, well within the ~50 req/min budget). It adds **no new Play Data
  Safety surface** — the same precise-location-to-TfL the near-me action already declares, on the
  same foreground, user-adjacent path, not a new recipient, category, or background collection. A
  distance-triggered version that relocates as the user moves ~100 m *while the screen is open* is
  still a later enhancement, with the parameters and battery trade-offs in `TODO.md`. The re-locate
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
    coarse fix says "Approximate location" too, and looks again from any different precise fix. The ask is foreground-only: it
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
  of starred journeys, then the places holding a starred row, each recorded when starred), less
  any picked lately. *From…* and *To…* keep **separate recent lists** — where the rider looks from
  and where they go — so each search lists its own history. As the user types, those and every place the app has lately shown near them (the
  widget's last departures and the nearby-lookup cache) match on the device alongside the
  bundled stations, so a starred bus stop appears at once; a bus stop (a journey's end
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
  "Archway" is listed once, not once per stand, and its page takes in the stops around it.
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
  its cross street ("Foo Street / Bar Road"), a place after its area ("City of Westminster, Tate
  Britain") — matches a query starting **any part** as a prefix. Best-effort: a geocode failure yields
  no places and the stops still stand. A better geocoder is a later option (`TODO.md`).
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
  named as a rider thinks of them (maintainer, 2026-09-24): **Tube & DLR** (the DLR is turn-up-and-go
  and on the Tube map), **Train** (the Overground, the Elizabeth line and National Rail), **Bus**,
  **Tram**, **Boat** (TfL's "river bus") and **Coach**. The overflow menu lists all six, always the
  same, each with a checkbox ticked while it shows. A long press on a near-me
  row opens a small menu, pinning or unpinning it and **"Hide all ‹group› services"** (a bare "Hide
  Train" read as hiding that one train); a long press on a place's header offers it for each group
  it serves. Starring a near-me row therefore takes the
  menu's first item. Journey cards keep a long press as a direct star, since a starred journey is
  the user's explicit choice and hiding doesn't reach it.
  A hidden mode's stops aren't picked for the near-me set, so they cost no request; a place that also
  serves other modes keeps them, with the hidden mode's rows left out, and the widget leaves them
  out too. With National Rail hidden, such a place's National Rail board isn't asked for either,
  from the next refresh of the list, a farther station's card, a trip or the widget, since it
  would only fill rows left out; a starred journey taking a National Rail line from the place still gets its
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
  would list dozens.

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
one line — "King's Cross St. Pancras – Platform 1", "Cranley Gardens – Stop G", or "Turnpike Lane ➔ Bank" for a destination (the arrow joins them, so no dash) — with the
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
keeps its name when the others didn't fit, and a bus stop whose second route didn't fit doesn't
claim the shown route's terminus as its own;
a widget too short for a header and a departure drops headers rather than show no departures. A **bus** pole carries no platform in the arrivals feed, so it
splits on its **stop letter** — the "D" a rider reads on the physical stop: "**Stop D**". The letter,
bearing, and "towards" come from the near-me `/StopPoint` lookup (`stopLetter`, `CompassPoint`,
`Towards`), not the arrivals feed, so a **watched** bus stop (no near-me lookup yet) has none until
that capture lands. TfL is inconsistent about where it carries the compass — some poles use
`CompassPoint`, others put an arrow in `stopLetter` (`->N`) in place of a real letter — so an
arrow-in-`stopLetter` is normalized to the bearing, and a compass-only pole renders the one way
whichever field TfL used. With no letter, the pole falls back to its **compass bearing** — a bare
direction word ("**Southbound**"), like the rail compass; with neither, to the **shared terminus** — an
arrow plus the destination ("**➔ Bank**") — when the whole stop heads one way (every route names the
same, non-blank terminus, principle 1); with none of the three, the **bare place name**. "Stop" is
reserved for a literal pole letter; the direction word and the "➔ destination" carry no "Stop".
Precedence: letter → bearing → terminus → bare. The header is **one line** — "Place – Qualifier
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
Station" reads "Battersea Power". A trailing **line-name parenthetical** is dropped the same
way — "Hammersmith (H&C Line)" shows as "Hammersmith", since the pill already names the line —
while a *geographic* parenthetical with no line ("Stratford (London)") is kept. A short list of **hardcoded display renames** shortens a
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
above). The branch **participates in grouping**: within a direction, a
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
`DestinationAbbreviations`; each part of a slash-separated name alike, so "Shepherd's Bush Market /
Wood Lane" keeps both places, and below the floor each place elides on its own rather than all but
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
**dash** when the line's source answered with no trains (TfL for its own lines, the National Rail
board for a National Rail line), **"No data"** when no source answered for it (a National Rail line
whose board failed, or that no board covers), and **"No key"** for a National Rail line at a
station a key would cover when none is set — a tap on it opens Settings.

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
are made, an email address isn't treated as one, and with no browser the tap says so. Below that it lists **every station from the boarding stop to where the soonest train
terminates** (a short-working ends where it does, not at the line's end), on a rail in the line's
color. The boarding stop is marked with a blue "you are here" dot (map-location blue, ringed to
stand off a blue line's rail), announced to a screen reader as "Your stop"; every other stop, the terminus included, is hollow,
so the one filled dot is the rider's (the terminus keeps a bold name). The list is headed only by the **direction** the train runs ("Southbound") — read off its
rail platform, or a bus pole's compass bearing, and left off when neither names one — not by "From" or
"Stops to": the list opens on the boarding stop and the app bar already names the destination. Only
while no list is shown (a status row, or a list loading, failed, unavailable, or withheld) does the
body name the boarding stop ("From Victoria"), so the page always says which stop it is about. The list comes from TfL's Route/Sequence for the line, fetched **when the page opens** — never
on the refresh path — and kept for a day, in memory and in the app's cache directory, so a reopened route shows at once, even after the process was killed; until
it arrives the page says so, a failed fetch says why with a retry, and where TfL's data admits more
than one path from here (a Northern train with no branch named) the page says the list is
unavailable rather than guessing a trunk (principle 1). **A bus runs to its route's end** when its
destination names neither a stop ahead nor the route: a bus blind shows an area or landmark, not
its last stop's name, so matching by name alone left most buses with no list. A
bus short-working whose label *does* name a stop ahead still ends there, and two variants that part
ways ahead are still unavailable. Rail keeps the strict name match — its destinations are stations,
so a miss there is a working the sequence doesn't model. **A loop goes the way its platform faces**
(maintainer, 2026-09-26): a Circle line train "to Edgware Road" can reach it either way round, so
where more than one path matches, the platform's compass ("Eastbound", or a loop's "Inner Rail" /
"Outer Rail", the outer running clockwise) keeps the path that leaves that way. TfL's direction
can't decide it — on a loop both trips pass the same platform. Where the platform names no compass
("Platform 1"), the train's direction does instead, keeping the routes TfL runs that way. **A train TfL shows as "Check Front
of Train"** keeps TfL's wording on the list, as the platform board has it, but names no place: it
has no stop list, and a trip or journey counts it only where every way it may run from its platform
agrees (all pass the rider's stop, at least as far as where they part, or none does); otherwise it's
one of the routes that couldn't be checked. **A station TfL lists under one id and routes
under another** (St Pancras's Thameslink departures, whose route calls at the station's low-level
platforms) boards at the same-named station in the same interchange — never a different station
in it, like King's Cross beside St Pancras. A journey starred there keeps the id the departures
carry, and its card places it the same way. Which interchange a stop is in comes from the bundled
station index, so this holds however the stop reached the screen — nearby, watched, or a journey's
end. Whenever the list is unavailable, the
debug log records why (principle 2). The list follows one predicted train, so
it is **withheld while the row is stale** — that prediction may no longer be the next train — and
returns with the next refresh (D4). Each station carries a **line pill per connection** — the
other tube, Overground, DLR, Elizabeth line and tram lines at the station or its interchange,
from the same response. Buses are left out, since nearly every station has several and they would
swamp the list. It is a full screen rather than a dialog
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
stay legible on the surface (darker on the light card, brighter on the dark one). An
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

A rider can **star a journey** — a segment between two stops, rail or bus (maintainer, 2026-09-23):
on a route page, tapping a stop on the stop list (after the boarding stop) stars the segment from the
boarding stop to it — both directions — and marks the stop with a star; tapping it again unstars it.
A journey is a **segment, not a line**: the same two stops starred from another line's page (the 43
or the 134 between two shared stops) are the same journey. Starred journeys lead the near-me list as
cards, each headed by the direction shown ("Highgate ➔ King's Cross St. Pancras ★", the gold star
marking a starred journey so its heading reads apart from a bus place's "Place ➔ Destination" header,
maintainer 2026-09-24) with the trains or
buses **on any line** from the origin that **call at the far end** (from each line's route; one whose
path can't be resolved is left out, not guessed). The origin is whichever end is **nearer the rider's
fix**, from TfL's published stop positions; the ⇄ button at the end of the heading shows the other
direction in place, for planning the way back (maintainer, 2026-09-24). A tap on the heading opens
the **journey's own view**: the journey in full, **Swap direction** and **Unstar journey** buttons, and
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
per refresh. A pole is dropped only once it has been judged: while its lookup or a line's route is
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
claims nothing new. Alerts on lines with no starred journey (a separate favorite-lines list) stay a
`TODO.md` idea.

**Change at a fork.** When a line runs only one branch from the origin (a Northern line train to
Edgware from King's Cross, none to High Barnet) and no direct train is due, the card shows the
other branch's trains instead, under "King's Cross ➔ Camden Town (for High Barnet)", with "No direct
trains" above them (maintainer, 2026-09-24). The change stop is the last stop the train's path
shares with the route to the far end, from the route data the card already loads; the brackets name
the journey's own end, not the branch's terminus. While a direct train is due none is offered, since
changing mostly lands the rider on that same train at the fork. It names only where to change, not
the connecting train's time, which isn't known. Rail only: a bus's path is often the route's end,
too loose to send a rider to change on. The widget keeps showing direct trains only.

**Direct only, for now.** A starred journey is one line between two stops; a starred trip with a change
(the eventual goal behind starring home and work) builds on *Trips with a change* below. The tap-a-stop entry point has no
cue of its own, so a starrable stop list opens with a one-line tip ("Tap a stop to star the journey
there") until the user dismisses it (maintainer, 2026-09-24); the dismissal is kept with the app's
settings. Starred journeys are
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
or the near-me list's place chips, since a trip to where the rider already is goes nowhere. The *From…* row holds no saved places yet: what a place as a start should do is
undecided (`TODO.md`). The trip opens on a **list of routes, best first**: ordered first by how far StopDash
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
rather than splitting the rest in two. With neither header shown, no card is headed Other. Routes riding the
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
when it gets there sooner** (maintainer, 2026-09-28): one that another route with fewer changes
beats or ties, while StopDash stands behind that route at least as far (usable, then unchecked, then
not running; then live, estimated, withheld — stricter than the list's order, which ranks an
unchecked route a live train times among the checked, so a route checked open is never left off for
one that couldn't be checked), is left off the list, and of two arriving together the one with fewer changes comes first.
An arrival that is withheld is never compared, and a route already open stays open.
Tapping the card opens its best route. **Every route looks alike** — no
route is expanded — as a card whose top row is its **duration · arrival** ("22 min · 08:24"); the
duration is from now to that arrival, so it takes in the same walks, waits and legs. Where waiting
for a frequent line past its predictions (below) could make it later, by a minute or more, both
show as a **range** (maintainer, 2026-09-30: once a route can rank above a live one on its latest, a
single estimated time hid why it sorted where it did) ("41–49 min · est. 11:26–34", the end's hour dropped within the same hour to
save width): from no wait up to the line's longer typical gap, the route timed again with each such
wait at its longest, so a wait that misses the next leg's train counts that too. Where that later
route can't be timed at all (the connection missed, nothing else known), it reads as open-ended
("41+ min · est. 11:26+"). It counts waiting only, not a slow run, and ranking still goes by the earliest arrival (maintainer,
2026-09-27), except that an estimate is set against a live route by the latest end of its range
(below). Under the top row, the **walk to where it starts** has a row of its own — a walker in the
pills' room, so the stop starts where a ride's does beside its pill, then the first stop, and its minutes in the
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
departures it still heads the empty state, just when a route elsewhere is wanted (with no stops in
range at all there is no row yet, as the trip from here plans from nearby stops). It is on the
near-me list only — not a station's page or a drill-down, which are about one place. A place the
rider is **already at** is left out, since "Home" at home is a route to nowhere: **within 200 m** on
an accurate fix (one whose own reported accuracy is within 100 m), back once past **250 m** so a
wandering fix doesn't make a chip flicker. The rule leans towards showing: a fix the list flags as
approximate, coarse or not updated, or one only an approximate location grant allows, hides nothing,
as a wrongly hidden chip costs the one-tap route while a wrongly shown one costs only space. The
places are read from the first frame, from the device (no request), so the row is there when the
list is; a place list that can't be read shows no row here — Settings and the To… picker are where
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

The trip's top bar carries the app's **overflow** as the list's does — its red dot while an update
is available, "Update available", "Send bug report" and About — so a problem seen on a trip can be
reported from it (maintainer, 2026-09-26). So does **every other screen** with no menu of its own —
On the way, a route's page, a station's page, the From…/To… search, Settings, the saved places and
the licenses (maintainer, 2026-09-29): a problem is reported from where it is seen, and the report's
screenshot shows that screen. The location gate keeps its own "Send bug report" button instead.
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
A walk reads "Walk to ‹place› (5 min)", its minutes in parentheses so they read as how long it
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
so both ends of a walk between stations or from a stop the route starts at, and the destination, as a starred journey's far end is
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
not to run. Either way it says what it couldn't check, until a check succeeds — "Couldn't check for
disruptions: ‹names›", each line whose status isn't known or whose check failed, then each stop with
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
withheld the arrival and why. Otherwise its arrival reads **"est."** instead of "about", and within
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
a fresh fix) that resolves to a different nearest stop discards the plan and re-plans at once,
showing "Planning…" rather than the old routes. A new fix that keeps the same nearest stop keeps the
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
rides: 10, 15, 20, 30, 45 or 60 minutes, **30 by default** (maintainer, 2026-09-30). It was a fixed
15, timed like every walk at the rider's pace, so a walk to a station that beat every ride (21 minutes
at the average pace) was never offered (maintainer's report, 2026-09-30). It is one setting, chosen in Settings (under the walking speed) or from a dropdown under the walking
speed atop a trip's routes (maintainer, 2026-09-30), and a change plans the trip again at once, as a
speed change does; plans are kept per limit. Every plan, whatever asks for it, waits for the walking
speed and max walk (and step-free level, below) to be read from storage, and the trip's dropdowns open nothing until then, so no
route is planned under the defaults in place of the rider's own choice; a read that never lands delays
a plan by two seconds at most, then it plans with the defaults and plans again once the read lands. Every
walk is timed at the rider's **walking speed** — Slow, Medium or Fast (the Planner's `walkingSpeed`;
Medium is its average and the default) — so a brisk walker isn't shown a ten-minute walk they do in
six, nor told a train is out of reach that isn't (maintainer, 2026-09-28). It is one setting, chosen
in Settings or from a dropdown atop a trip's routes or an opened route, and a change there plans the
trip again at once, since every walk and the connections after it were timed at the old pace; an
opened route stays open if the new plan still offers it. Plans are kept per pace.
The request names the Planner's modes (its own default set, walking among them): left to its
defaults, the Planner accepts `walkingSpeed` but times every walk in a route that rides at its
average, so the setting changed nothing (2026-09-28). Named, the pace times each walk and so which
connections it offers, while the routes stay those it offers by default.

**Step-free.** A trip's routes can be held to a step-free level (maintainer, 2026-09-30), the
Planner's `accessibilityPreference`: **Any** (no requirement, the default; nothing sent), **Station**
(step-free from the street to the platform, maybe a step or gap onto the train: what a suitcase or a
buggy needs) or **Fully** (step-free onto the train as well: what a wheelchair needs). The Planner
then plans with lifts, ramps and level walkways in place of stairs and escalators. It is one
setting, chosen in Settings or from a dropdown under the max walk atop a trip's routes, labeled
**Step-free: Any / Station / Fully** (the maintainer's names). In its menu, Station and Fully each
carry a line saying what they're for, since the bare names don't tell a rider with a suitcase or a
buggy that Station suits them too. A change plans the trip again at once, as a walk change does; plans are kept per level. A plan's wait for the settings to be read
(above) takes in the step-free level too, so a rider who needs step-free routes is never first shown
routes with stairs.

**Modes.** A row of chips under the step-free dropdown says which kinds of transport a trip may
ride: one per group the list already hides modes by, under the same names
(**Tube & DLR**, **Train**, **Bus**, **Tram**, **Boat**, **Coach**), selected while the trip rides it.
A tap turns a group off or on and plans again at once, as the options above do; plans are kept per
choice, and the choice is remembered across trips. It is sent as the Planner's `mode` list, less the
groups turned off. Walking, the cable car and rail replacement buses always stay: every route needs
its walks, the cable car is in no group, and a replacement bus stands in for a train or Tube the
rider still rides. The last group riding can't be turned off, since a trip riding nothing has no
route. It is its own setting, not the list's hidden modes: a mode hidden from the nearby list (a
noisy bus stop outside) can still be the best way somewhere, and a mode turned off for a trip (a
Tube strike) doesn't empty the list. The settings wait takes it in too.

**Two requests per plan.** The Planner answers with about three routes, often one route at three
departures, so each plan asks it twice at once: for the quickest routes (its default) and for the
**fewest changes**, which finds the walk to a station or the one bus the whole way that the quickest
three passed over (maintainer, 2026-09-30). The answers merge, the quickest's first, and a route both
offer (the same lines between the same stops at the same times) appears once; the list then orders
them as always (tiers, then arrival). Either answer alone still plans the trip, and says which
request failed in the debug log; only both failing fails the plan.

**One ride to the fastest route's last stop.** To a **place** at a coordinate — a saved favorite, or
a place or postcode picked in the *To…* search — the fewest changes can trade the one bus the whole way for a train and a long walk from the station, since a walk
counts as no change, while the quickest routes change on to that same bus — so neither request offers
the bus a short walk from the rider (maintainer's report, 2026-09-30). So when the route to a place
arriving soonest by the Planner's times rides more than once, the Planner is asked a **third** time,
for the fewest changes to the place **via** the stop that route gets off its last ride at; each
route it offers riding fewer times joins the list as its own card, ranked like any other. The
Planner builds the whole route, its walk on to the place included, so StopDash joins nothing: an
earlier design planned to the stop and added the fastest route's walk on, and each thing the join
had to carry (which pole, the change time, a bus later moved to its line's pole) turned up in review
one at a time (maintainer, 2026-09-30). It waits on the quickest answer, so its card lands a moment
after the rest; failing, it leaves the plan as it was and says so in the debug log. A trip to a stop
never asks: its fewest changes are already planned there.

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
rider makes, each **two** Planner calls (quickest and fewest changes, above), so about eight calls an hour;
a trip to a place whose fastest route changes adds a **third** each time (above), about twelve.
To a station complex each plan is that pair per station plus one pair for its bus stops
(about six at King's Cross, two or three at a typical interchange): about 48 calls an hour at King's Cross. Ranking needs every listed route's live trains, so each refresh fetches arrivals
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

**Later:** avoiding a line, done on the phone: the Planner has no way to exclude a line, so the trip
asks it for alternative routes and drops those using the avoided line.

### On the way

*In progress* (maintainer, 2026-09-26; mocked the same day). An open trip route has a **Start**
button; the trip is then **on the way** until the rider arrives or taps **End trip**, and follows
them there:

- **"Train" means whatever the leg rides.** The step names what the rider is looking for by its
  mode (maintainer, 2026-09-28): "Finding your bus…" / "Can't find your bus", and the same for a
  coach, a tram, a boat (river bus) and a cable car; every rail mode — Tube, DLR, Overground,
  Elizabeth line, National Rail — is a train.
- **Which train.** Start assumes the **next train the rider can catch** on the first leg — the
  soonest the route lists that TfL names (its `vehicleId`) and that is due after the rider can
  reach the stop — and switches when another is seen to be the one they're on: picked by the
  rider, or (with location, below) seen moving with them. A change picks the next leg's train the
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
  so it mustn't hold a rider already at the station on "Walk to…". A train ride ends the same way
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
  and **getting off it** ("Get off at …", a row of its own under it), so the rider can say they're on
  before they say they're off. At a boarding step, a walk's time runs from then and a ride's train is
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
  under that ride's own row in the route's legs, drawn as the departures board draws a stop — its
  platform header, or a bus pole's letter ("Archway – Stop D", maintainer, 2026-09-29: not where the
  buses go), over the board's own outlined card — so they read as a board, not as another step
  (maintainer, 2026-09-28), and after its train has left until they're seen on board (above): every line of the ride's mode whose route calls at
  where they get off — judged by each train's own line route, so another branch's trains stay out —
  a row per line and terminus with its next few times, not only the train followed, a time grayed
  when it leaves before the rider can be there (as a trip's cards gray one); "Updating…"
  once the board is too old to stand behind (D4); it shows "Loading" from the step's first frame
  until the first board is in. It costs one arrivals request per refresh during those steps, plus,
  for a line at that stop whose route isn't already held, the same route lookup a trip's cards make
  (one or two TfL requests per line, kept a day and shared with them), and for a bus, coach or tram
  stop one request for its pole's letter, kept for the process. All free, within the
  keyless budget, and naming only stops and lines, never the rider; a route that can't be loaded
  says "Couldn't check every line" rather than drop its trains. The walk's check costs one GPS fix
  per refresh until the rider is seen there or the walk's estimated time is up. Below the next step
  the route's legs are listed, a ride by
  its pill and a walk by a walker in the pills' column, so every leg's stops line up. A route with a **National Rail** train offers no
  Start but says it can't be followed yet: its times come from National Rail's boards, which name
  no train to follow. **One trip at a time:** while one is on the way, a route offers **Open current
  trip** in Start's place; the trip is ended from its own screen.
- **Following it.** The trip asks TfL for **the followed train's calls ahead of it**
  (`/Vehicle/{id}/Arrivals`, in one request) about every 30 s while it is shown. Still due at the
  boarding stop, the rider is **waiting** for it; once it has left, they are taken to be **on
  it**, the next stop and the stops left to getting off counted from its calls — so it works
  underground with no GPS. **Taken to be is not seen to be** (maintainer, 2026-09-29): a rider still on
  the platform underground looks the same, so until they're **seen on board** — by location (at a
  later stop of the ride, or well on along it, as *sees a rider already on their way* below; the
  checks carry on after the train leaves, within the same ten minutes of the wait) or by their word
  (Next, or a tap on "Get off at …") — the step stays the ride's: **"Take the train to …"** (by its
  mode, as "Finding your bus…" is), its board of departures still under it. Seen on board, the step
  becomes "Get off at …" with the stops left and the board goes. "Get off soon" is still said on the
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
  picked; calls that can't be fetched claim nothing (principle 1).
- **Only a recent answer is live.** A train's time, the stops left and "get off soon" show only
  while the last answer is under about a minute and a quarter old; back after a while away, the
  step stays but its details say **Updating…** until the next answer (principle 1).
- **Time left on the ride** beside its stops (maintainer, 2026-09-29): "6 stops (~6 min) · next …",
  counted to when the train followed is due where they get off, as the boards count a time; none while
  that stop is beyond TfL's predictions, as no time is claimed there.
- **Get off soon**, from **one stop, or two minutes, out**, said once per leg.
- **Where it shows:** the trip's own screen (the next step over the route), a **card pinned at the
  top of the main view** (the near-me list, under the top bar, not scrolling with it; a station's
  page, and a station, platform or starred journey opened from the list, are each their own view),
  pinned in the same place over the location prompt and the "Finding stops near you…" spinner too,
  while near me can't come up or isn't up yet (maintainer, 2026-09-29: at the top, not centered
  with them). On a short window or with large text, where pinned it could crowd out the prompt's
  buttons, it scrolls with the prompt instead, at its top. And the notifications below; each opens
  the trip.
- **Kept on the device** while on the way (the route, the leg, the train followed), in app storage
  that is never backed up, so it survives the app being closed; where a rider is going is theirs
  (*Privacy*), and it's forgotten when the trip ends.

**Notifications** need Android's notification permission, asked for when the rider taps Start;
declined, the trip still follows them on its screen and the main view's card, and its screen says
get-off alerts are off (checked again on every return, so a change in Settings shows). "Get off
soon" has **its own channel, high importance with sound and vibration**, so it can be silenced
apart from the trip's ongoing notification; it goes **five minutes after the stop** (or 15 minutes
with no time to it), so a stale alert doesn't linger. Until the foreground service below, it's
said only while the app is open, as the trip is only followed then.

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
trains the stop's board listed while the trip was shown, on the ride's line and bound where the rider
gets off (not another branch), the one that most recently left it and isn't behind them (up to three
asked after, a TfL request each, only then; a bus at either pole of the rider's stop pairs counts).
Behind them is a train still to call at a stop they're past, or, seen at a stop, one due there more
than a minute later: a newer train is tried first, so this is what keeps it from being taken for
theirs. Two trains between the same two stops as the rider can't be told apart this way. Seen where
they get off, they've done the ride, whichever train took them: the trip moves on to the next leg,
as when a rider on a train is seen at their station. Another line's train isn't followed, as nowhere else on a
trip is. With none found, the trip waits as it was. The ride's
stops are placed from its line's route, which the trip's cards already hold. Underground, where no
fix comes, it can't tell. The stop's position is the Planner's (or its line's route's); the fix is
only compared with it, on the device, and never logged, kept or sent.
Underground, where no fix comes, nothing is guessed. Outside those windows no fix is asked for; the
battery cost is at most ten fixes per ride after boarding, and about twenty while waiting. Following a bus above ground by location is still to do. It holds a partial wake lock (screen off) for the trip, since
a sleeping phone would otherwise stall the refresh and the get-off alert: renewed for two minutes on
each refresh, so a stalled service lets go. A trip never ended is followed in the background until
four hours after it started; the service then stops, and the app still follows the trip whenever it's open.
A kept trip it can't read is read again for up to 10 minutes, since its age isn't known until then.
If Android refuses the service, or it fails, the trip's screen says it updates only while the app is
open (never failing silently); each opening tries the service again.
The battery cost is that, the ~30 s train lookup while shown, and with location, those few precise
fixes; nothing new leaves the device but the followed train's TfL id.

### Disruptions

A departure time is worse than useless if the service is cancelled or the stop is
closed — showing the number alone is the "quietly wrong" failure (see *Engineering
quality bar*). So stopdash surfaces, for watched stops and their lines:

- **Line status** — minor/severe delays, part-suspended, suspended (TfL line status).
- **Stop closures and stop-level disruptions** — a closed entrance, a moved stop.
- **Cancellations** of specific predicted services, where TfL exposes them.

A disrupted line is always kept flagged — its countdowns are never shown as verified-clean
(principle 1). The chip's label is TfL's own wording where it names the disruption ("Part
Closure", "Suspended", "Severe Delays"), and a concise label recovered from the free-text
reason where the wording is only TfL's vague bus catch-all "Special Service" — which names
nothing on its own, the real state (usually a diversion) living only in the text. So a
diverted bus reads "Diversion"; a vague status whose text yields nothing better falls back
to "Service Alert" — never the meaningless "Special Service" — rather than being hidden.
On a line's page, **each station the alert's text names carries a ⚠** after its name, and **the
same stations are listed beside the alert's chip** ("Diversion  Camomile Street, Fenchurch Street")
so where it is reads next to what it is (maintainer, 2026-09-26) — a first guess at the stretch it
affects, since TfL gives that only as prose. A station matches without the place TfL qualifies it
with ("Stratford" for "Stratford (London)") but not inside a longer station name on the same page, and a
bus stop matches on its own name, not its cross
street, and destinations in a "towards …" list (joined by "and" or "or") are not taken as the affected stops. An alert
written all in capitals carries no case to tell names from words, so it marks every station it
names, destinations included — judged a clause at a time, so an all-caps clause stays generous even
when the alert goes on in ordinary case (maintainer, 2026-09-26): a marker too many beats a missed stop. A page with no
stop list to show — no train to follow (a suspended line), or a stale snapshot whose train-specific
list is withheld — loads the line's stations just to **name** the ones the alert mentions beside the
chip, and lists none: which direction or branch to list would
be a guess on a line with nothing running. A name counts as a place only where it isn't a line, a branch or a holiday of the same name
("Victoria line", "Bank branch", "Bank Holiday"), and only as a capitalized whole phrase; the guess
errs toward marking, since it only adds a marker and never hides or reorders anything. Filling in
the stations between two named ends ("between Oxford Circus and Euston"), and the compact chip, are
follow-ups (`TODO.md` Phase 3).
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
and dismissing that is a second, separate choice.

**A line alert is shown only on rows travelling the way it affects** (maintainer, 2026-09-28): a
northbound diversion says nothing about the buses heading south, and flagging them sends the rider
reading about a stretch they won't touch. TfL names an alert's direction only in its detailed status
response, ~40× the regular one, so the refresh stays plain and each new alert's direction is looked
up once in the background and remembered by its text (reworded text is a new alert). A lookup that
fails is tried again a minute later, then after twice as long each time, up to half an hour, so TfL
failing it over and over costs a detailed response every so often, not one every refresh; a new alert
on the line is still looked up at once, its wait starting over. Until that
answer arrives, or if it fails, or where TfL scopes an alert to no direction or a row has none (most
rail predictions), the alert shows in both directions, as before — the split only ever hides an
alert TfL itself says is for the other way. A trip's cards follow it too, by the direction each ride's
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
waiting for the list to check that line. Expiring a dismissal after a day is a `TODO.md` follow-up.

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
- **Cold load** (nothing saved to show yet): the app waits up to **2 s** for the whole batch
  (maintainer, 2026-09-26), showing its loading stamp meanwhile, so a typical load (well under a
  second to two) paints once, whole, with nothing to jump or tap. Past that, it shows each stop as
  soon as its departures and closure check are back, rather than a spinner until the slowest stop
  answers (one slow National Rail board shouldn't hold up the tube). A stop still out shows as a collapsed card —
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
— stopdash still has no nav library. Its first row opens the favorite-places editor (D9);
the opt-in "refresh widget every minute" toggle (D5) follows below it. The screen composable
is UI-only for the toggle: it reflects the setting and reports a
change, while persistence (a typed DataStore, mirroring the starred-rows store) and the
refresh scheduler (WorkManager) are wired by the activity, so the screen stays
JVM/Robolectric-renderable without touching Android services.

About — and so the license attribution — is reachable in **every** state that can last,
including the location gate when permission is denied and departures never resolve: it is
hosted above the gate, not inside the departures view, so a user who never grants location can
still open it. The gate's "Finding stops near you…" spinner leaves it off: finding a fix and its
stops is time-bounded, and every state it ends in offers About.
Settings shares that top-level hosting, but is reached only from the departures overflow menu
for now (the gate's own menu offers About alone). Opening either takes the departures view
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
spinner and everything else stay where they are either way. On the **location gate** ("finding stops") this is the only update
affordance — the gate has no overflow menu. On the departures **cold-load** spinner the
overflow (with its dot) is already there, so the button is a more **direct** prompt than a dot
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
  Data Layer, and never calls TfL). It shares the phone's application ID, so it can't be released
  before the package rename; its release build fails until the maintainer lifts that gate.
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
  train from the board whichever of them it boards at. A board is kept with the stops' arrivals
  (*Freshness → Shared arrivals*), so it's fetched once for every screen until it's 50 s old, and
  screens asking at the same moment share that one request. A stop's arrivals are as old as their
  oldest part, so a board read from the cache keeps its own age. A
  station whose own board the service refuses takes the board its trains come on: St Pancras's
  low-level Thameslink platforms (SPL) read St Pancras's (STP).
  Only times National Rail gives are shown: a cancelled train, or one "Delayed" with no estimate,
  is left out, and the TfL-run services it also lists (Overground, Elizabeth line, and tube trains
  on shared platforms, such as the District at Richmond) come from TfL alone. A board's train
  joins TfL's line by the **operator's code**, mapped to TfL's own line id, not by its brand name
  (maintainer, 2026-09-26): West Midlands Trains runs as two brands TfL has no line for, and
  "Northern" would take the tube line's id. A line TfL has no entry for at all (the Caledonian
  Sleeper) is one TfL answers "not recognised" for: its route page says the stops are unavailable,
  with no retry, and its status isn't asked again that session — its rows stay unchecked, never
  clean. The optional dependency fails on its own: a failed board (down, rate-limited, a bad key,
  a garbled answer) leaves the station's TfL departures in place, its National Rail lines' status rows saying
  "No data", and is logged; it never fails the stop or blanks the list. **Cost:
  £0**, at most one request per rail station per refresh against the user's own key's limit,
  shared by every screen.
  **Play Data Safety:** no new data type — a request carries only a public station code and the
  user's own key for that service, sent at their request; `docs/PRIVACY.md` names National Rail as
  a recipient, and the Data Safety form and privacy-policy link are re-checked before the release
  that ships it.
- **Reliability:** one required dependency, so if TfL is down or throttling, stopdash shows
  stamped last-good data and an offline/rate-limited notice (never a blank or an
  unlabeled stale number). Added latency lives off every render path (snapshot-render,
  above).
- **A refresh fans out in parallel, capped.** Each stop's requests go out together rather
  than one after another, through one small pool shared by the app and the widget, so a
  refresh costs a couple of round trips instead of one per request. Arrivals are started
  before closure checks, so departures tend to come back first — a best effort, not a
  promise; nothing depends on the order. The pool caps requests *at once*;
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
  stop that has failed since, so the stop is asked again. A junction's **bus poles share one closure request**
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

The widget renders the **persisted last-good snapshot** the app writes — never the
network. It reads the snapshot once when the host asks it to update and renders from it,
so it can't stall on a fetch, and it stamps the data's age and marks it stale rather than
passing old times off as live (D4). Its rows **mirror the in-app list**: the same rows
grouped the same way — one line per (destination, branch), so a branching service's
divergent trains each keep their own countdown — and the user's **starred** services
pinned to the top (D8), sharing the domain's grouping and pinning so the two surfaces can't
drift. It shows the via-branch in the same normalized short form as the app ("Charing X"):
the label is one short form per trunk on every surface, so neither has to measure a fuller
name. (Reordering the nearby set closest-first is
not yet mirrored — it needs per-stop distances the snapshot doesn't carry and is moot once
Phase 2's watched stops replace the interim nearby source.) The app pushes an update whenever it fetches, so the
widget follows the app's last refresh rather than waking on the OS's periodic schedule
(battery). Because the widget's host never re-renders it on its own (no periodic update),
the widget also schedules **one render-only redraw at its staleness boundary**, so a widget
left untouched after the app closes flips itself to the stale `?` treatment instead of
holding live-looking countdowns forever (D4) — a single bounded wake per snapshot, not a
polling cadence, and not a data refresh (fetching new data while the app isn't driving the
widget stays deferred, D5). A setting of the device's clock redraws a placed widget at once too,
since its countdowns and age are drawn against the clock as it read then (*Freshness*). The snapshot also records which of the stops it should show a
refresh asked for but couldn't get, with nothing earlier to fall back on; while any is missing
the widget says its stops are partly out of date, so a first refresh where one stop failed never
reads as complete (principle 1). **Disruptions reach the widget too** (D3): the snapshot keeps each
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
for that note.

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
debug log carries coarse diagnostics only: a stop ID, a line id, an HTTP status, or a
failed Play update check's exception class — never a raw coordinate or the user's API key.

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
Crashlytics keeps its own installation ID); the advertising ID is not collected. Turning the setting
on never releases a crash captured before consent: collection starts at once only if the crash SDK
found none waiting, otherwise the crash is discarded and collection starts on a later launch that
finds none; turning it off stops collection and discards what's unsent, so no report crosses the
consent line either way. A withdrawal reaches the SDKs before the tap returns, an opt-in is stored
before it reaches them, and the stored choice counts only while the SDKs agree with it (or an opt-in
is pending): a failed write, a kill mid-change, or a backup restored onto a new install all resolve
to **off**, so the user is asked again rather than collected from. `docs/PRIVACY.md` is the user-
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
4. **Jank-free.** The app list and the widget render from in-memory/snapshot state; no
   I/O in composition, and no blocking a first frame on a fetch *or a disk read* — a
   stamped placeholder shows at once and fills in when the persisted snapshot loads.
5. **Battery is the user's cost.** Background refresh is bounded and degrades to
   on-demand; anything that adds a wakeup or a location request is a battery change and
   is justified as one.
6. **Say why.** Non-obvious decisions are recorded where the next reader needs them — a
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
  tests, wired into CI's screenshot allow-list.
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
- **D5 — Widget refresh is opportunistic and bounded, not aggressive polling.** Tap,
  host update, and a bounded periodic schedule while plausibly visible; degrade to
  on-demand. The interval is a battery-tuning detail, not a spec guarantee. Keeping the
  widget *honest* is separate from refreshing its *data*: because the host never re-renders
  a static widget on its own, the widget schedules one render-only redraw at its staleness
  boundary (a single bounded wake per snapshot) so it flips to the stale treatment when the
  app is closed (D4) — fetching new data on that schedule was the deferred part.
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
  **letter** ("Stop D" — "Stop" only ever precedes a literal letter), else its **bearing** as a bare
  direction word ("Southbound"), else a bus place's shared **terminus** as an arrow plus the
  destination ("➔ Bank") — settled 2026-09-22, superseding the two-level header (place name once + indented
  sub-header) and the one-level `(place, direction)` step before it. The compass direction is not
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
