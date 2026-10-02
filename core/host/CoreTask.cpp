// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "CoreTask.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Protection.hpp"
#include "LocalPath.hpp"
#include "LogFile.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Task/TaskStore.hpp"
#include "Task/LoadFile.hpp"
#include "Task/SaveFile.hpp"
#include "Task/TaskFile.hpp"
#include "Task/TypeStrings.hpp"
#include "Task/ValidationErrorStrings.hpp"
#include "Engine/Task/TaskManager.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Engine/Task/Ordered/Points/OrderedTaskPoint.hpp"
#include "Engine/Task/Ordered/Points/StartPoint.hpp"
#include "Engine/Task/Ordered/Points/IntermediatePoint.hpp"
#include "Engine/Task/Factory/AbstractTaskFactory.hpp"
#include "Engine/Task/ObservationZones/CylinderZone.hpp"
#include "Engine/Task/ObservationZones/LineSectorZone.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"
#include "system/FileUtil.hpp"
#include "util/StringAPI.hxx"

#include <boost/json.hpp>

#include <memory>

/* the copy the pilot edits (main thread only) */
static std::unique_ptr<OrderedTask> edit_task;

static ProtectedTaskManager *
GetTaskManager() noexcept
{
  return backend_components != nullptr
    ? backend_components->protected_task_manager.get()
    : nullptr;
}

static const TaskBehaviour &
GetTaskBehaviour() noexcept
{
  return CommonInterface::GetComputerSettings().task;
}

/**
 * Show a change of the active task at once, like trigger_redraw() in
 * InputEventsTask.cpp: without a GPS fix nothing else recalculates.
 */
static void
Recalculate() noexcept
{
  if (!CommonInterface::Basic().location_available)
    ForceCalculation();
}

static bool
IsAreaTask(TaskFactoryType type) noexcept
{
  return type == TaskFactoryType::AAT || type == TaskFactoryType::MAT;
}

/** Lines report half their length, like XCSoar's line width setting. */
static const CylinderZone *
GetSizedZone(const OrderedTaskPoint &tp) noexcept
{
  return dynamic_cast<const CylinderZone *>(&tp.GetObservationZone());
}

static const char *
GetKind(const OrderedTaskPoint &tp) noexcept
{
  switch (tp.GetType()) {
  case TaskPointType::START:
    return "start";
  case TaskPointType::FINISH:
    return "finish";
  default:
    return "turn";
  }
}

static boost::json::array
ToJson(const LegalPointSet &types) noexcept
{
  boost::json::array a;
  for (unsigned i = 0; i < unsigned(TaskPointFactoryType::COUNT); ++i)
    if (const auto type = TaskPointFactoryType(i); types.Contains(type))
      a.emplace_back(boost::json::object{
        {"type", i},
        {"name", OrderedTaskPointName(type)},
      });
  return a;
}

static boost::json::object
ToJson(const OrderedTask &task) noexcept
{
  const auto &factory = task.GetFactory();
  const auto type = task.GetFactoryType();
  const auto errors = task.CheckTask();

  boost::json::array types;
  for (const auto t : task.GetFactoryTypes())
    types.emplace_back(boost::json::object{
      {"type", unsigned(t)},
      {"name", OrderedTaskFactoryName(t)},
    });

  boost::json::array points;
  for (unsigned i = 0; i < task.TaskSize(); ++i) {
    const OrderedTaskPoint &tp = task.GetPoint(i);
    const auto point_type = factory.GetType(tp);
    boost::json::object p{
      {"waypoint_id", tp.GetWaypoint().id},
      {"name", tp.GetWaypoint().name.c_str()},
      {"kind", GetKind(tp)},
      {"type", unsigned(point_type)},
      {"type_name", OrderedTaskPointName(point_type)},
      {"leg", i > 0 ? tp.GetNominalLegDistance() : 0.},
      {"types", ToJson(factory.GetValidTypes(i))},
    };
    if (const auto *zone = GetSizedZone(tp))
      p["radius"] = zone->GetRadius();
    points.emplace_back(std::move(p));
  }

  const auto &stats = task.GetStats();
  boost::json::object o{
    {"type", unsigned(type)},
    {"type_name", OrderedTaskFactoryName(type)},
    {"name", task.GetName().c_str()},
    {"valid", task.TaskSize() > 0 && !IsError(errors)},
    {"errors", task.TaskSize() > 0 ? getTaskValidationErrors(errors) : ""},
    {"distance", stats.distance_nominal},
    {"types", std::move(types)},
    {"points", std::move(points)},
  };
  if (IsAreaTask(type)) {
    o["distance_min"] = stats.distance_min;
    o["distance_max"] = stats.distance_max;
    o["aat_min_time"] =
      task.GetOrderedTaskSettings().aat_min_time.count();
  }
  return o;
}

