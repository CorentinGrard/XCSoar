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
#include "LogFile.hpp"
#include "thread/Debug.hpp"

#include <android/native_window.h>

#include <memory>

namespace {

/** MapWindow without a parent window: the core paints it itself. */
class CoreMapWindow final : public MapWindow {
public:
  using MapWindow::MapWindow;

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

  auto &map = *graphics->map;
  const auto &basic = CommonInterface::Basic();

  map.ReadBlackboard(basic, CommonInterface::Calculated(),
                     CommonInterface::GetComputerSettings(),
                     CommonInterface::GetMapSettings());
  map.ReadUIState(CommonInterface::GetUIState());

  /* north up, the aircraft where the app wants it (GlueMapWindow
     does this with its display modes; the app shows its own cards) */
  auto &projection = map.Projection();
  projection.SetScreenAngle(Angle::Zero());
  projection.SetScreenOrigin(aircraft_position);
  if (basic.location_available)
    projection.SetGeoLocation(basic.location);
  map.UpdateScreenBounds();

  Canvas canvas{size};
  map.Paint(canvas);
  graphics->display.SwapBuffers(surface);
}
