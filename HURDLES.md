# Waves (Firedown Fork) — Every Hurdle We Beat

This file is the permanent record of every hard-won finding from building a
custom GeckoView Android browser ON A PHONE (Termux → proot-distro, Ubuntu
ARM64). Every one of these cost us hours — most cost us days. **Do not
re-learn them.** Read this before touching the build environment, the NDK,
ADB, or anything GeckoView-related.

---

## 1. The setup that nearly killed the project (early sessions)

### 1.1 The original browser-project crashed ~6s after launch
- **Symptom:** `GeckoThread EXITED` → `System.exit(0)` ~6 seconds after
  launch, every time, on a non-rooted Android 13+ phone.
- **Root cause (exhaustively proven):** stock `geckoview-omni` AARs
  (120/128/133/153/154 — ALL tested) start a `NetlinkService`
  ("Netlink Monitor" thread) in EVERY process that does
  `socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE)` + `bind()`. On Android 13+,
  SELinux **denies `bind` on `netlink_route_socket`** for
  `untrusted_app_34` (AOSP removed it from the `netdomain` allow-rule).
  Stock GeckoView treats the bind failure as fatal.
- **What did NOT fix it (all tested, all failed):**
  - Every GeckoView version: 120 / 128 / 133 / 153 / 154
  - Every pref: `network.connectivity-service.enabled`,
    `network.captive-portal-service.enabled`, `network.manage-offline-status`,
    `network.notify.changed`, `network.trr.mode` / DoH
  - targetSdk 34 → 36, `extractNativeLibs`, SessionFeature,
    `GeckoNetworkManager.getInstance().start(context)` Java fallback,
    phantom-process toggle, storage cleanup
- **The truth:** Firefox 153.0.2 runs the byte-identical engine
  (`libxul.so` + `omni.ja`, same MD5) yet survives — its app layer (Fenix)
  never triggers the fatal child-process netlink bind. This is a PRIVATE
  app-layer integration we could NOT replicate through the public GeckoView
  API.
- **The decision that saved the project:** STOP rebuilding an engine shell
  from scratch. Fork **Firedown** (a real, shipped, working GeckoView app
  with uBlock + downloader). Never re-attempt a from-scratch GeckoView shell.
- **How to recognize this exact crash again:** logcat shows
  `avc: denied { bind } netlink_route_socket` with
  `scontext=u:r:untrusted_app_34`, `comm=4E65746C696E6B204D6F6E69746F72`
  (hex for "Netlink Monitor"; decode with `echo <hex> | xxd -r -p`), then
  `System.exit called, status: 0` and `GeckoThread EXITED`.

### 1.2 Diagnostic toolbox (from the 250M-token investigation)
- **Process-exit reasons (authoritative):**
  `adb shell dumpsys activity exit-info <package>`.
  `1`=EXIT_SELF, `2`=SIGNALED (SIGKILL), `4`=APP CRASH, `6`=LOW_MEMORY,
  `9`=EXCESSIVE_RESOURCE USAGE, `10`=DEPENDENCY_DIED.
- **In-app capture of previous exits (Android 11+):** in
  `Application.onCreate`, BEFORE engine init, read
  `ActivityManager.getHistoricalProcessExitReasons()` — logs process,
  reason, status, importance, timestamp, description for every package
  process. The single best diagnostic for "app dies silently".
- **GeckoThread lifecycle in logcat:** `LAUNCHED → MOZGLUE_READY →
  LIBS_READY → JNI_READY → PROFILE_READY → RUNNING → EXITED` tells you
  exactly how far Gecko got before dying.
- **`System.exit called, status: 0`** = Gecko's NATIVE side deliberately
  exited (not a crash) — follows a failed init in a child process.
- **Mozilla source digging:** use **searchfox.org** (fetch one file by URL,
  e.g. `https://searchfox.org/mozilla-central/source/netwerk/system/android/
  nsAndroidNetworkLinkService.cpp`). Never full-clone.
- **`GeckoRuntime.setDefaultPrefs(GeckoBundle)` is package-private** — only
  reachable by reflection or by being in `org.mozilla.gecko`. Fragile.

---

## 2. The ARM64 phone build environment (every tool Google didn't ship)

Google publishes **NO linux-aarch64 host tools** for the Android SDK/NDK.
Every single one must be swapped for a native binary. This is THE thing that
ate the setup time.

### 2.1 aapt2 (the x86-64 crash)
- **Symptom:** AGP's downloaded aapt2 is x86-64 and crashes on ARM64
  ("Illegal instruction" / segfault during resource processing).
