// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "RaspStore.hpp"
#include "util/StringFormat.hpp"
#include "Language/Language.hpp"
#include "Units/Units.hpp"
#include "system/ConvertPathName.hpp"
#include "system/FileUtil.hpp"
#include "system/Path.hpp"
#include "time/BrokenDateTime.hpp"
#include "io/ZipArchive.hpp"
#include "util/StringCompare.hxx"
#include "util/Macros.hpp"
#include "zzip/zzip.h"
#include "LogFile.hpp"

#include <set>

#include <string>
#include <cassert>
#include <stdio.h>
#include <windef.h> // for MAX_PATH

#define RASP_FORMAT "%s.curr.%02u%02ulst.d2.jp2"

static constexpr const char *WSTAR_HELP =
  N_("Average dry thermal updraft strength near mid-BL height. Subtract glider descent rate to get average vario reading for cloudless thermals. Updraft strengths will be stronger than this forecast if convective clouds are present, since cloud condensation adds buoyancy aloft (i.e. this neglects \"cloudsuck\"). W* depends upon both the surface heating and the BL depth.");

/* thermalmap.info's fields (https://www.thermalmap.info) */

static constexpr const char *THERMAL_STRENGTH_HELP =
  N_("Expected climb rate in thermals, before subtracting the glider's own "
     "sink rate.");

static constexpr const char *THERMAL_HEIGHT_HELP =
  N_("Height above sea level that thermals reach (top of the convective "
     "boundary layer).");

static constexpr const char *CONVERGENCE_HELP =
  N_("Vertical air motion caused by converging winds. Positive values mark "
     "convergence lines with better lift, negative values sinking air that "
     "suppresses thermals.");

static constexpr const char *CLOUD_HELP =
  N_("Forecast cloud cover in percent.");

static constexpr const char *WIND_HELP =
  N_("Forecast wind speed at this height above sea level.");

static constexpr const char *WAVE_HELP =
  N_("Vertical air motion at this height above sea level: lee waves and "
     "other lift or sink.");

static constexpr const char *XC_SPEED_HELP =
  N_("Expected cross-country speed of this glider type.");

static constexpr const char *PFD_HELP =
  N_("Potential flight distance of this glider type over the whole day.");

