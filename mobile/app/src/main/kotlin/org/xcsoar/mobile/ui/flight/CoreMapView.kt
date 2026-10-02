// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.flight

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The surface XCSoar's core draws its moving map into (D6).  The core
 * owns the drawing; this only hands over the surface and its size.
 */
@Composable
fun CoreMapView(
    onAttach: (surface: Surface, width: Int, height: Int, dpi: Int) -> Unit,
    onDetach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int,
                                                width: Int, height: Int) {
                        onAttach(holder.surface, width, height,
                                 context.resources.displayMetrics.densityDpi)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onDetach()
                    }
                })
            }
        },
        modifier = modifier,
    )
}
