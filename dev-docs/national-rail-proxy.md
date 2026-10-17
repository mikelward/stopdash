# National Rail proxy (design)

Status: **proposed** (maintainer, 2026-10-17). Nothing here is built yet. Decisions marked
**Open** need the maintainer's call before building.

## Why

National Rail data comes from two Rail Data Marketplace (RDM) products, each with its own key:

- **Live Departure Board** (Darwin, `GetDepartureBoard` / `GetDepBoardWithDetails`): times at a
  station. Used today, with a key the rider pastes in Settings.
- **NationalRail Disruptions API** (`POST /disruptions/incidents/search`): National Rail's own
  incident text. Not used yet. TfL's status for a National Rail line is often only a link to
  `nationalrail.co.uk/service-disruptions/<slug>/`; each incident in this feed carries that exact
  link in `disruptionLinks[].uri`. On 2026-10-17 all eight National Rail lines whose TfL status
  was a bare link matched an incident by it.

Asking every rider for one key, let alone two, keeps National Rail out of reach for most. A
small proxy holding the maintainer's keys removes both, and its cache keeps the marketplace load
independent of how many riders there are.

## Goals and non-goals

- **Goals:** no key needed for National Rail times or incident text; marketplace load bounded by
  stations and time, not riders; **no user data stored or logged**, apart from a one-minute count
  of each caller's cold board requests under a key their IP can't be recovered from, so
  `docs/PRIVACY.md` needs only a short addition and the Play Data Safety form one added purpose on
  a row it already has (*Policy and Play Data Safety*); the app's staleness
  contract (SPEC D4) holds through the cache.
- **Non-goals:** accounts, analytics, push, or any other server feature. The proxy answers
  National Rail questions and nothing else.

## Shape

One **Cloudflare Worker** (decided; see *Hosting*), with a per-data-center **Cache API**
cache, a **KV** namespace for the shared incident list, a **Cron Trigger** for the incident
prefetch, and, with the boards, two Workers **rate-limit bindings** for requests that miss the
cache: one per caller, keyed by a rotating hash, never the IP itself, and one per product. The two RDM keys are Worker secrets
(`wrangler secret put`), never in the app or the repository.

```
phone ──HTTPS──▶ Worker ──(cache hit)──▶ phone
                   │ miss / refresh
                   ▼
           api1.raildata.org.uk  (Live Departure Board, Disruptions API)
```

## Endpoints

