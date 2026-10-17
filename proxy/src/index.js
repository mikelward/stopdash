// stopdash's National Rail relay (dev-docs/national-rail-proxy.md): National Rail's current incidents,
// fetched on a schedule, and its live departure boards, fetched as asked and cached, both with
// stopdash's own Rail Data Marketplace keys, so the app needs no key of its own. Nothing about a
// caller is logged or forwarded: no console output in request handling (the tests hold it to that),
// Workers Logs off in wrangler.toml, and upstream requests built fresh from the validated path. The
// only per-caller state is a one-minute count of a caller's cold board requests (those that reach
// National Rail) under a key the IP can't be recovered from (`callerKey`).

const UPSTREAM = "https://api1.raildata.org.uk/1010-disruptions-experience-api-11_1/disruptions/incidents/search";
const LDB = "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120";
const INCIDENTS = "incidents";
const SECRET_PREFIX = "caller-secret:";
// How long a board is served from the cache: no longer, so a board the app shows is at most this old
// on top of its own refresh, inside what it already treats as live.
export const BOARD_SECONDS = 30;
// Darwin's rows: the plain board's, about the next hour at a busy terminus; a board with calling
// points lists at most ten.
const ROWS = 20;
const DETAIL_ROWS = 10;
// Station codes and a train's terminus: three capitals, never anything else.
const CRS = /^[A-Z]{3}$/;
// Boards fetched now, by cache key: callers missing the same board share one upstream request.
const inFlight = new Map();
// The oldest list served, the app's own staleness bound (SPEC D4): past it the app falls back to
// TfL's link rather than show words National Rail may have changed or cleared. Two missed runs.
export const MAX_AGE_MS = 5 * 60 * 1000;
// How long an edge copy of the list is kept before KV is read again: as often as the app asks.
const EDGE_SECONDS = 60;

export default {
  fetch: (request, env) => handle(request, env, { cache: caches.default }),
  scheduled: (event, env, ctx) => ctx.waitUntil(refresh(env, event.scheduledTime)),
};

/** Answers one request from the stored list. Never logs, never forwards anything from it. */
export async function handle(request, env, { now = Date.now(), cache, fetcher = fetch } = {}) {
  if (request.method !== "GET") return error(405, "method not allowed");
  const url = new URL(request.url);
  if (url.pathname.startsWith("/v1/board/")) return board(url, request, env, now, cache, fetcher);
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
      // The cron job's last thrown failure: its type and stack frames, never its message.
      lastFailure: held.lastFailure
        ? { at: new Date(held.lastFailure.at).toISOString(), type: held.lastFailure.type, frames: held.lastFailure.frames }
        : null,
    },
  });
}

/** How long a board request waits for National Rail: under the app's own 10 s, so it hears a 502 first. */
export const BOARD_TIMEOUT_MS = 8_000;

/** How long the cron job waits for National Rail: well under its two-minute period. */
export const UPSTREAM_TIMEOUT_MS = 30_000;

/**
 * One station's board: `/v1/board/{crs}` (Darwin's GetDepartureBoard), or `/v1/board/{crs}/details`
 * (GetDepBoardWithDetails), optionally `?to={crs}` (trains calling there) and `&offset={minutes}` (from
 * that many on, in quarter hours within Darwin's -120 to 119). Anything else is refused before the cache or National Rail sees it,
 * which keeps the set of distinct boards small. Served from the cache while under [BOARD_SECONDS]
 * old; a miss is a cold request, counted against the caller's limit and the relay's own budget, and
 * shared with any caller already waiting on the same board. A cached board is never refused.
 */
