/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.session.CallReminderAcknowledger
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The acknowledgement line is a wire format.
 *
 * `matrix-os`' `reminder-ack-watch.py` reads these back out of the room to record what the phone
 * did, so the shape of this string is a contract between two codebases. Change one, change both.
 */
class CallReminderAcknowledgementTest {
    private fun at(hhmm: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-10-14 $hhmm")!!.time

    private fun reminder(
        id: String = "server-42",
        state: CallReminder.State = CallReminder.State.Done,
        resolvedAt: Long? = at("15:02"),
        snoozedAt: Long? = null,
        dueAt: Long = at("15:00"),
    ) = CallReminder(
        id = id,
        roomId = "!room:chagai.website",
        sessionId = "@me:chagai.website",
        title = "Call Elisheva",
        dueAtMillis = dueAt,
        state = state,
        resolvedAtMillis = resolvedAt,
        snoozedAtMillis = snoozedAt,
    )

    @Test
    fun `done reads as the server expects`() {
        assertThat(CallReminderAcknowledger.acknowledgementText(reminder()))
            .isEqualTo("✅ Done 15:02 — Call reminder #42")
    }

    @Test
    fun `called reads as the server expects`() {
        val called = reminder(state = CallReminder.State.Called)
        assertThat(CallReminderAcknowledger.acknowledgementText(called))
            .isEqualTo("📞 Called 15:02 — Call reminder #42")
    }

    @Test
    fun `a snooze the user chose names the new time`() {
        val snoozed = reminder(
            state = CallReminder.State.Snoozed,
            resolvedAt = null,
            snoozedAt = at("15:02"),
            dueAt = at("15:20"),
        )
        assertThat(CallReminderAcknowledger.acknowledgementText(snoozed))
            .isEqualTo("⏰ Snoozed to 15:20 — Call reminder #42")
    }

    @Test
    fun `the app re-arming an unanswered reminder is not an acknowledgement`() {
        // Escalation moves a reminder to Snoozed without stamping snoozedAtMillis. That is the
        // app nagging, not the user doing something, and the chat must not be told about it.
        val escalated = reminder(state = CallReminder.State.Snoozed, resolvedAt = null, snoozedAt = null)
        assertThat(CallReminderAcknowledger.acknowledgementText(escalated)).isNull()
        assertThat(escalated.needsAcknowledgement).isFalse()
    }

    @Test
    fun `a reminder with no server id carries no reference`() {
        val local = reminder(id = "derived-abc123")
        assertThat(CallReminderAcknowledger.acknowledgementText(local))
            .isEqualTo("✅ Done 15:02 — Call reminder")
    }

    @Test
    fun `a note reminder is never acknowledged — it has no room to speak to`() {
        val note = reminder(id = "note-abc").copy(roomId = "", sessionId = "")
        assertThat(note.needsAcknowledgement).isFalse()
    }

    @Test
    fun `an outcome that has already been posted is not posted again`() {
        assertThat(reminder().copy(acknowledged = true).needsAcknowledgement).isFalse()
        assertThat(reminder().needsAcknowledgement).isTrue()
    }

    @Test
    fun `a reminder still waiting to ring has nothing to say`() {
        val waiting = reminder(state = CallReminder.State.Scheduled, resolvedAt = null)
        assertThat(waiting.needsAcknowledgement).isFalse()
    }
}
