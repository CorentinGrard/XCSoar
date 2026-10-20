// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreAnalysis.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "FlightStatistics.hpp"
#include "Computer/GlideComputer.hpp"
#include "Computer/TraceComputer.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Contest/Solvers/Contests.hpp"
#include "Engine/Trace/Point.hpp"
#include "Engine/Trace/Vector.hpp"
#include "Engine/GlideSolvers/GlidePolar.hpp"
#include "Engine/ThermalBand/ThermalBand.hpp"
#include "Atmosphere/CuSonde.hpp"
#include "Computer/Wind/Store.hpp"
#include "Computer/Settings.hpp"
#include "DataComponents.hpp"
#include "MapSettings.hpp"
#include "Airspace/AirspaceIntersectionVisitor.hpp"
#include "Airspace/AirspaceVisibility.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Engine/GlideSolvers/MacCready.hpp"
#include "Engine/GlideSolvers/GlideState.hpp"
#include "Engine/GlideSolvers/GlideResult.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "NMEA/Aircraft.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Geo/GeoVector.hpp"
#include "Formatter/HexColor.hpp"
#include "ui/canvas/PortableColor.hpp"
#include "NMEA/Derived.hpp"
#include "NMEA/MoreData.hpp"
#include "Math/LeastSquares.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <algorithm>
#include <chrono>
#include <optional>
#include <span>

using std::chrono::hours;

static boost::json::array
Points(const XYDataStore &store) noexcept
{
  boost::json::array a;
  for (const auto &slot : store.GetSlots())
    a.emplace_back(boost::json::array{slot.x, slot.y});
  return a;
}

static boost::json::object
Trend(const LeastSquares &ls) noexcept
{
  return {
    {"y0", ls.GetYAt(0)},
    {"gradient", ls.GetGradient()},
  };
}

/**
 * A line graph and its trend, as BarographRenderer draws the climb base
 * and ceiling: the line from two points on, the trend before.
 */
static void
LineOrTrend(boost::json::object &o, const char *name,
            const std::string &trend_name, const ConvexFilter &filter) noexcept
{
  if (filter.HasResult())
    o[name] = Points(filter);
  else if (!filter.IsEmpty())
    o[trend_name] = Trend(filter);
}

static boost::json::object
Barograph(const FlightStatistics &fs, bool terrain_valid) noexcept
{
  boost::json::object o;
  if (!fs.altitude.HasResult())
    return o;

  o["altitude"] = Points(fs.altitude);
  /* StatsComputer records 0 when there is no terrain */
  if (terrain_valid)
    o["terrain"] = Points(fs.altitude_terrain);
  LineOrTrend(o, "base", "base_trend", fs.altitude_base);
  LineOrTrend(o, "ceiling", "ceiling_trend", fs.altitude_ceiling);

  /* BarographCaption() */
  if (fs.altitude_ceiling.HasResult() && !fs.altitude_base.IsEmpty()) {
    o["working_band"] = boost::json::array{fs.GetMinWorkingHeight(),
                                           fs.GetMaxWorkingHeight()};
    if (fs.altitude_ceiling.GetCount() >= 4)
      o["ceiling_gradient"] = fs.altitude_ceiling.GetGradient();
  }
  return o;
}

static boost::json::object
Climb(const FlightStatistics &fs, double mac_cready) noexcept
{
  boost::json::object o{{"mac_cready", mac_cready}};
  if (fs.thermal_average.IsEmpty())
    return o;

  /* the bar of each climb spans its duration (DrawWeightBarGraph()) */
  boost::json::array thermals;
  for (const auto &slot : fs.thermal_average.GetSlots())
    thermals.emplace_back(boost::json::array{slot.x, slot.y, slot.weight});
  o["thermals"] = std::move(thermals);

  /* ClimbChartCaption() */
  o["average"] = fs.thermal_average.GetAverageY();
  if (fs.thermal_average.HasResult()) {
    o["trend"] = Trend(fs.thermal_average);
    o["gradient"] = fs.thermal_average.GetGradient();
  }
  return o;
}

