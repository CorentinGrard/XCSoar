// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Each function follows the dialog or panel it replaces (see
 * CoreTilePages.hpp).  Keep in sync with them.  Left out: the team code
 * dialog's "Flarm Lock" button (FLARM traffic is not in the app yet)
 * and the airspace dialog's "Set Squawk Code" (no transponder).
 */

#include "CoreTilePages.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Formatter/AngleFormatter.hpp"
#include "Formatter/UserGeoPointFormatter.hpp"
#include "Formatter/UserUnits.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "Radio/RadioFrequency.hpp"
#include "Radio/TransponderCode.hpp"
#include "LogFile.hpp"
#include "util/CharUtil.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <cmath>

/** The airspace of the last dlgAirspaceDetails() call. */
static ConstAirspacePtr shown_airspace;

static std::string
Serialize(const boost::json::object &o) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}

static WaypointPtr
LookupWaypoint(int id) noexcept
{
  if (id < 0 || data_components == nullptr ||
      data_components->waypoints == nullptr)
    return nullptr;

  return data_components->waypoints->LookupId(id);
}

/* TeamCodeWidget::Update() */
std::string
CoreTilePages::DescribeTeamCode() noexcept
{
  const MoreData &basic = CommonInterface::Basic();
  const TeamInfo &info = CommonInterface::Calculated();
  const TeamCodeSettings &settings =
    CommonInterface::GetComputerSettings().team_code;

  boost::json::object o{
    {"own", info.own_teammate_code.GetCode()},
    {"mate", settings.team_code.GetCode()},
    {"range", info.teammate_available
     ? FormatUserDistanceSmart(info.teammate_vector.distance).c_str() : ""},
    {"bearing", info.teammate_available
     ? FormatBearing(info.teammate_vector.bearing).c_str() : ""},
    {"relative_bearing", info.teammate_available && basic.track_available
     ? FormatAngleDelta(info.teammate_vector.bearing - basic.track).c_str()
     : ""},
    {"flarm_lock", settings.team_flarm_id.IsDefined()
     ? settings.team_flarm_callsign.c_str() : ""},
  };

  if (const auto waypoint =
      LookupWaypoint(settings.team_code_reference_waypoint))
    o["reference"] = boost::json::object{
      {"id", waypoint->id},
      {"name", waypoint->name.c_str()},
    };

  return Serialize(o);
}

/* TeamCodeWidget::OnCodeClicked() */
bool
CoreTilePages::SetTeamMateCode(const char *code) noexcept
{
  /* the dialog's text entry: up to 7 characters, trailing blanks
     stripped; team codes are base 36 (TeamCode.cpp) */
  std::string_view value{code};
  while (!value.empty() && value.back() == ' ')
    value.remove_suffix(1);
  if (value.size() > 7)
    return false;

  char upper[8];
  std::size_t length = 0;
  for (char ch : value) {
    if (!IsAlphaNumericASCII(ch))
      return false;
    upper[length++] = ToUpperASCII(ch);
  }
  upper[length] = '\0';

  TeamCodeSettings &settings = CommonInterface::SetComputerSettings().team_code;
  settings.team_code.Update(upper);
  if (settings.team_code.IsDefined())
    settings.team_flarm_id.Clear();
  return true;
}

/* TeamCodeWidget::OnSetWaypointClicked() */
bool
CoreTilePages::SetTeamReference(int waypoint_id) noexcept
{
  if (waypoint_id != -1 && LookupWaypoint(waypoint_id) == nullptr)
    return false;

  CommonInterface::SetComputerSettings().team_code
    .team_code_reference_waypoint = waypoint_id;
  Profile::Set(ProfileKeys::TeamcodeRefWaypoint, waypoint_id);
  Profile::Save();
  return true;
}

static boost::json::object
DescribeFrequency(RadioFrequency frequency, const char *name) noexcept
{
  char buffer[16];
  const char *text = frequency.Format(buffer, sizeof(buffer));
  return {
    {"frequency", text != nullptr ? text : ""},
    {"name", text != nullptr ? name : ""},
  };
}

std::string
CoreTilePages::DescribeRadio() noexcept
{
  const auto &radio = CommonInterface::GetComputerSettings().radio;
  return Serialize({
      {"active", DescribeFrequency(radio.active_frequency,
                                   radio.active_name.c_str())},
      {"standby", DescribeFrequency(radio.standby_frequency,
                                    radio.standby_name.c_str())},
    });
}

