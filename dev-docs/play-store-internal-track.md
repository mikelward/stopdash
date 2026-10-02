# Play Store internal testing track

CI uploads a signed release AAB to the Google Play **internal** testing track on
every push to `main` that contains a release-worthy commit. The upload is wired
into the `deploy` job in `.github/workflows/ci.yml` using
[`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play).
It is the only channel a *device* installs a build from, and the route to
alpha/beta/production later.

The same signed bundle is also published as a **GitHub prerelease**, tagged
`v<versionCode>` with `stopdash-<versionCode>.aab` attached and the same "What's
new" notes. It is not a second way to install anything — nobody installs an
AAB — it is the durable record of what shipped: the workflow artifact expires
and is reachable only from its own run's page, and the Actions list titles a run
by its last commit, so a push tipped with a housekeeping commit reads as
housekeeping even when it published. The release is permanent, linkable, and
lists every build in one place, which is also what makes the seed upload below
easy to repeat.

## What gets uploaded

`./gradlew :app:bundleRelease` produces
`app/build/outputs/bundle/release/app-release.aab` and the action uploads it to
the `internal` track on the `app.stopdash` listing. Play App Signing re-signs the
AAB with its managed app-signing key before delivery, so the upload key
generated below only authenticates to Play — it doesn't sign what testers run.

## When the upload runs

The upload steps only run when **all** of the following are true:

- The `deploy` job is running (push or manual `workflow_dispatch` on `main`,
  after the build and screenshot jobs pass).
- `RELEASE_KEYSTORE_BASE64` is non-empty (so the upload key materializes).
- `PLAY_SERVICE_ACCOUNT_JSON` is non-empty (so CI can authenticate to Play).
- The range since the last published run contains at least one release-worthy
  commit (see "Release notes" below); housekeeping-only pushes skip the release.
  A manual `workflow_dispatch` overrides the skip.

The GitHub prerelease runs on the same conditions **minus** the service
account — so a repository holding only the upload keystore still gets a
discoverable build, and every Play upload is preceded by its prerelease rather
than the reverse. Both stand down when a later push has already published a
higher `versionCode`: the newest release stays the newest build, and Play cannot
receive the older bundle either.

Forks and fresh clones without these secrets still get a green run — the AAB
build and upload steps are skipped. The release `signingConfig` in
`app/build.gradle.kts` is also only attached when `RELEASE_KEYSTORE_FILE` is
set, so a local `./gradlew :app:bundleRelease` without the env vars produces an
unsigned AAB rather than a build failure. It is still fully minified: R8 runs on
every release build, not only in CI.

## One-time Play Console setup

### 1. Create the app on Play Console

https://play.google.com/console → "Create app":

- **App name**: `Routemo - London bus + tube`
- **Default language**: English (United States)
- **App or game**: App; **Free or paid**: Free
- **Package name**: `app.stopdash` (must match `applicationId` in
  `app/build.gradle.kts`)

Complete the required declarations under "App content" using the facts recorded
in "App content and Data safety" below.

### 2. Seed the internal track with a manual upload

The first AAB for any new app must be uploaded through the Play Console UI; the
API can only create subsequent releases.

**Option A — let CI build it (recommended).** Add the four release-keystore
secrets from the table below (everything except `PLAY_SERVICE_ACCOUNT_JSON`) and
push to main (or dispatch the workflow). The `Build release AAB` job is
decoupled from the Play upload and publishes the signed AAB two ways: as a
workflow artifact called `app-release-aab`, and attached to a GitHub prerelease
tagged `v<versionCode>`. Take it from the Releases page — that copy does not
expire and says which build it is in its own title — and upload it through Play
Console.

**Option B — build locally:**

```sh
RELEASE_KEYSTORE_FILE=/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD=<password> \
RELEASE_KEY_PASSWORD=<password> \
RELEASE_KEY_ALIAS=stopdash \
./gradlew :app:bundleRelease
```

Scope it to `:app`: a root `bundleRelease` also runs `:wear`'s, which fails at the watch
app's release gate (TODO Phase 6).

No `CI=true` needed. Release minification is unconditional
(`isMinifyEnabled = true` in `app/build.gradle.kts`), so a local `bundleRelease`
already matches the fully-optimized artifact the CI pipeline ships thereafter —
R8 shrinks, optimizes and obfuscates on any machine (see
`app/proguard-rules.pro`).

Either way, upload `app-release.aab` via Play Console → Internal testing →
Create new release, and accept Play App Signing when prompted. Then add
`PLAY_SERVICE_ACCOUNT_JSON` so the next push to main uploads automatically.

### 3. Add internal testers

Play Console → Internal testing → Testers tab → "Create email list". Send the
opt-in URL to testers; they follow it once before the app appears in their Play
Store.

### 4. Enable the Google Play Android Developer API

Enable
https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com
on a Cloud project of your choice.

### 5. Create the service account

In the **same** Cloud project: IAM → Service accounts → Create (no Cloud-level
roles needed) → Keys tab → Add key → JSON. The downloaded JSON becomes the
`PLAY_SERVICE_ACCOUNT_JSON` secret.

### 6. Grant the service account access in Play Console

Play Console → Users and permissions → Invite new users → the service account
email. On "App permissions", add Routemo and grant **Releases: Release to
testing tracks** — the minimum for an internal-track upload. Propagation can
take a few minutes.

## App content and Data safety

Play Console → **Monitor and improve → Policy and programs → App content**.
Complete the required App content declarations and the Data safety form there.

**These are the engineering facts the form answers are built from — not the
final answers.** `docs/PRIVACY.md` is the source of truth; keep the two
consistent, and re-check the Console's current wording when submitting (Google
revises these forms periodically).

- **Privacy policy**: the published copy of `docs/PRIVACY.md` (confirm the
  hosted URL before submitting — e.g. a GitHub Pages copy at
  `https://mikelward.github.io/stopdash/PRIVACY.html`).
- **What stopdash sends off the device** (`docs/PRIVACY.md`, SPEC *Privacy*):
  - **Approximate or precise location → Transport for London**, on demand only,
    when the user asks for "near me now": the device coordinates are sent to
    TfL's `/StopPoint` lookup to find nearby stops. Precise where the user
    granted precise **and** an accurate fix is available, approximate otherwise.
    Never a background send; used only when the user initiates the nearby action.
  - **Watched-stop / line IDs → TfL**, in the departures and disruption
    requests that *are* the product.
  - **The typed stop-name or line query → TfL**, for stop/line search (Phase 2).
  - **An optional user-supplied TfL `app_key`**, if the user sets one, sent as
    their own credential with their own TfL calls and nowhere else.
  - **Nothing else leaves the device to stopdash** unless the user opts in to *Help
    make Routemo better* (off by default): then crash reports and usage stats go to
    Firebase, with the Data Safety categories listed in `docs/PRIVACY.md` and
    `dev-docs/firebase.md`. Otherwise no Firebase, no analytics, no crash reporter, no
    third-party tracker, no server of stopdash's own. (The
    user's own Android backup / device-to-device transfer carries their saved
    config; that is a platform feature under the user's control, not data
    stopdash collects or transmits — no Data Safety change, `docs/PRIVACY.md`.)
