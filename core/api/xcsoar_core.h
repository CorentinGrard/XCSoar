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
  XCS_VALID_SPEED_TO_FLY = 1u << 15,
  XCS_VALID_LD = 1u << 16,
  XCS_VALID_LD_REQUIRED = 1u << 17,
  XCS_VALID_NEXT_TIME = 1u << 18,
  XCS_VALID_TASK_SPEED = 1u << 19,
  XCS_VALID_CURRENT_THERMAL = 1u << 20,
  XCS_VALID_LAST_THERMAL = 1u << 21,
  XCS_VALID_QNH = 1u << 22,
  XCS_VALID_STATIC_PRESSURE = 1u << 23,
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

  /* appended in API version 1 (struct_size 368); the values and their
     validity are those of the matching XCSoar InfoBoxes */

  /** Speed to fly, IAS (XCS_VALID_SPEED_TO_FLY; "Speed dolphin"). */
  double speed_to_fly;
  /** Current glide ratio over ground (XCS_VALID_LD; "L/D instantaneous"). */
  double ld;
  /** Glide ratio needed to the next point; 0 means "+++", no glide
      needed (XCS_VALID_LD_REQUIRED; "Next L/D"). */
  double ld_required;
  /** Time to the next point at the current MacCready, s
      (XCS_VALID_NEXT_TIME; "Next ETE"). */
  double next_time_remaining;
  /** Achieved task speed, m/s (XCS_VALID_TASK_SPEED; "Speed task"). */
  double task_speed;
  /** The thermal being climbed: average climb (m/s), height gained (m)
      and time (s) (XCS_VALID_CURRENT_THERMAL; "Thermal avg/gain"). */
  double current_thermal_lift;
  double current_thermal_gain;
  double current_thermal_duration;
  /** The last thermal, same fields (XCS_VALID_LAST_THERMAL). */
  double last_thermal_lift;
  double last_thermal_gain;
  double last_thermal_duration;

  /* appended in API version 1 (struct_size 400); always valid */

  /** Water ballast on board, litres. */
  double ballast;
  /** The plane's maximum water ballast, litres; 0 if it carries none. */
  double max_ballast;
  /** Performance left by bugs, XCSoar's convention: 1 clean, 0.5
      means the sink rate is doubled. */
  double bugs;
  /** Wing loading, kg/m²; 0 if the plane's wing area is unknown. */
  double wing_loading;

  /* appended in API version 1 (struct_size 416) */

  /** QNH, hPa (XCS_VALID_QNH: set by the pilot, a device or XCSoar's
      automatic QNH on the ground).  Without it, the standard
      atmosphere, and baro_altitude is not valid. */
  double qnh;
  /** Static pressure, hPa (XCS_VALID_STATIC_PRESSURE): from a
      barometer, the phone's or a device's. */
  double static_pressure;
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

/** Set the water ballast (litres, 0..max_ballast of the snapshot) and
    send it to the devices. */
XCS_EXPORT xcs_status
xcs_set_ballast(xcs_core *core, double litres);

/** Set the bugs (0.5..1, 1 = clean, as in the snapshot) and send them
    to the devices. */
XCS_EXPORT xcs_status
xcs_set_bugs(xcs_core *core, double bugs);

/** Set the QNH (hPa, 850..1300, the range of XCSoar's flight setup)
    and send it to the devices. */
XCS_EXPORT xcs_status
xcs_set_qnh(xcs_core *core, double hpa);

/**
 * XCSoar's units and the pilot's choice, for the app to format values
 * like XCSoar's Formatter (JSON, UTF-8, null-terminated):
 *
 *   {"units": [{"unit": 1, "name": "km", "factor": 0.001,
 *               "offset": 0.0}, ...],
 *    "groups": [{"group": 1, "unit": 1, "choices": [3, 2, 1]}, ...],
 *    "presets": [{"name": "European"}, ...], "preset": 0}
 *
 * A unit's value is SI × factor + offset.  "unit" is XCSoar's Unit
 * enum, "group" its UnitGroup (1 distance, 2 altitude, 3 temperature,
 * 4 aircraft speed, 5 vertical speed, 6 wind speed, 7 task speed,
 * 8 pressure, 9 wing loading, 10 mass); "choices" are the units the
 * pilot may set (wind speed follows aircraft speed).  "preset": the
 * preset the settings equal, -1 for none.
 *
 * @param length_r as for xcs_get_data_status()
 */
