# XCSoar Mobile — Architecture

A new mobile UI (Android first, iOS later) on top of XCSoar's existing C++
core. The glide computer, task engine, device drivers and file parsers are
reused unchanged; only the presentation layer is rewritten.

Progress is tracked in [PLAN.md](PLAN.md). Decisions and their rationale are
in [DECISIONS.md](DECISIONS.md).

Upstream rules that also apply here: [AGENTS.md](../../AGENTS.md) and
[doc/architecture.rst](../../doc/architecture.rst) (layers, threads, locking,
UI guidelines). Where this document and those files overlap, the upstream
files win.

## 1. Guiding principles

1. **Never rewrite a number.** Anything that computes a flight value (glide,
   task, contest, airspace, wind, thermal, FLARM, drivers) stays in the C++
   core. Kotlin only displays, formats and sends commands.
2. **One narrow, stable boundary.** The UI talks to the core only through a
   plain C API (`xcsoar_core.h`). No C++ types, no JNI types. Kotlin/Android
   uses it through JNI today; Kotlin/Native (iOS) can use the same header later.
3. **Stay mergeable with upstream.** New code lives in new directories.
   Changes to existing `src/` files are limited to small seams that could be
   upstreamed. We merge `XCSoar/XCSoar` master regularly.
4. **Test every layer.** The existing C++ tests stay green. Every new layer
   has its own tests, and a fake core lets the UI be tested without native code.
5. **Built for the cockpit.** Readable in direct sunlight, large touch
   targets, glove-friendly, safe with one hand, no modal dialogs over the map
   in flight unless safety-critical.

## 2. Layers

```
┌──────────────────────── mobile/ (Kotlin, Gradle) ─────────────────────────┐
│ :app      Jetpack Compose screens · ViewModels (StateFlow) · navigation   │
│           InfoBoxes · map overlays · settings · task editor · theming    │
│ :core     interface XcsoarCore  ──┬── NativeXcsoarCore (JNI → C API)      │
│           models (FlightState,    └── FakeXcsoarCore (tests, previews)    │
│           Task, Waypoint…), units/formatting                             │
│ :platform Android I/O: BT classic / BLE / USB serial / internal GPS &     │
│           baro (reused from android/src/*.java), audio, storage (SAF)     │
└──────────────────────────────── JNI (thin) ───────────────────────────────┘
┌──────────────────── libxcsoar_core.so (C++20) ────────────────────────────┐
│ core/api     xcsoar_core.h — stable C ABI, versioned                       │
│ core/host    "headless" replacements for the old UI seams:                │
│              Protection.cpp triggers → snapshot publisher                 │
│              InputEvents::processGlideComputer → event callback           │
│              Message::AddMessage → event callback                         │
│              UI::Notify → core's own event loop (replaces UI thread)      │
│              CommonInterface settings → core-owned settings store         │
│ core/map     map renderer: existing src/Renderer + ui/canvas (OpenGL ES)  │
│              drawing into a native surface supplied by the app             │
│ src/ (reused, unchanged) Engine · Computer · Device + ~40 drivers · NMEA  │
│              IGC · Logger · Task · Waypoint · Airspace · Terrain ·         │
│              Topography · Weather · Profile · FLARM · net                  │
└────────────────────────────────────────────────────────────────────────────┘
```

### Why this cut works

The backend already has almost no knowledge of the UI:

- The data path is `DeviceBlackboard → MergeThread → CalculationThread`, and
  the UI is notified only through the free functions in `src/Protection.cpp`
  (`TriggerGPSUpdate`, `TriggerCalculatedUpdate`, …).
- Flight events (take-off, landing, circling, final glide, FLARM traffic)
  leave the backend through `InputEvents::processGlideComputer(GCE_*)`.
- `doc/architecture.rst` states the rule explicitly: the backend reaches the
  UI "only through event queues (`InputEvents`, `UI::Notify`)". Background
  network work (NOTAM, weather tiles, SkyLines…) runs on the asio thread and
  reports back with `UI::Notify`.
