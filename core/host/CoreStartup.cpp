// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * Headless counterpart of src/Startup.cpp.  Keep the order of the
 * steps identical to Startup() / Shutdown() so that differences are
 * easy to review; everything that touches a window, a dialog or the
 * map drawing is left out.
 */

#include "CoreStartup.hpp"
#include "CoreReceive.hpp"
#include "Interface.hpp"
#include "ActionInterface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "Protection.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Current.hpp"
#include "Profile/Settings.hpp"
#include "DataLayoutMigration.hpp"
#include "LocalPath.hpp"
#include "DataFilePath.hpp"
#include "UIState.hpp"
#include "LogFile.hpp"
#include "Language/LanguageGlue.hpp"
#include "Units/Units.hpp"
#include "Formatter/UserGeoPointFormatter.hpp"
#include "Operation/Operation.hpp"
#include "Operation/SubOperationEnvironment.hpp"
#include "io/FileCache.hpp"
#include "io/async/AsioThread.hpp"
#include "io/async/GlobalAsioThread.hpp"
#include "net/http/Init.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "Waypoint/Waypoints.hpp"
#include "Waypoint/WaypointGlue.hpp"
#include "Waypoint/WaypointDetailsReader.hpp"
#include "Airspace/Airspaces.hpp"
#include "Airspace/AirspaceGlue.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "Airspace/AirspaceWarningManager.hpp"
#include "Task/TaskManager.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Task/DefaultTask.hpp"
#include "Engine/Task/Ordered/OrderedTask.hpp"
#include "Computer/GlideComputer.hpp"
#include "Computer/GlideComputerInterface.hpp"
#include "Computer/Events.hpp"
#include "MergeThread.hpp"
#include "CalculationThread.hpp"
#include "Blackboard/DeviceBlackboard.hpp"
#include "Device/Factory.hpp"
#include "Device/device.hpp"
#include "Device/MultipleDevices.hpp"
#include "Logger/Logger.hpp"
#include "Logger/NMEALogger.hpp"
#include "Logger/GlueFlightLogger.hpp"
#include "Replay/Replay.hpp"
#include "Plane/PlaneGlue.hpp"
#include "FLARM/Glue.hpp"
#include "NMEA/Aircraft.hpp"
#include "Storage/StorageManager.hpp"
#include "Simulator.hpp"
#include "Input/InputQueue.hpp"
#include "CoreTask.hpp"
#include "Audio/VarioGlue.hpp"
#include "system/FileUtil.hpp"

#ifdef ANDROID
#include "Android/Main.hpp"
#endif

#include <cassert>

static TaskManager *task_manager;
static GlideComputerEvents *glide_computer_events;
static GlideComputerTaskEvents *task_events;
static DeviceFactory *device_factory;

static bool
LoadProfile() noexcept
{
  /* same order as src/Startup.cpp: migrate before selecting */
  MigrateDataLayoutToSubdirs();

  /* no startup dialog: without an explicit profile, use the default
     one (like dlgStartupShowModal() does when no profile exists) */
  if (Profile::GetPath() == nullptr)
    Profile::SetFiles(nullptr);

  Profile::Load();
  Profile::Use(Profile::map);

  Units::SetConfig(CommonInterface::GetUISettings().format.units);
  SetUserCoordinateFormat(
    CommonInterface::GetUISettings().format.coordinate_format);

  return true;
}

static void
LoadTerrain(OperationEnvironment &operation) noexcept
try {
  /* synchronous for now; src/Startup.cpp uses an async loader with a
     progress widget */
  data_components->terrain = RasterTerrain::OpenTerrain(file_cache,
                                                        operation);
} catch (...) {
  LogError(std::current_exception(), "LoadTerrain failed");
}

