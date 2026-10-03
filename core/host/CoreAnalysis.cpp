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
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>

#include <chrono>

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
    }

    o["contest"] = boost::json::object{
      {"name", ContestToString(settings.contest.contest) != nullptr
               ? ContestToString(settings.contest.contest) : ""},
      {"results", ContestResults(settings.contest.contest,
                                 calculated.contest_stats)},
      {"trace", Trace(glide_computer.GetTraceComputer())},
    };
  }

  StringOutputStream os;
  Json::Serialize(os, o);
  return std::move(os).GetValue();
}
