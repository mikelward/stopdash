# StopDash — privacy

This describes what stopdash keeps, what leaves the device, and — in detail — what its
on-device diagnostic log carries. It is the disclosure `AGENTS.md` requires to exist
before any on-device logging ships. The full store-facing Play Data Safety statement is
finalized at release (see `TODO.md` Phase 5); this document is the engineering-level
truth those answers are built from.

## What leaves the device

StopDash is a **client-only** app. By default it makes network calls to just two places, plus a
third only if you add a National Rail key: **Transport for London's Unified API**, the calls that
*are* the product; — on a release build only — **Google Play**, to ask whether an app update is
available (detailed below; it carries nothing about you); and, with a National Rail key, **National
Rail's live departure boards** (the Rail Data Marketplace, detailed below). **Firebase** is added
only if you turn on *Help make StopDash better* (off by default; see *Crash reports and usage stats*
below). Your **location, the stops and lines you look up, and your API keys** go only to TfL, or
with a National Rail key also to National Rail (if you opt in, Firebase sees only the rough region
Google infers from your IP address), and only ever what a request needs to answer your question
about departures: the details of what you're looking up (your location for "near me now" and for
a trip you plan from it —
**precise** if you grant precise and a precise fix is available, otherwise approximate (if you grant
only approximate, or if no precise fix can be obtained) — or the stop or line you're after) and, if
you've set an optional TfL API key (`app_key`), that key as your own credential, sent with your own
TfL calls and nowhere else. Location is **sent only on demand**, never in the background: when
you open the near-me list, refresh it, or come back to the app, and while the list is on screen,
when you've walked 100 m or more since it was found (at most once a minute), so it follows you. The one
use beyond that is a trip you start: it's checked on the phone while you walk to a stop you're
boarding at, while you wait there, just after you board, and as your train nears the station you get
off at, app open or closed, and never sent (see *On the way*, below).

**National Rail times (optional).** If you paste a National Rail API key (from the Rail Data
Marketplace) in Settings, stopdash also asks **National Rail's live departure boards** for the
departures at a railway station you're looking at. That request carries only the station's
three-letter National Rail code (e.g. `WAT` for Waterloo) and your key, never your location. Your
key is your own credential, sent only with those requests, never logged. Without a key, no
request goes there. The station codes themselves come bundled with the app, built from NaPTAN,
the Department for Transport's public stop list.

Nothing else leaves the device *to stopdash* unless you turn on **Help make StopDash better**
in Settings, which is **off by default** (see *Crash reports and usage stats* below): no
third-party tracker, no ads, and no server of stopdash's own.

On a release build, stopdash makes **one** other kind of network call — to **Google
Play**, asking whether an app update is available (this drives the "update available" dot
on the menu). It is a Play Services query about the app's *own* version; it sends **no**
location, watched stops, API key, or any other user data — nothing about you or your
travel — so it adds no new Play Data Safety category beyond Google Play's existing role as
the app's distributor. It is free, runs release-only (a debug build isn't a Play app), and
silently does nothing if Play is unavailable.

