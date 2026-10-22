# Syncing a rider's setup through their Google Drive

**Status: planned, favorite rides first (maintainer, 2026-10-22); the sync engine is built
(`Sync`, `SyncFile` in `:domain`), unused.** A
developer-facing write-up of how StopDash syncs a rider's saved setup between their phones
through their own Google Drive. The steps are the *Sync a rider's setup between their phones*
item in Phase 2 of `TODO.md`; what syncs after rides waits on the open questions at the end.

**Cost and reliability up front:** £0. The Drive API has no charge, and the data is a few KB
of the rider's own Drive storage. It needs Google Play services and a Google account. With no
server, nothing is pushed: each phone checks Drive when it opens, about one request each
time, and it adds no wakeup. The Google Cloud project exists already (Firebase's); the new
parts are the Drive API turned on in it, an OAuth consent screen, a Google consent sheet the
rider sees once, and a Play Data Safety declaration (see *Privacy and Play*).

## The problem

A rider with two phones in use sees rides, places and stars saved on one phone missing on the
other (maintainer, 2026-10-22). Android's own channels don't cover that:

- **Auto Backup and device-to-device transfer** (SPEC *Privacy*, allowed since 2026-09-18)
  copy StopDash's files to the rider's Drive about once a day, and restore them only when the
  app is installed on a phone being set up, or reinstalled. Each phone has its own backup, and
  a restore replaces the setup rather than merging it.
- **Account sync** (the sync switches under an account in Settings, `SyncAdapter`) is only a
  scheduler. Google's own apps use it with Google's servers; any other app brings its own
  server.
- **Export and import** through the share sheet was rejected as not useful (maintainer,
  2026-10-22).

## What Drive offers

- **The app data folder** (`appDataFolder`): a hidden folder in the rider's Drive that only
  StopDash can read or write. The rider can't browse it, but can delete it (Drive's settings →
  *Manage apps* → StopDash → *Delete hidden app data*). It counts against their Drive storage.
- **The `drive.appdata` scope** reaches that folder and nothing else in the rider's Drive.
  Google lists it as *recommended, non-sensitive*, which needs only basic app verification of
  the consent screen, not the security review restricted scopes do.
- **Authorization on Android** is Play services' `AuthorizationClient`
  (`Identity.getAuthorizationClient(…).authorize(…)`, `play-services-auth`): the first call
  shows Google's account choice and consent sheet; later calls return an access token
  silently. A grant the rider revoked comes back as a `PendingIntent` that needs the screen, so
  a background sync can only say "sign in again". No sign-in identity is needed: StopDash never
  learns who the rider is, only that it may use the folder.
- **Setup**: the Firebase project is already StopDash's Google Cloud project
  (`dev-docs/firebase.md`), and the Drive API needs no billing plan. In it: the Drive API turned
  on, the OAuth consent screen published for riders (app name, privacy policy link), and an
  Android OAuth client per signing key: the Play app-signing key for release, and a client of
  its own for the `.debug` build, which never gets Firebase but can still use the project's
  OAuth. A project has one consent screen, so the Desktop client the analytics script may use
  there shares it. Google ties a rider's grant to the project, not the client, and the app data
  folder very likely follows; unconfirmed, so a test should settle it. Either way debug and
  release must not share data: file names that differ by build, or a second project for
  debug builds.
- **No push without a server.** Drive's change notifications (`changes.watch`) are delivered
  to an HTTPS webhook, which StopDash doesn't have. So each phone asks: listing the folder's
  files with their modified times is one small request.
- **No compare-and-swap.** Whether Drive v3 rejects a write over a newer version of a file (an
  `If-Match` precondition) is unconfirmed; the design below doesn't need one.

## Design sketch

### What syncs