static boost::json::object
TaskSpeed(const FlightStatistics &fs, const GlidePolar &polar) noexcept
{
  boost::json::object o{{"estimated", polar.GetAverageSpeed()}};
  o["speeds"] = Points(fs.task_speed);
  o["trend"] = Trend(fs.task_speed);
  o["average"] = fs.task_speed.GetAverageY();
  return o;
}

/** As IsTaskLegVisible() in src/Renderer/TaskLegRenderer.cpp. */
static bool
IsLegReached(const OrderedTaskPoint &tp) noexcept
{
  switch (tp.GetType()) {
  case TaskPointType::START:
    return tp.HasExited();

  case TaskPointType::FINISH:
  case TaskPointType::AAT:
  case TaskPointType::AST:
    return tp.HasEntered();

  case TaskPointType::UNORDERED:
    break;
  }

  return false;
}

/** The task points reached, as RenderTaskLegs() marks them. */
static boost::json::array
Legs(const TaskManager &task_manager, const DerivedInfo &calculated) noexcept
{
  boost::json::array legs;
  if (!calculated.ordered_task_stats.start.HasStarted())
    return legs;

  const OrderedTask &task = task_manager.GetOrderedTask();
  for (unsigned i = 0, n = task.TaskSize(); i < n; ++i) {
    const OrderedTaskPoint &tp = task.GetTaskPoint(i);
    if (!IsLegReached(tp))
      continue;

    const auto dt = tp.GetScoredState().time - calculated.flight.takeoff_time;
    if (dt.count() >= 0)
      legs.emplace_back(boost::json::object{
        {"index", i},
        {"t", dt / hours{1}},
      });
  }
  return legs;
}

static boost::json::object
ContestResultJson(const char *label, const ContestStatistics &stats,
                  int index) noexcept
{
  const ContestResult &result = stats.GetResult(index);

  boost::json::array points;
  for (const auto &p : stats.GetSolution(index))
    if (p.IsDefined() && p.location.IsValid())
      points.emplace_back(boost::json::array{p.location.latitude.Degrees(),
                                             p.location.longitude.Degrees()});

  return {
    {"label", label},
    {"distance", result.distance},
    {"score", result.score},
    {"time", result.time.count()},
    {"speed", result.GetSpeed()},
    {"points", std::move(points)},
  };
}

/** The results FlightStatisticsRenderer::CaptionContest() shows. */
static boost::json::array
ContestResults(Contest contest, const ContestStatistics &stats) noexcept
{
  boost::json::array a;
  switch (contest) {
  case Contest::OLC_PLUS:
    a.emplace_back(ContestResultJson("Classic", stats, 0));
    a.emplace_back(ContestResultJson("FAI", stats, 1));
    a.emplace_back(ContestResultJson("Plus", stats, 2));
    break;

  case Contest::DHV_XC:
  case Contest::XCONTEST:
    a.emplace_back(ContestResultJson("Free", stats, 0));
    a.emplace_back(ContestResultJson("Triangle", stats, 1));
    break;

  case Contest::OLC_LEAGUE:
    a.emplace_back(ContestResultJson("", stats, 0));
    break;

  default:
    a.emplace_back(ContestResultJson("", stats, -1));
    break;
  }
  return a;
}

/** The flight, at most #max_points of it, for drawing. */
static boost::json::array
Trace(const TraceComputer &trace_computer) noexcept
{
  static constexpr std::size_t max_points = 500;

  TracePointVector v;
  trace_computer.LockedCopyTo(v);

  const std::size_t step = (v.size() + max_points - 1) / max_points;
  boost::json::array a;
  for (std::size_t i = 0; i < v.size(); i += std::max<std::size_t>(step, 1)) {
    const GeoPoint &p = v[i].GetLocation();
    a.emplace_back(boost::json::array{p.latitude.Degrees(),
                                      p.longitude.Degrees()});
  }
  return a;
}

