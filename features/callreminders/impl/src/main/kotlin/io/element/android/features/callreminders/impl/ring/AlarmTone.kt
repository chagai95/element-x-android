/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.ring

import android.content.ContentResolver
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import io.element.android.features.callreminders.impl.R

/**
 * The sounds a reminder can ring with.
 *
 * All five bundled tones are **generated from scratch** by `tools/chagai/make_tones.py` — nobody
 * else's ringtone is shipped inside the app, which keeps an AGPL build clean, and means each one
 * is built to be a seamless loop at a known loudness instead of being whatever a stock file
 * happens to be.
 *
 * The order here is the order of the picker, and [DEFAULT] is what a reminder rings with until
 * told otherwise: the marimba, because in testing it is the one that reliably pulls your eyes to
 * the phone without being unpleasant to wake up to.
 */
enum class AlarmTone(
    /** Stored in settings and in a reminder, so it must never change once shipped. */
    val id: String,
    @get:RawRes val rawRes: Int,
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
) {
    MarimbaRise(
        id = "marimba_rise",
        rawRes = R.raw.alarm_marimba_rise,
        labelRes = R.string.call_reminders_tone_marimba_rise,
        descriptionRes = R.string.call_reminders_tone_marimba_rise_subtitle,
    ),
    SynthPulse(
        id = "synth_pulse",
        rawRes = R.raw.alarm_synth_pulse,
        labelRes = R.string.call_reminders_tone_synth_pulse,
        descriptionRes = R.string.call_reminders_tone_synth_pulse_subtitle,
    ),
    BellCascade(
        id = "bell_cascade",
        rawRes = R.raw.alarm_bell_cascade,
        labelRes = R.string.call_reminders_tone_bell_cascade,
        descriptionRes = R.string.call_reminders_tone_bell_cascade_subtitle,
    ),
    RetroRing(
        id = "retro_ring",
        rawRes = R.raw.alarm_retro_ring,
        labelRes = R.string.call_reminders_tone_retro_ring,
        descriptionRes = R.string.call_reminders_tone_retro_ring_subtitle,
    ),
    Sunrise(
        id = "sunrise",
        rawRes = R.raw.alarm_sunrise,
        labelRes = R.string.call_reminders_tone_sunrise,
        descriptionRes = R.string.call_reminders_tone_sunrise_subtitle,
    ),

    /**
     * Whatever the phone's own alarm sound is. Not bundled, so [rawRes] is unused — [uri]
     * resolves it from [RingtoneManager] instead. Here because the right answer for some people
     * is "the sound I already wake up to".
     */
    SystemAlarm(
        id = "system_alarm",
        rawRes = 0,
        labelRes = R.string.call_reminders_tone_system,
        descriptionRes = R.string.call_reminders_tone_system_subtitle,
    ),
    ;

    /** Where the player should read this tone from. Null only if the phone has no alarm sound. */
    fun uri(context: Context): Uri? = if (this == SystemAlarm) {
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    } else {
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(RAW_PATH)
            .appendPath(context.resources.getResourceEntryName(rawRes))
            .build()
    }

    companion object {
        private const val RAW_PATH = "raw"

        val DEFAULT = MarimbaRise

        /** Tolerant lookup: an unknown id (an older build, a hand-edited pref) falls back. */
        fun fromId(id: String?): AlarmTone = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