static std::string
Serialize(const boost::json::value &value) noexcept
{
  StringOutputStream os;
  Json::Serialize(os, value);
  return std::move(os).GetValue();
}

std::string
CoreTask::Describe(bool edited) noexcept
{
  if (edited) {
    if (edit_task == nullptr)
      return {};

    auto o = ToJson(*edit_task);
    o["editing"] = true;
    return Serialize(o);
  }

  auto *task_manager = GetTaskManager();
  if (task_manager == nullptr)
    return {};

  const ProtectedTaskManager::Lease lease{*task_manager};
  auto o = ToJson(lease->GetOrderedTask());
  o["editing"] = false;
  o["active"] = lease->GetActiveTaskPointIndex();
  return Serialize(o);
}

void
CoreTask::BeginEdit() noexcept
{
  if (auto *task_manager = GetTaskManager())
    edit_task = task_manager->TaskClone();
}

void
CoreTask::CancelEdit() noexcept
{
  edit_task.reset();
}

bool
CoreTask::Commit() noexcept
{
  auto *task_manager = GetTaskManager();
  if (edit_task == nullptr || task_manager == nullptr)
    return false;

  /* TaskManagerDialog::Commit() */
  edit_task->GetFactory().CheckAddFinish();
  edit_task->UpdateStatsGeometry();

  if (edit_task->TaskSize() > 0 && IsError(edit_task->CheckTask()))
    return false;

  {
    /* this may change the waypoint database */
    const ScopeSuspendAllThreads suspend;
    edit_task->CheckDuplicateWaypoints(*data_components->waypoints);
    data_components->waypoints->Optimise();
  }

  task_manager->TaskCommit(*edit_task);
  edit_task.reset();
  Recalculate();

  try {
    task_manager->TaskSaveDefault();
  } catch (...) {
    LogError(std::current_exception(), "Failed to save the default task");
  }
  return true;
}

/** After a change of the points, like TaskEditPanel. */
static bool
Changed(bool ok) noexcept
{
  if (ok) {
    edit_task->ClearName();
    edit_task->UpdateGeometry();
  }
  return ok;
}

bool
CoreTask::Append(unsigned waypoint_id) noexcept
{
  if (edit_task == nullptr || edit_task->IsFull())
    return false;

  auto waypoint = data_components->waypoints->LookupId(waypoint_id);
  if (waypoint == nullptr)
    return false;

  /* TaskEditPanel::EditTaskPoint() for a new point */
  AbstractTaskFactory &factory = edit_task->GetFactory();
  std::unique_ptr<OrderedTaskPoint> point;
  if (edit_task->TaskSize() == 0)
    point = factory.CreateStart(std::move(waypoint));
  else
    point = factory.CreateIntermediate(std::move(waypoint));
  return point != nullptr && Changed(factory.Append(*point, true));
}

bool
CoreTask::Remove(unsigned index) noexcept
{
  return edit_task != nullptr && index < edit_task->TaskSize() &&
    Changed(edit_task->GetFactory().Remove(index));
}

bool
CoreTask::Swap(unsigned index) noexcept
{
  return edit_task != nullptr && index + 1 < edit_task->TaskSize() &&
    Changed(edit_task->GetFactory().Swap(index, true));
}

bool
CoreTask::Clear() noexcept
{
  if (edit_task == nullptr)
    return false;

  edit_task->RemoveAllPoints();
  return Changed(true);
}

bool
CoreTask::SetType(unsigned type) noexcept
{
  if (edit_task == nullptr || type >= unsigned(TaskFactoryType::COUNT))
    return false;

  /* TaskPropertiesPanel::OnTaskTypeChange() and Leave() */
  const auto new_type = TaskFactoryType(type);
  if (new_type != edit_task->GetFactoryType()) {
    edit_task->SetFactory(new_type);
    edit_task->GetFactory().MutateTPsToTaskType();
    edit_task->UpdateGeometry();
  }
  return true;
}

