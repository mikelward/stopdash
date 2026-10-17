import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { handle, refresh, londonDate, dayBefore, callerKey, MAX_AGE_MS, BOARD_SECONDS, stackFrames } from "../src/index.js";

// A KV namespace in memory: get with a type, put, delete; options it's given are recorded.
function kv() {
  const store = new Map();
  return {
    store,
    async get(key, options) {
      const value = store.get(key);
      if (value === undefined) return null;
      const type = typeof options === "string" ? options : options?.type;
      return type === "json" ? JSON.parse(value) : value;
    },
    async put(key, value) {
      store.set(key, value);
    },
    async delete(key) {
      store.delete(key);
    },
  };
}

const NOW = Date.parse("2026-10-17T12:00:00Z");
const LIST = JSON.stringify([{ summary: "Disruption at London Euston", disruptionLinks: [{ uri: "https://www.nationalrail.co.uk/service-disruptions/x/" }] }]);

let logged;
let original;
beforeEach(() => {
  logged = [];
  original = { log: console.log, error: console.error, warn: console.warn, info: console.info, debug: console.debug };
  for (const name of Object.keys(original)) console[name] = (...args) => logged.push([name, ...args]);
});
afterEach(() => Object.assign(console, original));

function env(overrides = {}) {
  return { RAIL: kv(), DISRUPTIONS_API_KEY: "EXAMPLE", ...overrides };
}

function upstream(status, body) {
  const calls = [];
  const fetcher = async (url, init) => {
    calls.push({ url, init });
    return new Response(body, { status });
  };
  return { calls, fetcher };
}

test("the cron job stores today's list, asked with the key and the London date", async () => {
  const e = env();
  const { calls, fetcher } = upstream(200, LIST);
  await refresh(e, NOW, fetcher);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.headers["x-apikey"], "EXAMPLE");
  assert.deepEqual(JSON.parse(calls[0].init.body), { startDate: "2026-10-17", endDate: "2026-10-17" });
  const held = JSON.parse(e.RAIL.store.get("incidents"));
  assert.equal(held.body, LIST);
  assert.equal(held.fetchedAt, NOW);
});

test("a failed or malformed fetch keeps the last good list", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  await refresh(e, NOW + 120_000, upstream(503, "{}").fetcher);
  await refresh(e, NOW + 240_000, upstream(200, '{"errors": []}').fetcher);
  await refresh(e, NOW + 360_000, async () => {
    throw new TypeError("network");
  });
  const held = JSON.parse(e.RAIL.store.get("incidents"));
  assert.equal(held.body, LIST);
  assert.equal(held.fetchedAt, NOW);
  assert.equal(held.lastStatus, "TypeError");
});

test("a failed refresh logs the error's type and stack, never its message", async () => {
  const e = env();
  await refresh(e, NOW, async () => {
    throw new TypeError("fetch failed for https://example.com/?key=EXAMPLE");
  });
  const errors = logged.filter(([level]) => level === "error").map(([, text]) => text);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /^incidents refresh failed: TypeError\n\s*at /);
  assert.doesNotMatch(errors[0], /EXAMPLE|fetch failed/);
});

test("stack frames keep only the frames", () => {
  const error = new Error("secret");
  const frames = stackFrames(error);
  assert.ok(frames.length > 0);
  assert.ok(frames.split("\n").every((line) => line.trimStart().startsWith("at ")));
  assert.doesNotMatch(frames, /secret/);
});

test("an older run that ends after a newer one leaves the newer list", async () => {
  const e = env();
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  // The older run's fetch hangs past the next tick, then fails.
  const older = refresh(e, NOW, async () => {
    await gate;
    throw new TypeError("network");
  });
  await refresh(e, NOW + 120_000, upstream(200, LIST).fetcher);
  release();
  await older;
  const held = JSON.parse(e.RAIL.store.get("incidents"));
  assert.equal(held.body, LIST);
  assert.equal(held.fetchedAt, NOW + 120_000);
  assert.equal(held.lastStatus, 200);
});