- Only about 10 references from `Device/`, `Computer/`, `Engine/`, `Task/`,
  `Logger/` and `NMEA/` reach `CommonInterface::` or `Message::`.
- The C++ test suite already links the engine without the UI by replacing
  these symbols with `test/src/Fake*.cpp`. `core/host` does the same thing in
  production.

So the seams are **link-time substitutions**, not refactors of existing code.

## 3. The core API (`core/api/xcsoar_core.h`)

Plain C, opaque handle, `xcs_` prefix. All functions are thread-safe.
Callbacks run on core threads, never on the UI thread.

```c
typedef struct xcs_core xcs_core;

/* lifecycle */
xcs_core *xcs_create(const xcs_config *cfg);   /* data dir, callbacks, api version */
int       xcs_start(xcs_core *);               /* load profile, data files, spawn threads */
void      xcs_stop(xcs_core *);
void      xcs_destroy(xcs_core *);

/* hot path: flight state (pushed, rate-limited, typically 1–10 Hz) */
typedef void (*xcs_snapshot_cb)(void *ctx, const xcs_flight_snapshot *s);

/* events: take-off, landing, airspace warning, FLARM alarm, message, … */
typedef void (*xcs_event_cb)(void *ctx, const xcs_event *e);

/* commands */
int xcs_set_mc(xcs_core *, double mc_ms);
int xcs_set_ballast(xcs_core *, double fraction);
int xcs_goto_waypoint(xcs_core *, int waypoint_id);
int xcs_task_set(xcs_core *, const char *task_json);
int xcs_airspace_ack(xcs_core *, int airspace_id, int kind);
...

/* cold path: queries and documents, as JSON (caller frees with xcs_free) */
char *xcs_task_get(xcs_core *);
char *xcs_waypoints_query(xcs_core *, const char *query_json);
char *xcs_settings_get(xcs_core *, const char *section);
int   xcs_settings_set(xcs_core *, const char *section, const char *json);

/* data sources */
int xcs_port_open(xcs_core *, int device_index, const xcs_port_ops *ops); /* platform-supplied byte stream */
int xcs_feed_nmea(xcs_core *, int device_index, const char *line);
int xcs_replay_start(xcs_core *, const char *igc_or_nmea_path, double speed);

/* map */
int  xcs_map_attach_surface(xcs_core *, void *native_window, int w, int h, float dpi);
void xcs_map_detach_surface(xcs_core *);
int  xcs_map_gesture(xcs_core *, const xcs_map_gesture *g);   /* pan, zoom, rotate */
char *xcs_map_items_at(xcs_core *, float x, float y);         /* JSON list */
```

### Data formats: two paths

| Path | Used for | Format | Why |
|---|---|---|---|
| Hot | Live flight state, several times a second | `xcs_flight_snapshot`: fixed-layout POD struct, SI units, with a validity bit per field | No allocation and no per-field JNI calls: Kotlin reads it with one direct `ByteBuffer` copy |
| Cold | Task, waypoints, airspace lists, settings, devices, analysis | JSON (core already has boost.json) ↔ `kotlinx.serialization` | Easy to evolve, easy to test with golden files, easy to debug |

The snapshot begins with `uint32 struct_size` and `uint32 api_version`, so new
fields can be appended without breaking old readers.

**Units:** the core always speaks SI. To avoid a second, drifting copy of
XCSoar's unit logic (see D9), the core exports its unit tables (`Units/`) and
format rules from `Formatter/` (decimals and rounding per unit) as JSON at
start-up. Kotlin applies them, and a golden test checks that Kotlin output
matches the C++ `Formatter` for the same inputs.

### Threading

```
device I/O threads ─▶ DeviceBlackboard ─▶ MergeThread ─▶ CalculationThread
                                                               │
                              core/host SnapshotPublisher ◀────┘ (TriggerCalculatedUpdate)
                                      │ copies Blackboard → xcs_flight_snapshot (under lock, µs)
                                      ▼
                         snapshot callback (core thread)
                                      │ JNI: copy into direct ByteBuffer
                                      ▼
             Kotlin: MutableStateFlow<FlightState> (conflated) ─▶ Compose recomposition
```