Three channels other than a TfL or National Rail request can carry **user data** off the device,
and all three are under your control rather than stopdash's. The first is the **crash reports and
usage stats** you can opt in to (see below): off unless you turn them on, they send Firebase crash
details, app interactions, device details and identifiers, and the approximate region Google
derives from your IP address, but never your location, stops or journeys. The second is **your
own Android backup and device-to-device transfer**, if you have it enabled: like any app's data, your saved stopdash
data (your settings and its last-good departures snapshot, which lists the widget's nearby stops nearest first, and which of them the app shows each line from, but never how far each was) rides it, so a phone swap keeps your
setup — all but the crash-report opt-in, which stays with the install, the rows your watch's complications show, which the phone relearns from the watch, and the stops the app last found near you, which say where you are now and are kept on the phone only. That is Android's channel, tied to your Google account — not something stopdash sends.
The third is a **bug report you choose to send** (see *Sending a bug report* below): it hands
the app you pick a diagnostic report that, unlike everything else here, **includes your exact
location and a screenshot of the screen you sent it from** — but only after a consent screen
that says so, and then to your clipboard and the app you pick (the clipboard copy happens as
soon as you confirm — detailed below).
So the guarantee is precise rather than absolute: **without your opt-in, the only user data
stopdash itself sends off the device goes in its TfL requests, and its National Rail requests if
you've added a key** (the Play update check carries none), **plus, if a paired watch has the
StopDash watch app, the widget's stops and departures, and any trip on the way, to that watch** (above); with it, the crash reports and usage
stats above go to Firebase too. Android's backup
carries your saved data under your control, and a bug report carries what you consent to share.

**Your Wear OS watch (optional).** If a watch paired with your phone has the
StopDash watch app installed, the phone sends it what your home-screen widget shows, so the watch
can show it too: the widget's stops (their names and IDs, and the nearby stops each is compared
against, which are worked out from your phone's last location), the order they sit nearest you and which of them the app shows each line from, never how far each is, so the watch shows each line once, from the same stop as the app, their departures, TfL's public
service status for their lines (such as "Severe Delays") and the work TfL has announced on them
for a later day (such as a weekend closure), for each direction where TfL says an
alert only affects one way, whether you dismissed it in the app, and which of those stops a bus alert is wholly behind (worked out on the phone from TfL's routes), which rows you've starred, and which kinds of transport you've hidden, plus TfL's current routes for a branching line where they differ from the ones built into the app (public data, the same for everyone). It never sends your coordinates or your API keys, and the watch never contacts TfL,
National Rail or anything else itself. In the other direction, the watch sends the phone its
requests to refresh, which carry only a random request number, and the row each StopDash complication shows (its stop ID, line and direction), so the phone keeps those rows in what it sends. This goes through **Google Play
services' Wearable Data Layer**: over Bluetooth when the watch is near, but when it isn't (a watch
on Wi-Fi or mobile data) it may pass **through Google's servers**. Nothing is sent when no paired
watch has the app. The watch keeps only the latest copy, never backs it up, and doesn't log it.
While the phone follows a **trip on the way**, it also sends the watch that trip, so the watch can
show where you are in it: its steps (each walk and ride, the line, and the stations at each end, so
where you're going), the step you're at with what to do there now, and the trains for your next
ride. It goes the same way as above, and the phone takes it off the Data Layer when the trip ends.
If the phone app stops without ending the trip, the watch stops showing it after 15 minutes, but the
Data Layer keeps that last copy until the phone next sends or removes a trip. The watch app itself
holds it in memory only.
For Play's Data Safety form, the determination is that this moves your own data between your own
devices and stopdash never receives it, so it adds no data type *collected* by the developer.
Google's Data Layer documentation says what passes through its servers is end-to-end encrypted
between your phone and your watch, and between them over Bluetooth the link is encrypted too.

**Find a station** (the menu's *From…* and *To…*, and a station's *To…*) sends the name you type to TfL's
stop search, once you pause typing, and then the chosen station's id to look up its stops and
departures, and the station's own position (a public place, not yours) to find the stops around it (for *To…*, the stops and the routes of the lines leaving where you start from; a *To…* from the near-me list starts from the location the near-me list was found from, which goes to TfL's Journey Planner as the trip's start — see **Trips with a change** — and refreshing it or coming back to the app finds your location again, exactly as the near-me list does). The name isn't saved,
logged or sent anywhere else. The last eight stations you open from *From…*, and separately the last eight
destinations you pick in *To…* (a stop, or a place or postcode with the name and map position TfL
gave it), are remembered on the device to list under *Recent*, in app storage
that Android never backs up or transfers; they are
never logged or sent anywhere, and clearing the app's data removes them. So that a starred stop
can be listed by name there, the place each starred row belongs to (its stop area or station, as
TfL names it) is kept the same way, and forgotten once you unstar it. The search also lists
and matches your starred stops and the stops the app has lately shown you, all read on the
device; none of that is sent anywhere either.

**Lines…** (the menu's *Lines…*) asks TfL once a day for its public list of lines and keeps it in
the app's cache; what you type is matched against that list on the device and never sent anywhere.
Opening a line asks TfL for that line's status, naming only the line. Opening a stop on its map asks TfL for that stop's public record, naming only the stop: a tube, rail or tram station's for its fare zone (kept in memory for a day, never stored), a bus stop's area for its letter (kept with the routes, below). The last eight lines you open
are remembered on the device to list under *Recent*, in app storage that Android never backs up or
transfers; they are never logged or sent anywhere, and clearing the app's data removes them.

**Favorite places** (Home, Work, School or a place of your own, saved from Settings) are stored by
**location**. When you add or edit one, the stop or station you type is sent to TfL's stop search,
once you pause typing, to find its position — the same search *Find a station* uses. You can instead
type a **postcode**: when you tap to look it up, it is sent to **TfL's Journey Planner**, which returns
the place(s) it names for you to choose from. A postcode is the same kind of location data the app
already sends to plan a trip — no new kind of data leaves the device. Either way the text you type in
the search field isn't saved, logged or sent anywhere else; only the place you then **choose** is
saved — by its coordinate and the name it resolved to (for a postcode, that name may be the postcode
itself), the same as choosing a stop. The saved place — its
coordinate, its label, and the **name of the stop or station it resolved to** (shown under the label,
e.g. "Oxford Circus") — is kept on the device with your other settings, so it rides your own Android
backup and device transfer like the rest (above); it is never logged or put in a bug report, and
deleting the place or clearing the app's data removes it. Tapping a saved place plans a trip to it
from where you are (see **Trips with a change** below).

**Trips with a change** (*To…* from the near-me list or a *From…* station) send both ends of the
trip together to **TfL's Journey Planner**. A trip from the near-me list starts from **your
location**, the position the near-me list was found from, so the Planner can walk you to whichever
stop or station serves the trip best. That is usually the position the nearby-stops lookup has just
sent TfL; when the app reused a recent lookup of the same spot instead (below), opening the trip is
what sends it; a trip from a *From…* station starts from
that station's stop id, as does **Plan again** on a trip on the way, which starts from a stop on its
route (the one still ahead nearest you, worked out on your device: your location isn't sent). The destination goes as the stop you picked. When you pick a station complex
such as King's Cross St. Pancras, the Planner is asked once for each of its stations and once for
its bus stops, each request carrying the same start. A trip **to a saved favorite** sends its stored
**coordinate** as the destination — the Planner walks the last leg to it — while the favorite's name
and id stay on your device. To a favorite, or a place or postcode picked in *To…*, when the fastest
route there changes, the Planner is asked once more between the same ends, via the stop that route
gets off at, to find one ride the whole way. A trip to a place (a favorite, or a place or postcode
picked in *To…*) also asks TfL once for the **stops near that place's coordinate**, to list the trains
from your stops that go straight there; it isn't asked when you're already within 200 m of the place.
Your location and a favorite's coordinate are the same **Location** data
the app already shares with TfL, so they add no Play Data Safety category. The Planner is asked when a trip opens, again when you've moved on from where it was planned, about every 15 minutes while it stays on screen (every 5 while a route's arrival can't be told without a fresh plan), and
when you tap *Try again*; while a trip is on screen, the departures at each stop where a route
boards are fetched from TfL like any other stop's, along with its lines' status. The plan is held
in memory only, never saved or logged beyond coarse diagnostics (a stop id, an HTTP status), and
nothing runs once you leave the trip.

**A trip on the way** (after you tap *Start* on a route) is kept on the device until you arrive or
end it: the route's stops and lines, the leg you're on and the train followed, and where you chose to
go (the destination's stops, or the position of the place you picked: a favorite, a place or a
postcode), in app storage that
Android never backs up or transfers, so the trip survives the app being closed. It's never logged
beyond coarse diagnostics (a line id, an error kind) or sent anywhere, except to your own watch if it
has the StopDash watch app (see **Your Wear OS watch**). If you've placed the StopDash widget, its
next step and the trains at your next change are kept on the phone for the widget to show (on the
lock screen too, if the widget is there), also never backed up, and removed when the trip ends. While a trip is on the way, app
open or closed, stopdash asks TfL about every 30 seconds (with the app open, as often as every 10
seconds while you're moving) where the followed train will call next, by TfL's own id for that
train, and for the departures at a stop where the next leg boards; neither says anything about you
that the trip's departures don't already. It also asks TfL as often for the status of
the lines the rest of the trip rides, and (at most every five minutes, shared with the trip's screen
and the list) whether the stops it still has to reach are closed or moved; these name the lines and
stops of your route, which is why they're listed here, and go only to TfL, which the trip already
asks about the same lines and stops. Ending the trip, or arriving, deletes it. The "get off
soon" alert names the stop and the trip's destination on your lock screen, like any notification,
the "time to board" alert likewise names the line, the stop you board at and the destination, and
the "route disruption" alert names a line of the rest of the trip and its disruption, a stop
still to reach that's closed or moved, or a line with no train predicted at the stop you change onto
it at, with the destination; you can turn any of them off in
Android's settings for StopDash. While a trip is on the way, an ongoing
notification shows its next step, until you arrive or end the trip (or, for a trip left running,
four hours after it started). With the watch app, your watch shows the step you're at in a
notification of its own, made on the watch from the trip it already has, and gone when the trip
ends. If you've allowed location, stopdash takes your
precise position about every 30 seconds while you walk to a stop you're boarding at, only to see
whether you've reached it, and for the first ten minutes you wait there for your train, only to see
whether you've already left on another (at a later stop of the ride, or well along it), a few times in the five
minutes after your train leaves that stop, only to see whether you got on (you're still at the stop
if not), and a few times as your train nears the station you get off at, only to see whether you're
already there. With the app open, it follows your position as you move instead, every few seconds,
for the same purposes and only while one of them applies. Each is compared on the device with the public positions of the ride's stops (and a
station's entrances), and with where a walk ends to show how far is left, and then dropped: never
logged, kept, or sent anywhere. The newest is held in memory for 2 minutes, so a walk that begins in
that time can show an estimate of how far it is, and is then let go, or sooner when the trip ends.

**Starred journeys** (two stops you travel between) are kept on the device with your other
settings and stars, so they ride your own Android backup like the rest (above); they are never
logged or sent anywhere. For the widget, the departures at a journey's nearer stop, and which of
them reach the other end, are saved with the widget's other departures on the device. Showing a journey's trains fetches the departures at its nearer stop
from TfL, like any other stop, and at the stops beside it; to find those, and the stops a short walk
from the other end, it asks TfL for the stops around each end's public position (TfL's own, never
yours), as opening a station does, and keeps the answer on the device for up to a day.

**Journey alerts**, if you turn them on for a favorite journey, keep each direction's days and times
with the journey on the device. During those times stopdash checks in the background, about every
15 minutes, by asking TfL for the status of the journey's lines (the same request the app makes for
the lines near you), and shows a silent notification on your phone naming the journey's two stops and
the disruption. What each alert last said, and whether you swiped it away, is kept on the device so
the same disruption isn't shown again. It is never logged or sent anywhere, stays out of your Android
backup and device transfer (it describes this phone's notifications), and is deleted when the
disruption clears, when the alert's time window closes, when you turn that direction's alerts off or
remove the journey, or when notifications are turned off. The on-device debug log notes, for each
check, when it was due and ran, how many journeys were being watched, the ids of the lines it asked
about with TfL's word for each ("Severe Delays"), and how many alerts it showed or took down; and when
the next check is due. It never names the journey's stops.

Journey alerts are held back while your phone is on a mobile network outside the UK. Each check reads
the country of the network your phone is connected to, which Android reports without any permission,
and compares it with the UK on the phone. It is never kept, logged or sent anywhere; the on-device debug
log notes only that a check was held back.

**Held in memory only:** the last precise (GPS) position, for up to 10 minutes, so a rough
network position that comes in while you haven't moved doesn't replace it. It is never written to
storage or logged, is sent only as the position of a nearby-stop lookup or as the start of a trip
you open from the near-me list (and, when a lookup used it, in a bug report you choose to send, as
described below). Past 10 minutes it is no longer used,
and it is deleted the next time the app takes a location or when the app's process ends, whichever
comes first.

**Kept on the device, never backed up:** to skip a repeat stop lookup when you reopen the app
near where you last used it, stopdash keeps the **positions of its last few nearby-stop lookups**
(up to four places) and the stops found around each, for up to a day, in the app's cache
directory. Android never includes that directory in a backup or device transfer, it is never
logged or sent anywhere, and clearing the app's cache removes it; an entry older than a day is
deleted the next time the app looks up nearby stops. The stops found near the places your trips go
to are kept the same way, in a file of their own (up to four places, for up to a day).

The **routes and stop areas the app fetched** (a line's stops, and the stops grouped with a
journey's starting stop) are kept the same way, for up to a day, so reopening a route doesn't ask
TfL again. They are TfL's public network data, but which ones are there says which routes you
looked at, so they stay in the app's cache directory too: never backed up, logged, or sent
anywhere, and an entry older than a day is deleted the next time the app starts or looks up a
route or stop area. The branching lines' current routes, which the app fetches for everyone to label
branches (the Northern, Central and Piccadilly lines), are kept there too, where they differ from the
routes built into the app, until a later fetch replaces them; they say nothing about you.

## Crash reports and usage stats (opt-in)

If — and only while — you turn on **Help make StopDash better** in Settings, stopdash sends crash
reports to **Firebase Crashlytics** and usage statistics to **Google Analytics for Firebase**
(Google). It is **off by default**: nothing is collected until you turn it on, and a crash from
before you did is discarded rather than sent (if one is waiting, reporting starts the next time you
open the app). Until you've answered, the app asks once, with a card at the top of the stops near
you: **Yes please** turns it on, **No thanks** keeps it off, and either way the card doesn't come
back. You can change your answer in Settings at any time.

What it sends:

- **Crash reports** — the error type and where in the code it happened, your device model, Android
  version and the app version, plus the last few lines of the diagnostic log (below). Log lines
  go through the log's off-device filter first, so a stop ID, line ID or coordinate in them is
  replaced by `•••`, and an error's message text is dropped (its type and code location stay).
  Errors the app catches and survives are reported the same way, and so is a crash that closes
  the app: its message text is dropped before Crashlytics sees it. The first lines of each start
  are logged before the app has read your choice back, so they're held in memory until it has:
  sent if you're opted in, and discarded if you're not.
- **Usage statistics** — that the app was opened, for how long and on which screen, your device
  model, Android version and app version, and the **country, region and city** Google infers from
  your IP address, under a **random app-instance ID** Firebase generates on the device and replaces
  whenever you turn this off. Crash reports carry Crashlytics' own random installation ID, which
  isn't replaced. Neither is your name, your account or your advertising ID.
- **Which features you use**, counted by kind and in ranges only: which screen you opened (the
  stops near you, a trip, Settings and so on) and what opened the app (its icon, the widget, a
  notification or a shortcut); the kind of thing you tapped (a journey card, a stop row, a change
  card, swapping a journey, starring or unstarring, opening search or Settings, refreshing the
  widget), opening a farther place (as "bus", "tube" and so on) or your faraway favorites, the
  location permission you chose (precise, approximate or none), how getting your location went (a
  fresh fix, the last known one, or none, with its accuracy and how long it took in ranges like
  "10–25 m" and "1–3 s"), and how many stops of each kind were near you, in ranges (0, 1, 2–3, 4 or
  more); for a trip, how planning it went (how many routes, in ranges, or why it failed), whether it
  started from where you are or a station and went to a station or a place, and the kind of route
  you opened or started ("Fastest", "Simplest", "Least walking" or another, and how many changes);
  and each setting you change, with its new value ("Slow", "Step-free to the train", "Buses off"
  and so on). Never which stop, line, journey or place, nor what you searched for: avoiding,
  hiding or adding a line counts only that you did. What's counted as the app starts, before it
  has read your choice back, waits in memory until it has: sent if you're opted in, discarded if
  you're not.
- **Your settings and setup**, sent again as they change: your walking speed, longest walk,
  step-free choice, the kinds of transport you've turned off or hidden, distance units, text size
  (in ranges) and the other switches in Settings; whether you've added your own TfL or National
  Rail key (never the key); how many widgets you've placed, whether a paired watch has StopDash (or a connected one doesn't),
  and how many rows, places and journeys you've starred, in ranges; and whether notifications and
  location are allowed. These let the usage above be read against them ("how many people who
  walk slowly plan trips?"), and are reset with the app-instance ID when you turn this off.

What it never sends: your location (coordinates), the stops or stations near you, your starred
rows or journeys, what you searched for, or your TfL API key. stopdash strips the advertising-ID
permission, so the advertising ID isn't collected either.

Turning it **off** stops collection at once, discards any crash report not yet sent, and resets the
Analytics app-instance ID. The choice is kept on this device only: restored onto a new phone from a
backup, it starts off again until you turn it back on. Development (debug) builds never send
anything. Firebase is free at stopdash's scale; uploads are batched by the SDKs, with no extra
wakeups or location requests. For the Play Data Safety form this adds **Crash logs**,
**Diagnostics**, **App interactions**, **Device or other IDs** and **Approximate location** (the
region Analytics infers from your IP address), all optional (user-controlled).

## The on-device diagnostic log

StopDash keeps a diagnostic log on the device so a misbehaving routing or departure
decision can be explained — for example, why "couldn't get your location" appeared, or
why a line showed "couldn't check for disruptions". Diagnosing those needs a record of
what the app saw, so the log carries **coarse state and reasons**, and nothing more:

- a **stop ID** or a **line id** (TfL identifiers, e.g. `victoria`, `940GZZLUOXC`),
- an **HTTP status or failure reason** for a TfL request (e.g. `429`, `offline`),
- **location fix outcomes**: that a fix could not be obtained, whether a recent cached
  fix was used instead of a fresh one, and coarse timing; for each fix the app uses, **which
  location provider** supplied it (e.g. `network`, `gps`), the **accuracy radius** that provider
  reported (or "unknown"), and **how old** it was; when a remembered precise fix is weighed
  against a rough network one, **how far apart** the two are; when the list moves because you
  walked off, **how far** from where it was found — **never a coordinate**,
- for each nearby-stops lookup, **how many stops it found** (a count only: several stops'
  distances would pin down where you were),
- on a trip on the way, **what a location fix saw you at** when it moved the trip on — the stop's
  placed point or one of the station's entrances — never which stop, how far, or where,
- on a ride followed by where you're seen (no train matched), **how far along it each fix placed
  you**, as a stop count ("stop 5 of 9", or not placed) with the fix's accuracy radius; and when a
  "get off soon" alert is shown, the stops left and whether they were counted from the train
  followed or from where you were seen — never which stop, or where,
- for each **widget render**, how many stored stops it kept against the nearby set (and that
  set's size), how many rows it drew and how old its data was; and for each **scheduled widget
  redraw**, how many seconds after it was due Android ran it — counts and seconds only, no stop or
  place,
- **per-refresh request counts and timing**: how many TfL requests a refresh made, of which
  kinds, how long it took, and how long it waited on the app's own rate limit — counts and
  milliseconds only, no stop or place,
- **trip timing**: how long each journey planner request took and how many routes it returned,
  and how long a trip's routes took to show, with what the page last waited for, by kind (a
  refresh, the line checks, line routes and how many) — counts and milliseconds only, never
  either end of the trip,
- **a slow or retried network request**: who it went to (TfL or National Rail), the
  endpoint's kind with every identifier and anything typed left out (e.g. `StopPoint/…/Disruption`), how long it
  took to get a connection, to start answering and to finish, whether the connection was new,
  each failed connect attempt's address family (IPv4 or IPv6) and failure class, and how long a
  request waited for a free slot — milliseconds and names only, never a stop, address or key,
- **which disruption/status lookup was unknown and why** (e.g. a line TfL returned no
  status for, or a prediction with no line id to check),
- **a failed timetable lookup**: the line id and stop ID whose timetable couldn't be fetched
  (asked only for a line with no live times, to tell "none coming" from "none known") and the
  failure reason,
- **which departure couldn't be checked against its line's route and why**, where a trip, a
  *To…* page or a journey card says some routes couldn't be checked — its line id, the stop ID
  it boards at, the reason (e.g. its destination matches no route) and the destination TfL shows
  for that train or bus (e.g. "Sutton (London)": where the vehicle ends, never where you get off) —
  and, by line id only,
  a starred journey its line's route can't place (never its two ends together, which would
  record a route you travel),
- **what a trip's *Direct* section shows, when that changes**: each row by its line id and the
  stop ID it boards at, and why it says it couldn't check every line (by line id, or the stop IDs
  whose arrivals didn't come) — never the place or any stop near it,
- **what a trip plan's journey planner answer offered**: how many routes, and each by its lines
  alone (e.g. `102+northern, northern`: line ids, "walk" for a route on foot) — never a stop, a
  place or either end of the trip,
- **why a trip route's arrival is withheld** ("arrival unknown"): which of its legs (by number),
  that leg's mode and line id, the reason (e.g. its line's predictions don't show it running
  often), how many predictions it had, and minute counts (how long before you'd reach the stop
  the last one leaves, the longest gap between them, how long ago the Planner's own departure
  left) — never a stop or either end of the trip,
- a **failed Play update check, or a failed attempt to open the Play listing** (release
  builds only — see *What leaves the device*): the caught exception's class name (e.g.
  `IllegalStateException`), or a fixed "no app to open the Play listing" reason — never any
  Play account, device, or version detail,
- a **favorite-place storage failure**: that reading, saving, or deleting a saved place, or
  searching for one, failed — by the caught exception's class name (e.g. `IOException`) —
  never the place's label, its coordinate, or the typed search text.
- **why the app's previous processes ended**, read once at startup from Android's own record:
  for the last five, the reason (e.g. `crash`, `anr`, `lowMemory`), how important Android
  counted the app at the time, the exit status and when it happened, plus when the app was
  installed and last updated — **never** Android's free-text description of an exit, which can
  name another app. After the most recent time the app stopped responding (an "ANR"), where
  it was stuck: a few lines of the frozen screen's code, each a place in the app's or a
  library's code (a class, method, file and line number), read from Android's own record of
  that freeze. Never a value the code was working on.
- **where the link to a paired watch stands**, when it changes: whether any paired watch has
  StopDash, how many do (a count only), whether the latest departures were queued for it or had
  already been, that a watch asked for a refresh, that a trip on the way was queued for it or
  taken off it, and the failure type if a send failed —
  never which watch, its name or id, or what was sent.
- **the app's own code reading or writing the disk on the screen's thread**, which can make
  the screen stutter: the kind (e.g. `DiskReadViolation`) and the place in the app's code that
  did it, once each per run, at most 20 — never what was read or written.

The log **never** carries:

- a **raw coordinate** or a full address,
- the TfL **`app_key`** or your National Rail key,
- any contact, name, or other personal identifier.

These diagnostics are written to Android's **Logcat** (visible to a developer with the
device connected) **and to a persisted log file on the device** — a small rotating file in
the app's private cache (excluded from backup), kept so that a crash or a silent process
kill still leaves a record of the last thing the app saw. The persisted log **stays on the
device**. The one off-device destination is Crashlytics, and only while you've opted in (above):
it receives the log's **off-device** rendering, with every stop ID, line ID and coordinate
replaced by `•••` before it leaves the app. The logging runs through one shared on-device buffer
(`mikelward/androidlog`), wired incrementally: a feature whose warning seam isn't connected
yet is discarded rather than recorded.

Two ways to get the log **off** the device for a bug report are foreseen, and they draw the
privacy line differently. A **location-safe export** — the log shared with its **travel data
(stop IDs and line ids) redacted**, since a shared file is otherwise subject to the same rule
as any other artifact that leaves the machine (`AGENTS.md` *Privacy*) — is still planned
(`TODO.md`). The other is the **consent-gated bug report** described next, which does the
opposite on purpose: it keeps the location *in*, openly and under consent, because that is the
context a routing bug is diagnosed from.

## Sending a bug report

StopDash can send a **bug report** from its overflow menu. This is the one channel that
deliberately carries what the on-device log never does — so it is gated by an explicit consent
screen that names exactly what leaves, and nothing is assembled or sent until you pass it. The
report carries:

- the **diagnostic log** described above (not redacted — the report already reveals more than
  the log's stop/line ids would; a busy run's oldest lines are left out so the report stays small
  enough to share),
- a **screenshot of the screen you sent the report from** — the departures or error screen you
  are reporting, so the report shows what you saw; it is the app's own window, so the consent
  dialog itself is not in it,
- your **exact location from the last nearby lookup** — the fix that found the stops you were
  looking at, which is where a routing bug happened; it is labeled that way in the report, since
  the departures screen can stay open while you move, so it is not necessarily where you are the
  instant you send,
- **where the app placed you in the last 15 minutes** (at most the last 20 positions): each
  location fix it got (including a rough one it set aside for a more precise remembered one) and
  each nearby lookup (with the stops it found and how far each was), with
  the time — so a fix that put you at the wrong station can be seen. These are kept **in memory only**, never in the diagnostic log or its
  file, are deleted when they turn 15 minutes old (if the phone is asleep then, within a minute of
it waking — the app doesn't wake the phone just to delete them), and are gone when the app's
process ends,
- **how far you are from each nearby stop**.

It is **user-initiated and £0**: stopdash runs no service of its own for it. Tapping *Send bug
report* opens the consent screen; on *Continue* the report is **copied to your clipboard**
(on-device, but readable by other apps from then) **and** handed to Android's share sheet, where
**you** choose the app it goes to — an email, an issue, a chat. So the clipboard copy happens as
soon as you tap Continue; nothing is sent to a destination *you* pick until you pick it, but the
report has left the composing screen at that point. A **"don't ask again"** option skips the
consent screen on later reports; it never sends anything on its own, and it lapses whenever the
report starts carrying more, so you see the new list before it is sent.

This is an honest trade, not a location-safe one: a report useful for a *where did routing go
wrong* bug has to say where you were, so this one says so plainly rather than stripping the
context to look safe. For **Play Data Safety** it is a user-initiated share of app diagnostics,
a screenshot, and a coarse-or-precise location to an app you choose — disclosed here as such.
