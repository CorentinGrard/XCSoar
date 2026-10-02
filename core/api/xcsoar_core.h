/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Copyright The XCSoar Project */

/*
 * xcsoar_core.h: the C API of XCSoar's headless core.
 *
 * This is the only interface between a user interface (Kotlin on
 * Android, Swift/Kotlin on iOS, test programs) and XCSoar's glide
 * computer.  See mobile/docs/ARCHITECTURE.md.
 *
 * Rules:
 *  - Plain C; no C++ types cross this boundary.
 *  - All values are SI units: metres, metres per second, degrees for
 *    angles and coordinates, seconds for time.
 *  - All functions are thread-safe unless noted.  Commands are
 *    executed on the core's own main thread; the calling thread blocks
 *    until the command has run.
 *  - Callbacks run on the core main thread.  They must return quickly
 *    and may call xcs_* functions (these run directly, without
 *    blocking).
 *  - Structs start with struct_size so fields can be appended without
 *    breaking older callers.  Existing fields never move.
 *  - Only one core may exist per process (XCSoar uses process-wide
 *    globals).
 */

#ifndef XCSOAR_CORE_H
#define XCSOAR_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Bumped when the API changes incompatibly. */
#define XCS_API_VERSION 1

#if defined(__GNUC__) || defined(__clang__)
#define XCS_EXPORT __attribute__((visibility("default")))
#else
#define XCS_EXPORT
#endif

typedef enum xcs_status {
  XCS_OK = 0,
  /** An argument was invalid (null pointer, wrong struct_size, ...). */
  XCS_ERROR_INVALID_ARGUMENT = -1,
  /** The call is not allowed in the current state (e.g. not started). */
  XCS_ERROR_STATE = -2,
  /** Another core already exists in this process. */
  XCS_ERROR_BUSY = -3,
  /** The operation failed; details were reported as an XCS_EVENT_MESSAGE. */
  XCS_ERROR_FAILED = -4,
} xcs_status;

typedef struct xcs_core xcs_core;

/*
 * Flight snapshot: the live state, published after every sensor merge
 * and every calculation.
 */

/** Bits of xcs_flight_snapshot.valid. */
enum {
  XCS_VALID_TIME = 1u << 0,
  XCS_VALID_LOCATION = 1u << 1,
  XCS_VALID_TRACK = 1u << 2,
  XCS_VALID_GROUND_SPEED = 1u << 3,
  XCS_VALID_AIRSPEED = 1u << 4,
  XCS_VALID_GPS_ALTITUDE = 1u << 5,
  XCS_VALID_BARO_ALTITUDE = 1u << 6,
  XCS_VALID_NAV_ALTITUDE = 1u << 7,
  XCS_VALID_TERRAIN = 1u << 8,
  XCS_VALID_VARIO = 1u << 9,
  XCS_VALID_NETTO_VARIO = 1u << 10,
  XCS_VALID_WIND = 1u << 11,
  XCS_VALID_TASK = 1u << 12,
  XCS_VALID_NEXT_WAYPOINT = 1u << 13,
  XCS_VALID_FINAL_GLIDE = 1u << 14,
};

/** Bits of xcs_flight_snapshot.flags. */
enum {
  /** A real (non-replay, non-simulated) GPS is connected. */
  XCS_FLAG_GPS_REAL = 1u << 0,
  XCS_FLAG_FLYING = 1u << 1,
  XCS_FLAG_CIRCLING = 1u << 2,
  /** Above final glide to the task finish. */
  XCS_FLAG_FINAL_GLIDE = 1u << 3,
  XCS_FLAG_REPLAY = 1u << 4,
};

