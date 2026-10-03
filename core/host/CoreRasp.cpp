// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreRasp.hpp"
#include "Interface.hpp"
#include "UIState.hpp"
#include "Weather/Rasp/RaspStore.hpp"
#include "Weather/Rasp/Configured.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "Repository/Glue.hpp"
#include "system/FileUtil.hpp"
#include "system/Path.hpp"
#include "time/BrokenDateTime.hpp"
#include "Language/Language.hpp"
#include "time/BrokenTime.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"
#include "LogFile.hpp"

#include <boost/json.hpp>

#include <cstdio>
#include <string>

static std::shared_ptr<RaspStore> store;

void
CoreRasp::Load() noexcept
try {
  /* today's file usually has the same fields: keep showing the one
     shown, by name */
  auto &state = CommonInterface::SetUIState().weather;
  std::string shown;
  if (state.map >= 0 && unsigned(state.map) < CountFields())
    shown = store->GetItemInfo(state.map).name.c_str();
  state.map = -1;

  store.reset();
  if (Profile::GetPath(ProfileKeys::RaspFile) == nullptr)
    return;

  /* no "xcsoar-rasp.dat" fallback: the app always names the file */
  store = LoadConfiguredRasp(false);

  for (unsigned i = 0; !shown.empty() && i < CountFields(); ++i)
    if (shown == store->GetItemInfo(i).name.c_str())
      state.map = i;
} catch (...) {
  LogError(std::current_exception(), "Failed to load RASP");
  store.reset();
}

void
CoreRasp::Deinitialise() noexcept
{
  store.reset();
}

std::shared_ptr<RaspStore>
CoreRasp::Get() noexcept
{
  return store;
}

unsigned
CoreRasp::CountFields() noexcept
{
  return store != nullptr ? store->GetItemCount() : 0;
}

static std::string
FormatTime(BrokenTime t) noexcept
{
  char buffer[8];
  snprintf(buffer, sizeof(buffer), "%02u:%02u", t.hour, t.minute);
  return buffer;
}

std::string
CoreRasp::Describe() noexcept
{
  const auto &state = CommonInterface::GetUIState().weather;

  boost::json::array fields;
  for (unsigned i = 0; i < CountFields(); ++i) {
    const auto &item = store->GetItemInfo(i);

    boost::json::array times;
    store->ForEachTime(i, [&times](BrokenTime t){
      times.emplace_back(FormatTime(t));
    });

    boost::json::object field{
      {"name", item.name.c_str()},
      {"label", item.label != nullptr ? gettext(item.label) : item.name.c_str()},
      {"times", std::move(times)},
    };
    if (item.help != nullptr)
      field["help"] = gettext(item.help);
    fields.emplace_back(std::move(field));
  }

  const bool showing = state.map >= 0 && unsigned(state.map) < CountFields();
  boost::json::value time = nullptr;
  if (showing && !state.time_auto_advance && state.time.IsPlausible())
    time = FormatTime(state.time);

  boost::json::object o{
    {"fields", std::move(fields)},
    {"field", showing ? state.map : -1},
    {"time", std::move(time)},
  };

  /* the file's day, and whether it is older than today, like
     IsRaspFileOutOfDate() (the system clock: there may be no fix) */
  if (const auto path = Profile::GetPath(ProfileKeys::RaspFile);
      path != nullptr) {
    BrokenDate modified = BrokenDate::Invalid();
    if (File::Exists(path))
      modified = BrokenDateTime{File::GetLastModification(path)};

    if (modified.IsPlausible()) {
      char date[16];
      snprintf(date, sizeof(date), "%04u-%02u-%02u",
               modified.year, modified.month, modified.day);
      o["file_date"] = date;
    }

    o["out_of_date"] =
      IsRaspForecastOutOfDate(modified, BrokenDateTime::NowUTC(),
                              BrokenDate::Invalid());
  }

  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}

bool
CoreRasp::Select(int field, const char *time) noexcept
{
  auto &state = CommonInterface::SetUIState().weather;

  if (field < 0) {
    state.map = -1;
    return true;
  }

  if (unsigned(field) >= CountFields())
    return false;

  if (time == nullptr || *time == '\0') {
    /* RASPDialog's "Now" */
    state.map = field;
    state.time = BrokenTime::Invalid();
    state.time_auto_advance = true;
    return true;
  }

  unsigned hour, minute;
  char end;
  if (sscanf(time, "%2u:%2u%c", &hour, &minute, &end) != 2 ||
      hour > 23 || minute > 59)
    return false;

  const BrokenTime t(hour, minute);
  if (!store->IsTimeAvailable(field, RaspStore::TimeToIndex(t)) ||
      minute % 15 != 0)
    return false;

  state.map = field;
  state.time = t;
  state.time_auto_advance = false;
  return true;
}
