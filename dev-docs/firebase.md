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
- **Only while the user has opted in** — *Help make LDN Go better* in Settings, off by default.
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

## Privacy line

Crash-report breadcrumbs are the log's `Destination.OFF_DEVICE` rendering: androidlog replaces any
argument not wrapped in `safe(...)` with `•••` and strips exception messages. Never wrap a stop ID,
line id, coordinate, search text or the API key in `safe(...)`; `CrashlyticsLogSinkTest` pins the
redaction. A fatal crash goes through the same redaction: `RedactingCrashHandler` sits directly in
front of Crashlytics' own uncaught-exception handler (installed first in `StopdashApp.onCreate`, so
the on-device file sink still records the original), and telemetry stays off if it couldn't be
installed. `RedactingCrashHandlerTest` pins it. Custom analytics events carry categories and bucketed counts only: every value comes from
`UsageEvent`'s closed vocabulary (`UsageEventTest` pins it), sent through `UsageEvents`, which
drops an event unless the stored choice reads yes. Never pass a stop, line, journey, search text or
coordinate into one; add a new category to the vocabulary instead.

Cost: £0 (Firebase's free tier covers Crashlytics and Analytics). Battery: SDK-batched uploads, no
extra wakeups or location requests.
