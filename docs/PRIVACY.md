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
about departures: the details of what you're looking up (your location for "near me now" —
**precise** if you grant precise and a precise fix is available, otherwise approximate (if you grant
only approximate, or if no precise fix can be obtained) — or the stop or line you're after) and, if
you've set an optional TfL API key (`app_key`), that key as your own credential, sent with your own
TfL calls and nowhere else. Location is used **only on demand**, never in the background.

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
data (your settings and its last-good departures snapshot) rides it, so a phone swap keeps your
setup — all but the crash-report opt-in, which stays with the install, and the rows your watch's complications show, which the phone relearns from the watch. That is Android's channel, tied to your Google account — not something stopdash sends.
The third is a **bug report you choose to send** (see *Sending a bug report* below): it hands
the app you pick a diagnostic report that, unlike everything else here, **includes your exact
location and a screenshot of the screen you sent it from** — but only after a consent screen
that says so, and then to your clipboard and the app you pick (the clipboard copy happens as
soon as you confirm — detailed below).
So the guarantee is precise rather than absolute: **without your opt-in, the only user data
stopdash itself sends off the device goes in its TfL requests, and its National Rail requests if
you've added a key** (the Play update check carries none), **plus, if a paired watch has the
StopDash watch app, the widget's stops and departures to that watch** (above); with it, the crash reports and usage
stats above go to Firebase too. Android's backup
carries your saved data under your control, and a bug report carries what you consent to share.

**Your Wear OS watch (optional; not released yet).** If a watch paired with your phone has the
StopDash watch app installed, the phone sends it what your home-screen widget shows, so the watch
can show it too: the widget's stops (their names and IDs, and the nearby stops each is compared
against, which are worked out from your phone's last location), their departures, which rows
you've starred, and which kinds of transport you've hidden. It never sends your coordinates or your API keys, and the watch never contacts TfL,
National Rail or anything else itself. In the other direction, the watch sends the phone its
requests to refresh, which carry only a random request number, and the row each StopDash complication shows (its stop ID, line and direction), so the phone keeps those rows in what it sends. This goes through **Google Play
services' Wearable Data Layer**: over Bluetooth when the watch is near, but when it isn't (a watch
on Wi-Fi or mobile data) it may pass **through Google's servers**. Nothing is sent when no paired
watch has the app. The watch keeps only the latest copy, never backs it up, and doesn't log it.
For Play's Data Safety form, the determination is that this moves your own data between your own
devices and stopdash never receives it, so it adds no data type *collected* by the developer; the
relay's handling by Google (its encryption and retention) is to be confirmed against Google's
documentation, and the form re-checked, before the watch app is released.

**Find a station** (the menu's *From…* and *To…*, and a station's *To…*) sends the name you type to TfL's
stop search, once you pause typing, and then the chosen station's id to look up its stops and
departures, and the station's own position (a public place, not yours) to find the stops around it (for *To…*, the stops and the routes of the lines leaving where you start from; a *To…* from the near-me list starts from stops already found near you, so opening it sends no location, but refreshing it or coming back to the app finds your location again, exactly as the near-me list does). The name isn't saved,
logged or sent anywhere else. The last eight stations you open from *From…*, and separately the last eight
destinations you pick in *To…*, are remembered on the device to list under *Recent*, in app storage
that Android never backs up or transfers; they are
never logged or sent anywhere, and clearing the app's data removes them. So that a starred stop
can be listed by name there, the place each starred row belongs to (its stop area or station, as
TfL names it) is kept the same way, and forgotten once you unstar it. The search also lists
and matches your starred stops and the stops the app has lately shown you, all read on the
device; none of that is sent anywhere either.

**Trips with a change** (*To…* from the near-me list or a *From…* station) send both ends of the
trip together to **TfL's Journey Planner**, as stop ids: the stop nearest you (or the *From…*
station) and the stop you picked. When you pick a station complex such as King's Cross St.
Pancras, the Planner is asked once for each of its stations and once for its bus stops, each
request carrying the same start. Your coordinates are never sent to the Planner; the nearest
stop's id stands in for where you are, which says no more than the nearby-stops lookup already
does. The Planner is asked when a trip opens, about every 15 minutes while it stays on screen, and
when you tap *Try again*; while a trip is on screen, the departures at each stop where a route
boards are fetched from TfL like any other stop's, along with its lines' status. The plan is held
in memory only, never saved or logged beyond coarse diagnostics (a stop id, an HTTP status), and
nothing runs once you leave the trip.

