// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

/*
 * The moving map of the headless core: what MainWindow and
 * GlueMapWindow do for the map in XCSoar's OpenGL builds, without the
 * window system.  The app owns the surface; the core main thread owns
 * the EGL context and draws (D6).
 */

#include "CoreMap.hpp"
#include "AndroidGraphics.hpp"
#include "Interface.hpp"
#include "Components.hpp"
#include "BackendComponents.hpp"
#include "DataComponents.hpp"
#include "MapWindow/MapWindow.hpp"
#include "Look/MapLook.hpp"
#include "Look/TrafficLook.hpp"
#include "Look/GlobalFonts.hpp"
#include "Look/DefaultFonts.hpp"
#include "Screen/Layout.hpp"
#include "Screen/Debug.hpp"
#include "Hardware/DisplayDPI.hpp"
#include "ui/display/Display.hpp"
#include "ui/canvas/Canvas.hpp"
#include "ui/canvas/opengl/Init.hpp"
#include "ui/canvas/opengl/Globals.hpp"
#include "Topography/TopographyStore.hpp"
#include "Topography/TopographyGlue.hpp"
#include "Computer/GlideComputer.hpp"
#include "Task/ProtectedTaskManager.hpp"
#include "Terrain/RasterTerrain.hpp"
#include "UISettings.hpp"
#include "MapSettings.hpp"
#include "Profile/Profile.hpp"
#include "Profile/Current.hpp"
#include "Profile/Map.hpp"
#include "Profile/Keys.hpp"
#include "NMEA/Derived.hpp"
#include "MapWindow/Items/MapItem.hpp"
#include "MapWindow/Items/List.hpp"
#include "MapWindow/Items/Builder.hpp"
#include "Formatter/AirspaceFormatter.hpp"
#include "Engine/Airspace/AbstractAirspace.hpp"
#include "Engine/Waypoint/Waypoint.hpp"
#include "Engine/Airspace/Airspaces.hpp"
#include "Engine/Waypoint/Waypoints.hpp"
#include "Airspace/ProtectedAirspaceWarningManager.hpp"
#include "json/Serialize.hxx"
#include "io/StringOutputStream.hxx"

#include <boost/json.hpp>
#include "LogFile.hpp"
#include "thread/Debug.hpp"
#include "ui/event/Timer.hpp"

#include <android/native_window.h>

#include <chrono>
#include <memory>

namespace {

/** MapWindow without a parent window: the core paints it itself. */
class CoreMapWindow final : public MapWindow {
public:
  CoreMapWindow(const MapLook &look, const TrafficLook &traffic_look) noexcept
    :MapWindow(look, traffic_look) {
    /* the app's orientation button shows where north is; XCSoar's
       compass would sit under the status bar */
    compass_visible = false;
  }

  void Paint(Canvas &canvas) noexcept {
    /* like GlueMapWindow::OnPaintBuffer(): with OpenGL, the main
       thread is the draw thread while painting */
    EnterDrawThread();
    /* nothing to load before the map has a position */
    if (visible_projection.IsValid())
      UpdateData();
    OnPaintBuffer(canvas);
    LeaveDrawThread();
  }

  MapWindowProjection &Projection() noexcept {
    return visible_projection;
  }

  using MapWindowBlackboard::ReadUIState;

private:
  /** Load the terrain tiles and topography of the visible area. */
  void UpdateData() noexcept {
    UpdateTerrain();
    UpdateTopography(256);
  }
};

/** Created on the first Attach(), kept until the process exits. */
struct Graphics {
  UI::Display display{EGL_DEFAULT_DISPLAY};

  /* new data is drawn at most this often */
  static constexpr auto REDRAW_INTERVAL = std::chrono::milliseconds{250};
  UI::Timer redraw_timer{[]{ CoreMap::Render(); }};

  MapLook map_look;
  TrafficLook traffic_look;
  std::unique_ptr<CoreMapWindow> map;

  Graphics(PixelSize size) {
    ScreenInitialized();

    /* before the looks load their icons into textures */
    CoreGraphics::SetTextureNonPowerOfTwo(OpenGL::texture_non_power_of_two);

    const auto &ui = CommonInterface::GetUISettings();
    Layout::Initialise(display, size, ui.GetPercentScale(), ui.custom_dpi);
    Fonts::Initialize();
    map_look.Initialise(ui.map, Fonts::map, Fonts::map_bold);
    traffic_look.Initialise(Fonts::map);

    map = std::make_unique<CoreMapWindow>(map_look, traffic_look);
    WindowStyle style;
    style.Hide();
    map->Create(nullptr, PixelRect{size}, style);
    ConnectData();
  }