static boost::json::array
Points(std::span<const DoublePoint2D> points) noexcept
{
  boost::json::array a;
  for (const auto &p : points)
    a.emplace_back(boost::json::array{p.x, p.y});
  return a;
}

/** RenderVarioHistogram(): counts of vario values, cruise and circling. */
static boost::json::object
VarioHistogram(const FlightStatistics &fs, const GlidePolar &polar) noexcept
{
  boost::json::object o{
    {"mac_cready", polar.GetMC()},
    {"cruise_sink", -polar.GetSBestLD()},
  };
  if (fs.vario_cruise_histogram.empty() &&
      fs.vario_circling_histogram.empty())
    return o;

  o["cruise"] = Points(fs.vario_cruise_histogram.GetSlots());
  o["circling"] = Points(fs.vario_circling_histogram.GetSlots());
  return o;
}

/**
 * ThermalBandRenderer::DrawThermalProfile(): the band's climb rate by
 * height above the terrain base, smoothed with its neighbours.
 */
static boost::json::array
ThermalProfile(const ThermalBand &band, double hoffset) noexcept
{
  boost::json::array a;
  if (!band.Valid())
    return a;

  for (unsigned i = 0; i < band.size(); ++i) {
    constexpr double k_smooth = 0.5;
    double k = band.GetSlice(i).n;
    double w = band.GetSlice(i).w_n * k;
    if (k > 0) {
      for (const unsigned j : {i - 1, i + 1}) {
        if (band.Occupied(j)) {
          const double k_this = k_smooth * band.GetSlice(j).n;
          w += band.GetSlice(j).w_n * k_this;
          k += k_this;
        }
      }
      w /= k;
    }
    a.emplace_back(boost::json::array{w, band.GetSliceCenter(i) - hoffset});
  }
  return a;
}

/**
 * ThermalBandRenderer::DrawThermalBand() (not on the map): heights
 * above the terrain base; "range" is the chart's height range, which
 * the risk MacCready line spans.
 */
static boost::json::object
ThermalBandChart(const MoreData &basic, const DerivedInfo &calculated,
                 const ComputerSettings &settings) noexcept
{
  boost::json::object o;
  /* no climbs recorded */
  if (calculated.common_stats.height_max_working <= 0)
    return o;

  const auto hoffset = calculated.GetTerrainBaseFallback();
  const GlidePolar &polar = settings.polar.glide_polar_task;

  /* ScaleChart(), ScaleChartFromThermalBand() */
  double y_min = settings.task.route_planner.safety_height_terrain;
  double y_max = y_min;
  const auto scale = [&](double h){
    y_min = std::min(y_min, h);
    y_max = std::max(y_max, h);
  };
  scale(calculated.common_stats.height_max_working - hoffset);

  const ThermalBand &collection = calculated.thermal_encounter_collection;
  const ThermalBand &current = calculated.thermal_encounter_band;
  for (const ThermalBand *band : {&collection, &current})
    if (band->Valid())
      scale(band->GetCeiling() - hoffset);

  std::optional<double> h;
  if (basic.NavAltitudeAvailable()) {
    h = basic.nav_altitude - hoffset;
    scale(*h);
    o["height"] = *h;
  }

  o["mac_cready"] = polar.GetMC();
  o["range"] = boost::json::array{y_min, y_max};
  o["working_band"] = boost::json::array{
    calculated.common_stats.height_min_working - hoffset,
    calculated.common_stats.height_max_working - hoffset,
  };
  o["collection"] = ThermalProfile(collection, hoffset);
  if (current.Valid())
    o["current"] = ThermalProfile(current, hoffset);

  /* DrawRiskMC() */
  if (h && *h > settings.task.safety_height_arrival && polar.GetMC() > 0) {
    boost::json::array risk;
    const double dh = (y_max - y_min) / 32;
    for (double y = y_min; dh > 0 && y <= y_max; y += dh) {
      const double f = calculated.CalculateWorkingFraction(
        y + hoffset, settings.task.safety_height_arrival);
      risk.emplace_back(boost::json::array{
        polar.GetRiskMC(f, settings.task.risk_gamma), y});
    }
    o["risk_mc"] = std::move(risk);
  }
  return o;
}

