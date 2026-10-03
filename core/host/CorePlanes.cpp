// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CorePlanes.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "LocalPath.hpp"
#include "LogFile.hpp"
#include "Plane/Plane.hpp"
#include "Plane/PlaneGlue.hpp"
#include "Plane/PlaneFileGlue.hpp"
#include "Polar/PolarStore.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Keys.hpp"
#include "Repository/FileType.hpp"
#include "system/FileUtil.hpp"
#include "system/Path.hpp"
#include "util/StringCompare.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <string_view>
#include <vector>

using std::string_view_literals::operator""sv;

/** The active plane file, as src/Dialogs/Plane/PlaneListDialog.cpp
    names it. */
static constexpr std::string_view PLANE_PATH = "PlanePath"sv;

/** The plane of the last take-off (a mobile app key). */
static constexpr std::string_view LAST_FLOWN_PLANE = "MobileLastFlownPlane"sv;

/** Recent co-pilots, most recent first, separated by '|' (a mobile app
    key; upstream keeps only the current one in "CoPilotName"). */
static constexpr std::string_view RECENT_COPILOTS = "MobileCoPilots"sv;
static constexpr std::size_t MAX_RECENT_COPILOTS = 20;

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

/** A profile path, if that file still exists. */
static std::string
ExistingProfilePath(std::string_view key) noexcept
{
  const auto path = Profile::GetPath(key);
  return path != nullptr && File::Exists(path) ? path.c_str() : "";
}

std::string
CorePlanes::List() noexcept
{
  struct Item {
    std::string path;
    Plane plane;
  };

  class Visitor final : public File::Visitor {
  public:
    std::vector<Item> items;

    void Visit(Path path, [[maybe_unused]] Path filename) override {
      Item item{path.c_str(), {}};
      if (PlaneGlue::ReadFile(item.plane, path))
        items.emplace_back(std::move(item));
    }
  } visitor;

  VisitDataFiles(GetFileTypePatterns(FileType::PLANE), visitor);
  std::sort(visitor.items.begin(), visitor.items.end(),
            [](const Item &a, const Item &b){
              return StringCollate(a.plane.registration.c_str(),
                                   b.plane.registration.c_str()) < 0;
            });

  boost::json::array planes;
  for (const auto &[path, plane] : visitor.items)
    planes.emplace_back(boost::json::object{
      {"path", path},
      {"registration", plane.registration.c_str()},
      {"competition_id", plane.competition_id.c_str()},
      {"type", plane.type.c_str()},
      {"polar_name", plane.polar_name.c_str()},
      {"weglide_type", plane.weglide_glider_type},
      {"double_seater", plane.double_seater},
      {"empty_mass", plane.empty_mass},
      {"reference_mass", plane.polar_shape.reference_mass},
      {"max_ballast", plane.max_ballast},
      {"dump_time", plane.dump_time},
      {"max_speed", plane.max_speed},
      {"wing_area", plane.wing_area},
      {"handicap", plane.handicap},
    });

  return Serialize(boost::json::object{
    {"active", ExistingProfilePath(PLANE_PATH)},
    {"last_flown", ExistingProfilePath(LAST_FLOWN_PLANE)},
    {"planes", std::move(planes)},
  });
}

std::string
CorePlanes::ListPolars() noexcept
{
  boost::json::array a;
  for (const auto &item : PolarStore::GetAll())
    a.emplace_back(item.name);
  return Serialize(a);
}

std::string
CorePlanes::DescribePolar(unsigned index) noexcept
{
  const auto polars = PolarStore::GetAll();
  if (index >= polars.size())
    return {};

  const auto &item = polars[index];
  return Serialize(boost::json::object{
    {"name", item.name},
    {"reference_mass", item.reference_mass},
    {"empty_mass", double(item.empty_mass)},
    {"max_ballast", item.max_ballast},
    {"wing_area", item.wing_area},
    {"max_speed", item.v_no},
    {"handicap", item.contest_handicap},
  });
}

bool
CorePlanes::SetDetails(const char *path, const Details &d) noexcept
try {
  /* the ranges of PlaneDetailsDialog and PlanePolarDialog */
  if (path == nullptr ||
      !(d.empty_mass >= 0 && d.empty_mass <= 1000) ||
      !(d.reference_mass >= 1 && d.reference_mass <= 1000) ||
      !(d.max_ballast >= 0 && d.max_ballast <= 500) ||
      !(d.max_speed >= 0 && d.max_speed <= 160) ||
      !(d.wing_area >= 0 && d.wing_area <= 40) ||
      d.dump_time < 10 || d.dump_time > 300 ||
      d.handicap < 50 || d.handicap > 150)
    return false;

  Plane plane;
  if (!PlaneGlue::ReadFile(plane, Path{path}))
    return false;

  plane.empty_mass = d.empty_mass;
  plane.polar_shape.reference_mass = d.reference_mass;
  plane.max_ballast = d.max_ballast;
  plane.max_speed = d.max_speed;
  plane.wing_area = d.wing_area;
  plane.dump_time = d.dump_time;
  plane.handicap = d.handicap;
  PlaneGlue::WriteFile(plane, Path{path});

  if (Profile::GetPathIsEqual(PLANE_PATH, Path{path}))
    Activate(path);
  return true;
} catch (...) {
  LogError(std::current_exception(), "Failed to save the plane");
  return false;
}

/** Only letters, digits, '-' and '_', like PlaneGlue::CreateFromPolar(). */
static std::string
SafeFileName(const char *s) noexcept
{
  std::string result;
  for (; *s != '\0'; ++s)
    if ((*s >= 'A' && *s <= 'Z') || (*s >= 'a' && *s <= 'z') ||
        (*s >= '0' && *s <= '9') || *s == '-' || *s == '_')
      result += *s;
  return result;
}

