// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreInfoBoxes.hpp"
#include "InfoBoxes/Content/Base.hpp"
#include "InfoBoxes/Content/Factory.hpp"
#include "InfoBoxes/Data.hpp"
#include "Language/Language.hpp"
#include "Profile/Profile.hpp"
#include "Units/Descriptor.hpp"
#include "util/StringCompare.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <array>
#include <memory>
#include <string_view>
#include <vector>

using namespace InfoBoxFactory;
using std::string_view_literals::operator""sv;

/** Profile keys of the layouts (mobile app keys): comma-separated
    InfoBox type numbers, as upstream stores its InfoBox panels. */
static constexpr std::array<std::string_view, CoreInfoBoxes::LAYOUT_COUNT>
  LAYOUT_KEYS{"MobileTilesCruise"sv, "MobileTilesCircling"sv};

/** The design's tiles, as upstream InfoBoxes. */
static constexpr Type DEFAULT_LAYOUTS[CoreInfoBoxes::LAYOUT_COUNT]
                                     [CoreInfoBoxes::TILE_COUNT] = {
  {NavAltitude, e_HeightAGL, e_Act_Speed, e_WP_GR, e_Speed_GPS, e_TL_Avg},
  {e_Thermal_Avg, e_Thermal_Gain, e_Thermal_Time, NavAltitude, e_HeightAGL,
   e_WindSpeed_Est},
};

/** Types that only draw (OnCustomPaint()): no text to show in a tile. */
static constexpr Type GRAPHIC_TYPES[] = {
  e_Barogram, e_Vario_spark, e_NettoVario_spark, e_CirclingAverage_spark,
  e_ThermalBand, e_TaskProgress, e_Horizon, WIND_ARROW, THERMAL_ASSISTANT,
  NEXT_ARROW, e_Climb_Perc_Chart,
};

static bool
IsGraphic(Type type) noexcept
{
  return std::find(std::begin(GRAPHIC_TYPES), std::end(GRAPHIC_TYPES),
                   type) != std::end(GRAPHIC_TYPES);
}

static bool
IsTileType(unsigned type) noexcept
{
  return type < NUM_TYPES && !IsGraphic(Type(type));
}

/** One tile: its type and upstream's content object, which may keep
    state between updates (e.g. averages), like an InfoBoxWindow. */
struct Tile {
  Type type = e_NUM_TYPES;
  std::unique_ptr<InfoBoxContent> content;
  InfoBoxData data;
};

static Tile tiles[CoreInfoBoxes::LAYOUT_COUNT][CoreInfoBoxes::TILE_COUNT];

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

static std::array<Type, CoreInfoBoxes::TILE_COUNT>
LoadLayout(unsigned layout) noexcept
{
  std::array<Type, CoreInfoBoxes::TILE_COUNT> types;
  std::copy(std::begin(DEFAULT_LAYOUTS[layout]),
            std::end(DEFAULT_LAYOUTS[layout]), types.begin());

  const std::string_view list = Profile::Get(LAYOUT_KEYS[layout], "");
  std::size_t start = 0;
  for (unsigned i = 0; i < types.size() && start < list.size(); ++i) {
    const auto end = std::min(list.find(',', start), list.size());
    const auto item = std::string{list.substr(start, end - start)};
    char *endptr;
    const auto value = strtoul(item.c_str(), &endptr, 10);
    if (endptr != item.c_str() && *endptr == '\0' && IsTileType(value))
      types[i] = Type(value);
    start = end + 1;
  }
  return types;
}

static void
SaveLayout(unsigned layout,
           const std::array<Type, CoreInfoBoxes::TILE_COUNT> &types) noexcept
{
  std::string list;
  for (const auto type : types) {
    if (!list.empty())
      list += ',';
    list += std::to_string(unsigned(type));
  }
  Profile::Set(LAYOUT_KEYS[layout], list);
  Profile::Save();
}

std::string
CoreInfoBoxes::DescribeTypes() noexcept
{
  std::vector<Type> types;
  for (unsigned i = 0; i < NUM_TYPES; ++i)
    if (IsTileType(i))
      types.push_back(Type(i));

  std::sort(types.begin(), types.end(), [](Type a, Type b){
    return StringCollate(gettext(GetName(a)), gettext(GetName(b))) < 0;
  });

  boost::json::array a;
  for (const auto type : types) {
    const char *description = GetDescription(type);
    a.emplace_back(boost::json::object{
      {"id", unsigned(type)},
      {"name", gettext(GetName(type))},
      {"caption", gettext(GetCaption(type))},
      {"description", description != nullptr ? gettext(description) : ""},
    });
  }
  return Serialize(a);
}

std::string
CoreInfoBoxes::DescribeLayouts() noexcept
{
  boost::json::array layouts;
  for (unsigned layout = 0; layout < LAYOUT_COUNT; ++layout) {
    boost::json::array ids;
    for (const auto type : LoadLayout(layout))
      ids.emplace_back(unsigned(type));
    layouts.emplace_back(std::move(ids));
  }
  return Serialize(boost::json::object{{"layouts", std::move(layouts)}});
}

bool
CoreInfoBoxes::SetTile(unsigned layout, unsigned tile, unsigned type) noexcept
{
  if (layout >= LAYOUT_COUNT || tile >= TILE_COUNT || !IsTileType(type))
    return false;

  auto types = LoadLayout(layout);
  types[tile] = Type(type);
  SaveLayout(layout, types);
  return true;
}

std::string
CoreInfoBoxes::Update(unsigned layout) noexcept
{
  if (layout >= LAYOUT_COUNT)
    return {};

  const auto types = LoadLayout(layout);

  boost::json::array a;
  for (unsigned i = 0; i < TILE_COUNT; ++i) {
    Tile &tile = tiles[layout][i];

    /* a new type: a new content object, as
       InfoBoxManager::DisplayInfoBox() does */
    if (tile.type != types[i] || !tile.content) {
      tile.type = types[i];
      tile.content = Create(tile.type);
      tile.data.Clear();
      tile.data.SetInvalid();
      tile.data.SetTitle(gettext(GetCaption(tile.type)));
    }

    if (tile.content)
      tile.content->Update(tile.data);

    const auto &d = tile.data;
    const char *unit = d.value_unit == Unit::UNDEFINED
      ? "" : Units::GetUnitName(d.value_unit);
    a.emplace_back(boost::json::object{
      {"type", unsigned(tile.type)},
      {"title", d.title.c_str()},
      {"value", d.value.c_str()},
      {"unit", unit != nullptr ? unit : ""},
      {"comment", d.comment.c_str()},
      {"color", d.value_color},
      {"comment_color", d.comment_color},
    });
  }
  return Serialize(a);
}

void
CoreInfoBoxes::Clear() noexcept
{
  for (auto &layout : tiles)
    for (auto &tile : layout) {
      tile.content.reset();
      tile.type = e_NUM_TYPES;
    }
}