async function board(url, request, env, now, cache, fetcher) {
  const match = /^\/v1\/board\/([^/]+)(\/details)?$/.exec(url.pathname);
  if (!match || !CRS.test(match[1])) return error(404, "not found");
  const crs = match[1];
  const details = Boolean(match[2]);
  const to = url.searchParams.get("to");
  const offset = url.searchParams.get("offset");
  for (const name of url.searchParams.keys()) {
    if (!details || (name !== "to" && name !== "offset")) return error(400, "unexpected query parameter");
  }
  if (url.searchParams.getAll("to").length > 1 || url.searchParams.getAll("offset").length > 1) return error(400, "repeated query parameter");
  if (to !== null && !CRS.test(to)) return error(400, "bad destination");
  if (offset !== null && (to === null || !/^-?\d+$/.test(offset) || Number(offset) % 15 !== 0 || Number(offset) < -120 || Number(offset) > 105)) {
    return error(400, "bad offset");
  }
  const operation = details ? "GetDepBoardWithDetails" : "GetDepartureBoard";
  const upstream = new URL(`${LDB}/${operation}/${crs}`);
  upstream.searchParams.set("numRows", String(details ? DETAIL_ROWS : ROWS));
  if (to !== null) {
    upstream.searchParams.set("filterCrs", to);
    upstream.searchParams.set("filterType", "to");
  }
  if (offset !== null && Number(offset) !== 0) upstream.searchParams.set("timeOffset", String(Number(offset)));
  // Keyed by the upstream request, built from the validated parts alone: nothing from the caller.
  const key = `https://board.cache.invalid/${upstream.pathname.split("/").slice(-2).join("/")}${upstream.search}`;

  try {
    const cached = await cache?.match(key);
    if (cached) {
      const fetchedAt = Date.parse(cached.headers.get("x-fetched-at"));
      if (now - fetchedAt < BOARD_SECONDS * 1000) return boardResponse(await cached.text(), fetchedAt, now);
    }
  } catch {
    // A cache that can't be read is a miss: the board is asked for as if none were held (Codex,
    // #944). Not logged, as nothing in request handling is.
  }
  // Claimed before anything else is awaited, so a caller missing the same board meanwhile joins this
  // request rather than making its own; only the caller that makes it is counted.
  const joined = inFlight.get(key);
  const pending = joined ?? (async () => {
    let allowed;
    try {
      allowed = await coldAllowed(request, env, now);
    } catch {
      // The per-caller count couldn't be read or kept: let it through, the relay's own budget still
      // bounds what reaches National Rail (Codex, #944). Not logged, as nothing in request handling is.
      allowed = true;
    }
    if (!allowed) return { status: 429, message: "too many requests", limited: true };
    let budget;
    try {
      budget = !env.UPSTREAM_BUDGET || (await env.UPSTREAM_BUDGET.limit({ key: "boards" })).success;
    } catch {
      // Without the budget nothing bounds the upstream key's use, so this one isn't asked: busy, as when
      // the budget is spent.
      budget = false;
    }
    if (!budget) return { status: 503, message: "busy" };
    return fetchBoard(upstream, env, now, cache, key, fetcher);
  })().finally(() => inFlight.delete(key));
  if (!joined) inFlight.set(key, pending);
  const answer = await pending;
  // Another caller's limit is no reason to refuse this one: it asks on its own.
  if (joined && answer.limited) return board(url, request, env, now, cache, fetcher);
  if (answer.body === undefined) return error(answer.status, answer.message);
  return boardResponse(answer.body, answer.fetchedAt, now);
}

// One board from National Rail, cached when it answered: {body, fetchedAt}, else {status, message}.
async function fetchBoard(upstream, env, now, cache, key, fetcher) {
  let response;
  let body;
  try {
    // Bounded, so a stalled National Rail can't hold every caller of this board on one request.
    response = await fetcher(upstream.toString(), {
      headers: { "x-apikey": env.LDB_API_KEY, accept: "application/json" },
      signal: AbortSignal.timeout(BOARD_TIMEOUT_MS),
    });
    // Read here too: a connection dropped mid-body is the transport failing, the same 502.
    if (response.ok) body = await response.text();
  } catch (e) {
    // The transport failed: the app reads a 502 as National Rail unreachable, as it would directly.
    // Nothing is logged, as nothing is in request handling (observability is off, and rule 1 of the
    // design): the error's type alone goes back in the answer, never its message, which could quote
    // the request, so the app's own log shows what failed.
    return { status: 502, message: `national rail unreachable: ${e?.name ?? "error"}` };
  }
  if (response.status === 429) return { status: 503, message: "busy" };
  if (!response.ok) return { status: 502, message: `national rail answered ${response.status}` };
  // Only a board is cached and passed on: an empty body or an error object in a 200 would otherwise be
  // read by the app as a live board with no trains (Codex, #944).
  if (!isBoard(body)) return { status: 502, message: "national rail unreachable: NotABoard" };
  try {
    await cache?.put(key, new Response(body, {
      headers: { "content-type": "application/json", "x-fetched-at": new Date(now).toISOString(), "cache-control": `max-age=${BOARD_SECONDS}` },
    }));
  } catch {
    // The cache only saves a request: the board is still good, so it's answered (Codex, #944). Not
    // logged, as nothing in request handling is; the next caller's miss is the only sign.
  }
  return { body, fetchedAt: now };
}

/** Whether [body] is a departure board: a JSON object naming its station. */
function isBoard(body) {
  try {
    const board = JSON.parse(body);
    return board !== null && typeof board === "object" && !Array.isArray(board) &&
      (typeof board.crs === "string" || typeof board.locationName === "string");
  } catch {
    // Not JSON at all, which is no board either: told as one in the 502.
    return false;
  }
}

