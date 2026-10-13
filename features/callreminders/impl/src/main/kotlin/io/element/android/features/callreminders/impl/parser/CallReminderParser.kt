/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.callreminders.impl.parser

import io.element.android.features.callreminders.impl.model.CallReminder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Turns a reminder message into a [CallReminder].
 *
 * There are two ways in, because there are two ways the app sees a message.
 *
 * 1. [fromStructuredContent] reads the `website.chagai.reminder` object the server attaches to the
 *    event. It is exact — it carries the server's reminder id and an ISO-8601 due time with a
 *    real offset — but it is only reachable where the raw event JSON is, i.e. from a timeline.
 * 2. [fromBody] reads the visible text of the message. Every path the app has — room list, push
 *    notification, timeline — carries the body, so this is the one that actually runs most of the
 *    time. It is why the server writes reminders in a fixed shape:
 *
 *        ⏰ Call reminder #42: Call Elisheva
 *        🕒 Tue 14 Oct 2026, 15:00
 *        📞 +41791234567
 *        📝 about Disney+
 *
 *    which reads as a perfectly ordinary message on WhatsApp while still parsing unambiguously.
 *
 * Only the first two lines are required; a reminder with no number or no note is fine.
 */
object CallReminderParser {
    const val STRUCTURED_FIELD = "website.chagai.reminder"
    const val ACK_FIELD = "website.chagai.reminder_ack"

    private val TITLE_RE = Regex("""^\s*⏰\s*Call reminder(?:\s*#(\d+))?\s*:\s*(.+?)\s*$""")
    private val WHEN_RE = Regex("""^\s*🕒\s*(.+?)\s*$""")
    private val PHONE_RE = Regex("""^\s*📞\s*(.+?)\s*$""")
    private val NOTES_RE = Regex("""^\s*📝\s*(.+?)\s*$""")

    /** The shape the server writes, and the only one [fromBody] accepts. */
    private const val WHEN_PATTERN = "EEE d MMM yyyy, HH:mm"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Cheap test used before doing any real work, so the observer can ignore the overwhelming
     * majority of messages without allocating anything.
     */
    fun looksLikeReminder(body: String?): Boolean = body != null && body.contains("⏰ Call reminder")

    fun fromBody(
        body: String,
        roomId: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): CallReminder? {
        var serverId: String? = null
        var title: String? = null
        var dueAt: Long? = null
        var phone: String? = null
        var notes: String? = null

        for (line in body.lineSequence()) {
            TITLE_RE.matchEntire(line)?.let {
                serverId = it.groupValues[1].takeIf(String::isNotEmpty)
                title = it.groupValues[2]
                return@let
            }
            if (title == null) continue
            WHEN_RE.matchEntire(line)?.let { dueAt = parseWhen(it.groupValues[1], timeZone) }
            PHONE_RE.matchEntire(line)?.let { phone = it.groupValues[1] }
            NOTES_RE.matchEntire(line)?.let { notes = it.groupValues[1] }
        }

        val resolvedTitle = title ?: return null
        val resolvedDue = dueAt ?: return null
        return CallReminder(
            id = serverId?.let { "server-$it" } ?: CallReminder.derivedId(roomId, resolvedDue, resolvedTitle),
            roomId = roomId,
            sessionId = sessionId,
            title = resolvedTitle,
            contact = contactFromTitle(resolvedTitle),
            phone = phone,
            notes = notes,
            dueAtMillis = resolvedDue,
            originalDueAtMillis = resolvedDue,
        ).takeIf { it.dueAtMillis > now - STALE_TOLERANCE_MILLIS }
    }

    /**
     * Reads the structured payload out of a raw event's `content`. Returns null for anything that
     * is not a call reminder, including a well-formed reminder of some other kind.
     */
    fun fromStructuredContent(
        rawEventJson: String,
        roomId: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): CallReminder? {
        val payload = runCatching {
            json.parseToJsonElement(rawEventJson)
                .jsonObject["content"]?.jsonObject
                ?.get(STRUCTURED_FIELD)?.jsonObject
        }.onFailure { Timber.w(it, "Could not read the reminder payload") }.getOrNull() ?: return null

        val kind = payload["kind"]?.jsonPrimitive?.contentOrNullSafe()
        if (kind != "call") return null
        val title = payload["title"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val due = payload["due"]?.jsonPrimitive?.contentOrNullSafe()?.let(::parseIso8601) ?: return null
        val serverId = payload["id"]?.jsonPrimitive?.contentOrNullSafe()
        val snooze = runCatching {
            payload["snooze_options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: CallReminder.DEFAULT_SNOOZE_MINUTES

        return CallReminder(
            id = serverId?.let { "server-$it" } ?: CallReminder.derivedId(roomId, due, title),
            roomId = roomId,
            sessionId = sessionId,
            title = title,
            contact = payload["contact"]?.jsonPrimitive?.contentOrNullSafe() ?: contactFromTitle(title),
            phone = payload["phone"]?.jsonPrimitive?.contentOrNullSafe(),
            notes = payload["notes"]?.jsonPrimitive?.contentOrNullSafe(),
            dueAtMillis = due,
            originalDueAtMillis = due,
            snoozeOptionsMinutes = snooze,
        ).takeIf { it.dueAtMillis > now - STALE_TOLERANCE_MILLIS }
    }

    /** "Call Elisheva" -> "Elisheva". Falls back to the whole title when it is not of that shape. */
    internal fun contactFromTitle(title: String): String? =
        Regex("""^\s*(?:call|ring|phone)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(title)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    internal fun parseWhen(value: String, timeZone: TimeZone): Long? {
        // Locale.ENGLISH on purpose: the server always writes English day and month names, so the
        // phone's own locale must not change how this is read.
        val format = SimpleDateFormat(WHEN_PATTERN, Locale.ENGLISH).apply {
            this.timeZone = timeZone
            isLenient = false
        }
        return runCatching { format.parse(value)?.time }
            .onFailure { Timber.d("Unparseable reminder time: %s", value) }
            .getOrNull()
    }

    internal fun parseIso8601(value: String): Long? {
        // Accept both +02:00 and +0200, which different producers write differently.
        val normalised = value.trim().replace(Regex("""([+-]\d{2}):(\d{2})$"""), "$1$2")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mmZ", "yyyy-MM-dd'T'HH:mm:ss")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).apply { isLenient = false }.parse(normalised)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        Timber.d("Unparseable ISO time: %s", value)
        return null
    }

    /**
     * A reminder whose time has only just passed is still worth ringing — the phone may have been
     * off, or the message delayed. Anything older than this is history, not an alarm.
     */
    private const val STALE_TOLERANCE_MILLIS = 60 * 60 * 1000L
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }
