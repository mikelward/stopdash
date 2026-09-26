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
  here — a fresh fix is ~1–2 s in the common case, comfortably under the refresh spinner.
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
    nearest Tube shows even where bus stops dominate the immediate area);
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
  nearest two of each mode" fetched a second Overground station 1.3 km from a big interchange). The
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
  simple direct trips can sit beside it; spelled out as *Find a station* on the location
  screen's button, since it needs no location) searches
  TfL's stops by name as the user types (a short pause after the last letter, and at least
  two letters, so a name costs one request rather than one per keystroke). Each match shows
  its name and modes; picking one opens **the near-me list as if you stood at that station**
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
  "Archway" is listed once, not once per stand, and its page (or a To… to it) takes in the stops
  around it (the fold stays inside a To…'s 0.2 mi, so a folded stop is still a destination).
  Same-named places farther apart ("Church Street" in two boroughs) both stay, and a
  result TfL gives no position for is never folded. If TfL's search fails but the list matched, the list's matches
  stand, with a line saying bus stops weren't searched.
- **From… To…** (maintainer, 2026-09-24) — **To…** keeps only the departures that **call at a
  chosen station directly**: the trains or buses whose path, on their own line's route, reaches it
  (or any station in its interchange, or **any stop within 0.2 mi of it** — maintainer,
  2026-09-25: the buses stopping outside a station arrive there as surely as its trains do, so "To…
  Archway" isn't tube-only). A **bus stop** picked as the destination takes in only its
  same-named stands nearby (those the search lists as one), not every pole within 0.2 mi, so a
  route calling at an unrelated stop down the road doesn't pass for one serving it. It is offered in two places. On the **near-me list's
  overflow**, it starts from **the stops the list shows by default** (the nearest of each mode within
  a mile, a tube station among them — the whole set, including a stop whose rows the list folds
  into a nearer stop's, since the trip folds them the same way) **plus any other stop within
  0.2 mi** (the pole across the road), leaving out hidden modes and stops with no routes, and titled "To ‹place›"; back returns to the list. The starting
  stops follow the rider: a refresh or a return to the app re-locates first, as the list does, and
  works them out again from where the rider now is. Its rows read like the list's: a line once,
  from its nearest stop, nearest first, with distances. On a **searched station's page** (reached with the overflow's *From…*), it works the same way
  from the station's position — its own stops, the default stops around it and any within 0.2 mi —
  titled "From ➔ To", and back returns to the station's page. Either way it is a look, not
  a pin: nothing is saved but the destination, which *To…*'s own *Recent* remembers. A departure
  whose route is still loading, failed to load, or can't be followed is left out and the page says
  so ("Checking routes…", "Some routes couldn't be checked") rather than pass a short list off as
  complete (principle 2), and the debug log names each departure it couldn't check — its line, its
  boarding stop and why — as it does for a trip's legs and a journey card's trains; only when everything was checked does it say "No direct trips to ‹place›
  soon". Direct only: a trip needing a change is **journey planning**, the eventual goal and a
  follow-up (`TODO.md`), and starring the trip as a journey is one too. The routes are the same
  per-line lookups a route page makes (a request or two per line a day, cached); the destination
  is TfL's stop lookup of the picked station and one of the stops around its public position,
  once each, and a trip from here fetches the arrivals of
  its origin stops like the list does. The destination's name and id go only to TfL, as the
  station search's already do (*Privacy*).
- **Farther stations** (maintainer, 2026-09-25) — where the near-me list reaches only one tube
  station, the rest of the network can be two miles off. Below the loaded places and the *More*
  controls, a **collapsed card** stands for the nearest station of each **rail line** the loaded
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
  out too. A closure still shows at a place that keeps an unhidden mode, so hiding one mode never
  hides a closed stop the user still rides from; a stop serving only hidden modes isn't checked at
  all, since its closure matters only to a rider of that mode and checking it would spend the
  requests hiding saves. Hiding takes
  effect on the list at once, and the fetch saving from the next re-locate; if every mode nearby is
  hidden, the stops are still fetched so the list can say what's hidden rather than claim nothing
  runs. While any mode is hidden, **a one-line banner** over the list names them ("Bus hidden") with
  **"Show all"**, which brings them back from the same fix without a new lookup, so a shorter list
  never passes for all there is (principle 2). The hidden set is a setting, kept on the device.

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
(*Concise copy*); the bare " Station" is dropped too, so a terminus like "Battersea Power
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
asset** (regenerated from TfL, no runtime cost on any path); incomplete or stale data
degrades to "show what TfL said", never to a confident wrong merge.

The one exception to that raw fallback is a **single-branch stop** — one every serving pattern
reaches on the same trunk. There the branch names which trunk the train came up behind this
stop, never a choice, so the label is dropped even for a short-working the asset doesn't model
as a terminus. King's Cross is Bank-only, so a Bank-branch train there terminating at Golders
Green or Finchley Central shows no "/Bank".

Where the pair won't fit, the **branch is kept whole** — it is the cue that tells the two trunks
apart, so truncating it would lose which train this is — and the **terminus yields**: it shortens
common whole words to a compact form (`East`→`E.`, `Street`→`St`, and the rest —
`DestinationAbbreviations`), then, for a name no word maps, drops to its **floor** — the first word
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
so a miss there is a working the sequence doesn't model. **A station TfL lists under one id and routes
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
London Northwestern Railway is pinned to LNWR, since the rail feed's spelling of it yields a code
too long for the pill. **Every
pill shares one fixed width**, sized to the widest code shown (a four-character bus route),
so the codes form a tidy left column the eye runs straight down a card list, rather than a
ragged edge that steps in and out as each code's length changes; a shorter code centers in
the shared box, and the width holds the widest code complete rather than truncating it. The
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
route-diagram color rather than the brand, as for Great Northern's purple). One exception, by
the maintainer's choice (2026-09-24): London Northwestern Railway has no template color, so it wears
the green from Wikipedia's *West Midlands Trains* route-map legend, picked from two candidates
rendered side by side, until a template or operator value confirms or replaces it. The operator code
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
or bus stop picked from the station search, never an address or a map point — from the rider's
nearest stop of any mode (the Planner walks on to a better one itself), or from the *From…* station when one is set. The search page keeps its look (each
result's name over its modes); it gains only a "From" chip naming the start ("Here", or the *From…*
station). The trip opens on a **list of routes, best first**: ordered first by how far StopDash
stands behind each route (tiers, below — usable before not, fully live before "est."), and within a
tier by the earliest end-to-end arrival, worked out leg by leg from live trains (below). The first
route is therefore the fastest one StopDash can vouch for, not an earlier estimate. Routes riding the
same lines in turn (changing at a different stop) would read as identical cards, so only one shows:
the best of them, unless another shares a card as below. Routes whose first ride goes between the
same two stops by the same mode and then ride the same lines — the 43 or the 134 to Highgate
station, then the Northern line — **share a card**: its header shows that leg's lines as **one pill
cut diagonally** ("43/134", read as "43 or 134"), then the later lines and the best of their
arrivals; under it, a live row for each of those lines, best first (maintainer, 2026-09-26). Tapping
the card opens its best route. **Every route looks alike** — no
route is expanded — as a card whose top row is its lines' pills in order (no station names, no
arrows), a ⚠ where a leg is disrupted, and **duration · arrival** ("22 min · 08:24") at the end,
where a number of changes might otherwise go; the duration is from now to that arrival, so it
takes in the same walks, waits and legs. The pills wrap onto a second line when a route has
many legs, and the time drops below them when it doesn't fit beside them. Under the top row is the
first leg's live row, the train the rider would catch now, **read like the main screen's row for that
stop and line**: the line's pill, the destinations its trains show, and their times. While the
line's route is still being checked, it shows the line's live trains at that stop (those heading for
the Planner's terminus, whether or not a name adds a place such as "(London)") as the main screen
does, timing nothing until the check vouches for them: only a bus to the terminus on no named branch
shows plain meanwhile, and one that may skip the rider's stop (another terminus, a named branch such
as "via Bank", or any rail service, which may run fast to the same terminus) grayed until then;
before any live train, the Planner's terminus (never the stop the leg gets off at, which reads as
the line's destination), failing that "from" the boarding stop. The times read "Loading" until the
boarding stop's arrivals arrive, and "–" when none can be vouched for (*Text is chosen to fit its
space*). A bus stop the Planner names by its stop pair (a road's two poles) boards at the pole its
bus uses, worked out from the line's route: the pole the Planner names can be the other side of the
road, where the buses run the other way. Both poles are fetched, and the times read "Loading" until
the route says which. A bus station's stands are in no pair, and the Planner can name one the line
doesn't use, so the ride gets off at the route's own stop of that name. A route that is all walking (two stops close
together) shows "Walk" where the pills go and its minutes, with no live row and no arrivals request.

The trip's top bar carries the app's **overflow** as the list's does — its red dot while an update
is available, "Update available", "Send bug report" and About — so a problem seen on a trip can be
reported from it (maintainer, 2026-09-26).

Tapping a route opens it, drawn with the list's own parts, **every leg a card**: the leg's platform header ("Highbury
& Islington – Platform 2") over a route card of that line's live departures toward the change, then
"6 stops to Whitechapel", then the next leg's header and card at the change station, down to "2 stops
to Canary Wharf". A walk reads "5 min walk to ‹place›"; a walk to a station's entrance, which the
Planner names by its street then the station ("Cannon Street, Cannon Street Rail Station"), goes by
the station ("Cannon Street"), never the street repeated. Line status and stop closures for every leg show exactly as on the list:
the ⚠ on a disrupted row, the status chip on a line with no trains, the closure card at a closed
stop. **Tapping a leg's row opens its line's page**, as a row on the list does: the line's full
service alert, with the list's × to dismiss it line-wide (*Disruptions*), and its stops (maintainer,
2026-09-26); back returns to the route. **Every stop the rider gets off at** is checked too — each leg's alighting stop, including
both ends of a walk between stations, and the destination — as a starred journey's far end is
(*Alerts for the journey shown*): a closure or move shows where the rider gets off, from the same
few-minute cache, and a failed check keeps the last known notice and claims nothing new. Routes sort
in tiers: a route with a suspended or closed leg, or a closed stop it gets off at, sorts below every
usable one, whether live or
estimated (below), so a route that can't be ridden is never listed first while one that can exists.
A route whose line status or closure check failed with nothing known yet sits between the two: below
every route checked and open, above those known not to run, and says it couldn't check for
disruptions, until a check succeeds.

**TfL's Journey Planner chooses the lines and changes; StopDash's live arrivals give the times**
(principle 1). The first leg counts down like any row. From "Here", the rider still has to reach the
first stop. The list's distance is a straight line, not a walkable path, so the walk is
estimated **conservatively** on the phone: that distance stretched for detours, at an unhurried pace,
shown as the route's first dotted link ("~7 min walk") so the rider sees the assumption. First-leg
trains that leave before the rider can get there are grayed; the estimate errs toward graying a
train that could be caught rather than offering one that can't (from a *From…* station the rider is
taken to be there already). A later leg shows the change station's live
trains, with those the rider can't reach in time grayed. The **arrival is worked out leg by leg**:
the first first-leg train the rider can reach plus its run time gives the time at the change, plus
the change or walk time; the first live train there that the rider can reach starts the next leg,
and so on to the end. A train only counts for a leg if its route **calls at that leg's alighting
stop**, as a direct trip's trains must reach the destination (*Journeys*): on a branching line, a
train for the other branch is shown but never used for the arrival. A train the list wouldn't count down — canceled, or with no time TfL
stands behind (*Disruptions*) — is never a reachable train: it is skipped for the arrival and the
ordering, and shown as the list shows it.
Run and change times are the Planner's, so the arrival reads "about". The same end-to-end arrival
orders the routes within a tier, so "fastest" never assumes a connection the rider can't make. A leg with no live
train in reach yet (none predicted that far ahead, its arrivals failed, or they have gone stale under
D4) falls back to the
Planner's own time for it, as long as the rider can reach the Planner's departure for that leg
(the legs before it, or the walk from "Here", get the rider there in time). A Tube, DLR, Overground
or Elizabeth line leg the rider reaches past its live predictions (its trains running, just not
predicted that far ahead, its predictions reaching at least 20 minutes out) is boarded as they
arrive, since those lines run every few minutes (maintainer, 2026-09-26); predictions ending sooner may
be the night's last train. Otherwise the Planner's train is missed and nothing says when the next one
leaves (a National Rail, tram or bus leg, a line with no trains, or arrivals that failed), so StopDash
**withholds that route's arrival** rather than guess a wait: the route shows "arrival unknown" in place of duration · arrival, and
sorts after every route in its tier that has an arrival, until a refresh brings live trains for the
leg. Otherwise its arrival reads **"est."** instead of "about", and within
its tier it sorts **after every route whose legs are all live**, so an estimate is never listed first
while a live-confirmed route exists. Walks
between stations show as a dotted link with the Planner's minutes. **Every walk the Planner includes counts** toward which
trains are reachable and toward the arrival: one before the first ride (from the stop sent to a
better one), between stations, and after the last ride to the picked stop.

