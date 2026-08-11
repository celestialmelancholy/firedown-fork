/**
 * Waves Image Relay — Cloudflare Worker + R2.
 *
 * Temporary anonymous image host for the browser's image search.
 * Contract matches what the app already sends (multipart, Litterbox-style).
 */

const MAX_UPLOAD_BYTES = 20 * 1024 * 1024; // per upload
const MAX_TTL_MS = 24 * 60 * 60 * 1000; // hard ceiling on requested TTL
const DEFAULT_TTL_MS = 60 * 60 * 1000; // 1 hour
const STORAGE_CAP_BYTES = 500 * 1024 * 1024; // hard cap: NEVER exceed 500MB
const KEY_PREFIX = "i/";

function parseTtl(timeField) {
  if (!timeField) return DEFAULT_TTL_MS;
  const m = /^(\d+)([mhd])$/.exec(timeField.trim());
  if (!m) return DEFAULT_TTL_MS;
  const n = parseInt(m[1], 10);
  const mult = m[2] === "m" ? 60 * 1000 : m[2] === "h" ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000;
  const ttl = n * mult;
  return Math.min(ttl, MAX_TTL_MS);
}

function randomId() {
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

async function readMultipartFile(request) {
  const form = await request.formData();
  const file = form.get("fileToUpload");
  if (!file || typeof file === "string" || !file.arrayBuffer) {
    return null;
  }
  const ttl = parseTtl(form.get("time"));
  const buf = await file.arrayBuffer();
  return { bytes: new Uint8Array(buf), ttl };
}

// Current total bytes in the bucket (single list call — returns sizes only,
// no bodies; ~100ms. Strongly consistent in R2, so this is the source of
// truth for the hard cap — no counter, no race).
async function usedBytes(env) {
  let total = 0;
  let cursor;
  do {
    const listed = await env.IMAGES.list({ prefix: KEY_PREFIX, cursor });
    for (const obj of listed.objects) total += obj.size;
    cursor = listed.truncated ? listed.cursor : undefined;
  } while (cursor);
  return total;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;
    const path = url.pathname;

    // POST /upload — accept the image, store it, return the public URL.
    if (method === "POST" && (path === "/upload" || path === "/")) {
      let file;
      let ttl;
      try {
        const parsed = await readMultipartFile(request);
        if (!parsed) return new Response("missing fileToUpload", { status: 400 });
        file = parsed.bytes;
        ttl = parsed.ttl;
      } catch (e) {
        return new Response("invalid multipart", { status: 400 });
      }
      try {
        if (file.byteLength > MAX_UPLOAD_BYTES) {
          return new Response("file too large", { status: 413 });
        }
        // Hard cap: NEVER exceed 500MB. R2 list is strongly consistent and
        // returns sizes only — this is the authoritative total, checked
        // before every upload. Refusing (HTTP 507) is the safe failure mode.
        const used = await usedBytes(env);
        if (used + file.byteLength > STORAGE_CAP_BYTES) {
          return new Response("storage cap reached", { status: 507 });
        }
        const id = randomId();
        const key = KEY_PREFIX + id;
        const expiresAt = Date.now() + ttl;
        await env.IMAGES.put(key, file, {
          customMetadata: { expiresAt: String(expiresAt) },
          httpMetadata: { contentType: "application/octet-stream" },
        });
        const base = new URL(request.url).origin;
        return new Response(`${base}/i/${id}`, { status: 200 });
      } catch (e) {
        return new Response("upload failed", { status: 500 });
      }
    }

    // GET /i/<id> — public fetch-back for search engines.
    if (method === "GET" && path.startsWith("/i/")) {
      const id = path.slice(3);
      const obj = await env.IMAGES.get(KEY_PREFIX + id);
      if (!obj) return new Response("not found", { status: 404 });
      const headers = { "Cache-Control": "public, max-age=60" };
      const ct = obj.httpMetadata?.contentType;
      if (ct) headers["Content-Type"] = ct;
      return new Response(obj.body, { headers });
    }

    // DELETE /i/<id> — immediate removal.
    if (method === "DELETE" && path.startsWith("/i/")) {
      const id = path.slice(3);
      await env.IMAGES.delete(KEY_PREFIX + id);
      return new Response("deleted", { status: 200 });
    }

    return new Response("not found", { status: 404 });
  },

  // Every minute: delete everything whose TTL has passed.
  async scheduled(event, env) {
    const now = Date.now();
    let cursor;
    let deleted = 0;
    do {
      const listed = await env.IMAGES.list({ prefix: KEY_PREFIX, cursor });
      for (const obj of listed.objects) {
        try {
          const head = await env.IMAGES.head(obj.key);
          const expiresAt = head?.customMetadata?.expiresAt;
          if (expiresAt && Number(expiresAt) <= now) {
            await env.IMAGES.delete(obj.key);
            deleted++;
          }
        } catch {}
      }
      cursor = listed.truncated ? listed.cursor : undefined;
    } while (cursor);
    console.log(`cleaned ${deleted} expired images`);
  },
};

