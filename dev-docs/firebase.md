# Firebase (Crashlytics + Analytics)

Crash reports and usage stats, opt-in (SPEC *Privacy*, `docs/PRIVACY.md`). The code ships in every
build; whether a build can collect depends on its config, and whether it does depends on the user.

## When Firebase is live

- **Needs `app/google-services.json`** (untracked, gitignored). Without it the google-services and
  Crashlytics Gradle plugins aren't applied, Firebase never initializes, and
  `FirebaseTelemetryBackend.orNull` returns null — no sink, no gate, nothing collected.
- **Never in a debug build**, config or not: `app/build.gradle.kts` disables the debug variant's
  google-services and mapping tasks and purges any previously generated resources, so the unit and
  screenshot suites (which run against debug) can't send anything.
- **Only while the user has opted in** — *Help make StopDash better* in Settings, off by default.
  The manifest starts both SDKs off; `TelemetryConsentHolder` stores each change and applies it
  (`TelemetryGate`) before the tap returns, and on each start trusts the stored choice only while
  the SDKs agree with it — any mismatch loads as off.

## Setup (maintainer, once)

Do this **after the package rename**: Firebase matches a config to the build by application ID, so
register the final one.

1. Create a Firebase project; add an Android app with the release application ID. Enable
   Crashlytics and Analytics (disable Google Signals and ads personalization).
2. Download `google-services.json`.
3. CI: add it verbatim as the `GOOGLE_SERVICES_JSON` secret in the `production` environment. The
   `release-build` job writes it to `app/google-services.json` before `bundleRelease`, which also
   uploads the R8 mapping so crash stacks deobfuscate. Without the secret the release build still
   succeeds, with Firebase dormant.
4. Local release builds: optionally copy it to `app/google-services.json` (never commit it).
5. Update the Play **Data Safety** form from `docs/PRIVACY.md`: Crash logs, Diagnostics, App
   interactions, Device or other IDs, and Approximate location (Analytics' IP-derived region) —
   collected, optional, not shared, encrypted in transit.
6. Register the custom definitions in Google Analytics (Admin → Data display → Custom definitions →
   Create custom dimension; the Firebase console's Analytics links there), or the parameters and user
   properties below reach only DebugView and BigQuery, never the reports. **Event-scoped** custom
   dimensions, one per parameter name: `kind`, `what`, `mode`, `grant`, `outcome`, `accuracy`,
   `time_to_fix`, `tube`, `overground`, `rail`, `bus`, `tram`, `boat`, `from`, `routes`, `to`, `plan`,
   `choice`, `rank`, `changes`, `setting`, `value` (`screen_name` is built in). **User-scoped**, one
   per user property: `walking_speed`, `max_walk`, `step_free`, `trip_modes_off`, `avoided_lines`,
   `hidden_modes`, `hidden_lines`, `distance_units`, `disruptions_row`, `live_widget`, `text_size`,
   `pinch_resize`, `own_tfl_key`, `rail_key`, `widgets`, `watch`, `starred_rows`, `favorite_places`,
   `favorite_journeys`, `notifications`, `location`. The free tier allows 50 and 25.

## What's counted

`UsageEvent` (`:domain`) is the whole vocabulary; `UsageEventTest` pins every value to a closed list.

| Event | Parameters | When |
|---|---|---|
| `screen_view` | `screen_name`: `home`, `route`, `station`, `search`, `trip_to`, `trip`, `on_the_way`, `settings`, `favorite_places`, `favorite_journeys`, `licenses`, `lines`, `line`, `line_stop` | A screen comes up, or the app returns to it (`ReportScreen`) |
| `open` | `from`: `launcher`, `widget`, `notification`, `shortcut`, `other` | A cold start from the icon, or a tap that brings the app up. A return to the app already running (Recents, or the icon) names no source and isn't counted here: its `screen_view` and Analytics' `session_start` count it |
| `tap` | `kind`: `journey_card`, `stop_row`, `change_card`, `swap`, `star`, `unstar`, `search`, `settings`, `widget_refresh` | Those taps |
| `reveal` | `what`, `mode` | A farther place or the faraway favorites opened |
| `location_permission` | `grant` | The answer to the location prompt |
| `location_fix` | `outcome`, `accuracy`, `time_to_fix` | A near-me fix |
| `nearby_stops` | one count bucket per mode group | A near-me lookup |
| `trip_plan` | `outcome`, `routes`, `from` (`here`/`stop`), `to` (`stop`/`place`), `plan` (`first`/`again`) | Each plan the Journey Planner answers |
| `route_open` | `choice` (the card's label), `rank` | A trip's card tapped |
| `trip_start` | `choice`, `changes` | Start (or Replace) on an open route |
| `setting_change` | `setting`, `value` | A setting changed, in Settings or atop a trip |

User properties (`usageProperties`, pinned by `UsagePropertiesTest`) are sent by
`UsagePropertiesPublisher`: as the opt-in lands, after each `setting_change`, and on each
`MainActivity.onResume`, read off the main thread by `UsageStateReader`. `watch` is `app` (a paired
watch has StopDash), `connected_no_app` (one connected now lacks it: the Data Layer lists a paired
watch without the app only while it's connected) or `none`.

## Privacy line

Crash-report breadcrumbs are the log's `Destination.OFF_DEVICE` rendering: androidlog replaces any
argument not wrapped in `safe(...)` with `•••` and strips exception messages. Never wrap a stop ID,
line id, coordinate, search text or the API key in `safe(...)`; `CrashlyticsLogSinkTest` pins the
redaction. A fatal crash goes through the same redaction: `RedactingCrashHandler` sits directly in
front of Crashlytics' own uncaught-exception handler (installed first in `StopdashApp.onCreate`, so
the on-device file sink still records the original), and telemetry stays off if it couldn't be
installed. `RedactingCrashHandlerTest` pins it. Custom analytics events carry categories and bucketed counts only: every value comes from
`UsageEvent`'s closed vocabulary (`UsageEventTest` pins it), sent through `UsageEvents`, which
drops an event once the stored choice reads no (one raised before it has loaded is held, at most 16,
and settled by `UsageEvents.settle`). Never pass a stop, line, journey, search text or coordinate
into one; add a new category to the vocabulary instead. User properties follow the same rule: counts
in buckets, never what was counted, and a key only as yes/no.

Cost: £0 (Firebase's free tier covers Crashlytics and Analytics). Battery: SDK-batched uploads, no
extra wakeups or location requests.
