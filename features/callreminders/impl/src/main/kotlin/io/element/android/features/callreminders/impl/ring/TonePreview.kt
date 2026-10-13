/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Plays a few seconds of a tone so the picker is a picker and not a guess.
 *
 * Deliberately *not* the real ring: no volume takeover, no vibration, no looping, and it stops
 * itself after a few seconds. Choosing a tone in a quiet room should not blast the room, and
 * should not need a second tap to make it stop. "Test the alarm" is the button that does the
 * real, loud thing.
 *
 * One player at a time, torn down on every new preview, so tapping down a list of tones never
 * stacks them.
 */
object TonePreview {
    private const val PREVIEW_MILLIS = 4_500L
    private const val PREVIEW_VOLUME = 0.7f

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    fun play(context: Context, tone: AlarmTone) {
        stop()
        val uri = tone.uri(context) ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                setVolume(PREVIEW_VOLUME, PREVIEW_VOLUME)
                prepare()
                start()
            }
        }.onFailure { Timber.w(it, "Could not preview the tone") }.getOrNull()
        handler.postDelayed(::stop, PREVIEW_MILLIS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }
}
