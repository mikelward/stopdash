# stopdash National Rail relay

A Cloudflare Worker that holds stopdash's Rail Data Marketplace keys and serves National Rail's
current incidents and live departure boards to the app, so a rider needs no key of their own. Design, privacy rules and
costs: [`dev-docs/national-rail-proxy.md`](../dev-docs/national-rail-proxy.md). It logs nothing
about a caller; keep it that way (the tests fail on any console output while answering a request).

- `GET /v1/incidents`: today's incidents, as National Rail's Disruptions API gives them, at most
  five minutes old (`Age` says how old, `X-Fetched-At` when), else 503.
- `GET /v1/board/{CRS}` and `GET /v1/board/{CRS}/details[?to={CRS}[&offset={minutes}]]`: a
  station's live departure board, as Darwin's Live Departure Board gives it, cached for 30 s.
  Only a request that misses the cache reaches National Rail, and only those count against a
  caller (20 a minute) and the relay's own budget (60 a minute per Cloudflare location, to raise
  once the product's quota is known).
- `GET /v1/health`: when the incident list was last fetched, how the last try went, and the last
  failure (its type and stack frames). With Workers Logs off, this is where a failed refresh shows.
- A cron job every two minutes fetches the incident list and makes each day's secret for the
  per-caller count. Every incidents request reads that stored copy.

No dependencies. `npm test` runs the tests with Node alone.

## Deploying (maintainer, by hand)

From this directory, signed in with `npx wrangler@4 login`:

1. **Storage:** `npx wrangler@4 kv namespace create RAIL`, and put the `id` it prints in
   `wrangler.toml`.
2. **Address:** put the relay's hostname (a subdomain of a domain on this Cloudflare account) in
   `wrangler.toml`'s `routes`. Cloudflare creates its DNS record and certificate on deploy.
3. **Keys:** `npx wrangler@4 secret put DISRUPTIONS_API_KEY`, pasting the NationalRail Disruptions
   API key, and `npx wrangler@4 secret put LDB_API_KEY`, pasting the Live Departure Board key, both
   from the Rail Data Marketplace. Never write either into a file here.
4. **Deploy:** `npx wrangler@4 deploy`. Within two minutes `/v1/health` should say `current: true`.
5. **App:** set `stopdash.railRelayUrl=https://<hostname>` in the repo's `gradle.properties`.

Leave Workers Logs, Logpush and Analytics off for this Worker (`wrangler.toml` sets
`observability.enabled = false`), and add no WAF rate-limit rule: those record callers' IPs.
Cloudflare's own DDoS protection stays on, as for every Worker.
