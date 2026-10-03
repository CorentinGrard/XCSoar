// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.xcsoar.AppPermissionManager
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.REPOSITORY_URI
import org.xcsoar.mobile.ui.data.DataFilesScreen
import org.xcsoar.mobile.ui.data.DownloadScreen
import org.xcsoar.mobile.ui.data.DownloadViewModel
import org.xcsoar.mobile.ui.data.DataFilesViewModel
import org.xcsoar.mobile.ui.analysis.AnalysisScreen
import org.xcsoar.mobile.ui.crew.CrewScreen
import org.xcsoar.mobile.ui.crew.CrewViewModel
import org.xcsoar.mobile.ui.crew.PilotScreen
import org.xcsoar.mobile.ui.crew.PilotViewModel
import org.xcsoar.mobile.ui.crew.PlaneEditScreen
import org.xcsoar.mobile.ui.crew.PlaneEditViewModel
import org.xcsoar.mobile.ui.analysis.AnalysisViewModel
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.tiles.TilePickerScreen
import org.xcsoar.mobile.ui.tiles.TilePickerViewModel
import org.xcsoar.mobile.ui.map.MapSettingsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsViewModel
import org.xcsoar.mobile.ui.map.MapSettingsViewModel
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.FlightsScreen
import org.xcsoar.mobile.ui.flights.FlightsViewModel
import org.xcsoar.mobile.ui.settings.AirspaceAlertsScreen
import org.xcsoar.mobile.ui.settings.AirspaceAlertsViewModel
import org.xcsoar.mobile.ui.settings.SafetyScreen
import org.xcsoar.mobile.ui.settings.SafetyViewModel
import org.xcsoar.mobile.ui.settings.SettingsScreen
import org.xcsoar.mobile.ui.settings.VarioSoundScreen
import org.xcsoar.mobile.ui.settings.VarioSoundViewModel
import org.xcsoar.mobile.ui.setup.FlightSetupScreen
import org.xcsoar.mobile.ui.task.TaskFilesScreen
import org.xcsoar.mobile.ui.task.TaskScreen
import org.xcsoar.mobile.ui.task.TaskViewModel
import org.xcsoar.mobile.ui.units.UnitsScreen
import org.xcsoar.mobile.ui.units.UnitsViewModel
import org.xcsoar.mobile.core.WaypointFilter
import org.xcsoar.mobile.ui.setup.FlightSetupViewModel
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.theme.XcsTheme
import java.io.File

private enum class Screen { FLIGHT, DATA_FILES, DOWNLOAD, MAP_SETTINGS, WAYPOINTS, FLIGHT_SETUP, FLIGHTS,
                            TASK, TASK_FILES, TASK_ADD_POINT, UNITS, ANALYSIS,
                            CREW, PLANE_EDIT, PILOT, TILE_PICKER, SETTINGS,
                            AIRSPACE_ALERTS, SAFETY, VARIO_SOUND }

/** How deep a page is: pages further in slide in from the right. */
private val Screen.depth: Int
    get() = when (this) {
        Screen.FLIGHT -> 0
        Screen.DOWNLOAD -> 3
        Screen.PLANE_EDIT, Screen.PILOT, Screen.UNITS, Screen.MAP_SETTINGS,
        Screen.DATA_FILES, Screen.AIRSPACE_ALERTS, Screen.SAFETY, Screen.VARIO_SOUND,
        Screen.TASK_FILES,
        Screen.TASK_ADD_POINT -> 2
        else -> 1
    }

private const val PAGE_MILLIS = 250

/**
 * Deeper pages slide in from the right over the one they leave; going
 * back, the page slides out to the right and uncovers the one below.
 */
private fun AnimatedContentTransitionScope<Screen>.pageTransition(): ContentTransform {
    val forward = targetState.depth > initialState.depth
    val spec = tween<IntOffset>(PAGE_MILLIS, easing = FastOutSlowInEasing)
    // opaque pages: no fade, so two pages never show through each other
    return if (forward)
        slideInHorizontally(spec) { it } togetherWith
            slideOutHorizontally(spec) { -it / 4 } using null
    else
        (slideInHorizontally(spec) { -it / 4 } togetherWith
            slideOutHorizontally(spec) { it } using null)
            .apply { targetContentZIndex = -1f }
}

