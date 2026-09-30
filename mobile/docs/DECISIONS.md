# Decisions

Short architecture decision records. Add new ones at the bottom; don't
rewrite old ones. If a decision is reversed, add a new record that replaces it.

Status values: **Accepted** · **Proposed** · **Superseded by #N**

---

## D1 — Keep the C++ core, rewrite only the UI
**Status:** Accepted (2026-09-28)

The core is about 170k lines of flight-critical code (engine, computer,
drivers, parsers) with years of edge-case fixes and a large test suite.
Rewriting it would be a multi-year project and a flight-safety risk. The UI
layer (`ui/`, `Form/`, `Widget/`, `Dialogs/`, `InfoBoxes/`, `Menu/`) is the
only part being replaced.

## D2 — Kotlin + Jetpack Compose, Android first; keep a path to iOS
**Status:** Accepted (2026-09-28)

Android is the primary target. Compose is the native, best-supported Android
UI toolkit, and the existing Java I/O code (Bluetooth, BLE, USB serial, IOIO,
sensors) can be reused as-is.

We start as a **plain Android project**, not Kotlin Multiplatform. The rule:
`:core` and the ViewModels must not import `android.*`. That keeps a later move
to KMP + Compose Multiplatform (for iOS) a mechanical change instead of a
rewrite.

*Rejected: Flutter.* It is a good cross-platform option, but it would add Dart
and a second FFI layer, cannot reuse the Java I/O code, and Android is the
priority.

## D3 — Plain C ABI between UI and core
**Status:** Accepted (2026-09-28)

`xcsoar_core.h` is plain C. There is no JNI in the API itself, and JNI glue
is a thin separate layer. This lets Kotlin/Native (iOS), Swift, or a desktop
test harness use the same API, and it keeps C++ types from leaking into the UI.

## D4 — Two data paths: POD snapshot (hot) and JSON (cold)
**Status:** Accepted (2026-09-28)

Live flight state is a fixed-layout struct pushed several times a second and
copied in one step. Everything else (task, waypoints, settings, lists) is JSON.
We will reconsider protobuf or FlatBuffers only if JSON becomes a measurable
performance or maintenance problem.

## D5 — Seams by link-time substitution in `core/host`
**Status:** Accepted (2026-09-28)

The backend reaches the UI only through `Protection.cpp`,
`InputEvents::processGlideComputer`, `Message::` and `CommonInterface::`. We
provide headless implementations of those symbols in `core/host`, the same
technique the C++ tests use in `test/src/Fake*.cpp`. We change existing
upstream files only when a symbol can't be replaced cleanly, and keep those
changes small enough to upstream.

## D6 — Reuse XCSoar's OpenGL map renderer first
**Status:** Accepted (2026-09-28)

This gives feature parity (terrain, airspace, task, topography, RASP, FLARM)
immediately. The map draws into a `SurfaceView` and Compose draws controls on
top. A MapLibre renderer may be evaluated later behind the same `xcs_map_*` API.

## D7 — Build the native library with the existing Makefile system
**Status:** Accepted (2026-09-28)

The Makefile already cross-compiles every third-party dependency for all
Android ABIs. Gradle calls `make` and packages the `.so`. We will not port
the build to CMake.

## D8 — Separate app ID, same data format
**Status:** Proposed

Application ID `org.xcsoar.mobile`, so the app installs alongside upstream
XCSoar. It reads the same `XCSoarData` layout and `.prf` profiles, so pilots
can migrate by copying files.

## D9 — The core is the single source for units and formatting
**Status:** Accepted (2026-09-28)

Upstream's rule is to reuse `Units/` and `Formatter/` and never build a
parallel API. Formatting every value in C++ and sending strings over JNI
several times a second would be wasteful, so the core exports its unit
conversion tables and per-unit format rules once (JSON), and Kotlin applies
them. A golden test compares Kotlin output with the C++ `Formatter` for the
same values, so the two can't drift apart.

