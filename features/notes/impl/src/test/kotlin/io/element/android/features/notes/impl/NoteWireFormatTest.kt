/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.notes.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.notes.impl.model.ChecklistItem
import io.element.android.features.notes.impl.model.Note
import io.element.android.features.notes.impl.model.NoteColour
import io.element.android.features.notes.impl.parser.NoteWireFormat
import org.junit.Test

/**
 * The note wire format is the contract between every device that has these notes, and between
 * this app and any other Matrix client looking at the room. If it changes silently, notes that
 * are already in the room stop being notes. So every case is pinned here.
 */
class NoteWireFormatTest {
    @Test
    fun `a round trip preserves everything`() {
        val note = Note(
            id = "abc123",
            title = "Groceries",
            body = "Milk for the week,\nand ask about the bread.",
            checklist = listOf(
                ChecklistItem("milk", done = false),
                ChecklistItem("bread", done = true),
            ),
            colour = NoteColour.Butter,
            pinned = true,
            remindAtMillis = 1_760_425_200_000L,
        )

        val encoded = NoteWireFormat.encode(note)
        val decoded = NoteWireFormat.decode(encoded, fallbackId = "ignored")

        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("abc123")
        assertThat(decoded.title).isEqualTo("Groceries")
        assertThat(decoded.body).isEqualTo("Milk for the week,\nand ask about the bread.")
        assertThat(decoded.checklist).isEqualTo(note.checklist)
        assertThat(decoded.colour).isEqualTo(NoteColour.Butter)
        assertThat(decoded.pinned).isTrue()
        assertThat(decoded.remindAtMillis).isEqualTo(note.remindAtMillis)
    }

    @Test
    fun `the encoded form is what a person would expect to read`() {
        val note = Note(
            id = "7f3a",
            title = "Groceries",
            body = "For the week.",
            checklist = listOf(ChecklistItem("milk"), ChecklistItem("bread", done = true)),
            colour = NoteColour.Mint,
            pinned = true,
        )
        assertThat(NoteWireFormat.encode(note)).isEqualTo(
            """
            📝 Groceries
            For the week.
            ☐ milk
            ☑ bread
            🔖 7f3a · mint · pinned
            """.trimIndent()
        )
    }

    @Test
    fun `a plain message is not a note`() {
        assertThat(NoteWireFormat.looksLikeNote("Hello there")).isFalse()
        assertThat(NoteWireFormat.decode("Hello there", fallbackId = "x")).isNull()
    }

    @Test
    fun `a note typed by hand with no metadata still reads, and keeps the fallback id`() {
        val decoded = NoteWireFormat.decode("📝 Shopping\nmilk\n☐ eggs", fallbackId = "evt-9")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("evt-9")
        assertThat(decoded.title).isEqualTo("Shopping")
        assertThat(decoded.body).isEqualTo("milk")
        assertThat(decoded.checklist).containsExactly(ChecklistItem("eggs", done = false))
        assertThat(decoded.colour).isEqualTo(NoteColour.None)
        assertThat(decoded.pinned).isFalse()
    }

    @Test
    fun `an unknown metadata token from a newer build does not lose the note`() {
        val decoded = NoteWireFormat.decode("📝 Thing\nbody\n🔖 id1 · sky · someNewFlag · pinned", fallbackId = "x")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("id1")
        assertThat(decoded.colour).isEqualTo(NoteColour.Sky)
        assertThat(decoded.pinned).isTrue()
    }

    @Test
    fun `a malformed due time is ignored rather than throwing`() {
        val decoded = NoteWireFormat.decode("📝 Thing\n🔖 id1 · due not-a-date", fallbackId = "x")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.remindAtMillis).isNull()
    }

    @Test
    fun `an empty note still encodes and decodes`() {
        val note = Note(id = "e1")
        val decoded = NoteWireFormat.decode(NoteWireFormat.encode(note), fallbackId = "x")
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.id).isEqualTo("e1")
        assertThat(decoded.title).isEmpty()
        assertThat(decoded.body).isEmpty()
        assertThat(decoded.checklist).isEmpty()
    }

    @Test
    fun `encoding derives a title when there is none, the way Supernote did`() {
        val note = Note(id = "d1", body = "Remember to ask the landlord about the heating")
        // 25 characters is "Remember to ask the landl"; trimmed back to the last space that
        // leaves whole words, which is "Remember to ask the".
        assertThat(NoteWireFormat.encode(note).lineSequence().first()).isEqualTo("📝 Remember to ask the")
    }

    @Test
    fun `a derived title never cuts a word in half`() {
        // 25 characters is "Buy aubergines tomorrow m"; the last space keeps three whole words.
        assertThat(Note.deriveTitle("Buy aubergines tomorrow morning")).isEqualTo("Buy aubergines tomorrow")
        assertThat(Note.deriveTitle("Short one")).isEqualTo("Short one")
        assertThat(Note.deriveTitle("")).isEmpty()
        // A single very long word has no space to trim back to, so it is simply cut.
        assertThat(Note.deriveTitle("Donaudampfschifffahrtsgesellschaftskapitaen")).hasLength(25)
    }

    @Test
    fun `a derived title falls back to the first checklist item`() {
        assertThat(Note.deriveTitle("", listOf(ChecklistItem("call the garage")))).isEqualTo("call the garage")
    }

    @Test
    fun `the due time is written in the same format the reminder wire format uses`() {
        // Wed 14 Oct 2026, 09:00 UTC+2 — the exact shape reminder.py's friendly_time() writes.
        val note = Note(id = "t1", title = "x", remindAtMillis = 1_760_425_200_000L)
        val encoded = NoteWireFormat.encode(note)
        assertThat(encoded).contains("· due ")
        val decoded = NoteWireFormat.decode(encoded, fallbackId = "x")
        assertThat(decoded!!.remindAtMillis).isEqualTo(note.remindAtMillis)
    }
}