test("the upstream fetch is cut off before the next run", async () => {
  const e = env();
  const { calls, fetcher } = upstream(200, LIST);
  await refresh(e, NOW, fetcher);
  assert.ok(calls[0].init.signal instanceof AbortSignal);
});

test("a request gets the stored list with its fetch time, and logs nothing", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  logged = [];
  const response = await handle(new Request("https://relay.example/v1/incidents", { headers: { "cf-connecting-ip": "192.0.2.1" } }), e, { now: NOW + 60_000 });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), LIST);
  assert.equal(response.headers.get("x-fetched-at"), new Date(NOW).toISOString());
  assert.equal(response.headers.get("age"), "60");
  assert.deepEqual(logged, []);
});

test("a list past five minutes old isn't served", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  const response = await handle(new Request("https://relay.example/v1/incidents"), e, { now: NOW + MAX_AGE_MS + 1 });
  assert.equal(response.status, 503);
  assert.equal((await handle(new Request("https://relay.example/v1/incidents"), env(), { now: NOW })).status, 503);
});

test("anything but a bare GET of a known path is refused, without echoing it, and logs nothing", async () => {
  const e = env();
  const cases = [
    [new Request("https://relay.example/v1/incidents?station=EUS"), 400],
    [new Request("https://relay.example/v1/incidents", { method: "POST", body: "x" }), 405],
    [new Request("https://relay.example/v1/departures/EUS"), 404],
  ];
  for (const [request, status] of cases) {
    const response = await handle(request, e, { now: NOW });
    assert.equal(response.status, status);
    assert.ok(!(await response.text()).includes("EUS"));
  }
  assert.deepEqual(logged, []);
});

test("London's date, not UTC's, names the day", () => {
  assert.equal(londonDate(Date.parse("2026-07-01T23:30:00Z")), "2026-07-02");
  assert.equal(londonDate(Date.parse("2026-12-01T23:30:00Z")), "2026-12-01");
});

test("health says when the list was fetched, and nothing about callers", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  const body = await (await handle(new Request("https://relay.example/v1/health"), e, { now: NOW })).json();
  assert.deepEqual(body, { incidents: { fetchedAt: new Date(NOW).toISOString(), current: true, lastAttemptAt: new Date(NOW).toISOString(), lastStatus: 200, lastFailure: null } });
});

test("the cron job's last failure is kept for health, as its type and frames alone", async () => {
  const e = env();
  await refresh(e, NOW, async () => {
    throw new TypeError("fetch failed for https://example.com/?key=EXAMPLE");
  });
  // A later success keeps the failure on record, with its own time, beside the fresh list.
  await refresh(e, NOW + 120_000, upstream(200, LIST).fetcher);
  const response = await handle(new Request("https://relay.example/v1/health"), e, { now: NOW + 120_000 });
  const failure = (await response.clone().json()).incidents.lastFailure;
  assert.equal(failure.at, new Date(NOW).toISOString());
  assert.equal(failure.type, "TypeError");
  assert.match(failure.frames, /^\s*at /);
  assert.doesNotMatch(await response.text(), /EXAMPLE|fetch failed/);
});

// The Cache API in memory: a response put under a key is matched back by it.
function edgeCache() {
  const store = new Map();
  return {
    store,
    async match(key) {
      const held = store.get(key);
      return held ? new Response(held.body, { headers: held.headers }) : undefined;
    },
    async put(key, response) {
      store.set(key, { body: await response.text(), headers: Object.fromEntries(response.headers) });
    },
  };
}

const BOARD = JSON.stringify({ generatedAt: "2026-10-17T13:00:00+01:00", locationName: "London Euston", crs: "EUS", trainServices: [] });

function boards(status = 200, body = BOARD) {
  const calls = [];
  const fetcher = async (url, init) => {
    calls.push({ url, init });
    return new Response(body, { status });
  };
  return { calls, fetcher };
}