| Store | Syncs? |
| --- | --- |
| Favorite rides (`starred-journeys.json`): rides with their hidden lines, grayed rides, ride alert times | Yes |
| Favorite places (`favorite-places.json`) | Yes, if the maintainer accepts their positions in Drive (open question 1) |
| Wi-Fi learned at places (`wifi_places`) | No: each phone learns its own (open question 3) |
| Watched stops, starred rows, dismissed alerts | Yes |
| Settings (`app-settings.json`): hidden modes and lines, trip modes, avoided lines, walking speed, max walk, step-free, units, ride grouping, train spacing, disruptions row | Yes |
| Settings that belong to one phone: font scale, pinch zoom, live widget refresh, watch options, dismissed cards, bug-report consent | No |
| TfL `app_key`, National Rail key | Open question 2 |
| Telemetry consent, alert state, widget and watch state, the departures snapshot, caches, nearby stops, recents, location | Never |

### One file per phone

Each phone writes only its own file (`phone-<install id>.json`) and reads everyone's. No two
phones ever write the same file, so no change is lost to a simultaneous write, with or without
compare-and-swap. Each file carries that phone's whole merged view. **No phone deletes
another's file**, however long untouched: its phone could come back and write it just as
another deletes it, losing changes nobody merged (Codex on #1141). A phone gone for good
leaves a few KB behind, read once and then skipped while its modified time stands; deleting
StopDash's data from Drive clears it. A phone that finds its own file missing uploads it again.

### Merging: newest wins, removals kept

- Every entry (a ride by its key, a place by its id, a watched stop, a star, a dismissal, each
  setting) carries when it last changed and whether it was removed. When two phones disagree,
  the newer change wins. Merging is order-free and repeatable: reading the files in any order,
  any number of times, gives the same result.
- **A removal is kept as a record, for good**, so no phone's copy can bring a removed ride back,
  however long that phone was away: one returning after a year still finds the removal newer
  than its copy of the ride, and drops the ride. A record is an entry's key and a time, a few
  dozen bytes, so keeping every one costs nothing that matters; pruning them would let a phone
  back after the pruning republish what they removed (Codex on #1141).
- **The stores stay as they are.** Sync keeps its own ledger per phone, in the same format as
  its file: each entry's value and change time as last synced. A sync compares each store with
  the ledger to timestamp what changed here, merges the other phones' files, and writes the
  result back through each store's own edit path, under its existing write lock, and only while
  the store still holds what was compared, else a change made meanwhile would be undone. Each
  store says which two values are the same entry, so a value this build re-encodes without a
  newer build's fields isn't a change, and never overwrites that build's richer copy.
- **Change times** are a hybrid logical clock (wall time, a counter, the install id to break
  ties), so two edits never tie, and an edit made after a phone has read another's file comes
  after every change in it. **Its wall time is Drive's, never the phone's** (maintainer,
  2026-10-22): the `Date` on Drive's answer that listed the files, plus the phone's monotonic
  time since (`DriveClock`). Every phone stamps by one clock, so a phone whose own clock is
  wrong, by a minute or a year, stamps like the rest and never needs setting right to sync
  (Codex on #1141, twice). A sync stamps the changes it finds when it runs, so of two phones
  that change one entry between their syncs, the one that syncs later wins.
- **Joining never removes anything.** A phone's first sync adds its entries to what Drive
  holds and takes what Drive holds: two phones' rides end up as one list. Each sync reads the
  other phones' files before it stamps this phone's changes, and stamps them after everything in
  those files, so a joining phone's rides beat any removal already in Drive, even one stamped in
  the same second (Codex on #1141). The store is compared with the ledger, not the merged view,
  so an entry only another phone holds is never taken for one removed here. Only a phone that has
  never synced with that Google account joins: turning sync off keeps the ledger, so turning it
  back on resumes, and a ride removed meanwhile on another phone is removed here too.
- **Edits within an entry** (a ride's hidden lines, a place renamed or moved) replace the whole
  entry; per-field merging can wait until it matters. Rides have no order to sync (nearest
  first); a list the rider orders (place chips, watched stops) syncs its order as one entry.
- **Mixed app versions:** a phone that can't read another phone's newer file skips it and says
  "Update StopDash to sync with your other phone"; it never removes anything on that file's
  account. With one file per phone, the newer phone still reads the older one's.

### When it syncs

- When the app comes to the front (with its refresh), and a few seconds after a change here (a
  one-time WorkManager job, waiting for a network). No periodic job; the widget and the watch
  never sync. No new wakeup.
- Each sync: one request to list the folder; a download for each file that changed since
  (usually none or one); an upload of this phone's file only if it changed. Typically one
  request per app open.
- A change on one phone shows on the other the next time it's opened. Network is never on a
  render path: the screens render from the stores as now, and a sync that lands updates them as
  any edit does.

### What the rider sees

- Settings → **Sync with Google Drive**, off by default. Turning it on shows Google's account
  choice and consent sheet ("See, create and delete its own configuration data in your Google
  Drive"), then merges this phone's setup with Drive's. The row names the account.
- Its status: "Synced 2 min ago", "Couldn't sync: offline", "Sign in again" (with a button),
  "Drive is full", "Update StopDash to sync with your other phone". Never silent (SPEC principle
  2).
- Turning it off stops syncing and leaves the phone's setup as it is, with an offer to delete
  StopDash's data from Drive. Deleting it while another phone still syncs only means that phone
  uploads its own file again; nothing is lost.

### Android backup

The install id and the sync ledger must be excluded from Auto Backup and device-to-device
transfer (`backup_rules.xml`, `data_extraction_rules.xml`): a phone restored from another's
backup would otherwise write as that phone, and the two would overwrite each other's file. The
grant doesn't travel either, so a restored phone with sync on says "Sign in again".

## Privacy and Play

- **A new channel StopDash itself starts.** Unlike Android backup (a platform feature the rider
  controls), sync uploads the rider's stops, rides and places, with Home's and Work's positions
  if places sync, to Google. It goes to the rider's own Drive and the developer never receives
  it, but `docs/PRIVACY.md`, SPEC *Privacy* and the consent screen's privacy policy all need to
  say so. Google's API Services User Data Policy applies to the app's use of the scope.
- **Play Data Safety** counts transmitting data off the device as collection. No published
  guidance exempts an upload to the user's own Drive. End-to-end encryption is exempt, but it
  needs a key both phones hold without a server, and the only channel the phones share is Drive
  itself, so it doesn't help. Assume a declaration: location (places' positions) and app
  activity (rides, stops), collected, optional, not shared, encrypted in transit, deletable by
  the user. Confirm with Play policy support before shipping.
- Off by default and opt-in, like crash reports and usage stats. Until the Data Safety form is
  updated, release builds show no Settings row and send nothing to Drive (maintainer,
  2026-10-22), so the steps before it ship without changing the Play declaration.

## Failure modes

| What happens | What the rider sees |
| --- | --- |
| Offline | "Couldn't sync: offline"; the next open tries again |
| Grant revoked, password changed | "Sign in again"; the phone's setup is untouched |
| Drive full (`storageQuotaExceeded`) | "Drive is full" |
| Rate limited | Not expected: Drive's per-user quotas are far above a few requests per open; a refusal is said, and the next open tries again |
| A phone's clock is wrong | Nothing: changes are stamped by Drive's clock |
| Two phones change one entry between their syncs | The change synced later wins; the other change to that entry is lost |
| The rider deletes StopDash's data from Drive | Each phone uploads its own file at its next sync; nothing is lost |
| Different Google accounts on the two phones | They don't sync; the row names each phone's account |
| No Play services | The option isn't offered |

## The work

- `play-services-auth` for `AuthorizationClient`. The Drive calls (list, download, create,
  update, delete) go over the existing Ktor client rather than Google's Java API client.
- Phases (maintainer, 2026-10-22: rides first): authorization, the Settings row and the sync
  engine with rides alone; then places, watched stops, stars and dismissals; then settings.
  Each store gains a diff-and-apply adapter for the engine; the engine is pure `:domain` and
  JVM-tested on recorded files, as the rest of the domain is.

## Open questions for the maintainer

1. Sync favorite places, which puts Home's and Work's positions in Drive?
2. Sync the TfL and National Rail keys?
3. Sync the Wi-Fi learned at places?
4. Which settings belong to one phone?
5. Accept the Play Data Safety declaration above?

Which Cloud project is settled: the Firebase project, already the app's Google Cloud project,
with debug builds kept apart by file name (2026-10-22).
