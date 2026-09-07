/**
 * Runnable check for the only non-trivial logic here: the paging loop.
 *
 * The API caps a page at 200, so `limit: 350` has to make two calls — and the
 * loop must stop when the pool runs out rather than spinning forever asking for
 * page 4, 5, 6 of a list that ended at page 2. Stdlib runner, no network.
 *
 *   node --test
 */
import test from "node:test";
import assert from "node:assert/strict";
import { fetchProxies, ProxmintError } from "./index.js";

const row = (n) => ({ ip: `1.2.3.${n % 256}`, port: 8080, protocol: "http", uptimePct: 99 });

/** Stub fetch with a pool of `total` rows; records every URL requested. */
function stubPool(total, { status = 200 } = {}) {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(new URL(url));
    if (status !== 200) {
      return { ok: false, status, statusText: "stubbed", json: async () => ({}) };
    }
    const u = new URL(url);
    const page = Number(u.searchParams.get("page"));
    const size = Number(u.searchParams.get("pageSize"));
    const start = (page - 1) * size;
    const rows = Array.from({ length: Math.max(0, Math.min(size, total - start)) }, (_, i) => row(start + i));
    return { ok: true, status: 200, json: async () => ({ rows, total, page, pageSize: size }) };
  };
  return calls;
}

const realFetch = globalThis.fetch;
test.after(() => {
  globalThis.fetch = realFetch;
});

test("pages past the API's 200-row cap", async () => {
  const calls = stubPool(1000);
  const rows = await fetchProxies({ limit: 350 });
  assert.equal(rows.length, 350);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].searchParams.get("pageSize"), "200");
  assert.equal(calls[1].searchParams.get("pageSize"), "150", "second page asks only for the remainder");
});

test("stops at the end of the pool instead of looping forever", async () => {
  const calls = stubPool(37);
  const rows = await fetchProxies({ limit: 500 });
  assert.equal(rows.length, 37);
  assert.ok(calls.length <= 2, `expected to stop quickly, made ${calls.length} requests`);
});

test("never returns more than the requested limit", async () => {
  stubPool(1000);
  assert.equal((await fetchProxies({ limit: 1 })).length, 1);
  assert.equal((await fetchProxies({ limit: 7 })).length, 7);
});

test("single page needs a single request", async () => {
  const calls = stubPool(1000);
  await fetchProxies({ limit: 20 });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].searchParams.get("pageSize"), "20");
});

test("builds filters, lowercasing the country code", async () => {
  const calls = stubPool(50);
  await fetchProxies({ protocol: "socks5", country: "DE", anonymity: "elite", sort: "latency", limit: 5 });
  const q = calls[0].searchParams;
  assert.equal(q.get("protocol"), "socks5");
  assert.equal(q.get("country"), "de");
  assert.equal(q.get("anonymity"), "elite");
  assert.equal(q.get("sort"), "latency");
});

test("omits filters that were not given", async () => {
  const calls = stubPool(50);
  await fetchProxies({ limit: 5 });
  const q = calls[0].searchParams;
  assert.equal(q.has("protocol"), false);
  assert.equal(q.has("country"), false);
});

test("rate limiting says so, and carries the status", async () => {
  stubPool(0, { status: 429 });
  await assert.rejects(fetchProxies(), (err) => {
    assert.ok(err instanceof ProxmintError);
    assert.equal(err.status, 429);
    assert.match(err.message, /60 requests\/minute/);
    return true;
  });
});

test("other HTTP failures surface the status", async () => {
  stubPool(0, { status: 503 });
  await assert.rejects(fetchProxies(), (err) => err instanceof ProxmintError && err.status === 503);
});

test("rejects a nonsense limit before making a request", async () => {
  const calls = stubPool(50);
  await assert.rejects(fetchProxies({ limit: 0 }), TypeError);
  await assert.rejects(fetchProxies({ limit: 2.5 }), TypeError);
  assert.equal(calls.length, 0, "must not hit the network to reject bad input");
});
