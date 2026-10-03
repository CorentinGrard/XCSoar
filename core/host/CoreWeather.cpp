// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreWeather.hpp"
#include "CoreNetwork.hpp"
#include "Weather/NOAAGlue.hpp"
#include "Weather/NOAAUpdater.hpp"
#include "Weather/NOAAFormatter.hpp"
#include "Formatter/TimeFormatter.hpp"
#include "Operation/Operation.hpp"
#include "util/StringAPI.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <cstring>

/* NOAAStore::SaveToProfile() writes five bytes per station into 120 */
static constexpr unsigned MAX_STATIONS = 20;

void
CoreWeather::Initialise() noexcept
{
  noaa_store = new NOAAStore();
  noaa_store->LoadFromProfile();
}

void
CoreWeather::Deinitialise() noexcept
{
  delete noaa_store;
  noaa_store = nullptr;
}

static NOAAStore::iterator
Find(const char *code) noexcept
{
  return std::find_if(noaa_store->begin(), noaa_store->end(),
                      [code](const NOAAStore::Item &item){
                        return StringIsEqualIgnoreCase(item.code, code);
                      });
}

static std::string
FormatTime(const BrokenDateTime &time) noexcept
{
  if (!time.IsPlausible())
    return {};

  char buffer[32];
  FormatISO8601(buffer, time);
  return buffer;
}

static boost::json::object
Describe(const NOAAStore::Item &item) noexcept
{
  boost::json::object o{{"code", item.code}};

  if (item.parsed_metar_available) {
    const auto &p = item.parsed_metar;
    if (p.name_available)
      o["name"] = p.name.c_str();
    if (p.qnh_available)
      o["qnh"] = p.qnh.GetHectoPascal();
    if (p.wind_available) {
      o["wind_bearing"] = p.wind.bearing.Degrees();
      o["wind_speed"] = p.wind.norm;
    }
    if (p.temperatures_available) {
      o["temperature"] = p.temperature;
      o["dew_point"] = p.dew_point;
    }
    if (p.visibility_available)
      o["visibility"] = p.visibility;
    o["cavok"] = p.cavok;
  }

  if (item.metar_available) {
    o["metar"] = item.metar.content.c_str();
    o["metar_time"] = FormatTime(item.metar.last_update);
  }

  if (item.taf_available) {
    o["taf"] = item.taf.content.c_str();
    o["taf_time"] = FormatTime(item.taf.last_update);
  }

  if (item.metar_available || item.taf_available) {
    std::string text;
    NOAAFormatter::Format(item, text);
    o["text"] = text;
  }

  return o;
}

std::string
CoreWeather::Describe() noexcept
{
  boost::json::array a;
  if (noaa_store != nullptr)
    for (const auto &item : *noaa_store)
      a.push_back(::Describe(item));

  StringOutputStream os;
  Json::Serialize(os, a);
  return std::move(os).GetValue();
}

bool
CoreWeather::Add(const char *code) noexcept
{
  if (noaa_store == nullptr || code == nullptr ||
      !NOAAStore::IsValidCode(code) || Find(code) != noaa_store->end() ||
      noaa_store->Count() >= MAX_STATIONS)
    return false;

  noaa_store->AddStation(code);
  noaa_store->SaveToProfile();
  return true;
}

bool
CoreWeather::Remove(const char *code) noexcept
{
  if (noaa_store == nullptr || code == nullptr)
    return false;

  const auto i = Find(code);
  if (i == noaa_store->end())
    return false;

  noaa_store->erase(i);
  noaa_store->SaveToProfile();
  return true;
}

std::vector<std::string>
CoreWeather::Codes() noexcept
{
  std::vector<std::string> codes;
  if (noaa_store != nullptr)
    for (const auto &item : *noaa_store)
      codes.emplace_back(item.code);
  return codes;
}

NOAAStore::Item
CoreWeather::Download(const char *code)
{
  NOAAStore::Item item{};
  strncpy(item.code, code, sizeof(item.code) - 1);

  NullOperationEnvironment env;
  RunNetworkTask(NOAAUpdater::Update(item, *Net::curl, env));
  return item;
}

void
CoreWeather::Store(const NOAAStore::Item &item) noexcept
{
  if (noaa_store == nullptr)
    return;

  const auto i = Find(item.code);
  if (i == noaa_store->end())
    return;

  /* keep what this download did not get */
  if (item.metar_available) {
    i->metar = item.metar;
    i->metar_available = true;
    i->parsed_metar = item.parsed_metar;
    i->parsed_metar_available = item.parsed_metar_available;
  }
  if (item.taf_available) {
    i->taf = item.taf;
    i->taf_available = true;
  }
}
