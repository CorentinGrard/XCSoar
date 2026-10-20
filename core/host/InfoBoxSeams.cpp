// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The UI that XCSoar's InfoBox contents (src/InfoBoxes/Content) reach
 * from a tap (HandleClick()), their dialog panels (GetDialogContent())
 * and their drawing (OnCustomPaint()) (link-time seams,
 * mobile/docs/DECISIONS.md D5).  A tapped tile's dialogs tell
 * CoreInfoBoxes what was asked for, and the app shows its own page for
 * it (CoreInfoBoxes::Click()); the panels and drawing never run.
 */

#include "Dialogs/Dialogs.h"
#include "Dialogs/dlgAnalysis.hpp"
#include "InfoBoxes/Content/ShowAnalysis.hpp"
#include "Dialogs/Airspace/Airspace.hpp"
#include "Dialogs/Task/TaskDialogs.hpp"
#include "Dialogs/Traffic/TrafficDialogs.hpp"
#include "Dialogs/Waypoint/WaypointDialogs.hpp"
#include "InfoBoxes/InfoBoxManager.hpp"
#include "InfoBoxes/Panel/AltitudeInfo.hpp"
#include "InfoBoxes/Panel/AltitudeSetup.hpp"
#include "InfoBoxes/Panel/AltitudeSimulator.hpp"
#include "InfoBoxes/Panel/ATCReference.hpp"
#include "InfoBoxes/Panel/ATCSetup.hpp"
#include "InfoBoxes/Panel/MacCreadyEdit.hpp"
#include "InfoBoxes/Panel/MacCreadySetup.hpp"
#include "InfoBoxes/Panel/RadioEdit.hpp"
#include "InfoBoxes/Panel/SpeedSimulator.hpp"
#include "Input/InputEvents.hpp"
#include "PageActions.hpp"
#include "TeamActions.hpp"
#include "FLARM/Id.hpp"
#include "UIGlobals.hpp"
#include "Widget/Widget.hpp"
#include "CoreInfoBoxes.hpp"
#include "CoreTilePages.hpp"

#include <cstdlib>
#include <iterator>

/* src/Dialogs: tapped InfoBoxes open these */

void
ShowWindSettingsDialog()
{
  CoreInfoBoxes::RequestAction("wind");
}

void
dlgStatusShowModal(int page)
{
  /* the pages of src/Dialogs/StatusPanels, in dlgStatus.cpp's order */
  static constexpr const char *pages[] = {
    "flight", "system", "task", "rules", "times",
  };
  CoreInfoBoxes::RequestAction("status",
                               page >= 0 && page < int(std::size(pages))
                               ? pages[page] : nullptr);
}

void
dlgTeamCodeShowModal()
{
  CoreInfoBoxes::RequestAction("team_code");
}

void
dlgAnalysisShowModal([[maybe_unused]] UI::SingleWindow &parent,
                     [[maybe_unused]] const Look &look,
                     [[maybe_unused]] const FullBlackboard &blackboard,
                     [[maybe_unused]] GlideComputer &glide_computer,
                     [[maybe_unused]] const Airspaces *airspaces,
                     [[maybe_unused]] const RasterTerrain *terrain,
                     [[maybe_unused]] AnalysisPage page)
{
}

/* src/InfoBoxes/Content/ShowAnalysis.cpp, which needs the main window
   and the look for the dialog */
bool
ShowAnalysis(AnalysisPage page) noexcept
{
  /* AnalysisPage's members, lower case */
  static constexpr const char *pages[] = {
    "barograph", "climb", "thermal_band", "vario_histogram", "task_speed",
    "wind", "polar", "maccready", "temptrace", "task", "contest", "airspace",
  };
  static_assert(std::size(pages) == unsigned(AnalysisPage::COUNT));
  CoreInfoBoxes::RequestAction("analysis", pages[unsigned(page)]);
  return true;
}

void
dlgAirspaceDetails(ConstAirspacePtr airspace,
                   [[maybe_unused]] ProtectedAirspaceWarningManager *warnings)
{
  CoreTilePages::ShowAirspace(std::move(airspace));
  CoreInfoBoxes::RequestAction("airspace");
}

void
dlgAlternatesListShowModal([[maybe_unused]] Waypoints *waypoints,
                           [[maybe_unused]] std::optional<AlternateInfoBoxSlot> slot) noexcept
{
  CoreInfoBoxes::RequestAction("alternates");
}

void
dlgWaypointDetailsShowModal([[maybe_unused]] Waypoints *waypoints,
                            [[maybe_unused]] WaypointPtr waypoint,
                            [[maybe_unused]] bool allow_navigation,
                            [[maybe_unused]] bool allow_edit,
                            [[maybe_unused]] const WaypointDetailsNesting *nesting) noexcept
{
  CoreInfoBoxes::RequestAction("waypoint");
}

WaypointPtr
ShowWaypointListDialog([[maybe_unused]] Waypoints &waypoints,
                       [[maybe_unused]] const GeoPoint &location,
                       [[maybe_unused]] OrderedTask *ordered_task,
                       [[maybe_unused]] unsigned ordered_task_index,
                       [[maybe_unused]] std::optional<TypeFilter> initial_type,
                       [[maybe_unused]] bool prepopulate_with_task,
                       [[maybe_unused]] const char *caption,
                       [[maybe_unused]] bool *pan_from_details)
{
  /* nothing picked: the InfoBox changes nothing */
  CoreInfoBoxes::RequestAction("waypoints");
  return nullptr;
}

/* src/InfoBoxes/Panel: the InfoBox dialog's pages.  Each asks for the
   app's page that edits what it would (CoreInfoBoxes::Click()); the
   altitude simulator panel has none. */

std::unique_ptr<Widget>
LoadAltitudeInfoPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("altitude");
  return nullptr;
}

std::unique_ptr<Widget>
LoadAltitudeSetupPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("altitude");
  return nullptr;
}

std::unique_ptr<Widget>
LoadAltitudeSimulatorPanel([[maybe_unused]] unsigned id)
{
  return nullptr;
}

std::unique_ptr<Widget>
LoadATCReferencePanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("atc");
  return nullptr;
}

std::unique_ptr<Widget>
LoadATCSetupPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("atc");
  return nullptr;
}

std::unique_ptr<Widget>
LoadMacCreadyEditPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("maccready");
  return nullptr;
}

std::unique_ptr<Widget>
LoadMacCreadySetupPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("maccready");
  return nullptr;
}

std::unique_ptr<Widget>
LoadActiveRadioFrequencyEditPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("radio");
  return nullptr;
}

std::unique_ptr<Widget>
LoadStandbyRadioFrequencyEditPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("radio");
  return nullptr;
}

std::unique_ptr<Widget>
LoadSpeedSimulatorPanel([[maybe_unused]] unsigned id)
{
  CoreInfoBoxes::RequestAction("simulator");
  return nullptr;
}

/* the main window, input events and pages */

void
InfoBoxManager::ScheduleRedraw() noexcept
{
}

void
InputEvents::eventThermalAssistant([[maybe_unused]] const char *misc)
{
}

void
PageActions::Restore()
{
}

void
TeamActions::TrackFlarm([[maybe_unused]] FlarmId id,
                        [[maybe_unused]] const char *callsign) noexcept
{
}

/* only OnCustomPaint() and tap handlers use these; upstream does not
   declare them [[noreturn]] */
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wsuggest-attribute=noreturn"
#endif

UI::SingleWindow &
UIGlobals::GetMainWindow()
{
  std::abort();
}

const Look &
UIGlobals::GetLook()
{
  std::abort();
}

#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC diagnostic pop
#endif
