// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreNotam.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "DataComponents.hpp"
#include "NetComponents.hpp"
#include "Protection.hpp"
#include "NOTAM/NOTAMGlue.hpp"
#include "NOTAM/NOTAM.hpp"
#include "NOTAM/Filter.hpp"
#include "NOTAM/Config.hpp"
#include "NOTAM/Settings.hpp"
#include "Airspace/AirspaceGlue.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Formatter/TimeFormatter.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "time/BrokenDateTime.hpp"
#include "util/UTF8.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"
#include "LogFile.hpp"

#include <boost/json.hpp>

#include <algorithm>

static NOTAMGlue *
Glue() noexcept
{
  return net_components != nullptr ? net_components->notam.get() : nullptr;
}

/** Rebuild the NOTAM airspaces (after a settings change), like
    NOTAMConfigPanel::Save(). */
static void
UpdateAirspaces(NOTAMGlue &glue) noexcept
try {
  if (data_components == nullptr || data_components->airspaces == nullptr)
    return;

  const ScopeSuspendAllThreads suspend;
  glue.UpdateAirspaces(*data_components->airspaces);
  if (data_components->terrain != nullptr)
    SetAirspaceGroundLevels(*data_components->airspaces,
                            *data_components->terrain);
} catch (...) {
  LogError(std::current_exception(), "Failed to update NOTAM airspaces");
}

void
CoreNotam::LoadCached() noexcept
try {
  auto *glue = Glue();
  if (glue == nullptr || data_components == nullptr ||
      data_components->airspaces == nullptr)
    return;

  const ScopeSuspendAllThreads suspend;
  const auto &basic = CommonInterface::Basic();
  if (glue->LoadCachedNOTAMsAndUpdate(*data_components->airspaces,
                                      basic.location_available
                                      ? basic.location
                                      : GeoPoint::Invalid()) &&
      data_components->terrain != nullptr)
    SetAirspaceGroundLevels(*data_components->airspaces,
                            *data_components->terrain);
} catch (...) {
  LogError(std::current_exception(), "Failed to load cached NOTAMs");
}

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

std::string
CoreNotam::DescribeSettings() noexcept
{
  const auto &s = CommonInterface::GetComputerSettings().airspace.notam;
  return Serialize(boost::json::object{
    {"enabled", s.enabled},
    {"radius_km", s.radius_km},
    {"refresh_interval_min", s.refresh_interval_min},
    {"show_only_effective", s.show_only_effective},
    {"show_ifr", s.show_ifr},
    {"max_radius_m", s.max_radius_m},
    {"hidden_qcodes", s.hidden_qcodes.c_str()},
  });
}

/* Each Get() leaves @p value alone when @p key is missing and returns
   false when it has the wrong type or is out of range. */

static bool
Get(const boost::json::object &o, std::string_view key, bool &value) noexcept
{
  const auto *v = o.if_contains(key);
  if (v == nullptr)
    return true;
  if (!v->is_bool())
    return false;
  value = v->get_bool();
  return true;
}

static bool
Get(const boost::json::object &o, std::string_view key, unsigned &value,
    unsigned min, unsigned max) noexcept
{
  const auto *v = o.if_contains(key);
  if (v == nullptr)
    return true;
  if (!v->is_int64() || v->get_int64() < min || v->get_int64() > max)
    return false;
  value = unsigned(v->get_int64());
  return true;
}

