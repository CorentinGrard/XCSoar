// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import org.xcsoar.mobile.ui.analysis.AnalysisViewModel
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.map.MapSettingsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsScreen
import org.xcsoar.mobile.ui.waypoints.WaypointsViewModel
import org.xcsoar.mobile.ui.map.MapSettingsViewModel
import org.xcsoar.mobile.ui.flights.FlightLog
import org.xcsoar.mobile.ui.flights.FlightsScreen
import org.xcsoar.mobile.ui.flights.FlightsViewModel
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
                            TASK, TASK_FILES, TASK_ADD_POINT, UNITS, ANALYSIS }

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
                    initializer { FlightsViewModel(app::flightLogs) }
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

                LaunchedEffect(flightViewModel) {
                    flightViewModel.alerts.collect { alerts.play(it) }
                }

                var screen by rememberSaveable { mutableStateOf(Screen.FLIGHT) }
                when (screen) {
                    Screen.FLIGHT -> FlightScreen(
                        flightViewModel,
                        onOpenDataFiles = { screen = Screen.DATA_FILES },
                        onOpenMapSettings = { screen = Screen.MAP_SETTINGS },
                        onOpenWaypoints = { screen = Screen.WAYPOINTS },
                        onOpenFlightSetup = { screen = Screen.FLIGHT_SETUP },
                        onOpenFlights = { screen = Screen.FLIGHTS },
                        onOpenTask = { screen = Screen.TASK },
                        onOpenUnits = { screen = Screen.UNITS },
                        onOpenAnalysis = { screen = Screen.ANALYSIS },
                    )
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
                        onBack = { screen = Screen.FLIGHT },
                    )
                    Screen.DOWNLOAD -> DownloadScreen(
                        downloadViewModel, onDone = { screen = Screen.DATA_FILES })
                    Screen.WAYPOINTS -> WaypointsScreen(
                        waypointsViewModel, onDone = { screen = Screen.FLIGHT })
                    Screen.MAP_SETTINGS -> MapSettingsScreen(
                        mapViewModel, onBack = { screen = Screen.FLIGHT })
                    Screen.FLIGHT_SETUP -> FlightSetupScreen(
                        setupViewModel, onBack = { screen = Screen.FLIGHT })
                    Screen.UNITS -> UnitsScreen(
                        unitsViewModel, onBack = { screen = Screen.FLIGHT })
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
                        flightsViewModel, onShare = ::share, onBack = { screen = Screen.FLIGHT })
                }
            }
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