bool
CoreTilePages::SetRadioFrequency(bool standby, const char *mhz,
                                 const char *name) noexcept
{
  const RadioFrequency frequency = RadioFrequency::Parse(mhz);
  if (!frequency.IsDefined())
    return false;

  /* an empty name clears the old one, as ActionInterface does with
     nullptr */
  if (name != nullptr && *name == '\0')
    name = nullptr;

  if (standby)
    ActionInterface::SetStandbyFrequency(frequency, name);
  else
    ActionInterface::SetActiveFrequency(frequency, name);
  return true;
}

/* RadioOffsetButtons::OnOffset() */
void
CoreTilePages::OffsetRadioFrequency(bool standby, int khz) noexcept
{
  if (standby)
    ActionInterface::OffsetStandbyFrequency(khz, true);
  else
    ActionInterface::OffsetActiveFrequency(khz, true);
}

/* ATCReferencePanel::UpdateValues() and ATCSetupPanel */
std::string
CoreTilePages::DescribeAtc() noexcept
{
  const auto &poi = CommonInterface::GetComputerSettings().poi;
  boost::json::object o{
    {"declination", poi.magnetic_declination.Degrees()},
  };

  const GeoPoint &location = poi.atc_reference;
  if (location.IsValid()) {
    const auto waypoint = data_components != nullptr &&
      data_components->waypoints != nullptr
      ? data_components->waypoints->GetNearest(location, 100)
      : nullptr;
    o["waypoint"] = waypoint != nullptr ? waypoint->name.c_str() : "";
    o["location"] = FormatGeoPoint(location).c_str();
  } else
    o["waypoint"] = "";

  return Serialize(o);
}

/* the panel's "Relocate" and "Clear" */
bool
CoreTilePages::SetAtcReference(int waypoint_id) noexcept
{
  auto &location = CommonInterface::SetComputerSettings().poi.atc_reference;
  if (waypoint_id == -1) {
    location.SetInvalid();
    return true;
  }

  const auto waypoint = LookupWaypoint(waypoint_id);
  if (waypoint == nullptr)
    return false;

  location = waypoint->location;
  return true;
}

bool
CoreTilePages::SetMagneticDeclination(double degrees) noexcept
{
  if (!(degrees >= -30 && degrees <= 30))
    return false;

  /* the panel's whole degrees */
  CommonInterface::SetComputerSettings().poi.magnetic_declination =
    Angle::Degrees(std::round(degrees));
  return true;
}

void
CoreTilePages::ShowAirspace(ConstAirspacePtr airspace) noexcept
{
  shown_airspace = std::move(airspace);
}

/* AirspaceDetailsWidget::Prepare() */
std::string
CoreTilePages::DescribeAirspace() noexcept
{
  if (!shown_airspace)
    return "{}";

  const AbstractAirspace &airspace = *shown_airspace;
  auto *warnings = backend_components != nullptr
    ? backend_components->GetAirspaceWarnings()
    : nullptr;

  char buffer[64];
  boost::json::object o{
    {"name", airspace.GetName()},
    {"class", AirspaceFormatter::GetClassShort(airspace)},
    {"type", AirspaceFormatter::GetType(airspace)},
  };

  AirspaceFormatter::FormatAltitude(buffer, airspace.GetTop());
  o["top"] = buffer;
  AirspaceFormatter::FormatAltitude(buffer, airspace.GetBase());
  o["base"] = buffer;

  const RadioFrequency frequency = airspace.GetRadioFrequency();
  const char *text = frequency.Format(buffer, sizeof(buffer));
  o["frequency"] = text != nullptr ? text : "";
  const char *station = airspace.GetStationName();
  o["station"] = text != nullptr && station != nullptr ? station : "";

  const TransponderCode squawk = airspace.GetTransponderCode();
  o["squawk"] = squawk.IsDefined()
    ? squawk.Format(buffer, sizeof(buffer)) : "";

  const NMEAInfo &basic = CommonInterface::Basic();
  if (warnings != nullptr && basic.location_available &&
      basic.location.IsValid()) {
    const GeoPoint closest =
      airspace.ClosestPoint(basic.location, warnings->GetProjection());
    o["distance"] = FormatUserDistance(closest.Distance(basic.location))
      .c_str();
  } else
    o["distance"] = "";

  o["acknowledged"] = warnings != nullptr && warnings->GetAckDay(airspace);
  return Serialize(o);
}

/* AirspaceDetailsWidget::AckDayOrEnable() */
bool
CoreTilePages::AcknowledgeAirspaceDay(bool acknowledge) noexcept
{
  auto *warnings = backend_components != nullptr
    ? backend_components->GetAirspaceWarnings()
    : nullptr;
  if (!shown_airspace || warnings == nullptr)
    return false;

  try {
    warnings->AcknowledgeDay(shown_airspace, acknowledge);
  } catch (...) {
    LogError(std::current_exception(),
             "Failed to update airspace day acknowledgement");
    return false;
  }
  return true;
}
