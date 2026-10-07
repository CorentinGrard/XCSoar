// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreProfiles.hpp"
#include "LocalPath.hpp"
#include "LogFile.hpp"
#include "Profile/File.hpp"
#include "Profile/Keys.hpp"
#include "Profile/Map.hpp"
#include "Profile/Profile.hpp"
#include "Repository/FileType.hpp"
#include "system/FileUtil.hpp"
#include "system/Path.hpp"
#include "util/StringCompare.hxx"
#include "util/StringUtil.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <string_view>
#include <vector>

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

/** The profile's password; empty if it has none. */
static std::string
GetPassword(Path path) noexcept
try {
  ProfileMap map;
  Profile::LoadFile(map, path);
  const char *password = map.Get(ProfileKeys::Password);
  return password != nullptr ? password : "";
} catch (...) {
  return {};
}

static bool
IsCurrent(Path path) noexcept
{
  const Path current = Profile::GetPath();
  return current != nullptr && StringIsEqual(current.c_str(), path.c_str());
}

std::string
CoreProfiles::List() noexcept
{
  struct Item {
    std::string name, path;
  };

  class Visitor final : public File::Visitor {
  public:
    std::vector<Item> items;

    void Visit(Path path, Path filename) override {
      std::string name = filename.c_str();
      if (const auto dot = name.rfind('.'); dot != std::string::npos)
        name.erase(dot);
      items.push_back({std::move(name), path.c_str()});
    }
  } visitor;

  VisitDataFiles(GetFileTypePatterns(FileType::PROFILE), visitor);
  std::sort(visitor.items.begin(), visitor.items.end(),
            [](const Item &a, const Item &b){
              return StringCollate(a.name.c_str(), b.name.c_str()) < 0;
            });

  boost::json::array list;
  for (const auto &item : visitor.items) {
    const Path path{item.path.c_str()};
    boost::json::object o;
    o["name"] = item.name;
    o["path"] = item.path;
    o["current"] = IsCurrent(path);
    o["password"] = !GetPassword(path).empty();
    list.emplace_back(std::move(o));
  }

  return Serialize(list);
}

bool
CoreProfiles::CheckPassword(const char *path, const char *password) noexcept
{
  const auto expected = GetPassword(Path{path});
  return expected.empty() ||
    StringIsEqualIgnoreCase(expected.c_str(), password);
}

/** A name that is one file name: no folders, not hidden. */
static bool
IsValidName(const char *name) noexcept
{
  return !StringIsEmpty(name) && name[0] != '.' &&
    StringFind(name, '/') == nullptr && StringFind(name, '\\') == nullptr;
}

std::string
CoreProfiles::Create(const char *name, const char *copy_from) noexcept
try {
  if (!IsValidName(name))
    return {};

  std::string filename{name};
  filename += ".prf";
  const auto path = LocalPath(AllocatedPath::Build(
    GetFileTypeDefaultDir(FileType::PROFILE), Path{filename.c_str()}));
  if (const auto parent = path.GetParent(); parent != nullptr)
    Directory::CreateRecursive(parent);

  if (copy_from == nullptr) {
    if (!File::CreateExclusive(path))
      return {};
  } else {
    if (File::ExistsAny(path))
      return {};

    /* the current profile's latest settings are in memory */
    if (IsCurrent(Path{copy_from}))
      Profile::Save();

    ProfileMap map;
    Profile::LoadFile(map, Path{copy_from});
    Profile::SaveFile(map, path);
  }

  return path.c_str();
} catch (...) {
  LogError(std::current_exception(), "Failed to create profile");
  return {};
}

bool
CoreProfiles::Delete(const char *path) noexcept
{
  return !IsCurrent(Path{path}) && File::Delete(Path{path});
}
