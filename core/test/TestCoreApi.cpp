// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Contract tests (L1) for the C API in core/api/xcsoar_core.h.  Uses
 * only the public API, like the Kotlin binding does.
 *
 * Run from the source root (it reads test/data).
 */

#include "xcsoar_core.h"
#include "InfoBoxes/Content/Type.hpp"
#include "TestUtil.hpp"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <limits>
#include <mutex>
#include <set>
#include <string>
#include <string_view>
#include <thread>
#include <vector>

static constexpr const char *DATA_PATH = "output/test/TestCoreApi";
static constexpr const char *FLIGHT = "test/data/01lz1hq1.igc";
static constexpr const char *AIRSPACE = "test/data/AirspaceAus-DAA.txt";
static constexpr const char *MAP = "test/data/benalla9.xcm";

struct Recorder {
  std::mutex mutex;
  std::vector<xcs_flight_snapshot> snapshots;
  std::vector<uint32_t> gce;
  std::set<std::thread::id> callback_threads;
  unsigned replay_finished = 0;

  void Clear() {
    const std::lock_guard lock{mutex};
    snapshots.clear();
    gce.clear();
    replay_finished = 0;
  }

  bool HasEvent(uint32_t code) {
    const std::lock_guard lock{mutex};
    for (auto c : gce)
      if (c == code)
        return true;
    return false;
  }
};

static void
OnSnapshot(void *ctx, const xcs_flight_snapshot *snapshot)
{
  auto &r = *static_cast<Recorder *>(ctx);
  const std::lock_guard lock{r.mutex};
  r.snapshots.push_back(*snapshot);
  r.callback_threads.insert(std::this_thread::get_id());
}

static void
OnEvent(void *ctx, const xcs_event *event)
{
  auto &r = *static_cast<Recorder *>(ctx);
  const std::lock_guard lock{r.mutex};
  r.callback_threads.insert(std::this_thread::get_id());
  if (event->type == XCS_EVENT_GLIDE_COMPUTER)
    r.gce.push_back(event->code);
  else if (event->type == XCS_EVENT_REPLAY_FINISHED)
    ++r.replay_finished;
}

static xcs_config
MakeConfig(Recorder &recorder) noexcept
{
  xcs_config config{};
  config.struct_size = sizeof(config);
  config.api_version = XCS_API_VERSION;
  config.data_path = DATA_PATH;
  config.on_snapshot = OnSnapshot;
  config.on_event = OnEvent;
  config.callback_ctx = &recorder;
  config.flags = XCS_CONFIG_NO_DEVICES;
  return config;
}

static void
TestCreateArguments(Recorder &recorder)
{
  xcs_core *core = nullptr;
  auto config = MakeConfig(recorder);

  ok1(xcs_api_version() == XCS_API_VERSION);
  ok1(xcs_create(nullptr, &core) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_create(&config, nullptr) == XCS_ERROR_INVALID_ARGUMENT);

  config.api_version = XCS_API_VERSION + 1;
  ok1(xcs_create(&config, &core) == XCS_ERROR_INVALID_ARGUMENT);
  config.api_version = XCS_API_VERSION;

  config.struct_size = 4;
  ok1(xcs_create(&config, &core) == XCS_ERROR_INVALID_ARGUMENT);
  config.struct_size = sizeof(config);

  config.data_path = "";
  ok1(xcs_create(&config, &core) == XCS_ERROR_INVALID_ARGUMENT);

  ok1(core == nullptr);

  /* NULL arguments of the other functions */
  ok1(xcs_start(nullptr) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_stop(nullptr) == XCS_ERROR_INVALID_ARGUMENT);
  xcs_destroy(nullptr);
}

