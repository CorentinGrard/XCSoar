// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The parts of ActionInterface that push settings and state to the
 * MainWindow.  The UI-free setters are in ActionInterface.cpp.
 */

#include "ActionInterface.hpp"
#include "Interface.hpp"
#include "MainWindow.hpp"
#include "Projection/MapWindowProjection.hpp"
#include "Language/Language.hpp"
#include "InfoBoxes/InfoBoxManager.hpp"
#include "Device/MultipleDevices.hpp"
#include "CalculationThread.hpp"
#include "UIState.hpp"
#include "DisplayMode.hpp"
#include "Operation/MessageOperationEnvironment.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataGlobals.hpp"
#include "PageActions.hpp"
#include "PageSettings.hpp"
#include "Weather/Features.hpp"

using namespace CommonInterface;

namespace ActionInterface {
static void
SendGetComputerSettings() noexcept;
}

static void
UpdateMapScalePageInfo(UIState &state) noexcept;

void
XCSoarInterface::ExchangeBlackboard() noexcept
{
  ExchangeDeviceBlackboard();
  ActionInterface::SendGetComputerSettings();
  ActionInterface::SendMapSettings();
}

void
ActionInterface::SendGetComputerSettings() noexcept
{
  assert(backend_components->calculation_thread != nullptr);

  main_window->SetComputerSettings(GetComputerSettings());

  backend_components->calculation_thread->SetComputerSettings(GetComputerSettings());
  backend_components->calculation_thread->SetScreenDistanceMeters(main_window->GetProjection().GetScreenDistanceMeters());
}

void
ActionInterface::SendMapSettings(const bool trigger_draw) noexcept
{
  if (trigger_draw) {
    main_window->UpdateGaugeVisibility();
    InfoBoxManager::ProcessTimer();
  }

  /* Don't show indicator when the gauge is indicating the traffic anyway */
  SetMapSettings().show_flarm_alarm_level =
    !GetUISettings().traffic.enable_gauge;

  main_window->SetMapSettings(GetMapSettings());

  if (trigger_draw) {
    main_window->FullRedraw();
    BroadcastUISettingsUpdate();
  }

  // TODO: trigger refresh if the settings are changed
}

void
ActionInterface::SendUIState(const bool trigger_draw) noexcept
{
  UpdateMapScalePageInfo(SetUIState());

  main_window->SetUIState(GetUIState());

  if (trigger_draw)
    main_window->FullRedraw();
}

[[gnu::pure]]
static unsigned
GetPanelIndex(const UIState &ui_state)
{
  if (ui_state.auxiliary_enabled) {
    unsigned panel = ui_state.auxiliary_index;
    if (panel >= InfoBoxSettings::MAX_PANELS)
      panel = InfoBoxSettings::PANEL_AUXILIARY;
    return panel;
  }
  else if (ui_state.display_mode == DisplayMode::CIRCLING)
    return InfoBoxSettings::PANEL_CIRCLING;
  else if (ui_state.display_mode == DisplayMode::FINAL_GLIDE)
    return InfoBoxSettings::PANEL_FINAL_GLIDE;
  else
    return InfoBoxSettings::PANEL_CRUISE;
}

static void
UpdateMapScalePageInfo(UIState &state) noexcept
{
  const PagesState &pages = state.pages;
  const PageLayout &configured = PageActions::GetConfiguredLayout();
  const PageLayout &layout = PageActions::GetCurrentLayout();

  /* Pan fullscreen keeps the configured map overlay visible — retain its
     type for RASP HUD logic and show the active layer in the PAN string. */
  const PageLayout &overlay_layout =
    (pages.special_page.IsDefined() &&
     pages.special_page == PageLayout::FullScreen() &&
     configured.IsMapMain() &&
     configured.overlay != PageLayout::Overlay::NONE)
    ? configured
    : layout;

  state.page_overlay = overlay_layout.IsMapMain()
    ? overlay_layout.overlay
    : PageLayout::Overlay::NONE;

  state.map_scale_page_title.clear();

  if (overlay_layout.IsMapMain() &&
      overlay_layout.overlay != PageLayout::Overlay::NONE) {
    const auto &settings = CommonInterface::GetUISettings();
    const char *title = overlay_layout.MakeTitle(
      settings.info_boxes,
      std::span{state.map_scale_page_title.data(),
                state.map_scale_page_title.capacity()},
      DataGlobals::GetRasp().get(), true);
    if (title != nullptr)
      state.map_scale_page_title = title;
  }
}

void
ActionInterface::UpdateDisplayMode() noexcept
{
  UIState &state = SetUIState();
  const UISettings &settings = GetUISettings();

  state.display_mode = GetNewDisplayMode(settings.info_boxes, state,
                                         Calculated());
  state.panel_index = GetPanelIndex(state);

  const auto &panel = settings.info_boxes.panels[state.panel_index];
  state.panel_name = gettext(panel.name);

  UpdateMapScalePageInfo(state);
}

void
ActionInterface::SendUIState() noexcept
{
  UpdateMapScalePageInfo(SetUIState());

  /* force-update all InfoBoxes just in case the display mode has
     changed */
  InfoBoxManager::SetDirty();
  InfoBoxManager::ProcessTimer();

  main_window->SetUIState(GetUIState());
}

void
ActionInterface::ScheduleSendUIState() noexcept
{
  if (main_window != nullptr)
    main_window->ScheduleRefreshInfoBoxes();
}

void
ActionInterface::SetQNH(AtmosphericPressure qnh, bool to_devices) noexcept
{
  const NMEAInfo &basic = Basic();
  ComputerSettings &settings_computer = SetComputerSettings();

  settings_computer.pressure = qnh;
  settings_computer.pressure_available.Update(basic.clock);

  SendGetComputerSettings();

  InfoBoxManager::SetDirty();
  InfoBoxManager::ProcessTimer();

  if (to_devices && backend_components && backend_components->devices) {
    MessageOperationEnvironment env;
    backend_components->devices->PutQNH(qnh, env);
  }
}
