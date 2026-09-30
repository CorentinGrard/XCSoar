// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * xcs-replay: replay a flight through the core's C API, as fast as
 * possible and deterministically, and print what the core reports as
 * JSON lines: one "snapshot" object per interval of flight time and one
 * "event" object per event.  Used for the golden replay tests (L2) and
 * for debugging.
 *
 * Usage: xcs-replay [--interval SECONDS] [--mc M/S] DATA_DIR FLIGHT
 *
 * DATA_DIR should be empty (or not exist) to get reproducible results:
 * the core loads its profile, waypoints and task from there.
 */

#include "xcsoar_core.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

struct Options {
  double interval = 60;
  double mac_cready = 1;
  const char *data_path = nullptr;
  const char *flight = nullptr;
};

[[noreturn]] static void
Usage(const char *argv0)
{
  fprintf(stderr,
          "Usage: %s [--interval SECONDS] [--mc M/S] DATA_DIR FLIGHT\n",
          argv0);
  exit(EXIT_FAILURE);
}

static Options
ParseCommandLine(int argc, char **argv)
{
  Options o;
  int i = 1;
  for (; i < argc && argv[i][0] == '-'; ++i) {
    if (strcmp(argv[i], "--interval") == 0 && i + 1 < argc)
      o.interval = atof(argv[++i]);
    else if (strcmp(argv[i], "--mc") == 0 && i + 1 < argc)
      o.mac_cready = atof(argv[++i]);
    else
      Usage(argv[0]);
  }

  if (argc - i != 2)
    Usage(argv[0]);

  o.data_path = argv[i];
  o.flight = argv[i + 1];
  return o;
}

/**
 * Append a number with a fixed number of decimals (or null if the
 * value is not valid), so the output is stable and diffable.
 */
static void
Field(std::string &out, const char *name, bool valid, double value,
      int decimals)
{
  char buffer[64];
  if (valid && std::isfinite(value)) {
    /* avoid "-0.0" */
    const double scale = std::pow(10.0, decimals);
    if (std::round(value * scale) == 0)
      value = 0;
    snprintf(buffer, sizeof(buffer), ",\"%s\":%.*f", name, decimals, value);
  } else
    snprintf(buffer, sizeof(buffer), ",\"%s\":null", name);
  out += buffer;
}

/* everything after XCS_EVENT_REPLAY_FINISHED is live state which
   depends on timing; it is not part of the output */
static bool finished = false;

static void
OnSnapshot([[maybe_unused]] void *ctx, const xcs_flight_snapshot *s)
{
  if (finished)
    return;

  const uint32_t v = s->valid;
  std::string line = "{\"type\":\"snapshot\"";
  Field(line, "time", v & XCS_VALID_TIME, s->time_utc, 0);

  char flags[64];
  snprintf(flags, sizeof(flags),
           ",\"flying\":%s,\"circling\":%s,\"final_glide\":%s",
           (s->flags & XCS_FLAG_FLYING) ? "true" : "false",
           (s->flags & XCS_FLAG_CIRCLING) ? "true" : "false",
           (s->flags & XCS_FLAG_FINAL_GLIDE) ? "true" : "false");
  line += flags;

  Field(line, "flight_time", true, s->flight_time, 0);
  Field(line, "lat", v & XCS_VALID_LOCATION, s->latitude, 5);
  Field(line, "lon", v & XCS_VALID_LOCATION, s->longitude, 5);
  Field(line, "track", v & XCS_VALID_TRACK, s->track, 0);
  Field(line, "ground_speed", v & XCS_VALID_GROUND_SPEED, s->ground_speed, 1);
  Field(line, "tas", v & XCS_VALID_AIRSPEED, s->true_airspeed, 1);
  Field(line, "gps_alt", v & XCS_VALID_GPS_ALTITUDE, s->gps_altitude, 0);
  Field(line, "baro_alt", v & XCS_VALID_BARO_ALTITUDE, s->baro_altitude, 0);
  Field(line, "nav_alt", v & XCS_VALID_NAV_ALTITUDE, s->nav_altitude, 0);
  Field(line, "agl", v & XCS_VALID_TERRAIN, s->altitude_agl, 0);
  Field(line, "vario", v & XCS_VALID_VARIO, s->vario, 1);
  Field(line, "avg_vario", v & XCS_VALID_VARIO, s->average_vario, 1);
  Field(line, "netto", v & XCS_VALID_NETTO_VARIO, s->netto_vario, 1);
  Field(line, "wind_speed", v & XCS_VALID_WIND, s->wind_speed, 1);
  Field(line, "wind_bearing", v & XCS_VALID_WIND, s->wind_bearing, 0);
  Field(line, "mc", true, s->mac_cready, 1);
  Field(line, "next_distance", v & XCS_VALID_NEXT_WAYPOINT,
        s->next_distance, 0);
  Field(line, "final_glide_alt_diff", v & XCS_VALID_FINAL_GLIDE,
        s->final_glide_altitude_difference, 0);
  line += "}";

  puts(line.c_str());
}

static void
OnEvent([[maybe_unused]] void *ctx, const xcs_event *e)
{
  if (finished)
    return;

  switch (e->type) {
  case XCS_EVENT_GLIDE_COMPUTER:
    printf("{\"type\":\"event\",\"gce\":%u}\n", e->code);
    break;

  case XCS_EVENT_REPLAY_FINISHED:
    puts("{\"type\":\"replay_finished\"}");
    finished = true;
    break;

  default:
    /* messages depend on data files and language; not part of the
       golden output */
    break;
  }
}

int
main(int argc, char **argv)
{
  const Options o = ParseCommandLine(argc, argv);

  xcs_config config{};
  config.struct_size = sizeof(config);
  config.api_version = XCS_API_VERSION;
  config.data_path = o.data_path;
  config.on_snapshot = OnSnapshot;
  config.on_event = OnEvent;
  config.flags = XCS_CONFIG_NO_DEVICES;

  xcs_core *core;
  if (xcs_create(&config, &core) != XCS_OK ||
      xcs_start(core) != XCS_OK) {
    fprintf(stderr, "Failed to start the core\n");
    return EXIT_FAILURE;
  }

  xcs_status status = xcs_set_mac_cready(core, o.mac_cready);
  if (status == XCS_OK) {
    uint32_t fixes = 0;
    status = xcs_replay_run(core, o.flight, o.interval, &fixes);
    fprintf(stderr, "%u fixes\n", fixes);
  }

  xcs_destroy(core);

  if (status != XCS_OK) {
    fprintf(stderr, "Replay failed\n");
    return EXIT_FAILURE;
  }

  return EXIT_SUCCESS;
}
