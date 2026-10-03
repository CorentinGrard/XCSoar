// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.analysis

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.Analysis
import org.xcsoar.mobile.core.TaskLeg
import org.xcsoar.mobile.core.TaskSpeedHistory
import org.xcsoar.mobile.ui.theme.XcsTheme

/**
 * L4: the analysis pages (mobile/docs/ARCHITECTURE.md §6), with what the
 * core wrote after replaying test/data/01lz1hq1.igc.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class AnalysisScreenshotTest {
    private val replay by lazy {
        Analysis.parse(checkNotNull(javaClass.getResource("/analysis.json")).readText())
    }

    private fun capture(name: String, page: AnalysisPage, analysis: Analysis?) =
        captureRoboImage("src/test/screenshots/analysis_$name.png") {
            XcsTheme(dark = false) {
                AnalysisContent(analysis, page, {}, {})
            }
        }

    @Test
    fun barograph() = capture("barograph", AnalysisPage.BAROGRAPH, replay)

    @Test
    fun climb() = capture("climb", AnalysisPage.CLIMB, replay)

    /* the replay flew no task: task points and speeds made up */
    @Test
    fun taskSpeed() = capture("task_speed", AnalysisPage.TASK_SPEED, Analysis.parse(
        """{"legs":[{"index":0,"t":0.4},{"index":1,"t":2.1},{"index":2,"t":3.6}],
            "task_speed":{"estimated":24.0,"average":22.5,
                          "trend":{"y0":19.0,"gradient":1.2},
                          "speeds":[[0.5,15.0],[1.0,19.5],[1.5,21.0],[2.0,24.5],
                                    [2.5,23.0],[3.0,24.0],[3.5,25.5],[4.0,25.0]]}}"""))

    @Test
    fun contest() = capture("contest", AnalysisPage.CONTEST, replay)

    @Test
    fun noData() = capture("no_data", AnalysisPage.BAROGRAPH, null)
}