static void
TestNotStarted(xcs_core *core)
{
  xcs_flight_snapshot s{};
  s.struct_size = sizeof(s);

  ok1(xcs_stop(core) == XCS_ERROR_STATE);
  ok1(xcs_set_mac_cready(core, 1) == XCS_ERROR_STATE);
  ok1(xcs_set_ballast(core, 0) == XCS_ERROR_STATE);
  ok1(xcs_set_bugs(core, 1) == XCS_ERROR_STATE);
  ok1(xcs_set_qnh(core, 1013) == XCS_ERROR_STATE);
  {
    char buffer[64];
    size_t length;
    ok1(xcs_planes_list(core, buffer, sizeof(buffer), &length) == XCS_ERROR_STATE);
    ok1(xcs_weglide_upload(core, FLIGHT, buffer, sizeof(buffer), &length) == XCS_ERROR_STATE);
  }
  {
    char buffer[16];
    size_t length;
    ok1(xcs_get_analysis(core, buffer, sizeof(buffer), &length) == XCS_ERROR_STATE);
  }
  ok1(xcs_task_edit(core, XCS_TASK_BEGIN, 0, 0) == XCS_ERROR_STATE);
  ok1(xcs_units_set(core, 2, 10) == XCS_ERROR_STATE);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO, 1) == XCS_ERROR_STATE);
  ok1(xcs_get_snapshot(core, &s) == XCS_ERROR_STATE);
  ok1(xcs_replay_run(core, FLIGHT, 60, nullptr) == XCS_ERROR_STATE);
  ok1(xcs_set_data_file(core, XCS_DATA_AIRSPACE, AIRSPACE) == XCS_ERROR_STATE);

  char buffer[64];
  size_t length;
  ok1(xcs_get_data_status(core, buffer, sizeof(buffer), &length)
      == XCS_ERROR_STATE);
}

static std::string
DataStatus(xcs_core *core)
{
  char buffer[8192];
  size_t length = 0;
  if (xcs_get_data_status(core, buffer, sizeof(buffer), &length) != XCS_OK ||
      length != std::strlen(buffer))
    return {};
  return buffer;
}

/** The number after "count": in the section of @p section. */
static long
DataCount(const std::string &status, const char *section)
{
  const auto start = status.find(std::string{"\""} + section + "\":");
  if (start == std::string::npos)
    return -1;
  const auto count = status.find("\"count\":", start);
  if (count == std::string::npos)
    return -1;
  return std::strtol(status.c_str() + count + 8, nullptr, 10);
}

