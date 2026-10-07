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
    fun thermalBand() = capture("thermal_band", AnalysisPage.THERMAL_BAND, replay)

    @Test
    fun varioHistogram() = capture("vario_histogram", AnalysisPage.VARIO_HISTOGRAM, replay)

    @Test
    fun polar() = capture("polar", AnalysisPage.POLAR, replay)

    @Test
    fun macCready() = capture("maccready", AnalysisPage.MAC_CREADY, replay)

    /* none of these in the replay: made up */
    @Test
    fun wind() = capture("wind", AnalysisPage.WIND, Analysis.parse(
        """{"legs":[],"wind":{"levels":[[600,3.0,250],[900,4.5,260],[1200,6.0,265],
            [1500,7.5,270],[1800,8.0,280],[2100,9.5,285]]}}"""))

    @Test
    fun temperature() = capture("temperature", AnalysisPage.TEMPERATURE, Analysis.parse(
        """{"legs":[],"temperature":{"levels":[[0,301,300,285],[500,296,297,284],
            [1000,291,294,283],[1500,286,291,282],[2000,281,287,null]],
            "thermal_height":1700,"cloud_base":2100}}"""))

    @Test
    fun task() = capture("task", AnalysisPage.TASK, Analysis.parse(
        """{"legs":[],"task":{"points":[
            {"name":"Benalla","location":[-36.55,145.98],"target":[-36.55,145.98]},
            {"name":"Mansfield","location":[-37.05,146.08],"target":[-37.05,146.08]},
            {"name":"Wangaratta","location":[-36.36,146.31],"target":[-36.36,146.31]},
            {"name":"Benalla","location":[-36.55,145.98],"target":[-36.55,145.98]}],
            "distance_to_go":148000,"task_to_go":5400}}"""))

    @Test
    fun noData() = capture("no_data", AnalysisPage.BAROGRAPH, null)
}