function boardResponse(body, fetchedAt, now) {
  return new Response(body, {
    headers: {
      "content-type": "application/json",
      "x-fetched-at": new Date(fetchedAt).toISOString(),
      // How old the board is, counted here, for the app to date it by rather than by clocks.
      age: String(Math.max(0, Math.floor((now - fetchedAt) / 1000))),
      "cache-control": `public, max-age=${Math.max(0, BOARD_SECONDS - Math.floor((now - fetchedAt) / 1000))}`,
    },
  });
}

/**
 * Whether this caller may make another cold board request this minute. Keyed by `callerKey`, never the
 * IP. With no limiter bound, or no secret yet for today, it's let through: the relay's own budget
 * still caps what reaches National Rail.
 */
async function coldAllowed(request, env, now) {
  const ip = request.headers.get("cf-connecting-ip");
  if (!env.COLD_PER_CALLER || !ip) return true;
  const secret = await env.RAIL.get(SECRET_PREFIX + londonDate(now), { cacheTtl: 60 });
  if (!secret) return true;
  const { success } = await env.COLD_PER_CALLER.limit({ key: await callerKey(secret, ip) });
  return success;
}

/**
 * The key a caller's count is kept under: an HMAC of its IP with today's secret, truncated. The secret
 * is random, made each London day and deleted the next, so a key can't be turned back into an IP,
 * matched against one without the secret, or linked to the same caller another day.
 */
export async function callerKey(secret, ip) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(ip)));
  return [...mac.slice(0, 12)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The calendar date in London at [at], as YYYY-MM-DD: the day National Rail's incidents are for. */
export function londonDate(at) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/London", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(at));
}

/** The calendar day before [date] (YYYY-MM-DD), as YYYY-MM-DD: not 24 hours before, which skips a day
 * after the clocks go forward (Codex, #944). */
export function dayBefore(date) {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}

/**
 * The cron job: today's caller secret made (yesterday's deleted), and today's incidents fetched and
 * stored. One KV write per run for the list, so a run every two minutes stays inside the free plan's
 * writes. A failed fetch keeps the last good list, which ages out on its own. May log, as no caller is
 * involved: the upstream status, never a key.
 *
 * Runs can overlap (a scheduled run may last 15 minutes), so the fetch is cut off at [UPSTREAM_TIMEOUT_MS],
 * under the two-minute period, and a run that finds a later run's result stored writes nothing: an older
 * failure never replaces a newer list.
 */
export async function refresh(env, at = Date.now(), fetcher = fetch) {
  const today = londonDate(at);
  try {
    if (!(await env.RAIL.get(SECRET_PREFIX + today))) {
      const bytes = crypto.getRandomValues(new Uint8Array(32));
      await env.RAIL.put(SECRET_PREFIX + today, btoa(String.fromCharCode(...bytes)), { expirationTtl: 2 * 24 * 60 * 60 });
    }
    // Checked every run, not only when today's is made, so a delete that failed is tried again (Codex,
    // #944). Read first: a delete is a write, and one each run would outrun the free plan's writes.
    const yesterday = SECRET_PREFIX + dayBefore(today);
    if (await env.RAIL.get(yesterday)) await env.RAIL.delete(yesterday);
  } catch (e) {
    // Rotation is retried next run; the incident list goes on regardless (Codex, #944). With no
    // secret for today, cold board requests go uncounted per caller, under the relay's own budget.
    console.error(`caller secret rotation failed: ${e?.name ?? "error"}\n${stackFrames(e)}`);
  }
  let status;
  let body;
  let failure;
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
    // The type and where it was thrown, never the message, which could quote what it was given. Kept
    // in KV too, for /v1/health, since Workers Logs stays off so that no request is ever recorded.
    failure = { at, type: status, frames: stackFrames(e) };
    console.error(`incidents refresh failed: ${status}\n${failure.frames}`);
  }
  console.log(`incidents refresh: ${status}`);
  // Read after the fetch, not before it, so what a slower overlapping run stored is seen.
  const held = (await env.RAIL.get(INCIDENTS, { type: "json" })) ?? {};
  if ((held.lastAttemptAt ?? -Infinity) > at) return held;
  const next = body
    ? { body, fetchedAt: at, lastAttemptAt: at, lastStatus: status, lastFailure: held.lastFailure }
    : { ...held, lastAttemptAt: at, lastStatus: status, ...(failure ? { lastFailure: failure } : {}) };
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