typedef struct xcs_flight_snapshot {
  uint32_t struct_size;
  uint32_t api_version;

  /** Increases with every published snapshot. */
  uint64_t sequence;

  /** XCS_VALID_* bits: which fields below hold data. */
  uint32_t valid;
  /** XCS_FLAG_* bits. */
  uint32_t flags;

  /** UTC, seconds since 1970-01-01 (XCS_VALID_TIME). */
  double time_utc;
  /** Flight time so far; 0 if not flying yet. */
  double flight_time;

  /** WGS84 (XCS_VALID_LOCATION). */
  double latitude;
  double longitude;

  /** Track over ground, degrees true (XCS_VALID_TRACK). */
  double track;
  double ground_speed;
  /** XCS_VALID_AIRSPEED */
  double true_airspeed;
  double indicated_airspeed;

  /** Above MSL (XCS_VALID_GPS_ALTITUDE / _BARO_ALTITUDE / _NAV_ALTITUDE).
      nav_altitude is the one XCSoar uses for calculations. */
  double gps_altitude;
  double baro_altitude;
  double nav_altitude;

  /** XCS_VALID_TERRAIN */
  double terrain_altitude;
  double altitude_agl;

  /** Total energy vario, instantaneous and 30 s average (XCS_VALID_VARIO). */
  double vario;
  double average_vario;
  /** XCS_VALID_NETTO_VARIO */
  double netto_vario;

  /** Wind the glider flies in: speed, and the direction it comes from,
      degrees true (XCS_VALID_WIND). */
  double wind_speed;
  double wind_bearing;

  /** Current MacCready setting (always valid). */
  double mac_cready;

  /** Next task point (XCS_VALID_NEXT_WAYPOINT). */
  double next_distance;
  double next_bearing;
  /** Altitude above (positive) or below the glide path to the next
      point, at the current MacCready (XCS_VALID_NEXT_WAYPOINT). */
  double next_altitude_difference;

  /** Final glide to the task finish (XCS_VALID_FINAL_GLIDE). */
  double task_remaining_distance;
  double final_glide_altitude_difference;

  /** UTF-8, zero-terminated, possibly truncated at a character
      boundary (XCS_VALID_NEXT_WAYPOINT). */
  char next_name[64];
} xcs_flight_snapshot;

/*
 * Events: things that happen, as opposed to state.
 */

typedef enum xcs_event_type {
  /** A glide computer event; code is an XCS_GCE_* value. */
  XCS_EVENT_GLIDE_COMPUTER = 1,
  /** A message for the pilot; text (and optionally detail) are set. */
  XCS_EVENT_MESSAGE = 2,
  /** The replay reached the end of its file. */
  XCS_EVENT_REPLAY_FINISHED = 3,
} xcs_event_type;

/** Stable codes of XCS_EVENT_GLIDE_COMPUTER. */
typedef enum xcs_gce {
  XCS_GCE_OTHER = 0,
  XCS_GCE_TAKEOFF = 1,
  XCS_GCE_LANDING = 2,
  XCS_GCE_FLIGHTMODE_CLIMB = 3,
  XCS_GCE_FLIGHTMODE_CRUISE = 4,
  XCS_GCE_FLIGHTMODE_FINALGLIDE = 5,
  XCS_GCE_FINALGLIDE_ABOVE = 6,
  XCS_GCE_FINALGLIDE_BELOW = 7,
  XCS_GCE_FINALGLIDE_TERRAIN = 8,
  XCS_GCE_LANDABLE_UNREACHABLE = 9,
  XCS_GCE_TASK_START = 10,
  XCS_GCE_TASK_NEXTWAYPOINT = 11,
  XCS_GCE_TASK_FINISH = 12,
  XCS_GCE_ARM_READY = 13,
  XCS_GCE_AIRSPACE_NEAR = 14,
  XCS_GCE_AIRSPACE_ENTER = 15,
  XCS_GCE_AIRSPACE_LEAVE = 16,
  XCS_GCE_FLARM_TRAFFIC = 17,
  XCS_GCE_FLARM_NOTRAFFIC = 18,
  XCS_GCE_FLARM_NEWTRAFFIC = 19,
  XCS_GCE_GPS_CONNECTION_WAIT = 20,
  XCS_GCE_GPS_FIX_WAIT = 21,
  XCS_GCE_HEIGHT_MAX = 22,
  XCS_GCE_TEAM_POS_REACHED = 23,
  XCS_GCE_POLAR_CHANGED = 24,
  XCS_GCE_ALTERNATE_CHANGED = 25,
  XCS_GCE_COMMPORT_RESTART = 26,
} xcs_gce;