/** planes/<registration>.xcp, numbered if that file exists already. */
static AllocatedPath
NewPlanePath(const char *registration) noexcept
{
  auto name = SafeFileName(registration);
  if (name.empty())
    name = "plane";

  const auto dir = LocalPath(GetFileTypeDefaultDir(FileType::PLANE));
  for (unsigned i = 1;; ++i) {
    const auto file = i == 1
      ? name + ".xcp"
      : name + "-" + std::to_string(i) + ".xcp";
    auto path = AllocatedPath::Build(dir, file.c_str());
    if (!File::Exists(path))
      return path;
  }
}

std::string
CorePlanes::Save(const char *path, const char *registration,
                 const char *competition_id, const char *type, int polar,
                 unsigned weglide_type, bool double_seater) noexcept
try {
  if (path == nullptr || registration == nullptr || *registration == '\0' ||
      competition_id == nullptr || type == nullptr)
    return {};

  const auto polars = PolarStore::GetAll();
  if (polar >= int(polars.size()))
    return {};

  const bool create = *path == '\0';
  Plane plane{};
  if (create) {
    /* a plane file without a polar cannot be read back */
    if (polar < 0)
      return {};
    plane.handicap = 100;
  } else if (!PlaneGlue::ReadFile(plane, Path{path}))
    return {};

  if (polar >= 0)
    PlaneGlue::ApplyPolar(plane, polars[polar]);

  plane.registration.SetUTF8(registration);
  plane.competition_id.SetUTF8(competition_id);
  plane.type.SetUTF8(type);
  plane.weglide_glider_type = weglide_type;
  plane.double_seater = double_seater;

  const auto target = create ? NewPlanePath(registration)
                             : AllocatedPath{Path{path}};
  PlaneGlue::WriteFile(plane, target);

  /* the active plane changed: fly with the new values */
  if (Profile::GetPathIsEqual(PLANE_PATH, target))
    Activate(target.c_str());

  return target.c_str();
} catch (...) {
  LogError(std::current_exception(), "Failed to save the plane");
  return {};
}

bool
CorePlanes::Activate(const char *path) noexcept
{
  if (path == nullptr)
    return false;

  /* as LoadFile() in src/Dialogs/Plane/PlaneListDialog.cpp */
  ComputerSettings &settings = CommonInterface::SetComputerSettings();
  if (!PlaneGlue::ReadFile(settings.plane, Path{path}))
    return false;

  Profile::SetPath(PLANE_PATH, Path{path});
  PlaneGlue::Synchronize(settings.plane, settings,
                         settings.polar.glide_polar_task);
  if (backend_components != nullptr)
    backend_components->SetTaskPolar(settings.polar);
  Profile::Save();
  return true;
}

bool
CorePlanes::Delete(const char *path) noexcept
{
  if (path == nullptr || *path == '\0' ||
      Profile::GetPathIsEqual(PLANE_PATH, Path{path}))
    return false;

  Plane plane{};
  /* only plane files */
  return PlaneGlue::ReadFile(plane, Path{path}) && File::Delete(Path{path});
}

void
CorePlanes::RecordTakeoff() noexcept
{
  const auto path = Profile::GetPath(PLANE_PATH);
  if (path == nullptr)
    return;

  Profile::SetPath(LAST_FLOWN_PLANE, path);
  Profile::Save();
}

static std::vector<std::string>
RecentCopilots() noexcept
{
  std::vector<std::string> names;
  const std::string_view list = Profile::Get(RECENT_COPILOTS, "");
  std::size_t start = 0;
  while (start < list.size()) {
    const auto end = std::min(list.find('|', start), list.size());
    if (end > start)
      names.emplace_back(list.substr(start, end - start));
    start = end + 1;
  }
  return names;
}

std::string
CorePlanes::DescribeCrew() noexcept
{
  const auto &logger = CommonInterface::GetComputerSettings().logger;

  boost::json::array copilots;
  for (const auto &name : RecentCopilots())
    copilots.emplace_back(name);

  return Serialize(boost::json::object{
    {"pilot", logger.pilot_name.c_str()},
    {"copilot", logger.copilot_name.c_str()},
    {"copilots", std::move(copilots)},
  });
}

/** Moves @p name to the top of the recent co-pilots. */
static void
RememberCopilot(const std::string &name) noexcept
{
  auto names = RecentCopilots();
  std::erase(names, name);
  names.insert(names.begin(), name);
  if (names.size() > MAX_RECENT_COPILOTS)
    names.resize(MAX_RECENT_COPILOTS);

  std::string list;
  for (const auto &n : names) {
    if (!list.empty())
      list += '|';
    list += n;
  }
  Profile::Set(RECENT_COPILOTS, list);
}

bool
CorePlanes::SetCrew(const char *pilot, const char *copilot) noexcept
{
  auto &logger = CommonInterface::SetComputerSettings().logger;

  if (pilot != nullptr) {
    logger.pilot_name.SetUTF8(pilot);
    Profile::Set(ProfileKeys::PilotName, logger.pilot_name.c_str());
  }

  if (copilot != nullptr) {
    /* '|' separates the recent names */
    std::string name{copilot};
    std::replace(name.begin(), name.end(), '|', ' ');

    logger.copilot_name.SetUTF8(name.c_str());
    Profile::Set(ProfileKeys::CoPilotName, logger.copilot_name.c_str());
    if (!logger.copilot_name.empty())
      RememberCopilot(logger.copilot_name.c_str());
  }

  Profile::Save();
  return true;
}