The UI never holds a core lock, and a slow UI frame never slows the
calculation. Because the flow is conflated, the UI always sees the latest
state and older ones are dropped.

**The core's "UI thread".** Upstream code expects a main thread with an event
loop: `UI::Notify` callbacks, the `ProcessTimer` housekeeping (twice per
second), `InterfaceBlackboard`, and `BlackboardListener`s. `core/host` runs
one dedicated **core main thread** with that event loop. From the upstream
code's point of view it *is* the UI thread (`InMainThread()` is true there).
All `xcs_*` commands are posted to it, so upstream threading rules hold
unchanged. The Android main thread never runs XCSoar code.

Thread overview inside `libxcsoar_core`:

| Thread | Owner | Role |
|---|---|---|
| core main | `core/host` | event loop, `UI::Notify`, ProcessTimer, commands, snapshot publishing |
| MergeThread / CalculationThread | upstream | sensor merge and glide computer (unchanged) |
| asio | upstream `GlobalAsioThread` | HTTP, NOTAM, weather, tracking |
| map render | `core/map` | draws into the app's surface (OpenGL ES) |
| device I/O | platform (Java) | Bluetooth/USB threads push bytes into the core |

Shutdown follows the order in `doc/architecture.rst` (network → threads →
storage → components); `xcs_stop` implements the same sequence as
`Startup.cpp`.

### Settings and data compatibility

The core still owns settings and keeps using the existing profile format
(`*.prf`) and the existing `XCSoarData` layout (waypoints, airspace, maps,
tasks, polars, logs). A pilot can copy their current XCSoar data folder and
profile into the new app and have everything work. Settings are exposed as
typed JSON sections (e.g. `polar`, `units`, `airspace_warnings`) rather than
raw profile keys.

## 4. Map

The moving map is the hardest part and XCSoar's most valuable UI. Plan:

- **Phase 1 (parity):** reuse XCSoar's existing OpenGL ES map renderer
  (`src/Renderer`, `src/MapWindow`, `src/Terrain`, `src/Topography`,
  `src/Weather`, plus the `ui/canvas` + `ui/opengl` backend it draws with).
  It renders into an `ANativeWindow` from an Android `SurfaceView` on a core
  render thread. Terrain shading, airspace, task, trail, FLARM, topography and
  RASP all work immediately.
- **Compose draws everything interactive on top:** InfoBoxes, buttons,
  airspace-warning banners, FLARM radar, bottom sheets. Gestures are handled
  in Kotlin and sent to `xcs_map_gesture`.
- **Later (optional):** evaluate a MapLibre-based renderer behind the same
  `xcs_map_*` API. This is not needed for a first release.

The old window system (`ui/window`, `ui/event` main loop), `Form/`, `Widget/`,
`Dialogs/`, `InfoBoxes/` (as widgets) and `Menu/` are **not** linked into
`libxcsoar_core`.

## 5. Repository layout

```
core/                     new C++ (C API + headless host + map glue)
  api/xcsoar_core.h
  host/                   Snapshot publisher, event bridge, settings store
  map/
  test/                   API contract tests + golden replay tests
build/core.mk             make target: libxcsoar_core (host + Android ABIs)
mobile/                   Gradle project (Kotlin, Compose)
  app/  core/  platform/
  docs/ARCHITECTURE.md  PLAN.md  DECISIONS.md
src/ test/ …              upstream XCSoar, touched only for seams
```

The native library is built with the existing Makefile system (it already
handles the NDK, cross-compiling third-party libraries, and every target).
Gradle calls `make TARGET=ANDROIDAARCH64 libxcsoar_core` (plus x86_64 for the
emulator) and packages the resulting `.so` as `jniLibs`. We do not port the
build to CMake.

## 6. Testing strategy