typedef struct xcs_event {
  uint32_t struct_size;
  /** xcs_event_type */
  uint32_t type;
  /** Depends on type (xcs_gce for XCS_EVENT_GLIDE_COMPUTER). */
  uint32_t code;
  /** UTF-8 or NULL; valid only during the callback. */
  const char *text;
  const char *detail;
} xcs_event;

typedef void (*xcs_snapshot_callback)(void *ctx,
                                      const xcs_flight_snapshot *snapshot);
typedef void (*xcs_event_callback)(void *ctx, const xcs_event *event);

/** Bits of xcs_config.flags. */
enum {
  /** Do not open any device (GPS, sensors, varios, loggers): for replay,
      flight analysis and tests, e.g. on machines without the hardware. */
  XCS_CONFIG_NO_DEVICES = 1u << 0,
};

typedef struct xcs_config {
  uint32_t struct_size;
  /** Must be XCS_API_VERSION. */
  uint32_t api_version;

  /** The XCSoarData directory (UTF-8).  It is created if missing. */
  const char *data_path;

  /** Profile file name inside profiles/, or NULL for "default.prf". */
  const char *profile;

  /** Optional; called on the core main thread. */
  xcs_snapshot_callback on_snapshot;
  xcs_event_callback on_event;
  void *callback_ctx;

  /** XCS_CONFIG_* bits. */
  uint32_t flags;
} xcs_config;

/*
 * Lifecycle.  create → start → (commands) → stop → destroy.  A core may
 * be started again after stop.
 */

XCS_EXPORT xcs_status
xcs_create(const xcs_config *config, xcs_core **core_r);

/** Start the main thread, load profile and data files, start the
    glide computer.  Blocks until the core is running. */
XCS_EXPORT xcs_status
xcs_start(xcs_core *core);

/** Save the profile and task, stop everything.  Blocks. */
XCS_EXPORT xcs_status
xcs_stop(xcs_core *core);

/** Stops the core if needed and frees it.  NULL is allowed. */
XCS_EXPORT void
xcs_destroy(xcs_core *core);

/** The version this library implements (XCS_API_VERSION). */
XCS_EXPORT uint32_t
xcs_api_version(void);

/*
 * Commands.  Require a started core.
 */

/** Set the MacCready value (m/s, 0..5) and send it to the devices. */
XCS_EXPORT xcs_status
xcs_set_mac_cready(xcs_core *core, double mac_cready);

/** Copy the latest snapshot. */
XCS_EXPORT xcs_status
xcs_get_snapshot(xcs_core *core, xcs_flight_snapshot *snapshot);

/*
 * Data files: the same files and profile settings as XCSoar.
 */

typedef enum xcs_data_file {
  /** Map (.xcm): terrain and topography; may also contain waypoints
      and airspace.  Profile key MapFile. */
  XCS_DATA_MAP = 1,
  /** Airspace (OpenAir .txt/.air, .sua).  Profile key AirspaceFileList. */
  XCS_DATA_AIRSPACE = 2,
  /** Waypoints (.cup, .dat, ...).  Profile key WPFileList. */
  XCS_DATA_WAYPOINTS = 3,
} xcs_data_file;

/**
 * Use this file for one kind of data (replacing the files configured
 * for it), save the profile and load the data again.  Blocks while
 * loading.  Load errors are reported as XCS_EVENT_MESSAGE.
 *
 * @param kind an xcs_data_file value
 * @param path UTF-8; files inside the data directory are stored
 * relative to it, like XCSoar does.  NULL or "" removes the file.
 */
XCS_EXPORT xcs_status
xcs_set_data_file(xcs_core *core, uint32_t kind, const char *path);

/**
 * Describe the configured data files and what was loaded from them, as
 * a JSON object (UTF-8, null-terminated):
 *
 *   {"map": {"files": ["..."], "terrain": true},
 *    "airspace": {"files": ["..."], "count": 123},
 *    "waypoints": {"files": ["..."], "count": 456}}
 *
 * "count" includes what came from the map file.
 *
 * @param length_r receives the JSON length without the terminator; if
 * it does not fit into size bytes, nothing is written and the call
 * fails with XCS_ERROR_INVALID_ARGUMENT (retry with a larger buffer)
 */
