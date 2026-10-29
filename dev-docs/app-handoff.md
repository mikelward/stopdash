# Handing a trip to another app

A developer-facing survey of what StopDash can pass to other journey apps (Google Maps,
Citymapper, Transit, Moovit, TfL Go) when the rider wants to carry on there, and what it can't.
Researched 2026-10-29; recheck a link format before building on it.

## Short answer

No app takes a trip with its stations or lines already chosen. The most any link carries is a
start, a destination, a mode and sometimes a time. The only planner that takes "via this
station" and "only these modes" is TfL's own Journey Planner, which StopDash already calls.

## What each app's link carries

| App | Link | Carries | Stations or lines? |
|---|---|---|---|
| Google Maps | `https://www.google.com/maps/dir/?api=1&origin=…&destination=…&travelmode=transit` | Start, destination, mode | No. Stops along the way (`waypoints`) don't apply to transit, and there's no supported departure time. Android's `google.navigation:` covers only driving, walking and cycling. |
| Citymapper | `https://citymapper.com/directions?startcoord=…&startname=…&endcoord=…&endname=…&arrival_time=…` (or `citymapper://directions?…`) | Start and destination (position plus a name), arrive-by time | No. An older blog post names the time `arriveby`; check which one works. Without a start it plans from where the rider is. |
| Transit | `transit://directions?from=lat,lon&to=lat,lon`, `transit://routes?q=<line>` | Start and destination, or one line's page | Opening a line is the nearest thing, but it plans nothing. Seen only on a third-party listing; check transitapp.com/apis. |
| Moovit | `moovit://directions?…` (parameters in Moovit's deep-linking docs) | Start and destination | No. Needs a partner ID. |
| TfL Go | None published (a developer's `tflgo://` question on TfL's forum has no answer) | — | — |
| TfL Journey Planner (web and API) | `/Journey/JourneyResults/{from}/to/{to}?via=…&mode=…&journeyPreference=…` | Start, destination, one station to go via, modes, quickest or fewest changes, time | Partly: a via station and a mode filter, not one particular line. |
| Any maps app | `geo:0,0?q=51.5,-0.12(Name)` (StopDash's map pin today) | A labeled point | The point only. |

## What would help riders

The rider has usually chosen a stop and a line in StopDash already, so a handoff fixes the stop
and leaves the rest to the other app:

1. **Walking directions to the chosen stop**: the stop's position to Google Maps with
   `travelmode=walking`, or a `geo:` link for any maps app. The station is fixed exactly. Filed
   in `TODO.md` under the stop's *Show it on a map*.
2. **"Continue in…" from the boarding stop**: that stop as the start, the rider's destination as
   the end. The station is fixed, but the other app picks the line and may not suggest the one
   StopDash showed.
3. **Citymapper's arrive-by time**, when StopDash knows when the rider wants to arrive; no other
   app takes one.
4. **Line preferences stay in StopDash**: no link carries them, so favoring a line belongs in
   StopDash's own planning, through TfL's via station and mode filter.

## Cost, battery and privacy

Nothing to run: a handoff is an intent on the phone, with no new request, wakeup or location
fix from StopDash. The rider starts it, but the receiving app then sends the start and
destination to its own servers under its own policy, so the button names the app ("Open in
Citymapper") and `docs/PRIVACY.md` gets a line before one ships. An app that isn't installed
falls back to its web link, or the button is hidden.

## Sources

- [Citymapper: Launch Citymapper for Directions](https://citymapper.com/tools/1053/launch-citymapper-for-directions)
- [Citymapper blog: launching directions from apps and the web](https://blog.citymapper.com/post/59578777734/launching-citymapper-directions-from-apps-and-the)
- [Transit listing on apis.io](https://apis.io/providers/transit/)
- [Moovit deep linking](https://moovit.com/developers/deeplinking/)
- [TfL forum: URL scheme for TfL Go](https://techforum.tfl.gov.uk/t/url-scheme-for-tfl-go-app/2953)
- [Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started)
