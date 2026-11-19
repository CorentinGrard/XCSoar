// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreInfoBoxes.hpp"
#include "CoreAnalysis.hpp"
#include "InfoBoxes/Content/Base.hpp"
#include "InfoBoxes/Content/Factory.hpp"
#include "InfoBoxes/Content/Group.hpp"
#include "InfoBoxes/Data.hpp"
#include "Math/Angle.hpp"
#include "Engine/Navigation/TraceHistory.hpp"
#include "FlightStatistics.hpp"
#include "Computer/GlideComputer.hpp"
#include "BackendComponents.hpp"
#include "Components.hpp"
#include "Interface.hpp"
#include "InfoBoxes/Panel/Panel.hpp"
#include "Widget/Widget.hpp"
#include "Language/Language.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Current.hpp"
#include "Profile/Map.hpp"
#include "Units/Descriptor.hpp"
#include "util/StringCompare.hxx"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <array>
#include <memory>
#include <mutex>
#include <string_view>
#include <vector>

using namespace InfoBoxFactory;
using std::string_view_literals::operator""sv;

/** Profile keys of the layouts (mobile app keys): comma-separated
    InfoBox type numbers, as upstream stores its InfoBox panels. */
static constexpr std::array<std::string_view, CoreInfoBoxes::LAYOUT_COUNT>
  LAYOUT_KEYS{"MobileTilesCruise"sv, "MobileTilesCircling"sv};

/** The design's tiles, as upstream InfoBoxes; MacCready is the third
    (the app steps it from there; its comment is the speed to fly). */
static constexpr Type DEFAULT_LAYOUTS[CoreInfoBoxes::LAYOUT_COUNT]
                                     [CoreInfoBoxes::TILE_COUNT] = {
  {NavAltitude, e_HeightAGL, e_MacCready, e_WP_GR, e_Speed_GPS, e_TL_Avg},
  {e_Thermal_Avg, e_Thermal_Gain, e_MacCready, NavAltitude, e_HeightAGL,
   e_WindSpeed_Est},
};

/** Set once a profile's saved layouts got their MacCready tile. */
static constexpr std::string_view MACCREADY_TILE_KEY = "MobileTilesMacCready"sv;

/** Where a saved layout gets its MacCready tile: the design's place. */
static constexpr unsigned MACCREADY_TILE = 2;

/** Types that only draw (OnCustomPaint()) and whose drawing the app
    does not have yet (Graphic()): not offered for a tile. */
static constexpr Type GRAPHIC_TYPES[] = {
  e_Horizon,
};

static bool
IsGraphic(Type type) noexcept
{
  return std::find(std::begin(GRAPHIC_TYPES), std::end(GRAPHIC_TYPES),
                   type) != std::end(GRAPHIC_TYPES);
}

/** e_CustomText shows the text of upstream's InfoBox panels, which
    the app does not edit: not offered for a tile either. */
static bool
IsTileType(unsigned type) noexcept
{
  return type < NUM_TYPES && !IsGraphic(Type(type)) && type != e_CustomText;
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
ParseLayout(unsigned layout) noexcept
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
  /* saved with MacCready a tile: GiveMacCreadyATile() leaves it */
  Profile::map.Set(MACCREADY_TILE_KEY, true);
  Profile::Save();
}

/* the profile's saved layouts were checked for MacCready */
static bool maccready_checked = false;

/**
 * MacCready had a control of its own under the tiles; now it is a
 * tile.  A layout saved before gets it once, in its third tile, unless
 * it shows MacCready already: else it would be gone from the screen.
 * Layouts saved since are the pilot's choice (SaveLayout() marks them).
 */
static void
GiveMacCreadyATile() noexcept
{
  maccready_checked = true;
  bool done = false;
  if (Profile::map.Get(MACCREADY_TILE_KEY, done) && done)
    return;

  for (unsigned layout = 0; layout < CoreInfoBoxes::LAYOUT_COUNT; ++layout) {
    if (*Profile::Get(LAYOUT_KEYS[layout], "") == 0)
      continue;  // the defaults have it

    auto types = ParseLayout(layout);
    if (std::find(types.begin(), types.end(), e_MacCready) == types.end())
      types[MACCREADY_TILE] = e_MacCready;
    SaveLayout(layout, types);
  }
}

static std::array<Type, CoreInfoBoxes::TILE_COUNT>
LoadLayout(unsigned layout) noexcept
{
  if (!maccready_checked)
    GiveMacCreadyATile();
  return ParseLayout(layout);
}

