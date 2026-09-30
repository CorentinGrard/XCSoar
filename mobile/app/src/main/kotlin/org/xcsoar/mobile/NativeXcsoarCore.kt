// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xcsoar.mobile.core.CoreEvent
import org.xcsoar.mobile.core.CoreEventType
import org.xcsoar.mobile.core.FlightState
import org.xcsoar.mobile.core.GlideComputerEvent
import org.xcsoar.mobile.core.SnapshotDecoder
import org.xcsoar.mobile.core.XcsoarCore
import java.nio.ByteBuffer

/**
 * [XcsoarCore] backed by XCSoar's glide computer in libxcsoar_core.so.
 *
 * Commands block until the core main thread has run them, so they run
 * on [Dispatchers.IO].  Only one instance may exist per process.
 *
 * @param dataPath the XCSoarData directory
 */
class NativeXcsoarCore(private val dataPath: String) : XcsoarCore, NativeCore.Listener {
    private val state = MutableStateFlow<FlightState?>(null)
    override val flightState: StateFlow<FlightState?> = state.asStateFlow()

    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CoreEvent> = eventFlow.asSharedFlow()

    private val lock = Mutex()
    private var handle = 0L

    override suspend fun start() = lock.withLock {
        if (handle != 0L) return@withLock
        withContext(Dispatchers.IO) {
            NativeCore.listener = this@NativeXcsoarCore
            val h = NativeCore.nativeCreate(dataPath)
            check(h != 0L) { "xcs_create failed" }
            val status = NativeCore.nativeStart(h)
            if (status != 0) {
                NativeCore.nativeDestroy(h)
                error("xcs_start failed: $status")
            }
            handle = h
        }
    }

    override suspend fun stop() = lock.withLock {
        if (handle == 0L) return@withLock
        withContext(Dispatchers.IO) {
            NativeCore.nativeDestroy(handle)   // stops first
            handle = 0L
            NativeCore.listener = null
        }
    }

    override suspend fun setMacCready(macCready: Double) =
        command { NativeCore.nativeSetMacCready(it, macCready) }

    override suspend fun startReplay(path: String, timeScale: Double) =
        command { NativeCore.nativeReplayStart(it, path, timeScale) }

    override suspend fun stopReplay() =
        command { NativeCore.nativeReplayStop(it) }

    private suspend fun command(block: (Long) -> Int) = lock.withLock {
        check(handle != 0L) { "core not started" }
        val status = withContext(Dispatchers.IO) { block(handle) }
        check(status == 0) { "command failed: $status" }
    }

    /* NativeCore.Listener, called on the core main thread */

    override fun onSnapshot(buffer: ByteBuffer) {
        state.value = SnapshotDecoder.decode(buffer)
    }

    override fun onEvent(type: Int, code: Int, text: String?, detail: String?) {
        val event = when (type) {
            CoreEventType.GLIDE_COMPUTER -> CoreEvent.GlideComputer(GlideComputerEvent.fromCode(code))
            CoreEventType.MESSAGE -> CoreEvent.Message(text ?: "", detail)
            CoreEventType.REPLAY_FINISHED -> CoreEvent.ReplayFinished
            else -> return
        }
        eventFlow.tryEmit(event)
    }
}
