// stopdash's National Rail relay (dev-docs/national-rail-proxy.md): National Rail's current incidents,
// fetched on a schedule with stopdash's own Rail Data Marketplace key, so the app needs no key of its
// own. A request carries nothing about the rider and nothing about it is logged or kept: no
// console output in request handling (the tests hold it to that), Workers Logs off in
// wrangler.toml, and no per-caller state at all. Every request is answered from the stored list,
// so none reaches National Rail and none needs a per-caller limit: that comes with the boards,
// counting only requests that miss the cache (dev-docs/national-rail-proxy.md *Abuse and quotas*).

const UPSTREAM = "https://api1.raildata.org.uk/1010-disruptions-experience-api-11_1/disruptions/incidents/search";
const INCIDENTS = "incidents";
// The oldest list served, the app's own staleness bound (SPEC D4): past it the app falls back to
// TfL's link rather than show words National Rail may have changed or cleared. Two missed runs.
export const MAX_AGE_MS = 5 * 60 * 1000;
// How long an edge copy of the list is kept before KV is read again: as often as the app asks.
const EDGE_SECONDS = 60;

export default {
  fetch: (request, env) => handle(request, env),
  scheduled: (event, env, ctx) => ctx.waitUntil(refresh(env, event.scheduledTime)),
};

/** Answers one request from the stored list. Never logs, never forwards anything from it. */
export async function handle(request, env, now = Date.now()) {
  if (request.method !== "GET") return error(405, "method not allowed");
  const url = new URL(request.url);
  if (url.search) return error(400, "no query parameters");
  if (url.pathname === "/v1/health") return health(env, now);
  if (url.pathname !== "/v1/incidents") return error(404, "not found");
  const held = await env.RAIL.get(INCIDENTS, { type: "json", cacheTtl: EDGE_SECONDS });
  if (!held?.body || !held.fetchedAt || now - held.fetchedAt > MAX_AGE_MS) return error(503, "no current incident list");
  return new Response(held.body, {
    headers: {
      "content-type": "application/json",
      "x-fetched-at": new Date(held.fetchedAt).toISOString(),
      // How old the list is, counted here: the app dates it by this, never by comparing its clock with
      // the timestamp above.
      age: String(Math.max(0, Math.floor((now - held.fetchedAt) / 1000))),
      "cache-control": `public, max-age=${EDGE_SECONDS}`,
    },
  });
}

/** What the relay last fetched and when: nothing about any caller. */
async function health(env, now) {
  const held = (await env.RAIL.get(INCIDENTS, { type: "json" })) ?? {};
  return Response.json({
    incidents: {
      fetchedAt: held.fetchedAt ? new Date(held.fetchedAt).toISOString() : null,
      current: Boolean(held.fetchedAt && now - held.fetchedAt <= MAX_AGE_MS),
      lastAttemptAt: held.lastAttemptAt ? new Date(held.lastAttemptAt).toISOString() : null,
      lastStatus: held.lastStatus ?? null,
    },
  });
}

/** How long the cron job waits for National Rail: well under its two-minute period. */
export const UPSTREAM_TIMEOUT_MS = 30_000;

/** The calendar date in London at [at], as YYYY-MM-DD: the day National Rail's incidents are for. */
export function londonDate(at) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/London", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(at));
}

/**
 * The cron job: today's incidents fetched and stored. One KV write per run for the list, so a run every two minutes stays inside the free plan's
 * writes. A failed fetch keeps the last good list, which ages out on its own. May log, as no caller is
 * involved: the upstream status, never a key.
 *
 * Runs can overlap (a scheduled run may last 15 minutes), so the fetch is cut off at [UPSTREAM_TIMEOUT_MS],
 * under the two-minute period, and a run that finds a later run's result stored writes nothing: an older
 * failure never replaces a newer list.
 */
export async function refresh(env, at = Date.now(), fetcher = fetch) {
  const today = londonDate(at);
  let status;
  let body;
  try {
    const response = await fetcher(UPSTREAM, {
      method: "POST",
      headers: { "x-apikey": env.DISRUPTIONS_API_KEY, "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ startDate: today, endDate: today }),
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    status = response.status;
    if (response.ok) {
      const text = await response.text();
      // Only a list is stored: an error object in a 200 would otherwise pass for "no incidents".
      if (Array.isArray(JSON.parse(text))) body = text;
      else status = "not a list";
    }
  } catch (e) {
    status = e?.name ?? "error";
    // The type and where it was thrown, never the message, which could quote what it was given.
    console.error(`incidents refresh failed: ${status}\n${stackFrames(e)}`);
  }
  console.log(`incidents refresh: ${status}`);
  // Read after the fetch, not before it, so what a slower overlapping run stored is seen.
  const held = (await env.RAIL.get(INCIDENTS, { type: "json" })) ?? {};
  if ((held.lastAttemptAt ?? -Infinity) > at) return held;
  const next = body
    ? { body, fetchedAt: at, lastAttemptAt: at, lastStatus: status }
    : { ...held, lastAttemptAt: at, lastStatus: status };
  await env.RAIL.put(INCIDENTS, JSON.stringify(next));
  return next;
}

/** [e]'s stack frames alone: V8's stack starts with "Name: message", and the message is dropped. */
export function stackFrames(e) {
  return String(e?.stack ?? "").split("\n").filter((line) => line.trimStart().startsWith("at ")).join("\n");
}

function error(status, message) {
  return Response.json({ error: message }, { status });
}
