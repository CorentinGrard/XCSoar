# XCSoar Mobile — Plan & Progress

Design: [ARCHITECTURE.md](ARCHITECTURE.md) · Decisions: [DECISIONS.md](DECISIONS.md)

Legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` dropped (say why)

Every milestone ends with a **demo** you can run on your Android phone, and
nothing counts as done without its tests (levels L0–L5 in ARCHITECTURE §6).

---

## Status

| Milestone | Goal | State |
|---|---|---|
| M0 | Foundations: build, branch, CI baseline | ◐ reference app install left |
| M1 | Headless core behind the C API, replay tests on the host | ◐ in progress |
| M2 | Android skeleton: replay an IGC, see live InfoBoxes | ☐ |
| M3 | Moving map in the new app | ☐ |
| M4 | Flyable with internal GPS: task, Go To, MC, airspace warnings, vario audio, IGC logging | ☐ |
| M5 | External devices: Bluetooth / BLE / USB, drivers, declaration | ☐ |
| M6 | Settings, profiles, data management | ☐ |
| M7 | Cockpit polish and beta release | ☐ |
| M8 | iOS | ☐ |

**Current focus:** M1 (C API + golden replay tests next)

---

## M0 — Foundations
- [x] Fork `XCSoar/XCSoar` on GitHub; `origin` → fork, `upstream` → XCSoar
- [x] Create a long-lived `mobile` branch (pushed to `origin/mobile`, 2026-09-30);
      decide on a regular upstream-merge routine
- [x] macOS prerequisites: `./ide/provisioning/install-darwin-packages.sh BASE MACOS`
      plus `cmake ninja ccache` (the script only installs cmake/ninja for `IOS`,
      but the MACOS target builds SDL2 with them)
- [x] `git submodule update --init --recursive` (lib/glm, android/UsbSerial,
      android/ioio: a plain clone leaves them empty)
- [x] Host build works on macOS. Build command:
      `SDKROOT=/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk gmake -j$(sysctl -n hw.ncpu) USE_CCACHE=y check`
      `SDKROOT` is needed because Command Line Tools 27 (macOS 27 SDK) is newer
      than Xcode 26.6's linker; the host helper tools otherwise fail to link.
      Updating Xcode to 27 should remove the need.
- [x] Install NDK r26d (26.3.11579264) alongside 27: upstream pins r26
      (the Makefile picks `$ANDROID_SDK/ndk/26.*` automatically)
- [x] `make check` passes on the host (**L0 baseline**): 2026-09-29, upstream
      `87f579c695`, 123 test programs, 11,642 checks, all PASS. The test run takes
      about 33 s; a rebuild + check with a warm ccache takes about 1 min 40 s
- [x] Android native build works (NDK r26d): 2026-09-30, `libxcsoar.so` arm64
      (28 MB stripped, 247 MB with debug info, 53 JNI exports). Needs `brew install vorbis-tools
      automake autoconf libtool` (`oggenc` converts the sounds; SQLite is
      patched and then autoreconf'd) and the same `SDKROOT=…` export as the macOS build:
      without it, third-party configure scripts (zlib) silently fail their checks.
      Also: build-tools 36.0.0 is expected, 36.1.0 works via
      `ANDROID_BUILD_TOOLS_DIR=$HOME/Library/Android/sdk/build-tools/36.1.0`; and
      use a **HotSpot** JDK (Android Studio's JBR). The default IBM Semeru
      (OpenJ9) JDK crashes in `d8` with a GC assertion and writes a 9 GB core dump.
      Command:
      `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
      PATH="$JAVA_HOME/bin:$PATH" SDKROOT=… gmake -j$(sysctl -n hw.ncpu)
      TARGET=ANDROIDAARCH64 USE_CCACHE=y ANDROID_SDK=$HOME/Library/Android/sdk
      ANDROID_BUILD_TOOLS_DIR=$HOME/Library/Android/sdk/build-tools/36.1.0
      ./output/ANDROID/arm64-v8a/dbg/bin/libxcsoar.so`
      Shortcut: `source mobile/tools/env.sh` then `xmake …` (sets all of the above).
- [ ] Install upstream XCSoar from Play Store / F-Droid on your phone (reference for parity)
- [x] CI: `.github/workflows/mobile.yml` runs `make check` (TARGET=UNIX, Debian
      trixie, same setup as upstream) on `mobile`/`mobile-*` pushes and PRs.
      First run green on 2026-09-30 (8 min 21 s with cold caches). Upstream's
      `build-native.yml` only runs on `master`/release branches.

## M1 — Headless core (`core/`, C++)
Build (host): `source mobile/tools/env.sh && xmake VFB=y TARGET_DIR=MACOS_CORE core`
Run: `./output/MACOS_CORE/bin/CoreSmoke DATA_DIR [FLIGHT.igc]`

- [x] `build/core.mk`: every non-UI source of the main program goes into
      `core-candidates.a`; programs linked against it only pull in what their
      entry points reach, so every leftover UI call shows up as an undefined
      symbol (the seam list). 411 → 0 undefined symbols.
- [x] Seams implemented in `core/host` (link-time, D5):
      `Protection.cpp` (Trigger*, CreateCalculationThread, Suspend/Resume),
      `InputEvents::processGlideComputer` / `processNmea`, `Message::AddMessage`,
      `ShowMessageBox` (answers "yes/OK", reports the text as a message),
      `InfoBoxManager::SetDirty/ProcessTimer` (no-op), `AppendOverlayTitle`,
      the `*FileChanged` flags. All forward to a `CoreListener`.
- [x] `CoreStartup()` / `CoreShutdown()`: headless `Startup()`/`Shutdown()`
      (same order; default profile; terrain loaded synchronously)
- [x] Core main thread = XCSoar's own UI event loop in the headless `VFB`
      flavour (poll backend, no window). Needed because `Replay` and device
      `Descriptor`s use `UI::Timer`/`UI::Notify`. Merge/calculation results are
      handed over with `UI::Notify` (coalescing), then `CoreReceive` does the
      backend half of `UIReceiveBlackboard` (blackboard copy + listeners,
      settings from devices, device notification, task events) (D12)
- [x] Upstream refactor: `src/ActionInterface.cpp` split; MainWindow/page
      code moved to `src/ActionInterfaceUI.cpp` (pure move, D11)
- [x] Upstream fixes for the clang + no-OpenGL build: `Audio/Sound.cpp`
      unused parameter, `Terrain/RasterRenderer.cpp` unused constant,
      `ui/event/shared/Event.hpp` missing `<cstddef>`
- [x] `CoreSmoke`: starts the core, replays an IGC (timed, 200×) and prints
      listener counts + final state. Replay of `01lz1hq1.igc`: take-off,
      flight-mode and alternate events arrive; runs in ~100 s
- [x] `make all check` still green after the upstream changes (123 programs,
      11,642 checks, 2026-09-30)
- [ ] Topography, FLARM database and `AllMonitors` equivalents: topography moves
      to the map (M3); monitors become core events (task advance, airspace
      warnings, traffic) for the UI to present
- [x] Android core library: `xmake TARGET=ANDROIDAARCH64 core` →
      `libxcsoar_core.so` (22 MB stripped, linked with `--no-undefined`),
      exports the `xcs_*` API and the 27 JNI entry points of device/sensor
      I/O. The core runs XCSoar's Android event queue itself
      (`CoreEventQueue`, D12); Android globals (`context`, Bluetooth/USB/IOIO
      helpers) are defined by the core and filled by the app's JNI glue (M2)
- [x] Export only `xcs_*` and `Java_*` from `libxcsoar_core.so`
      (`core/api/libxcsoar_core.map`): 2,762 → 37 exported symbols. Add
      `JNI_OnLoad` there in M2
- [ ] Not yet run on a device: needs the JNI glue that creates `Context`
      and the helpers (first task of M2)
- [x] `core/api/xcsoar_core.h` v1: create/start/stop/destroy, snapshot and
      event callbacks (always on the core main thread), `xcs_get_snapshot`,
      `xcs_set_mac_cready`, `xcs_replay_start/stop` (timed) and
      `xcs_replay_run` (deterministic, every fix). Commands run on the core
      main thread; the caller waits. One core per process (XCSoar globals)
- [x] `xcs_flight_snapshot` v1 (280 bytes, layout locked by `static_assert`):
      time, flight time, position, track, ground/air speed, GPS/baro/nav
      altitude, terrain/AGL, vario/average/netto, wind, MC, next point
      (name/distance/bearing/altitude difference), final glide; validity bits
      and flags (flying, circling, final glide, replay, real GPS)
- [x] Stable public event codes (`xcs_gce`), mapped from the internal GCE
      list with a `static_assert` that fails when upstream adds one
- [x] Upstream: `InitThreadDebug()` on all platforms (thread/Debug);
      optional per-fix callback in `Replay::ProcessAllFixes()`;
      `TriangleContest` use-after-free fix
- [x] **L1** `TestCoreApi` (TAP, 45 checks): arguments, state errors,
      single-instance, deterministic replay (take-off, climb, cruise events;
      MC kept), callbacks only on the core main thread, 4 threads × 50
      concurrent commands, restart after stop
- [x] L1 under ASan + TSan (`SANITIZE=address|thread`; macOS needs
      `CFLAGS=-Wno-deprecated-declarations` for the vendored shapelib).
      ASan found a real upstream bug: `TriangleContest` kept using trace
      points freed by `Trace::Thin()` while resuming its search → fixed
      (restart the search when the master trace changed, like
      `ContestDijkstra`). TSan: 5 known upstream races (debug thread flag,
      event loop wake flag, curl shutdown), suppressed with reasons in
      `core/test/tsan.supp`; none in core code. Golden replays also clean
      under ASan
- [ ] Upstream TAP regression test for the `TriangleContest` fix (before
      sending it upstream)
- [ ] Run L1 + L2 under ASan/TSan in CI
- [x] **L2** golden replays: `xcs-replay` (C API only, JSON lines) +
      `core/test/check_golden.py` (per-field tolerances, `--update`) on 4 IGC
      files; deterministic (byte-identical across runs)
- [x] `make VFB=y core-check` runs L1 + L2
- [x] CI job `core-api` runs `make TARGET=UNIX VFB=y core-check` on Linux
      x86_64: green (golden files from macOS arm64 hold within tolerance).
      Linux needed: `--start-group` (GNU ld), `-fno-finite-math-only` at the
      API boundary (GCC keeps `-ffinite-math-only`), vario audio init (ALSA).
      Failures are posted as annotations (readable without log access)

## M2 — Android skeleton (`mobile/`)
- [~] Gradle project (Gradle 9.8 wrapper, AGP 9.4.1, Kotlin 2.4.20, Compose
      BOM 2026.09, version catalog): `:core` (pure Kotlin/JVM) builds and its
      tests pass; `:app` needs Android SDK Platform 37 (compileSdk 37 is
      required by the current AndroidX libraries). `:platform` later
- [ ] Gradle task calls `make` for `arm64-v8a` + `x86_64` and packages `libxcsoar_core.so`
- [ ] JNI glue (`core/jni/`):
      - `JNI_OnLoad`: the non-UI half of `InitNative()` in
        `src/Android/Main.cpp` (`Java::*`, `Context`, `Environment`, sensor,
        port and listener classes, `PortBridge`, `SAFHelper`); matching
        deinit; add `JNI_OnLoad` to `libxcsoar_core.map`
      - `init(context, permissionManager)`: create `Context` and the
        Bluetooth/USB/IOIO helpers the way `runNative()` does
      - snapshot → direct `ByteBuffer`, events → Kotlin callback
- [~] `:core`: `XcsoarCore` interface, `FlightState`, `SnapshotDecoder`,
      `FakeXcsoarCore` (synthetic cruise/thermal flight) done;
      `NativeXcsoarCore` (JNI) next
- [x] **L3** `SnapshotDecoderTest` (8 tests): every field and validity bit,
      buffer position, bad version/size, and the Kotlin constants
      (`XCS_GCE_*`, `XCS_VALID_*`, `XCS_FLAG_*`, API version) parsed from
      `xcsoar_core.h` so the two sides cannot drift
- [ ] Unit formatting (m/ft, km/h/kt, m/s/kt, …) + tests
- [~] Flight screen v0: status line, 10 InfoBoxes (value size follows box
      height, dashes when invalid, lift/sink/task accents), MC −/+ (≥56 dp,
      fire on release), sunlight and night themes with colours named by
      function; screen kept on. Configurable content later
- [ ] Data directory: pick or import `XCSoarData` via SAF; copy a sample data set
- [ ] Replay screen: choose an IGC file, play/pause/speed
- [ ] **L4** screenshot tests of InfoBoxes (day/sunlight/night themes)
- [ ] **Demo:** replay an IGC on the phone and watch the InfoBoxes update

## M3 — Moving map
- [ ] `core/map`: build `Renderer` + `MapWindow` drawing code with the
      `ui/canvas/opengl` backend against an EGL surface, headless (no `ui/window`)
- [ ] `xcs_map_attach_surface` / `detach` / resize; render thread tied to the
      app lifecycle (pause/resume, rotation)
- [ ] `SurfaceView` in Compose; gestures (pan, pinch-zoom, rotate/north-up) → `xcs_map_gesture`
- [ ] Map settings: orientation, zoom, trail, terrain/topography toggles
- [ ] Long-press → `xcs_map_items_at` → bottom sheet (waypoint, airspace, traffic)
- [ ] Overlays in Compose: vario bar, final-glide bar, wind arrow, status icons
- [ ] Measure: frame time, CPU, battery over a 1-hour replay
- [ ] **Demo:** replay with a live moving map + InfoBoxes

## M4 — Flyable with the internal GPS
- [ ] Location permission + foreground service (GPS keeps running with screen off)
- [ ] Internal GPS + barometer → core (reuse `InternalGPS.java`, `NonGPSSensors.java`)
- [ ] MC / ballast / bugs quick controls
- [ ] Waypoint search (nearest, name, type) + Go To
- [ ] Task: view, edit (points, sectors, AAT), load/save `.tsk`/`.cup`, advance/restart
- [ ] Airspace warnings: banner + acknowledge actions + sound
- [ ] Vario / speed-to-fly audio (reuse `src/Audio` synthesizer through an AAudio sink)
- [ ] IGC logging on by default; flight list with share/export
- [ ] Analysis pages: barograph, climb history, task speed, contest (charts drawn
      in Compose from JSON data)
- [ ] **L5** instrumented replay test on an emulator in CI (nightly)
- [ ] **Demo / field test:** car or walk test, then a real flight *next to* a
      certified instrument or the upstream XCSoar app

## M5 — External devices
- [ ] Port abstraction: `xcs_port_ops` (platform supplies a byte stream)
- [ ] Reuse `android/src` port classes: Bluetooth classic, BLE (Nordic UART, HM-10),
      USB serial, TCP/UDP
- [ ] Device list and setup screen: pick a driver, baud rate, connect/reconnect
- [ ] FLARM: traffic on the map, radar screen, collision alarm banner + sound
- [ ] Task declaration to loggers (LX, FLARM, Volkslogger, …)
- [ ] Test with real hardware you own (list it here): …

## M6 — Settings & data
- [ ] Settings screens backed by `xcs_settings_*` JSON sections: units, polar /
      plane, safety heights, airspace filters, audio, map, InfoBox pages
- [ ] Profiles: list, switch, import existing `.prf`
- [ ] File manager: download waypoints, airspace, maps from the XCSoar repository
- [ ] Translations: convert `po/*.po` → Android `strings.xml` at build time

## M7 — Polish & beta
- [ ] Cockpit UX pass: touch targets ≥ 56 dp, sunlight contrast audit,
      one-handed quick actions, landscape + portrait, tablet layouts
- [ ] Screen always on, orientation lock, brightness shortcut
- [ ] Crash reporting (opt-in, privacy-friendly) + native crash symbolication
- [ ] F-Droid / Play internal testing track
- [ ] User docs: migration guide from XCSoar

## M8 — iOS
- [ ] Move `:core` + ViewModels to Kotlin Multiplatform; UI to Compose Multiplatform
- [ ] Build `libxcsoar_core` for iOS (the Makefile already supports iOS targets)
- [ ] CoreLocation / CoreBluetooth port implementations

---

## Log
Newest first. One line per session: what was done and what's next.

- 2026-09-30 — C API v1 (`xcsoar_core.h`), L1 contract tests (45) clean under
  ASan/TSan, L2 golden replays (4 flights, deterministic). ASan found and we
  fixed an upstream use-after-free in `TriangleContest`. Next: CI for the core,
  Android headless flavour, then M2.

- 2026-09-30 — M1 started: headless core links (411 → 0 seams), starts/stops,
  replays an IGC with glide computer events. Split `ActionInterface.cpp` (D11),
  core main thread on the VFB event loop (D12). Next: C API + golden tests.

- 2026-09-30 — Android arm64 native build green. Fixes: automake, vorbis-tools,
  HotSpot JDK (OpenJ9 crashed in d8), build-tools 36.1.0 override; all wrapped
  in `mobile/tools/env.sh` (`xmake`). Next: commit + push `mobile` (first CI run),
  then M1 (`libxcsoar_core`).

- 2026-09-29 — macOS host build + `make check` green (L0 baseline). Fixes: brew
  cmake/ninja, submodules, `SDKROOT` for the Xcode/CLT mismatch. Next: CI job,
  NDK r26d, Android native build.

- 2026-09-28 — Moved to the fork (`CorentinGrard/XCSoar`), created the `mobile` branch.
  Aligned the design with `AGENTS.md` and `doc/architecture.rst`: `UI::Notify` seam,
  core main-thread event loop, UI colour/touch rules, TAP tests, D9–D10. Next: macOS build + `make check`.
- 2026-09-28 — Analysed the codebase, chose the architecture (D1–D8), wrote the plan. Next: M0.