XCS_EXPORT xcs_status
xcs_units_get(xcs_core *core, char *buffer, size_t size, size_t *length_r);

/** Set the unit of a group (one of its "choices"); applied at once
    (the map too) and saved in the profile. */
XCS_EXPORT xcs_status
xcs_units_set(xcs_core *core, uint32_t group, uint32_t unit);

/** Load XCSoar's unit preset `index` (see "presets"); saved. */
XCS_EXPORT xcs_status
xcs_units_preset(xcs_core *core, uint32_t index);

/** Sound options (XCSoar's profile keys in brackets). */
typedef enum xcs_sound_option {
  /** 0/1: the vario sound, XCSoar's synthesiser [AudioVario2] */
  XCS_SOUND_VARIO = 1,
  /** 0..100: its level in XCSoar's mixer; the system volume (media
      stream on Android) applies on top [SoundVolume] */
  XCS_SOUND_VARIO_VOLUME = 2,
  /** 0 manual (the vario), 1 auto: speed to fly in cruise, which needs
      airspeed and a total energy vario, else the vario
      [VarioSoundSwitchingMode] */
  XCS_SOUND_VARIO_SWITCHING = 3,
  /** 0/1: silent while the lift is in the dead band
      [VarioDeadBandEnabled] */
  XCS_SOUND_VARIO_DEAD_BAND = 4,
  /** cm/s, -500..0: the dead band's lower edge [VarioDeadBandMin] */
  XCS_SOUND_VARIO_DEAD_BAND_MIN = 5,
  /** cm/s, 0..200: the dead band's upper edge [VarioDeadBandMax] */
  XCS_SOUND_VARIO_DEAD_BAND_MAX = 6,
} xcs_sound_option;

/** Set a sound option; saved in the profile.  XCS_ERROR_INVALID_ARGUMENT
    for an unknown option or value, XCS_ERROR_FAILED if this device has
    no audio output for the vario. */
XCS_EXPORT xcs_status
xcs_sound_set_option(xcs_core *core, uint32_t option, int32_t value);

XCS_EXPORT xcs_status
xcs_sound_get_option(xcs_core *core, uint32_t option, int32_t *value_r);

/** Copy the latest snapshot. */
XCS_EXPORT xcs_status
xcs_get_snapshot(xcs_core *core, xcs_flight_snapshot *snapshot);

enum {
  XCS_WAYPOINTS_ALL = 0,
  /** Airfields and outlandings. */
  XCS_WAYPOINTS_LANDABLE = 1,
  XCS_WAYPOINTS_AIRPORT = 2,
};

/**
 * Search the waypoints like XCSoar's waypoint list: name (a substring,
 * NULL or "" for all) and type filter, nearest first (from the
 * aircraft, else from home), at most max entries, as JSON:
 *
 *   [{"id": 42, "name": "Anduze", "landable": true, "airport": false,
 *     "elevation": 136.0, "distance": 23400.0, "bearing": 41.0,
 *     "reachable": true, "arrival": 340}, ...]
 *
 * "arrival" is the arrival height in metres as XCSoar's map labels
 * show it (around terrain when known, else straight glide); it and
 * "reachable" are only there for landables, with a GPS fix.  Buffer rules as for xcs_get_data_status().
 */
XCS_EXPORT xcs_status
xcs_waypoints_search(xcs_core *core, const char *name, uint32_t filter,
                     uint32_t max, char *buffer, size_t size,
                     size_t *length_r);

/**
 * Fly directly to a waypoint (XCSoar's "Go to"): the next point of the
 * snapshot becomes that waypoint.  waypoint_id comes from
 * xcs_map_items_at().  XCS_ERROR_FAILED when the profile only allows
 * landable go-to targets and this one is not.
 */