/**
 * RenderWindChart(): the wind at ten heights from the lowest climb
 * base to the highest ceiling: [height, m/s, bearing it blows from];
 * heights without a wind measurement are left out.
 */
static boost::json::array
WindLevels(const FlightStatistics &fs, const NMEAInfo &basic,
           const WindStore &wind_store) noexcept
{
  boost::json::array a;
  if (fs.altitude_base.IsEmpty() || fs.altitude_ceiling.IsEmpty())
    return a;

  const auto height =
    fs.altitude_ceiling.GetMaxY() - fs.altitude_ceiling.GetMinY();
  if (height <= 10)
    return a;

  constexpr unsigned steps = 10;
  for (unsigned i = 0; i < steps; ++i) {
    const auto h = height * i / (steps - 1) + fs.altitude_base.GetMinY();
    /* getWind() returns early, without setting it, when it has nothing */
    bool found = false;
    const Vector wind = wind_store.GetWind(basic.time, h, found);
      /* upstream draws 0 there: no measurement is not calm air */
    if (!found)
      continue;

    /* as WindStore::NewWind() */
    const Angle bearing = wind.x == 0 && wind.y == 0
      ? Angle::Zero()
      : Angle::FromXY(wind.x, wind.y).AsBearing();
    a.emplace_back(boost::json::array{h, wind.Magnitude(),
                                      bearing.Degrees()});
  }
  return a;
}

/**
 * RenderGlidePolar() and its caption: the polar, the dolphin speed
 * command at the current MacCready, the best glide line from MC, and
 * the climb rates flown at each speed ([m/s, m/s], sink negative).
 */
static boost::json::object
Polar(const GlidePolar &polar, const ClimbHistory &climb_history) noexcept
{
  boost::json::object o;
  if (!polar.IsValid())
    return o;

  const auto mc = polar.GetMC();
  const auto s_min = -polar.GetSMax();
  const auto vmin = polar.GetVMin();
  const auto vmax = polar.GetVMax();

  /* emplace_back(): one nested array in braces would be a copy */
  boost::json::array dolphin;
  dolphin.emplace_back(boost::json::array{vmin, mc});
  auto w = polar.GetSMin() + mc;
  for (double v_last = vmin;;) {
    w += s_min * 0.05;
    const auto v = polar.SpeedToFly(-w, 0);
    const auto w_dolphin = -polar.SinkRate(v) + w;
    if (w_dolphin <= s_min)
      break;
    if (v > v_last) {
      dolphin.emplace_back(boost::json::array{v, w_dolphin});
      v_last = v;
    }
  }

  boost::json::array curve, climbs;
  const auto dv = (vmax - vmin) / 50;
  for (auto v = vmin; v <= vmax + dv / 2; v += dv) {
    curve.emplace_back(boost::json::array{v, -polar.SinkRate(v)});
    if (climb_history.Check(v))
      climbs.emplace_back(boost::json::array{v, climb_history.Get(v)});
  }

  const auto slope = (-polar.GetSBestLD() - mc) / polar.GetVBestLD();
  o["mac_cready"] = mc;
  o["v_min"] = vmin;
  o["v_max"] = vmax;
  o["polar"] = std::move(curve);
  o["dolphin"] = std::move(dolphin);
  o["climbs"] = std::move(climbs);
  o["best_glide"] = boost::json::array{
    boost::json::array{vmin, mc + slope * vmin},
    boost::json::array{vmax, mc + slope * vmax},
  };
  /* GlidePolarCaption(), RenderGlidePolarInfo() */
  o["best_ld"] = polar.GetBestLD();
  o["v_best_ld"] = polar.GetVBestLD();
  o["min_sink"] = polar.GetSMin();
  o["v_min_sink"] = polar.GetVMin();
  o["mass"] = polar.GetTotalMass();
  if (polar.GetWingLoading() > 0)
    o["wing_loading"] = polar.GetWingLoading();
  return o;
}