static void
TestDataFiles(xcs_core *core)
{
  const auto airspace = std::filesystem::absolute(AIRSPACE).string();
  const auto map = std::filesystem::absolute(MAP).string();

  ok1(xcs_set_data_file(core, 0, airspace.c_str()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_data_file(core, 4, airspace.c_str()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_data_file(core, XCS_DATA_AIRSPACE, "\xff") == XCS_ERROR_INVALID_ARGUMENT);

  /* too small: nothing written, the needed length reported */
  char small[8] = "x";
  size_t length = 0;
  ok1(xcs_get_data_status(core, small, sizeof(small), &length)
      == XCS_ERROR_INVALID_ARGUMENT);
  ok1(length >= sizeof(small) && small[0] == 'x');

  ok1(xcs_set_data_file(core, XCS_DATA_AIRSPACE, airspace.c_str()) == XCS_OK);
  auto status = DataStatus(core);
  ok1(status.find(airspace) != std::string::npos);
  ok1(DataCount(status, "airspace") > 0);

  ok1(xcs_set_data_file(core, XCS_DATA_MAP, map.c_str()) == XCS_OK);
  status = DataStatus(core);
  ok1(status.find("\"terrain\":true") != std::string::npos);
  ok1(DataCount(status, "waypoints") > 0);

  /* removing the files unloads their data */
  ok1(xcs_set_data_file(core, XCS_DATA_AIRSPACE, nullptr) == XCS_OK);
  ok1(xcs_set_data_file(core, XCS_DATA_MAP, "") == XCS_OK);
  status = DataStatus(core);
  ok1(status.find("\"terrain\":false") != std::string::npos);
  ok1(DataCount(status, "airspace") == 0);
}

static std::string
TaskJson(xcs_core *core, uint32_t which)
{
  static char buffer[16384];
  size_t length = 0;
  if (xcs_task_get(core, which, buffer, sizeof(buffer), &length) != XCS_OK)
    return {};
  return buffer;
}

/** How often @p needle occurs in @p haystack. */
static unsigned
Count(const std::string &haystack, const char *needle)
{
  unsigned n = 0;
  for (auto i = haystack.find(needle); i != std::string::npos;
       i = haystack.find(needle, i + 1))
    ++n;
  return n;
}

/** The first @p n waypoint ids of xcs_waypoints_search(). */
static std::vector<unsigned>
WaypointIds(xcs_core *core, unsigned n)
{
  char buffer[16384];
  size_t length = 0;
  std::vector<unsigned> ids;
  if (xcs_waypoints_search(core, nullptr, XCS_WAYPOINTS_ALL, n,
                           buffer, sizeof(buffer), &length) != XCS_OK)
    return ids;

  const std::string json{buffer};
  for (auto i = json.find("\"id\":"); i != std::string::npos;
       i = json.find("\"id\":", i + 1))
    ids.push_back(std::strtoul(json.c_str() + i + 5, nullptr, 10));
  return ids;
}

static void
TestTask(xcs_core *core)
{
  const auto map = std::filesystem::absolute(MAP).string();
  ok1(xcs_set_data_file(core, XCS_DATA_MAP, map.c_str()) == XCS_OK);
  const auto ids = WaypointIds(core, 3);
  ok1(ids.size() == 3);
  if (ids.size() != 3) {
    skip(33, 0, "no waypoints");
    return;
  }

  /* editing needs XCS_TASK_BEGIN */
  ok1(TaskJson(core, XCS_TASK_EDITED).empty());
  ok1(xcs_task_edit(core, XCS_TASK_APPEND, 0, ids[0]) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, 99, 0, 0) == XCS_ERROR_INVALID_ARGUMENT);

  ok1(xcs_task_edit(core, XCS_TASK_BEGIN, 0, 0) == XCS_OK);
  ok1(xcs_task_edit(core, XCS_TASK_CLEAR, 0, 0) == XCS_OK);
  for (const unsigned id : ids)
    xcs_task_edit(core, XCS_TASK_APPEND, 0, id);
  ok1(xcs_task_edit(core, XCS_TASK_APPEND, 0, 1e9) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, XCS_TASK_APPEND, 0, 0.5) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, XCS_TASK_SWAP, 2, 0) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, XCS_TASK_SWAP, 0, 0) == XCS_OK);

  auto edited = TaskJson(core, XCS_TASK_EDITED);
  ok1(Count(edited, "\"waypoint_id\"") == 3);
  ok1(edited.find("\"editing\":true") != std::string::npos);

  /* the active task is untouched until the commit, which adds the
     finish */
  ok1(Count(TaskJson(core, XCS_TASK_ACTIVE), "\"waypoint_id\"") == 0);
  ok1(xcs_task_edit(core, XCS_TASK_COMMIT, 0, 0) == XCS_OK);
  auto active = TaskJson(core, XCS_TASK_ACTIVE);
  ok1(Count(active, "\"waypoint_id\"") == 3);
  ok1(active.find("\"kind\":\"finish\"") != std::string::npos);
  ok1(active.find("\"active\":0") != std::string::npos);
  ok1(TaskJson(core, XCS_TASK_EDITED).empty());

  /* zones and task type */
  ok1(xcs_task_edit(core, XCS_TASK_BEGIN, 0, 0) == XCS_OK);
  ok1(xcs_task_edit(core, XCS_TASK_SET_RADIUS, 1, 2345) == XCS_OK);
  ok1(xcs_task_edit(core, XCS_TASK_SET_RADIUS, 1, -1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, XCS_TASK_SET_TYPE, 0, 5 /* AAT */) == XCS_OK);
  edited = TaskJson(core, XCS_TASK_EDITED);
  ok1(edited.rfind("{\"type\":5,", 0) == 0);
  ok1(edited.find("\"aat_min_time\"") != std::string::npos);

  /* save, find and load it again */
  ok1(xcs_task_save(core, "../escape") == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_save(core, "core-api-test") == XCS_OK);
  char buffer[16384];
  size_t length = 0;
  ok1(xcs_task_list_files(core, buffer, sizeof(buffer), &length) == XCS_OK &&
      std::strstr(buffer, "core-api-test.tsk") != nullptr);
  const auto saved = std::filesystem::absolute(
    std::filesystem::path{DATA_PATH} / "tasks" / "core-api-test.tsk").string();
  ok1(xcs_task_edit(core, XCS_TASK_CANCEL, 0, 0) == XCS_OK);
  ok1(xcs_task_load(core, saved.c_str(), 0) == XCS_OK &&
      TaskJson(core, XCS_TASK_EDITED).rfind("{\"type\":5,", 0) == 0);
  ok1(xcs_task_edit(core, XCS_TASK_CANCEL, 0, 0) == XCS_OK);
  std::filesystem::remove(saved);

  /* fly it */
  ok1(xcs_task_edit(core, XCS_TASK_ADVANCE, 0, 2) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_task_edit(core, XCS_TASK_ADVANCE, 0, 1) == XCS_OK &&
      TaskJson(core, XCS_TASK_ACTIVE).find("\"active\":1") != std::string::npos);
  ok1(xcs_task_edit(core, XCS_TASK_RESTART, 0, 0) == XCS_OK);

  /* an empty task is no task */
  xcs_task_edit(core, XCS_TASK_BEGIN, 0, 0);
  xcs_task_edit(core, XCS_TASK_CLEAR, 0, 0);
  ok1(xcs_task_edit(core, XCS_TASK_COMMIT, 0, 0) == XCS_OK &&
      Count(TaskJson(core, XCS_TASK_ACTIVE), "\"waypoint_id\"") == 0);

  xcs_set_data_file(core, XCS_DATA_MAP, nullptr);
}

