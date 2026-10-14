/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.model

import kotlinx.serialization.Serializable

/**
 * One call reminder, as the phone knows it.
 *
 * Created from a reminder message in a room (see
 * [io.element.android.features.callreminders.impl.parser.CallReminderParser]) and then owned
 * locally: the alarm, the snoozes and the outcome all live on the device, so the reminder rings
 * on time whether or not the network is reachable at that moment.
 */
@Serializable
data class CallReminder(
    /**
     * Stable identity. The server's own reminder id when the message carried one, otherwise
     * derived from the room, title and due time, so the same message seen twice is one reminder.
     */
    val id: String,
    /** The room the reminder came from, so "Open chat" and the acknowledgement know where to go. */
    val roomId: String,
    val sessionId: String,
    /** What to do, e.g. "Call Elisheva". */
    val title: String,
    /** Who to call, when the message named them separately from the title. */
    val contact: String? = null,
    /** Number to dial. Never dialled automatically — it only pre-fills the dialer. */
    val phone: String? = null,
    /** Why: free text from the reminder, shown on the alarm screen. */
    val notes: String? = null,
    /** When it should ring, as epoch milliseconds. Updated in place by a snooze. */
    val dueAtMillis: Long,
    /** The original due time, kept so a snoozed reminder can still say when it was meant to be. */
    val originalDueAtMillis: Long = dueAtMillis,
    /** Snooze offsets offered on the alarm screen, in minutes. */
    val snoozeOptionsMinutes: List<Int> = DEFAULT_SNOOZE_MINUTES,
    val state: State = State.Scheduled,
    /** How many times it has already re-rung after being left unanswered. */
    val escalationCount: Int = 0,
    /** Epoch millis the outcome was recorded. */
    val resolvedAtMillis: Long? = null,
    /**
     * Epoch millis the **user** pushed this reminder back, as opposed to the app re-arming it
     * because nobody answered. Only a deliberate snooze is worth telling the chat about.
     */
    val snoozedAtMillis: Long? = null,
    /** True once the outcome has been posted back to the room. */
    val acknowledged: Boolean = false,
    /** Ring loudly (default) or gently. */
    val gentle: Boolean = false,
) {
    enum class State {
        /** Waiting for its moment. */
        Scheduled,

        /** Currently ringing, or waiting for the user to deal with it. */
        Ringing,

        /** Pushed back; [dueAtMillis] is the new time. */
        Snoozed,

        /** The user said they had made the call. */
        Done,

        /** The user opened the dialer from the alarm but has not confirmed yet. */
        Called,
    }

    val isOpen: Boolean get() = state == State.Scheduled || state == State.Snoozed || state == State.Ringing

    /**
     * There is an outcome the room has not been told about yet.
     *
     * A user-chosen snooze counts: the settings screen promises the chat is told about "done" and
     * "snoozed" alike, and for a reminder that also reaches WhatsApp, "pushed to 15:20" is the
     * difference between "he is on it" and silence.
     */
    val needsAcknowledgement: Boolean
        get() = !acknowledged &&
            roomId.isNotEmpty() &&
            sessionId.isNotEmpty() &&
            (resolvedAtMillis != null || (state == State.Snoozed && snoozedAtMillis != null))

    /** What to show as the person: an explicit contact, else the title itself. */
    val displayContact: String get() = contact ?: title

    companion object {
        val DEFAULT_SNOOZE_MINUTES = listOf(5, 15, 60)

        /**
         * Identity for a reminder whose message carried no server id: the room plus the due time
         * plus the title. Deterministic, so re-reading the same message never creates a duplicate.
         */
        fun derivedId(roomId: String, dueAtMillis: Long, title: String): String =
            "derived-" + "$roomId|$dueAtMillis|$title".hashCode().toUInt().toString(16)
    }
}