  ~Graphics() noexcept {
    map.reset();
    Fonts::Deinitialize();
    ScreenDeinitialized();
  }

  /** Like Startup() does for its GlueMapWindow. */
  void ConnectData() noexcept {
    auto &data = *data_components;
    auto &backend = *backend_components;

    if (data.topography == nullptr) {
      data.topography = std::make_unique<TopographyStore>();
      LoadConfiguredTopography(*data.topography);
    }

    map->SetWaypoints(data.waypoints.get());
    map->SetTask(backend.protected_task_manager.get());
    map->SetRoutePlanner(&backend.glide_computer->GetProtectedRoutePlanner());
    map->SetGlideComputer(backend.glide_computer.get());
    map->SetAirspaces(data.airspaces.get());
    map->SetTopography(data.topography.get());
    map->SetTerrain(data.terrain.get());

    /* until the GPS has a fix: the home waypoint, else the middle of
       the map file (Startup() only knows home) */
    if (!CommonInterface::Basic().location_available) {
      const auto &settings = CommonInterface::GetComputerSettings();
      if (settings.poi.home_location_available)
        map->SetLocation(settings.poi.home_location);
      else if (data.terrain != nullptr)
        map->SetLocation(data.terrain->GetTerrainCenter());
    }
  }
};

Graphics *graphics;
ANativeWindow *window;
EGLSurface surface = EGL_NO_SURFACE;
PixelSize size;
PixelPoint aircraft_position;

/* the map follows the aircraft until the pilot pans it */
bool follow = true;

/* for the circling zoom: the flight mode of the last frame */
bool was_circling = false;

/**
 * The screen angle, like GlueMapWindow::UpdateScreenAngle() without
 * its pages and two-finger twist.
 */
Angle
ScreenAngle(MapOrientation orientation, const NMEAInfo &basic,
            const DerivedInfo &calculated) noexcept
{
  switch (orientation) {
  case MapOrientation::NORTH_UP:
    return Angle::Zero();

  case MapOrientation::TARGET_UP:
    if (calculated.task_stats.current_leg.vector_remaining.IsValid())
      return calculated.task_stats.current_leg.vector_remaining.bearing;
    break;

  case MapOrientation::HEADING_UP:
    return basic.attitude.heading_available
      ? basic.attitude.heading
      : Angle::Zero();

  case MapOrientation::WIND_UP:
    if (calculated.wind_available && calculated.wind.norm >= 0.5)
      return calculated.wind.bearing;
    break;

  case MapOrientation::TRACK_UP:
    break;
  }

  return basic.track_available ? basic.track : Angle::Zero();
}

/**
 * Keep separate scales for cruise and circling when the profile asks
 * for it (GlueMapWindow::SwitchZoomClimb()).
 */
void
SwitchZoomClimb(MapWindowProjection &projection, bool circling) noexcept
{
  auto &settings = CommonInterface::SetMapSettings();
  if (!settings.circle_zoom_enabled)
    return;

  if (circling) {
    settings.cruise_scale = projection.GetScale();
    projection.SetScale(settings.circling_scale);
  } else {
    settings.circling_scale = projection.GetScale();
    projection.SetScale(settings.cruise_scale);
  }
}

void
ReleaseSurface() noexcept
{
  if (surface != EGL_NO_SURFACE) {
    graphics->display.MakeCurrent(EGL_NO_SURFACE);
    graphics->display.DestroySurface(surface);
    surface = EGL_NO_SURFACE;
  }

  if (window != nullptr) {
    ANativeWindow_release(window);
    window = nullptr;
  }
}

} // namespace

bool
CoreMap::Attach(ANativeWindow *_window, unsigned width, unsigned height,
                unsigned dpi) noexcept
try {
  size = {width, height};
  if (aircraft_position == PixelPoint{})
    aircraft_position = PixelRect{size}.GetCenter();

  if (graphics == nullptr) {
    Display::ProvideDPI(dpi, dpi);
    graphics = new Graphics(size);
  }

  ReleaseSurface();
  ANativeWindow_acquire(_window);
  window = _window;
  surface = graphics->display.CreateWindowSurface(window);
  graphics->display.MakeCurrent(surface);
  OpenGL::SetupViewport({width, height});

  graphics->map->Resize(size);
  Render();
  return true;
} catch (...) {
  LogError(std::current_exception(), "Map attach failed");
  return false;
}