- **On-device diagnostic log**: coarse stop/line IDs, HTTP status, and location
  fix outcomes — **never a raw coordinate or the `app_key`** (`docs/PRIVACY.md`).
  Stays on the device; a future shareable export redacts travel data.

> **TODO (maintainer): complete the Play Data Safety form and the rest of the
> App content questionnaires.** The drafted answers are in
> `docs/play-store/listing.md`, pending sign-off.

## Store listing copy

The Play listing text (title, short and full description) is drafted in
`docs/play-store/listing.md`, **pending maintainer approval**. It isn't part of this deploy
pipeline: paste it into Play Console → Grow → Store presence → Main store listing once it's
signed off.

Release notes do **not** live with the listing copy: `whatsnew-en-US` is
generated per release from commit subjects by the deploy job (see "Release
notes" below).

## Generating the upload keystore

Keep this keystore safe. It is only the upload credential — Play App Signing holds
the app-signing key that actually reaches devices — so losing it never bricks the
listing or blocks users from updating. Recovery is an upload key reset in Play
Console (Setup → App integrity), where you register a new certificate and Google
switches the accepted upload key over; budget a couple of business days. Accepting
Play App Signing at the seed upload (step 2) is what keeps a lost key that cheap.

```sh
KEYSTORE_PASSWORD=$(openssl rand -hex 24)
keytool -genkeypair \
  -keystore release.keystore \
  -alias stopdash \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=StopDash Release, O=StopDash, C=US" \
  -validity 36500 \
  -keyalg RSA \
  -keysize 2048
base64 -w0 release.keystore > release.keystore.b64
echo "Password: $KEYSTORE_PASSWORD"
```

**Store `release.keystore` and its password in a password manager before deleting
anything.** GitHub secrets are write-only: once pasted, the value can never be read
back out, so the password manager is the only copy. Then save the base64 contents and
password into the secrets below and **delete the local keystore file** — but if this
is the very first AAB (step 2), keep it around until the upload key is enrolled in
Play App Signing.

## Required secrets

Add these in repo Settings → Environments → `production` (the `deploy` and
`release-build` jobs run in that environment; restrict its deployment branches to
`main`).

**Environment scope only — never as repository secrets.** A repository secret is
readable by any workflow run on any branch, and this repo is public with agents
pushing branches to it; an environment secret behind the `main`-only deployment
branch policy is reachable only from a `main` deploy. Secrets are not a boundary
against write access either way, which is the other reason the value belongs in a
password manager rather than only in GitHub.

`PLAY_SERVICE_ACCOUNT_JSON` needs no backup — a lost service-account key is
replaced by minting a new JSON key in Cloud Console. The release keystore is the
only entry here that cannot be regenerated equivalently.

| Secret | Description |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded PKCS12 keystore bytes (`base64 -w0 release.keystore`). |
| `RELEASE_KEYSTORE_PASSWORD` | Random hex string set when the keystore was generated. |
| `RELEASE_KEY_PASSWORD` | Same value as `RELEASE_KEYSTORE_PASSWORD` (PKCS12 convention). |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore. Use `stopdash` to match the snippet above. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Full JSON contents of the service account key from step 5. |

## Release notes

The "Build release notes" step collects the subject of every release-worthy
commit in the range — skipping `ci:` / `docs:` / `internal:` / `refactor:` /
`test:` / `tests:` prefixes and commits that only touch docs or dotfiles,
`docs/PRIVACY.md` included — into a
`• `-bulleted, oldest-first list written to `whatsnew-en-US`, truncated to
Play's 500-character per-language cap with a trailing `…` when it overflows. See
"Commit messages" in `AGENTS.md`: every subject is end-user copy.

## versionCode

Play rejects an AAB whose `versionCode` is `<=` the highest already on any
track. `versionCode` derives from `git rev-list --count HEAD` (see
`app/build.gradle.kts`), which increases monotonically as long as `main` only
moves forward. CI checks out with `fetch-depth: 0` so the count isn't truncated
by a shallow clone. A release build fails at its first task
(`:app:checkReleaseVersion`) when git can't read the history (a source archive)
or the clone is shallow, rather than ship `versionCode` 1 or a short count; run
`git fetch --unshallow` first. A debug build takes the fallback with a warning.

## Troubleshooting

- **`The Android App Bundle was not signed.`** — the release `signingConfig`
  didn't attach. Confirm `RELEASE_KEYSTORE_BASE64` is set and the materialize
  step ran.
- **`APK specifies a version code that has already been used.`** — usually a
  shallow clone truncated `git rev-list --count HEAD`; check the checkout ran
  with `fetch-depth: 0`.
- **`The caller does not have permission`** — the service account lacks
  "Release to testing tracks" on the app, or the invite hasn't propagated.
- **`Package not found: app.stopdash`** — the listing doesn't exist yet, or the
  first AAB hasn't been uploaded manually (step 2).
- **`Upload to Play Store internal track` is skipped** — `PLAY_SERVICE_ACCOUNT_JSON`
  isn't set, or nothing release-worthy is queued. The GitHub prerelease is
  unaffected in the first case, since it doesn't gate on that secret, so the
  signed bundle is still on the Releases page even when nothing reached Play.
- **`Publish a GitHub release` says a later push already published** — an older
  push's deploy reached that step after a newer one had published a higher
  `versionCode`. Standing down is correct: it keeps the newest release the
  newest build, and it stands the Play upload down too so Play cannot receive
  the older bundle either. The older push's commits are not lost — the notes
  range still measures from the last Play publication.
