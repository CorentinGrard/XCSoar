// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.xcsoar.mobile.core.DataFile
import org.xcsoar.mobile.core.RepositoryFile
import org.xcsoar.mobile.core.XcsoarCore

data class DownloadState(
    val kind: DataFile = DataFile.MAP,
    /** Files of [kind]; null while the index loads. */
    val files: List<RepositoryFile>? = null,
    val query: String = "",
    /** The file being downloaded and its progress (0..1, -1 unknown). */
    val downloading: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    /** Set when the file was downloaded and loaded: the screen closes. */
    val done: Boolean = false,
) {
    /**
     * [files] matching [query]: up to two letters are a country code or
     * the start of a name ("fr" must not match "from"); longer queries
     * search names and descriptions.
     */
    val shown: List<RepositoryFile>
        get() {
            val q = query.trim()
            return files.orEmpty().filter {
                when {
                    q.isEmpty() -> true
                    q.length <= 2 -> it.area.equals(q, true) || it.name.startsWith(q, true)
                    else -> it.name.contains(q, true) || it.description.contains(q, true)
                }
            }
        }
}

/**
 * XCSoar's file repository for one kind of data file: browse, download,
 * then use it like a picked file.
 *
 * @param index downloads the repository index; returns its path
 * @param download downloads a file into XCSoarData; returns its path
 */
class DownloadViewModel(
    private val core: XcsoarCore,
    private val index: suspend () -> String,
    private val download: suspend (RepositoryFile, (Float) -> Unit) -> String,
) : ViewModel() {
    private val stateFlow = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = stateFlow.asStateFlow()

    private var job: Job? = null

    fun open(kind: DataFile) {
        job?.cancel()
        stateFlow.value = DownloadState(kind = kind)
        job = viewModelScope.launch {
            try {
                val files = core.repositoryFiles(index())
                    ?: error("downloads need the native core")
                stateFlow.update {
                    it.copy(files = files.filter { f -> f.dataFile == kind }.sortedBy { f -> f.name })
                }
            } catch (e: Exception) {
                stateFlow.update { it.copy(files = emptyList(), error = e.message ?: e.toString()) }
            }
        }
    }

    fun search(query: String) = stateFlow.update { it.copy(query = query) }

    fun download(file: RepositoryFile) {
        if (stateFlow.value.downloading != null) return
        stateFlow.update { it.copy(downloading = file.name, progress = 0f, error = null) }
        job = viewModelScope.launch {
            try {
                val path = download(file) { p -> stateFlow.update { it.copy(progress = p) } }
                core.setDataFile(stateFlow.value.kind, path)
                stateFlow.update { it.copy(downloading = null, done = true) }
            } catch (e: Exception) {
                stateFlow.update { it.copy(downloading = null, error = e.message ?: e.toString()) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        stateFlow.update { it.copy(downloading = null) }
    }
}