static std::string
UnitsJson(xcs_core *core)
{
  char buffer[8192];
  size_t length = 0;
  if (xcs_units_get(core, buffer, sizeof(buffer), &length) != XCS_OK)
    return {};
  return buffer;
}

/** A JSON getter's answer, or "" if it failed. */
template<typename F>
static std::string
GetJson(F &&get)
{
  static char buffer[65536];
  size_t length = 0;
  if (get(buffer, sizeof(buffer), &length) != XCS_OK)
    return {};
  return buffer;
}

static bool
Contains(const std::string &json, const std::string &part)
{
  return json.find(part) != std::string::npos;
}

static void
TestPlanesAndCrew(xcs_core *core)
{
  auto planes = [core]{
    return GetJson([core](char *b, size_t s, size_t *l){
      return xcs_planes_list(core, b, s, l);
    });
  };

  ok1(Contains(planes(), "\"planes\":["));
  ok1(GetJson([core](char *b, size_t s, size_t *l){
    return xcs_polars_list(core, b, s, l);
  }).starts_with("[\""));

  char path[1024];
  size_t length;
  /* a new plane needs a polar */
  ok1(xcs_plane_save(core, "", "D-TEST", "TT", "LS 4", -1, 0, 0,
                     path, sizeof(path), &length) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_plane_save(core, "", "", "TT", "LS 4", 0, 0, 0,
                     path, sizeof(path), &length) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_plane_save(core, "", "D-TEST", "TT", "LS 4", 0, 160, 0,
                     path, sizeof(path), &length) == XCS_OK &&
      Contains(path, "planes/D-TEST"));
  ok1(Contains(planes(), "\"registration\":\"D-TEST\",\"competition_id\":\"TT\""));

  ok1(xcs_plane_activate(core, "does/not/exist.xcp") == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_plane_activate(core, path) == XCS_OK);
  ok1(Contains(planes(), std::string{"\"active\":\""} + path + "\""));

  /* edit: two seats; the active plane keeps flying */
  char saved[1024];
  ok1(xcs_plane_save(core, path, "D-TEST", "TT", "Duo", -1, 160, 1,
                     saved, sizeof(saved), &length) == XCS_OK &&
      std::string{saved} == path);
  ok1(Contains(planes(), "\"type\":\"Duo\"") &&
      Contains(planes(), "\"double_seater\":true"));
  /* the active plane's details, and the polar values they start from */
  ok1(GetJson([core](char *b, size_t s, size_t *l){
    return xcs_polar_get(core, 0, b, s, l);
  }).find("\"reference_mass\":") != std::string::npos);
  {
    char buffer[64];
    ok1(xcs_polar_get(core, 9999, buffer, sizeof(buffer), &length) == XCS_ERROR_INVALID_ARGUMENT);
  }
  xcs_plane_details details{};
  details.struct_size = sizeof(details);
  details.empty_mass = 230;
  details.reference_mass = 350;
  details.max_ballast = 100;
  details.dump_time = 9;
  details.handicap = 107;
  ok1(xcs_plane_set_details(core, path, &details) == XCS_ERROR_INVALID_ARGUMENT);
  details.dump_time = 120;
  ok1(xcs_plane_set_details(core, path, &details) == XCS_OK);
  ok1(Contains(planes(), "\"empty_mass\":2.3E2,\"reference_mass\":3.5E2,"
                         "\"max_ballast\":1E2,\"dump_time\":120"));

  double crew_mass = -1;
  ok1(xcs_set_crew_mass(core, 301) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_crew_mass(core, 85) == XCS_OK);
  ok1(xcs_get_crew_mass(core, &crew_mass) == XCS_OK && crew_mass == 85);
  ok1(xcs_set_crew_mass(core, 90) == XCS_OK);

  ok1(xcs_plane_delete(core, path) == XCS_ERROR_INVALID_ARGUMENT);

  auto crew = [core]{
    return GetJson([core](char *b, size_t s, size_t *l){
      return xcs_crew_get(core, b, s, l);
    });
  };
  ok1(xcs_crew_set(core, "Jane Doe", "John Roe") == XCS_OK);
  ok1(xcs_crew_set(core, nullptr, "Ann|Smith") == XCS_OK);
  ok1(Contains(crew(), "\"pilot\":\"Jane Doe\",\"copilot\":\"Ann Smith\"") &&
      Contains(crew(), "\"copilots\":[\"Ann Smith\",\"John Roe\""));
  /* solo: no co-pilot, the recent ones stay */
  ok1(xcs_crew_set(core, nullptr, "") == XCS_OK);
  ok1(Contains(crew(), "\"copilot\":\"\"") &&
      Contains(crew(), "\"copilots\":[\"Ann Smith\""));

  auto weglide = [core]{
    return GetJson([core](char *b, size_t s, size_t *l){
      return xcs_weglide_get(core, b, s, l);
    });
  };
  ok1(xcs_weglide_set(core, 1, 1234, "1980-02-30") == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_weglide_set(core, 1, 1234, "1980-05-31") == XCS_OK);
  ok1(Contains(weglide(), "\"enabled\":true,\"pilot_id\":1234,\"birthdate\":\"1980-05-31\""));
  ok1(!GetJson([core](char *b, size_t s, size_t *l){
    return xcs_weglide_aircraft_search(core, "ls", 5, b, s, l);
  }).empty());

  /* not set up: fails before using the network */
  ok1(xcs_weglide_set(core, 0, 1234, "1980-05-31") == XCS_OK);
  char answer[512];
  ok1(xcs_weglide_upload(core, FLIGHT, answer, sizeof(answer), &length) == XCS_ERROR_FAILED &&
      Contains(answer, "\"error\":"));
}

