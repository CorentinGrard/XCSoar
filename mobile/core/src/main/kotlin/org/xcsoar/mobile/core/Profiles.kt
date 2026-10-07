// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** One of XCSoar's profile files (`xcs_profiles_list`). */
@Serializable
data class ProfileInfo(
    /** The file name without ".prf". */
    val name: String,
    val path: String,
    /** The core runs with this profile. */
    val current: Boolean = false,
    /** It asks for its password before it is used, copied or deleted. */
    val password: Boolean = false,
) {
    companion object {
        fun parseList(text: String): List<ProfileInfo> =
            json.decodeFromString(ListSerializer(serializer()), text)
    }
}
