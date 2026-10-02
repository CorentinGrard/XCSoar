// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

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
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.map.MapSettingsScreen
import org.xcsoar.mobile.ui.map.MapSettingsViewModel
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.theme.XcsTheme

private enum class Screen { FLIGHT, DATA_FILES, DOWNLOAD, MAP_SETTINGS }

class MainActivity : ComponentActivity() {
    private val app get() = application as XcsoarApp

    private val alerts by lazy { Alerts(this) }

    /* one system permission dialog at a time, for AppPermissionManager */
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionResult?.invoke(granted)
            permissionResult = null
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

                LaunchedEffect(flightViewModel) {
                    flightViewModel.alerts.collect { alerts.play(it) }
                }

                var screen by rememberSaveable { mutableStateOf(Screen.FLIGHT) }
                when (screen) {
                    Screen.FLIGHT -> FlightScreen(
                        flightViewModel,
                        onOpenDataFiles = { screen = Screen.DATA_FILES },
                        onOpenMapSettings = { screen = Screen.MAP_SETTINGS },
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
                    Screen.MAP_SETTINGS -> MapSettingsScreen(
                        mapViewModel, onBack = { screen = Screen.FLIGHT })
                }
            }
        }
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
        alerts.release()
        super.onDestroy()
    }

    override fun onPause() {
        app.permissionManager.requester = null
        super.onPause()
    }
}
