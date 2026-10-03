// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreWeGlide.hpp"
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
#include "net/http/Init.hpp"
#include "lib/curl/CoStreamRequest.hxx"
#include "lib/curl/Easy.hxx"
#include "lib/curl/Global.hxx"
#include "lib/curl/Setup.hxx"
#include "json/ParserOutputStream.hxx"
#include "json/Serialize.hxx"
#include "io/FileLineReader.hpp"
#include "io/StringOutputStream.hxx"
#include "co/InjectTask.hxx"
#include "co/InvokeTask.hxx"
#include "co/Task.hxx"
#include "system/Path.hpp"
#include "util/BindMethod.hxx"
#include "util/StringStrip.hxx"
#include "util/StringCompare.hxx"
#include "util/CharUtil.hxx"

#include <boost/json.hpp>
#include <fmt/format.h>

#include <algorithm>
#include <cstdio>
#include <future>
#include <optional>
#include <stdexcept>

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

/**
 * Runs a coroutine on the network thread and waits for it, like
 * ShowCoFunctionDialog() without the dialog.
 */
template<typename T>
class BlockingNetworkTask {
  std::optional<T> result;
  std::promise<void> done;

  static Co::InvokeTask
  Store(Co::Task<T> task, std::optional<T> &result)
  {
    result = co_await std::move(task);
  }

  void OnCompletion(std::exception_ptr error) noexcept {
    if (error)
      done.set_exception(error);
    else
      done.set_value();
  }

public:
  T Run(Co::Task<T> task) {
    if (Net::curl == nullptr)
      throw std::runtime_error("No network");

    auto future = done.get_future();
    Co::InjectTask inject{Net::curl->GetEventLoop()};
    inject.Start(Store(std::move(task), result),
                 BIND_THIS_METHOD(OnCompletion));
    future.get();
    return std::move(*result);
  }
};

template<typename T>
static T
RunNetworkTask(Co::Task<T> task)
{
  return BlockingNetworkTask<T>{}.Run(std::move(task));
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