bool
CoreTask::SetPointType(unsigned index, unsigned type) noexcept
{
  if (edit_task == nullptr || index >= edit_task->TaskSize() ||
      type >= unsigned(TaskPointFactoryType::COUNT))
    return false;

  /* MutateTaskPointDialog */
  AbstractTaskFactory &factory = edit_task->GetFactory();
  const auto new_type = TaskPointFactoryType(type);
  if (!factory.GetValidTypes(index).Contains(new_type))
    return false;

  const OrderedTaskPoint &old_point = edit_task->GetPoint(index);
  if (factory.GetType(old_point) == new_type)
    return true;

  auto point = factory.CreateMutatedPoint(old_point, new_type);
  return point != nullptr && Changed(factory.Replace(*point, index, true));
}

bool
CoreTask::SetRadius(unsigned index, double radius) noexcept
{
  if (edit_task == nullptr || index >= edit_task->TaskSize() ||
      !(radius > 0 && radius <= 1e6))
    return false;

  auto *zone = dynamic_cast<CylinderZone *>(
    &edit_task->GetPoint(index).GetObservationZone());
  if (zone == nullptr)
    return false;

  /* the observation zone editors change the zone in place */
  if (auto *line = dynamic_cast<LineSectorZone *>(zone))
    line->SetLength(2 * radius);
  else
    zone->SetRadius(radius);
  return Changed(true);
}

bool
CoreTask::SetAATMinTime(double seconds) noexcept
{
  if (edit_task == nullptr || !(seconds >= 0 && seconds <= 24 * 3600))
    return false;

  auto settings = edit_task->GetOrderedTaskSettings();
  settings.aat_min_time = std::chrono::duration<unsigned>(unsigned(seconds));
  edit_task->SetOrderedTaskSettings(settings);
  return true;
}

std::string
CoreTask::ListFiles() noexcept
{
  TaskStore store;
  try {
    store.Scan(true);
  } catch (...) {
    LogError(std::current_exception(), "Task file scan failed");
  }

  boost::json::array files;
  for (unsigned i = 0; i < store.Size(); ++i)
    files.emplace_back(boost::json::object{
      {"name", store.GetName(i)},
      {"path", store.GetPath(i).c_str()},
      {"index", store.GetTaskIndex(i)},
    });
  return Serialize(files);
}

bool
CoreTask::Load(const char *path, unsigned index) noexcept
{
  if (GetTaskManager() == nullptr || path == nullptr || *path == '\0')
    return false;

  std::unique_ptr<OrderedTask> task;
  try {
    task = TaskFile::GetTask(Path{path}, GetTaskBehaviour(),
                             data_components->waypoints.get(), index);
  } catch (...) {
    LogError(std::current_exception(), "Task file load failed");
  }
  if (task == nullptr)
    return false;

  task->UpdateGeometry();
  edit_task = std::move(task);
  return true;
}

bool
CoreTask::Save(const char *name) noexcept
{
  if (edit_task == nullptr || name == nullptr || *name == '\0' ||
      StringFind(name, '/') != nullptr || StringFind(name, '\\') != nullptr)
    return false;

  /* TaskActionsPanel::SaveTask() */
  try {
    const auto folder = MakeLocalPath("tasks");
    const std::string file = std::string{name} + ".tsk";
    SaveTask(AllocatedPath::Build(folder, file.c_str()), *edit_task);
    edit_task->SetName(name);
    return true;
  } catch (...) {
    LogError(std::current_exception(), "Task file save failed");
    return false;
  }
}

bool
CoreTask::Advance(int offset) noexcept
{
  auto *task_manager = GetTaskManager();
  if (task_manager == nullptr || (offset != 1 && offset != -1))
    return false;

  task_manager->IncrementActiveTaskPoint(offset);
  Recalculate();
  return true;
}

void
CoreTask::Restart() noexcept
{
  if (auto *task_manager = GetTaskManager()) {
    task_manager->ResetTask();
    Recalculate();
  }
}

void
CoreTask::Deinitialise() noexcept
{
  edit_task.reset();
}
