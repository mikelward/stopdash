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
6. Register the custom definitions, or the parameters and user properties reach only DebugView and
   BigQuery, never the reports. `analytics-definitions.tsv` (beside this file) lists one per event
   parameter and user property, and `AnalyticsDefinitionsTest` fails when it and the app drift apart.
   `scripts/register_analytics_definitions.py` creates the ones the property lacks, through the Analytics
   Admin API; user-scoped ones are kept out of ads personalization, those already there (made by hand,
   say) updated to be. The API costs nothing (£0, a few dozen calls a run against its free quota), and
   the script runs on the maintainer's machine, sending Analytics only these names and display names:
   nothing about the app's users, so the Play Data Safety form is unchanged. Once, in the Firebase
   project's Google Cloud console, enable the **Google Analytics Admin API**; your account needs the
   Editor role on the Analytics property. Then:

   ```sh
   gcloud auth application-default login \
       --scopes=https://www.googleapis.com/auth/analytics.edit,https://www.googleapis.com/auth/cloud-platform
   python3 scripts/register_analytics_definitions.py PROPERTY_ID --quota-project FIREBASE_PROJECT_ID
   ```

   `PROPERTY_ID` is the number in Analytics' Admin → Property settings. That's a dry run listing what
   it would do; add `--apply` to do it, and `--archive-stale` to archive dimensions no longer listed (a
   renamed parameter's old name). Analytics can take up to 48 hours to free an archived slot, so a run
   that would pass the property's limits creates nothing; run it again later. It's safe to run again
   after any change to the list. Should Google refuse gcloud's own sign-in for that scope, create a
   Desktop OAuth client in the project (APIs & Services → Credentials) and add `--client-id-file=` its
   downloaded JSON to the login; or pass any token with the scope as `GA_ACCESS_TOKEN`. By hand
   instead: Admin → Data display → Custom definitions → Create custom dimension, one per line of the
   list.

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
| `setting_change` | `setting`, `setting_value` | A setting changed, in Settings or atop a trip |

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
