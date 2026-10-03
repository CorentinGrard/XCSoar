// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.crew

import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.xcsoar.mobile.core.PlaneInfo
import org.xcsoar.mobile.ui.theme.XcsTheme

/** L4: aircraft and crew picker, plane editor, pilot and WeGlide. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class CrewScreenshotTest {
    private val planes = listOf(
        PlaneInfo("/d/planes/D-1234.xcp", "D-1234", "XY", "LS 4", "LS-4", 160, false),
        PlaneInfo("/d/planes/D-5678.xcp", "D-5678", "", "Duo Discus", "Duo Discus", 61, true),
    )

    private fun crew(name: String, state: CrewState, onBack: (() -> Unit)?) =
        captureRoboImage("src/test/screenshots/crew_$name.png") {
            XcsTheme(dark = false) {
                CrewContent(state, {}, {}, {}, {}, {}, {}, onBack)
            }
        }

    /* at app start: the last plane flown is chosen, no Back */
    @Test
    fun singleSeater() = crew("single", CrewState(
        planes, lastFlown = planes[0].path, selected = planes[0].path), onBack = null)

    @Test
    fun twoSeaterWithCopilot() = crew("two_seats", CrewState(
        planes, lastFlown = planes[0].path, selected = planes[1].path,
        copilots = listOf("John Roe", "Ann Smith"), copilot = "John Roe"), onBack = {})

    @Test
    fun noPlaneYet() = crew("empty", CrewState(emptyList()), onBack = null)

    @Test
    fun planeEditor() = captureRoboImage("src/test/screenshots/plane_edit.png") {
        XcsTheme(dark = false) {
            PlaneEditContent(
                PlaneEditState(path = "/d/planes/D-5678.xcp", registration = "D-5678",
                               type = "Duo Discus", polarName = "Duo Discus",
                               weGlideType = 61, weGlideName = "Duo Discus",
                               doubleSeater = true),
                {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    @Test
    fun pilot() = captureRoboImage("src/test/screenshots/pilot.png") {
        XcsTheme(dark = false) {
            PilotContent(PilotState(loaded = true, pilot = "Jane Doe", weGlideEnabled = true,
                                    pilotId = "1234", birthdate = "1980-05-31"),
                         {}, {}, {}, {}, {}, {})
        }
    }
}