/**
 * RenderMacCready() and its caption: the best glide speed and the
 * average cross-country speed for MacCready 0 to 5.2 m/s,
 * [MC, Vopt, Vave].
 */
static boost::json::object
MacCready(const GlidePolar &polar) noexcept
{
  boost::json::object o;
  if (!polar.IsValid())
    return o;

  static constexpr double MAX_MACCREADY = 5.2;
  static constexpr unsigned STEPS_MACCREADY = 25;

  GlidePolar gp = polar;
  boost::json::array speeds;
  for (unsigned i = 0; i <= STEPS_MACCREADY; ++i) {
    const double m = MAX_MACCREADY * i / STEPS_MACCREADY;
    gp.SetMC(m);
    /* upstream draws Vave from 0 at MC 0 */
    speeds.emplace_back(boost::json::array{m, gp.GetVBestLD(),
                                           i > 0 ? gp.GetAverageSpeed() : 0.});
  }
  o["mac_cready"] = polar.GetMC();
  o["v_max"] = polar.GetVMax();
  o["speeds"] = std::move(speeds);
  o["v_opt"] = polar.GetVBestLD();
  o["v_average"] = polar.GetAverageSpeed();
  o["mass"] = polar.GetTotalMass();
  if (polar.GetWingLoading() > 0)
    o["wing_loading"] = polar.GetWingLoading();
  return o;
}

/**
 * RenderTemperatureChart() and its caption: per 100 m level with data,
 * [height, dry adiabat, air, dew point or null], kelvin.
 */
static boost::json::object
Temperature(const CuSonde &cu_sonde) noexcept
{
  boost::json::array levels;
  for (unsigned i = 0; i < cu_sonde.cslevels.size(); ++i) {
    const auto &level = cu_sonde.cslevels[i];
    if (level.empty())
      continue;

    boost::json::value dew = nullptr;
    if (!level.dewpoint_empty())
      dew = level.dewpoint.ToKelvin();
    levels.emplace_back(boost::json::array{
      double(i * CuSonde::HEIGHT_STEP), level.dry_temperature.ToKelvin(),
      level.air_temperature.ToKelvin(), std::move(dew)});
  }

  boost::json::object o{{"levels", std::move(levels)}};
  if (o["levels"].as_array().size() >= 2) {
    o["thermal_height"] = cu_sonde.thermal_height;
    o["cloud_base"] = cu_sonde.cloud_base;
  }
  return o;
}

/**
 * The ordered task as FlightStatisticsRenderer::RenderTask() draws it
 * (points and legs, without their zones) and CaptionTask()'s numbers.
 */
static boost::json::object
TaskChart(const TaskManager &task_manager,
          const DerivedInfo &calculated) noexcept
{
  boost::json::object o;
  const TaskStats &stats = calculated.ordered_task_stats;
  const OrderedTask &task = task_manager.GetOrderedTask();
  if (!stats.task_valid || !stats.bounds.IsValid() ||
      IsError(task.CheckTask()))
    return o;

  boost::json::array points;
  for (unsigned i = 0, n = task.TaskSize(); i < n; ++i) {
    const OrderedTaskPoint &tp = task.GetTaskPoint(i);
    const GeoPoint &l = tp.GetLocation();
    const GeoPoint &target = tp.GetLocationRemaining();
    points.emplace_back(boost::json::object{
      {"name", tp.GetWaypoint().name},
      {"location", boost::json::array{l.latitude.Degrees(),
                                      l.longitude.Degrees()}},
      {"target", boost::json::array{target.latitude.Degrees(),
                                    target.longitude.Degrees()}},
    });
  }
  o["points"] = std::move(points);

  if (calculated.task_stats.total.remaining.IsDefined()) {
    o["distance_to_go"] = calculated.task_stats.total.remaining.GetDistance();
    if (stats.has_targets) {
      o["task_to_go"] = stats.total.time_remaining_start.count();
      o["aat_to_go"] = calculated.common_stats.aat_time_remaining.count();
      o["target_speed"] = calculated.common_stats.aat_speed_target;
    } else
      o["task_to_go"] = stats.total.time_remaining_now.count();
  }
  return o;
}

