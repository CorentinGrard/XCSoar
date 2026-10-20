// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreWeGlide.hpp"
#include "CoreNetwork.hpp"
#include "Interface.hpp"
#include "Plane/Plane.hpp"
#include "Plane/PlaneFileGlue.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "Formatter/TimeFormatter.hpp"
#include "time/Calendar.hxx"
#include "Operation/Operation.hpp"
#include "net/client/WeGlide/AircraftList.hpp"
#include "net/client/WeGlide/Error.hpp"
#include "net/client/WeGlide/UploadFlight.hpp"
#include "net/client/WeGlide/ListTasks.hpp"
#include "net/client/WeGlide/DownloadTask.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/TaskBehaviour.hpp"
#include "net/http/Init.hpp"
#include "lib/curl/CoStreamRequest.hxx"
#include "lib/curl/Easy.hxx"
#include "lib/curl/Global.hxx"
#include "lib/curl/Setup.hxx"
#include "json/ParserOutputStream.hxx"
#include "json/Serialize.hxx"
#include "io/FileLineReader.hpp"
#include "io/StringOutputStream.hxx"
#include "co/Task.hxx"
#include "system/Path.hpp"
#include "util/StringStrip.hxx"
#include "util/StringCompare.hxx"
#include "util/CharUtil.hxx"

#include <boost/json.hpp>
#include <fmt/format.h>

#include <algorithm>
#include <cstdio>
#include <stdexcept>

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

std::string
CoreWeGlide::DescribeSettings() noexcept
{
  const auto &settings = CommonInterface::GetComputerSettings().weglide;

  char date[16] = "";
  if (settings.pilot_birthdate.IsPlausible())
    FormatISO8601(date, settings.pilot_birthdate);

  return Serialize(boost::json::object{
    {"enabled", settings.enabled},
    {"pilot_id", settings.pilot_id},
    {"birthdate", date},
  });
}

bool
CoreWeGlide::SetSettings(bool enabled, unsigned pilot_id,
                         const char *birthdate) noexcept
{
  BrokenDate date = BrokenDate::Invalid();
  if (birthdate != nullptr && *birthdate != '\0') {
    /* as Profile::Load(WeGlideSettings) reads it */
    unsigned year, month, day;
    if (sscanf(birthdate, "%04u-%02u-%02u", &year, &month, &day) != 3)
      return false;
    date = {year, month, day};
    if (!date.IsPlausible() || day > DaysInMonth(month, year))
      return false;
  }

  auto &settings = CommonInterface::SetComputerSettings().weglide;
  settings.enabled = enabled;
  settings.pilot_id = pilot_id;
  settings.pilot_birthdate = date;

  Profile::Set(ProfileKeys::WeGlideEnabled, enabled);
  Profile::Set(ProfileKeys::WeGlidePilotID, pilot_id);
  char buffer[16] = "";
  if (date.IsPlausible())
    FormatISO8601(buffer, date);
  Profile::Set(ProfileKeys::WeGlidePilotBirthDate, buffer);
  Profile::Save();
  return true;
}

/** @p name contains @p query, ignoring ASCII case (UTF-8 bytes of
    other letters compare as they are). */
static bool
ContainsIgnoreCase(std::string_view name, std::string_view query) noexcept
{
  return std::search(name.begin(), name.end(), query.begin(), query.end(),
                     [](char a, char b){
                       return ToUpperASCII(a) == ToUpperASCII(b);
                     }) != name.end();
}

std::string
CoreWeGlide::SearchAircraft(const char *query, unsigned max) noexcept
{
  const std::string_view q = query != nullptr ? query : "";

  boost::json::array a;
  for (const auto &type : WeGlide::LoadAircraftListCache()) {
    if (a.size() >= max)
      break;
    if (ContainsIgnoreCase(type.name.c_str(), q))
      a.emplace_back(boost::json::object{
        {"id", type.id},
        {"name", type.name.c_str()},
      });
  }
  return Serialize(a);
}

void
CoreWeGlide::UpdateAircraftList()
{
  WeGlideSettings settings;
  settings.SetDefaults();
  NullOperationEnvironment env;
  RunNetworkTask(WeGlide::DownloadAircraftList(*Net::curl, settings, env));
}

/** GET {default_url}/aircraft/{id}, as DownloadAircraftList() does. */
static Co::Task<boost::json::value>
DownloadAircraft(CurlGlobal &curl, unsigned id)
{
  const auto url = fmt::format("{}/aircraft/{}",
                               WeGlideSettings::default_url, id);
  CurlEasy easy{url.c_str()};
  Curl::Setup(easy);
  easy.SetTimeout(25);

  Json::ParserOutputStream parser;
  const auto response =
    co_await Curl::CoStreamRequest(curl, std::move(easy), parser);
  auto body = parser.Finish();

  if (response.status != 200)
    throw WeGlide::ResponseToException(response.status, body);

  co_return body;
}

std::string
CoreWeGlide::DescribeAircraft(unsigned id)
{
  const auto value = RunNetworkTask(DownloadAircraft(*Net::curl, id));
  const auto &o = value.as_object();

  auto string = [&o](const char *key) -> std::string {
    const auto *v = o.if_contains(key);
    return v != nullptr && v->is_string() ? v->as_string().c_str() : "";
  };
  const auto *double_seater = o.if_contains("double_seater");

  return Serialize(boost::json::object{
    {"id", id},
    {"name", string("name")},
    {"double_seater", double_seater != nullptr &&
                      double_seater->is_bool() && double_seater->as_bool()},
    {"kind", string("kind")},
    {"sc_class", string("sc_class")},
  });
}

