// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.xcsoar.mobile.core.FakeXcsoarCore
import org.xcsoar.mobile.ui.flight.FlightScreen
import org.xcsoar.mobile.ui.flight.FlightViewModel
import org.xcsoar.mobile.ui.theme.XcsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // in flight the screen must stay on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            XcsTheme {
                val viewModel: FlightViewModel = viewModel(factory = viewModelFactory {
                    initializer {
                        // replaced by the native core once the JNI glue exists
                        FlightViewModel { scope -> FakeXcsoarCore(scope) }
                    }
                })
                FlightScreen(viewModel)
            }
        }
    }
}