void
CoreMap::Detach() noexcept
{
  if (graphics != nullptr)
    ReleaseSurface();
}

void
CoreMap::Deinitialise() noexcept
{
  if (graphics == nullptr)
    return;

  ReleaseSurface();
  delete graphics;
  graphics = nullptr;
  aircraft_position = {};
  follow = true;
  was_circling = false;
}

bool
CoreMap::IsAttached() noexcept
{
  return surface != EGL_NO_SURFACE;
}

void
CoreMap::SetAircraftPosition(int x, int y) noexcept
{
  aircraft_position = {x, y};
  Render();
}

void
CoreMap::Zoom(int steps) noexcept
{
  if (graphics == nullptr)
    return;

  auto &projection = graphics->map->Projection();
  projection.SetMapScale(projection.StepMapScale(projection.GetMapScale(),
                                                 steps));
  Render();
}

void
CoreMap::Pan(double dx, double dy) noexcept
{
  if (graphics == nullptr)
    return;

  auto &projection = graphics->map->Projection();
  if (!projection.IsValid())
    return;

  /* the point that was under the screen origin moves with the finger */
  const PixelPoint origin = projection.GetScreenOrigin();
  projection.SetGeoLocation(
    projection.ScreenToGeo({origin.x - int(dx), origin.y - int(dy)}));
  follow = false;
  Render();
}

void
CoreMap::Scale(double factor) noexcept
{
  if (graphics == nullptr || !(factor > 0))
    return;

  auto &projection = graphics->map->Projection();
  projection.SetFreeMapScale(projection.GetMapScale() / factor);
  Render();
}

void
CoreMap::Follow() noexcept
{
  follow = true;
  Render();
}

void
CoreMap::SetOrientation(unsigned orientation) noexcept
{
  if (orientation > unsigned(MapOrientation::WIND_UP))
    return;

  auto &settings = CommonInterface::SetMapSettings();
  settings.cruise_orientation = settings.circling_orientation =
    MapOrientation(orientation);
  Profile::map.Set(ProfileKeys::OrientationCruise, orientation);
  Profile::map.Set(ProfileKeys::OrientationCircling, orientation);
  Profile::Save();
  Render();
}

unsigned
CoreMap::GetOrientation() noexcept
{
  return unsigned(CommonInterface::GetMapSettings().cruise_orientation);
}

static boost::json::object
Describe(const MapItem &item) noexcept
{
  boost::json::object o;
  switch (item.type) {
  case MapItem::Type::LOCATION: {
    const auto &i = static_cast<const LocationMapItem &>(item);
    o["type"] = "location";
    if (i.HasElevation())
      o["elevation"] = i.elevation;
    break;
  }

  case MapItem::Type::SELF:
    o["type"] = "self";
    break;

  case MapItem::Type::TASK_OZ: {
    const auto &i = static_cast<const TaskOZMapItem &>(item);
    o["type"] = "task";
    o["name"] = i.waypoint->name.c_str();
    break;
  }

  case MapItem::Type::AIRSPACE: {
    const auto &airspace = *static_cast<const AirspaceMapItem &>(item).airspace;
    o["type"] = "airspace";
    o["name"] = airspace.GetName();
    o["class"] = AirspaceFormatter::GetClassOrType(airspace);
    char buffer[64];
    AirspaceFormatter::FormatAltitudeShort(buffer, airspace.GetTop());
    o["top"] = buffer;
    AirspaceFormatter::FormatAltitudeShort(buffer, airspace.GetBase());
    o["base"] = buffer;
    break;
  }

  case MapItem::Type::THERMAL:
    o["type"] = "thermal";
    break;

  case MapItem::Type::WAYPOINT: {
    const auto &waypoint = *static_cast<const WaypointMapItem &>(item).waypoint;
    o["type"] = "waypoint";
    o["name"] = waypoint.name.c_str();
    o["landable"] = waypoint.IsLandable();
    if (waypoint.has_elevation)
      o["elevation"] = waypoint.elevation;
    char buffer[32];
    if (waypoint.radio_frequency.Format(buffer, sizeof(buffer)) != nullptr)
      o["frequency"] = buffer;
    if (!waypoint.comment.empty())
      o["detail"] = waypoint.comment.c_str();
    break;
  }

  case MapItem::Type::TRAFFIC:
    o["type"] = "traffic";
    break;

  default:
    o["type"] = "other";
    break;
  }
  return o;
}