XCS_EXPORT xcs_status
xcs_get_data_status(xcs_core *core, char *buffer, size_t size,
                    size_t *length_r);

/*
 * Moving map: XCSoar's map drawn by the core with OpenGL ES into a
 * surface of the app.  Android only for now; elsewhere these return
 * XCS_ERROR_FAILED.  New data is drawn at most
 * four times per second, and each of these calls redraws at once.
 */

/**
 * Draw into this surface (Android: an ANativeWindow *, which gains a
 * reference), replacing a previous one.  Call again when the size
 * changes.
 *
 * @param width, height the surface size in pixels
 * @param dpi the screen density, for symbol and text sizes
 */
XCS_EXPORT xcs_status
xcs_map_attach(xcs_core *core, void *native_window, uint32_t width,
               uint32_t height, uint32_t dpi);

/** Stop drawing; call before the surface is destroyed. */
XCS_EXPORT xcs_status
xcs_map_detach(xcs_core *core);

/** Where to draw the aircraft, in pixels from the top left corner
    (e.g. the middle of the part not covered by the app's cards). */
XCS_EXPORT xcs_status
xcs_map_set_aircraft_position(xcs_core *core, int32_t x, int32_t y);

/** Zoom in (steps < 0) or out (steps > 0) along XCSoar's scale list. */
XCS_EXPORT xcs_status
xcs_map_zoom(xcs_core *core, int32_t steps);

/** Move the map with the finger by (dx, dy) pixels; the map stops
    following the aircraft until xcs_map_follow(). */
XCS_EXPORT xcs_status
xcs_map_pan(xcs_core *core, float dx, float dy);

/** Zoom continuously (pinch): factor > 1 zooms in. */
XCS_EXPORT xcs_status
xcs_map_scale(xcs_core *core, float factor);

/** Centre on the aircraft again and follow it. */
XCS_EXPORT xcs_status
xcs_map_follow(xcs_core *core);

/** Which way is up on the map (XCSoar's map orientation setting). */
typedef enum xcs_map_orientation {
  XCS_MAP_TRACK_UP = 0,
  XCS_MAP_NORTH_UP = 1,
  /** Towards the next waypoint. */
  XCS_MAP_TARGET_UP = 2,
  XCS_MAP_HEADING_UP = 3,
  /** The wind comes from the top. */
  XCS_MAP_WIND_UP = 4,
} xcs_map_orientation;

/** Set the orientation for cruise and circling; saved in the profile.
    In track and target up, the aircraft is drawn lower in cruise (the
    profile's glider position) to show more ahead. */
XCS_EXPORT xcs_status
xcs_map_set_orientation(xcs_core *core, uint32_t orientation);

/** The orientation in cruise (an xcs_map_orientation). */
XCS_EXPORT xcs_status
xcs_map_get_orientation(xcs_core *core, uint32_t *orientation_r);

/*
 * Replay of IGC or NMEA files.
 */

/** Replay in (scaled) real time.  time_scale 1 = real time.  An
    XCS_EVENT_REPLAY_FINISHED event follows the end of the file. */
XCS_EXPORT xcs_status
xcs_replay_start(xcs_core *core, const char *path, double time_scale);

XCS_EXPORT xcs_status
xcs_replay_stop(xcs_core *core);

/**
 * Replay the whole file as fast as possible and deterministically:
 * every fix goes through the merge and calculation code, in order,
 * with no timing involved.  Snapshots and events are published as
 * usual (snapshots at most once per snapshot_interval seconds of
 * flight time; 0 = after every fix).  The last snapshot (the state at
 * the end of the file) and XCS_EVENT_REPLAY_FINISHED are published
 * before the core resumes live operation.  Blocks until the file has
 * been processed.  For tests and flight analysis.
 *
 * @param fixes_r if not NULL, receives the number of fixes processed
 */
XCS_EXPORT xcs_status
xcs_replay_run(xcs_core *core, const char *path, double snapshot_interval,
               uint32_t *fixes_r);

#ifdef __cplusplus
}
#endif

#endif