- **Fix:** Commit451 `android-arm-build-tools` aarch64 aapt2 drop-in at
  `/opt/android-sdk/build-tools/37.0.0/aapt2`, set in `gradle.properties`:
  ```
  android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/37.0.0/aapt2
  ```
- This is **mandatory**, not optional — there is no Google aarch64 aapt2.

### 2.2 NDK clang toolchain
- **Symptom:** NDK ships only `linux-x86_64` clang/lld.
- **Fix:** symlink the NDK's
  `toolchains/llvm/prebuilt/linux-x86_64/bin/{clang,clang++,ld.lld,llvm-*,lld}`
  → `/usr/bin/clang` (proot's OWN Ubuntu clang 21 — NOT Termux's; Termux's
  `clang-21` was accidentally overwritten and never restored). Original set
  backed up in `toolchains/llvm/prebuilt/x86_64-backup/` (181 files).
- **The NDK's `bin/clang` / `bin/clang++` / `aarch64-linux-androidXX-clang`
  wrappers are CUSTOM SCRIPTS** calling `/usr/bin/clang` with:
  ```
  --sysroot=$NDK/sysroot -resource-dir=$NDK/lib/clang/19
  --rtlib=compiler-rt -lunwind -lc -ldl -lm
  -I$S/usr/include/c++/v1 -I$S/usr/include/aarch64-linux-android
  -L$S/usr/lib/aarch64-linux-android/26 -L$R/lib/linux/aarch64
  ```
- **CRITICAL — the -L MUST point at the API-level dir (`.../26/`) with
  `libc.so` (SHARED)**, NOT the top-level dir with `libc.a` (STATIC).
  Linking static libc into a shared .so caused the getauxval crash below.

### 2.3 cmake / ninja
- SDK `cmake/3.22.1/bin/{cmake,ctest,cpack}` → symlinks to Termux cmake 4.3.3.
- `ninja` → `/usr/bin/ninja` (apt ninja-build 1.13.2).

