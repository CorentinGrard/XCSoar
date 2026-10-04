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

Done 2026-10-02: `xcs_units_get` exports the unit table and the pilot's
choice; `UnitFormatter` (Kotlin) mirrors `Formatter/Units.cpp`, including
printf's rounding of the exact binary value and "-0.0".
`core/test/FormatGolden.cpp` (built from six sources, no libraries) writes
`mobile/core/src/test/resources/format-golden.txt`; the Kotlin test
replays it, and `make core-check` fails when the C++ output changes.
The app keeps two presentation choices of its own: vario values carry a
sign except at zero, and speeds have no decimals.

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

## D16 — Flight screen follows the "modern cockpit" design canvas
**Status:** Superseded by D19

The flight screen follows the design canvas "XCSoar — modern cockpit"
(claude.ai artifact DNgXpmeJEMwtP2HTs1qzNy): pale map ground, white
floating cards, one dark vario instrument, Barlow / Barlow Condensed
(SIL OFL, bundled in `res/font`, licence in `assets/licenses`). Its
palette becomes the daylight theme and replaces the pure white/black
sunlight theme; every text colour keeps at least 4.5:1 contrast.

Where the design conflicts with ARCHITECTURE §7, §7 wins:
- controls are 56 dp, not the design's 44 px;
- MacCready steps by 0.1 m/s, like XCSoar, not 0.5;
- the 30 s average is drawn in updraft sky blue, not amber, because
  amber means caution;
- values the core does not publish yet are left out instead of shown
  with sample numbers, and the map area stays a placeholder until M3.

## D17 — The core draws the map on its main thread, into the app's surface
**Status:** Accepted (2026-10-02)

Refines D6. In XCSoar's OpenGL builds the UI thread paints the map
(`GlueMapWindow::OnPaintBuffer`), reading the blackboard directly. The
core does the same on its main thread, which also owns the EGL context:
the app passes the `Surface` of a `SurfaceView` (`xcs_map_attach`), the
core creates the EGL surface, paints a plain `MapWindow` (no parent
window, `Window::Create(nullptr, …)`) and swaps buffers after every
snapshot. No separate render thread, no locking beyond upstream's.

The core does its own projection (north up, aircraft position from the
app, zoom along XCSoar's scale list) instead of `GlueMapWindow`, whose
display modes, gestures and overlays belong to the old UI.

Seams (D5) for the map: images are decoded by the app's
`org.xcsoar.CoreGraphics` instead of the old `NativeView`
(`core/map/AndroidBitmap.cpp` replaces `ui/canvas/android/Bitmap.cpp` in
a core-only screen library); SkySight overlays, `GlueMapWindow` lookups
and download progress widgets are no-ops (`core/map/MapSeams.cpp`). One
upstream change: `TextUtil` uses the calling thread's `JNIEnv` instead of
the one cached at initialisation.

## D18 — The vario sound keeps upstream's OpenSL ES player
**Status:** Accepted (2026-10-02)

The plan said "AAudio sink". The core already runs XCSoar's own audio
chain on Android (`AudioVarioGlue`, `PCMMixer`, `AndroidPCMPlayer` over
OpenSL ES), set up like `Startup()` does; it plays on the media stream.
Keeping it means no new audio code and the same sound as XCSoar. OpenSL
ES is deprecated but still supported on current Android; replace it
upstream (for both apps) if it ever goes away.

The app only turns the sound on and off (`xcs_sound_set_option`, saved
under upstream's profile keys) and makes the volume keys control the
media stream.

## D19 — Map first: the "Design rework" canvas replaces the modern cockpit
**Status:** Accepted (2026-10-03)

The design brief ("XCSoar Mobile — Product and Design Brief", claude.ai
doc 76556a35-f3d5-43f3-acfb-8ee40fc07ff5) and the pilot's answers set the
direction: gliders only; the phone is mostly a backup next to a
certified vario; portrait first, landscape supported; same features as
XCSoar, but a modern look of its own.  The design canvas "XCSoar Mobile —
Design rework" (claude.ai artifact 5t6DJiHxmxJykMMzDUNvCA) draws it.

- The map gets most of the screen (about 70 % in portrait, was 40 %).
- The dark vario panel goes.  The vario is a bar on the map's left edge
  (number, bar from zero, 30 s average marker, netto); final glide is a
  bar on the right edge.  A backup instrument does not need half the
  screen.
- Sink is neutral grey, never orange: orange only ever means caution
  (arrival below glide, airspace ahead).  Lift stays green.
- No quick-action bar (ARCHITECTURE §7): it costs room for no gain.
  MacCready stays one tap away; "Set MC" appears beside it in lift.
- The Cruise / Circling switch leaves the flight screen: the tiles
  follow the flight mode, and the menu has Auto / Cruise / Circling.
- The menu is a sheet: four large in-flight buttons (Go to, Task, Flight
  setup, Analysis), then a list for the ground (Aircraft & crew,
  Flights, Data files, Settings, Replay).  Units, Map and Pilot &
  WeGlide move to a Settings page, which lists the planned settings as
  "Soon".
- Go to: only each row's "Go" button acts, so a bumpy tap on the list
  does not change the target.
- Map buttons sit in one row along the bottom of the map; the north mark
  is black, not red (red means warning).

## D20 — The core's own NetComponents
**Status:** Accepted (2026-10-03)

Live tracking, the thermal info map and NOTAMs live in upstream's
`NetComponents`, which the backend timer and the merge thread already
feed.  Its constructor also creates the RASP, xctherm and EDL download
glue, which reach dialogs and the main window.  The core defines the
`NetComponents` constructor, destructor and `BeginShutdown()` itself
(`core/host/CoreNetComponents.cpp`, a link-time seam like D5) and creates
only tracking, TIM and NOTAMs; the never-created members' destructors are
empty seams there too.  If one of those downloads is wanted later, its
seam goes and the real object is linked (the linker reports duplicates).

In the app, blocking network calls (WeGlide, weather) take their own
lock instead of the one every command takes, so a slow download never
holds up a MacCready change; stopping the core waits for both.


## D21 — One menu, uniform tiles
**Status:** Accepted (2026-10-04)

Amends D19 after flying it.

- Every tile has the same height: the InfoBox comment (e.g. the altitude
  in feet) shares the caption's line instead of adding a third line.
  A comment with a number is left out when it does not fit, never cut
  ("178…" for 1784 ft reads as another value); text ends with "…".
- MacCready is a tile spanning two columns, and the menu is the last
  tile beside it: one 3-column grid, no row of odd sizes.
- "Set MC" floats over the bottom left of the map in lift, within reach
  of the thumb, since the grid has no free cell for it.
- The vario sound and map orientation buttons leave the map; the menu
  has a "Quick settings" group with Tiles, Map up (North / Track /
  Target) and a vario sound switch.  A turned map shows a passive north
  mark beside the zoom buttons.
- Quick settings also has the theme: System (the phone's, the default
  and first), White (sunlight) or Dark (night).  It is the app's own choice, kept in its
  preferences, not in XCSoar's profile; the status bar icons follow it.
- The Settings page goes: its entries are a section of the menu, each
  page listed once (Airspace and Data files were in both).  "Polar and
  masses" goes too: Aircraft & crew edits the plane.  The "Soon" rows
  go; they return when they are built.
