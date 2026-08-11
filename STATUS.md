# WAVES (Firedown Fork) — STATUS

**Updated:** 2026-08-11 (image search rebuilt + OWN Cloudflare relay deployed — see ITEM 7)

## ⚠️ NEXT SESSION STARTS HERE — Image Search Fix (waiting-list #1)

**IMAGE SEARCH IS REBUILT AND BUILDING CLEAN (2026-08-11) — on-device verification pending.**
The old Google-direct-upload path (dead: 404) is replaced by a verified flow: upload the
picked photo to **OUR OWN Cloudflare relay** (Worker + R2, auto-delete 1h, hard 450MB cap),
then open the user's selected engine's URL-based image search. Details + verification
evidence in "## ITEM 7 — Image search fix (REBUILT)" below.

Everything else on the waiting list is DONE (see below). The remaining open item:
homepage rebrand video background (#8, concept discussed, screenshots + brief in chat, NOT started).

## Current state

- **Base:** Firedown (solarizeddev) — GeckoView Android browser + uBlock Origin + media downloader.
  - Version: **1.1.87** (versionCode 1187, upstream `main` @ clone time).
  - Upstream repo: https://github.com/solarizeddev/firedown.git
- **Rebranded:** Firedown → **Waves** (display name, strings, logo, download folder). User is now
  CONSIDERING renaming again to **"Eunoia"** (see PENDING DECISIONS) — not started.
- **Working branch:** `custom-ui` (long-lived; NEVER work on `main`). Pushed to fork.
- **Remotes:**
  - `origin` → https://github.com/celestialmelancholy/firedown-fork.git (our GitHub fork — cloud backup + push target)
  - `upstream` → https://github.com/solarizeddev/firedown.git (original dev — source of updates)
- **GitHub:** `origin/custom-ui` = `a741f3a8` (all work pushed). `origin/main` = upstream base, untouched.
  - Pushing is authenticated via `gh` CLI as `celestialmelancholy` (device login — token stays on device).
  - **⚠️ ALL work since the last commit (`c9daca40`) is UNCOMMITTED** (many modified + new files on
    `custom-ui`, 2026-08-11). Nothing pushed. Fixes are DONE and verified on-device — ask the user
    about commit/push.
- **Update flow (when upstream pushes):** `git fetch upstream` → `git merge upstream/main` on `custom-ui`.
  - `custom-ui` tracks `origin/main` (clean merges from upstream).
- **License:** MIT (Firedown core) + GPL-3.0 (bundled uBlock Origin). Attribution line stays in README/LICENSE — never remove.
- **Working tree changes (intentional):** `app/build.gradle` + `settings.gradle` (GeckoView → official AAR), `gradle.properties` (aapt2 override), `.gitignore` (/.commandcode/), `STATUS.md` (this file), `HURDLES.md`, plus all customization work below.

## Environment (phone build machine) — ALL SET UP, WORKS

- Ubuntu ARM64 via proot (Termux → proot-distro). JDK 17 + JDK 21 (Gradle toolchain) via apt, Android SDK at `/opt/android-sdk`.
- **CRITICAL:** aapt2 x86-64 crash on ARM64 — uses Commit451 aarch64 aapt2 drop-in at
  `/opt/android-sdk/build-tools/37.0.0/aapt2`, set in `gradle.properties`:
  `android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/37.0.0/aapt2`
- **ARM64 host-tool swaps (Google ships only x86-64 host tools; backups kept for restore):**
  - NDK `toolchains/llvm/prebuilt/linux-x86_64/bin/{clang,clang++,ld.lld,llvm-*,lld}` →
    **symlinks to `/usr/bin/clang` (proot's own Ubuntu clang 21)** — NOT Termux's (Termux's
    `clang-21` was accidentally overwritten mid-session and never restored; proot clang works).
    Original x86-64 set backed up in `toolchains/llvm/prebuilt/x86_64-backup/` (181 files).
  - SDK `cmake/3.22.1/bin/{cmake,ctest,cpack}` → symlinks to Termux cmake 4.3.3;
    `ninja` → `/usr/bin/ninja` (apt ninja-build 1.13.2).
- **NDK clang wrapper (CRITICAL, custom):** the NDK's `bin/clang`, `bin/clang++`, and all
  `aarch64-linux-androidXX-clang[++]` wrappers are CUSTOM SCRIPTS calling `/usr/bin/clang` with:
  `--sysroot=$NDK/sysroot -resource-dir=$NDK/lib/clang/19 --rtlib=compiler-rt -lunwind -lc -ldl -lm
  -I$S/usr/include/c++/v1 -I$S/usr/include/aarch64-linux-android
  -L$S/usr/lib/aarch64-linux-android/26 -L$R/lib/linux/aarch64`
  - The `-L` MUST point at the API-level dir (`.../26/`) with `libc.so` (SHARED), NOT the top-level
    dir with `libc.a` (STATIC) — linking the static libc caused the getauxval crash (see below).
- Build: `./gradlew :app:assembleReleaseFast` (R8 OFF — fast iteration, ~1-6 min with config cache).
  Real `assembleRelease` (R8 on, ~8-9 min) before shipping.
- Sign: `/opt/android-sdk/build-tools/37.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app-releaseFast-signed.apk app/build/outputs/apk/releaseFast/app-releaseFast-unsigned.apk`
- **CRITICAL SIGNING GOTCHA (learned 2026-08-11):** the signed APK CAN be corrupt if the sign
  process is interrupted (timed-out copy). ALWAYS verify after signing:
  `unzip -t app-releaseFast-signed.apk` (must say "No errors") AND
  `/opt/android-sdk/build-tools/37.0.0/apksigner verify --verbose app-releaseFast-signed.apk`
  BEFORE copying to the phone. A corrupt APK shows a generic file icon + "problem with the app file".
- Copy to phone: `cp app-releaseFast-signed.apk /storage/emulated/0/Download/firedown-custom-ui.apk`
  then `cmp` to confirm byte-identical.
- **Build speed:** config cache + up-to-date tasks make rebuilds ~1 min; cold builds 3-6 min.
- ADB: wireless debugging; IP:port rotates per pairing — ask user each session (pair code + connect port are different!).

## Build status — ✅ WORKS ON DEVICE

- **APK built + runs on the phone.** Opens, no crash on website load/search.
- **CRASH FIXED (the big one):** the on-website-load crash was NOT the old netlink issue. It was
  our `libfiredown.so` (FFmpeg JNI) **embedding STATIC bionic libc** → `getauxval` null-deref
  SIGSEGV at load (`System.loadLibrary` → `dlopen` → constructor). Fixed by linking SHARED
  `libc.so` (see wrapper note above). Symptom signature: `Fatal signal 11`, `getauxval+28` in
  `libfiredown.so`, backtrace via `FFmpegMetaDataReader.<clinit>`.
- **GeckoView:** using OFFICIAL `org.mozilla.geckoview:geckoview:153.0.20260730155536` from
  maven.mozilla.org (Firedown's custom `geckoview-default` AAR is private/unavailable — private
  `firedown-geckoview` repo). Works fine on-device.
- **FFmpeg libs:** extracted from the official release APK → `external/ffmpeg/lib/arm64-v8a/`;
  headers from FFmpeg 8.1.2 source → `external/ffmpeg/include/` (added generated `avconfig.h`).
  These are GITIGNORED, kept on disk — do not delete.

## ROADMAP

| # | Item | Status |
|---|------|--------|
| 1 | Firefox-style homepage | ✅ Implemented (v2) — both known issues FIXED |
| 2 | Single top bar (remove bottom toolbar; shield → address bar → + → tab counter → menu; download button compact in top bar) | ✅ Implemented + UX-fix rounds (dead buttons, jump-back-in, bookmark move, focused full-width bar, back-button, focus icons w/ functionality, ripples, conditional coral) — build passes; on-device verification pending |
| 3 | Fix background media playback (auto-pause active media on tab switch / minimize / exit) | ✅ Implemented (pauseSession/pauseAll in GeckoMediaController + repo sweeps + TRIM_MEMORY_UI_HIDDEN) — on-device verification pending |
| 4 | Rebrand Firedown → Waves (name, logo, strip Firedown; attribution stays) | ✅ DONE + icon safe-zone fix. **User CONSIDERING rename again → "Eunoia"** (pending decision) |
| 5 | Firefox extension support | 🔶 Extensions screen IMPLEMENTED (list/install/enable/disable/remove, 3-dot + Settings entries, crash-proofed). **AMO-signed .xpi only** (GeckoView constraint). On-device verification pending |
| 6 | Tab tray Firefox 1:1 (cards, colors, active ring, scroll, fades) | ✅ DONE (2026-08-11) — card surface #312F33, orange active ring, pixel-exact active-tab scroll, no page-behind snap. See waiting-list below |
| 7 | Image search fix (Lens → classic) | ✅ REBUILT (2026-08-11) — OWN Cloudflare relay (Worker+R2) + URL-based endpoints, engine-aware; on-device verification pending |

## ITEM 4 — Rebrand to Waves — ✅ DONE (+ icon fix)
- All user-facing strings → "Waves" (base + 75 locales); app name; download folder
  `Download/Waves` (StoragePaths.FOLDERNAME); file-name prefix `[Waves]`;
  notification icon; splash; new-tab glyphs; download icon → standard
  `download_24` (functionality unchanged: click → Captured sheet/Downloads/Vault,
  coral-on-media tint preserved).
- Waves Ukiyo-e logo on the exact dark homepage surface `#131315` (launcher
  adaptive + mipmaps + home brand mark + splash). Icon reworked per Android
  adaptive-icon standards: 432px transparent foreground, emblem at 66% safe
  zone, 1:1 legacy tiles — fixes the over-zoom/blur/tiny-dot critique.
- Settings: removed the "Firedown" section title + Share/Donate/Help; kept About.
- Functional URLs (firedown.app, github) + style-name identifiers kept.
- **Rebranding to another name (e.g. "Eunoia") later is CHEAP now**: the same
  string/drawable sweep that did Firedown→Waves does Waves→Eunoia.

## ITEM 5 — Extensions — IMPLEMENTED (2026-08-09; on-device verification pending)
- **Built this session:** `settings/ExtensionsFragment.java` + `settings_extensions.xml` (static
  categories; dynamic rows rebuilt ONLY on the main thread from a cached list — the async
  PreferenceScreen mutation that NPE-crashed is gone). Entries: 3-dot menu "Extensions" row
  (Library group) + Settings door row, both deep-linking via `SettingsActivity.EXTRA_OPEN_EXTENSIONS`.
  `GeckoRuntimeHelper.getWebExtensionController()` accessor + process-wide auto-allow PromptDelegate
  (install/update/optional all auto-granted).
- **⚠️ Platform constraint (verified in GeckoView source):** `install()` only accepts
  **Mozilla-signed .xpi**. Arbitrary unsigned .xpi will FAIL with an InstallException — this is
  GeckoView's design, not fixable from app code. The screen lists + toggles the 5 built-ins, and
  installs AMO-signed files.
- **Engine support proven:** GeckoView 153.0; `GeckoRuntimeHelper` already uses
  `WebExtensionController` (ensureBuiltIn/list/uninstall) + full delegate wiring
  for 5 built-ins: uBlock, youtube, webrequests/downloader, icons, nostr, p2pshare.
- **AMO (addons.mozilla.org) direct install: NOT possible** — Firefox's store
  requires a browser-specific install handshake GeckoView's public API lacks.
- **Feasible v1:** in-app "Extensions" screen — install from `.xpi` via system
  file picker (`WebExtensionController.install`), list installed, enable/disable/
  remove. Real gallery experience without AMO.
- **Feasible v2:** bundle curated extensions as built-ins (like uBlock) e.g. Dark
  Reader. Ship-in-APK model.

## PENDING DECISIONS (user's open questions)
- **Rename "Waves" → "Eunoia"?** User is unsure ("kinda don't feel the wave
  name"). NOT stupid — it's the CHEAPEST time to rename (brand is not shipped
  publicly, no user base). Cost = the same string sweep done once before
  (app_name + ~30 strings + folder name + logo swap). New name needs a logo
  decision (reuse wave logo? new?). Still open — decide before shipping.
- **Commit/push strategy:** all work since `c9daca40` (2026-08-09 through 08-11)
  is uncommitted on `custom-ui`. Fixes are DONE + verified on-device. ASK the
  user about committing/pushing — do NOT push without approval.
- **Extensions screen** (Item 5) — scope v1 (.xpi installer) vs v2 (bundled).
- **Download icon** — currently standard Material download_24; keep or custom.
- Remaining roadmap: Item 3 (background media pause), Item 5 extensions.

## ✅ RESOLVED 2026-08-09/10/11 — previously-broken items now fixed (verified on-device)

- **Tab update transitions** — reverted Chrome tab-anim code; Firefox `fade_in`/`fade_out` (250ms,
  exact copies from mozilla-firefox) wired into browser↔tabs nav. Verified: feels like Firefox.
- **Mic/image-search ripple** — pill → `?attr/colorSurfaceContainerHighest`; buttons →
  `AppCompatImageButton`; `GeckoToolbar` tint → `setImageTintList`. Resolved.
- **Incognito icon** — exact pathData from user's SVG + theme tint. Resolved.
- **Home-tab routing** — `addNewTab()` → direct home nav; `OPEN_SESSION` routes `isHome()` →
  `popToCorrectHome`; `setGeckoViewSession` skips URI load for home. Fixed grey-blank + "+" bugs.
- **Build speed-up** — `releaseFast` (R8 off) + config cache + parallel/daemon/caching.
  Iteration builds ~1 min; cold ~3-6 min.

### ✅ WAITING LIST — ALL RESOLVED (2026-08-11) except #1 (image search, next session)

1. **IMAGE SEARCH** — ✅ **REBUILT (2026-08-11); on-device verification pending.** The old
   Google direct-upload (`/searchbyimage/upload` + `udm=2` rewrite) is DEAD — Google's
   Images redesign 404s the legacy endpoint. Verified with curl (see ITEM 7 below) that
   Yandex's classic `cbir_id=0`/`upfile` direct upload is ALSO dead. New flow: upload the
   picked photo to **our own Cloudflare relay** (Worker + R2, auto-delete 1h, hard 450MB
   cap) → get public URL → open the selected engine's URL-based image search. APK built +
   signed + zip/sig verified + copied to Downloads.

2. **NO VISIBLE 250ms FADE ON TAB MINIMIZE** — ✅ FIXED (2026-08-10). The tab-button path now goes
   through `navigateToTabsWithFade`; `OPEN_TABS_FADE` NavOptions has `enterAnim=fade_in` only (the
   browser's `fade_out` was REMOVED — see #3). Verified on-device: fade feels like Firefox.

3. **MINIMIZE: PAGE STAYS BEHIND TABS ~0.5s THEN SNAPS** — ✅ FIXED (2026-08-10/11). Root cause was
   TWO things: (a) `navigateToTabs()` awaited `capturePixels()` (~300-500ms) before navigating — now
   navigates IMMEDIATELY, capture is async; (b) the old `OPEN_TABS_FADE` had `setExitAnim(fade_out)`
   on the browser, keeping the page visible/fading behind the semi-transparent tray for 250ms — now
   the browser is removed instantly, only the tray fades in. Verified on-device: no page behind.

4. **FRESH INSTALL / NEW HOME TABS SHOW GREY BLANK** — ✅ FIXED (2026-08-09, home-tab routing).
   Never reappeared.

5. **SWITCHER "+" OPENED THE SEARCH ENGINE'S HOMEPAGE** — ✅ FIXED (same root cause as #4).
   Never reappeared.

6. **TAB GRID: LAST TAB CUT OFF / ACTIVE TAB HALF-HIDDEN UNDER TOOLBAR** — ✅ FIXED (2026-08-11).
   - Bottom clearance: `TabsHolderFragment.updateTabGridBottomPadding()` computes bar height + FAB
     overhang + `tab_grid_bottom_breathing` (16dp) and pushes it to each page's RecyclerView via
     `BaseTabsFragment.setRecyclerViewBottomPadding()` (padding ON the RV, not the pager).
   - Active-tab scroll: `BaseTabsFragment.tryApplyFirstSnapshot` → `scrollToPositionWithOffset(row, 0)`
     brings the row to the top so it's laid out, then `post` → `positionActiveRowExactly()` computes
     `targetBottom = viewportHeight - padding - rowHeight` (one full row of breathing space below the
     active card — the "after-scroll screenshot" look) and `scrollBy`s the exact delta. Both run
     before the RV's first draw (hidden behind LCEE loading) — NO visible scroll.
   - ⚠️ CRASH FIXED (2026-08-11): `scrollToActiveRow`'s post lambda had NO null guard →
     NPE on tab switch after view destroy. Both lambdas now guard `mRecyclerView == null ||
     mGridLayoutManager == null`. Verified on-device: no crash, active tab lands correctly.

7. **RARE: APP ICON AS A TAB THUMBNAIL** — ✅ LIKELY FIXED (home-tab routing). Never reappeared.

8. **HOMEPAGE UI REBRAND** — ⏳ CONCEPT DISCUSSED, NOT STARTED. User's brief (2026-08-11):
   static bg → full-bleed looping video (`autoplay muted loop playsinline`, `object-fit: cover`,
   fixed layer behind UI, 0% fade loop, preload/cache so it doesn't rebuffer per new-tab, 1080p max,
   shortcut-icon contrast fix, swappable source via config; time-of-day switching RESERVED).
   Screenshots: `Screenshot_20260810-185541.png` (homepage w/ ocean bg), `234622/235504` (ocean
   variants), `Screenshot_20260811-133743/153435/153439` (tab-tray states). Video assets: `anim2/3/4.mp4`,
   `anim_switch.mp4` in workspace root. Revisit AFTER image search.

9. **FIREFOX 1:1 TAB TRAY** — ✅ MOSTLY DONE (2026-08-11). Card surface #312F33 (Nova Gray65, from
   Firefox's Acorn `surfaceBright`), tray bg #131315, active ring = 4dp brand-orange gradient
   (Violet30→50 recolored to `brand_orange`→`brand_orange_dark`, border-only — the orange wash was
   REVERTED per user 2026-08-11), card corners 16dp, thumbnail top 4dp/bottom 12dp, grid aspect 0.8
   (4:5), header 12dp favicon + 10/5dp spacers + 40dp close touch target, new-tab card leading icon
   `ic_new_tab_tile` (orange tile + white plus), press scale 0.75/alpha 0.7 (Firefox Scale.DRAG_ACTIVE).
   Tab tray header toggle + bottom bar still Firedown-styled (not Firefox) — lower priority.

## ITEM 7 — Image search fix — ✅ REBUILT (2026-08-11, on-device verification pending)

**Why the old code died:** Google's Images redesign (announced late July 2026) 404s the
legacy `searchbyimage/upload` endpoint the app POSTed to. The `udm=2` URL rewrite was never
the bug — the endpoint it depended on was phased out.

**Verification done this session (curl, on-device network):**
- Google direct upload (`searchbyimage/upload`): DEAD (404 / no redirect).
- Yandex direct upload (classic `upfile` + `cbir_id=0`, and `images-apphost/image-details`):
  ALSO DEAD — 307s to a plain page with `cbirId:""`, `total:0`, no thumbs, even with a full
  cookie session. Yandex now requires browser-side session machinery; not reproducible from
  a plain HTTP client.
- **The flow that works:** upload the image to **our own relay**, then open the selected
  engine's URL-based image search:
  - **Google:** `https://lens.google.com/uploadbyurl?url=<ENC>` → verified 303 → real Lens
    results with `vsrid` token.
  - **Yandex:** `https://yandex.com/images/search?rpt=imageview&url=<ENC>` → verified real
    results with `cbirId` populated.
  - **Bing:** `https://www.bing.com/images/search?view=detailv2&iss=sbi&form=SBIVSP&sbisrc=UrlPaste&q=imgurl:<ENC>` → verified real results with `bcid` token.
- DuckDuckGo + Ecosia are Bing-powered → reuse Bing's template. Brave / StartPage / Mojeek /
  Baidu / custom engine → fall back to Yandex (the user's daily driver).

**THE RELAY (deployed 2026-08-11):** `relay/` in this repo — Cloudflare Worker + R2.
- URL: `https://waves-image-relay.nahidhasansajid4620.workers.dev` (POST `/upload` →
  public `GET /i/<id>`; `DELETE /i/<id>`; 1-minute cron deletes expired).
- Bucket: `waves-image-relay` (standard class — no 30-day minimum retention; R2 bills
  GB-months as an average, so a 1h photo ≈ 1/720 GB-month — negligible vs the 500MB budget).
- **HARD 500MB CAP — never exceeded:** before each upload the relay lists the bucket (R2's
  strongly-consistent list, sizes only — ~100ms, no counter to drift) and refuses (HTTP 507)
  if the total would exceed 500MB. This is authoritative; search may break at the cap, but
  500MB is the absolute ceiling. The user explicitly chose "never exceed, even if search
  breaks."
- Contract: same multipart the app sends (`fileToUpload` + optional `time` TTL) — the app
  change was ONE constant: `IMAGE_TEMP_HOST_URL` → the relay URL.
- Deployed with the user's own scoped API token (Workers Scripts + R2 perms only), stored
  on-device (`/root/.cf_token`), never in chat. KV was NOT needed (kept the token scope
  minimal). The user can revoke it in the Cloudflare dashboard + delete the file.
- Smoke-tested locally (`relay/test/smoke.test.js`, 4 tests incl. cap) + live round trip
  verified (upload → byte-identical fetch-back → delete → 404).

**SPEED + UX (2026-08-11, same session):** the user reported the flow took 9–10s with a
dead-looking 3–4s pause after picking. Fixed in the app (`BaseBrowserFragment.java`):
- **Downscale before upload:** the picked photo is now decoded with inSampleSize (long edge
  capped at 1280px), EXIF-rotated, re-encoded JPEG q85 — uploads drop from 3–10MB to
  ~200–500KB. This is the single biggest speedup (upload + engine fetch-back both shrink).
- **Immediate feedback:** a "Searching image…" snackbar (LENGTH_INDEFINITE) shows the moment
  the picker closes, so the tap no longer looks dead; it's dismissed on success/error.
- **10MB source ceiling:** refused rather than OOM.
- New dep: `androidx.exifinterface:exifinterface:1.3.7`.

**Code change (all in `BaseBrowserFragment.java`):**
- `uploadImageToSearch()` now: show working snackbar → `downscaleForUpload()` (1280px, EXIF,
  JPEG q85) → `uploadToTempHost()` (relay multipart POST) → `buildEngineImageSearchUrl()`
  (routes by `mSearchRepository.getSearchType()`) → `openUriInCurrentTab()`.
- Removed: the Google-only `toClassicImageSearchUrl()` rewrite (dead path).
- Privacy: the photo sits in the user's OWN R2 bucket for at most 1h (cron-deleted, hard
  cap), under the user's own Cloudflare account and code — not a third-party host.

**Build:** `assembleReleaseFast` ✅. Signed + `unzip -t` + `apksigner verify` all clean.
Copied to `/storage/emulated/0/Download/firedown-custom-ui.apk` (byte-identical).
**On-device checklist (next session):** pick a photo → "Searching image…" shows immediately
→ results open in the current tab for Google / Yandex / Bing / DuckDuckGo; incognito tab
works; failure shows the error snackbar (working snackbar dismissed).
**On-device checklist (next session):** pick a photo → results open in the current tab for
Google / Yandex / Bing / DuckDuckGo; incognito tab works; failure shows the snackbar.

## ITEM 1 — Homepage (implemented v2, both known issues FIXED)

### Implemented (verified on device by user)
- Branding header: flame logo + "Firedown" name (left), incognito icon → NEW PRIVATE TAB (right).
- 0.5dp edge-to-edge grey divider under the search box (app bar bottom border). ✅ user-approved.
- Shortcuts: 4-col grid of REAL most-visited sites (`MostVisitedTilesAdapter` + `getMostVisited()`),
  row-by-row fill. Header + grid + "Show all >" are CONDITIONAL (show only with data; "Show all"
  only with >1 item). ✅ user-approved.
- Tracker capsule: live uBlock count, hidden at 0, tap → tracker sheet.
- "Jump back in": horizontal cards of real recent non-home tabs (`JumpBackInAdapter` +
  `getTabs()`, sorted by lastAccess), thumbnail/title/url, tap reopens session. Conditional like
  shortcuts. ✅ user-approved.
- Shortcut long-press: context menu ("Open in new tab" / "Remove from Shortcuts") — NOT silent
  delete. ✅ user-approved (was a bug: silent blocklist).
- Section text: regular weight (not bold), white, 17sp.

### FIXED this session (both remaining issues)
1. **Jump-back-in thumbnails now have rounded corners.** Root cause: `item_home_jump_back_in.xml`
   used an `AppCompatImageView` with a rounded background drawable — the loaded bitmap painted
   OVER the background. The `RoundedCorners` Glide transform in `JumpBackInAdapter` didn't show
   because the thumb loads from a file path.
   - Fix: switched `@id/jump_thumb` to a `com.google.android.material.imageview.ShapeableImageView`
     with `app:shapeAppearanceOverlay="@style/HomeJumpThumbnail"` (new 14dp style, distinct from the
     shared 6dp `RoundedThumbnail`). ShapeableImageView actually CLIPS the bitmap to the shape —
     the same pattern `fragment_tabs_item.xml` already used successfully.
   - Files: `app/src/main/res/layout/item_home_jump_back_in.xml`,
     `app/src/main/res/values/styles.xml`.

2. **Logo/text gap below the divider fixed.** Root cause (from STATUS.md): `home_brand_mark` had
   `android:layout_gravity="center_vertical"` — the NestedScrollView's `fillViewport=true` stretches
   the parent FrameLayout to the viewport, so the gravity CENTERED the whole home block and
   overrode every padding change.
   - Fix: changed to `layout_gravity="top"` so the block starts at the top, then set the branding
     header `paddingTop` to 20dp (the original spec estimate — correct all along).
   - File: `app/src/main/res/layout/fragment_home.xml`.

## ITEM 2 — Single Top Bar (implemented this session)

**Goal met:** bottom toolbar removed. The top bar now hosts, left→right:
**shield → address pill → flame download (compact) → + new tab → tab counter → 3-dot menu.**
(Bookmarks button also rides in the top-bar cluster on Home/incognito; hidden on the browser.)

### How it was done (minimal-invasion — no behavior lost)
- **Buttons moved, not reimplemented.** The bottom bar's `+`/tab-counter/menu (and bookmarks on
  Home) now live in a `top_bar_actions` cluster inside `browser_address_bar.xml` (the GeckoToolbar
  layout). They keep their exact IDs, classes (`TabsBrowserButton` etc.), drawables, and functions.
- **`BottomNavigationBar` is now a zero-height GONE controller**, kept in every fragment layout so
  ALL existing consumers keep working unchanged: `AnchorBehavior` (snackbar anchoring),
  `BottomNavigationBehavior` (scroll follower), `NestedGeckoViewBehavior` (clipping), and
  `FindViewUtils`. It binds the relocated buttons by ID from the fragment root in
  `onAttachedToWindow` and still receives `onBadgeCount`/`onTabsCount`/`updateTheme`. The download
  badge now attaches to the compact flame button.
- **Fragments unchanged in their handlers**: `onBottomBarButtonClick`/`onBottomBarButtonLongClick`
  key off button IDs, so Home/HomeIncognito/Browser logic is untouched. Each fragment got ONE new
  `R.id.download_button` case:
  - Browser: flame → the Captured sheet (`dialog_browser_options`) — same action as the old FAB.
  - Home: flame → `DownloadsActivity`; HomeIncognito: flame → `VaultActivity` (same as old button).
  - The old `downloads_button` cases were dropped (that ID no longer exists).
- **Browser FAB removed** (`download_button` FAB + `BottomNavigationFABBehavior` usage in
  `fragment_browser.xml`). `BaseBrowserFragment.mDownloadButton` retyped `FloatingActionButton` →
  `View` (the flame is now a MaterialButton in the toolbar).
- **Scroll math:** `mBottomBarSize = 0` in BrowserFragment → `dynamicToolbarMaxHeight =
  app_bar_size` (only the top bar scroll-hides). Fullscreen/find-in-page still work: fullscreen
  hides the whole toolbar (flame included); find-in-page hides just the flame.
- **Home layouts:** removed the `app_bar_size` bottom margins that reserved the bottom bar
  (`home_scroll`, `bottom_new_tab` flash overlay) so content now extends to the bottom edge.
- `app/src/main/res/layout/bottom_bar.xml` DELETED (no longer referenced).

### Files changed for Item 2 (all on `custom-ui`)
- `app/src/main/res/layout/browser_address_bar.xml` — added `top_bar_actions` cluster; pill now
  ends before it
- `app/src/main/res/layout/fragment_browser.xml` — removed FAB; bottom bar GONE + 0-height
- `app/src/main/res/layout/fragment_home.xml` — bottom bar GONE + 0-height; removed bottom-bar
  margins on `home_scroll` + `bottom_new_tab`
- `app/src/main/res/layout/fragment_home_incognito.xml` — same as home
- `app/src/main/res/layout/bottom_bar.xml` — DELETED
- `app/src/main/java/com/solarized/firedown/geckoview/toolbar/BottomNavigationBar.java` — binds
  toolbar-hosted buttons by ID; keeps all badge/tab/theme/click logic
- `app/src/main/java/com/solarized/firedown/phone/fragments/BaseBrowserFragment.java` —
  `mDownloadButton` retyped to `View`
- `app/src/main/java/com/solarized/firedown/phone/fragments/BrowserFragment.java` —
  `mBottomBarSize=0`; flame click → Captured sheet; FAB dock listener removed;
  `mDownloadButton` show/hide → setVisibility
- `app/src/main/java/com/solarized/firedown/phone/fragments/HomeFragment.java` +
  `HomeIncognitoFragment.java` — added `download_button` handler (Downloads/Vault)

### Item 2 on-device verification checklist (NOT YET DONE — next session)
- [ ] APK installed, top bar shows: shield → address pill → flame → + → tab counter → menu
- [ ] No bottom bar anywhere (browse a page, scroll — only the top bar hides)
- [ ] Tab counter updates live; + opens a new tab (long-press → new-tabs dialog)
- [ ] 3-dot menu opens the popup; flame opens the Captured sheet (browser) / Downloads (Home) / Vault (incognito)
- [ ] Shield opens security info (browser) / search-engine picker (Home)
- [ ] 3-dot menu has Bookmarks (Browser: star + Library row [both original]; Home: added row)
- [ ] Tapping the address bar hides the cluster and the pill goes full-width; icons return on blur/enter
- [ ] No dead buttons after tab switch / new tab / reopen (the fragment-crossing bug)
- [ ] Jump back in shows each site ONCE (no duplicates); tapping opens the tab
- [ ] Fullscreen video hides the top bar; back restores it
- [ ] Find-in-page works (toolbar becomes find bar)
- [ ] Snackbars still anchor at the bottom

## ITEM 2 — UX-fix round (this session, build passes)
Fixes applied after the user's on-device review of the single top bar:
1. **DEAD BUTTONS (root cause found + fixed).** `BottomNavigationBar` used
   `getRootView().findViewById(...)` in `onAttachedToWindow`. During fragment
   navigation (home ↔ browser) BOTH fragments' views coexist in the window, so
   the lookup could bind another fragment's toolbar buttons — listeners died
   when that view detached (the "buttons work, then stop after tab switch"
   bug). Fix: fragments now call `bindButtons(fragmentRootView)` in onCreateView
   with their OWN root — every lookup is scoped to the right fragment.
   The user's lead (bookmark icon appearing = buttons break) matched this:
   the cluster's bookmarks button made the mismatch visible.
2. **Bookmark moved into the 3-dot menu.** Removed the `search_button` (bookmark)
   icon from the top-bar cluster entirely. Home's 3-dot popup got a "Bookmarks"
   row (it had none); the Browser popup already had the Bookmark★ star + a
   Bookmarks Library row (both original — NOT added twice).
3. **Jump back in: duplicates + dead tap.** The repository can hold duplicate/
   stale GeckoState objects for one tab (navigation churn), so the raw list
   showed the same site twice and the stale id failed the
   `getGeckoState(sessionId)` lookup on tap. The adapter now dedupes by entity
   id (most recent lastAccess wins), so each site shows once and taps resolve
   to the live id.
4. **Thumbnail roundness.** Was over-rounded (14dp on a small thumb reads
   bigger than the card). Thumbnail overlay now 10dp, and the adapter no longer
   applies a redundant RoundedCorners bitmap transform (the ShapeableImageView
   clips alone) — no double-rounding.
5. **Colorful download flame.** The flame vector is monochrome; it now tints to
   the brand primary (coral) — incognito-aware via IncognitoColors — matching
   the accent badge dot when media is caught.
6. **Home gaps.** Shortcuts → tracker capsule margin 12dp → 24dp (2×). "Jump
   back in" header paddingTop 40dp → 60dp (1.5×).
7. **Focused full-width address bar (new UX).** While the address field is
   focused (typing/editing), the whole action cluster (+ / tab / flame / menu)
   hides and the pill stretches edge-to-edge (goneMarginEnd). Blur or enter
   restores the cluster and compresses the pill. Also applies to find-in-page
   mode.

### Files changed in the UX-fix round (all on `custom-ui`)
- `app/src/main/java/com/solarized/firedown/geckoview/toolbar/BottomNavigationBar.java` — `bindButtons(hostRoot)` (fragment-scoped); removed search_button handling
- `app/src/main/java/com/solarized/firedown/geckoview/GeckoToolbar.java` — `updateViewVisibility` hides/shows the cluster on focus
- `app/src/main/java/com/solarized/firedown/phone/fragments/HomeFragment.java` + `HomeIncognitoFragment.java` — `bindButtons(v)`; `popup_bookmarks` handler; removed dead `search_button` branches
- `app/src/main/java/com/solarized/firedown/phone/fragments/BrowserFragment.java` — `bindButtons(v)`
- `app/src/main/java/com/solarized/firedown/phone/dialogs/PopupHomeSheetDialogFragment.java` — binds `popup_bookmarks`
- `app/src/main/res/layout/fragment_dialog_home_popup.xml` — added Bookmarks row
- `app/src/main/res/layout/browser_address_bar.xml` — removed bookmarks button; `goneMarginEnd` on pill; flame tint primary
- `app/src/main/res/layout/fragment_home.xml` — gap tweaks (24dp capsule, 60dp jump header)
- `app/src/main/res/values/styles.xml` — `HomeJumpThumbnail` 10dp
- `app/src/main/java/com/solarized/firedown/ui/adapters/JumpBackInAdapter.java` — dedupe by id; drop redundant thumb rounding

## Files changed for Item 1 (all on `custom-ui`)
- `app/src/main/res/layout/fragment_home.xml` — new home content (replaced brand block)
- `app/src/main/res/layout/item_home_jump_back_in.xml` — new recents card layout
- `app/src/main/java/com/solarized/firedown/phone/fragments/HomeFragment.java` — wiring (grid,
  capsule, recents, incognito, conditional visibility, shortcut menu, onDestroyView re-added)
- `app/src/main/java/com/solarized/firedown/ui/adapters/JumpBackInAdapter.java` — new adapter
- `app/src/main/res/values/colors.xml` — `home_*` colors (shortcut circle #3A3A40, card #2B2B30, divider, header #FFF, subheader #9AA0A6)
- `app/src/main/res/values/dimens.xml` — `home_card_radius` 14dp
- `app/src/main/res/values/strings.xml` — `home_*` strings
- `app/src/main/res/drawable/` — `ic_home_shield_check.xml`, `bg_home_shortcut_circle.xml`, `bg_home_jump_thumb.xml`
- Deleted: `ic_home_infinity.xml`, `ic_home_pin.xml` (unused)

## Hard-won lessons (do not re-learn)
- **Never link the NDK's static libc.a into a shared .so** — embed bionic → getauxval SIGSEGV.
  Always `-L` the API-level dir (`.../26/`) so `libc.so` (shared) is found.
- **Google publishes NO linux-aarch64 host tools** (aapt2, cmake, ninja, NDK clang). Every one
  must be swapped for a native binary. NDK clang → proot's `/usr/bin/clang` + NDK sysroot.
- **Wireless adb:** pairing port ≠ connect port. Get BOTH from the Wireless debugging screen.
  Mobile data usually breaks adb; wifi works.
- **Build on this phone:** run `./gradlew :app:assembleRelease` — ~6-10 min. Always sign before
  installing (debug keystore at `~/.android/debug.keystore`, password `android`).
- **User's net drops occasionally (rain).** Retries work; Gradle caches progress.
- **Termux's `pkg`/`apt` refuse to run as root** — if a Termux binary is damaged, it cannot be
  reinstalled from proot; switch to proot/Ubuntu binaries instead (that's how clang was fixed).
- **Rounding image corners:** an ImageView's `background` drawable is NOT clipped by the loaded
  bitmap — the bitmap paints over it. Use a `ShapeableImageView` + `shapeAppearanceOverlay` (it
  clips content), or a Glide `RoundedCorners` transform on the load itself.
- **Moving controls between toolbars:** keep the button IDs + classes the same and route clicks
  through one controller (`BottomNavigationBar`). Every existing fragment handler keyed on `R.id.*`
  keeps working with zero changes.
- **Ripples on a same-color surface are invisible** unless they have a contrasting container — a
  borderless ripple over a matching-tone search box reads as nothing (mic/image-search pill failed
  once with #14FFFFFF; needs stronger contrast).
- **Use the user's exact SVG/XML paths** — do NOT hand-convert SVG arcs to Android pathData (the
  incognito glasses broke this way). If conversion is unavoidable, verify the render.
- **PreferenceScreen mutation from async callbacks NPEs** (`PreferenceGroup` "monitor-enter on
  null") — never touch preference rows off the main thread or after detach; cache + rebuild on
  the main thread only.
- **Image search:** Lens endpoints (lens.google.com/upload) 500/nag on direct POST — use
  `https://www.google.com/searchbyimage/upload` (multipart `encoded_image` + `image_content`),
  which 303-redirects to working results. Verified via curl.
- **GeckoView `install()` = Mozilla-signed .xpi only** — unsigned .xpi fails by design; built-ins
  via ensureBuiltIn/installBuiltIn work unsigned.

## Milestone checklist
- [x] Work on `custom-ui` (never `main`) — enforced
- [x] Compile after each milestone: `./gradlew assembleRelease`
- [x] GPL-3.0 attribution present (README + LICENSE + NOTICE untouched)
- [x] Update STATUS.md + SKILL.md roadmap at each milestone