bool
CoreStartup(OperationEnvironment &operation, bool open_devices)
{
#ifdef ANDROID
  /* the app's JNI glue must have created the Java-side objects */
  if (context == nullptr) {
    LogString("CoreStartup: no Android Context");
    return false;
  }
#endif

  CommonInterface::SetUISettings().SetDefaults();
  CommonInterface::SetSystemSettings().SetDefaults();
  CommonInterface::SetComputerSettings().SetDefaults();
  CommonInterface::SetUIState().Clear();

  const auto &computer_settings = CommonInterface::GetComputerSettings();
  auto &live_blackboard = CommonInterface::GetLiveBlackboard();

  if (!LoadProfile())
    return false;

  /* create XCSoarData on the first start */
  CreateDataPath();

  file_cache = new FileCache(GetCachePath());

  data_components = new DataComponents();
  backend_components = new BackendComponents();

  /* no UI thread to notify yet: process storage events directly */
  backend_components->storage_manager =
    std::make_unique<StorageManager>([]{});
  backend_components->storage_manager->StartMonitoring();

  ReadLanguageFile();

  backend_components->igc_logger = std::make_unique<Logger>();
  backend_components->nmea_logger = std::make_unique<NMEALogger>();

  device_factory = new DeviceFactory{
    *asio_thread, *global_cares_channel,
#ifdef ANDROID
    *context, permission_manager,
    bluetooth_helper, ioio_helper, usb_serial_helper,
#endif
  };

  backend_components->devices =
    std::make_unique<MultipleDevices>(*backend_components->device_blackboard,
                                      backend_components->nmea_logger.get(),
                                      *device_factory);

  task_events = new GlideComputerTaskEvents();
  task_manager = new TaskManager(computer_settings.task,
                                 *data_components->waypoints);
  task_manager->SetTaskEvents(*task_events);
  task_manager->Reset();

  backend_components->protected_task_manager =
    std::make_unique<ProtectedTaskManager>(*task_manager,
                                           computer_settings.task);

  LoadTerrain(operation);

  backend_components->glide_computer =
    std::make_unique<GlideComputer>(computer_settings,
                                    *data_components->waypoints,
                                    *data_components->airspaces,
                                    *backend_components->protected_task_manager,
                                    *task_events);
  backend_components->glide_computer->SetTerrain(data_components->terrain.get());
  backend_components->glide_computer->SetLogger(backend_components->igc_logger.get());
  backend_components->glide_computer->Initialise();

  backend_components->replay =
    std::make_unique<Replay>(*backend_components->device_blackboard,
                             backend_components->igc_logger.get(),
                             *backend_components->protected_task_manager);

  GlidePolar &gp =
    CommonInterface::SetComputerSettings().polar.glide_polar_task;
  gp = GlidePolar(0);
  gp.SetMC(computer_settings.task.safety_mc);
  gp.SetBugs(computer_settings.polar.degradation_factor);
  gp.SetCrewMass(computer_settings.logger.crew_mass_template);
  PlaneGlue::FromProfile(CommonInterface::SetComputerSettings().plane,
                         Profile::map);
  PlaneGlue::Synchronize(computer_settings.plane,
                         CommonInterface::SetComputerSettings(), gp);
  task_manager->SetGlidePolar(gp);

  /* topography is only used for drawing the map; it is loaded by the
     map renderer (M3), not here */

  LogFormat("Loading waypoints");
  {
    SubOperationEnvironment sub_env(operation, 256, 512);
    WaypointGlue::LoadWaypoints(*data_components->waypoints,
                                data_components->terrain.get(),
                                sub_env);
  }

  try {
    SubOperationEnvironment sub_env(operation, 512, 768);
    WaypointDetails::ReadFileFromProfile(*data_components->waypoints,
                                         sub_env);
  } catch (...) {
    LogError(std::current_exception());
  }

  auto &settings = CommonInterface::SetComputerSettings();
  WaypointGlue::SetHome(*data_components->waypoints,
                        settings.poi, settings.team_code,
                        false);
  ActionInterface::SetStartupLocation();

  backend_components->device_blackboard->Merge();
  CommonInterface::ReadBlackboardBasic(
    backend_components->device_blackboard->Basic());

  {
    SubOperationEnvironment sub_env(operation, 768, 1024);
    ReadAirspace(*data_components->airspaces,
                 computer_settings.pressure,
                 sub_env);
  }

  if (data_components->terrain)
    SetAirspaceGroundLevels(*data_components->airspaces,
                            *data_components->terrain);

  {
    const AircraftState aircraft_state =
      ToAircraftState(backend_components->device_blackboard->Basic(),
                      backend_components->device_blackboard->Calculated());
    ProtectedAirspaceWarningManager::ExclusiveLease
      lease(backend_components->glide_computer->GetAirspaceWarnings());
    lease->Reset(aircraft_state);
  }

  /* the vario sound (a no-op where there is no audio player) */
  AudioVarioGlue::Initialise();
  AudioVarioGlue::Configure(CommonInterface::GetUISettings().sound.vario);

  if (backend_components->devices != nullptr)
    devStartup(*backend_components->devices,
               CommonInterface::GetSystemSettings());

  CoreInitNotify();
  CreateCalculationThread();

  glide_computer_events = new GlideComputerEvents();
  glide_computer_events->Reset();
  live_blackboard.AddListener(*glide_computer_events);

  /* no AllMonitors: they react to backend state by showing UI
     (task advance, airspace warnings, traffic); the core reports
     events instead */

  if (!is_simulator() && computer_settings.logger.enable_flight_logger) {
    backend_components->flight_logger =
      std::make_unique<GlueFlightLogger>(live_blackboard);
    backend_components->flight_logger->SetPath(
      LogsDataSavePath("flights.log"));
  }

  if (computer_settings.logger.enable_nmea_logger)
    backend_components->nmea_logger->Enable();

  LogString("CoreStarted");

  assert(!global_running);
  global_running = true;

  backend_components->merge_thread->Start();
  backend_components->calculation_thread->Start();

  /* opens the devices, among other things (like ProcessTimer()) */
  CoreStartTimer(open_devices);

  return true;
}