function limiter(allow = () => true) {
  const keys = [];
  return { keys, limit: async ({ key }) => (keys.push(key), { success: allow(keys.length) }) };
}

async function seeded(overrides = {}) {
  const e = env({ LDB_API_KEY: "EXAMPLE", ...overrides });
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  logged = [];
  return e;
}

const ask = (path, ip = "192.0.2.1") => new Request(`https://relay.example${path}`, { headers: { "cf-connecting-ip": ip } });

test("a board is fetched once with the relay's key, then served from the cache for 30 s", async () => {
  const e = await seeded();
  const cache = edgeCache();
  const { calls, fetcher } = boards();
  const first = await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher });
  assert.equal(first.status, 200);
  assert.equal(await first.text(), BOARD);
  assert.equal(calls.length, 1);
  const upstreamUrl = new URL(calls[0].url);
  assert.equal(upstreamUrl.pathname.endsWith("/GetDepartureBoard/EUS"), true);
  assert.equal(upstreamUrl.searchParams.get("numRows"), "20");
  assert.deepEqual(calls[0].init.headers, { "x-apikey": "EXAMPLE", accept: "application/json" });
  const second = await handle(ask("/v1/board/EUS", "198.51.100.7"), e, { now: NOW + 29_000, cache, fetcher });
  assert.equal(second.headers.get("x-fetched-at"), new Date(NOW).toISOString());
  assert.equal(second.headers.get("age"), "29");
  assert.equal(calls.length, 1);
  await handle(ask("/v1/board/EUS"), e, { now: NOW + BOARD_SECONDS * 1000, cache, fetcher });
  assert.equal(calls.length, 2);
  assert.deepEqual(logged, []);
});

test("a followed train's board is asked with its filter and offset", async () => {
  const e = await seeded();
  const { calls, fetcher } = boards();
  await handle(ask("/v1/board/EUS/details?to=MKC&offset=30"), e, { now: NOW, cache: edgeCache(), fetcher });
  const upstreamUrl = new URL(calls[0].url);
  assert.equal(upstreamUrl.pathname.endsWith("/GetDepBoardWithDetails/EUS"), true);
  assert.deepEqual(Object.fromEntries(upstreamUrl.searchParams), { numRows: "10", filterCrs: "MKC", filterType: "to", timeOffset: "30" });
});

test("only cold board requests count against a caller, under today's HMAC of the IP", async () => {
  const cold = limiter((n) => n <= 1);
  const e = await seeded({ COLD_PER_CALLER: cold });
  const cache = edgeCache();
  const { fetcher } = boards();
  assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher })).status, 200);
  // Cached: served, and not counted.
  for (let i = 0; i < 5; i++) assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW + 1000, cache, fetcher })).status, 200);
  assert.equal(cold.keys.length, 1);
  // Cold, past the limit: refused.
  assert.equal((await handle(ask("/v1/board/KGX"), e, { now: NOW + 1000, cache, fetcher })).status, 429);
  // A cached board is still served to that caller.
  assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW + 2000, cache, fetcher })).status, 200);
  const secret = e.RAIL.store.get(`caller-secret:${londonDate(NOW)}`);
  assert.equal(cold.keys[0], await callerKey(secret, "192.0.2.1"));
  assert.match(cold.keys[0], /^[0-9a-f]{24}$/);
  assert.deepEqual(logged, []);
});

test("callers missing the same board together make one request, counted once", async () => {
  const cold = limiter();
  const e = await seeded({ COLD_PER_CALLER: cold });
  let release;
  const gate = new Promise((resolve) => (release = resolve));
  let asked = 0;
  const fetcher = async () => (asked++, await gate, new Response(BOARD));
  const first = handle(ask("/v1/board/EUS"), e, { now: NOW, cache: edgeCache(), fetcher });
  const second = handle(ask("/v1/board/EUS", "198.51.100.7"), e, { now: NOW, cache: edgeCache(), fetcher });
  await new Promise((resolve) => setImmediate(resolve));
  release();
  assert.equal((await first).status, 200);
  assert.equal((await second).status, 200);
  assert.equal(asked, 1);
  assert.equal(cold.keys.length, 1);
});