std::string
CoreMap::ItemsAt(int x, int y) noexcept
{
  boost::json::array items;

  if (graphics != nullptr && graphics->map->Projection().IsValid()) {
    const auto &projection = graphics->map->Projection();
    const auto location = projection.ScreenToGeo({x, y});
    const auto range =
      projection.DistancePixelsToMeters(Layout::GetHitRadius());

    const auto &basic = CommonInterface::Basic();
    const auto &calculated = CommonInterface::Calculated();
    const auto &computer_settings = CommonInterface::GetComputerSettings();
    const auto &settings = CommonInterface::GetMapSettings();
    auto &data = *data_components;
    auto &backend = *backend_components;

    /* the same sources as GlueMapWindow::ShowMapItems() */
    MapItemList list;
    MapItemListBuilder builder(list, location, range);
    if (settings.item_list.add_location)
      builder.AddLocation(basic, data.terrain.get());
    if (basic.location_available)
      builder.AddSelfIfNear(basic.location, basic.attitude.heading);
    builder.AddTaskOZs(*backend.protected_task_manager);
    builder.AddVisibleAirspace(*data.airspaces,
                               &backend.glide_computer->GetAirspaceWarnings(),
                               computer_settings.airspace, settings.airspace,
                               basic, calculated);
    if (projection.GetMapScale() <= 4000)
      builder.AddThermals(calculated.thermal_locator, basic, calculated);
    builder.AddWaypoints(*data.waypoints,
                         &backend.glide_computer->GetProtectedRoutePlanner(),
                         basic, calculated, computer_settings);
    builder.AddTraffic(basic.flarm.traffic);
    list.Sort();

    for (const auto *item : list)
      items.emplace_back(Describe(*item));
  }

  StringOutputStream os;
  Json::Serialize(os, items);
  return std::move(os).GetValue();
}

void
CoreMap::Invalidate() noexcept
{
  if (IsAttached())
    graphics->redraw_timer.SchedulePreserve(Graphics::REDRAW_INTERVAL);
}

void
CoreMap::OnDataChanged() noexcept
{
  if (graphics == nullptr)
    return;

  /* a new map file may bring new topography */
  graphics->map->SetTopography(nullptr);
  auto &topography = *data_components->topography;
  topography.Reset();
  LoadConfiguredTopography(topography);

  graphics->ConnectData();
  graphics->map->FlushCaches();
  Render();
}

void
CoreMap::Render() noexcept
{
  if (!IsAttached())
    return;

  graphics->redraw_timer.Cancel();

  auto &map = *graphics->map;
  const auto &basic = CommonInterface::Basic();

  map.ReadBlackboard(basic, CommonInterface::Calculated(),
                     CommonInterface::GetComputerSettings(),
                     CommonInterface::GetMapSettings());
  map.ReadUIState(CommonInterface::GetUIState());

  /* what GlueMapWindow's display modes do, minus its overlays: the
     app shows its own cards */
  const auto &calculated = CommonInterface::Calculated();
  const auto &settings = CommonInterface::GetMapSettings();
  auto &projection = map.Projection();

  if (calculated.circling != was_circling) {
    was_circling = calculated.circling;
    if (follow)
      SwitchZoomClimb(projection, was_circling);
  }

  const auto orientation = was_circling
    ? settings.circling_orientation
    : settings.cruise_orientation;
  projection.SetScreenAngle(ScreenAngle(orientation, basic, calculated));

  /* looking ahead in cruise when the map turns with the glider: the
     aircraft sits glider_screen_position percent above the bottom of
     the free area (whose middle is aircraft_position) */
  PixelPoint origin = aircraft_position;
  if (follow && !was_circling && orientation != MapOrientation::NORTH_UP) {
    const int free_height = 2 * (int(size.height) - aircraft_position.y);
    origin.y = int(size.height) -
      free_height * settings.glider_screen_position / 100;
  }
  projection.SetScreenOrigin(origin);
  if (follow && basic.location_available)
    projection.SetGeoLocation(basic.location);
  map.UpdateScreenBounds();

  Canvas canvas{size};
  map.Paint(canvas);
  graphics->display.SwapBuffers(surface);
}
