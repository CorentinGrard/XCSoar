// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The text of src/Dialogs/StatusPanels: each function follows its
 * panel's Prepare() (the labels) and Refresh() (the values).  Keep in
 * sync with them.  Left out: the system page's network row (the core
 * has no Android NativeView to ask; the phone shows its own) and the
 * task page's "Set MacCready" what-if field.
 */

#include "CoreStatus.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Logger/Logger.hpp"
#include "Hardware/PowerGlobal.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Formatter/UserUnits.hpp"
#include "Formatter/AngleFormatter.hpp"
#include "Formatter/UserGeoPointFormatter.hpp"
#include "Formatter/TimeFormatter.hpp"
#include "Formatter/LocalTimeFormatter.hpp"
#include "Math/SunEphemeris.hpp"
#include "Language/Language.hpp"
#include "time/BrokenDateTime.hpp"
#include "time/RoughTime.hpp"
#include "util/StaticString.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#ifdef HAVE_BATTERY
#include "Hardware/PowerInfo.hpp"
#endif

#include <boost/json.hpp>

#include <chrono>

namespace {

/** One page's rows, in the panel's order. */
class Rows {
  boost::json::array rows;

public:
  void Add(const char *label, const char *value) {
    rows.emplace_back(boost::json::object{{"label", label}, {"value", value}});
  }