/**
 * A page over the flight screen: it takes every touch, so none reaches
 * the map under it.
 */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent() }
        }) { content() }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as XcsoarApp

    private val alerts by lazy { Alerts(this) }

    /* one system permission dialog at a time, for AppPermissionManager */
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionResult?.invoke(granted)
            permissionResult = null
            // the location permission may just have been granted
            if (granted)
                FlightService.start(this)
        }

    /* the data file kind waiting for the system file picker */
    private var pickingKind: DataFile? = null
    private var onPicked: ((DataFile, String) -> Unit)? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pickingKind
            pickingKind = null
            if (uri != null && kind != null)
                onPicked?.invoke(kind, uri.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // in flight the screen must stay on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // the volume keys set the vario sound's volume (OpenSL plays on
        // the media stream), even while it is silent
        volumeControlStream = AudioManager.STREAM_MUSIC

        // the notification of FlightService (it runs without it, unseen)
        if (savedInstanceState == null)
            app.permissionManager.requestNotificationPermissionDirect()

        setContent {
            XcsTheme {
                val flightViewModel: FlightViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        FlightViewModel(
                            core = app.anyCore,
                            demoFlight = { app.demoFlight().path },
                        )
                    }
                })
                val dataViewModel: DataFilesViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        DataFilesViewModel(app.anyCore) { uri, kind ->
                            app.importDataFile(Uri.parse(uri), kind)
                        }
                    }
                })
                onPicked = dataViewModel::choose

                val mapViewModel: MapSettingsViewModel = viewModel(factory = viewModelFactory {
                    initializer { MapSettingsViewModel(app.anyCore) }
                })

                val downloadViewModel: DownloadViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        DownloadViewModel(
                            app.anyCore,
                            index = { app.downloader.index(REPOSITORY_URI).path },
                            download = app::downloadDataFile,
                        )
                    }
                })

                val waypointsViewModel: WaypointsViewModel = viewModel(factory = viewModelFactory {
                    initializer { WaypointsViewModel(app.anyCore) }
                })

                val setupViewModel: FlightSetupViewModel = viewModel(factory = viewModelFactory {
                    initializer { FlightSetupViewModel(app.anyCore) }
                })

                val flightsViewModel: FlightsViewModel = viewModel(factory = viewModelFactory {
                    initializer { FlightsViewModel(app::flightLogs, app.core) }
                })

                val taskViewModel: TaskViewModel = viewModel(factory = viewModelFactory {
                    initializer { TaskViewModel(app.anyCore) }
                })
                // a second waypoint list, to pick task points from all waypoints
                val pickViewModel: WaypointsViewModel = viewModel(
                    key = "pick", factory = viewModelFactory {
                        initializer { WaypointsViewModel(app.anyCore, WaypointFilter.ALL) }
                    })

                val unitsViewModel: UnitsViewModel = viewModel(factory = viewModelFactory {
                    initializer { UnitsViewModel(app.anyCore) }
                })

                val analysisViewModel: AnalysisViewModel = viewModel(factory = viewModelFactory {
                    initializer { AnalysisViewModel(app.anyCore) }
                })

                val crewViewModel: CrewViewModel = viewModel(factory = viewModelFactory {
                    initializer { CrewViewModel(app.anyCore) }
                })
                val planeEditViewModel: PlaneEditViewModel = viewModel(factory = viewModelFactory {
                    initializer { PlaneEditViewModel(app.anyCore) }
                })
                val pilotViewModel: PilotViewModel = viewModel(factory = viewModelFactory {
                    initializer { PilotViewModel(app.anyCore) }
                })
                val varioSoundViewModel: VarioSoundViewModel = viewModel(
                    factory = viewModelFactory { initializer { VarioSoundViewModel(app.anyCore) } })
                val safetyViewModel: SafetyViewModel = viewModel(
                    factory = viewModelFactory { initializer { SafetyViewModel(app.anyCore) } })
                val airspaceAlertsViewModel: AirspaceAlertsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { AirspaceAlertsViewModel(app.anyCore) }
                    })
                val tilePickerViewModel: TilePickerViewModel = viewModel(
                    factory = viewModelFactory { initializer { TilePickerViewModel(app.anyCore) } })

                LaunchedEffect(flightViewModel) {
                    flightViewModel.alerts.collect { alerts.play(it) }
                }

                // once per app start: the plane and crew of this flight
                var screen by rememberSaveable {
                    mutableStateOf(if (app.crewChosen || app.core == null) Screen.FLIGHT
                                   else Screen.CREW)
                }
                /* the crew screen was opened from the menu (it has Back) */
                var crewFromMenu by rememberSaveable { mutableStateOf(false) }
                /* data files open from the menu and from settings: back to either */
                var dataFilesBack by rememberSaveable { mutableStateOf(Screen.FLIGHT) }
                var airspaceAlertsBack by rememberSaveable { mutableStateOf(Screen.FLIGHT) }
                /* the flight menu; it stays open under the pages it opens,
                   so their Back comes back to it */
                var menuOpen by rememberSaveable { mutableStateOf(false) }
                val crewDone = {
                    app.crewChosen = true
                    screen = Screen.FLIGHT
                }
                if (screen == Screen.CREW && !crewFromMenu) {
                    // never hold up a flight: the app restarted in the air
                    val flying = flightViewModel.flightState.collectAsState().value?.flying
                    LaunchedEffect(flying) { if (flying == true) crewDone() }
                }

                Box(Modifier
                    .fillMaxSize()
                    .then(if (screen != Screen.FLIGHT) Modifier.clearAndSetSemantics {}
                          else Modifier)) {
                    FlightScreen(
                        flightViewModel,
                        onOpenDataFiles = {
                            dataFilesBack = Screen.FLIGHT
                            screen = Screen.DATA_FILES
                        },
                        onOpenWaypoints = { screen = Screen.WAYPOINTS },
                        onOpenFlightSetup = { screen = Screen.FLIGHT_SETUP },
                        onOpenFlights = { screen = Screen.FLIGHTS },
                        onOpenTask = { screen = Screen.TASK },
                        onOpenAnalysis = { screen = Screen.ANALYSIS },
                        onOpenCrew = {
                            crewFromMenu = true
                            screen = Screen.CREW
                        },
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenAirspaceAlerts = {
                            airspaceAlertsBack = Screen.FLIGHT
                            screen = Screen.AIRSPACE_ALERTS
                        },
                        onEditTile = { layout, tile ->
                            tilePickerViewModel.open(layout, tile)
                            screen = Screen.TILE_PICKER
                        },
                        menuOpen = menuOpen,
                        onMenuOpen = { menuOpen = it },
                        covered = screen != Screen.FLIGHT,
                    )
                }

                // pages slide in over the flight screen, which keeps
                // running under them (the map is not torn down)
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { pageTransition() },
                    modifier = Modifier.fillMaxSize(),
                    label = "page",
                ) { page ->
                    if (page != Screen.FLIGHT) Page {
                        when (page) {
                            Screen.TILE_PICKER -> TilePickerScreen(tilePickerViewModel) {
                                flightViewModel.refreshTiles()
                                screen = Screen.FLIGHT
                            }
                            Screen.CREW -> CrewScreen(
                                crewViewModel,
                                onEditPlane = { plane ->
                                    planeEditViewModel.open(plane)
                                    screen = Screen.PLANE_EDIT
                                },
                                onDone = crewDone,
                                onBack = if (crewFromMenu) crewDone else null,
                            )
                            Screen.PLANE_EDIT -> PlaneEditScreen(
                                planeEditViewModel,
                                onSaved = { path ->
                                    crewViewModel.load(select = path)
                                    screen = Screen.CREW
                                },
                                onDeleted = {
                                    crewViewModel.load()
                                    screen = Screen.CREW
                                },
                                onBack = { screen = Screen.CREW },
                            )
                            Screen.SETTINGS -> SettingsScreen(
                                onBack = { screen = Screen.FLIGHT },
                                onPilot = { screen = Screen.PILOT },
                                onUnits = { screen = Screen.UNITS },
                                onMap = if (flightViewModel.hasMap) ({ screen = Screen.MAP_SETTINGS })
                                        else null,
                                onDataFiles = {
                                    dataFilesBack = Screen.SETTINGS
                                    screen = Screen.DATA_FILES
                                },
                                onAirspaceAlerts = {
                                    airspaceAlertsBack = Screen.SETTINGS
                                    screen = Screen.AIRSPACE_ALERTS
                                },
                                onSafety = { screen = Screen.SAFETY },
                                onVarioSound = { screen = Screen.VARIO_SOUND },
                            )
                            Screen.VARIO_SOUND -> VarioSoundScreen(
                                varioSoundViewModel, onBack = { screen = Screen.SETTINGS })
                            Screen.SAFETY -> SafetyScreen(
                                safetyViewModel, onBack = { screen = Screen.SETTINGS })
                            Screen.AIRSPACE_ALERTS -> AirspaceAlertsScreen(
                                airspaceAlertsViewModel, onBack = { screen = airspaceAlertsBack })
                            Screen.PILOT -> PilotScreen(
                                pilotViewModel, onBack = { screen = Screen.SETTINGS })
                            Screen.DATA_FILES -> DataFilesScreen(
                                dataViewModel,
                                onChoose = { kind ->
                                    pickingKind = kind
                                    // file types are not reliable for .xcm/.txt: offer every file
                                    filePicker.launch(arrayOf("*/*"))
                                },
                                onDownload = { kind ->
                                    downloadViewModel.open(kind)
                                    screen = Screen.DOWNLOAD
                                },
                                onBack = { screen = dataFilesBack },
                            )
                            Screen.DOWNLOAD -> DownloadScreen(
                                downloadViewModel, onDone = { screen = Screen.DATA_FILES })
                            Screen.WAYPOINTS -> WaypointsScreen(
                                waypointsViewModel,
                                // flying somewhere new: back to the map itself
                                onDone = {
                                    menuOpen = false
                                    screen = Screen.FLIGHT
                                },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.MAP_SETTINGS -> MapSettingsScreen(
                                mapViewModel, onBack = { screen = Screen.SETTINGS })
                            Screen.FLIGHT_SETUP -> FlightSetupScreen(
                                setupViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.UNITS -> UnitsScreen(
                                unitsViewModel, onBack = { screen = Screen.SETTINGS })
                            Screen.ANALYSIS -> AnalysisScreen(
                                analysisViewModel, onBack = { screen = Screen.FLIGHT })
                            Screen.TASK -> TaskScreen(
                                taskViewModel,
                                onAddPoint = { screen = Screen.TASK_ADD_POINT },
                                onOpenFiles = { screen = Screen.TASK_FILES },
                                onBack = { screen = Screen.FLIGHT })
                            Screen.TASK_FILES -> TaskFilesScreen(
                                taskViewModel, onBack = { screen = Screen.TASK })
                            Screen.TASK_ADD_POINT -> WaypointsScreen(
                                pickViewModel, onDone = { screen = Screen.TASK },
                                title = "Add point", onPick = taskViewModel::add)
                            Screen.FLIGHTS -> FlightsScreen(
                                flightsViewModel, onShare = ::share, onOpenUrl = ::openUrl,
                                onBack = { screen = Screen.FLIGHT })
                            Screen.FLIGHT -> {}
                        }
                    }
                }
            }
        }
    }

    /** A web page, e.g. the flight on WeGlide, in the browser. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // no browser
        }
    }

    /** Hand an IGC file to another app (mail, WeGlide, XContest…). */
    private fun share(flight: FlightLog) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", File(flight.path))
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, flight.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // the chooser takes the grant from the clip data (for its preview)
        send.clipData = ClipData.newRawUri(flight.name, uri)
        startActivity(Intent.createChooser(send, flight.name))
    }

    override fun onStart() {
        super.onStart()
        // keeps flying when the screen goes off or another app is in front
        FlightService.start(this)
    }

    override fun onResume() {
        super.onResume()
        app.permissionManager.requester = AppPermissionManager.Requester { permission, onResult ->
            if (permissionResult != null) {
                onResult(false)   // another dialog is open
            } else {
                permissionResult = onResult
                permissionLauncher.launch(permission)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing)
            FlightService.stop(this)
        alerts.release()
        super.onDestroy()
    }

    override fun onPause() {
        app.permissionManager.requester = null
        super.onPause()
    }
}