XCS_EXPORT xcs_status
xcs_goto_waypoint(xcs_core *core, uint32_t waypoint_id);

/*
 * Airspace warnings, as XCSoar's warning manager computes them.
 */

/**
 * The active (not acknowledged) airspace warnings, most severe first, as
 * a JSON array (UTF-8, null-terminated):
 *
 *   [{"id": "0x7c3a...", "state": "inside", "name": "LF-R 46 N",
 *     "class": "Restricted", "top": "FL95", "base": "SFC",
 *     "distance": 1200.0, "time": 85.0}, ...]
 *
 * "state" is inside, near (predicted to be entered soon) or task (the
 * task crosses it); "distance" (m) and "time" (s) to the airspace are
 * present when known.  "id" is for xcs_airspace_acknowledge().  Buffer
 * rules as for xcs_get_data_status().  XCS_GCE_AIRSPACE_* events tell
 * when to ask again.
 */
XCS_EXPORT xcs_status
xcs_get_airspace_warnings(xcs_core *core, char *buffer, size_t size,
                          size_t *length_r);

enum {
  /** Until the warning changes (e.g. from near to inside). */
  XCS_ACK_WARNING = 0,
  /** For the rest of the day. */
  XCS_ACK_DAY = 1,
};

XCS_EXPORT xcs_status
xcs_airspace_acknowledge(xcs_core *core, const char *id, uint32_t mode);

/** Airspace warning options (profile keys in brackets).  The core
    computes warnings only when XCS_AIRSPACE_WARNINGS is on; the others
    say how the app presents them, and the core only keeps them. */
typedef enum xcs_airspace_option {
  /** 0/1: XCSoar's airspace warnings, on by default [AirspaceWarn] */
  XCS_AIRSPACE_WARNINGS = 1,
  /** 0/1: a tone for a new or worse warning, on by default
      [MobileAirspaceSound] */
  XCS_AIRSPACE_ALERT_SOUND = 2,
  /** 0/1: vibrate for a new or worse warning, on by default
      [MobileAirspaceVibration] */
  XCS_AIRSPACE_ALERT_VIBRATION = 3,
  /** 0..600: hide a warning after this many seconds, 0 (the default)
      keeps it until acknowledged [MobileAirspaceAutoHide] */
  XCS_AIRSPACE_AUTO_HIDE = 4,
  /** 10..1000: warn this many seconds before entering, 30 by default
      [WarningTime] */
  XCS_AIRSPACE_WARNING_TIME = 5,
  /** 10..1000: an acknowledged warning stays quiet this many seconds,
      30 by default [AcknowledgementTime] */
  XCS_AIRSPACE_ACK_TIME = 6,
} xcs_airspace_option;

/** Set an airspace warning option; saved in the profile.
    XCS_ERROR_INVALID_ARGUMENT for an unknown option or value. */
XCS_EXPORT xcs_status
xcs_airspace_set_option(xcs_core *core, uint32_t option, int32_t value);

XCS_EXPORT xcs_status
xcs_airspace_get_option(xcs_core *core, uint32_t option, int32_t *value_r);

/*
 * Safety: the margins of XCSoar's glide computer (its "Safety factors"
 * settings).
 */

typedef enum xcs_safety_option {
  /** m, 0..2000: height above the field to arrive at
      [SafetyAltitudeArrival] */
  XCS_SAFETY_ARRIVAL_HEIGHT = 1,
  /** m, 0..1000: terrain clearance on final glide
      [SafetyAltitudeTerrain] */
  XCS_SAFETY_TERRAIN_HEIGHT = 2,
  /** m/s, 0..10, in 0.1 steps: MacCready for reach and arrival heights
      [SafetyMacCready] */
  XCS_SAFETY_MC = 3,
  /** 0..1, in 0.1 steps: lowers MacCready for speed to fly when low
      [RiskGamma] */
  XCS_SAFETY_RISK_FACTOR = 4,
  /** How alternates are sorted: 0 nearest, 1 along the task, 2 toward
      home [AbortTaskMode] */
  XCS_SAFETY_ALTERNATES = 5,
  /** 0/1: the turn back marker on the map [TurnBackMarkerEnabled] */
  XCS_SAFETY_TURN_BACK_MARKER = 6,
} xcs_safety_option;

