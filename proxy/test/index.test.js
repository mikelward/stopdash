import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { handle, refresh, londonDate, MAX_AGE_MS, stackFrames } from "../src/index.js";

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
  const response = await handle(new Request("https://relay.example/v1/incidents", { headers: { "cf-connecting-ip": "192.0.2.1" } }), e, NOW + 60_000);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), LIST);
  assert.equal(response.headers.get("x-fetched-at"), new Date(NOW).toISOString());
  assert.equal(response.headers.get("age"), "60");
  assert.deepEqual(logged, []);
});

test("a list past five minutes old isn't served", async () => {
  const e = env();
  await refresh(e, NOW, upstream(200, LIST).fetcher);
  const response = await handle(new Request("https://relay.example/v1/incidents"), e, NOW + MAX_AGE_MS + 1);
  assert.equal(response.status, 503);
  assert.equal((await handle(new Request("https://relay.example/v1/incidents"), env(), NOW)).status, 503);
});

test("anything but a bare GET of a known path is refused, without echoing it, and logs nothing", async () => {
  const e = env();
  const cases = [
    [new Request("https://relay.example/v1/incidents?station=EUS"), 400],
    [new Request("https://relay.example/v1/incidents", { method: "POST", body: "x" }), 405],
    [new Request("https://relay.example/v1/board/EUS"), 404],
  ];
  for (const [request, status] of cases) {
    const response = await handle(request, e, NOW);
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
  const body = await (await handle(new Request("https://relay.example/v1/health"), e, NOW)).json();
  assert.deepEqual(body, { incidents: { fetchedAt: new Date(NOW).toISOString(), current: true, lastAttemptAt: new Date(NOW).toISOString(), lastStatus: 200, lastFailure: null } });
});

test("the cron job's last failure is kept for health, as its type and frames alone", async () => {
  const e = env();
  await refresh(e, NOW, async () => {
    throw new TypeError("fetch failed for https://example.com/?key=EXAMPLE");
  });
  // A later success keeps the failure on record, with its own time, beside the fresh list.
  await refresh(e, NOW + 120_000, upstream(200, LIST).fetcher);
  const response = await handle(new Request("https://relay.example/v1/health"), e, NOW + 120_000);
  const failure = (await response.clone().json()).incidents.lastFailure;
  assert.equal(failure.at, new Date(NOW).toISOString());
  assert.equal(failure.type, "TypeError");
  assert.match(failure.frames, /^\s*at /);
  assert.doesNotMatch(await response.text(), /EXAMPLE|fetch failed/);
});