  boost::json::object Page(const char *name, const char *title) && {
    return {{"name", name}, {"title", title}, {"rows", std::move(rows)}};
  }
};

/* FlightStatusPanel */
boost::json::object
FlightPage() noexcept
{
  const NMEAInfo &basic = CommonInterface::Basic();
  const DerivedInfo &calculated = CommonInterface::Calculated();
  Rows rows;

  rows.Add(_("Location"), basic.location_available
           ? FormatGeoPoint(basic.location).c_str() : "");
  rows.Add(_("Altitude"), basic.gps_altitude_available
           ? FormatUserAltitude(basic.gps_altitude).c_str() : "");
  rows.Add(_("Max. height gain"),
           FormatUserAltitude(calculated.max_height_gain).c_str());

  /* dlgStatusShowModal() looks for it within 100 km */
  const auto nearest = basic.location_available
    ? data_components->waypoints->GetNearest(basic.location, 100000)
    : nullptr;
  if (nearest) {
    const GeoVector vec(basic.location, nearest->location);
    rows.Add(_("Near"), nearest->name.c_str());
    rows.Add(_("Bearing"), FormatBearing(vec.bearing).c_str());
    rows.Add(_("Distance"), FormatUserDistanceSmart(vec.distance).c_str());
  } else {
    rows.Add(_("Near"), "-");
    rows.Add(_("Bearing"), "-");
    rows.Add(_("Distance"), "-");
  }

  return std::move(rows).Page("flight", _("Flight"));
}

[[gnu::pure]]
const char *
GetGPSStatus(const NMEAInfo &basic) noexcept
{
  if (!basic.alive)
    return N_("Disconnected");
  else if (!basic.location_available)
    return N_("Fix invalid");
  else if (!basic.gps_altitude_available)
    return N_("2D fix");
  else
    return N_("3D fix");
}

/* SystemStatusPanel */
boost::json::object
SystemPage() noexcept
{
  const NMEAInfo &basic = CommonInterface::Basic();
  const GPSState &gps = basic.gps;
  Rows rows;
  StaticString<80> temp;

  rows.Add(_("GPS lock"), gettext(GetGPSStatus(basic)));

  if (!basic.alive)
    temp.clear();
  else if (gps.satellites_used_available)
    temp.Format("%u", gps.satellites_used);
  else
    temp = _("Unknown");
  rows.Add(_("Satellites in view"), temp);

  rows.Add(_("Variometer"),
           basic.netto_vario_available ? _("Netto")
           : basic.total_energy_vario_available ? C_("Status", "Total energy")
           : basic.noncomp_vario_available ? C_("Status", "Uncompensated")
           : _("Disconnected"));

  temp = basic.flarm.status.available ? _("Connected") : _("Disconnected");
  if (basic.flarm.version.available &&
      !basic.flarm.version.software_version.empty()) {
    temp.append(" (fw ");
    temp.UnsafeAppendASCII(basic.flarm.version.software_version.c_str());
    temp.push_back(')');
  }
  rows.Add("FLARM", temp);

  rows.Add(_("Logger"), backend_components->igc_logger != nullptr &&
           backend_components->igc_logger->IsLoggerActive()
           ? _("On") : _("Off"));

  temp.clear();
#ifdef HAVE_BATTERY
  const auto &battery = Power::global_info.battery;
  if (battery.remaining_percent)
    temp.Format("%u %% ", *battery.remaining_percent);
#endif
  if (basic.voltage_available)
    temp.AppendFormat("%.1f V", (double)basic.voltage);
  else if (basic.battery_level_available)
    temp.AppendFormat("%.0f%%", (double)basic.battery_level);
  rows.Add(_("Supply voltage"), temp);

  return std::move(rows).Page("system", _("System"));
}

/* TaskStatusPanel */
boost::json::object
TaskPage() noexcept
{
  const TaskStats &task_stats = CommonInterface::Calculated().ordered_task_stats;
  const bool remaining = task_stats.total.remaining_effective.IsDefined() &&
    !task_stats.task_finished;
  Rows rows;
  StaticString<32> temp;

  if (task_stats.has_targets)
    rows.Add(_("Assigned task time"),
             FormatTimeHHMM(backend_components->protected_task_manager
                            ->GetOrderedTaskSettings().aat_min_time).c_str());

  rows.Add(_("Estimated task time"),
           task_stats.total.remaining_effective.IsDefined()
           ? FormatSignedTimeHHMM(task_stats.GetEstimatedTotalTime()).c_str()
           : "");
  rows.Add(_("Remaining time"), remaining
           ? FormatSignedTimeHHMM(task_stats.total.time_remaining_now).c_str()
           : "");
  rows.Add(_("Task distance"), task_stats.total.planned.IsDefined()
           ? FormatUserDistanceSmart(task_stats.total.planned.GetDistance()).c_str()
           : "");
  rows.Add(_("Remaining distance"),
           task_stats.total.remaining.IsDefined() && !task_stats.task_finished
           ? FormatUserDistanceSmart(task_stats.total.remaining.GetDistance()).c_str()
           : "");
  rows.Add(_("Speed estimated"), remaining
           ? FormatUserTaskSpeed(task_stats.total.planned.GetSpeed()).c_str()
           : "");
  rows.Add(_("Speed average"), task_stats.total.travelled.IsDefined()
           ? FormatUserTaskSpeed(task_stats.total.travelled.GetSpeed()).c_str()
           : "");

  temp.clear();
  if (task_stats.total.planned.IsDefined() &&
      task_stats.distance_min < task_stats.distance_max) {
    const auto range = 2 * (task_stats.total.planned.GetDistance() -
                            task_stats.distance_min) /
      (task_stats.distance_max - task_stats.distance_min) - 1;
    temp.Format("%.0f %%", range * 100);
  }
  rows.Add(_("AAT range"), temp);

  rows.Add(_("Speed remaining"), remaining
           ? FormatUserTaskSpeed(task_stats.total.remaining_effective.GetSpeed(),
                                 false).c_str()
           : "");
  rows.Add(_("Achieved MacCready"),
           FormatUserVerticalSpeed(task_stats.effective_mc, true, false).c_str());
  rows.Add(_("Achieved speed"), task_stats.total.travelled.IsDefined()
           ? FormatUserTaskSpeed(task_stats.total.travelled.GetSpeed(),
                                 false).c_str()
           : "");
  temp.Format("%.0f %%", task_stats.cruise_efficiency * 100);
  rows.Add(_("Cruise efficiency"), temp);

  return std::move(rows).Page("task", _("Task"));
}

/* RulesStatusPanel */
boost::json::object
RulesPage() noexcept
{
  const TaskStats &task_stats = CommonInterface::Calculated().ordered_task_stats;
  const StartStats &start_stats = task_stats.start;
  const ComputerSettings &settings = CommonInterface::GetComputerSettings();
  Rows rows;

  rows.Add(_("Valid start"), start_stats.HasStarted() ? _("Yes") : _("No"));

  if (start_stats.HasStarted()) {
    rows.Add(_("Start time"),
             FormatLocalTimeHHMM(start_stats.time, settings.utc_offset).c_str());
    rows.Add(_("Start alt."), FormatUserAltitude(start_stats.altitude).c_str());
  } else {
    rows.Add(_("Start time"), "");
    rows.Add(_("Start alt."), "");
  }

  StaticString<80> start_point;
  start_point.clear();
  double finish_elevation = 0;
  {
    const ProtectedTaskManager::Lease task_manager{
      *backend_components->protected_task_manager};
    const OrderedTask &task = task_manager->GetOrderedTask();
    const unsigned task_size = task.TaskSize();
    if (task_size > 0) {
      start_point = task.GetTaskPoint(0).GetWaypoint().name.c_str();
      finish_elevation = task.GetTaskPoint(task_size - 1).GetElevation();
    }
  }
  rows.Add(_("Start point"), start_point);

  if (start_stats.HasStarted()) {
    rows.Add(_("Start speed"),
             FormatUserTaskSpeed(start_stats.ground_speed).c_str());
    rows.Add(_("PEV offset at start"), start_stats.pev_offset_available
             ? FormatTimespanSmart(std::chrono::seconds{
                 start_stats.pev_offset_seconds}, 3).c_str()
             : C_("Status", "—"));
  } else {
    rows.Add(_("Start speed"), "");
    rows.Add(_("PEV offset at start"), "");
  }

  rows.Add(_("Finish min. alt."), FormatUserAltitude(finish_elevation).c_str());
  rows.Add(_("Valid finish"), task_stats.task_finished ? _("Yes") : _("No"));

  return std::move(rows).Page("rules", _("Rules"));
}

/* TimesStatusPanel */
boost::json::object
TimesPage() noexcept
{
  const NMEAInfo &basic = CommonInterface::Basic();
  const FlyingState &flight = CommonInterface::Calculated().flight;
  const ComputerSettings &settings = CommonInterface::GetComputerSettings();
  Rows rows;
  StaticString<64> temp;

  if (basic.time_available) {
    rows.Add(_("Local time"),
             FormatLocalTimeHHMM(basic.time, settings.utc_offset).c_str());
    rows.Add(_("UTC time"), FormatTimeHHMM(basic.time).c_str());
  } else {
    rows.Add(_("Local time"), "");
    rows.Add(_("UTC time"), "");
  }

  temp.clear();
  if (basic.date_time_utc.IsDatePlausible())
    temp.Format("%04d-%02d-%02d", basic.date_time_utc.year,
                basic.date_time_utc.month, basic.date_time_utc.day);
  rows.Add(_("UTC date"), temp);

  temp.clear();
  if (flight.flight_time.count() > 0) {
    if (flight.takeoff_time.IsDefined() && flight.landing_time.IsDefined()) {
      bool set = false;
      if (basic.time_available && basic.date_time_utc.IsDatePlausible()) {
        const BrokenDateTime takeoff_dt =
          basic.GetDateTimeAt(flight.takeoff_time).FloorToMinute();
        const BrokenDateTime landing_dt =
          basic.GetDateTimeAt(flight.landing_time).FloorToMinute();
        if (takeoff_dt.IsPlausible() && landing_dt.IsPlausible()) {
          temp = FormatSignedTimeHHMM(
            std::chrono::duration_cast<std::chrono::seconds>(landing_dt -
                                                             takeoff_dt)).c_str();
          set = true;
        }
      }

      if (!set) {
        const RoughTime rough_takeoff =
          RoughTime::FromSinceMidnight(flight.takeoff_time);
        const RoughTime rough_landing =
          RoughTime::FromSinceMidnight(flight.landing_time);
        temp = FormatSignedTimeHHMM(
          FloatDuration{rough_landing - rough_takeoff}).c_str();
      }
    } else
      temp = FormatSignedTimeHHMM(flight.flight_time).c_str();
  }
  rows.Add(_("Flight time"), temp);

  rows.Add(_("Takeoff time"), flight.takeoff_time.IsDefined()
           ? FormatLocalTimeHHMM(flight.takeoff_time, settings.utc_offset).c_str()
           : "");
  rows.Add(_("Landing time"), flight.landing_time.IsDefined()
           ? FormatLocalTimeHHMM(flight.landing_time, settings.utc_offset).c_str()
           : "");

  temp.clear();
  if (basic.location_available && basic.date_time_utc.IsDatePlausible()) {
    const SunEphemeris::Result sun =
      SunEphemeris::CalcSunTimes(basic.location, basic.date_time_utc,
                                 settings.utc_offset);
    const unsigned sunrise_hours = (int)sun.time_of_sunrise;
    const unsigned sunrise_mins =
      (int)((sun.time_of_sunrise - double(sunrise_hours)) * 60);
    const unsigned sunset_hours = (int)sun.time_of_sunset;
    const unsigned sunset_mins =
      (int)((sun.time_of_sunset - double(sunset_hours)) * 60);
    temp.Format("%02u:%02u - %02u:%02u", sunrise_hours, sunrise_mins,
                sunset_hours, sunset_mins);
  }
  rows.Add(_("Daylight time"), temp);

  return std::move(rows).Page("times", _("Times"));
}

} // namespace

std::string
CoreStatus::Describe() noexcept
{
  boost::json::object o{{"pages", boost::json::array{
        FlightPage(), SystemPage(), TaskPage(), RulesPage(), TimesPage(),
      }}};

  const NMEAInfo &basic = CommonInterface::Basic();
  if (basic.location_available)
    o["location"] = boost::json::array{basic.location.latitude.Degrees(),
                                       basic.location.longitude.Degrees()};

  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}