test("the relay's own budget caps what reaches National Rail", async () => {
  const e = await seeded({ UPSTREAM_BUDGET: limiter(() => false) });
  const { calls, fetcher } = boards();
  assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW, cache: edgeCache(), fetcher })).status, 503);
  assert.equal(calls.length, 0);
});

test("a limiter that fails lets a caller through, but a budget that fails asks nothing", async () => {
  const broken = { limit: async () => { throw new TypeError("binding down"); } };
  // The per-caller count down: the board is still served, under the relay's own budget.
  const open = await seeded({ COLD_PER_CALLER: broken, UPSTREAM_BUDGET: limiter() });
  const first = boards();
  assert.equal((await handle(ask("/v1/board/EUS"), open, { now: NOW, cache: edgeCache(), fetcher: first.fetcher })).status, 200);
  assert.equal(first.calls.length, 1);
  // The budget down: nothing bounds the key's use, so the answer is busy and nothing is asked.
  const closed = await seeded({ UPSTREAM_BUDGET: broken });
  const second = boards();
  const busy = await handle(ask("/v1/board/EUS"), closed, { now: NOW, cache: edgeCache(), fetcher: second.fetcher });
  assert.equal(busy.status, 503);
  assert.equal(second.calls.length, 0);
  assert.deepEqual(logged, []);
});

test("National Rail's failures are told as the app reads them, and never cached", async () => {
  const e = await seeded();
  const cache = edgeCache();
  assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher: boards(429).fetcher })).status, 503);
  assert.equal((await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher: boards(500).fetcher })).status, 502);
  const down = async () => {
    throw new TypeError("network failed for https://example.com/?x=EXAMPLE");
  };
  const unreachable = await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher: down });
  assert.equal(unreachable.status, 502);
  // The error's type says what failed; its message, which could quote the request, never goes back.
  const said = await unreachable.text();
  assert.match(said, /national rail unreachable: TypeError/);
  assert.doesNotMatch(said, /EXAMPLE|network failed/);
  // A cache that can't be read or written is a miss, and still answers the good board.
  const broken = { match: async () => { throw new TypeError("cache down"); }, put: async () => { throw new TypeError("cache down"); } };
  const fresh = await handle(ask("/v1/board/EUS"), e, { now: NOW, cache: broken, fetcher: boards().fetcher });
  assert.equal(fresh.status, 200);
  assert.equal(await fresh.text(), BOARD);
  // A 200 that isn't a board (empty, an error object, not JSON) is a failure too, never "no trains".
  for (const body of ["", JSON.stringify({ error: "quota" }), "<html>", "[]", "null"]) {
    const odd = await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher: boards(200, body).fetcher });
    assert.equal(odd.status, 502, `body ${JSON.stringify(body)}`);
    assert.match(await odd.text(), /national rail unreachable: NotABoard/);
  }
  assert.equal(cache.store.size, 0);
  // Headers, then the connection dropped mid-body: the same 502, not an unhandled failure.
  const cut = async () => new Response(new ReadableStream({ pull(c) { c.error(new TypeError("reset by https://example.com/?x=EXAMPLE")); } }));
  const dropped = await handle(ask("/v1/board/EUS"), e, { now: NOW, cache, fetcher: cut });
  assert.equal(dropped.status, 502);
  assert.doesNotMatch(await dropped.text(), /EXAMPLE|reset by/);
  assert.equal(cache.store.size, 0);
  assert.deepEqual(logged, []);
});

test("a board's upstream request is cut off rather than left to stall its callers", async () => {
  const e = await seeded();
  const { calls, fetcher } = boards();
  await handle(ask("/v1/board/EUS"), e, { now: NOW, cache: edgeCache(), fetcher });
  assert.ok(calls[0].init.signal instanceof AbortSignal);
});

