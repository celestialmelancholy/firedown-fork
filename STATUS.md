# FIREDOWN FORK — STATUS

**Updated:** 2026-08-06 (session handoff — ready for new session)

## Current state

- **Base:** Firedown (solarizeddev) — GeckoView Android browser + uBlock Origin + media downloader.
  - Version: **1.1.87** (versionCode 1187, upstream `main` @ clone time).
  - Upstream repo: https://github.com/solarizeddev/firedown.git
- **Working branch:** `custom-ui` (long-lived; NEVER work on `main`).
- **Remotes:**
  - `origin` → https://github.com/celestialmelancholy/firedown-fork.git (our GitHub fork — cloud backup + push target)
  - `upstream` → https://github.com/solarizeddev/firedown.git (original dev — source of updates)
- **Update flow (when upstream pushes):** `git fetch upstream` → `git merge upstream/main` on `custom-ui`.
  - `custom-ui` tracks `origin/main` (clean merges from upstream).
- **License:** MIT (Firedown core) + GPL-3.0 (bundled uBlock Origin). Attribution line stays in README/LICENSE — never remove.
- **Working tree changes (intentional):** `app/build.gradle` + `settings.gradle` (GeckoView → official AAR), `gradle.properties` (aapt2 override), `.gitignore` (/.commandcode/), `STATUS.md` (this file), plus all homepage work below.

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
- Build: `./gradlew assembleRelease` → unsigned APK in `app/build/outputs/apk/release/`.
  - Full build ~6-10 min. Sign: `apksigner sign --ks ~/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app-release-signed.apk app-release-unsigned.apk`
  - Copy to phone: `cp app-release-signed.apk "/storage/emulated/0/Download/firedown-custom-ui.apk"`
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
| 2 | Single top bar (remove bottom toolbar; shield → address bar → + → tab counter → menu; flame download button compact in top bar) | ✅ Implemented + UX-fix rounds (dead buttons, jump-back-in, bookmark move, focused full-width bar, back-button, focus icons, ripples, conditional flame) — build passes; on-device verification pending |
| 3 | Fix background media playback (auto-pause active media on tab switch / minimize / exit) | ⏳ Pending |
| 4 | App rename (keep flame logo) + upstream git strategy | ✅ Git strategy done; **rename → REBRAND to "Waves" (new name + logo + strip Firedown from UI; attribution stays in README/LICENSE/NOTICE + GitHub)** — planned, not started |
| 5 | Firefox extension support (GeckoView WebExtension API + add-on UI) | ⏳ Discussed — planned as separate milestone AFTER rebrand |

## ITEM 2 — UX-fix round 2 (back button, focus icons, ripples, flame) — build passes
1. **Back button — page history first, always.** BrowserFragment's back handler
   routes through `GeckoState.canGoBackNow()` (reads the entity flag kept
   current by `NavigationDelegate.onHistoryStateChange` — GeckoView's public API
   has NO live `canGoBack()` on GeckoSession, verified at build time). Chain
   order unchanged: fullscreen → search mode → page history → previous tab →
   external → home.
2. **Back with the address bar open no longer exits the app.** HomeFragment +
   HomeIncognitoFragment back callbacks now check the edit field's focus state:
   if the field is focused (overlay not visible), back just clears focus/editing
   (closes keyboard) instead of falling through to default (which finished the
   activity on the root destination).
3. **Chrome-style focus icons.** New `ic_image_search_24` + `ic_mic_24` Material
   vectors (matching the app's drawable conventions). They sit on the right of
   the address pill, GONE at rest, VISIBLE while the field is focused (wired in
   `GeckoToolbar.updateViewVisibility`, tinted in `updateTheme`). Actions NOT
   wired yet (image search / voice search need a defined backend) — icons are
   visual affordances for now.
4. **Tap ripples.** `home_private_infinity` (incognito icon) got
   `selectableItemBackgroundBorderless`; both "Show all >" TextViews got
   `selectableItemBackground` + touch padding — they now show the touch
   indicator like every other icon.
5. **Download flame — conditional color.** Neutral (onSurfaceVariant) at rest;
   turns brand-primary (coral) ONLY while media is being caught
   (`onBadgeCount > 0`, same signal as the badge dot). Reverts to neutral when
   the count clears. Incognito resolves its own primary. XML default also
   neutral.

### Files changed (round 2, all on `custom-ui`)
- `app/src/main/java/com/solarized/firedown/geckoview/GeckoState.java` — `canGoBackNow()` (live query)
- `app/src/main/java/com/solarized/firedown/phone/fragments/BrowserFragment.java` — back uses live query
- `app/src/main/java/com/solarized/firedown/phone/fragments/HomeFragment.java` + `HomeIncognitoFragment.java` — focused-field back guard
- `app/src/main/java/com/solarized/firedown/geckoview/GeckoToolbar.java` — focus-icon visibility + theme + clicks
- `app/src/main/res/layout/browser_address_bar.xml` — mic + image-search buttons; flame neutral default
- `app/src/main/res/drawable/ic_mic_24.xml`, `ic_image_search_24.xml` — NEW vectors
- `app/src/main/res/layout/fragment_home.xml` — ripples on incognito + Show-all
- `app/src/main/java/com/solarized/firedown/geckoview/toolbar/BottomNavigationBar.java` — flame tint driven by badge count

## PENDING DECISIONS (from whatnow.md — discussed, user chose: fix bugs first, rebrand later)
- **Rebrand to "Waves"** — new app name + logo; strip "Firedown" from all UI
  strings/labels; keep flame logo? (user said new logo); attribution stays in
  README/LICENSE/NOTICE + GitHub. Planned as its own milestone.
- **Download icon after rebrand** — the flame IS Firedown's logo; after rebrand,
  switch the download affordance to a standard download icon (or a custom one).
- **Firefox extension support** — GeckoView WebExtension API + add-on management
  UI (install/enable/disable). Substantial; planned AFTER rebrand as Item 5.
- Remaining roadmap: Item 3 (background media pause), Item 4 rename→rebrand,
  Item 5 extensions.


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

## Milestone checklist
- [x] Work on `custom-ui` (never `main`) — enforced
- [x] Compile after each milestone: `./gradlew assembleRelease`
- [x] GPL-3.0 attribution present (README + LICENSE + NOTICE untouched)
- [x] Update STATUS.md + SKILL.md roadmap at each milestone
