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