static void
TestTiles(xcs_core *core)
{
  using namespace InfoBoxFactory;

  const auto types = GetJson([core](char *b, size_t s, size_t *l){
    return xcs_tiles_types(core, b, s, l);
  });
  ok1(Contains(types, "{\"id\":" + std::to_string(e_Speed_GPS) + ",\"name\":"));
  /* graphics only: no text for a tile */
  ok1(!Contains(types, "{\"id\":" + std::to_string(e_Barogram) + ","));

  auto layouts = [core]{
    return GetJson([core](char *b, size_t s, size_t *l){
      return xcs_tiles_layouts(core, b, s, l);
    });
  };
  /* the profile outlives a test run: start from the design's tiles */
  xcs_tiles_set(core, XCS_TILES_CRUISE, 0, NavAltitude);
  ok1(Contains(layouts(), "{\"layouts\":[[" + std::to_string(NavAltitude) + "," +
                          std::to_string(e_HeightAGL) + ","));

  ok1(xcs_tiles_set(core, 2, 0, e_Speed_GPS) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_tiles_set(core, XCS_TILES_CRUISE, 6, e_Speed_GPS) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_tiles_set(core, XCS_TILES_CRUISE, 0, e_Barogram) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_tiles_set(core, XCS_TILES_CRUISE, 0, e_Speed_GPS) == XCS_OK);
  ok1(Contains(layouts(), "{\"layouts\":[[" + std::to_string(e_Speed_GPS) + "," +
                          std::to_string(e_HeightAGL) + ","));

  char buffer[64];
  size_t length;
  ok1(xcs_tiles_update(core, 2, buffer, sizeof(buffer), &length) == XCS_ERROR_INVALID_ARGUMENT);

  /* after the replay: upstream's InfoBoxes, with their captions */
  const auto tiles = GetJson([core](char *b, size_t s, size_t *l){
    return xcs_tiles_update(core, XCS_TILES_CRUISE, b, s, l);
  });
  ok1(Contains(tiles, "{\"type\":" + std::to_string(e_Speed_GPS) + ",\"title\":\"V GND\""));
  ok1(Contains(tiles, "\"title\":\"Vopt\"") && Contains(tiles, "\"unit\":\"km/h\""));
}