## D10 — Upstream rules apply to the new code
**Status:** Accepted (2026-09-28)

`AGENTS.md` and `doc/architecture.rst` apply to everything in this project:
layer rules, the threading model (the core runs its own main-thread event
loop so `InMainThread()`, `UI::Notify` and `BlackboardListener` keep working),
TAP tests for C++ logic, the UI colour and touch guidelines, 79 columns and
SPDX headers in C++. For user-visible changes to upstream code: update
`NEWS.txt` and `doc/manual/en/`.

## D11 — Split ActionInterface.cpp instead of copying its setters
**Status:** Accepted (2026-09-30)

`ActionInterface.cpp` mixed UI-free setters (MacCready, ballast, bugs,
masses, radio, transponder, startup location, blackboard receive) with
code that pushes state to `MainWindow`. Backend code
(`ApplyExternalSettings`) and our C API need the setters. The
`MainWindow`/page functions (and `SetQNH`, which calls them) moved,
unchanged, to `src/ActionInterfaceUI.cpp`. Pure move, upstreamable. The
setters' remaining UI call (`InfoBoxManager::SetDirty`) is a no-op seam in
the core.

## D12 — The core main thread runs XCSoar's own event loop (VFB flavour)
**Status:** Accepted (2026-09-30)

`Replay` and device `Descriptor`s rely on `UI::Timer` and `UI::Notify`, and
several backend duties run on the UI thread (`UIReceiveBlackboard`). The core
therefore keeps a main thread running upstream's `UI::EventLoop`. On the host
this is the existing headless `VFB=y` flavour (poll backend, virtual
display), built into its own folder with `TARGET_DIR=MACOS_CORE`. On Android
no new flavour is needed: XCSoar's Android event queue is a plain C++ queue
(mutex, condition variable, timers) that Java merely feeds, so the core runs
it on its own thread. Upstream's `UI::EventLoop` needs a `TopWindow`, so the
core has its own small loop (`CoreEventQueue::Run()`: callbacks and timers
only) on both platforms.

## D13 — Reused Java I/O classes keep their `org.xcsoar` names
**Status:** Accepted (2026-09-30)

`libxcsoar_core.so` contains the JNI side of XCSoar's device and sensor I/O
(`NativePortListener`, `NativeSensorListener`, `NativeInputListener`,
`NativeDetectDeviceListener`, `StorageHotplugReceiver`: 27 entry points).
JNI binds by fully qualified class name, so the Java classes reused from
`android/src` (ports, Bluetooth/BLE/USB, IOIO, internal sensors) stay in
package `org.xcsoar` with unchanged names. The new app's own code lives in
its own package. The old UI's JNI (`NativeView`, `EventBridge`) is not in the
core library.

## D14 — Split ProcessTimer.cpp like ActionInterface.cpp
**Status:** Accepted (2026-09-30)

`ProcessTimer()` (twice per second on the UI thread) also does backend work:
it opens devices (`MainWindow::LateInitialise`, then `AutoReopen`), ticks
them, stops a replay on real movement, raises the GPS wait events, updates
the UTC offset, dumps ballast, degrades bugs and drives the network clients.
Following D11, those parts moved unchanged to `src/BackendProcessTimer.cpp`
(`BackendSettingsTimer()`, `BackendDeviceTimer()`), called by `ProcessTimer()`
at the same points; the core calls them from its own 500 ms timer.

## D15 — Hosts can run the core without devices
**Status:** Accepted (2026-09-30)

`xcs_config.flags = XCS_CONFIG_NO_DEVICES` leaves every device closed. Used by
replay, analysis and tests: on macOS the default device is the built-in GPS,
whose CoreLocation code needs the process main thread, which a test blocks
while waiting in `xcs_stop()`. For the iOS app (M8) this means the host's
main thread must never block on the core while devices are open.

