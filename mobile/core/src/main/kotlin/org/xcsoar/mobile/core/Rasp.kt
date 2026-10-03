// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** The RASP file's fields and what the map shows (`xcs_rasp_get`). */
@Serializable
data class RaspInfo(
    val fields: List<RaspField> = emptyList(),
    /** Index into [fields]; -1 when the map shows none. */
    val field: Int = -1,
    /** "HH:MM" local; null follows the clock. */
    val time: String? = null,
) {
    val selected get() = fields.getOrNull(field)

    companion object {
        fun parse(text: String): RaspInfo = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class RaspField(
    /** RASP's name, e.g. "wstar". */
    val name: String,
    /** XCSoar's label, translated, e.g. "W*". */
    val label: String,
    val help: String? = null,
    /** "HH:MM" local, the times the file has for this field. */
    val times: List<String> = emptyList(),
)
