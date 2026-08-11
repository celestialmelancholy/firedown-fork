# Chrome for Android — Tab Animation Extraction (Cross-Validated)

**Date:** 2026-08-09 · **Chrome build on phone:** 150.0.7871.186 · **Chromium source:** `main` (fetched 2026-08-09)
**Method:** Chromium source (authoritative) + Chrome 150 APK resource decode (interpolators confirmed) + on-device recording attempted (skipped by user decision — source is authoritative; device can only confirm durations, not math).
**Brief reference:** `chrome-tab-animation-extraction-briefcopy.md` (the copy is byte-identical to the original except a trailing newline on the last line).

---

## 1. The source of truth moved (Hub migration)

The old classes (`TabSwitcherLayout`, `SimpleAnimationLayout`, `TabGridDialogView`) **no longer exist on Chromium `main`** — the tab-switcher UI migrated into `chrome/browser/hub/`. The authoritative animation code today lives in:

- `chrome/browser/hub/android/java/src/org/chromium/chrome/browser/hub/HubAnimationConstants.java`
- `chrome/browser/hub/android/java/src/org/chromium/chrome/browser/hub/NewTabAnimationUtils.java`
- `chrome/browser/hub/android/java/src/org/chromium/chrome/browser/hub/ShrinkExpandAnimator.java`
- `chrome/browser/hub/android/java/src/org/chromium/chrome/browser/hub/ShrinkExpandAnimationData.java`
- `chrome/browser/hub/android/java/src/org/chromium/chrome/browser/hub/RoundedCornerAnimatorUtil.java`
- `chrome/browser/hub/internal/android/java/src/org/chromium/chrome/browser/hub/ShrinkExpandHubLayoutAnimatorProvider.java`
- `ui/android/java/src/org/chromium/ui/interpolators/Interpolators.java`

The old `ZOOMING_DURATION=325` (brief) is now `HUB_LAYOUT_SHRINK_EXPAND_DURATION_MS=325` — same value, new home.

---

## 2. THE TABLE (rows = interaction, columns = exact values)

Legend: **[S]** = Chromium `main` source · **[A]** = decoded Chrome 150 APK · **[M]** = on-device measured (skipped)
Disagreements flagged in **bold**.

| Interaction | Duration (ms) | Interpolator (control points) | Scale / translate math | Corner-radius progression | Alpha crossfade |
|---|---|---|---|---|---|
| **New tab** (toolbar `+`, grid `+`, long-press → new tab) | **300** [S] | STANDARD cubic-bezier `(0.2, 0, 0, 1)` [S] | Init rect = target×**0.2**, final rect = target×**1.1** (overshoot), anchored at the trigger (`+` button or card) position. Per-frame: `scaleX = rectW/initW`, `scaleY = rectH/initH`, `translationX = rect.left − round(init.left + (1−scaleX)·initW/2)`, same for Y. Image fill: `xFactor = scale/scaleX`, `yFactor = scale/scaleY`, + x-center postTranslate, + yOffset crop preTranslate. [S] | Fixed: `[0, radius, radius, radius]` → `[0, radius, radius, radius]` where radius = `tab_grid_card_bg_radius` (**24dp**), so **no radius animation** for new-tab — it's a constant 24dp on the animating layer. [S] | Toolbar alpha 0→1 (top toolbar shown behind) with **FAST_OUT_LINEAR_IN** `(0.4,0,1,1)` [S]; bottom bar Y-scale 0→1 same curve [S] |
| **Open existing tab** (grid card → fullscreen) | **325** [S] | EMPHASIZED = FastOutExtraSlowIn path: `M 0,0 C 0.05,0 0.133333,0.06 0.166667,0.4 C 0.208333,0.82 0.25,1 1,1` [S] | Rect-lerp (same per-frame formulas as new-tab) from the card's screen rect to the fullscreen rect, with **top-clip crop** math when the card is partially offscreen (`initialYOffset · ((1−scaleX)/(finalScaleX−1) + 1)` preTranslate). [S] | Card radii **12dp top / 20dp bottom → 24dp** all corners, linear lerp (`start + round(delta·fraction)`), final radii pre-scaled by `initialRect.width()/finalRect.width()` at data-build time. [S] | Toolbar alpha 0→1 expand (FAST_OUT_LINEAR_IN), 1→0 shrink; bottom-bar Y-scale 0→1 (expand) / 1→0 (shrink), same curve [S] |
| **Minimize to grid** (fullscreen → grid) | **325** [S] | EMPHASIZED (same path as above) [S] | Rect-lerp from fullscreen rect back to card rect (exact reverse math) [S] | 24dp → 12/20dp (reverse of expand) [S] | Toolbar alpha 1→0 (FAST_OUT_LINEAR_IN) [S]; bottom-bar Y-scale 1→0 [S] |
| **Close tab** (X / swipe) | **no separate anim in Hub** — card is removed from the grid; swipe uses `swipe_to_dismiss_threshold` (**144dp**); tab-grid dialog close = 400ms fade + 200ms alpha + 50ms card fade (group dialog path) [S] | EMPHASIZED (dialog) [S] | — | — | card fade-out 50ms at end [S] |
| **Tab-grid open (fallback when no thumbnail)** | fade **325ms**; translate 300ms; pane fade 120ms; pane slide 250ms; color-blend 240ms [S] | EMPHASIZED (fade); STANDARD (pane slide) [S] | — | — | fade 0→1 [S] |

