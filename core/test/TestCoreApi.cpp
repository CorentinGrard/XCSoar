// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Contract tests (L1) for the C API in core/api/xcsoar_core.h.  Uses
 * only the public API, like the Kotlin binding does.
 *
 * Run from the source root (it reads test/data).
 */

#include "xcsoar_core.h"
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
      mc_kept = true;
    uint64_t last = 0;
    for (const auto &snapshot : recorder.snapshots) {
      increasing = increasing && snapshot.sequence > last;
      last = snapshot.sequence;
      flew = flew || (snapshot.flags & XCS_FLAG_FLYING);
      located = located || (snapshot.valid & XCS_VALID_LOCATION);
      mc_kept = mc_kept && equals(snapshot.mac_cready, 1.5);
    }
    ok1(increasing);
    ok1(flew);
    ok1(located);
    ok1(mc_kept);
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
  plan_tests(9 + 8 + 6 + 25 + 15 + 7);

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