/** "#RRGGBB" */
static std::string
HexColor(RGB8Color color) noexcept
{
  char buffer[8];
  FormatHexColor(buffer, sizeof(buffer), color);
  return buffer;
}

/**
 * The airspaces on the cross-section, as AirspaceIntersectionVisitorSlice
 * collects them: each visible airspace the line crosses, with its limits
 * and where along the line it is.
 */
class CrossSectionAirspaces final : public AirspaceIntersectionVisitor {
  const AirspaceRendererSettings &settings;
  const GeoPoint start;
  const double range;
  const AltitudeState &state;

public:
  boost::json::array airspaces;

  CrossSectionAirspaces(const AirspaceRendererSettings &_settings,
                        GeoPoint _start, double _range,
                        const AltitudeState &_state) noexcept
    :settings(_settings), start(_start), range(_range), state(_state) {}

  void Visit(ConstAirspacePtr airspace) noexcept override {
    const AbstractAirspace &as = *airspace;
    if (intersections.empty() || !IsAirspaceTypeOrClassVisible(as, settings))
      return;

    boost::json::array spans;
    for (const auto &[from, to] : intersections)
      /* only one edge: the next one is beyond the line */
      spans.emplace_back(boost::json::array{
        start.Distance(from), from == to ? range : start.Distance(to)});

    const auto &look = settings.classes[as.GetTypeOrClass()];
    airspaces.emplace_back(boost::json::object{
      {"name", as.GetName()},
      {"class", AirspaceFormatter::GetClassOrType(as)},
      {"top", as.GetTopAltitude(state)},
      {"base", as.IsBaseTerrain() ? 0. : as.GetBaseAltitude(state)},
      {"fill", HexColor(look.fill_color)},
      {"border", HexColor(look.border_color)},
      {"spans", std::move(spans)},
    });
  }
};

/**
 * The analysis dialog's airspace page (CrossSectionRenderer, as
 * ChartControl::UpdateCrossSection() sets it up): 50 km along the
 * track, the terrain under it in 64 slices, the airspaces it crosses
 * and the glide line at the task polar.  null when not moving.
 */