/** Set a safety option; saved in the profile.  XCS_ERROR_INVALID_ARGUMENT
    for an unknown option or a value out of range. */
XCS_EXPORT xcs_status
xcs_safety_set_option(xcs_core *core, uint32_t option, double value);

XCS_EXPORT xcs_status
xcs_safety_get_option(xcs_core *core, uint32_t option, double *value_r);

/**
 * XCSoar's airspace classes, in its numbering, with whether each is
 * drawn on the map and warned of, and how many the loaded airspace
 * files hold (by class, or by type when a file gives none, as the
 * warnings filter them), as a JSON array (UTF-8, null-terminated):
 *
 *   [{"class": 2, "name": "Prohibited", "display": true,
 *     "warning": true, "count": 14}, ...]
 *
 * Buffer rules as for xcs_get_data_status().
 */
XCS_EXPORT xcs_status
xcs_airspace_classes(xcs_core *core, char *buffer, size_t size,
                     size_t *length_r);

/** Draw (@p display 0/1) and warn of (@p warning 0/1) one class; saved
    in the profile [AirspaceDisplay<n>, AirspaceWarning<n>]. */
XCS_EXPORT xcs_status
xcs_airspace_set_class(xcs_core *core, uint32_t airspace_class,
                       int32_t display, int32_t warning);

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

/*
 * The task, like XCSoar's task manager: the pilot edits a copy of the
 * active task, which replaces it on XCS_TASK_COMMIT, so a half-built
 * task never guides the glider.
 */

enum {
  XCS_TASK_ACTIVE = 0,
  XCS_TASK_EDITED = 1,
};

/**
 * The active or the edited task as JSON (UTF-8, null-terminated):
 *
 *   {"type": 4, "type_name": "Racing", "name": "", "editing": false,
 *    "valid": true, "errors": "", "distance": 123400.0, "active": 1,
 *    "types": [{"type": 0, "name": "FAI badges/records"}, ...],
 *    "points": [{"waypoint_id": 42, "name": "Anduze", "kind": "start",
 *                "type": 1, "type_name": "Start line", "radius": 1000.0,
 *                "leg": 0.0,
 *                "types": [{"type": 1, "name": "Start line"}, ...]}, ...]}
 *
 * The task "type" is XCSoar's TaskFactoryType, a point's "type" its
 * TaskPointFactoryType; "types" lists the ones allowed, with XCSoar's
 * names.  "radius" (half the length for lines) only for zones that have
 * one; "distance_min",
 * "distance_max" and "aat_min_time" (s) only for area tasks; "active"
 * (the index of the point flown to) only for the active task.  "errors"
 * is XCSoar's validation message for an invalid task.
 *
 * @param which XCS_TASK_ACTIVE or XCS_TASK_EDITED (XCS_ERROR_FAILED if
 * no edit is in progress)
 * @param length_r as for xcs_get_data_status()
 */
XCS_EXPORT xcs_status
xcs_task_get(xcs_core *core, uint32_t which, char *buffer, size_t size,
             size_t *length_r);

/** Operations of xcs_task_edit(). */
typedef enum xcs_task_op {
  /** Start editing a copy of the active task (again, if already). */
  XCS_TASK_BEGIN = 1,
  XCS_TASK_CANCEL = 2,
  /** Make the edited task active and save it as the default task;
      XCS_ERROR_FAILED if it is not valid (editing goes on).  An empty
      task means no task. */
  XCS_TASK_COMMIT = 3,
  /** Append waypoint `value` (an id from xcs_waypoints_search()). */
  XCS_TASK_APPEND = 4,
  XCS_TASK_REMOVE = 5,
  /** Swap the points `index` and `index` + 1. */
  XCS_TASK_SWAP = 6,
  XCS_TASK_CLEAR = 7,
  /** Task type `value` (TaskFactoryType); the points follow. */
  XCS_TASK_SET_TYPE = 8,
  /** Type `value` (TaskPointFactoryType) of point `index`. */
  XCS_TASK_SET_POINT_TYPE = 9,
  /** Radius `value` (m; lines: half their length) of point `index`. */
  XCS_TASK_SET_RADIUS = 10,
  /** Minimum time `value` (s) of an area task. */
  XCS_TASK_SET_AAT_MIN_TIME = 11,
  /** Active task: fly to the next (`value` 1) or previous (-1) point. */
  XCS_TASK_ADVANCE = 12,
  /** Active task: start again, as if not flown yet. */
  XCS_TASK_RESTART = 13,
} xcs_task_op;

