// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreTracking.hpp"
#include "Interface.hpp"
#include "Tracking/TrackingSettings.hpp"
#include "Tracking/SkyLines/Key.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Current.hpp"
#include "Profile/Map.hpp"
#include "Profile/Keys.hpp"
#include "util/NumberParser.hpp"
#include "util/UTF8.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <cinttypes>
#include <cstdio>

#ifndef HAVE_SKYLINES_TRACKING
#error The core needs live tracking (HAVE_HTTP)
#endif

/* TrackingConfigPanel's choices */
static constexpr unsigned INTERVALS[] = {
  1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 120, 180, 300, 600, 900, 1200,
  1800, 2400, 3000, 3600,
};

static constexpr unsigned MAX_VEHICLE_TYPE =
  unsigned(LiveTrack24::Settings::VehicleType::HANGGLIDER_RIGID);

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

static std::string
FormatKey(uint64_t key) noexcept
{
  char buffer[24];
  snprintf(buffer, sizeof(buffer), "%" PRIX64, key);
  return buffer;
}

std::string
CoreTracking::Describe() noexcept
{
  const auto &settings = CommonInterface::GetComputerSettings().tracking;
  const auto &sl = settings.skylines;
  const auto &lt = settings.livetrack24;
  const auto &cloud = settings.cloud;

  boost::json::value cloud_enabled = nullptr;
  if (cloud.enabled != TriState::UNKNOWN)
    cloud_enabled = cloud.enabled == TriState::TRUE;

  return Serialize(boost::json::object{
    {"skylines", {
      {"enabled", sl.enabled},
      {"roaming", sl.roaming},
      {"interval", sl.interval},
      {"traffic", sl.traffic_enabled},
      {"near_traffic", sl.near_traffic_enabled},
      {"key", FormatKey(sl.key)},
    }},
    {"livetrack24", {
      {"enabled", lt.enabled},
      {"server", lt.server.c_str()},
      {"username", lt.username.c_str()},
      {"password", lt.password.c_str()},
      {"interval", lt.interval},
      {"vehicle_type", unsigned(lt.vehicleType)},
      {"vehicle_name", lt.vehicle_name.c_str()},
    }},
    {"cloud", {
      {"enabled", cloud_enabled},
      {"show_traffic", cloud.show_traffic},
      {"show_thermals", cloud.show_thermals},
      {"roaming", cloud.roaming},
    }},
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
GetInterval(const boost::json::object &o, unsigned &value) noexcept
{
  const auto *v = o.if_contains("interval");
  if (v == nullptr)
    return true;
  if (!v->is_int64() ||
      std::find(std::begin(INTERVALS), std::end(INTERVALS),
                v->get_int64()) == std::end(INTERVALS))
    return false;
  value = unsigned(v->get_int64());
  return true;
}

/** A string that fits @p value whole (no cut UTF-8 sequence). */
template<std::size_t N>
static bool
Get(const boost::json::object &o, std::string_view key,
    StaticString<N> &value) noexcept
{
  const auto *v = o.if_contains(key);
  if (v == nullptr)
    return true;
  const auto *s = v->if_string();
  if (s == nullptr || s->size() >= N || !ValidateUTF8(std::string_view{*s}))
    return false;
  value = std::string_view{*s};
  return true;
}

/** Hexadecimal, as TrackingConfigPanel's key field; empty is 0. */
static bool
GetKey(const boost::json::object &o, uint64_t &value) noexcept
{
  const auto *v = o.if_contains("key");
  if (v == nullptr)
    return true;
  const auto *s = v->if_string();
  if (s == nullptr || s->size() > 16)
    return false;
  if (s->empty()) {
    value = 0;
    return true;
  }

  const std::string text{*s};
  char *end;
  const uint64_t key = ParseUint64(text.c_str(), &end, 16);
  if (end == text.c_str() || *end != '\0')
    return false;
  value = key;
  return true;
}

static bool
Apply(const boost::json::object &o, SkyLinesTracking::Settings &s) noexcept
{
  return Get(o, "enabled", s.enabled) && Get(o, "roaming", s.roaming) &&
    GetInterval(o, s.interval) && Get(o, "traffic", s.traffic_enabled) &&
    Get(o, "near_traffic", s.near_traffic_enabled) && GetKey(o, s.key);
}

static bool
Apply(const boost::json::object &o, LiveTrack24::Settings &s) noexcept
{
  if (!Get(o, "enabled", s.enabled) || !Get(o, "server", s.server) ||
      !Get(o, "username", s.username) || !Get(o, "password", s.password) ||
      !GetInterval(o, s.interval) ||
      !Get(o, "vehicle_name", s.vehicle_name) || s.server.empty())
    return false;

  if (const auto *v = o.if_contains("vehicle_type")) {
    if (!v->is_int64() || v->get_int64() < 0 ||
        v->get_int64() > MAX_VEHICLE_TYPE)
      return false;
    s.vehicleType = LiveTrack24::Settings::VehicleType(v->get_int64());
  }
  return true;
}

static bool
Apply(const boost::json::object &o, CloudSettings &s) noexcept
{
  if (const auto *v = o.if_contains("enabled")) {
    if (!v->is_bool())
      return false;
    s.enabled = v->get_bool() ? TriState::TRUE : TriState::FALSE;
  }

  return Get(o, "show_traffic", s.show_traffic) &&
    Get(o, "show_thermals", s.show_thermals) &&
    Get(o, "roaming", s.roaming);
}

/** A section of @p root, or null if missing; false if not an object. */
static bool
Section(const boost::json::object &root, std::string_view key,
        const boost::json::object *&section) noexcept
{
  section = nullptr;
  const auto *v = root.if_contains(key);
  if (v == nullptr)
    return true;
  section = v->if_object();
  return section != nullptr;
}

/* the keys TrackingConfigPanel and CloudConfigPanel save */
static void
Save(const TrackingSettings &settings) noexcept
{
  const auto &sl = settings.skylines;
  Profile::Set(ProfileKeys::SkyLinesTrackingEnabled, sl.enabled);
  Profile::Set(ProfileKeys::SkyLinesRoaming, sl.roaming);
  Profile::Set(ProfileKeys::SkyLinesTrackingInterval, sl.interval);
  Profile::Set(ProfileKeys::SkyLinesTrafficEnabled, sl.traffic_enabled);
  Profile::Set(ProfileKeys::SkyLinesNearTrafficEnabled,
               sl.near_traffic_enabled);
  Profile::Set(ProfileKeys::SkyLinesTrackingKey, FormatKey(sl.key).c_str());

  const auto &lt = settings.livetrack24;
  Profile::Set(ProfileKeys::LiveTrack24Enabled, lt.enabled);
  Profile::Set(ProfileKeys::LiveTrack24Server, lt.server.c_str());
  Profile::Set(ProfileKeys::LiveTrack24Username, lt.username.c_str());
  Profile::Set(ProfileKeys::LiveTrack24Password, lt.password.c_str());
  Profile::Set(ProfileKeys::LiveTrack24TrackingInterval, lt.interval);
  Profile::map.SetEnum(ProfileKeys::LiveTrack24TrackingVehicleType,
                       lt.vehicleType);
  Profile::Set(ProfileKeys::LiveTrack24TrackingVehicleName,
               lt.vehicle_name.c_str());

  const auto &cloud = settings.cloud;
  if (cloud.enabled != TriState::UNKNOWN)
    Profile::Set(ProfileKeys::CloudEnabled, cloud.enabled == TriState::TRUE);
  Profile::Set(ProfileKeys::CloudShowTraffic, cloud.show_traffic);
  Profile::Set(ProfileKeys::CloudShowThermals, cloud.show_thermals);
  Profile::Set(ProfileKeys::CloudRoaming, cloud.roaming);
  if (cloud.key != 0)
    Profile::Set(ProfileKeys::CloudKey, FormatKey(cloud.key).c_str());

  Profile::Save();
}

bool
CoreTracking::Set(const char *json) noexcept
try {
  if (json == nullptr)
    return false;

  boost::system::error_code ec;
  const auto value = boost::json::parse(json, ec);
  const auto *root = value.if_object();
  if (ec || root == nullptr)
    return false;

  const boost::json::object *skylines, *livetrack24, *cloud;
  if (!Section(*root, "skylines", skylines) ||
      !Section(*root, "livetrack24", livetrack24) ||
      !Section(*root, "cloud", cloud))
    return false;

  /* all or nothing: change a copy */
  TrackingSettings settings = CommonInterface::GetComputerSettings().tracking;
  if ((skylines != nullptr && !Apply(*skylines, settings.skylines)) ||
      (livetrack24 != nullptr && !Apply(*livetrack24, settings.livetrack24)) ||
      (cloud != nullptr && !Apply(*cloud, settings.cloud)))
    return false;

  /* the server knows this phone by its key (CloudConfigPanel) */
  if (settings.cloud.enabled == TriState::TRUE && settings.cloud.key == 0)
    settings.cloud.key = SkyLinesTracking::GenerateKey();

  /* the backend timer passes them to TrackingGlue */
  CommonInterface::SetComputerSettings().tracking = settings;
  Save(settings);
  return true;
} catch (...) {
  return false;
}