std::string
CoreInfoBoxes::DescribeTypes() noexcept
{
  std::vector<Type> types;
  for (unsigned i = 0; i < NUM_TYPES; ++i)
    if (IsTileType(i))
      types.push_back(Type(i));

  /* by upstream's topics, in the order of its content picker */
  std::sort(types.begin(), types.end(), [](Type a, Type b){
    if (GetGroup(a) != GetGroup(b))
      return GetGroup(a) < GetGroup(b);
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
      {"group", gettext(GetGroupName(GetGroup(type)))},
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

static boost::json::array
History(const TraceVariableHistory &history) noexcept
{
  boost::json::array a;
  for (double v : history)
    a.emplace_back(v);
  return a;
}

/** InfoBoxContentSpark::Paint(), TraceHistoryRenderer::RenderVario()'s
    data: the last 30 values, oldest first (m/s). */
static boost::json::object
Spark(const TraceVariableHistory &history, bool center) noexcept
{
  const auto &calculated = CommonInterface::Calculated();
  return {
    {"kind", "spark"},
    {"values", History(history)},
    {"capacity", history.capacity()},
    {"center", center},
    {"mac_cready",
     CommonInterface::GetComputerSettings().polar.glide_polar_task.GetMC()},
    {"scale", boost::json::array{
        calculated.common_stats.vario_scale_negative * (center ? 1 : 0),
        calculated.common_stats.vario_scale_positive}},
  };
}

/** A tile is a few dozen pixels wide: enough points for that. */
static constexpr std::size_t SPARK_POINTS = 64;

/** [[x, y], ...] of a data store, thinned to [SPARK_POINTS], with its
    last point. */
static boost::json::array
Points(const LeastSquares &store) noexcept
{
  boost::json::array points;
  if (!store.HasResult())
    return points;

  const auto slots = store.GetSlots();
  const std::size_t step = (slots.size() + SPARK_POINTS - 1) / SPARK_POINTS;
  for (std::size_t i = 0; i < slots.size(); i += step)
    points.emplace_back(boost::json::array{slots[i].x, slots[i].y});
  if ((slots.size() - 1) % step != 0)
    points.emplace_back(boost::json::array{slots.back().x, slots.back().y});
  return points;
}

/**
 * What ThermalAssistantRenderer draws: the climb XCSoar measured on each
 * heading of the circle (LiftDatabase, from north in 10° steps), the
 * turn's direction, the heading and the average climb.
 */
static boost::json::object
ThermalAssistantJson(const MoreData &basic, const DerivedInfo &calculated) noexcept
{
  boost::json::array lift;
  for (const double v : calculated.lift_database)
    lift.emplace_back(v);
  return {
    {"kind", "thermal_assistant"},
    {"circling", calculated.circling},
    {"left", calculated.TurningLeft()},
    {"heading", basic.attitude.heading.AsBearing().Degrees()},
    {"average", calculated.average},
    {"lift", std::move(lift)},
  };
}

std::string
CoreInfoBoxes::ThermalAssistant() noexcept
{
  return Serialize(ThermalAssistantJson(CommonInterface::Basic(),
                                        CommonInterface::Calculated()));
}

/**
 * InfoBoxContentThermalAssistant without its renderer, whose look the
 * core has none of (UIGlobals): like upstream's, nothing to show unless
 * circling; the app draws it from Graphic().
 */
class ThermalAssistantContent final : public InfoBoxContent {
public:
  void Update(InfoBoxData &data) noexcept override {
    const auto &basic = CommonInterface::Basic();
    if (!CommonInterface::Calculated().circling) {
      data.SetInvalid();
      return;
    }

    data.SetCustom(basic.location_available.ToInteger() +
                   basic.attitude.heading_available.ToInteger());
  }
};

/**
 * What a graphical InfoBox's OnCustomPaint() draws, as data for the app
 * to draw; null when it would draw nothing (no SetCustom()) or for a
 * type the app does not draw.
 */
static boost::json::value
Graphic(Type type, const InfoBoxData &data) noexcept
{
  if (data.GetCustom() == 0)
    return nullptr;

  const auto &basic = CommonInterface::Basic();
  const auto &calculated = CommonInterface::Calculated();
  const auto &trace = calculated.trace_history;

  switch (type) {
  case e_Vario_spark:
    return Spark(trace.BruttoVario, true);
  case e_NettoVario_spark:
    return Spark(trace.NettoVario, true);
  case e_CirclingAverage_spark:
    return Spark(trace.CirclingAverage, false);

  case e_Barogram: {
    /* RenderBarographSpark(): the flight's altitude over time */
    if (backend_components == nullptr || !backend_components->glide_computer)
      return nullptr;
    const auto &fs = backend_components->glide_computer->GetFlightStats();
    const std::lock_guard lock{fs.mutex};
    if (!fs.altitude.HasResult())
      return nullptr;
    return boost::json::object{
      {"kind", "barogram"},
      {"points", Points(fs.altitude)},
      {"terrain", Points(fs.altitude_terrain)},
    };
  }

  case WIND_ARROW:
    /* where the wind blows to, relative to the heading */
    return boost::json::object{
      {"kind", "wind_arrow"},
      {"angle", (calculated.wind.bearing - basic.attitude.heading +
                 Angle::HalfCircle()).AsBearing().Degrees()},
    };

  case NEXT_ARROW:
    /* where the next waypoint is, relative to the track */
    return boost::json::object{
      {"kind", "next_arrow"},
      {"angle", (calculated.task_stats.current_leg.vector_remaining.bearing -
                 basic.track).AsBearing().Degrees()},
    };

  case e_TaskProgress: {
    /* TaskProgressRenderer::Draw(): the ordered task's points around a
       circle, [where along the task (0..1), achieved], the active one,
       and how much of it is left */
    const TaskSummary &summary = calculated.common_stats.ordered_summary;
    boost::json::array points;
    for (const auto &p : summary.pts)
      points.emplace_back(boost::json::array{p.p, p.achieved ? 1. : 0.});
    return boost::json::object{
      {"kind", "task_progress"},
      {"points", std::move(points)},
      {"active", summary.active},
      {"remaining", summary.p_remaining},
    };
  }

  case e_ThermalBand: {
    /* DrawThermalBandSpark(): the analysis page's band, small */
    const auto &settings = CommonInterface::GetComputerSettings();
    auto band = CoreAnalysis::ThermalBandChart(basic, calculated, settings);
    if (band.empty())
      return nullptr;
    return boost::json::object{
      {"kind", "thermal_band"},
      {"band", std::move(band)},
    };
  }

  case THERMAL_ASSISTANT:
    return ThermalAssistantJson(basic, calculated);

  case e_Climb_Perc_Chart:
    /* ClimbPercentRenderer::Draw(): a pie of the time circling and
       climbing, circling at all, and climbing without circling (%) */
    return boost::json::object{
      {"kind", "climb_percent"},
      {"values", boost::json::array{
          calculated.circling_climb_percentage,
          calculated.circling_percentage,
          calculated.noncircling_climb_percentage,
        }},
    };

  default:
    return nullptr;
  }
}

/** A new type: a new content object, as
    InfoBoxManager::DisplayInfoBox() does. */
static void
Show(Tile &tile, Type type) noexcept
{
  if (tile.type == type && tile.content)
    return;

  tile.type = type;
  tile.content = type == THERMAL_ASSISTANT
    ? std::make_unique<ThermalAssistantContent>()
    : Create(type);
  tile.data.Clear();
  tile.data.SetInvalid();
  tile.data.SetTitle(gettext(GetCaption(type)));
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

    Show(tile, types[i]);
    if (tile.content)
      tile.content->Update(tile.data);

    /* upstream's "MC MANUAL" leaves no room for the speed to fly in the
       tile's one caption line: manual is plain "MC" (its value is green,
       "MC AUTO" blue) */
    if (tile.type == e_MacCready &&
        StringIsEqual(tile.data.title.c_str(), gettext("MC MANUAL")))
      tile.data.SetTitle(gettext(GetCaption(e_MacCready)));

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
      {"graphic", Graphic(tile.type, d)},
    });
  }
  return Serialize(a);
}