static boost::json::value
CrossSection(const MoreData &basic, const DerivedInfo &calculated,
             const ComputerSettings &settings) noexcept
{
  if (!basic.location_available || !basic.track_available)
    return nullptr;

  static constexpr unsigned NUM_SLICES = 64;
  const GeoPoint start = basic.location;
  const GeoVector vec{50000, basic.track};

  const double nav_altitude = basic.NavAltitudeAvailable()
    ? basic.nav_altitude : 0.;
  boost::json::object o{
    {"range", vec.distance},
    {"heights", boost::json::array{fdim(nav_altitude, 3300.),
                                   std::max(3300., nav_altitude + 1000.)}},
  };
  if (basic.NavAltitudeAvailable())
    o["altitude"] = basic.nav_altitude;

  /* CrossSectionRenderer::UpdateTerrain() */
  boost::json::array terrain;
  if (data_components != nullptr && data_components->terrain != nullptr) {
    const GeoPoint diff = vec.EndPoint(start) - start;
    RasterTerrain::Lease map{*data_components->terrain};
    for (unsigned i = 0; i < NUM_SLICES; ++i) {
      const auto h = map->GetHeight(start + diff * (double(i) / (NUM_SLICES - 1)));
      if (h.IsSpecial())
        terrain.emplace_back(nullptr);
      else
        terrain.emplace_back(h.GetValue());
    }
  }
  o["terrain"] = std::move(terrain);

  if (data_components != nullptr && data_components->airspaces != nullptr) {
    const AircraftState aircraft = ToAircraftState(basic, calculated);
    CrossSectionAirspaces visitor{CommonInterface::GetMapSettings().airspace,
                                  start, vec.distance, aircraft};
    data_components->airspaces->VisitIntersecting(start, vec.EndPoint(start),
                                                  true, visitor);
    o["airspaces"] = std::move(visitor.airspaces);
  }

  /* CrossSectionRenderer::PaintGlide() */
  const GlidePolar &polar = settings.polar.glide_polar_task;
  if (basic.NavAltitudeAvailable() && polar.IsValid()) {
    const class MacCready mc(settings.task.glide, polar);
    const GlideState task(vec, 0, nav_altitude, calculated.GetWindOrZero());
    const GlideResult result = mc.SolveStraight(task);
    if (result.IsOk()) {
      const double arrival = result.GetArrivalAltitude();
      if (arrival > 0)
        o["glide"] = boost::json::array{
          boost::json::array{0., nav_altitude},
          boost::json::array{result.vector.distance, arrival}};
      else if (nav_altitude - arrival > 0)
        /* down to zero, where the line meets the ground */
        o["glide"] = boost::json::array{
          boost::json::array{0., nav_altitude},
          boost::json::array{result.vector.distance * nav_altitude /
                             (nav_altitude - arrival), 0.}};
    }
  }
  return o;
}

std::string
CoreAnalysis::Describe() noexcept
{
  const auto &calculated = CommonInterface::Calculated();
  const auto &settings = CommonInterface::GetComputerSettings();

  boost::json::object o;
  if (calculated.flight.flying)
    o["flight_time"] = calculated.flight.flight_time / hours{1};

  bool ordered_task = false;
  if (backend_components != nullptr &&
      backend_components->protected_task_manager) {
    const ProtectedTaskManager::Lease lease{
      *backend_components->protected_task_manager};
    o["legs"] = Legs(lease, calculated);
    ordered_task = lease->CheckOrderedTask();
    o["task"] = TaskChart(lease, calculated);
  } else
    o["legs"] = boost::json::array{};

  const GlidePolar &polar = settings.polar.glide_polar_task;

  if (backend_components != nullptr && backend_components->glide_computer) {
    const GlideComputer &glide_computer = *backend_components->glide_computer;
    const FlightStatistics &fs = glide_computer.GetFlightStats();

    {
      const std::lock_guard lock{fs.mutex};
      o["barograph"] = Barograph(fs, calculated.terrain_valid);
      o["climb"] = Climb(fs, polar.GetMC());
      /* RenderSpeed() and TaskSpeedCaption() */
      if (ordered_task && polar.IsValid() && fs.task_speed.HasResult())
        o["task_speed"] = TaskSpeed(fs, polar);
      o["vario_histogram"] = VarioHistogram(fs, polar);
      o["wind"] = boost::json::object{
        {"levels", WindLevels(fs, CommonInterface::Basic(),
                              glide_computer.GetWindStore())},
      };
    }

    o["temperature"] = Temperature(glide_computer.GetCuSonde());

    o["contest"] = boost::json::object{
      {"name", ContestToString(settings.contest.contest) != nullptr
               ? ContestToString(settings.contest.contest) : ""},
      {"results", ContestResults(settings.contest.contest,
                                 calculated.contest_stats)},
      {"trace", Trace(glide_computer.GetTraceComputer())},
    };
  }

  o["thermal_band"] = ThermalBandChart(CommonInterface::Basic(), calculated,
                                       settings);
  o["polar"] = Polar(polar, calculated.climb_history);
  o["mac_cready"] = MacCready(polar);
  o["airspace"] = CrossSection(CommonInterface::Basic(), calculated, settings);

  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}