void
CoreReloadDataFiles(bool map, bool waypoints, bool airspace,
                    OperationEnvironment &operation) noexcept
{
  if (map)
    waypoints = airspace = true;

  const ScopeSuspendAllThreads suspend;

  auto &glide_computer = *backend_components->glide_computer;

  if (map) {
    /* like DataGlobals::UnsetTerrain() / SetTerrain() */
    glide_computer.SetTerrain(nullptr);
    data_components->terrain.reset();
    LoadTerrain(operation);
    glide_computer.SetTerrain(data_components->terrain.get());
  }

  if (waypoints) {
    auto &way_points = *data_components->waypoints;
    WaypointGlue::LoadWaypoints(way_points, data_components->terrain.get(),
                                operation);

    try {
      WaypointDetails::ReadFileFromProfile(way_points, operation);
    } catch (...) {
      LogError(std::current_exception());
    }

    {
      ProtectedTaskManager::ExclusiveLease lease{
        *backend_components->protected_task_manager};
      auto task = lease->Clone(CommonInterface::GetComputerSettings().task);
      if (task) {
        task->CheckDuplicateWaypoints(way_points);
        way_points.Optimise();
      }
    }

    /* DataGlobals::UpdateHome(true) */
    if (!way_points.IsEmpty()) {
      auto &settings = CommonInterface::SetComputerSettings();
      WaypointGlue::SetHome(way_points, settings.poi, settings.team_code,
                            true);
      ActionInterface::SetStartupLocation();
      WaypointGlue::SaveHome(Profile::map, settings.poi, settings.team_code);
      Profile::Save();
    }
  }

  if (airspace) {
    glide_computer.GetAirspaceWarnings().Clear();
    glide_computer.ClearAirspaces();

    auto &airspaces = *data_components->airspaces;
    airspaces.Clear();
    ReadAirspace(airspaces, CommonInterface::GetComputerSettings().pressure,
                 operation);

    if (data_components->terrain)
      SetAirspaceGroundLevels(airspaces, *data_components->terrain);
  }
}