void
CoreInfoBoxes::Clear() noexcept
{
  /* the next start may have another profile */
  maccready_checked = false;
  for (auto &layout : tiles)
    for (auto &tile : layout) {
      tile.content.reset();
      tile.type = e_NUM_TYPES;
    }
}

/* what the last HandleClick() asked for (RequestAction()) */
static std::string requested_action, requested_page;

void
CoreInfoBoxes::RequestAction(const char *action, const char *page) noexcept
{
  requested_action = action;
  requested_page = page != nullptr ? page : "";
}

/**
 * Ask an InfoBox's dialog panels for the page they edit: each loader (a
 * seam in InfoBoxSeams.cpp) requests it, as the dialogs do.  Not by the
 * loaders' addresses: their code is the same, and optimised builds fold
 * identical functions into one (--icf=all).
 */
static void
RequestPanelAction(const InfoBoxPanel *panels) noexcept
{
  for (; panels != nullptr && panels->load != nullptr &&
         requested_action.empty(); ++panels)
    panels->load(0);
}

std::string
CoreInfoBoxes::Click(unsigned layout, unsigned index) noexcept
{
  if (layout >= LAYOUT_COUNT || index >= TILE_COUNT)
    return {};

  /* the tile as shown, even before its first update */
  Tile &tile = tiles[layout][index];
  Show(tile, LoadLayout(layout)[index]);

  requested_action.clear();
  requested_page.clear();
  if (tile.content && !tile.content->HandleClick()) {
    /* not handled: the dialog's panels */
    requested_action.clear();
    requested_page.clear();
    RequestPanelAction(tile.content->GetDialogContent());
  }

  boost::json::object o;
  o["action"] = requested_action.empty() ? "none" : requested_action;
  if (!requested_page.empty())
    o["page"] = requested_page;
  return Serialize(o);
}