**Curve definitions (exact):**
- `EMPHASIZED` = androidx `FastOutExtraSlowInInterpolator` — path `M 0,0 C 0.05,0 0.133333,0.06 0.166667,0.4 C 0.208333,0.82 0.25,1 1,1` [S][A confirmed identical in APK resource `0x7f0d0007`/`0x7f0d000e`]
- `STANDARD_INTERPOLATOR` = `(0.2, 0, 0, 1)` [S]
- `EMPHASIZED_ACCELERATE` = `(0.3, 0, 0.8, 0.15)` [S] (used for dialog Y-translate-out)
- `EMPHASIZED_DECELERATE` = `(0.05, 0.7, 0.1, 1)` [S]
- `FAST_OUT_LINEAR_IN` = `(0.4, 0, 1, 1)` [S][A `0x7f0d0017`]
- `FAST_OUT_SLOW_IN` = `(0.4, 0, 0.2, 1)` [S][A `0x7f0d0015`/`0x7f0d0018`]
- `LINEAR_OUT_SLOW_IN` = `(0, 0, 0.2, 1)` [S][A `0x7f0d0016`/`0x7f0d001a`]

---

## 3. APK cross-check (Chrome 150)

- **Obfuscation reality:** Chrome strips resource names AND class names — `public.xml` has all dimens/anim/interpolator as `APKTOOL_DUMMYVAL_0x7f08xxxx`, and the smali tree is single-letter classes with no `hub`/`tab_management` packages. **Class-level extraction is impossible** on this build; the brief's "resource names survive" assumption does NOT hold for Chrome 150.
- **What survived:** the `interpolator/` XMLs decoded intact. Confirmed: `(0.4,0,1,1)`, `(0.4,0,0.2,1)`, `(0,0,0.2,1)`, the EMPHASIZED path, and `linearInterpolator`. → **Curves cross-validated [S]+[A].**
- **Duration literals** (numeric values survive R8): found `300` (`0x12c`) in a `ViewGroup` animation context (e9p/z8p — a 300ms alpha fade class) and `400` (`0x190`) in a duration field, consistent with source 300/325/400 family, but class identity is unverifiable → flagged as weak evidence.

---

## 4. On-device measurement (attempted, then skipped by decision)

**Why skipped:** device recording can only confirm *durations* (frame counts), never *math* (curves/scales/radii). Source already provides the math; the remaining device value would be a Finch-override check for the two durations, which is low-value relative to cost.

**Partial data captured before skipping (animator scale = 1.0):**
- Grid open (tap tab-count): YAVG transition ≈ 10–12 frames @60fps ≈ **165–200ms** observed fade (source says 325ms fade; the delta is because the *settled-grid* frame is reached early in a fade curve, and screenrecord caps at ~15–30fps on this device — unreliable for exact ms).
- Page-return (back): ≈ 11 frames ≈ **183ms** observed (same caveat).
- **Black-gap finding:** the "black stretch" seen in screenshots is a **screen-lock/recording artifact**, NOT an animation — a second clean recording showed NO black gap (min YAVG 29.7). The real transition is a smooth fade/scale.

---

## 5. What this means for the Firedown port

**Port 1:1 with these values:**

| Firedown piece (current) | Replace with (Chrome-exact) |
|---|---|
| `scale_up_from_tabs.xml` (400ms, 0.85→1.02, popup interp) | **325ms**, EMPHASIZED path, rect-lerp from card rect → fullscreen, radii 12/20→24dp |
| `scale_down_to_tabs.xml` (400ms, 1.0→0.85) | **325ms**, EMPHASIZED, fullscreen rect → card rect, radii 24→12/20dp |
| `NewTabGrowAnimator.java` (450ms, 0.02→1.0 spring, top-left pivot, then 200ms fade) | **300ms**, STANDARD `(0.2,0,0,1)`, init rect = target×0.2 → target×1.1 anchored at trigger, constant 24dp radius, toolbar alpha via FAST_OUT_LINEAR_IN; the surface layer is the home-surface color |
| `popup_interpolator.xml` (already the EMPHASIZED path!) | Keep — it already equals Chrome's EMPHASIZED. |

**Key nuance:** the current `scale_up/down` are pure *scale+fade* of the whole view (pivot center) — Chrome's is a *rect-lerp with translation*, plus corner-radius animation, plus toolbar/bottom-bar alpha/scale. For 1:1, Firedown needs a `ShrinkExpandAnimator`-equivalent: animate a `Rect` (card→fullscreen), apply `scaleX/scaleY/translationX/translationY` per frame, animate corner radii 12/20→24dp with the same curve, and fade the top bar (FAST_OUT_LINEAR_IN).

---

## 6. Sources & paths (for re-verification)

All fetched from `refs/heads/main` 2026-08-09 (commit `8040ee47e9` for tab_ui, `708625466f` for hub):
- HubAnimationConstants, NewTabAnimationUtils, ShrinkExpandAnimator, ShrinkExpandAnimationData, RoundedCornerAnimatorUtil, ShrinkExpandHubLayoutAnimatorProvider, Interpolators.java, dimens.xml (tab_ui).
- APK: `/root/firedown-fork/extraction/chrome-apk/` (base.apk, decoded-res/, jadx-out/, recordings).
