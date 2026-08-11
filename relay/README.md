# Waves Image Relay — Cloudflare Worker + R2

Temporary anonymous image host used by the Waves browser's image search.

**How it works**
- Accepts the exact same multipart upload the app already sends (field
  `fileToUpload`, optional `time` TTL, like Litterbox's contract).
- Stores the image in a dedicated R2 bucket (standard class — **no 30-day
  minimum retention**).
- Returns a public URL `https://<worker>/i/<random-id>`.
- A **1-minute cron** deletes every image whose TTL has expired.
- **Hard 500MB storage cap — never exceeded, ever.** Before each upload the
  relay lists the bucket (R2's strongly-consistent list returns object sizes
  only) and refuses the upload (HTTP 507) if it would push the total over
  500MB. This is the authoritative source of truth — no counter, no drift.
  Even if search breaks as a result, the cap is non-negotiable — the user's
  R2 budget is 500MB total.

**Why this exists:** the app previously used litterbox.catbox.moe (a third
party that briefly holds a copy of the user's photo). This relay keeps the
photo under the user's own Cloudflare account and code.

**Privacy:** the photo sits on Cloudflare's infrastructure (the user's own
account) for at most the TTL (default 1 hour, configurable per upload via
the `time` field). The search engine still receives the photo — that is
inherent to image search.

---

## Deploy (from this phone — no credentials leave the device)

The user runs `wrangler login` themselves (opens a browser, taps Allow; the
OAuth token is stored on-device at `~/.wrangler`). The agent never sees the
token — it only runs `wrangler` locally, which uses the device token.

```bash
# 0. install wrangler (once)
npm i -g wrangler

# 1. auth — USER RUNS THIS, not the agent
wrangler login

# 2. create the bucket (once)
wrangler r2 bucket create waves-image-relay

# 3. deploy
cd relay
wrangler deploy
```

After deploy, wrangler prints the Worker's public URL (e.g.
`https://waves-image-relay.<subdomain>.workers.dev`). Point the app's
`IMAGE_TEMP_HOST_URL` at `https://<that>/upload`.

---

## Contract

**POST `/upload`** — multipart/form-data
- `fileToUpload` (file) — the image bytes
- `time` (optional string) — TTL: `1h`, `30m`, `10m`, `24h` (default `1h`)

Response: `200 text/plain` → the public URL of the uploaded image.
Any error → non-2xx with a short text reason.

**GET `/i/<id>`** — returns the stored image bytes (public, for the search
engine to fetch back).

**DELETE `/i/<id>`** — removes the image immediately.

**Cron (every minute)** — deletes all expired images.

---

## Budget safety

- **Storage cap — HARD, 500MB, never exceeded:** before each upload the relay
  lists the bucket (R2's strongly-consistent list, sizes only) and refuses
  (HTTP 507) if the total would exceed 500MB. No counter to drift — R2 is the
  source of truth. The cap is non-negotiable; the user's total R2 budget is
  500MB.
- **Per-upload cap:** `MAX_UPLOAD_BYTES` (default 20MB).
- **TTL cap:** `MAX_TTL_MS` (default 24h) — even if a client asks for longer.
- The free tier's real limit is operations (1M Class A writes + 10M Class B
  reads/month) — this relay's usage is negligible.

## R2 bucket

Created via `wrangler r2 bucket create waves-image-relay`. The Worker binds
it as `IMAGES` in `wrangler.toml`. Storage class: standard (no minimum
retention).
