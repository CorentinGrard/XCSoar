// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.ProfileInfo
import org.xcsoar.mobile.core.XcsoarCore

/**
 * XCSoar's profiles: list, create, copy, import, delete, and switch
 * (which restarts the app with the other profile).
 *
 * @param importFile copy a picked document (URI) into XCSoarData/profiles
 * @param switch save the profile in use and restart with [ProfileInfo.path]
 */
class ProfilesViewModel(
    private val core: XcsoarCore,
    private val importFile: suspend (String) -> String,
    private val switch: suspend (String) -> Unit,
) : ViewModel() {
    private val profilesFlow = MutableStateFlow<List<ProfileInfo>?>(null)
    /** `null` while loading. */
    val profiles: StateFlow<List<ProfileInfo>?> = profilesFlow.asStateFlow()

    private val errorFlow = MutableStateFlow<String?>(null)
    /** Why the last action failed; cleared by the next one. */
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            profilesFlow.value = try {
                core.profiles()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** The profile asks for a password before [use] or [delete]. */
    suspend fun checkPassword(profile: ProfileInfo, password: String): Boolean =
        try {
            core.checkProfilePassword(profile.path, password)
        } catch (_: Exception) {
            false
        }

    /** Fly with [profile] from now on: the app restarts with it. */
    fun use(profile: ProfileInfo) = act("Could not switch profiles") { switch(profile.path) }

    /**
     * A new profile [name]: XCSoar's defaults, or a copy of the profile
     * in use.
     */
    fun create(name: String, copyCurrent: Boolean) =
        act("Not created: \"${name.trim()}\" is taken or not a file name") {
            val current = profilesFlow.value?.firstOrNull { it.current }
            core.createProfile(name.trim(), current?.path.takeIf { copyCurrent })
        }

    fun import(uri: String) = act("Not imported", showCause = true) { importFile(uri) }

    fun delete(profile: ProfileInfo, password: String? = null) =
        act("${profile.name} not deleted") { core.deleteProfile(profile.path, password) }

    /** @param showCause add the exception's message (ours, not the core's) */
    private fun act(failure: String, showCause: Boolean = false, block: suspend () -> Unit) {
        errorFlow.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                errorFlow.value = e.message?.takeIf { showCause }?.let { "$failure: $it" }
                    ?: failure
            }
            refresh()
        }
    }
}