**Starred journeys** (two stops you travel between) are kept on the device with your other
settings and stars, so they ride your own Android backup like the rest (above); they are never
logged or sent anywhere. For the widget, the departures at a journey's nearer stop, and which of
them reach the other end, are saved with the widget's other departures on the device. Showing a journey's trains fetches the departures at its nearer stop
from TfL, like any other stop.

**Held in memory only:** the last precise (GPS) position, for up to 10 minutes, so a rough
network position that comes in while you haven't moved doesn't replace it. It is never written to
storage or logged, is sent only as the position of a nearby-stop lookup (and, when a lookup used
it, in a bug report you choose to send, as described below). Past 10 minutes it is no longer used,
and it is deleted the next time the app takes a location or when the app's process ends, whichever
comes first.

**Kept on the device, never backed up:** to skip a repeat stop lookup when you reopen the app
near where you last used it, stopdash keeps the **positions of its last few nearby-stop lookups**
(up to four places) and the stops found around each, for up to a day, in the app's cache
directory. Android never includes that directory in a backup or device transfer, it is never
logged or sent anywhere, and clearing the app's cache removes it; an entry older than a day is
deleted the next time the app looks up nearby stops.

The **routes and stop areas the app fetched** (a line's stops, and the stops grouped with a
journey's starting stop) are kept the same way, for up to a day, so reopening a route doesn't ask
TfL again. They are TfL's public network data, but which ones are there says which routes you
looked at, so they stay in the app's cache directory too: never backed up, logged, or sent
anywhere, and an entry older than a day is deleted the next time the app starts or looks up a
route or stop area.

## Crash reports and usage stats (opt-in)

If — and only while — you turn on **Help make StopDash better** in Settings, stopdash sends crash
reports to **Firebase Crashlytics** and usage statistics to **Google Analytics for Firebase**
(Google). It is **off by default**: nothing is collected until you turn it on, and a crash from
before you did is discarded rather than sent (if one is waiting, reporting starts the next time you
open the app).

What it sends:

- **Crash reports** — the error type and where in the code it happened, your device model, Android
  version and the app version, plus the last few lines of the diagnostic log (below). Log lines
  go through the log's off-device filter first, so a stop ID, line ID or coordinate in them is
  replaced by `•••`, and an error's message text is dropped (its type and code location stay).
  Errors the app catches and survives are reported the same way, and so is a crash that closes
  the app: its message text is dropped before Crashlytics sees it.
- **Usage statistics** — that the app was opened, for how long and on which screen, your device
  model, Android version and app version, and the **country, region and city** Google infers from
  your IP address, under a **random app-instance ID** Firebase generates on the device and replaces
  whenever you turn this off. Crash reports carry Crashlytics' own random installation ID, which
  isn't replaced. Neither is your name, your account or your advertising ID.

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
  against a rough network one, **how far apart** the two are — **never a coordinate**,
- for each nearby-stops lookup, **how many stops it found** (a count only: several stops'
  distances would pin down where you were),
- **per-refresh request counts and timing**: how many TfL requests a refresh made, of which
  kinds, how long it took, and how long it waited on the app's own rate limit — counts and
  milliseconds only, no stop or place,
- **which disruption/status lookup was unknown and why** (e.g. a line TfL returned no
  status for, or a prediction with no line id to check),
- **which departure couldn't be checked against its line's route and why**, where a trip, a
  *To…* page or a journey card says some routes couldn't be checked — its line id, the stop ID
  it boards at and the reason (e.g. its destination matches no route) — and, by line id only,
  a starred journey its line's route can't place (never its two ends together, which would
  record a route you travel),
- a **failed Play update check, or a failed attempt to open the Play listing** (release
  builds only — see *What leaves the device*): the caught exception's class name (e.g.
  `IllegalStateException`), or a fixed "no app to open the Play listing" reason — never any
  Play account, device, or version detail.

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

- the **diagnostic log** described above (in full, not redacted — the report already reveals
  more than the log's stop/line ids would),
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