void
CoreProcessGlideComputerEvent(unsigned gce) noexcept
{
  if (gce != GCE_TAKEOFF && gce != GCE_LANDING)
    return;

  if (is_simulator() || backend_components == nullptr ||
      backend_components->igc_logger == nullptr)
    return;

  using AutoLogger = LoggerSettings::AutoLogger;
  const ComputerSettings &settings = CommonInterface::GetComputerSettings();
  const auto auto_logger = settings.logger.auto_logger;
  if (auto_logger == AutoLogger::OFF ||
      (auto_logger == AutoLogger::START_ONLY && gce != GCE_TAKEOFF))
    return;

  /* "AutoLogger start" / "AutoLogger stop", without asking */
  try {
    auto &logger = *backend_components->igc_logger;
    if (gce == GCE_TAKEOFF)
      logger.GUIStartLogger(CommonInterface::Basic(), settings,
                            backend_components->protected_task_manager.get(),
                            true);
    else
      logger.GUIStopLogger(CommonInterface::Basic(), true);
  } catch (...) {
    LogError(std::current_exception(), "Logger I/O error");
  }
}

void
CoreShutdown() noexcept
{
  auto &live_blackboard = CommonInterface::GetLiveBlackboard();

  global_running = false;

  LogString("Entering core shutdown...");

  CoreStopTimer();

  if (backend_components != nullptr &&
      backend_components->igc_logger != nullptr) {
    try {
      backend_components->igc_logger->GUIStopLogger(CommonInterface::Basic(),
                                                    true);
    } catch (...) {
      LogError(std::current_exception());
    }
  }

  if (glide_computer_events != nullptr) {
    live_blackboard.RemoveListener(*glide_computer_events);
    delete glide_computer_events;
    glide_computer_events = nullptr;
  }

  SaveFlarmColors();
  SaveFlarmMessaging();
  Profile::Save();
  /* the core may be started again in this process */
  Profile::Clear();

  if (backend_components != nullptr &&
      backend_components->devices != nullptr) {
    LogString("Stop devices");
    backend_components->devices->Close();
  }

  LogString("Stop threads");
  if (backend_components != nullptr) {
    if (backend_components->calculation_thread)
      backend_components->calculation_thread->BeginStop();

    if (backend_components->merge_thread)
      backend_components->merge_thread->BeginStop();

    if (backend_components->merge_thread &&
        backend_components->merge_thread->IsDefined()) {
      backend_components->merge_thread->Join();
      backend_components->merge_thread.reset();
    }

    if (backend_components->calculation_thread &&
        backend_components->calculation_thread->IsDefined()) {
      backend_components->calculation_thread->Join();
      backend_components->calculation_thread.reset();
    }
  }

  CoreDeinitNotify();

  CoreTask::Deinitialise();

  AudioVarioGlue::Deinitialise();

  if (backend_components != nullptr &&
      backend_components->protected_task_manager) {
    LogString("Save default task");
    try {
      backend_components->protected_task_manager->TaskSaveDefault();
    } catch (...) {
      LogError(std::current_exception());
    }
  }

  if (backend_components != nullptr)
    backend_components->devices.reset();

  delete device_factory;
  device_factory = nullptr;

  if (backend_components != nullptr) {
    backend_components->nmea_logger.reset();

    if (backend_components->protected_task_manager) {
      backend_components->protected_task_manager->SetRoutePlanner(nullptr);
      backend_components->protected_task_manager.reset();
    }
  }

  delete task_manager;
  task_manager = nullptr;

  delete task_events;
  task_events = nullptr;

  DeinitTrafficGlobals();

  if (backend_components != nullptr &&
      backend_components->storage_manager != nullptr)
    backend_components->storage_manager->StopMonitoring();

  delete backend_components;
  backend_components = nullptr;

  delete data_components;
  data_components = nullptr;

  delete file_cache;
  file_cache = nullptr;

  CloseLanguageFile();

  LogString("Finished core shutdown");
}
