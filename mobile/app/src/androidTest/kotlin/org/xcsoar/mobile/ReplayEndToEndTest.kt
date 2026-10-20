// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.xcsoar.mobile.core.TileLayout
import org.xcsoar.mobile.ui.Format

/**
 * L5 (mobile/docs/ARCHITECTURE.md §6): the real core, optimised as it
 * flies (build type l5), replays the demo flight through the app, and
 * the flight screen shows it flying, with a vario and a wind estimate;
 * a tap on a tile asks for the page its InfoBox opens.
 *
 * Runs on an emulator in CI and on a phone: the l5 app has its own
 * application id, so the pilot's app and data are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ReplayEndToEndTest {
    private val permissions = GrantPermissionRule.grant(*buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray())

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test
    fun demoFlightFliesOnTheFlightScreen() {
        startFlightScreen()

        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Replay").performScrollTo().performClick()
        compose.onNodeWithText("Demo flight").performClick()
        waitFor("the replay bar", STARTUP_MS) {
            exists(hasContentDescription("Skip 10 minutes"))
        }

        // XCSoar sees the take-off a few minutes into the file; "On ground"
        // also goes when the core has no state ("Starting…"): not a take-off
        compose.onNodeWithContentDescription("Skip 10 minutes").performClick()
        waitFor("the take-off", FLIGHT_MS) {
            !exists(hasText("On ground")) && !exists(hasText("Starting…")) &&
                exists(hasContentDescription("Stop replay"))
        }

        waitFor("a climb or sink on the vario", FLIGHT_MS) {
            descriptions("Vario ").any { !it.startsWith("Vario 0.0,") &&
                                         !it.startsWith("Vario ${Format.INVALID},") }
        }

        // a circling estimate needs a few turns
        compose.onNodeWithContentDescription("Skip 10 minutes").performClick()
        waitFor("a wind estimate", FLIGHT_MS) {
            descriptions("Wind from ").any { !it.contains(Format.INVALID) }
        }

        compose.onNodeWithContentDescription("Stop replay").performClick()
        waitFor("the replay stopped", STARTUP_MS) {
            !exists(hasContentDescription("Stop replay"))
        }
    }

    /**
     * What a tapped tile opens, from the optimised core: the host tests
     * (debug builds) missed that it folded the panels' seams into one
     * function, so every panel tile opened Flight setup.
     */
    @Test
    fun tilesOpenTheirInfoBoxPage() = runBlocking {
        startFlightScreen()
        val core = requireNotNull(ApplicationProvider.getApplicationContext<XcsoarApp>().core) {
            "no native core"
        }

        // InfoBox types (src/InfoBoxes/Content/Type.hpp) and their pages
        val pages = mapOf(
            10 to "maccready",  // e_MacCready
            25 to "wind",       // e_WindSpeed_Est
            55 to "team_code",  // e_Team_Code
            94 to "altitude",   // NavAltitude
            103 to "atc",       // ATC_RADIAL
            115 to "radio",     // e_ActiveRadio
            116 to "radio",     // e_StandbyRadio
        )
        for ((type, page) in pages) {
            core.setTile(TileLayout.CIRCLING, 5, type)
            assertEquals("tile type $type", page,
                         core.tapTile(TileLayout.CIRCLING, 5)?.action)
        }
    }

    /** Past the plane picker of a fresh app, to the flight screen. */
    private fun startFlightScreen() {
        if (exists(hasText("Not now")))
            compose.onNodeWithText("Not now").performClick()
        waitFor("the core started", STARTUP_MS) { exists(hasText("On ground")) }
    }

    private fun exists(matcher: SemanticsMatcher) =
        compose.onAllNodes(matcher, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /** The content descriptions starting with [prefix] now. */
    private fun descriptions(prefix: String): List<String> =
        compose.onAllNodes(hasContentDescription(prefix, substring = true),
                           useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
            .flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            .filter { it.startsWith(prefix) }

    private fun waitFor(what: String, timeoutMs: Long, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: Throwable) {
            // what the flight screen showed instead
            val seen = (descriptions("Vario ") + descriptions("Wind from ") +
                        listOf("On ground", "Starting…", "NO GPS", "REPLAY")
                            .filter { exists(hasText(it)) }).joinToString(" | ")
            throw AssertionError("$what: not within ${timeoutMs / 1000} s; seen: $seen", e)
        }
    }

    private companion object {
        const val STARTUP_MS = 30_000L
        /** the replay runs at 10× and skips at ~80× */
        const val FLIGHT_MS = 120_000L
    }
}