static void
TestUnits(xcs_core *core)
{
  auto json = UnitsJson(core);
  ok1(json.find("{\"unit\":10,\"name\":\"ft\"") != std::string::npos);
  ok1(json.find("\"presets\":[{\"name\":") != std::string::npos);

  /* altitude (2) in feet (10), not in km (1) */
  ok1(xcs_units_set(core, 2, 10) == XCS_OK);
  ok1(UnitsJson(core).find("{\"group\":2,\"unit\":10,") != std::string::npos);
  ok1(xcs_units_set(core, 2, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_units_set(core, 99, 1) == XCS_ERROR_INVALID_ARGUMENT);

  /* aircraft speed (4) in knots (5): wind speed (6) follows */
  ok1(xcs_units_set(core, 4, 5) == XCS_OK);
  ok1(UnitsJson(core).find("{\"group\":6,\"unit\":5}") != std::string::npos);

  ok1(xcs_units_preset(core, 99) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_units_preset(core, 0) == XCS_OK);
  json = UnitsJson(core);
  ok1(json.find("\"preset\":0") != std::string::npos &&
      json.find("{\"group\":2,\"unit\":9,") != std::string::npos);
}

static void
TestAirspaceOptions(xcs_core *core)
{
  int32_t value = -1;
  ok1(xcs_airspace_set_option(core, 99, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNINGS, 2) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_AUTO_HIDE, -1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_AUTO_HIDE, 601) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_get_option(core, XCS_AIRSPACE_WARNINGS, nullptr) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_get_option(core, 99, &value) == XCS_ERROR_INVALID_ARGUMENT);

  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNINGS, 0) == XCS_OK);
  ok1(xcs_airspace_get_option(core, XCS_AIRSPACE_WARNINGS, &value) == XCS_OK && value == 0);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_AUTO_HIDE, 10) == XCS_OK);
  ok1(xcs_airspace_get_option(core, XCS_AIRSPACE_AUTO_HIDE, &value) == XCS_OK && value == 10);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_ALERT_VIBRATION, 0) == XCS_OK);
  ok1(xcs_airspace_get_option(core, XCS_AIRSPACE_ALERT_VIBRATION, &value) == XCS_OK && value == 0);

  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNING_TIME, 9) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNING_TIME, 60) == XCS_OK);
  ok1(xcs_airspace_get_option(core, XCS_AIRSPACE_WARNING_TIME, &value) == XCS_OK && value == 60);

  /* class 3 is XCSoar's DANGER */
  ok1(xcs_airspace_set_class(core, 999, 1, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_airspace_set_class(core, 3, 1, 0) == XCS_OK);
  {
    char buffer[16384];
    size_t length = 0;
    ok1(xcs_airspace_classes(core, buffer, sizeof(buffer), &length) == XCS_OK &&
        std::string_view{buffer}.find("{\"class\":3,\"name\":\"Danger Area\","
                                      "\"display\":true,\"warning\":false")
        != std::string_view::npos);
  }

  /* back to the defaults */
  ok1(xcs_airspace_set_class(core, 3, 1, 1) == XCS_OK);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNING_TIME, 30) == XCS_OK);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_WARNINGS, 1) == XCS_OK);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_AUTO_HIDE, 0) == XCS_OK);
  ok1(xcs_airspace_set_option(core, XCS_AIRSPACE_ALERT_VIBRATION, 1) == XCS_OK);
}

static void
TestSafety(xcs_core *core)
{
  double arrival = -1, mc = -1, alternates = -1, value = -1;
  ok1(xcs_safety_get_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, &arrival) == XCS_OK);
  ok1(xcs_safety_get_option(core, XCS_SAFETY_MC, &mc) == XCS_OK);
  ok1(xcs_safety_get_option(core, XCS_SAFETY_ALTERNATES, &alternates) == XCS_OK);

  ok1(xcs_safety_set_option(core, 99, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, -1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, 2001) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ARRIVAL_HEIGHT,
                            std::numeric_limits<double>::quiet_NaN()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ALTERNATES, 3) == XCS_ERROR_INVALID_ARGUMENT);

  ok1(xcs_safety_set_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, 250) == XCS_OK);
  ok1(xcs_safety_get_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, &value) == XCS_OK && value == 250);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_MC, 1.5) == XCS_OK);
  ok1(xcs_safety_get_option(core, XCS_SAFETY_MC, &value) == XCS_OK && value == 1.5);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ALTERNATES, 2) == XCS_OK);
  ok1(xcs_safety_get_option(core, XCS_SAFETY_ALTERNATES, &value) == XCS_OK && value == 2);

  ok1(xcs_safety_set_option(core, XCS_SAFETY_ARRIVAL_HEIGHT, arrival) == XCS_OK);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_MC, mc) == XCS_OK);
  ok1(xcs_safety_set_option(core, XCS_SAFETY_ALTERNATES, alternates) == XCS_OK);
}