/**
 * Change the task.  Operations other than XCS_TASK_BEGIN, ADVANCE and
 * RESTART need an edit in progress.  XCS_ERROR_INVALID_ARGUMENT for an
 * unknown operation, a bad index or value, or a change the task type
 * does not allow.
 */
XCS_EXPORT xcs_status
xcs_task_edit(xcs_core *core, uint32_t op, uint32_t index, double value);

/**
 * The task files XCSoar finds (.tsk files in XCSoarData/tasks and the
 * tasks in other files it reads, e.g. SeeYou .cup), as JSON:
 *
 *   [{"name": "Grenoble 300.tsk", "path": "/…/Grenoble 300.tsk",
 *     "index": 0}, ...]
 *
 * @param length_r as for xcs_get_data_status()
 */
XCS_EXPORT xcs_status
xcs_task_list_files(xcs_core *core, char *buffer, size_t size,
                    size_t *length_r);

/** Load task `index` of a file into the editor (editing starts).
    XCS_ERROR_FAILED if it cannot be read. */
XCS_EXPORT xcs_status
xcs_task_load(xcs_core *core, const char *path, uint32_t index);

/** Save the edited task as XCSoarData/tasks/<name>.tsk (name: UTF-8,
    no folder, no extension).  XCS_ERROR_FAILED on I/O errors. */
XCS_EXPORT xcs_status
xcs_task_save(xcs_core *core, const char *name);

/**
 * The data of XCSoar's analysis pages (barograph, climb history, task
 * speed, contest) as a JSON object (UTF-8, null-terminated); the
 * format is described in core/host/CoreAnalysis.hpp.  Buffer rules as
 * for xcs_get_data_status().
 */
XCS_EXPORT xcs_status
xcs_get_analysis(xcs_core *core, char *buffer, size_t size,
                 size_t *length_r);

/*
 * The flight screen's tiles: XCSoar's InfoBoxes (JSON formats in
 * core/host/CoreInfoBoxes.hpp).  Buffer rules as for
 * xcs_get_data_status().
 */

/** Layouts of tiles: cruise and circling. */
enum {
  XCS_TILES_CRUISE = 0,
  XCS_TILES_CIRCLING = 1,
};

/** The InfoBox types a tile can show, with XCSoar's names. */
XCS_EXPORT xcs_status
xcs_tiles_types(xcs_core *core, char *buffer, size_t size, size_t *length_r);

/** The types shown in each layout's tiles. */
XCS_EXPORT xcs_status
xcs_tiles_layouts(xcs_core *core, char *buffer, size_t size,
                  size_t *length_r);

/** Show InfoBox @p type in tile @p tile of @p layout (saved). */
XCS_EXPORT xcs_status
xcs_tiles_set(xcs_core *core, uint32_t layout, uint32_t tile, uint32_t type);

/** The tiles of @p layout now: title, value, unit, comment, colours. */
XCS_EXPORT xcs_status
xcs_tiles_update(xcs_core *core, uint32_t layout, char *buffer, size_t size,
                 size_t *length_r);

/*
 * Planes and crew (core/host/CorePlanes.hpp has the JSON formats).
 * Buffer rules as for xcs_get_data_status().
 */

/** The plane files, the active one and the one of the last take-off. */
XCS_EXPORT xcs_status
xcs_planes_list(xcs_core *core, char *buffer, size_t size,
                size_t *length_r);

/** XCSoar's built-in polars: a JSON array of names, by index. */
XCS_EXPORT xcs_status
xcs_polars_list(xcs_core *core, char *buffer, size_t size,
                size_t *length_r);

