// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

package org.xcsoar.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spoken callouts through Android's text-to-speech, as navigation
 * guidance: other apps' audio ducks while it speaks.  The app's words
 * are English, so is the voice when the phone has one.
 */
class Voice(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private val availableFlow = MutableStateFlow<Boolean?>(null)
    /** null until the engine answered; false: the phone has no voice. */
    val available: StateFlow<Boolean?> = availableFlow.asStateFlow()

    /* said before the engine was ready */
    private val pending = mutableListOf<String>()
    private val speaking = AtomicInteger()
    private val ids = AtomicInteger()

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts.setAudioAttributes(attributes)
                // the phone's own English, else British English
                val english = Locale.getDefault().takeIf { it.language == "en" } ?: Locale.UK
                if (tts.isLanguageAvailable(english) >= TextToSpeech.LANG_AVAILABLE)
                    tts.language = english
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished()
                    override fun onError(utteranceId: String?, errorCode: Int) = finished()
                })
            } else
                Log.w(TAG, "no text-to-speech engine ($status)")
            val waiting = synchronized(pending) {
                availableFlow.value = ok
                pending.toList().also { pending.clear() }
            }
            if (ok) waiting.forEach(::speak)
        }
    }

    /** Say [text] after what is being said; nothing without a voice. */
    fun speak(text: String) {
        synchronized(pending) {
            when (availableFlow.value) {
                null -> { pending += text; return }
                false -> return
                true -> {}
            }
        }
        Log.i(TAG, "says: $text")
        if (speaking.getAndIncrement() == 0)
            audio?.requestAudioFocus(focus)
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, "callout${ids.incrementAndGet()}") !=
            TextToSpeech.SUCCESS)
            finished()
    }

    /* the other apps get their volume back after the last callout */
    private fun finished() {
        if (speaking.decrementAndGet() <= 0) {
            speaking.set(0)
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    private companion object {
        const val TAG = "Voice"
    }
}