static void
TestStarted(xcs_core *core, Recorder &recorder)
{
  ok1(xcs_start(core) == XCS_ERROR_STATE);

  xcs_flight_snapshot s{};
  s.struct_size = 4;
  ok1(xcs_get_snapshot(core, &s) == XCS_ERROR_INVALID_ARGUMENT);
  s.struct_size = sizeof(s);
  ok1(xcs_get_snapshot(core, &s) == XCS_OK);
  ok1(s.struct_size == sizeof(s));
  ok1(s.api_version == XCS_API_VERSION);
  ok1(s.sequence >= 1);

  ok1(xcs_set_mac_cready(core, -1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_mac_cready(core, 6) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_mac_cready(core, std::numeric_limits<double>::quiet_NaN()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_mac_cready(core, 1.5) == XCS_OK);

  /* the profile's plane decides the maximum (0 without water) */
  const double ballast = s.max_ballast / 2;
  ok1(xcs_set_ballast(core, -1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_ballast(core, std::numeric_limits<double>::quiet_NaN()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_ballast(core, s.max_ballast + 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_ballast(core, ballast) == XCS_OK);

  ok1(xcs_set_bugs(core, 0.4) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_bugs(core, 1.1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_bugs(core, std::numeric_limits<double>::quiet_NaN()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_bugs(core, 0.8) == XCS_OK);

  ok1(xcs_set_qnh(core, 849) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_qnh(core, 1301) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_qnh(core, std::numeric_limits<double>::quiet_NaN()) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_set_qnh(core, 1020) == XCS_OK);

  /* the host build always has a (possibly silent) vario sound */
  int32_t value = -1;
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO, 2) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_set_option(core, 99, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_VOLUME, 101) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_get_option(core, XCS_SOUND_VARIO, nullptr) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO, 1) == XCS_OK);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_VOLUME, 60) == XCS_OK);
  ok1(xcs_sound_get_option(core, XCS_SOUND_VARIO, &value) == XCS_OK && value == 1);
  ok1(xcs_sound_get_option(core, XCS_SOUND_VARIO_VOLUME, &value) == XCS_OK && value == 60);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO, 0) == XCS_OK);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_SWITCHING, 2) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_DEAD_BAND_MIN, 1) == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_DEAD_BAND_MIN, -50) == XCS_OK);
  ok1(xcs_sound_get_option(core, XCS_SOUND_VARIO_DEAD_BAND_MIN, &value) == XCS_OK && value == -50);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_SWITCHING, 1) == XCS_OK);
  ok1(xcs_sound_get_option(core, XCS_SOUND_VARIO_SWITCHING, &value) == XCS_OK && value == 1);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_SWITCHING, 0) == XCS_OK);
  ok1(xcs_sound_set_option(core, XCS_SOUND_VARIO_DEAD_BAND_MIN, -30) == XCS_OK);

  ok1(xcs_replay_run(core, "test/data/does-not-exist.igc", 60, nullptr)
      == XCS_ERROR_FAILED);

  /* deterministic replay of a whole flight */
  recorder.Clear();
  uint32_t fixes = 0;
  ok1(xcs_replay_run(core, FLIGHT, 60, &fixes) == XCS_OK);
  ok1(fixes > 1000);

  {
    const std::lock_guard lock{recorder.mutex};
    ok1(recorder.snapshots.size() > 50);
    ok1(recorder.replay_finished == 1);

    bool increasing = true, flew = false, located = false,
      mc_kept = true, polar_kept = true, qnh_kept = true, baro = false;
    uint64_t last = 0;
    for (const auto &snapshot : recorder.snapshots) {
      increasing = increasing && snapshot.sequence > last;
      last = snapshot.sequence;
      flew = flew || (snapshot.flags & XCS_FLAG_FLYING);
      located = located || (snapshot.valid & XCS_VALID_LOCATION);
      mc_kept = mc_kept && equals(snapshot.mac_cready, 1.5);
      polar_kept = polar_kept && equals(snapshot.ballast, ballast) &&
        equals(snapshot.bugs, 0.8) && snapshot.wing_loading >= 0;
      qnh_kept = qnh_kept && (snapshot.valid & XCS_VALID_QNH) &&
        equals(snapshot.qnh, 1020);
      /* the IGC file's pressure altitude, with the QNH set above */
      baro = baro || ((snapshot.valid & XCS_VALID_STATIC_PRESSURE) &&
                      (snapshot.valid & XCS_VALID_BARO_ALTITUDE));
    }
    ok1(increasing);
    ok1(flew);
    ok1(located);
    ok1(mc_kept);
    ok1(polar_kept);
    ok1(qnh_kept);
    ok1(baro);
  }

  /* the analysis pages after a whole flight */
  {
    size_t length;
    ok1(xcs_get_analysis(core, nullptr, 0, &length) == XCS_ERROR_INVALID_ARGUMENT);

    char small[16];
    ok1(xcs_get_analysis(core, small, sizeof(small), &length) == XCS_ERROR_INVALID_ARGUMENT &&
        length > sizeof(small));

    std::string json(length + 1, '\0');
    ok1(xcs_get_analysis(core, json.data(), json.size(), &length) == XCS_OK);
    json.resize(length);
    ok1(json.find("\"barograph\":{\"altitude\":[[") != std::string::npos);
    ok1(json.find("\"thermals\":[[") != std::string::npos &&
        json.find("\"average\":") != std::string::npos);
    ok1(json.find("\"results\":[{") != std::string::npos &&
        json.find("\"trace\":[[") != std::string::npos);
  }

  ok1(recorder.HasEvent(XCS_GCE_TAKEOFF));
  ok1(recorder.HasEvent(XCS_GCE_FLIGHTMODE_CLIMB));
  ok1(recorder.HasEvent(XCS_GCE_FLIGHTMODE_CRUISE));

  /* callbacks only ever run on one thread, the core main thread */
  {
    const std::lock_guard lock{recorder.mutex};
    ok1(recorder.callback_threads.size() == 1);
    ok1(recorder.callback_threads.count(std::this_thread::get_id()) == 0);
  }

  /* commands from several threads at once */
  std::atomic<unsigned> failures{0};
  std::vector<std::thread> threads;
  for (unsigned i = 0; i < 4; ++i)
    threads.emplace_back([core, &failures, i]{
      xcs_flight_snapshot t{};
      t.struct_size = sizeof(t);
      for (unsigned j = 0; j < 50; ++j) {
        if (xcs_set_mac_cready(core, (i + j) % 5) != XCS_OK ||
            xcs_get_snapshot(core, &t) != XCS_OK)
          ++failures;
      }
    });
  for (auto &t : threads)
    t.join();
  ok1(failures == 0);
}