| Level | What | Tool | Runs where |
|---|---|---|---|
| L0 | Existing XCSoar unit tests (task, glide, airspace, drivers, parsers) | `make check` (134 Test* programs) | host, CI |
| L1 | C API contract tests: lifecycle, commands, JSON schemas, thread safety | C++ test in `core/test`, linked against `libxcsoar_core` | host, CI, plus TSan/ASan builds |
| L2 | **Golden replay tests:** replay `test/data/*.igc` through the C API, dump a snapshot every N seconds to JSON and compare to committed golden files | same | host, CI |
| L3 | Kotlin core binding: `ByteBuffer` ↔ `FlightState` decoding, JSON models, unit formatting | JUnit + golden JSON shared with L2 | JVM, CI |
| L4 | ViewModels and screens against `FakeXcsoarCore` | JUnit, Compose UI tests, screenshot tests (Roborazzi) | JVM, CI |
| L5 | End-to-end: real `.so` on an emulator, replay an IGC, check the UI shows expected values | Android instrumented tests | emulator, CI (nightly) + your phone |

L2 is the main safety net. It shows whether the numbers the pilot sees change,
including after merging upstream changes. L4 screenshot tests catch visual
regressions.

C++ tests (L1, L2) follow upstream conventions (`.cursor/rules/xcsoar-testing.mdc`):
TAP via `TestUtil.hpp` (`plan_tests`, `ok1`), registered in `build/test.mk`, run
by `make check`. Any logic we add to existing `src/` (for example a seam made
reusable) gets a TAP test in `test/src/` like any upstream change.

## 7. UX direction

The new UI follows upstream's **User interface guidelines** and **Touch
interaction** rules in `doc/architecture.rst` (NASA colour usage, FAA EFB
DOT/FAA/AR-03/67, ICAO Annex 4). Modern styling is fine, but these are not
negotiable:

- **Colour means function.** Red = warning, orange = caution, green = safe,
  blue = neutral safe; FLARM uses alarm green/orange/red; lift green, sink
  copper orange; route dark purple-blue; task ICAO magenta; updraft data sky
  blue. Text stays monochrome. Saturation and high contrast are reserved for
  important items. Colours are Compose theme tokens named by function
  (`warning`, `taskLine`…), never by hue. Check every theme with a
  colour-blindness simulator.
- **Data display.** Invalid data shows dashes or nothing, never a stale number.
  Values are in user units, with digits matching real accuracy.
- **Touch.** Visual (and optional haptic) feedback within 100 ms. Actions fire
  on **lift-off**; sliding off cancels. One press = one winner (tap, drag or
  hold). Hold arms after 500 ms with an honest one-way fill. A dead band of
  about 20 dp before a movement counts as a drag. Busy indicator if input
  isn't handled within 0.5 s. Compose's default `clickable` already fires on
  release; we wrap it in one shared `XcsTouch` modifier so every control
  behaves the same.
- **Map overlays.** Overlays contrast with the map, keep the ownship area
  clear, are hidden when they have no purpose in the current flight state,
  and are stacked alert > caution > info.
- **Safety.** Nothing that makes the pilot stare at the screen. No control
  whose misconfiguration could be unsafe without a guard.

On top of that:

- **Flight screen:** full-screen map with a configurable InfoBox strip or
  grid, plus a vario bar and final-glide bar on the edge. There are pages as
  in XCSoar (map, map+InfoBoxes, analysis), switched with a horizontal swipe.
- **Quick actions:** a thumb-reachable bottom bar (MC ±, Go To, Task, Wind,
  Menu) instead of XCSoar's hardware-key-oriented menu.
- **Themes:** Material 3 base with a high-contrast "sunlight" theme (default
  in flight), a night theme, and dynamic sizing for 5"–10" screens,
  landscape and portrait.
- **On the ground:** normal app navigation for task planning, data downloads
  and device setup. Some screens are then disabled while flying.
- **Language:** reuse XCSoar's gettext catalogues (`po/*.po`) by converting
  them to Android string resources at build time, so existing translations
  carry over.
