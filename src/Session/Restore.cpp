// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "Restore.hpp"
#include "SessionState.hpp"
#include "Interface.hpp"
#include "Engine/GlideSolvers/GlidePolar.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Waypoint/Waypoints.hpp"
#include "Atmosphere/Pressure.hpp"
#include "Atmosphere/Temperature.hpp"
#include "time/Stamp.hpp"

#include <limits>

void
Session::RestoreComputer(GlidePolar &gp) noexcept
{
  const SessionState &state = Session::State();
  if (!state.flying)
    return;

  auto &settings = CommonInterface::SetComputerSettings();
  if (state.have_bugs)
    settings.polar.SetBugs(state.bugs);
  if (state.have_ballast)
    gp.SetBallastLitres(state.ballast_litres);
  if (state.have_crew)
    gp.SetCrewMass(state.crew_mass);

  if (state.have_qnh) {
    settings.pressure = AtmosphericPressure::HectoPascal(state.qnh_hpa);
    auto now = CommonInterface::Basic().clock;
    if (!now.IsDefined() || now.ToDuration().count() <= 0)
      now = TimeStamp{FloatDuration{1}};
    settings.pressure_available.Update(now);
  }

  if (state.have_max_temp)
    settings.forecast_temperature =
      Temperature::FromKelvin(state.max_temp_k);

  if (state.have_atc_reference)
    settings.poi.atc_reference = state.atc_reference;
  if (state.have_atc_declination)
    settings.poi.magnetic_declination =
      Angle::Degrees(state.atc_declination_deg);
}

void
Session::RestoreGoto(TaskManager &task_manager, Waypoints &waypoints) noexcept
{
  const SessionState &state = Session::State();
  if (!state.flying || !state.have_goto)
    return;

  if (!state.ShouldRestoreGoto() ||
      task_manager.GetMode() != TaskType::NONE) {
    Session::ClearGoto();
    return;
  }

  WaypointPtr wp;
  if (state.goto_id != 0) {
    wp = waypoints.LookupId(state.goto_id);
    /* Ids follow file order.  A moved point is a different waypoint. */
    if (wp != nullptr && state.have_goto_location &&
        wp->location.Distance(state.goto_location) > 100)
      wp = nullptr;
  }
  if (wp == nullptr && state.have_goto_location)
    wp = waypoints.LookupLocation(state.goto_location, 100);
  if (wp == nullptr && state.goto_name == "(goto)" &&
      state.have_goto_location) {
    const double elevation = state.have_goto_elevation
      ? state.goto_elevation
      : std::numeric_limits<double>::quiet_NaN();
    waypoints.AddTempPoint(state.goto_location, elevation, "(goto)");
    wp = waypoints.LookupName("(goto)");
  }

  if (wp == nullptr || !task_manager.DoGoto(std::move(wp)))
    Session::ClearGoto();
}