/**
 * Create (empty @p path) or change a plane file and write its path
 * (null-terminated) into @p buffer.  @p polar is an index into
 * xcs_polars_list(), or -1 to keep the polar; a new plane needs one.
 * The active plane is flown with the new values at once.
 */
XCS_EXPORT xcs_status
xcs_plane_save(xcs_core *core, const char *path, const char *registration,
               const char *competition_id, const char *type, int32_t polar,
               uint32_t weglide_type, int double_seater,
               char *buffer, size_t size, size_t *length_r);

/**
 * One of XCSoar's built-in polars (an index into xcs_polars_list()) with
 * the plane values it brings, as a JSON object:
 *
 *   {"name": "LS-4", "reference_mass": 361.0, "empty_mass": 220.0,
 *    "max_ballast": 120.0, "wing_area": 10.5, "max_speed": 0.0,
 *    "handicap": 104}
 *
 * kg, litres, m², m/s; 0 where the polar does not say.
 */
XCS_EXPORT xcs_status
xcs_polar_get(xcs_core *core, uint32_t index, char *buffer, size_t size,
              size_t *length_r);

/** A plane's masses, ballast and limits (XCSoar's plane dialogs). */
typedef struct xcs_plane_details {
  /** sizeof(xcs_plane_details) */
  uint32_t struct_size;
  /** s, 10..300: time to dump full ballast */
  uint32_t dump_time;
  /** %, 50..150 */
  uint32_t handicap;
  uint32_t reserved;
  /** kg, 0..1000: the rigged glider */
  double empty_mass;
  /** kg, 1..1000: the polar's reference mass */
  double reference_mass;
  /** litres, 0..500 */
  double max_ballast;
  /** m/s, 0..160: limit for speed to fly; 0 none */
  double max_speed;
  /** m², 0..40 */
  double wing_area;
} xcs_plane_details;

/** Change a plane file's details; the active plane is flown with them at
    once.  XCS_ERROR_INVALID_ARGUMENT for a value out of range. */
XCS_EXPORT xcs_status
xcs_plane_set_details(xcs_core *core, const char *path,
                      const xcs_plane_details *details);

/** kg, 0..300: all on board but the empty glider and the water, for this
    flight; also the default for the next start [CrewWeightTemplate]. */
XCS_EXPORT xcs_status
xcs_set_crew_mass(xcs_core *core, double kg);

XCS_EXPORT xcs_status
xcs_get_crew_mass(xcs_core *core, double *kg_r);

/** Fly this plane from now on (saved in the profile). */
XCS_EXPORT xcs_status
xcs_plane_activate(xcs_core *core, const char *path);

/** Delete a plane file (not the active plane). */
XCS_EXPORT xcs_status
xcs_plane_delete(xcs_core *core, const char *path);

/** The pilot, co-pilot and recent co-pilots. */
XCS_EXPORT xcs_status
xcs_crew_get(xcs_core *core, char *buffer, size_t size, size_t *length_r);

/** Set the crew for the next IGC files; "" co-pilot: solo; NULL: keep. */
XCS_EXPORT xcs_status
xcs_crew_set(xcs_core *core, const char *pilot, const char *copilot);

/*
 * WeGlide (core/host/CoreWeGlide.hpp has the JSON formats).
 *
 * The functions marked "blocks" use the network: call them from a
 * worker thread (XCS_ERROR_STATE on the core main thread).  When they
 * fail with XCS_ERROR_FAILED, @p buffer holds {"error": "..."} with
 * WeGlide's message, if it fits.
 */

/** {"enabled": …, "pilot_id": …, "birthdate": "YYYY-MM-DD"} */
XCS_EXPORT xcs_status
xcs_weglide_get(xcs_core *core, char *buffer, size_t size,
                size_t *length_r);

/** @p birthdate "YYYY-MM-DD" or ""; XCS_ERROR_INVALID_ARGUMENT if it
    is not a date. */
XCS_EXPORT xcs_status
xcs_weglide_set(xcs_core *core, int enabled, uint32_t pilot_id,
                const char *birthdate);

