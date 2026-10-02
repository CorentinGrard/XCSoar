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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.xcsoar.AppPermissionManager
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.ui.data.DataFilesScreen
import org.xcsoar.mobile.ui.data.DataFilesViewModel
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.theme.XcsTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as XcsoarApp

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

                var showDataFiles by rememberSaveable { mutableStateOf(false) }
                if (showDataFiles)
                    DataFilesScreen(
                        dataViewModel,
                        onChoose = { kind ->
                            pickingKind = kind
                            // file types are not reliable for .xcm/.txt: offer every file
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onBack = { showDataFiles = false },
                    )
                else
                    FlightScreen(flightViewModel, onOpenDataFiles = { showDataFiles = true })
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

    override fun onPause() {
        app.permissionManager.requester = null
        super.onPause()
    }
}