/** The "HFGIDGLIDERID:" line of an IGC file's header, trimmed. */
static std::string
ReadGliderId(Path igc_path) noexcept
try {
  FileLineReaderA reader(igc_path);
  char *line;
  while ((line = reader.ReadLine()) != nullptr) {
    /* the header ends where the fixes begin */
    if (*line == 'B')
      break;

    static constexpr char prefix[] = "HFGIDGLIDERID:";
    if (StringStartsWithIgnoreCase(line, prefix)) {
      std::string_view id{line + sizeof(prefix) - 1};
      return std::string{Strip(id)};
    }
  }
  return {};
} catch (...) {
  return {};
}

bool
CoreWeGlide::PrepareUpload(const char *igc_path,
                           UploadRequest &request) noexcept
{
  const auto &settings = CommonInterface::GetComputerSettings();
  if (!settings.weglide.IsConfigured())
    return false;

  request.settings = settings.weglide;

  /* the plane that flew it, if we know it, else the active one */
  request.aircraft_id = settings.plane.weglide_glider_type;
  const auto registration = ReadGliderId(Path{igc_path});
  if (const auto path = PlaneGlue::FindByRegistration(registration.c_str());
      path != nullptr) {
    Plane plane{};
    if (PlaneGlue::ReadFile(plane, path) && plane.weglide_glider_type != 0)
      request.aircraft_id = plane.weglide_glider_type;
  }

  return true;
}

std::string
CoreWeGlide::Upload(const char *igc_path, const UploadRequest &request)
{
  NullOperationEnvironment env;
  const auto value =
    RunNetworkTask(WeGlide::UploadFlight(*Net::curl, request.settings,
                                         request.aircraft_id,
                                         Path{igc_path}, env));

  /* the first flight of the answer, as UploadJsonInterpreter() in
     src/net/client/WeGlide/UploadIGCFile.cpp reads it */
  const auto &flight = value.as_array().at(0).as_object();
  auto string = [](const boost::json::object &o, const char *key) {
    const auto *v = o.if_contains(key);
    return v != nullptr && v->is_string()
      ? std::string{v->as_string().c_str()} : std::string{};
  };

  const auto id = flight.at("id").to_number<int64_t>();
  const auto *user = flight.if_contains("user");
  const auto *aircraft = flight.if_contains("aircraft");

  return Serialize(boost::json::object{
    {"flight_id", id},
    {"url", fmt::format("https://www.weglide.org/flight/{}", id)},
    {"date", string(flight, "scoring_date")},
    {"pilot", user != nullptr && user->is_object()
              ? string(user->as_object(), "name") : ""},
    {"aircraft", aircraft != nullptr && aircraft->is_object()
                 ? string(aircraft->as_object(), "name") : ""},
    {"registration", string(flight, "registration")},
    {"competition_id", string(flight, "competition_id")},
  });
}

static Co::Task<std::vector<WeGlide::TaskInfo>>
DownloadTaskList(CoreWeGlide::TaskList which, const WeGlideSettings &settings,
                 ProgressListener &progress)
{
  switch (which) {
  case CoreWeGlide::TaskList::MINE:
    return WeGlide::ListTasksByUser(*Net::curl, settings, settings.pilot_id,
                                    progress);
  case CoreWeGlide::TaskList::DECLARED:
    return WeGlide::ListDeclaredTasks(*Net::curl, settings, progress);
  case CoreWeGlide::TaskList::TODAY:
    return WeGlide::ListDailyCompetitions(*Net::curl, settings, progress);
  case CoreWeGlide::TaskList::SCORES:
    break;
  }
  return WeGlide::ListRecentTaskScores(*Net::curl, settings, progress);
}

std::string
CoreWeGlide::ListTasks(TaskList which, const WeGlideSettings &settings)
{
  if (which == TaskList::MINE && settings.pilot_id == 0)
    throw std::runtime_error("Set your WeGlide pilot ID first");

  NullOperationEnvironment env;
  const auto tasks = RunNetworkTask(DownloadTaskList(which, settings, env));

  boost::json::array a;
  for (const auto &task : tasks) {
    boost::json::array turnpoints;
    for (const auto &tp : task.turnpoints)
      turnpoints.emplace_back(tp.name.c_str());

    boost::json::array scores;
    for (const auto &score : task.scores)
      scores.emplace_back(boost::json::object{
        {"user", score.user_name.c_str()},
        {"points", score.points},
        /* km/h in WeGlide's answer */
        {"speed", score.speed / 3.6},
      });

    a.emplace_back(boost::json::object{
      {"id", task.id},
      {"name", task.name.c_str()},
      {"user_name", task.user_name.c_str()},
      {"distance", task.distance},
      {"kind", WeGlide::ToString(task.kind)},
      {"ruleset", task.ruleset.c_str()},
      {"scoring_date", task.scoring_date.c_str()},
      {"turnpoints", std::move(turnpoints)},
      {"scores", std::move(scores)},
    });
  }
  return Serialize(a);
}

std::unique_ptr<OrderedTask>
CoreWeGlide::DownloadTask(uint64_t id, const TaskBehaviour &behaviour)
{
  WeGlideSettings settings;
  settings.SetDefaults();
  NullOperationEnvironment env;
  return RunNetworkTask(WeGlide::DownloadTask(*Net::curl, settings, id,
                                              behaviour, nullptr, env));
}