All `GET`, JSON, versioned under `/v1`. Responses pass the marketplace body through unchanged
(so the app's existing parsers, `DarwinBoardDto` and the incident DTO, stay as they are), plus
`Age` (whole seconds since the Worker got the body from the marketplace, counted by the Worker)
and `X-Fetched-At` (that instant, for people reading it; the app never compares it with its own
clock).

| Path | Upstream | Notes |
|---|---|---|
| `/v1/incidents` | `POST …/disruptions/incidents/search` `{startDate, endDate}` = today in London | Served from the prefetched copy only. |
| `/v1/board/{crs}` | `GetDepartureBoard/{crs}?numRows=20` | The plain board every screen shares. |
| `/v1/board/{crs}/details` | `GetDepBoardWithDetails/{crs}?numRows=10` | A trip's calling points. |
| `/v1/board/{crs}/details?to={crs}&offset={m}` | same, `filterCrs`, `filterType=to`, `timeOffset` | Following a train (`RailBoardSource.runs`). |
| `/v1/health` | none | The incident list's `fetchedAt` and the cron job's last attempt and status. No user data. |

**Validation**, before anything touches the cache or the marketplace: `crs` and `to` match
`^[A-Z]{3}$`; `offset` is an integer the app already snaps to quarter hours, accepted only as a
multiple of 15 in −120…105 (Darwin takes −120…119); any other query parameter is a 400. That bounds the space of
distinct upstream requests, so neither the cache nor the marketplace can be flooded with
variants.

## Caching

**Incidents: prefetched.** A Cron Trigger every **2 minutes** asks the Disruptions API for
today's incidents (London date; the minute after midnight asks for the new day). A good answer
is written to KV with its `fetchedAt`; a failed one leaves the last good copy and records the
failure for `/v1/health`. Requests read through the Cache API (60 s) in front of KV.

- Upstream load: **720 requests a day**, fixed, whatever the number of riders.
- A copy older than **5 minutes** (the app's shared staleness bound, two missed runs) is not
  served: `/v1/incidents` answers 503, and the app falls back to TfL's link. The app holds words no
  longer than that either, from the relay's fetch by its `Age`. A stale incident list never passes
  for a current one.

**Boards: cached on request, not prefetched.** The cache key is the normalized upstream request
(path and validated parameters, never the key or anything from the caller).

- **Served for 30 s, then fetched again.** No stale-while-revalidate in v1 (autopilot, TODO
  *Decisions needing review*). The app dates each board by the relay's `Age`, so a cached one is
  never taken for fresher than it is; a longer window is a cost and freshness call, not a
  correctness one.
- **One upstream request per key at a time** within an isolate: concurrent misses share it.
  A data center can run several isolates, and the Cache API doesn't collapse concurrent misses
  across them, so a cold burst can fetch the same board a few times over.
- Upstream load is therefore about one request per station variant per data center per 30 s,
  times the isolates that miss together. It tracks the stations riders look at, not the riders;
  London traffic lands mostly on one or two data centers. The hard bound is the **upstream
  budget** (*Abuse and quotas*), not this estimate. Should duplicate fills ever spend a real share
  of the budget, a Durable Object per board key coordinates them across isolates, at some added
  latency and cost; not in v1.
- **Why no board prefetch:** warming "popular" stations means keeping a list of the stations
  riders asked for. However anonymous, that's a record built from user requests, which this
  design keeps none of.

## Staleness (SPEC D4)

Data is dated by when the **marketplace** answered, not by when the app asked, and by a duration
the relay counts (`Age`), never by comparing the phone's wall clock with a server timestamp: either
clock can be wrong, and the app's stamps are steady-clock ones. The incident list is used for at
most the shared staleness bound from the relay's fetch (`RailIncidentCache`), after which TfL's
link is shown again. A board is dated the same way: its `Age` is taken off its steady-clock stamp
(`RailBoard.age` into `ArrivalsCache`), so it goes stale at the bound counted from the relay's
fetch, not from the app's ask.

## No user data: what the proxy sees and keeps

**What reaches it.** For `/v1/incidents`, nothing about the rider: the request has no
parameters. For boards, the path names a station (and for a followed train, its terminus and a
quarter-hour offset), which says roughly where the rider is or is going. Every request also
arrives with the phone's **IP address** and **User-Agent**, which Cloudflare's edge needs to
answer it.

**What it keeps.** Only the cached marketplace answers, keyed by station and parameters, which
are the same public data for everyone. Nothing ties a cache entry to who asked for it. With the
boards, also a one-minute count of each caller's cache misses under a hashed IP (rule 3), which
can't be turned back into an IP and is gone when the window closes. The incidents alone keep
nothing per caller: every request reads the stored list.

**Rules the code and configuration must follow:**

1. **No request logging of any kind.** No `console.log` / `console.error` in request handling.
   `observability.enabled = false` in `wrangler.toml` (Workers Logs off). No Logpush, no
   Analytics Engine, no `wrangler tail` against production, and Cloudflare Web Analytics off for
   the Worker's route. What can't be turned off is Cloudflare's own edge analytics: sampled
   records of requests to the zone, which can include the client IP, kept for about a month and
   readable in the dashboard. That is the host's processing as a service provider, not a log the
   relay writes, and `docs/PRIVACY.md` says so (*Policy and Play Data Safety*).
2. **The Cron job may log**, since no request is involved: upstream status and timing only.
3. **No raw IP is ever a key, and no WAF rule.** A WAF rate-limit rule logs blocked IPs to
   Security Events. The Workers rate-limit binding syncs its counters through a backing store
   within the data center, so the per-caller limit is keyed by
   `HMAC-SHA256(daily secret, IP)`, truncated, never the IP, and counts only cache misses. The secret lives only in the Worker's
   memory and KV, rotates at midnight London time, and the old one is deleted, so a key can't be
   turned back into an IP, matched against one without the secret, or linked across days. The
   counter itself lasts one window (60 s).
4. **Upstream requests are built fresh**: URL, the RDM key header, `Accept`. Never copy the
   incoming request or its headers (no `CF-Connecting-IP`, `User-Agent` or `X-Forwarded-For` to
   the marketplace).
5. **Errors answer without echoing input**: a 400 says which rule failed, not the value.
6. **A test asserts the rules**: the Worker's suite runs every endpoint, including failures,
   with `console` spied, and fails on any output; and it checks an upstream request carries
   only the allowed headers.

**Cloudflare itself** processes the IP to route and answer the request, as any host or CDN
must, under its data processing terms. It's a service provider here, not a recipient the data
is shared with.

## Abuse and quotas

- There's **no secret in the app**: any key shipped in an APK can be extracted, so it would only
  pretend to protect. Protection is the validation above (a bounded request space), the cache
  (an attacker mostly hits it), and two limits on **cold requests only**, those that miss the
  cache and so reach National Rail (maintainer, 2026-10-17). A cached answer costs National Rail
  nothing, so it's never refused; what it costs the Worker's own bill is Cloudflare's DDoS
  protection's to bound. The incidents have no cold requests at all (the cron job fetches them), so
  they need neither.
- **Per caller**: 20 cold board requests a minute per hashed IP (rule 3). A rider following a trip
  causes at most about 6 (up to three boards every 30 s, fewer when another rider's are cached), so
  it leaves room for a few riders behind one carrier-NAT address while stopping a scraper walking
  stations within seconds. Over it, a 429, which the app treats as a failed refresh and shows
  honestly; a board already cached is still served.
- **Upstream budget**: the rate-limit binding, keyed by product, caps cold calls to the
  marketplace (60 a minute to start, raised under the product's quota once known). A miss over
  budget gets a 503, which the app shows as "No data". It protects the marketplace key whatever one
  data center's callers do, including many at once, which a per-caller cap can't. It isn't a global
  count: Cloudflare keeps a rate limit's counter per location and lets it run a little over, so the
  aggregate bound is the budget times the locations serving riders (London's traffic lands mostly
  on one or two). Size it with that in mind, at about half the product's quota; a global count
  (a Durable Object) is the step up if the quota turns out tight. Cloudflare's own DDoS mitigation still sits in front, as
  it does for every Worker.
- **Open:** each RDM product's quota and terms of use. Neither product page has been read from
  this environment. Before launch, confirm the request allowance per key and that serving the
  data to the app's users through a proxy is permitted (the Disruptions API is listed as Open
  Government Licence, which also asks for attribution).
- **Play Integrity** could later gate the endpoints to genuine installs if abuse shows up. Not
  in v1: it adds a Google round trip to the cold path.

## Cost

- **Cloudflare Workers:** the free plan covers 100,000 requests a day. A rider refreshing two
  rail stations once a minute for 30 minutes makes ~60; the free plan carries ~1,500 such riders a
  day. Past that, Workers Paid is **$5 a month** for 10 million requests.
- **KV:** 720 writes and well under 100,000 reads a day, both inside the free plan.
- **Rail Data Marketplace:** free, within whatever per-key quota the products set (see *Open*).
- **Battery:** no new wakeup or refresh interval on the phone; requests replace ones made today.

## Failure modes

| Failure | What the rider sees |
|---|---|
| Proxy unreachable or 5xx | A National Rail line's times say "No data", as a failed board does today. Incident text falls back to the link's own words. |
| Incident list older than 5 min | The relay answers 503 and the app drops the words it held from it, showing TfL's link. |
| Marketplace down | A board the relay fetched under 30 s ago is still served; past that the relay answers 502 and the app treats it as any failed board: it keeps showing what it already had within its own staleness bound, marked stale, then "No data". No stale-if-error at the relay in v1. |
| Rider pasted their own key | The app goes straight to the marketplace with it, bypassing the proxy (kept as the escape hatch, and for anyone who'd rather not use it). |

## App changes (later PRs)

Disruptions first (maintainer, 2026-10-17): the relay ships with `/v1/incidents` alone and the
app's incident text, which carry nothing about the rider, then boards in a later release.

1. **Incidents**: an incident client against
   `/v1/incidents`; National Rail line statuses whose `fullText` is only a
   `nationalrail.co.uk/service-disruptions/…` link get the matching incident's summary and text
   for display (`LineStatus` keeps TfL's own `fullText` for identity, so dismissals don't change).
   No key, no new Settings row.
2. **Boards**: `KtorDarwinClient` routes each request by whether the rider has a key: their own,
   straight to Darwin with its paths and parameters, as now; none, to the relay's own paths
   (`/v1/board/{crs}`, `/details`, `?to=`, `&offset=`), with no key and the board's codes alone.
   It isn't a base-URL swap: the relay's paths aren't Darwin's. National Rail then works without a
   key, so the "No key" states, the default-hidden National Rail mode (`RailKeyDefault`) and the
   "National Rail needs a key" dialog all change. That's a product change for SPEC
   *National Rail* in the same PR.
3. **Settings links:** the remaining key row links to its marketplace page for riders who want
   their own key: Live Departure Board,
   <https://raildata.org.uk/dashboard/dataProduct/P-d81d6eaf-8060-4467-a339-1c833e50cbbe/overview>.

## Policy and Play Data Safety

Constraint (maintainer, 2026-10-17): a **small wording change** to the privacy policy, and as
little change to the Data Safety form as possible. The per-caller cold limit (*Abuse and quotas*)
is the one thing the relay keeps about a caller, so it is disclosed rather than argued away
(maintainer, 2026-10-17, choosing it over a product-only budget): its counter outlives the request
in the rate-limit binding's backing store, which Play doesn't treat as ephemeral.

- **Data Safety form: one added purpose.**
  - *Device or other IDs*: already **Collected, Optional**, for Analytics (the opt-in Firebase).
    It gains **Fraud prevention, security, and compliance**, for the relay's per-caller key. It
    stays Optional: the key is made only for National Rail times fetched without a key of your
    own, which the app works without and which your own key avoids.
  - *Approximate location*: already **Collected**, for app functionality. A station code reaching
    the proxy instead of National Rail is the same data type for the same purpose.
  - *Shared: No* still holds. The marketplace sees the Worker, not the rider: no IP, no
    User-Agent, nothing per rider (rule 4). Cloudflare hosts the Worker as a service provider,
    which Play doesn't count as sharing; its sampled edge analytics (rule 1) are that processing,
    and add no data type the form doesn't already declare: Play has none for an IP address as
    such, and what one can stand for, approximate location and an identifier, already are.
  - *Ephemeral: No* stays as it is on every row.
  - *Encrypted in transit: Yes* still holds: the proxy is HTTPS only. The note listing the hosts
    is our own text in `listing.md`, not a form answer.
  - *User IDs* keeps its Rail Data Marketplace key line, because pasting your own key stays
    possible (the escape hatch in *Failure modes*).
- **`docs/PRIVACY.md`: a short addition.** The National Rail paragraph says that without a key of
  your own the same requests go through stopdash's relay, which sees the same station codes and
  your connection's IP address, passes on only the codes, and logs nothing; and that, to protect
  National Rail's limits, it counts for one minute the requests it couldn't answer from its cache,
  under a key your IP address can't be recovered from, and keeps nothing else about you. It adds
  that Cloudflare, which hosts the relay, keeps its own sampled request records, which can include
  your IP address, for about a month, as any web host does. The summary names the relay as a
  destination. Nothing else moves: what is sent, why, and that it's
  never your location are all as now.
- **Must not grow.** Anything that would change a form answer further is out of scope for the
  proxy unless the maintainer decides otherwise: a log or analytics of requests, a stored list of
  stations asked for, an install ID or other identifier on requests, a caller key kept longer than
  one rate-limit window, or forwarding anything about the rider upstream.
- **Store listing text** (not the Data Safety form): the *App access* note and the description's
  "add your own free API key" line change once boards need no key. That's an ordinary listing edit.

## Hosting

**Cloudflare Workers** (maintainer, 2026-10-17). No Worker request logs unless turned on (the
zone's own sampled edge analytics, rule 1, aside); the rate-limit binding
counts without logging; Cron Triggers and KV are built in; the free plan covers the expected
load. **Rejected: Firebase Cloud Functions** (the project already uses Firebase): needs the
pay-as-you-go plan, and Cloud Run's request log records every caller's IP and User-Agent by
default, so honoring "logs nothing" means a Log Router exclusion that has to stay in place.

## Where the code lives

**`proxy/` in this repository** (maintainer, 2026-10-17), as clothescast keeps its Gemini TTS
proxy in `functions/`:

- A JavaScript Worker with no dependencies, its own `package.json`, tests (Node alone) and `wrangler.toml`, and nothing the
  Gradle build reads.
- **Its own CI job** runs the Worker's tests; `.github/lanes.conf` sends `proxy/**` down the code
  lane, and an Android-only change needs no Worker run (and the reverse), which `classify` decides
  by path.
- **Release notes skip `proxy/` by path**, as clothescast's skip `functions/`: the relay isn't in
  the APK, so a relay commit never ships a Play bullet. Its subjects still take a prefix
  (`internal:`) so intent reads plainly. Relay commits do count towards versionCode, as
  clothescast's do; the number only has to rise.
- **Deployed by hand** (`wrangler deploy` from `proxy/`), as clothescast's functions are, so no
  Cloudflare token sits in this repository's secrets beside the Play release keys.
- One difference from clothescast on purpose: no App Check or anonymous sign-in. Those give each
  install an identifier, a new Data Safety answer, and the relay serves public data that doesn't
  need one.

## Domain

**A subdomain of the maintainer's own domain** (maintainer, 2026-10-17), whose DNS is on Cloudflare
anyway, served as a Worker custom domain (`routes` with `custom_domain = true` in
`proxy/wrangler.toml`): free, and the address every installed build carries stays the
maintainer's, so the relay can move host later without breaking old installs. `workers.dev` stays
off for the Worker (`workers_dev = false`), so there's one address to reason about.

## Open decisions

1. **RDM quotas and terms** for both products (above).
