# Buteo

[![Buteo](https://github.com/CorentinGrard/buteo/actions/workflows/mobile.yml/badge.svg?branch=mobile)](https://github.com/CorentinGrard/buteo/actions/workflows/mobile.yml)

**Buteo** is a glide computer for Android phones, with a touch interface
made for the cockpit. Its flight computer is
[XCSoar](https://xcsoar.org)'s: glide calculations, task rules,
contests, airspace warnings and data formats are XCSoar's own code, run
under a new Kotlin interface.

> **Not made by the XCSoar project.** Buteo is an independent app built
> on XCSoar's code. Please report its problems [here](#feedback), not
> to XCSoar.
>
> **A backup instrument.** It has flown on a real flight, but it is no
> certified instrument: never use it as your only source for
> navigation, airspace or traffic.

## What it does

- XCSoar's moving map: terrain, airspace, waypoints, the task, the
  trail and thermals
- Six tiles, one set for cruise and one for circling, with any of
  XCSoar's InfoBoxes (graphical ones too)
- Vario and final glide bars on the map's edges; vario and speed to fly
  sound
- Tasks: edit, load `.tsk` and SeeYou `.cup` files or WeGlide tasks;
  Go to with arrival heights for the nearest landables
- Airspace warnings with a banner, a tone and vibration; NOTAMs
- The thermal assistant around the glider while circling
- Spoken callouts: airspace, final glide, task points, climbs
- IGC logging from take-off to landing, a flight summary after the
  landing, upload to WeGlide
- A first start that sets up units and the glider, and downloads the
  map, airspace and airfields for where you are
- XCSoar's analysis pages, and replay of IGC files
- Your XCSoar profiles, planes and data files work as they are

Not there yet: external devices (Bluetooth, BLE and USB varios,
loggers, FLARM traffic), iOS. The full list, and where XCSoar's
features are in Buteo: [Moving from XCSoar](../mobile/docs/MIGRATING.md).

## Install

On an Android 8 or newer phone (64-bit ARM), download the APK of the
[latest release](https://github.com/CorentinGrard/buteo/releases/latest)
and open it. Not on Google Play yet.

## How it is made

Buteo is written largely with Claude Code (AI), directed and tested by
a glider pilot.

The new code is a Kotlin / Jetpack Compose app (`mobile/`) and a
headless build of XCSoar's C++ backend behind a C API (`core/`,
`build/core.mk`). The rest of this repository is upstream XCSoar,
merged regularly and changed only where the core needs a seam.

- [Architecture](../mobile/docs/ARCHITECTURE.md)
- [Decisions](../mobile/docs/DECISIONS.md)
- [Plan and progress](../mobile/docs/PLAN.md)

## Building

You need a full JDK 21 (not a JRE), the Android SDK and NDK
(`ide/provisioning/install-android-tools.sh` installs them) and the
packages XCSoar's
[build instructions](https://xcsoar.readthedocs.io/en/latest/build.html)
list. Then:

```sh
git clone --recurse-submodules -b mobile https://github.com/CorentinGrard/buteo
cd buteo/mobile
export JAVA_HOME=/path/to/jdk-21 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleRelease -Pxcsoar.abis=arm64-v8a
```

The first build compiles XCSoar's third-party libraries and takes a
while. Tests: `./gradlew :core:test :app:verifyRoborazziDebug` for the
app, and `make VFB=y core-check` at the top for the core (see
[ARCHITECTURE.md](../mobile/docs/ARCHITECTURE.md), §6).

## Feedback

Bugs and ideas: [GitHub Issues](https://github.com/CorentinGrard/buteo/issues).

## License

GPL-2.0-or-later, like XCSoar: see [COPYING](../COPYING). XCSoar's
authors are in [AUTHORS](../AUTHORS); third-party code is listed in
[THIRD_PARTY_NOTICES.txt](../THIRD_PARTY_NOTICES.txt).