test("a board request outside its shape is refused before the cache or National Rail", async () => {
  const e = await seeded();
  const { calls, fetcher } = boards();
  const cases = [
    ["/v1/board/eus", 404],
    ["/v1/board/EUSX", 404],
    ["/v1/board/EUS?to=MKC", 400],
    ["/v1/board/EUS/details?offset=30", 400],
    ["/v1/board/EUS/details?to=MKC&offset=7", 400],
    ["/v1/board/EUS/details?to=MKC&offset=120", 400],
    ["/v1/board/EUS/details?to=mkc", 400],
    ["/v1/board/EUS/details?to=MKC&to=WFJ", 400],
    ["/v1/board/EUS/details?to=MKC&station=EUS", 400],
  ];
  for (const [path, status] of cases) {
    const response = await handle(ask(path), e, { now: NOW, cache: edgeCache(), fetcher });
    assert.equal(response.status, status, path);
    assert.ok(!(await response.text()).includes("MKC"));
  }
  assert.equal(calls.length, 0);
  assert.deepEqual(logged, []);
});

test("a new London day makes a new secret and deletes yesterday's", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  const first = e.RAIL.store.get("caller-secret:2026-10-17");
  assert.ok(first);
  await refresh(e, NOW + 60_000, upstream(200, LIST).fetcher);
  assert.equal(e.RAIL.store.get("caller-secret:2026-10-17"), first);
  await refresh(e, Date.parse("2026-10-18T00:01:00+01:00"), upstream(200, LIST).fetcher);
  assert.equal(e.RAIL.store.get("caller-secret:2026-10-17"), undefined);
  const next = e.RAIL.store.get("caller-secret:2026-10-18");
  assert.notEqual(next, first);
  assert.notEqual(await callerKey(first, "192.0.2.1"), await callerKey(next, "192.0.2.1"));
});

test("yesterday is the calendar day before, across the clocks going forward and month ends", async () => {
  assert.equal(dayBefore("2026-03-30"), "2026-03-29");
  assert.equal(dayBefore("2026-03-01"), "2026-02-28");
  assert.equal(dayBefore("2026-01-01"), "2025-12-31");
  // The first run after midnight following the spring change deletes the day before's secret, not
  // the one 24 hours earlier, which is two calendar days back (Codex, #944).
  const e = env();
  const before = Date.parse("2026-03-29T12:00:00+01:00");
  await refresh(e, before, upstream(200, LIST).fetcher);
  assert.ok(e.RAIL.store.get("caller-secret:2026-03-29"));
  await refresh(e, Date.parse("2026-03-30T00:01:00+01:00"), upstream(200, LIST).fetcher);
  assert.equal(e.RAIL.store.get("caller-secret:2026-03-29"), undefined);
});

test("yesterday's secret is deleted on a later run if the first try failed", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  const remove = e.RAIL.delete;
  e.RAIL.delete = async () => {
    throw new Error("kv unavailable");
  };
  const tomorrow = Date.parse("2026-10-18T00:01:00+01:00");
  // The incident list is still refreshed, and the failure said by type and frames alone.
  const { calls, fetcher } = upstream(200, LIST);
  await refresh(e, tomorrow, fetcher);
  assert.equal(calls.length, 1);
  assert.equal(JSON.parse(e.RAIL.store.get("incidents")).fetchedAt, tomorrow);
  assert.ok(e.RAIL.store.get("caller-secret:2026-10-17"));
  assert.ok(logged.some(([level, text]) => level === "error" && /^caller secret rotation failed: Error\n/.test(text) && !/kv unavailable/.test(text)));
  e.RAIL.delete = remove;
  await refresh(e, tomorrow + 120_000, upstream(200, LIST).fetcher);
  assert.equal(e.RAIL.store.get("caller-secret:2026-10-17"), undefined);
});
