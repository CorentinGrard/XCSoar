// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreUnits.hpp"
#include "Interface.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "Units/Units.hpp"
#include "Units/Descriptor.hpp"
#include "Units/UnitsStore.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <initializer_list>
#include <string_view>
#include <vector>

namespace {

/** A group the pilot sets, its profile key and its choices. */
struct GroupInfo {
  UnitGroup group;
  std::string_view profile_key;
  std::vector<Unit> choices;
};

}

/* the choices of UnitsConfigPanel::Prepare(); wind speed is not set on
   its own (it follows the aircraft speed) */
static const GroupInfo groups[] = {
  { UnitGroup::DISTANCE, ProfileKeys::DistanceUnitsValue,
    { Unit::STATUTE_MILES, Unit::NAUTICAL_MILES, Unit::KILOMETER } },
  { UnitGroup::ALTITUDE, ProfileKeys::AltitudeUnitsValue,
    { Unit::FEET, Unit::METER } },
  { UnitGroup::TEMPERATURE, ProfileKeys::TemperatureUnitsValue,
    { Unit::DEGREES_CELCIUS, Unit::DEGREES_FAHRENHEIT } },
  { UnitGroup::HORIZONTAL_SPEED, ProfileKeys::SpeedUnitsValue,
    { Unit::STATUTE_MILES_PER_HOUR, Unit::KNOTS, Unit::KILOMETER_PER_HOUR,
      Unit::METER_PER_SECOND } },
  { UnitGroup::VERTICAL_SPEED, ProfileKeys::LiftUnitsValue,
    { Unit::KNOTS, Unit::METER_PER_SECOND, Unit::FEET_PER_MINUTE } },
  { UnitGroup::TASK_SPEED, ProfileKeys::TaskSpeedUnitsValue,
    { Unit::STATUTE_MILES_PER_HOUR, Unit::KNOTS, Unit::KILOMETER_PER_HOUR,
      Unit::METER_PER_SECOND } },
  { UnitGroup::PRESSURE, ProfileKeys::PressureUnitsValue,
    { Unit::HECTOPASCAL, Unit::MILLIBAR, Unit::INCH_MERCURY } },
  { UnitGroup::WING_LOADING, ProfileKeys::WingLoadingUnitValue,
    { Unit::KG_PER_M2, Unit::LB_PER_FT2 } },
  { UnitGroup::MASS, ProfileKeys::MassUnitValue,
    { Unit::KG, Unit::LB } },
};

static Unit &
GetUnitRef(UnitSetting &config, UnitGroup group) noexcept
{
  switch (group) {
  case UnitGroup::DISTANCE: return config.distance_unit;
  case UnitGroup::ALTITUDE: return config.altitude_unit;
  case UnitGroup::TEMPERATURE: return config.temperature_unit;
  case UnitGroup::HORIZONTAL_SPEED: return config.speed_unit;
  case UnitGroup::VERTICAL_SPEED: return config.vertical_speed_unit;
  case UnitGroup::WIND_SPEED: return config.wind_speed_unit;
  case UnitGroup::TASK_SPEED: return config.task_speed_unit;
  case UnitGroup::PRESSURE: return config.pressure_unit;
  case UnitGroup::WING_LOADING: return config.wing_loading_unit;
  case UnitGroup::MASS: return config.mass_unit;
  case UnitGroup::NONE:
  case UnitGroup::ROTATION:
    break;
  }
  return config.rotation_unit;
}

/** Use the new settings everywhere and save them, like the panel. */
static void
Apply(UnitSetting &config) noexcept
{
  config.wind_speed_unit = config.speed_unit;
  Units::SetConfig(config);

  for (const auto &g : groups)
    Profile::Set(g.profile_key, unsigned(GetUnitRef(config, g.group)));
  Profile::Save();
}

std::string
CoreUnits::Describe() noexcept
{
  const UnitSetting &config = CommonInterface::GetUISettings().format.units;

  boost::json::array units;
  for (unsigned i = 1; i < unsigned(Unit::COUNT); ++i) {
    const auto &d = Units::unit_descriptors[i];
    units.emplace_back(boost::json::object{
      {"unit", i},
      {"name", d.name},
      {"factor", d.factor_to_user},
      {"offset", d.offset_to_user},
    });
  }

  boost::json::array list;
  for (const auto &g : groups) {
    boost::json::array choices;
    for (const Unit u : g.choices)
      choices.emplace_back(unsigned(u));
    list.emplace_back(boost::json::object{
      {"group", unsigned(g.group)},
      {"unit", unsigned(config.GetByGroup(g.group))},
      {"choices", std::move(choices)},
    });
  }
  list.emplace_back(boost::json::object{
    {"group", unsigned(UnitGroup::WIND_SPEED)},
    {"unit", unsigned(config.wind_speed_unit)},
  });

  boost::json::array presets;
  for (unsigned i = 0; i < Units::Store::Count(); ++i)
    presets.emplace_back(boost::json::object{
      {"name", Units::Store::GetName(i)},
    });

  const boost::json::value value = boost::json::object{
    {"units", std::move(units)},
    {"groups", std::move(list)},
    {"presets", std::move(presets)},
    {"preset", int(Units::Store::EqualsPresetUnits(config)) - 1},
  };

  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

bool
CoreUnits::Set(unsigned group, unsigned unit) noexcept
{
  const auto *g = std::find_if(std::begin(groups), std::end(groups),
                               [group](const GroupInfo &i){
                                 return unsigned(i.group) == group;
                               });
  if (g == std::end(groups) ||
      std::find(g->choices.begin(), g->choices.end(), Unit(unit)) ==
      g->choices.end())
    return false;

  UnitSetting &config = CommonInterface::SetUISettings().format.units;
  GetUnitRef(config, g->group) = Unit(unit);
  Apply(config);
  return true;
}

bool
CoreUnits::ApplyPreset(unsigned index) noexcept
{
  if (index >= Units::Store::Count())
    return false;

  UnitSetting &config = CommonInterface::SetUISettings().format.units;
  config = Units::Store::Read(index);
  Apply(config);
  return true;
}
