/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.callreminders.impl.model.CallReminder
import io.element.android.features.callreminders.impl.parser.CallReminderParser
import org.junit.Test
import java.util.TimeZone

/**
 * The parser is the one piece that has to agree with a program on another machine (the server's
 * reminder.py writes the message these tests read), so it is the piece worth pinning down.
 */
class CallReminderParserTest {
    private val zurich = TimeZone.getTimeZone("Europe/Zurich")
    private val roomId = "!self:chagai.website"
    private val sessionId = "@me:chagai.website"

    // 2026-10-14 15:00 in Europe/Zurich (CEST, UTC+2).
    private val due = 1_791_982_800_000L
    private val justBefore = due - 60_000L

    private val fullMessage = """
        ⏰ Call reminder #42: Call Elisheva
        🕒 Wed 14 Oct 2026, 15:00
        📞 +41791234567
        📝 about Disney+
    """.trimIndent()

    @Test
    fun `a full reminder message is parsed completely`() {
        val reminder = CallReminderParser.fromBody(fullMessage, roomId, sessionId, now = justBefore, timeZone = zurich)
        assertThat(reminder).isNotNull()
        reminder!!
        assertThat(reminder.id).isEqualTo("server-42")
        assertThat(reminder.title).isEqualTo("Call Elisheva")
        assertThat(reminder.contact).isEqualTo("Elisheva")
        assertThat(reminder.phone).isEqualTo("+41791234567")
        assertThat(reminder.notes).isEqualTo("about Disney+")
        assertThat(reminder.dueAtMillis).isEqualTo(due)
        assertThat(reminder.state).isEqualTo(CallReminder.State.Scheduled)
    }

    @Test
    fun `the number and the note are optional`() {
        val body = "⏰ Call reminder: Call the garage\n🕒 Wed 14 Oct 2026, 15:00"
        val reminder = CallReminderParser.fromBody(body, roomId, sessionId, now = justBefore, timeZone = zurich)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.phone).isNull()
        assertThat(reminder.notes).isNull()
        // No server id in the message, so the identity is derived and therefore stable.
        assertThat(reminder.id).startsWith("derived-")
        val again = CallReminderParser.fromBody(body, roomId, sessionId, now = justBefore, timeZone = zurich)
        assertThat(again!!.id).isEqualTo(reminder.id)
    }

    @Test
    fun `an ordinary message is not a reminder`() {
        assertThat(CallReminderParser.looksLikeReminder("see you at 3")).isFalse()
        assertThat(CallReminderParser.fromBody("see you at 3", roomId, sessionId, now = justBefore, timeZone = zurich)).isNull()
    }

    @Test
    fun `a reminder without a time is rejected rather than guessed`() {
        val body = "⏰ Call reminder #7: Call Elisheva\n📞 +41791234567"
        assertThat(CallReminderParser.fromBody(body, roomId, sessionId, now = justBefore, timeZone = zurich)).isNull()
    }

    @Test
    fun `an unparseable time is rejected`() {
        val body = "⏰ Call reminder #7: Call Elisheva\n🕒 sometime next week"
        assertThat(CallReminderParser.fromBody(body, roomId, sessionId, now = justBefore, timeZone = zurich)).isNull()
    }

    @Test
    fun `a reminder from long ago is not resurrected`() {
        val wayLater = due + 48L * 60 * 60 * 1000
        assertThat(CallReminderParser.fromBody(fullMessage, roomId, sessionId, now = wayLater, timeZone = zurich)).isNull()
    }

    @Test
    fun `a reminder whose moment only just passed still rings`() {
        val slightlyLate = due + 10 * 60 * 1000
        assertThat(CallReminderParser.fromBody(fullMessage, roomId, sessionId, now = slightlyLate, timeZone = zurich)).isNotNull()
    }

    @Test
    fun `the structured payload wins where it is available`() {
        val raw = """
            {
              "type": "m.room.message",
              "content": {
                "msgtype": "m.text",
                "body": "⏰ Call reminder #42: Call Elisheva",
                "website.chagai.reminder": {
                  "v": 1,
                  "id": 42,
                  "kind": "call",
                  "title": "Call Elisheva",
                  "due": "2026-10-14T15:00:00+0200",
                  "phone": "+41791234567",
                  "contact": "Elisheva",
                  "notes": "about Disney+",
                  "snooze_options": [10, 30, 120]
                }
              }
            }
        """.trimIndent()
        val reminder = CallReminderParser.fromStructuredContent(raw, roomId, sessionId, now = justBefore)
        assertThat(reminder).isNotNull()
        assertThat(reminder!!.id).isEqualTo("server-42")
        assertThat(reminder.dueAtMillis).isEqualTo(due)
        assertThat(reminder.snoozeOptionsMinutes).containsExactly(10, 30, 120).inOrder()
    }

    @Test
    fun `a structured payload of another kind is ignored`() {
        val raw = """{"content":{"website.chagai.reminder":{"kind":"task","title":"x","due":"2026-10-14T15:00:00+0200"}}}"""
        assertThat(CallReminderParser.fromStructuredContent(raw, roomId, sessionId, now = justBefore)).isNull()
    }

    @Test
    fun `malformed json does not throw`() {
        assertThat(CallReminderParser.fromStructuredContent("not json", roomId, sessionId, now = justBefore)).isNull()
    }

    @Test
    fun `the contact is taken from the title when the message does not name one`() {
        assertThat(CallReminderParser.contactFromTitle("Call Elisheva")).isEqualTo("Elisheva")
        assertThat(CallReminderParser.contactFromTitle("Ring the garage")).isEqualTo("the garage")
        assertThat(CallReminderParser.contactFromTitle("Follow up on the invoice")).isNull()
    }

    @Test
    fun `iso times with either offset style are accepted`() {
        assertThat(CallReminderParser.parseIso8601("2026-10-14T15:00:00+02:00")).isEqualTo(due)
        assertThat(CallReminderParser.parseIso8601("2026-10-14T15:00:00+0200")).isEqualTo(due)
        assertThat(CallReminderParser.parseIso8601("nonsense")).isNull()
    }
}
