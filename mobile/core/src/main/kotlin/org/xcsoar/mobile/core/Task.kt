// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** A task or task point type the core allows, with XCSoar's name. */
@Serializable
data class TaskTypeChoice(val type: Int, val name: String)

/** One point of a task (`xcs_task_get`). */
@Serializable
data class TaskPointInfo(
    @SerialName("waypoint_id") val waypointId: Int,
    val name: String,
    /** "start", "turn" or "finish". */
    val kind: String,
    /** XCSoar's TaskPointFactoryType. */
    val type: Int,
    @SerialName("type_name") val typeName: String,
    /** Zone radius (lines: half their length), m; null if it has none. */
    val radius: Double? = null,
    /** Distance from the previous point, m. */
    val leg: Double = 0.0,
    /** The point types allowed here. */
    val types: List<TaskTypeChoice> = emptyList(),
)

/** The active or the edited task (`xcs_task_get`). */
@Serializable
data class TaskInfo(
    /** XCSoar's TaskFactoryType. */
    val type: Int,
    @SerialName("type_name") val typeName: String,
    val name: String = "",
    val editing: Boolean = false,
    val valid: Boolean = false,
    /** XCSoar's validation message when not valid. */
    val errors: String = "",
    /** Nominal distance, m. */
    val distance: Double = 0.0,
    /** Area tasks only. */
    @SerialName("distance_min") val distanceMin: Double? = null,
    @SerialName("distance_max") val distanceMax: Double? = null,
    /** Area tasks only, s. */
    @SerialName("aat_min_time") val aatMinTime: Double? = null,
    /** The point flown to; active task only. */
    val active: Int? = null,
    val types: List<TaskTypeChoice> = emptyList(),
    val points: List<TaskPointInfo> = emptyList(),
) {
    val isArea get() = aatMinTime != null

    companion object {
        fun parse(text: String): TaskInfo = json.decodeFromString(serializer(), text)
    }
}

/** A task XCSoar finds in a file (`xcs_task_list_files`). */
@Serializable
data class TaskFileInfo(
    val name: String,
    val path: String,
    /** The task's position in its file. */
    val index: Int = 0,
) {
    companion object {
        fun parseList(text: String): List<TaskFileInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}

/** Values of `xcs_task_op` (core/api/xcsoar_core.h). */
enum class TaskOp(val code: Int) {
    BEGIN(1),
    CANCEL(2),
    COMMIT(3),
    /** value: waypoint id */
    APPEND(4),
    REMOVE(5),
    /** Swap index and index + 1. */
    SWAP(6),
    CLEAR(7),
    /** value: TaskFactoryType */
    SET_TYPE(8),
    /** value: TaskPointFactoryType */
    SET_POINT_TYPE(9),
    /** value: metres */
    SET_RADIUS(10),
    /** value: seconds */
    SET_AAT_MIN_TIME(11),
    /** value: 1 next, -1 previous */
    ADVANCE(12),
    RESTART(13),
}