bool
CoreNotam::SetSettings(const char *json) noexcept
try {
  if (json == nullptr)
    return false;

  boost::system::error_code ec;
  const auto value = boost::json::parse(json, ec);
  const auto *o = value.if_object();
  if (ec || o == nullptr)
    return false;

  /* all or nothing: change a copy */
  auto &settings = CommonInterface::SetComputerSettings().airspace.notam;
  NOTAMSettings s = settings;
  if (!Get(*o, "enabled", s.enabled) ||
      !Get(*o, "radius_km", s.radius_km, 1, MAX_NOTAM_REQUEST_RADIUS_KM) ||
      !Get(*o, "refresh_interval_min", s.refresh_interval_min, 0,
           MAX_NOTAM_REFRESH_INTERVAL_MIN) ||
      !Get(*o, "show_only_effective", s.show_only_effective) ||
      !Get(*o, "show_ifr", s.show_ifr) ||
      !Get(*o, "max_radius_m", s.max_radius_m, 0, 1000000))
    return false;

  if (const auto *v = o->if_contains("hidden_qcodes")) {
    const auto *q = v->if_string();
    if (q == nullptr || q->size() >= s.hidden_qcodes.capacity() ||
        !ValidateUTF8(std::string_view{*q}))
      return false;
    s.hidden_qcodes = std::string_view{*q};
  }

  const NOTAMSettings old = settings;
  settings = s;

  /* NOTAMConfigPanel's keys */
  Profile::Set(ProfileKeys::NOTAMEnabled, s.enabled);
  Profile::Set(ProfileKeys::NOTAMRadius, s.radius_km);
  Profile::Set(ProfileKeys::NOTAMRefreshInterval, s.refresh_interval_min);
  Profile::Set(ProfileKeys::NOTAMShowOnlyEffective, s.show_only_effective);
  Profile::Set(ProfileKeys::NOTAMShowIFR, s.show_ifr);
  Profile::Set(ProfileKeys::NOTAMMaxRadius, s.max_radius_m);
  Profile::Set(ProfileKeys::NOTAMHiddenQCodes, s.hidden_qcodes.c_str());
  Profile::Save();

  auto *glue = Glue();
  if (glue == nullptr)
    return true;

  glue->SetSettings(s);

  if (old.enabled && !s.enabled) {
    /* switched off: no NOTAM airspaces, and no stale cache next time */
    glue->Clear();
    UpdateAirspaces(*glue);
    glue->InvalidateCache();
    return true;
  }

  if (!s.enabled)
    return true;

  if (!old.enabled || old.radius_km != s.radius_km) {
    const auto &basic = CommonInterface::Basic();
    if (basic.location_available && basic.location.IsValid())
      glue->ForceUpdateLocation(basic.location, true);
  }

  if (old.show_only_effective != s.show_only_effective ||
      old.show_ifr != s.show_ifr || old.max_radius_m != s.max_radius_m ||
      old.hidden_qcodes != s.hidden_qcodes)
    UpdateAirspaces(*glue);

  return true;
} catch (...) {
  LogError(std::current_exception(), "Failed to set NOTAM settings");
  return false;
}

static std::string
FormatTime(std::chrono::system_clock::time_point t) noexcept
{
  const BrokenDateTime dt{t};
  if (!dt.IsPlausible())
    return {};

  char buffer[32];
  FormatISO8601(buffer, dt);
  return buffer;
}

static std::string
FormatAltitude(const AirspaceAltitude &altitude) noexcept
{
  /* the NOTAM gave none (NOTAMConverter falls back to SFC / FL656) */
  if (altitude.reference == AltitudeReference::MSL &&
      altitude.altitude == NOTAMAltitude::INVALID_ALTITUDE)
    return {};

  char buffer[64];
  AirspaceFormatter::FormatAltitudeShort(buffer, altitude);
  return buffer;
}

std::string
CoreNotam::Describe() noexcept
{
  boost::json::object o{{"loading", false}, {"total", 0}};
  boost::json::array list;

  if (auto *glue = Glue(); glue != nullptr) {
    const auto snapshot = glue->GetSnapshot();
    const auto &settings = CommonInterface::GetComputerSettings().airspace.notam;
    const auto now = std::chrono::system_clock::now();
    const auto &basic = CommonInterface::Basic();
    const bool located = basic.location_available;

    o["loading"] = glue->IsLoading();
    o["total"] = snapshot.notams.size();
    if (snapshot.last_update_time > 0)
      o["updated"] = FormatTime(std::chrono::system_clock::from_time_t(
                                  snapshot.last_update_time));

    std::vector<std::pair<double, const struct NOTAM *>> shown;
    for (const auto &notam : snapshot.notams)
      if (NOTAMFilter::ShouldDisplay(notam, settings, now, false))
        shown.emplace_back(located && notam.geometry.center.IsValid()
                           ? basic.location.Distance(notam.geometry.center)
                           : 0.,
                           &notam);
    std::stable_sort(shown.begin(), shown.end(),
                     [](const auto &a, const auto &b){
                       return a.first < b.first;
                     });

    for (const auto &[distance, notam] : shown) {
      boost::json::object n{
        {"number", notam->number},
        {"location", notam->location},
        {"text", notam->text},
        {"start", FormatTime(notam->start_time)},
        {"permanent", notam->end_time_permanent},
        {"active", notam->IsActive(now)},
        {"lower", FormatAltitude(notam->lower_altitude)},
        {"upper", FormatAltitude(notam->upper_altitude)},
      };
      if (!notam->end_time_permanent)
        n["end"] = FormatTime(notam->end_time);
      if (located && notam->geometry.center.IsValid())
        n["distance"] = distance;
      list.emplace_back(std::move(n));
    }
  }

  o["notams"] = std::move(list);
  return Serialize(o);
}

bool
CoreNotam::Refresh() noexcept
try {
  auto *glue = Glue();
  const auto &basic = CommonInterface::Basic();
  if (glue == nullptr ||
      !CommonInterface::GetComputerSettings().airspace.notam.enabled ||
      !basic.location_available || !basic.location.IsValid())
    return false;

  /* NOTAMList's "Update": the pilot asked, so say how it went */
  glue->MarkManualRefreshRequested();
  glue->ResetFetchFailureNotification();
  glue->ForceUpdateLocation(basic.location, true);
  return true;
} catch (...) {
  LogError(std::current_exception(), "Failed to refresh NOTAMs");
  return false;
}
