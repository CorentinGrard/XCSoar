// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Headless replacements for UI functions the backend calls (link-time
 * seams, see mobile/docs/DECISIONS.md D5).  Each one forwards to the
 * CoreListener; none of them may block.
 */

#include "CoreListener.hpp"
#include "Input/InputQueue.hpp"
#include "Message.hpp"
#include "Dialogs/Message.hpp"
#include "UtilsSettings.hpp"
#include "PageOverlayTitle.hpp"
#include "InfoBoxes/InfoBoxManager.hpp"
#include "Protection.hpp"
#include "LogFile.hpp"
#include "DataGlobals.hpp"
#include "Weather/SkySight/SkySightClient.hpp"

/* src/Input/InputQueue.cpp */
bool
InputEvents::processGlideComputer(unsigned gce_id)
{
  if (!global_running || gce_id >= GCE_COUNT)
    return false;

  if (auto *listener = GetCoreListener())
    listener->OnGlideComputerEvent(gce_id);
  return true;
}

/* src/Input/InputQueue.cpp: hardware switches (e.g. Vega) mapped to
   input events; the UI will offer its own mapping */
bool
InputEvents::processNmea([[maybe_unused]] unsigned key)
{
  return false;
}

/* src/Message.cpp */
void
Message::AddMessage(const char *text, const char *data) noexcept
{
  LogFmt("Message: {} {}", text, data != nullptr ? data : "");

  if (auto *listener = GetCoreListener())
    listener->OnMessage(text, data);
}

/* src/Dialogs/Message.cpp: there is nobody to ask.  Report the text
   as a message and answer with the choice that keeps the current
   operation going ("yes" / "OK"): the backend only asks before
   starting or stopping the IGC logger, where proceeding is the safe
   default. */
int
ShowMessageBox(const char *text, const char *caption,
               unsigned flags) noexcept
{
  Message::AddMessage(caption, text);

  switch (flags & 0x0f) {
  case MB_YESNO:
  case MB_YESNOCANCEL:
    return IDYES;

  case MB_RETRYCANCEL:
    return IDCANCEL;

  case MB_ABORTRETRYIGNORE:
    return IDIGNORE;

  default:
    return IDOK;
  }
}

/* src/UtilsSettings.cpp: set by the settings dialogs to make the UI
   reload files; the core will reload through its settings API */
bool AirspaceFileChanged = false;
bool WaypointFileChanged = false;
bool AirfieldFileChanged = false;
bool InputFileChanged = false;
bool MapFileChanged = false;
bool FlarmFileChanged = false;
bool RaspFileChanged = false;
bool ChecklistFileChanged = false;

/* src/PageOverlayTitle.cpp reaches the weather overlays of the map
   window; page titles are only shown by the UI, which names its own
   pages */
void
AppendOverlayTitle([[maybe_unused]] BasicStringBuilder<char> &builder,
                   [[maybe_unused]] const PageLayout &layout,
                   [[maybe_unused]] const RaspStore *rasp)
{
}

/* src/InfoBoxes/InfoBoxManager.cpp: "redraw the InfoBoxes" signals from
   the setters in ActionInterface.cpp; the UI redraws on every
   calculated update anyway */
void
InfoBoxManager::SetDirty() noexcept
{
}

void
InfoBoxManager::ProcessTimer() noexcept
{
}

/* src/DataGlobals.cpp: no SkySight client yet (weather overlays come
   through the app) */
std::shared_ptr<SkySightClient>
DataGlobals::GetSkySight() noexcept
{
  return {};
}