### 2.4 Termux vs proot rules
- **Termux's `pkg`/`apt` REFUSE to run as root.** If a Termux binary is
  damaged, it cannot be reinstalled from proot — switch to proot/Ubuntu
  binaries instead (that's exactly how clang was fixed).
- The phone's `/data` filled to 100% during large-APK testing — always check
  `df -h /data`, keep 5-10GB free before installing ~600MB debug APKs.

---

## 3. The getauxval SIGSEGV (the on-device crash that nearly stalled us)

- **Symptom:** app opens, then crashes on website load/search:
  `Fatal signal 11`, `getauxval+28` in `libfiredown.so` (our FFmpeg JNI),
  backtrace via `FFmpegMetaDataReader.<clinit>`, at `System.loadLibrary` →
  `dlopen` → constructor.
- **Root cause:** our `libfiredown.so` was **embedding STATIC bionic libc**
  (`libc.a`) → `getauxval` null-deref at load.
- **Fix:** link SHARED `libc.so` — i.e. the `-L .../26/` rule in §2.2.
- **Lesson:** NEVER link the NDK's static `libc.a` into a shared `.so`.

---

## 4. Wireless ADB (the rotating-port trap)

- **Pairing port ≠ connect port.** The "IP address & Port" on the Wireless
  debugging screen is the CONNECT port; the pairing dialog gives a separate
  PAIR port + 6-digit code. Get BOTH from the screen; they're different.
- The connect port **rotates every re-pair** (pairing persists, connection
  port changes). Ask the user for the current port each session.
- **Mobile data usually breaks adb; Wi-Fi works.**
- On this machine ADB targets the phone ITSELF (the build machine IS the
  phone), so "wireless debugging to the phone" is the loopback use case.

---

## 5. Build / release flow (the working recipe)

```
./gradlew :app:assembleRelease          # ~6-10 min, unsigned APK in
                                        # app/build/outputs/apk/release/

apksigner sign --ks ~/.android/debug.keystore \
  --ks-key-alias androiddebugkey --ks-pass pass:android \
  --key-pass pass:android \
  --out app-release-signed.apk app-release-unsigned.apk

cp app-release-signed.apk /storage/emulated/0/Download/firedown-custom-ui.apk
```
- Debug keystore: `~/.android/debug.keystore`, password `android`.
- **User's net drops occasionally (rain).** Retries work; Gradle caches
  progress. Builds take 6-10+ min — audit ALL changes for dangling
  references BEFORE running a build (each failed build costs a full cycle).

---

## 6. GeckoView integration findings (Firedown-specific)

- **GeckoView:** use the OFFICIAL `org.mozilla.geckoview:geckoview:...` AAR
  from maven.mozilla.org. Firedown's custom `geckoview-default` AAR is
  private/unavailable, and its `firedown-geckoview` repo is private. The
  official AAR works fine on-device.
- **FFmpeg libs:** extracted from the official release APK →
  `external/ffmpeg/lib/arm64-v8a/`; headers from FFmpeg 8.1.2 source →
  `external/ffmpeg/include/` (added generated `avconfig.h`). GITIGNORED,
  kept on disk — never delete.
- **`_disable_art_image_` process-name suffix is NORMAL** on this device
  (Firefox + Google apps have it) — NOT a crash cause.
- **No live `GeckoSession.canGoBack()`** in the public API (verified at
  build time). Back-history state comes from the entity flag updated by
  `NavigationDelegate.onHistoryStateChange`. There IS `session.goBack()`.

---

## 7. UI/UX pitfalls (this fork's customizations)

- **Rounding image corners:** an ImageView's `background` drawable is NOT
  clipped by the loaded bitmap — the bitmap paints over it. Use a
  `ShapeableImageView` + `shapeAppearanceOverlay` (it clips content), or a
  Glide `RoundedCorners` transform on the load. Also: a small thumbnail at
  the SAME dp radius as its big card reads MORE rounded (proportionally) —
  use a slightly smaller radius on the thumb (e.g. card 14dp, thumb 10dp).
- **Moving controls between toolbars:** keep the button IDs + classes the
  same and route clicks through one controller (`BottomNavigationBar`).
  Every fragment handler keyed on `R.id.*` keeps working with zero changes.
- **Fragment-view binding trap (the dead-buttons bug):** during fragment
  navigation (home ↔ browser) BOTH fragments' views coexist in the window.
  `getRootView().findViewById()` binds to the WRONG fragment's buttons and
  the listeners die when that view detaches. ALWAYS bind from the fragment's
  OWN root view (`bindButtons(fragmentRoot)`), never the window root.
- **GeckoView back-history flag can be stale on freshly-opened tabs** — the
  entity `canGoBackward` flag is updated by navigation events; on a brand-new
  tab it can lag. Prefer the live source when available.
- **Address-bar back must not exit the app:** with the field focused (overlay
  not visible), back should clear focus/editing, not fall through to default
  (which finishes the activity on the root destination).

---

## 8. Rebrand notes (Firedown → Waves)

- The download folder constant lives in `StoragePaths.FOLDERNAME` — ONE
  change renames every `Download/Firedown` path. Hardcoded
  `"primary:Download/Firedown"` doc URIs also exist (SettingsFragment,
  DownloadFragment, WebBookmarkFragment).
- Functional URLs stay (`firedown.app`, `storage.firedown.app`,
  `github.com/solarizeddev/firedown`, `firedown.js`) — real services.
- User-facing strings live in `values/strings.xml` + 75 locale files —
  replace the capital-F brand token, never the lowercase URL tokens.
- The launcher icon sits on the exact dark homepage surface `#131315`
  (`md_theme_surface` in values-night) — keep it fixed across themes.
- Style names (`Firedown.Widget.*`, `Firedown.Divider.*`) are internal code
  identifiers, NOT user-visible — leave them.

---

## 9. What we have NOT solved yet (open items)

- **Firefox AMO gallery install** — NOT possible via GeckoView's public API
  (AMO requires a Firefox-specific install handshake). BUT the engine already
  runs 5 built-in WebExtensions (uBlock, youtube, webrequests, icons, nostr,
  p2pshare) via `WebExtensionController.ensureBuiltIn`, and `install()` from a
  file URI is available — so an in-app ".xpi installer + manager" screen is
  the realistic "gallery" path.
- **Background media auto-pause** on tab switch / minimize / exit — planned
  (Item 3), not started.
- **App package rename** `com.solarized.firedown` → new — deferred (risky,
  touches every file). The user-visible name is Waves without it.
- **Potential SECOND rename Waves → Eunoia** — user is considering it. It is
  cheap NOW (same string sweep already done once); needs a logo decision.
- **Voice-search / image-search edge cases** — the mic uses the system
  `RecognizerIntent`; image search opens Google Lens via the photo picker.
  Both are v1.

---

*Keep this file updated. Every hour we spent here is in one of these
sections — the next session should start by reading this.*
