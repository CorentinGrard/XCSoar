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
| M1 | Headless core behind the C API, replay tests on the host | ☑ done |
| M2 | Android skeleton: replay an IGC, see live InfoBoxes | ◐ demo works on the emulator |
| M3 | Moving map in the new app | ◐ map, gestures, hold card on the phone |
| M4 | Flyable with internal GPS: task, Go To, MC, airspace warnings, vario audio, IGC logging | ◐ all features in; field test and L5 left |
| M5 | External devices: Bluetooth / BLE / USB, drivers, declaration | ☐ on hold: no hardware ([not done](#not-done-no-external-hardware)) |
| M6 | Settings, profiles, data management | ☐ |
| M7 | Cockpit polish and beta release | ☐ |
| M8 | iOS | ☐ |

**Current focus:** M4 field test (IGC logging on a takeoff), L5 replay test.
M5 is on hold: no external device to test with.

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
- [x] JNI glue (`core/jni/CoreJni.cpp`): `JNI_OnLoad` initialises the Java
      classes the app ships (`Java::*`, `Context`, `Environment`, internal
      sensors); `nativeInit(context, permissionManager)`; create/start/stop/
      destroy, MacCready, replay; snapshot → direct `ByteBuffer` (no copy),
      events → Kotlin, on the core main thread attached to the JVM
- [ ] More Java I/O classes (Bluetooth/BLE/USB/IOIO ports, device
      detection, storage hotplug) with their `Initialise()` calls (M5)
- [x] `NativeXcsoarCore` (Kotlin) + `XcsoarApp` owning the core for the
      process; falls back to `FakeXcsoarCore` without the native library
- [x] Upstream Java I/O reused unchanged: Gradle copies 7 classes from
      `android/src` (InternalGPS, NonGPSSensors, ...) into generated sources
      (D13); `org.xcsoar.AppPermissionManager` implements their
      package-private `PermissionManager` and shows the system dialog
- [x] Gradle task `nativeCore` runs XCSoar's `make` (via `env.sh`) and
      packages `libxcsoar_core.so` (`-Pxcsoar.abis=arm64-v8a,x86_64`)
- [x] Devices open like in XCSoar: the core runs the backend half of
      `ProcessTimer` every 500 ms (D14), including the first device open
- [x] **On the emulator (arm64, Android 16):** core starts, internal GPS
      asks for the location permission, a GPS fix shows up (1,147 m MSL for
      1,200 m ellipsoidal: the geoid correction), the bundled IGC replays
      through the real glide computer (take-off, tow climb, "Next (takeoff)")
- [~] `:core`: `XcsoarCore` interface, `FlightState`, `SnapshotDecoder`,
      `FakeXcsoarCore` (synthetic cruise/thermal flight) done;
      `NativeXcsoarCore` (JNI) next
- [x] **L3** `SnapshotDecoderTest` (8 tests): every field and validity bit,
      buffer position, bad version/size, and the Kotlin constants
      (`XCS_GCE_*`, `XCS_VALID_*`, `XCS_FLAG_*`, API version) parsed from
      `xcsoar_core.h` so the two sides cannot drift
- [x] Snapshot v2 (struct_size 368): speed to fly, current and required
      L/D, time to the next point, task speed, current and last thermal,
      with the conditions of XCSoar's InfoBoxes; the design's cruise and
      circling boxes, ETE on the next waypoint card, "Set MC from thermal"
- [x] Units (Menu → Units): XCSoar's presets and a unit per group,
      saved in the profile and used by the map too (`xcs_units_get` /
      `_set` / `_preset`, `core/host/CoreUnits.cpp`).  The app formats
      with XCSoar's rules in Kotlin (`UnitFormatter`, D9), checked against
      799 outputs of the C++ Formatter (`core/test/FormatGolden.cpp` →
      `format-golden.txt`, compared by `make core-check`).  MacCready
      steps in the lift unit (0.1 m/s, 0.2 kt, 10 fpm).  Checked on a
      Pixel 7 (British preset, MC in knots, kept after a restart)
- [x] Flight screen v0: status line, 10 InfoBoxes, MC −/+, sunlight and
      night themes; screen kept on
- [~] Flight screen v1 after the design canvas "XCSoar — modern cockpit"
      (D16): daylight theme, bundled Barlow fonts, map area with next
      waypoint card, wind and status chips; vario instrument, 6 InfoBoxes
      per mode, MC stepper, final glide tile, Cruise/Circling switch
      (follows the flight mode, a tap overrides until the next change),
      menu (replay demo / stop). Portrait bottom sheet, landscape side
      panel. Missing until the snapshot grows: task leg/ETE, speed to
      fly, L/D, thermal stats, thermal assistant, climb per turn
- [x] Data files screen (Menu → Data files): pick a map (.xcm), airspace
      (OpenAir/.sua) and waypoint file with the system picker; copied into
      the XCSoarData folder of its kind, saved in the profile and loaded at
      once (`xcs_set_data_file`, `xcs_get_data_status` JSON). Tested on a
      Pixel 7 with benalla9.xcm, FRA_FULL.xcm and the French OpenAir file
- [x] Download maps, airspace and waypoints from XCSoar's repository
      (Data files → Download): the app fetches the index and the file
      (https, SHA-256 checked when listed), the core parses the index
      (`xcs_repository_list`, XCSoar's parser) and loads the file
- [~] Replay: "Replay demo" (bundled IGC, 10×) on the ground; choosing a
      file, play/pause/speed later
- [x] **L4** Roborazzi screenshot tests of the flight screen against the
      fake core (cruise, circling, night, no data, landscape); reference
      images in `app/src/test/screenshots`, checked in CI
      (`:app:verifyRoborazziDebug`; update with `:app:recordRoborazziDebug`)
- [x] **Demo:** replay an IGC on the phone and watch the InfoBoxes update
      (emulator; your phone next)

## M3 — Moving map
- [x] `core/map`: `MapWindow` + renderers with the OpenGL ES canvas on an
      EGL surface of the app, drawn on the core main thread (D17); looks and
      fonts as in MainWindow; icons are XCSoar's drawables
      (`make core-drawables`, packaged by Gradle) decoded by
      `org.xcsoar.CoreGraphics`; text via the reused `TextUtil.java`
- [x] `xcs_map_attach` / `detach` / `set_aircraft_position` / `zoom`;
      SurfaceView behind the Compose cards, attach on every size change,
      detach before the surface goes away; redrawn after every snapshot
- [x] Zoom buttons (XCSoar's scale list); north up; aircraft in the middle
      of the area the cards leave free
- [x] Gestures: pan and pinch-zoom (`xcs_map_pan`, `xcs_map_scale`), merged
      in the view model while the core draws; panning stops following the
      aircraft, a centre button (`xcs_map_follow`) brings it back
- [x] Orientation from the profile like GlueMapWindow (track, north,
      target, heading, wind up), button cycling north/track/target up
      (`xcs_map_set_orientation`, saved in the profile); look-ahead glider
      position in cruise; separate circling zoom when the profile has it
- [x] New data drawn at most 4 times per second (was one frame per snapshot,
      ~10/s); interactions draw at once
- [x] Fix: start without GPS fix and home waypoint crashed (no position);
      the map now starts on home, else on the middle of the map file
- [~] Daylight map look matching the design: Pastel terrain offered;
      airspace and waypoint styles still XCSoar's
- [x] Map settings screen (Menu → Map): terrain on/off and colours
      (XCSoar's ramps; Pastel is closest to the design), topography,
      trail length (`xcs_map_set_option`, XCSoar's profile keys)
- [x] Hold the map (arms after 500 ms, commits on lift-off) →
      `xcs_map_items_at` (XCSoar's map item builder, JSON) → floating card,
      not modal: terrain, airspace with class and limits, waypoints with
      elevation and frequency, task points, thermals, traffic
- [ ] Overlays in Compose: vario bar, final-glide bar, wind arrow, status icons
- [~] Measure: frame time, CPU, battery over a 1-hour replay.  First look
      (debug build, Pixel 7, on the ground): core thread incl. map ~4 % of
      a core, Android main thread ~30 % (debug Compose, ~14 ms per frame).
      The flight screen now recomposes at most 5×/s.  Next: a release
      (R8) build for real numbers; simpleperf needs a rooted or
      perf-enabled device
- [ ] **Demo:** replay with a live moving map + InfoBoxes

## M4 — Flyable with the internal GPS
- [x] Location permission + foreground service: `FlightService`
      (foregroundServiceType "location", partial wake lock, low-importance
      notification back to the flight screen) starts whenever the app comes
      to the front with the location permission, or right after it is
      granted; removing the app from the recent apps stops it.  The core
      stays in `XcsoarApp`.  Checked on a Pixel 7 (Android 16): GPS fixes
      keep reaching the app with the screen off and another app in front
- [x] Internal GPS + barometer → core: upstream's `InternalGPS.java` and
      `NonGPSSensors.java` (pressure through XCSoar's Kalman filter: vario,
      static pressure).  Menu → Flight setup → QNH (850–1300 hPa, 1 hPa /
      0.01 inHg steps, like XCSoar's dialog) and the barometric altitude;
      snapshot `qnh` / `static_pressure` (struct_size 416),
      `xcs_set_qnh`.  The baro altitude is only valid once QNH is known,
      so until then XCSoar uses the GPS altitude.  Checked on a Pixel 7
      (ICP20100 sensor): live vario indoors, XCSoar's automatic QNH set
      1026 hPa about 25 s after start, flight screen on the baro altitude.
      Not in the screen yet: XCSoar's forecast temperature
- [x] MC / ballast / bugs: MC −/+ on the flight screen; Menu → Flight
      setup for water ballast (5 l steps, Empty / Full, up to the plane's
      maximum), bugs (5 % steps, 0–50 % like XCSoar) and the wing loading.
      Snapshot fields `ballast`, `max_ballast`, `bugs`, `wing_loading`
      (struct_size 400), `xcs_set_ballast` / `xcs_set_bugs` (XCSoar's
      ActionInterface, so devices get them too).  Ballast dump timer and
      crew mass: later, with the plane settings (M6)
- [x] Go To: from the map's hold card and from a waypoint list (Menu → Go
      to waypoint, or tap the next waypoint card): nearest first, landable /
      airports / all, name search, distance, bearing and arrival height
      (`xcs_waypoints_search`: XCSoar's WaypointFilter, WaypointListBuilder
      and CalculateWaypointReach; `xcs_goto_waypoint`)
- [x] Task (Menu → Task), like XCSoar's task manager: the pilot edits a
      copy that replaces the active task on "Done" (validated; saved as
      Default.tsk).  Points from the waypoint list, order, remove; zone
      type and size per point; task type (racing, AAT, MAT, FAI…) and AAT
      minimum time; load from XCSoarData (.tsk, SeeYou .cup tasks) and
      save as .tsk; Next / Previous / Restart on the active task.  C API
      `xcs_task_get` / `xcs_task_edit` / `xcs_task_list_files` /
      `xcs_task_load` / `xcs_task_save` (`core/host/CoreTask.cpp`).
      Checked on a Pixel 7.  Later: the task on the map's own overlays,
      sector angles, start open/close times, optional starts, AAT targets
- [~] Airspace warnings: banner above the cards (red inside, orange
      ahead or crossing the task, time and distance to it), "Ack" until it
      changes and "Day" (`xcs_get_airspace_warnings`,
      `xcs_airspace_acknowledge`, XCSoar's warning manager); polled every
      second and on airspace events.  Tested live (inside CTR Montpellier).
      A tone (alarm stream) and vibration once per new warning and again
      when it gets worse (ahead → inside)
- [x] Vario / speed-to-fly audio: XCSoar's synthesiser through its own
      OpenSL ES player (D18), switched by a speaker button over the map
      (`xcs_sound_set_option` / `xcs_sound_get_option`, saved in the
      profile, off by default like XCSoar); the volume keys set the media
      volume.  Checked on a Pixel 7 during a replay.  Volume, mode
      (auto / vario / STF) and dead band settings: later (M6)
- [~] IGC logging on by default; flight list with share/export.  The
      core starts XCSoar's IGC logger on takeoff and stops it on landing
      like default.xci's "AutoLogger" events (profile setting, on by
      default; never for replays): `CoreProcessGlideComputerEvent()`.
      Menu → Flights lists `XCSoarData/logs/*.igc`, newest first, and
      shares one through a FileProvider.  List and share checked on a
      Pixel 7; **auto start/stop not yet seen on a real takeoff** (needs
      a car/walk test or test GPS fixes)
- [x] Analysis pages (Menu → Analysis): barograph, climb history, task
      speed and contest, from XCSoar's FlightStatistics and contest
      results (`xcs_get_analysis`, `core/host/CoreAnalysis.cpp`), charts
      drawn in Compose; the numbers of upstream's captions (working band,
      ceiling and climb trends, Vave / Vest, distance / score / time /
      speed per contest result).  Refreshed every 5 s while shown.
      Checked on a Pixel 7 during a replay.  Not yet: upstream's other
      pages (vario histogram, thermal band, wind, polar, MacCready,
      temperature trace, task, airspace cross-section) and the contest
      path on the terrain map (it is drawn on a plain background)
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

### Not done: no external hardware

On hold (2026-10-03): the pilot owns no external device, so nothing in M5
gets built or tested until one is available.  Upstream XCSoar code that
the core already links but that has never run in this app:

- Ports: Bluetooth classic, BLE (Nordic UART, HM-10, BLE sensors), USB
  serial, IOIO, TCP/UDP, and their Java classes (`BluetoothHelper`,
  `UsbSerialHelper`, …) with their `Initialise()` calls
- Device configuration: the profile's `DeviceA`..`DeviceF`, driver and
  baud rate; a device list and setup screen; reconnect
- Drivers sending settings out: `xcs_set_mac_cready`, `xcs_set_ballast`,
  `xcs_set_bugs` and `xcs_set_qnh` call `PutMacCready` / `PutBallast` /
  `PutBugs` / `PutQNH` on the devices, and XCSoar's automatic QNH calls
  `PutQNH` / `PutElevation`, but no device has ever received them
- Settings coming in from devices (QNH, MC, ballast, bugs from a vario
  via `ApplyExternalSettings`): untested
- FLARM: traffic on the map, radar screen, collision alarm banner and
  sound (needs snapshot or event API for traffic)
- Task declaration to loggers (LX, FLARM, Volkslogger, …) and IGC
  download from loggers
- Airspeed, TE vario and netto from an external vario: the snapshot has
  the fields, never seen with real data

## M6 — Settings & data
- [x] Aircraft & crew: when the app opens (not when it restarts in the
      air), the pilot picks the plane, the last one flown first, and for
      a two-seater the co-pilot (solo, a recent one or a new one); also
      Menu → Aircraft & crew.  Planes are XCSoar's .xcp files (profiles/
      planes): registration, competition ID, type, built-in polar,
      WeGlide type and a new "DoubleSeater" key, set from WeGlide's
      `double_seater` when the type is picked.  The co-pilot goes to the
      IGC header (HFCM2CREW2); the profile keeps the recent ones
      ("MobileCoPilots") and the plane of the last take-off
      ("MobileLastFlownPlane").  C API `xcs_planes_*`, `xcs_plane_*`,
      `xcs_crew_*` (`core/host/CorePlanes.cpp`).  Checked on a Pixel 7
- [~] WeGlide: Menu → Pilot & WeGlide (pilot name, pilot ID, date of
      birth, XCSoar's own profile keys); aircraft types downloaded from
      WeGlide; Flights → "Upload to WeGlide" with upstream's UploadFlight,
      the aircraft found from the IGC file's registration.  `xcs_weglide_*`
      (`core/host/CoreWeGlide.cpp`), network calls on a worker thread.
      Aircraft list and type lookup checked on a Pixel 7; **no real upload
      yet** (needs the pilot's own account and flight)
- [ ] Settings screens backed by `xcs_settings_*` JSON sections: units, polar /
      plane, safety heights, airspace filters, audio, map, InfoBox pages
- [ ] Profiles: list, switch, import existing `.prf`
- [ ] File manager: download waypoints, airspace, maps from the XCSoar repository
- [ ] Translations: convert `po/*.po` → Android `strings.xml` at build time

### Not done: aircraft, crew and WeGlide

- Co-pilot on WeGlide: the upload form has no co-pilot; WeGlide's
  `PATCH /v1/flightdetail/{id}` (`co_user_id`, `co_user_name`) needs a
  logged-in WeGlide account (OAuth), which XCSoar does not have.  The
  co-pilot is only in the IGC header
- Automatic upload after landing (upstream's "WeGlideAutomaticUpload" is
  for flights downloaded from loggers)
- Remembering which flights were uploaded (the list forgets on restart;
  WeGlide refuses a second upload of the same file)
- "Last flown" is recorded at take-off: not yet seen on a real take-off
- Plane editor: no custom polar, masses, ballast, handicap or speed
  editing yet (upstream's PlanePolarDialog import / custom polar)
- WeGlide tasks (upstream's WeGlideTasksPanel / DownloadTask)

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

- 2026-10-03 — Aircraft & crew picker at app start (last plane flown,
  co-pilot for two-seaters), plane editor with built-in polars and
  WeGlide types, Pilot & WeGlide settings, upload to WeGlide from the
  flight list.  Fixed: curl crashed on Android (CertificateUtil was not
  initialised).  TestCoreApi 178 checks, TestPlanes 38.  Checked on a
  Pixel 7 with a test plane (removed again).  Next: a real WeGlide
  upload by the pilot, field test.

- 2026-10-03 — CI: the Gradle cache key is computed before the build
  (its post step failed and skipped saving every cache).  Barometer: the
  phone's pressure sensor already reached the core through upstream's
  code; QNH added to the C API and Flight setup, checked on a Pixel 7
  with XCSoar's automatic QNH.  TestCoreApi 146 checks pass.  M5 on hold
  (no hardware); what is not done is listed under M5.  Analysis pages
  (barograph, climb, task speed, contest) through `xcs_get_analysis`,
  checked on a Pixel 7 during a replay; TestCoreApi 153 checks.  Next:
  field test, L5.

- 2026-10-02 — `FlightService`: the flight computer keeps running with the
  screen off or another app in front (location foreground service, wake
  lock, notification). Checked on a Pixel 7. Flight setup screen: water
  ballast and bugs through the C API (TestCoreApi extended; not run
  locally, the Fedora host lacks fmt-devel and friends: CI runs it).
  Vario sound on/off (upstream's OpenSL player, D18). IGC logger starts
  on takeoff and stops on landing; flight list with Share. Next: see the
  logger start on a real takeoff, then the task.
- 2026-10-02 — Task: view, edit, zones, types, load/save, advance/restart
  (C API + task screens), checked on a Pixel 7. TestCoreApi extended
  (not runnable on this Fedora host: thirdparty.py has no native Linux
  target; needs the -devel packages). Next: field test, then M5.
- 2026-10-02 — `make core-check` runs on this Fedora host (139 TAP checks,
  golden replays, Formatter golden all pass) after fixing the host build
  of the task/units API and a test crash.  Fedora recipe: the -devel
  packages incl. libsodium, dbus, netcdf, libgeotiff, proj; a
  `lua5.4.pc` alias (`Requires: lua`) on `PKG_CONFIG_PATH`; and
  `LIBGEOTIFF_USE_PKG_CONFIG=y` (headers in /usr/include/libgeotiff).
- 2026-10-02 — Units: settings screen, XCSoar's formatting rules in
  Kotlin with a golden test against the C++ Formatter (D9 done). Next:
  field test, then M5.

- 2026-10-02 — Data files screen (map/airspace/waypoints via the system
  picker) and XCSoar's moving map in the app, both working on a Pixel 7
  with the French map and airspace. Upstream: `TextUtil` uses the calling
  thread's JNIEnv. Next: map gestures, redraw only on change, design look.

- 2026-10-02 — Flight screen v1 from the design canvas: theme, components,
  phone and landscape layouts against the current snapshot; L4 screenshot
  tests. Linux build: needs a full JDK (Fedora's default is a JRE without
  `javac`; Temurin 21 works) and `ANDROID_HOME`. Next: snapshot v2 (speed
  to fly, L/D, task progress, climb stats) to fill the design's gaps.

- 2026-09-30 — M2: Gradle project, Kotlin binding, JNI glue, flight screen. The
  app runs XCSoar's engine on the emulator: internal GPS (with permission
  prompt) and IGC replay. Found and fixed: devices were never opened
  (ProcessTimer split, D14), core objects ignored header changes (depfiles),
  a core start failure crashed the app. Next: your phone, CI for mobile/.

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
