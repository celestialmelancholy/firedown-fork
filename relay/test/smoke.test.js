// Smoke test for the relay Worker (node --test, no Cloudflare needed).
// Verifies the contract: upload → URL → fetch-back → delete, plus cap + TTL.
import { test } from "node:test";
import assert from "node:assert/strict";

// In-memory fake of the R2 binding + KV counter so the Worker runs standalone.
function fakeR2() {
  const map = new Map();
  return {
    map,
    async put(key, bytes, meta) {
      map.set(key, { bytes, meta });
    },
    async get(key) {
      const o = map.get(key);
      return o ? { body: o.bytes, httpMetadata: o.meta?.httpMetadata } : null;
    },
    async head(key) {
      const o = map.get(key);
      return o ? { size: o.bytes.byteLength, customMetadata: o.meta?.customMetadata } : null;
    },
    async delete(key) {
      map.delete(key);
    },
    async list({ prefix, cursor } = {}) {
      const keys = [...map.keys()].filter((k) => k.startsWith(prefix || ""));
      const objects = keys.map((k) => ({ key: k, size: map.get(k).bytes.byteLength }));
      return { objects, truncated: false, cursor: undefined };
    },
  };
}

function fakeKV() {
  const map = new Map();
  return {
    map,
    async get(key) {
      return map.get(key) ?? null;
    },
    async put(key, val) {
      map.set(key, String(val));
    },
  };
}

// Build a multipart body like the app sends.
function multipart(bytes, fields = {}, filename = "image.png", mime = "image/png") {
  const boundary = "----test" + Math.random().toString(36).slice(2);
  const chunks = [];
  const push = (s) => chunks.push(Buffer.from(s));
  push(`--${boundary}\r\n`);
  push(`Content-Disposition: form-data; name="fileToUpload"; filename="${filename}"\r\n`);
  push(`Content-Type: ${mime}\r\n\r\n`);
  chunks.push(Buffer.from(bytes));
  push("\r\n");
  for (const [name, value] of Object.entries(fields)) {
    push(`--${boundary}\r\n`);
    push(`Content-Disposition: form-data; name="${name}"\r\n\r\n`);
    push(value);
    push("\r\n");
  }
  push(`--${boundary}--\r\n`);
  const body = Buffer.concat(chunks);
  return { body, headers: { "content-type": `multipart/form-data; boundary=${boundary}` } };
}

const mod = await import("../src/index.js");

function makeCtx(r2, kv) {
  const env = { IMAGES: r2, USED: kv };
  return { fetch: (req) => mod.default.fetch(req, env) };
}

test("upload → url → fetch-back → delete round trip", async () => {
  const r2 = fakeR2();
  const kv = fakeKV();
  const ctx = makeCtx(r2, kv);
  const img = Buffer.from("fake-png-bytes");
  const { body, headers } = multipart(img);
  const up = await ctx.fetch(
    new Request("https://relay.test/upload", { method: "POST", body, headers }),
  );
  assert.equal(up.status, 200);
  const url = (await up.text()).trim();
  assert.match(url, /^https:\/\/relay\.test\/i\/[0-9a-f]{24}$/);

  const got = await ctx.fetch(new Request(url));
  assert.equal(got.status, 200);
  assert.equal(Buffer.from(await got.arrayBuffer()).toString(), "fake-png-bytes");

  const del = await ctx.fetch(new Request(url, { method: "DELETE" }));
  assert.equal(del.status, 200);
  const gone = await ctx.fetch(new Request(url));
  assert.equal(gone.status, 404);
});

test("rejects missing file", async () => {
  const r2 = fakeR2();
  const kv = fakeKV();
  const ctx = makeCtx(r2, kv);
  const boundary = "----b";
  const body = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="time"\r\n\r\n1h\r\n--${boundary}--\r\n`,
  );
  const res = await ctx.fetch(
    new Request("https://relay.test/upload", {
      method: "POST",
      body,
      headers: { "content-type": `multipart/form-data; boundary=${boundary}` },
    }),
  );
  assert.equal(res.status, 400);
});

test("refuses when storage cap reached (R2 list is source of truth)", async () => {
  const r2 = fakeR2();
  const kv = fakeKV();
  const ctx = makeCtx(r2, kv);
  // Pre-fill a huge fake object so the R2 total sits AT the 500MB cap.
  r2.map.set("i/aaaaaaaaaaaaaaaaaaaaaaaa", {
    bytes: new Uint8Array(500 * 1024 * 1024),
    meta: { customMetadata: { expiresAt: String(Date.now() + 3600_000) } },
  });
  const { body, headers } = multipart(Buffer.from("tiny"));
  const res = await ctx.fetch(
    new Request("https://relay.test/upload", { method: "POST", body, headers }),
  );
  assert.equal(res.status, 507);
  // Nothing extra was written to R2.
  assert.equal(r2.map.size, 1);
});

test("cron deletes expired images", async () => {
  const r2 = fakeR2();
  const kv = fakeKV();
  const env = { IMAGES: r2, USED: kv };
  const past = Date.now() - 1000;
  r2.map.set("i/old", { bytes: new Uint8Array([1]), meta: { customMetadata: { expiresAt: String(past) } } });
  r2.map.set("i/fresh", { bytes: new Uint8Array([2]), meta: { customMetadata: { expiresAt: String(Date.now() + 3600_000) } } });
  await mod.default.scheduled({}, env);
  assert.equal(r2.map.has("i/old"), false);
  assert.equal(r2.map.has("i/fresh"), true);
});
