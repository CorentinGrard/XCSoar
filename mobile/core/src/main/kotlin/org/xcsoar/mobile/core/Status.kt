// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** One line of a status page: a label and its value ("" when unknown). */
@Serializable
data class StatusRow(val label: String, val value: String)

/** A page of XCSoar's status dialog; [name] is flight, system, task, rules or times. */
@Serializable
data class StatusPage(val name: String, val title: String, val rows: List<StatusRow>)

/**
 * XCSoar's status dialog (`xcs_get_status_pages`): its pages as the core
 * formats them, and the GPS position (degrees) when there is a fix.
 */
@Serializable
data class StatusPages(
    val pages: List<StatusPage> = emptyList(),
    private val location: List<Double>? = null,
) {
    val latitude get() = location?.getOrNull(0)
    val longitude get() = location?.getOrNull(1)

    companion object {
        fun parse(text: String): StatusPages = json.decodeFromString(serializer(), text)
    }
}
