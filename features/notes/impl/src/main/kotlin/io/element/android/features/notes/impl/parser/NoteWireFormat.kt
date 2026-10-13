/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl.parser

import io.element.android.features.notes.impl.model.ChecklistItem
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.model.NoteColour
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How a note is written into a Matrix message, and read back out of one.
 *
 * ### Why the message body, and not a custom event field
 *
 * The obvious design is a custom event type, or an extra JSON field on an `m.room.message`. It is
 * not available: the Kotlin API this fork builds on exposes `sendMessage(body, htmlBody, …)` and
 * nothing lower, so a custom field could be *read* from a timeline but never written. The same
 * constraint shaped the call-reminder format, and the same answer applies — **the visible body is
 * the wire format**.
 *
 * That turns out to be the better design anyway, for three reasons:
 *
 * 1. **A note is readable everywhere.** In Element, in a web client, in the WhatsApp bridge —
 *    it is just a message that looks like a note. A custom event type would show as "unsupported
 *    event" in every client but this one.
 * 2. **It degrades well.** An old build, or a human typing by hand, produces something this
 *    parser still understands; an unknown colour or a malformed time is ignored rather than
 *    losing the note.
 * 3. **Editing is free.** Changing a note is an `m.replace` on its message, which every client
 *    already understands as an edit, and which gives version history with no second data
 *    structure to maintain.
 *
 * ### The format
 *
 * ```
 * 📝 Groceries
 * Milk for the week, and ask about the bread.
 * ☐ milk
 * ☑ bread
 * ☐ eggs
 * 🔖 3f9c2a · butter · pinned · due Wed 14 Oct 2026, 09:00
 * ```
 *
 * * **Line 1** is `📝 ` + the title. Its presence is what makes a message a note.
 * * **Lines beginning `☐ ` or `☑ `** are checklist items, unticked and ticked, in order.
 * * **Everything else between** is the body.
 * * **The last line**, beginning `🔖 `, is the metadata: the note id first, then any of the
 *   colour, `pinned`, and `due <time>`, separated by ` · `. Unknown parts are ignored.
 *
 * The time format is deliberately **the same one the reminder wire format uses** —
 * `EEE d MMM yyyy, HH:mm` in English with `Locale.UK` — so there is one time format across the
 * whole fork rather than two that can drift apart. [NoteWireFormatTest] pins every case.
 */
object NoteWireFormat {
    const val TITLE_MARKER = "📝 "
    const val META_MARKER = "🔖 "
    const val UNCHECKED = "☐ "
    const val CHECKED = "☑ "

    private const val SEPARATOR = " · "
    private const val PINNED = "pinned"
    private const val DUE = "due "

    /**
     * Cheap test used before parsing anything, including on every message the room produces.
     * Must stay cheap: it runs for every event in the notes room's timeline.
     */
    fun looksLikeNote(body: String?): Boolean = body != null && body.startsWith(TITLE_MARKER)

    /** Writes [note] as a message body. The inverse of [decode]. */
    fun encode(note: Note): String = buildString {
        append(TITLE_MARKER).append(note.title.ifBlank { Note.deriveTitle(note.body, note.checklist) })
        note.body.trimEnd().takeIf { it.isNotBlank() }?.let { append('\n').append(it) }
        note.checklist.filter { it.text.isNotBlank() }.forEach { item ->
            append('\n').append(if (item.done) CHECKED else UNCHECKED).append(item.text.trim())
        }
        append('\n').append(META_MARKER).append(note.id)
        if (note.colour != NoteColour.None) append(SEPARATOR).append(note.colour.id)
        if (note.pinned) append(SEPARATOR).append(PINNED)
        note.remindAtMillis?.let { append(SEPARATOR).append(DUE).append(formatTime(it)) }
    }

    /**
     * Reads a note out of a message body, or returns null if it is not one.
     *
     * [fallbackId] is used when the message carries no id — a note typed by hand in another
     * client, say. Deriving it from something stable (the event id) rather than generating a
     * fresh one each time is what stops the same message becoming a new note on every sync.
     */
    fun decode(
        body: String,
        fallbackId: String,
        eventId: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Note? {
        if (!looksLikeNote(body)) return null
        val lines = body.lines()
        val title = lines.first().removePrefix(TITLE_MARKER).trim()

        val metaLine = lines.lastOrNull { it.startsWith(META_MARKER) }
        val contentLines = lines.drop(1).filterNot { it === metaLine }

        val checklist = mutableListOf<ChecklistItem>()
        val bodyLines = mutableListOf<String>()
        for (line in contentLines) {
            when {
                line.startsWith(CHECKED) -> checklist += ChecklistItem(line.removePrefix(CHECKED).trim(), done = true)
                line.startsWith(UNCHECKED) -> checklist += ChecklistItem(line.removePrefix(UNCHECKED).trim(), done = false)
                else -> bodyLines += line
            }
        }

        var id = fallbackId
        var colour = NoteColour.None
        var pinned = false
        var remindAt: Long? = null
        metaLine?.removePrefix(META_MARKER)?.split(SEPARATOR)?.forEachIndexed { index, rawPart ->
            val part = rawPart.trim()
            when {
                index == 0 && part.isNotEmpty() -> id = part
                part == PINNED -> pinned = true
                part.startsWith(DUE) -> remindAt = parseTime(part.removePrefix(DUE).trim())
                NoteColour.fromId(part) != NoteColour.None -> colour = NoteColour.fromId(part)
                else -> Unit // an unknown token from a newer build: ignore it, keep the note
            }
        }

        return Note(
            id = id,
            eventId = eventId,
            title = title,
            body = bodyLines.joinToString("\n").trim(),
            checklist = checklist,
            colour = colour,
            pinned = pinned,
            remindAtMillis = remindAt,
            createdAtMillis = timestampMillis,
            updatedAtMillis = timestampMillis,
            pending = null,
        )
    }

    /**
     * The same format the reminder wire format writes, so a due time reads identically wherever
     * it appears. `Locale.UK` and not the device locale: this string travels between devices and
     * must parse on all of them.
     */
    fun formatTime(millis: Long): String = wireFormat().format(Date(millis))

    private fun parseTime(value: String): Long? = runCatching { wireFormat().parse(value)?.time }.getOrNull()

    /** A fresh instance per call: [SimpleDateFormat] is not thread-safe and this is called from several. */
    private fun wireFormat() = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK)
}