static void
TestRepositoryList()
{
  std::filesystem::create_directories(DATA_PATH);
  const std::string path = std::string{DATA_PATH} + "/repository";
  {
    FILE *f = fopen(path.c_str(), "w");
    fputs("name=FRA_FULL.xcm\n"
          "uri=http://download.xcsoar.org/source/map/region/FRA_FULL.xcm\n"
          "type=map\n"
          "area=fr\n"
          "update=2026-05-01\n"
          "\n"
          "name=france.txt\n"
          "uri=https://example.org/france.txt\n"
          "type=airspace\n", f);
    fclose(f);
  }

  char buffer[4096];
  size_t length = 0;
  ok1(xcs_repository_list(nullptr, buffer, sizeof(buffer), &length)
      == XCS_ERROR_INVALID_ARGUMENT);
  ok1(xcs_repository_list("does/not/exist", buffer, sizeof(buffer), &length)
      == XCS_ERROR_FAILED);
  ok1(xcs_repository_list(path.c_str(), buffer, sizeof(buffer), &length)
      == XCS_OK);

  const std::string json{buffer};
  ok1(json.find("\"name\":\"FRA_FULL.xcm\"") != std::string::npos);
  ok1(json.find("\"type\":\"map\"") != std::string::npos);
  ok1(json.find("\"folder\":\"maps\"") != std::string::npos);
  ok1(json.find("\"updated\":\"2026-05-01\"") != std::string::npos);
  ok1(json.find("\"type\":\"airspace\"") != std::string::npos);
}

int
main()
{
  plan_tests(9 + 17 + 6 + 55 + 15 + 35 + 11 + 23 + 11 + 7 + 23 + 17 + 8 + 9);

  Recorder recorder;
  TestCreateArguments(recorder);
  TestRepositoryList();

  const auto config = MakeConfig(recorder);
  xcs_core *core = nullptr;
  ok1(xcs_create(&config, &core) == XCS_OK);

  xcs_core *second = nullptr;
  ok1(xcs_create(&config, &second) == XCS_ERROR_BUSY);

  TestNotStarted(core);

  ok1(xcs_start(core) == XCS_OK);
  TestStarted(core, recorder);
  TestDataFiles(core);
  TestTask(core);
  TestUnits(core);
  TestAirspaceOptions(core);
  TestSafety(core);
  TestPlanesAndCrew(core);
  TestTiles(core);
  ok1(xcs_stop(core) == XCS_OK);

  /* the same core can be started again */
  ok1(xcs_start(core) == XCS_OK);
  ok1(xcs_stop(core) == XCS_OK);
  xcs_destroy(core);

  /* after destroy, a new core may be created */
  ok1(xcs_create(&config, &second) == XCS_OK);
  xcs_destroy(second);

  return exit_status();
}