/** Aircraft types from the downloaded list, matching @p query. */
XCS_EXPORT xcs_status
xcs_weglide_aircraft_search(xcs_core *core, const char *query, uint32_t max,
                            char *buffer, size_t size, size_t *length_r);

/** Download WeGlide's aircraft list.  Blocks. */
XCS_EXPORT xcs_status
xcs_weglide_aircraft_update(xcs_core *core, char *buffer, size_t size,
                            size_t *length_r);

/** One aircraft type, with "double_seater".  Blocks. */
XCS_EXPORT xcs_status
xcs_weglide_aircraft_get(xcs_core *core, uint32_t id, char *buffer,
                         size_t size, size_t *length_r);

/**
 * Upload an IGC file to WeGlide with the pilot's settings and the
 * plane that flew it.  XCS_ERROR_FAILED if WeGlide is not set up (see
 * the error).  Blocks.  Never retry it for a small buffer: the result
 * is small, and a second call uploads again.
 */
XCS_EXPORT xcs_status
xcs_weglide_upload(xcs_core *core, const char *igc_path, char *buffer,
                   size_t size, size_t *length_r);

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

/**
 * Parse a repository index file (XCSoar's https://download.xcsoar.org/
 * repository format, downloaded by the app) into a JSON array:
 *
 *   [{"name": "FRA_FULL.xcm", "uri": "http://...", "type": "map",
 *     "area": "fr", "description": "...", "updated": "2026-05-01",
 *     "folder": "maps", "sha256": "ab12..."}, ...]
 *
 * "type" is map, airspace, waypoint or other; "folder" is where XCSoar
 * keeps that kind of file inside XCSoarData.  Optional fields may be
 * missing.  Needs no core: it only parses.  Buffer rules as for
 * xcs_get_data_status().
 */
XCS_EXPORT xcs_status
xcs_repository_list(const char *path, char *buffer, size_t size,
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

/** Display options of the map (XCSoar's profile keys in brackets). */
typedef enum xcs_map_option {
  /** 0/1: draw the terrain [DrawTerrain] */
  XCS_MAP_TERRAIN = 1,
  /** Colour ramp of the terrain, XCSoar's numbering, e.g. 0 "Low
      lands", 1 "Mountainous", 5 "Imhof Atlas", 6 "ICAO", 11 "Pastel",
      14 "French SIA VFR Chart" [TerrainRamp] */
  XCS_MAP_TERRAIN_RAMP = 2,
  /** 0/1: roads, rivers, towns [DrawTopology] */
  XCS_MAP_TOPOGRAPHY = 3,
  /** Snail trail: 0 off, 1 long, 2 short, 3 full [SnailTrail] */
  XCS_MAP_TRAIL = 4,
} xcs_map_option;

/** Set a display option; saved in the profile.  XCS_ERROR_INVALID_ARGUMENT
    for an unknown option or value. */
XCS_EXPORT xcs_status
xcs_map_set_option(xcs_core *core, uint32_t option, int32_t value);

XCS_EXPORT xcs_status
xcs_map_get_option(xcs_core *core, uint32_t option, int32_t *value_r);

/**
 * What is on the map around pixel (x, y) (XCSoar's map item list), as a
 * JSON array, nearest first (UTF-8, null-terminated):
 *
 *   [{"type": "airspace", "name": "LF-R 46 N", "class": "Restricted",
 *     "top": "FL95", "base": "SFC"},
 *    {"type": "waypoint", "id": 42, "name": "Anduze", "landable": false,
 *     "elevation": 136.0, "frequency": "123.500", "detail": "..."},
 *    {"type": "location", "elevation": 646.0}, ...]
 *
 * "type" is one of location, self, task, airspace, thermal, waypoint,
 * traffic, other; elevations are metres; airspace limits are formatted
 * like XCSoar does.  Buffer rules as for xcs_get_data_status().
 */
XCS_EXPORT xcs_status
xcs_map_items_at(xcs_core *core, int32_t x, int32_t y, char *buffer,
                 size_t size, size_t *length_r);

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