static constexpr RaspStore::MapInfo WeatherDescriptors[] = {
  {
    "wstar",
    N_("W*"),
    WSTAR_HELP,
  },
  {
    "wstar_bsratio",
    N_("W*"),
    WSTAR_HELP,
  },
  {
    "blwindspd",
    N_("BL Wind spd"),
    N_("The speed and direction of the vector-averaged wind in the BL. This prediction can be misleading if there is a large change in wind direction through the BL."),
  },
  {
    "hbl",
    N_("H bl"),
    N_("Height of the top of the mixing layer, which for thermal convection is the average top of a dry thermal. Over flat terrain, maximum thermalling heights will be lower due to the glider descent rate and other factors. In the presence of clouds (which release additional buoyancy aloft, creating \"cloudsuck\") the updraft top will be above this forecast, but the maximum thermalling height will then be limited by the cloud base. Further, when the mixing results from shear turbulence rather than thermal mixing this parameter is not useful for glider flying."),
  },
  {
    "dwcrit",
    N_("dwcrit"),
    nullptr,
  },
  {
    "blcloudpct",
    N_("bl cloud"),
    N_("This parameter provides an additional means of evaluating the formation of clouds within the BL and might be used either in conjunction with or instead of the other cloud prediction parameters. It assumes a very simple relationship between cloud cover percentage and the maximum relative humidity within the BL. The cloud base height is not predicted, but is expected to be below the BL Top height."),
  },
  {
    "sfctemp",
    N_("Sfc temp"),
    N_("The temperature at a height of 2m above ground level. This can be compared to observed surface temperatures as an indication of model simulation accuracy; e.g. if observed surface temperatures are significantly below those forecast, then soaring conditions will be poorer than forecast."),
  },
  {
    "hwcrit",
    N_("hwcrit"),
    nullptr,
  },
  {
    "wblmaxmin",
    N_("wblmaxmin"),
    N_("Maximum grid-area-averaged extensive upward or downward motion within the BL as created by horizontal wind convergence. Positive convergence is associated with local small-scale convergence lines. Negative convergence (divergence) produces subsiding vertical motion, creating low-level inversions which limit thermalling heights."),
  },
  {
    "blcwbase",
    N_("blcwbase"),
    nullptr,
  },
  {
    "ThermalStrength",
    N_("Thermal strength"),
    THERMAL_STRENGTH_HELP,
  },
  {
    "ThermalHeight",
    N_("Thermal height"),
    THERMAL_HEIGHT_HELP,
  },
  {
    "Convergence",
    N_("Convergence"),
    CONVERGENCE_HELP,
  },
  {
    "Cloudfraction_Low",
    N_("Low clouds"),
    CLOUD_HELP,
  },
  {
    "Cloudfraction_Mid",
    N_("Mid-level clouds"),
    CLOUD_HELP,
  },
  {
    "Cloudfraction_High",
    N_("High clouds"),
    CLOUD_HELP,
  },
  {
    "Cloudfraction_Accumulated",
    N_("Total clouds"),
    CLOUD_HELP,
  },
  {
    "Rain",
    N_("Rain"),
    N_("Forecast precipitation."),
  },
  {
    "XCSpeed_LS4",
    N_("XC speed LS4"),
    XC_SPEED_HELP,
  },
  {
    "XCSpeed_DuoDiscus",
    N_("XC speed Duo Discus"),
    XC_SPEED_HELP,
  },
  {
    "XCSpeed_K8",
    N_("XC speed K8"),
    XC_SPEED_HELP,
  },
  {
    "PFD_Day_LS4",
    N_("Flight distance LS4"),
    PFD_HELP,
  },
  {
    "PFD_Day_DuoDiscus",
    N_("Flight distance Duo Discus"),
    PFD_HELP,
  },
  {
    "PFD_Day_K8",
    N_("Flight distance K8"),
    PFD_HELP,
  },
  {
    "BL_AverageWindSpeed",
    N_("BL wind"),
    N_("Average wind speed in the boundary layer, where thermals are."),
  },
  {
    "VerticalWindShear",
    N_("Wind shear"),
    N_("Change of wind with height in the boundary layer. Strong shear "
       "breaks thermals up."),
  },
  {
    "Temperature2m",
    N_("Temperature 2 m"),
    N_("The temperature at a height of 2m above ground level."),
  },
  {
    "SurfaceHeatFlux",
    N_("Surface heating"),
    N_("Heat flowing from the ground into the air, which drives thermals."),
  },
  {
    "SeaLevelPressure",
    N_("Sea level pressure"),
    nullptr,
  },
  {
    "Windspeed_10m",
    N_("Wind 10 m"),
    N_("Forecast wind speed 10m above the ground."),
  },
  {
    "Windspeed_500m",
    N_("Wind 500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_1000m",
    N_("Wind 1000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_1500m",
    N_("Wind 1500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_2000m",
    N_("Wind 2000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_2500m",
    N_("Wind 2500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_3000m",
    N_("Wind 3000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_3500m",
    N_("Wind 3500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_4000m",
    N_("Wind 4000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_4500m",
    N_("Wind 4500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_5000m",
    N_("Wind 5000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_5500m",
    N_("Wind 5500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_6000m",
    N_("Wind 6000 m"),
    WIND_HELP,
  },
  {
    "Windspeed_6500m",
    N_("Wind 6500 m"),
    WIND_HELP,
  },
  {
    "Windspeed_7000m",
    N_("Wind 7000 m"),
    WIND_HELP,
  },
  {
    "Wave_1000m",
    N_("Wave 1000 m"),
    WAVE_HELP,
  },
  {
    "Wave_2000m",
    N_("Wave 2000 m"),
    WAVE_HELP,
  },
  {
    "Wave_3000m",
    N_("Wave 3000 m"),
    WAVE_HELP,
  },
  {
    "Wave_4000m",
    N_("Wave 4000 m"),
    WAVE_HELP,
  },
  {
    "Wave_5000m",
    N_("Wave 5000 m"),
    WAVE_HELP,
  },
  {
    "Wave_6000m",
    N_("Wave 6000 m"),
    WAVE_HELP,
  },
  {
    "Wave_7000m",
    N_("Wave 7000 m"),
    WAVE_HELP,
  },
};

RaspStore::MapItem::MapItem(const char *_name)
  :name(_name)
{
  std::fill_n(times, ARRAY_SIZE(times), false);
}

BrokenDateTime
RaspStore::GetFileModifiedTime() const noexcept
{
  if (path == nullptr || path.empty() || !File::Exists(path))
    return BrokenDateTime::Invalid();

  const auto modified = File::GetLastModification(path);
  if (modified == std::chrono::system_clock::time_point{})
    return BrokenDateTime::Invalid();

  const BrokenDateTime dt{modified};
  if (!dt.IsPlausible())
    return BrokenDateTime::Invalid();

  return dt.ToLocal();
}

BrokenTime
RaspStore::IndexToTime(unsigned index)
{
  return BrokenTime(index / 4, (index % 4) * 15);
}

unsigned
RaspStore::TimeToIndex(BrokenTime t) noexcept
{
  return unsigned(t.hour) * 4u + unsigned(t.minute) / 15u;
}

unsigned
RaspStore::CountAvailableTimes(unsigned item_index) const noexcept
{
  if (item_index >= maps.size())
    return 0;

  unsigned n = 0;
  for (unsigned i = 0; i < MAX_WEATHER_TIMES; ++i)
    if (maps[item_index].times[i])
      ++n;
  return n;
}

bool
RaspStore::HasSelectedTimeData(unsigned item_index, bool auto_advance,
                               BrokenTime manual_time,
                               BrokenTime auto_local_time) const noexcept
{
  if (item_index >= maps.size())
    return false;

  /* All-day / single-slot fields always have displayable raster data;
     RaspCache snaps to that slot via GetNearestTime. */
  if (IsSingleTimeField(item_index))
    return true;

  const BrokenTime forecast = (auto_advance || !manual_time.IsPlausible())
    ? auto_local_time
    : manual_time;
  if (!forecast.IsPlausible())
    return false;

  const unsigned time_index = TimeToIndex(forecast);
  return IsTimeAvailable(item_index, time_index);
}

unsigned
RaspStore::GetNearestTime(unsigned item_index, unsigned time_index) const
{
  if (item_index >= maps.size() || time_index >= MAX_WEATHER_TIMES)
    return MAX_WEATHER_TIMES;

  assert(time_index < MAX_WEATHER_TIMES);

  // scan forward to next valid time
  for (unsigned t = time_index; t < MAX_WEATHER_TIMES; ++t)
    if (IsTimeAvailable(item_index, t))
      return t;

  for (int t = time_index; t >= 0; --t)
    if (IsTimeAvailable(item_index, t))
      return t;

  return MAX_WEATHER_TIMES;
}

bool
RaspStore::WeatherFilename(char *filename, Path name,
                                          unsigned time_index)
{
  if (filename == nullptr || MAX_PATH <= 0)
    return false;

  filename[0] = '\0';

  const NarrowPathName narrow_name(name);
  if (!narrow_name.IsDefined())
    return false;

  const BrokenTime t = IndexToTime(time_index);
  const int n = StringFormat(filename, MAX_PATH, RASP_FORMAT,
                         (const char *)narrow_name, t.hour, t.minute);
  if (n < 0 || n >= MAX_PATH) {
    filename[0] = '\0';
    return false;
  }

  return true;
}

std::unique_ptr<ZipArchive>
RaspStore::OpenArchive() const
{
  if (path == nullptr || path.empty())
    return nullptr;

  return std::make_unique<ZipArchive>(path);
}

bool
RaspStore::ExistsItem(const ZipArchive &archive, Path name, unsigned time_index)
{
  char filename[MAX_PATH];
  if (!WeatherFilename(filename, name, time_index))
    return false;

  return archive.Exists(filename);
}

bool
RaspStore::ScanMapItem(const ZipArchive &archive, MapItem &item)
{
  bool found = false;
  for (unsigned i = 0; i < MAX_WEATHER_TIMES; i++)
    if (ExistsItem(archive, Path(item.name), i))
      found = item.times[i] = true;

  return found;
}

void
RaspStore::ScanAll()
try {
  /* not holding the lock here, because this method is only called
     during startup, when the other threads aren't running yet */

  auto archive = OpenArchive();
  if (!archive)
    return;

  maps.clear();

  std::set<std::string> names;

  for (const auto &i : WeatherDescriptors) {
    if (maps.full())
      break;

    MapItem item(i.name);
    item.label = i.label;
    item.help = i.help;
    if (ScanMapItem(*archive, item))
      maps.push_back(item);

    names.insert(i.name);
  }

  std::string name;
  while (!maps.full() && !(name = archive->NextName()).empty()) {
    if (!StringEndsWith(name.c_str(), ".jp2"))
      continue;

    MapItem item("");

    auto dot = name.find('.');
    if (dot == name.npos || dot == 0 ||
        dot >= item.name.capacity())
      continue;

    item.name.SetASCII(std::string_view{name}.substr(0, dot));
    item.label = nullptr;
    item.help = nullptr;

    if (!names.insert(item.name.c_str()).second)
      continue;

    if (ScanMapItem(*archive, item))
      maps.push_back(item);
  }

  // TODO: scan the rest
} catch (...) {
  LogError(std::current_exception(), "No rasp data file");
}
