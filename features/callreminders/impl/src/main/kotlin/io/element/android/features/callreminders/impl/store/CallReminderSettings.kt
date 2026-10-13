/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.store

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The handful of choices the call-reminder alarm offers.
 *
 * Plain SharedPreferences: these are read from broadcast receivers at alarm time, where a
 * suspending DataStore read would be the wrong shape, and there are only a few of them.
 */
class CallReminderSettings private constructor(private val prefs: SharedPreferences) {
    /** Minutes offered on the alarm screen's snooze row. */
    var snoozeOptionsMinutes: List<Int>
        get() = prefs.getString(KEY_SNOOZE, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SNOOZE
        set(value) = prefs.edit { putString(KEY_SNOOZE, value.joinToString(",")) }

    /**
     * Ring on the alarm audio stream rather than the notification stream. On by default: it is
     * the whole point — the alarm stream is what lets a reminder cut through a silenced phone,
     * exactly like a clock alarm does.
     */
    var ringInSilentMode: Boolean
        get() = prefs.getBoolean(KEY_SILENT_MODE, true)
        set(value) = prefs.edit { putBoolean(KEY_SILENT_MODE, value) }

    /** Ring again if the alarm is left unanswered. */
    var escalationEnabled: Boolean
        get() = prefs.getBoolean(KEY_ESCALATION, true)
        set(value) = prefs.edit { putBoolean(KEY_ESCALATION, value) }

    /** How long the alarm rings before it gives up and waits to re-ring. */
    var autoSilenceMinutes: Int
        get() = prefs.getInt(KEY_AUTO_SILENCE, DEFAULT_AUTO_SILENCE_MINUTES)
        set(value) = prefs.edit { putInt(KEY_AUTO_SILENCE, value.coerceIn(1, 30)) }

    /** How many times it re-rings before it stops asking. */
    var maxEscalations: Int
        get() = prefs.getInt(KEY_MAX_ESCALATIONS, DEFAULT_MAX_ESCALATIONS)
        set(value) = prefs.edit { putInt(KEY_MAX_ESCALATIONS, value.coerceIn(0, 10)) }

    /** Ring gently (a softer tone, no vibration escalation) instead of like a klaxon. */
    var gentleByDefault: Boolean
        get() = prefs.getBoolean(KEY_GENTLE, false)
        set(value) = prefs.edit { putBoolean(KEY_GENTLE, value) }

    /** Tell the room (and so WhatsApp) what happened to each reminder. */
    var sendAcknowledgements: Boolean
        get() = prefs.getBoolean(KEY_ACK, true)
        set(value) = prefs.edit { putBoolean(KEY_ACK, value) }

    /** Keep a quiet notification showing the next call reminder. */
    var showNextReminderNotification: Boolean
        get() = prefs.getBoolean(KEY_NEXT_NOTIFICATION, true)
        set(value) = prefs.edit { putBoolean(KEY_NEXT_NOTIFICATION, value) }

    companion object {
        private const val PREFS_NAME = "call_reminders"
        private const val KEY_SNOOZE = "snooze_options"
        private const val KEY_SILENT_MODE = "ring_in_silent_mode"
        private const val KEY_ESCALATION = "escalation_enabled"
        private const val KEY_AUTO_SILENCE = "auto_silence_minutes"
        private const val KEY_MAX_ESCALATIONS = "max_escalations"
        private const val KEY_GENTLE = "gentle_by_default"
        private const val KEY_ACK = "send_acknowledgements"
        private const val KEY_NEXT_NOTIFICATION = "show_next_reminder"

        val DEFAULT_SNOOZE = listOf(5, 15, 60)
        const val DEFAULT_AUTO_SILENCE_MINUTES = 3
        const val DEFAULT_MAX_ESCALATIONS = 3

        fun get(context: Context): CallReminderSettings =
            CallReminderSettings(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
