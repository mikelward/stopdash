# Play Store listing and App content answers

**Draft, pending maintainer sign-off.** This is what we paste into Play Console. The facts
come from `docs/PRIVACY.md`, which stays the source of truth: when that changes, re-check
this. It covers the phone app only. The watch app shares this listing, so re-check the Data
Safety answers before it ships (`TODO.md` Phase 6).

## Store listing (Grow → Store presence → Main store listing)

The default language is **English (United States)**, matching the app's base strings and the
Play setup in `dev-docs/play-store-internal-track.md`. The UK gets its own translation (below),
the way `values-en-rGB` overrides the app's strings.

**App name** (30 characters max): `Routemo - London bus + tube`

**Short description** (80 characters max):

> Live TfL departures near you, in the app and on your home screen.

**Full description:**

> Live bus, Tube, Overground, DLR, Elizabeth line and tram departures from the stops near you, at a glance.
>
> • **Near me**: the stops closest to you, sorted by distance, with their next departures
> • **Widget**: the stops near where you last opened the app, on the home screen (and the lock screen, where your phone supports it), with optional refresh every minute
> • **Favorites**: star the services you catch so they stay at the top
> • **Journeys**: star two stops to see which trains get you from one to the other
> • **Disruptions**: closures and delays on your lines, right on the stop's card
> • **Find a station**: search any stop or station by name, no location needed
> • **National Rail** (optional): add your own free API key to see live mainline times
>
> Honest about freshness: every time shows how old it is, and Routemo never passes old data off as live. When TfL can't be reached, it says so.
>
> No ads, no account. Your location goes only to TfL, only to find stops near you. On a trip you start, it's also checked on your phone to see if you boarded, and never sent. A bug report includes it only if you agree. Crash reports and usage stats (with a rough region from your IP address) are opt-in and off by default.
>
> Powered by TfL Open Data. Routemo isn't affiliated with or endorsed by Transport for London.

Until the widget shows user-chosen watched stops (`TODO.md` Phase 2), the copy promises
stops *near you*, not "your stops"; revise it when that lands.

Keep the last paragraph: TfL's open-data terms require the attribution, and the disclaimer
keeps the listing clear of Play's impersonation policy. Don't use TfL's logo or roundel.

**English (United Kingdom) translation** (Store presence → Main store listing → Manage
translations → add English (United Kingdom)): the same app name, short description and full
description, with one change: "**Favorites**" becomes "**Favourites**".

**Graphics:** `icon-512.png` (app icon) and `feature-graphic.png` (feature graphic), both in
this directory. **Category:** Maps & Navigation.

## App content (Policy → App content)

| Question | Answer |
|---|---|
| Privacy policy | `https://mikelward.github.io/stopdash/PRIVACY.html` (open it first to check it loads) |
| App access | All or some functionality is restricted. Instructions: "No login. Live National Rail times need the user's own free Rail Data Marketplace key, pasted in Settings; everything else works without one." Add a reviewer key in Play Console if you want the reviewer to see National Rail times (never commit it here) |
| Ads | No |
| Advertising ID | No (the manifest removes `AD_ID`) |
| Content rating (IARC) | Category "Utility, Productivity, Communication or Other"; No to every question, including user interaction, sharing location with other users, and digital purchases. Expect Everyone / PEGI 3 |
| Target audience | 13 and over only; appeals to children: No (keeps the app out of the Families policy) |
| News app | No |
| Government app | No (uses TfL data but isn't a TfL or government app) |
| Financial features | None |
| Health | No |
| Location permissions | Foreground only, so no background-location declaration |

## Data Safety

- **Collects or shares any required user data types:** Yes
- **Encrypted in transit:** Yes (TfL, National Rail and Firebase are all HTTPS)
- **Account creation:** the app doesn't let users create an account
- **Deletion request:** No (everything else is stored on the device, and clearing the app's data removes it)

| Data type | Collected | Shared | Ephemeral | Required? | Purpose |
|---|---|---|---|---|---|
| Location → Precise location | Yes | No | No | Optional | App functionality |
| Location → Approximate location | Yes | No | No | Optional | App functionality, Analytics |
| App activity → In-app search history | Yes | No | No | Optional | App functionality |
| App activity → App interactions | Yes | No | No | Optional | Analytics |
| App info and performance → Crash logs | Yes | No | No | Optional | Analytics |
| App info and performance → Diagnostics | Yes | No | No | Optional | Analytics |
| Device or other IDs | Yes | No | No | Optional | Analytics |
| Personal info → User IDs | Yes | No | No | Optional | App functionality |

Why:

- **Location:** "near me" sends your position to TfL, precise if you grant precise location,
  otherwise approximate. It's Optional because you can deny the permission and still use
  the watched list and Find a station. Approximate location is also Analytics, because the
  opt-in Firebase records the region it infers from your IP address.
- **In-app search history** is **Optional**: Find a station sends what you type to TfL,
  but only when you choose to search. You can use the app without searching, through the
  nearby stops instead.
- **User IDs** covers the optional API keys you paste in Settings: your TfL `app_key`, sent
  with your TfL requests, and your Rail Data Marketplace key, sent with National Rail
  requests. Each goes only to the service that issued it, and ties those requests to your
  account there.
- **Everything marked Analytics** is sent only when *Help make Routemo better* is on, and
  it's off by default. That's why those rows are Optional.
- **Ephemeral = No** on every row: Routemo can't vouch for how long TfL keeps a request,
  and the app keeps its last few lookup positions in its cache for up to a day. Answering No
  never understates.
- **Shared = No:** a TfL request is user-initiated and expected, and Firebase acts as a
  service provider. Play excludes both from "sharing".
- **Not declared:**
  - The bug report: the user hands it to an app they choose through the share sheet, so
    Routemo never collects it.
  - The Play update check: it carries no user data.
  - Android backup: it's the platform's channel, not the app's.