**Lifecycle.** The trip screen appears at once, titled with both ends, with a "Planning…"
placeholder; the Planner call runs off the render path. A plan is kept in memory for the trip and
reused if the same trip is reopened within 15 minutes. From "Here", a re-locate (the crosshairs, or
a fresh fix) that resolves to a different nearest stop discards the plan and re-plans at once,
showing "Planning…" rather than the old station's routes. Any new fix, even one that resolves to
the same stop, keeps the plan but recomputes the walk to the first stop and re-ranks the
routes, so the reachable first-leg trains follow the rider. While a re-locate is in flight, or after
one that fails (which keeps the old stop, as on the list, and shows the list's location banner over
the trip), the origin is unconfirmed: until a fix is confirmed again, the walk is
from the last confirmed position and every route's arrival reads "est." at best, never live-confirmed. A plan older than that is re-planned, on open
or when it expires while the screen is visible, showing the older plan stamped with its age meanwhile,
so a route that has since become viable can appear. An opened route is matched across a re-plan by its lines and
stops in order, never by its place in the list; if the new plan no longer has it, the trip goes back
to the refreshed list rather than keep showing a route the Planner no longer offers. The live times refresh with the list's refresh cycle
while the screen is visible, and follow the list's staleness rule (D4): a stale leg withholds its
countdowns rather than show them as live, and stops feeding the route's arrival and ordering: it falls
back as above ("est.", or "arrival unknown" when the Planner's departure is no longer reachable). If planning fails, the screen says why with a **Retry**
("Couldn't plan the trip: you're offline", as the list words its errors), over the last plan for the
trip if one is held, never a blank. Retry is disabled while its call is in flight. A change station whose arrivals fail withholds that leg's times
and falls back the same way; it is retried on the next refresh. Nothing retries in a loop:
Planner and arrivals requests go through the same rate limiter as every TfL request.

**Walking** is capped at 15 minutes per walk (the Planner's `maxWalkingMinutes`), so it never offers
a long walk beside the rides; configurable later.

**One stop per end, every station of a complex.** The Planner takes a single stop or station id for
each end, not an interchange's or a folded search result's several stands, and it leans toward the
end's own mode: aimed at King's Cross St. Pancras's Underground station it offered a change onto the
Metropolitan line where Thameslink runs direct to St Pancras. So the start is one stop (the nearest
to the rider, or the *From…* station's own stop), and a picked **station complex** (an interchange,
TfL's `HUB…`) is planned to **once per station code** — never merged, since neither names nor ids
tell a station's platform variant from another station — **plus once to one of its bus stops** (the Planner walks between
stands), the requests in parallel (maintainer, 2026-09-26: the best way there whatever the line or
mode). The answers merge, and of the routes not riding a hidden mode, the six arriving soonest by
the Planner's timetable are timed; routes show as each answer lands. If some stations can't be planned to, the others'
routes stand and the trip says "Couldn't plan to every station", with a retry; only a whole plan is
reused. Not the complex's centre point: that ended every trip at a street address with a walk and
didn't lift the lean. An ordinary pick is planned to as picked, the Planner walking the last
stretch itself where a neighboring stop serves the trip better, so a same-named stand the search
folded into the result is reached on foot rather than lost.

**What leaves the phone:** both ends of the trip go to TfL's Journey Planner as stop ids — the
nearest stop's id stands in for the rider's position, never a coordinate. It is free and keyless
(within TfL's anonymous budget). The Planner is called when a trip opens without a plan under 15
minutes old (the plan is held in memory only, so a trip reopened after process death re-plans), again
every 15 minutes while the screen stays visible, on a re-locate to a new nearest stop, and once
per tap of Retry: about four calls an hour for a trip left open, plus one per re-locate or Retry the
rider makes. To a station complex each of those is one call per station plus one for its bus stops
(about six at King's Cross, two or three at a typical interchange): about 24 an hour at King's Cross. Ranking needs every listed route's live trains, so each refresh fetches arrivals
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

**Later:** mode toggles at the top of a trip, remembered across trips (the Planner takes a mode
list), and avoiding a line, done on the phone: the Planner has no way to exclude a line, so the trip
asks it for alternative routes and drops those using the avoided line.

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
Pulling the affected stretch
("Diversion Moorgate to Monument") and the compact chip are follow-ups (`TODO.md` Phase 3).
TfL's `isNow` flag is not used to hide a "future" alert: it reads `false` even for planned
closures currently in effect, so telling current from future needs the dates in the text,
and showing a not-yet-current diversion is the safe side (an extra chip beats a hidden
disruption).

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
prunes it only for a line whose status TfL actually returned, as for places. Expiring a dismissal
after a day is a `TODO.md` follow-up.

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
  departures") rather than keep a list of dashes. Line status is checked once
  every stop is back, so until then the list says it's still checking for disruptions. A
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
  of its twin stop ids isn't shared, since another screen may place it under the other. Nothing is
  saved to storage.
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
— stopdash still has no nav library. Its first setting is the opt-in "refresh widget every
minute" toggle (D5). The screen composable is UI-only: it reflects the setting and reports a
change, while persistence (a typed DataStore, mirroring the starred-rows store) and the
refresh scheduler (WorkManager) are wired by the activity, so the screen stays
JVM/Robolectric-renderable without touching Android services.

About — and so the license attribution — is reachable in **every** state, including the
location gate when permission is denied and departures never resolve: it is hosted above the
gate, not inside the departures view, so a user who never grants location can still open it.
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
red dot, and the menu gains an "Update available" item that opens the Play listing — Play
does the download and install. It is a lightweight nudge, not a banner: a dot costs no row
or top-bar width, and there is no in-app update flow to shoehorn a download/restart UI into.
The loading screens also surface the same nudge as an outlined **Update available** button
below the spinner. On the **location gate** ("finding stops") this is the only update
affordance — the gate has no overflow menu. On the departures **cold-load** spinner the
overflow (with its dot) is already there, so the button is a more **direct** prompt than a dot
the user may not notice while waiting, not the only path to it. Either way a user sitting on a
slow fix or a cold load can act on the update without hunting a menu, and it stays a secondary
offer (outlined, below the spinner), not the screen's main action.
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
2026-09-24); any other English locale reads the base. TfL's own line and place names stay as TfL
spells them.

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
  the APK; a per-user key does neither.
- **National Rail (optional, the user's own key).** TfL's arrivals feed has no times for
  National Rail services (Great Northern, Thameslink, Southern…). With a free Rail Data
  Marketplace key pasted in settings (maintainer, 2026-09-24), a rail station's departures also
  come from National Rail's own live departure boards (Darwin's `GetDepartureBoard`, by the
  station's three-letter CRS code), alongside TfL's; without one, a disrupted line's status row there says "No
  key" and opens Settings (*Departures*). Like D7, stopdash ships no key. TfL's `910G` ids end in the station's TIPLOC, and
  NaPTAN (DfT, Open Government Licence v3.0) pairs each TIPLOC with its CRS, so the app bundles
  that table (rebuilt weekly with the station list) and the two join exactly, never by name.
  Where TfL lists one station under two National Rail ids, its board is fetched once and shown
  under one of them, the first whose own TfL fetch works, which keeps it while it keeps asking.
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
  £0**, one request per rail station per refresh against the user's own key's limit.
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
  only; a failed request is never reused. A junction's **bus poles share one closure request**
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
widget stays deferred, D5). The snapshot also records which of the stops it should show a
refresh asked for but couldn't get, with nothing earlier to fall back on; while any is missing
the widget says its stops are partly out of date, so a first refresh where one stop failed never
reads as complete (principle 1). **Interim data source**: until Phase 2's user-chosen watched stops exist, the
widget shows the last *nearby* set the app fetched — "the stops near where you last
opened the app". Phase 2 replaces that with the watched stops; a live-refresh cadence for
the widget when the app isn't driving it is deferred (D5).

### On the watch

The Wear OS companion (`dev-docs/wear-os.md`) shows the widget's snapshot as the phone last sent
it, on a tile, a watch-face complication and a small app. It never calls TfL itself, and like the
widget it renders only what is stored, stamped with its age and marked stale rather than passed
off as live (D4).

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
  refreshed, partly refreshed, rate-limited, TfL unreachable, no stops, or recent enough to skip.
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
movements, and no coordinate, stop list, or API key in logs, commits, PRs, or fixtures; the
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
data), the starred-row keys and the hidden modes — never a coordinate or a key. The watch sends back the rows its
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
handler placed in front of Crashlytics' own. Usage stats carry Firebase's
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
  TfL fixtures (never the live API in tests, and never a real coordinate in a fixture).
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
  `mikelward/androidlog` buffer, resolved as a published dependency.
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

- **Door-to-door routing.** Trips go stop to stop (*Trips with a change*): no addresses, map
  points, or walking directions to a door. StopDash still leads with "what's next from here".
- **Non-TfL operators** outside the Unified API (coach, etc.), National Rail aside: its
  times come from National Rail's own feed once the user adds a key (*Data source*). Without
  one, TfL gives no times for them, so a National Rail line TfL reports disrupted at a station
  shows only as its status row, saying "No key" where times would be (*Departures*); one in good
  service isn't listed.
- **Ticketing**, Oyster/contactless balances, and service maps.
- **Writing to TfL.** StopDash is read-only.
- **Continuous background location / geofencing.** Location is used on demand in the
  app to find nearby stops, never tracked in the background.

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
